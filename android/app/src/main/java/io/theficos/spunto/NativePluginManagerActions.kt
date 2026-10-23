package io.theficos.spunto

import android.content.res.Resources

import io.theficos.spunto.data.sync.ApiException
import io.theficos.spunto.data.sync.AuthConfig
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*

internal interface NativePluginBackend {
 suspend fun catalog():JsonObject
 suspend fun manifest():JsonObject
 suspend fun ai():JsonObject
 suspend fun settings(id:String):JsonObject
 suspend fun toggle(id:String,on:Boolean,permissions:List<String>):JsonObject
}
internal data class PluginManagerSnapshot(val catalog:JsonObject,val manifest:JsonObject,val epoch:String="")
internal sealed interface PluginToggleResult {
 data class Applied(val row:JsonObject,val refreshFailed:Boolean):PluginToggleResult
 data object ReviewPermissions:PluginToggleResult
 data class Failed(val code:String):PluginToggleResult
}
internal data class PluginPageState(val id:String?=null,val page:NativePluginPage=NativePluginPage(),val loading:Boolean=false,val error:String="")

/** Requests and their UI receipts belong to the exact owner credential and current attempt. */
internal class NativePluginManagerActions(
 private val resources:Resources,
 private val scope:CoroutineScope,
 private val owner:()->AuthConfig?,
 private val online:()->Boolean,
 private val epoch:()->String,
 private val backend:(AuthConfig)->NativePluginBackend,
 private val current:()->NativePluginCatalog,
 private val publish:suspend (AuthConfig,PluginManagerSnapshot)->Boolean,
 private val acknowledge:suspend (AuthConfig,String,JsonObject)->Boolean,
 private val readPage:(AuthConfig,NativePluginFacts)->NativePluginPage,
 private val cachePage:(AuthConfig,NativePluginFacts,NativePluginPage)->Unit,
 private val publishAi:(AuthConfig,JsonObject)->Unit,
 private val report:(String)->Unit,
 private val now:()->Long={System.currentTimeMillis()},
) {
 val busy=MutableStateFlow(false)
 val undo=MutableStateFlow<PluginUndo?>(null)
 val page=MutableStateFlow(PluginPageState())
 private var generation=0L
 private var refreshJob:Job?=null
 private var toggleJob:Job?=null
 private var pageGeneration=0L
 private var pageJob:Job?=null
 private var expiry:Job?=null
 private fun owns(config:AuthConfig,attempt:Long)=owner()==config && generation==attempt
 private suspend fun snapshot(api:NativePluginBackend,expectedEpoch:String):PluginManagerSnapshot {
  repeat(2) {
   val manifest=api.manifest();val catalog=api.catalog()
   if(manifest.pluginLong("plugins_rev")!=null && manifest.pluginLong("plugins_rev")==catalog.pluginLong("plugins_rev")) return PluginManagerSnapshot(catalog,manifest,expectedEpoch)
  }
  throw ApiException(409,"plugin_snapshot_changed")
 }
 fun invalidate() {
  generation++;refreshJob?.cancel();toggleJob?.cancel();busy.value=false;undo.value=null;expiry?.cancel();closePage()
 }
 fun refresh():Job? {
  val config=owner() ?: return null
  if(!online() || busy.value) return null
  val expectedEpoch=epoch();val attempt=++generation;refreshJob?.cancel()
  return scope.launch {
   try {
    val api=backend(config);val snapshot=snapshot(api,expectedEpoch);ensureActive()
    if(owns(config,attempt) && epoch()==expectedEpoch) publish(config,snapshot)
    val ai=api.ai();ensureActive();if(owns(config,attempt) && epoch()==expectedEpoch) publishAi(config,ai)
   } catch(cancelled:CancellationException) {throw cancelled}
   catch(error:Exception) {if(owns(config,attempt)) report(errorCode(error))}
  }.also {refreshJob=it}
 }
 fun toggle(id:String,on:Boolean,accepted:List<String>?=null,requiredUndo:PluginUndo?=null,onComplete:(PluginToggleResult)->Unit={}):Job? {
  val config=owner() ?: return null
  if(!online() || busy.value) return null
  val plugin=current().rows.firstOrNull {it.id==id} ?: return null
  if(if(on) !plugin.canEnable else !plugin.canDisable) return null
  val permissions=plugin.permissions ?: return null
  if(on && (accepted==null || accepted.size!=accepted.distinct().size || accepted.toSet()!=permissions.toSet())) {onComplete(PluginToggleResult.ReviewPermissions);return null}
  val expectedEpoch=epoch();val attempt=++generation;refreshJob?.cancel();busy.value=true;undo.value=null;expiry?.cancel()
  return scope.launch {
   try {
    val api=backend(config)
    if(requiredUndo!=null) {
     val latest=current().rows.firstOrNull {it.id==id}
     if(latest==null || undoPermissions(requiredUndo,latest,config.serverUrl.trimEnd('/'),now())==null) {if(owns(config,attempt)) onComplete(PluginToggleResult.ReviewPermissions);return@launch}
    }
    val row=api.toggle(id,on,if(on) permissions else emptyList());ensureActive()
    if(!owns(config,attempt)) return@launch
    require(row.pluginText("id")==id && row.pluginText("switch") in setOf("on","off") && row.pluginLong("plugins_rev")!=null) {resources.getString(R.string.settings_the_server_did_not_acknowledge_this_switch)}
    if(epoch()!=expectedEpoch || !owns(config,attempt)) return@launch
    if(!acknowledge(config,expectedEpoch,row)) {if(owns(config,attempt)) onComplete(PluginToggleResult.Failed(resources.getString(R.string.settings_plugin_facts_changed_refresh_before_trying_again)));return@launch}
    if(!owns(config,attempt) || epoch()!=expectedEpoch) return@launch
    if(!on && row.pluginText("switch")=="off" && NativePluginFacts(row).canEnable) {
     val receipt=PluginUndo(id,config.serverUrl.trimEnd('/'),permissions,plugin.granted.orEmpty().toSet(),now()+7000)
     undo.value=receipt;expiry=scope.launch {delay(7000);if(undo.value===receipt) undo.value=null}
    }
    if(row.pluginText("switch")!=(if(on) "on" else "off")) report(resources.getString(R.string.settings_the_plugin_changed_on_the_server_its))
    var refreshFailed=false
    try {val snapshot=snapshot(api,expectedEpoch);ensureActive();if(owns(config,attempt) && epoch()==expectedEpoch) publish(config,snapshot)}
    catch(cancelled:CancellationException) {throw cancelled}
    catch(error:Exception) {refreshFailed=true;if(owns(config,attempt)) report(resources.getString(R.string.settings_switch_saved_refresh_unavailable))}
    if(owns(config,attempt) && epoch()==expectedEpoch) onComplete(PluginToggleResult.Applied(row,refreshFailed))
   } catch(cancelled:CancellationException) {throw cancelled}
   catch(error:Exception) {
    if(owns(config,attempt)) {
     if(error is ApiException && error.status==409) {
      try {val snapshot=snapshot(backend(config),expectedEpoch);ensureActive();if(owns(config,attempt) && epoch()==expectedEpoch) publish(config,snapshot)}
      catch(cancelled:CancellationException) {throw cancelled}
      catch(refreshError:Exception) {if(owns(config,attempt)) report(errorCode(refreshError))}
     }
     if(owns(config,attempt) && epoch()==expectedEpoch) {report(errorCode(error));onComplete(if(error is ApiException && error.code=="permissions_changed") PluginToggleResult.ReviewPermissions else PluginToggleResult.Failed(errorCode(error)))}
    }
   } finally {if(owns(config,attempt)) busy.value=false}
  }.also {toggleJob=it}
 }
 fun undo(onReview:(String)->Unit) {
  val receipt=undo.value ?: return
  val config=owner() ?: return
  val plugin=current().rows.firstOrNull {it.id==receipt.pluginId} ?: return
  if(now()>=receipt.expiresAt || receipt.server!=config.serverUrl.trimEnd('/') || plugin.removed || plugin.pin!=EnvironmentPin.Unmanaged || !online()) {undo.value=null;return}
  val accepted=undoPermissions(receipt,plugin,config.serverUrl.trimEnd('/'),now())
  undo.value=null
  if(accepted==null) {if(plugin.canEnable) onReview(plugin.id)} else toggle(plugin.id,true,accepted,requiredUndo=receipt) {if(it==PluginToggleResult.ReviewPermissions) onReview(plugin.id)}
 }
 fun select(id:String) {
  closePage()
  val config=owner() ?: return
  val plugin=current().rows.firstOrNull {it.id==id} ?: return
  page.value=PluginPageState(id,if(plugin.removed) NativePluginPage() else readPage(config,plugin))
  if(!online() || !plugin.valid || plugin.removed) return
  val expectedEpoch=epoch();val attempt=pageGeneration;page.value=page.value.copy(loading=true)
  pageJob=scope.launch {
   fun ownsPage()=owner()==config && epoch()==expectedEpoch && pageGeneration==attempt && page.value.id==id && current().rows.firstOrNull {it.id==id}?.raw?.let {it.pluginText("version")==plugin.raw.pluginText("version") && it.pluginText("api")==plugin.raw.pluginText("api") && it.pluginText("state")!="removed"}==true
   try {
    val result=safePluginPage(backend(config).settings(id));ensureActive()
    if(ownsPage()) {cachePage(config,plugin,result);page.value=PluginPageState(id,result)}
   } catch(cancelled:CancellationException) {throw cancelled}
   catch(error:Exception) {if(ownsPage()) page.value=page.value.copy(error=errorCode(error))}
   finally {if(ownsPage()) page.value=page.value.copy(loading=false)}
  }
 }
 fun closePage() {pageGeneration++;pageJob?.cancel();pageJob=null;page.value=PluginPageState()}
 private fun errorCode(error:Exception)=if(error is ApiException) error.detail.ifBlank {resources.getString(R.string.settings_the_server_couldn_t_update_plugins_try)} else resources.getString(R.string.settings_couldn_t_reach_your_server_cached_plugin)
}
