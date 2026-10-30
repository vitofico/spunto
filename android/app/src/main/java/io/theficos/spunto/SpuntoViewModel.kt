package io.theficos.spunto

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import io.theficos.spunto.core.model.*
import io.theficos.spunto.data.local.NoteEntity
import io.theficos.spunto.data.local.RevisionEntity
import io.theficos.spunto.data.local.TimelineSpec
import io.theficos.spunto.core.plugins.*
import io.theficos.spunto.data.sync.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*

class SpuntoViewModel(application:Application):AndroidViewModel(application) {
 val container=(application as SpuntoApplication).container.current
 val repository=container.repository
 private val historyRepository by lazy { HistoryRepository(repository,container.auth) }
 val notes=repository.notes.stateIn(viewModelScope,SharingStarted.WhileSubscribed(5000),emptyList())
 val labels=repository.labels().stateIn(viewModelScope,SharingStarted.WhileSubscribed(5000),emptyList())
 val noteCount=repository.dao.noteCount().stateIn(viewModelScope,SharingStarted.WhileSubscribed(5000),null)
 val outputs=repository.outputs().stateIn(viewModelScope,SharingStarted.WhileSubscribed(5000),emptyList())
 val pending=repository.pendingCount.stateIn(viewModelScope,SharingStarted.WhileSubscribed(5000),0)
 private val keptConflicts=MutableStateFlow(container.preferences.getStringSet("conflicts-kept",null).orEmpty().toSet())
 /** Conflicted copies still waiting for a decision on this phone; "Keep both" settles one here only. */
 val conflicts=combine(repository.conflicts,keptConflicts) { rows,kept -> rows.filter { it.id !in kept } }.stateIn(viewModelScope,SharingStarted.WhileSubscribed(5000),emptyList())
 /** A timeline long-press asked for this note's history; the note page opens it once. */
 val historyFor=MutableStateFlow<String?>(null)
 fun keepBothConflict(id:String) { keptConflicts.value+=id;container.preferences.edit().putStringSet("conflicts-kept",keptConflicts.value).apply() }
 private val pages=object:LinkedHashMap<TimelineSpec,Pair<Job,Flow<PagingData<NoteEntity>>>>(8,.75f,true) {
  override fun removeEldestEntry(eldest:MutableMap.MutableEntry<TimelineSpec,Pair<Job,Flow<PagingData<NoteEntity>>>>)=(size>4).also { if(it) eldest.value.first.cancel() }
 }
 /** Lists reopen at their scroll position after a note or a rotation because loaded pages outlive the screen; the four latest filters stay cached. */
 fun paging(spec:TimelineSpec):Flow<PagingData<NoteEntity>> {
  val day=spec.copy(now=spec.now-Math.floorMod(spec.now,86_400_000L)) // filters resolve "days" against UTC midnight only
  return pages.getOrPut(day) { val job=Job(viewModelScope.coroutineContext[Job]);job to repository.paging(day).cachedIn(viewModelScope+job) }.second
 }
 val auth=container.auth.config.map {it?.takeIf {config ->config.replicaId==container.replicaId}}.stateIn(viewModelScope,SharingStarted.Eagerly,null)
 val status=container.sync.state
 val online=combine(auth,status,container.connected) { auth,status,connected -> auth!=null && connected && status !is SyncStatus.Waiting && status!=SyncStatus.NeedsSignIn }.stateIn(viewModelScope,SharingStarted.Eagerly,false)
 val message=MutableStateFlow("")
 val manifest=MutableStateFlow(container.manifest.value)
 val catalog=MutableStateFlow(JsonObject(emptyMap()))
 val pluginAi=MutableStateFlow(JsonObject(emptyMap()))
 private val pluginEpoch=MutableStateFlow("")
 private var pluginConfig:AuthConfig?=null
 val reauthenticate=MutableStateFlow(false)
 val remote=MutableStateFlow(JsonObject(emptyMap()))
 init {
  viewModelScope.launch {status.collect {if(it==SyncStatus.NeedsSignIn) reauthenticate.value=true}}
  viewModelScope.launch {auth.collect {config ->
   if(config!=pluginConfig) {
    pluginManager.invalidate();pluginConfig=config;catalog.value=JsonObject(emptyMap());pluginAi.value=JsonObject(emptyMap());pluginEpoch.value=""
    if(config!=null) {
     if(container.preferences.getString("native-plugin-server",null)==config.serverUrl.trimEnd('/')) {
      catalog.value=payload(container.preferences.getString("plugin-catalog","{}")!!)
      pluginAi.value=payload(container.preferences.getString("native-plugin-ai","{}")!!)
     }
    }
    publishPluginReplica(container.database.dao().state());if(config!=null) synchronize()
   }
  }}
  viewModelScope.launch {container.database.dao().observeState().collect(::publishPluginReplica)}
 }
 internal val pluginManager:NativePluginManagerActions by lazy {NativePluginManagerActions(
  container.context.resources,viewModelScope,{auth.value},{online.value},{pluginEpoch.value},
  {config -> object:NativePluginBackend {
   private val api=ApiClient(config)
   private suspend fun <T> read(block:suspend ()->T):T=try {block()} catch(error:ApiException) {if(error.status==401 && auth.value==config) reauthenticate.value=true;throw error}
   override suspend fun catalog()=read {api.get("/api/v1/plugins")}
   override suspend fun manifest()=read {api.manifest()}
   override suspend fun ai()=read {api.ai()}
   override suspend fun settings(id:String)=read {api.get("/api/v1/plugins/$id/settings")}
   override suspend fun toggle(id:String,on:Boolean,permissions:List<String>)=read {api.post("/api/v1/plugins/$id/${if(on) "enable" else "disable"}",if(on) buildJsonObject {put("permissions",JsonArray(permissions.map(::JsonPrimitive)))} else JsonObject(emptyMap()))}
  }},
  {nativePluginCatalog(catalog.value,manifest.value)},::publishPluginSnapshot,::acknowledgePlugin,
  {config,plugin -> safePluginPage(payload(container.preferences.getString(pluginPageCacheKey(config.serverUrl.trimEnd('/'),plugin),"{}")!!)).takeIf {it.schema.isNotEmpty()} ?: catalogPluginPage(plugin)},
  {config,plugin,page -> if(auth.value==config) container.preferences.edit().putString(pluginPageCacheKey(config.serverUrl.trimEnd('/'),plugin),page.json().toString()).apply()},
  {config,ai -> if(auth.value==config) {
   val profiles=(ai["profiles"] as? JsonArray).orEmpty().mapNotNull {it as? JsonObject}.map {JsonObject(it.filterKeys {key -> key in setOf("name","base_url","chat_model","embed_model","leaves_network")})}
   pluginAi.value=buildJsonObject {put("profiles",JsonArray(profiles));put("bindings",ai["bindings"] as? JsonObject ?: JsonObject(emptyMap()))}
   container.preferences.edit().putString("native-plugin-ai",pluginAi.value.toString()).putString("native-plugin-server",config.serverUrl.trimEnd('/')).apply()
  }},
  {error -> message.value=error;if(error.contains("401")) reauthenticate.value=true}
 )}
 private suspend fun publishPluginReplica(emitted:io.theficos.spunto.data.local.SyncStateEntity?) {
  // Read the authority again; emitted rows may have waited behind a restore or credential change.
  val config=container.auth.config.first()
  val committed=readCommittedPluginPublication(repository,config) {container.auth.config.first()} ?: return
  container.manifest.withCurrentPublication {
   processPluginPublications.withCurrent {currentOwner,publication ->
    if(currentOwner==committed.owner && auth.value==config) {
     pluginEpoch.value=committed.owner.epoch
     manifest.value=container.manifest.value;catalog.value=publication?.catalog ?: JsonObject(emptyMap())
     if(config!=null && publication!=null) container.preferences.edit().putString("plugin-catalog",publication.catalog.toString()).putString("native-plugin-server",config.serverUrl.trimEnd('/')).apply()
    }
   }
  }
 }
 private suspend fun publishPluginSnapshot(config:AuthConfig,snapshot:PluginManagerSnapshot):Boolean {
  val publication=persistPluginSnapshot(repository,config,snapshot.epoch,snapshot.manifest,snapshot.catalog) {container.auth.config.first()} ?: return false
  if(auth.value!=config) return false
  val accepted=container.manifest.adopt(publication.owner) {current ->
   manifest.value=current.manifest;catalog.value=current.catalog
   container.preferences.edit().putString("plugin-catalog",current.catalog.toString()).putString("native-plugin-server",config.serverUrl.trimEnd('/')).apply()
  } ?: return false
  return true
 }
 private suspend fun acknowledgePlugin(config:AuthConfig,expectedEpoch:String,row:JsonObject):Boolean {
  val publication=acknowledgePluginSnapshot(repository,config,expectedEpoch,row) {container.auth.config.first()} ?: return false
  if(auth.value!=config) return false
  val accepted=container.manifest.adopt(publication.owner) {current ->
   catalog.value=current.catalog;manifest.value=current.manifest
   io.theficos.spunto.plugins.quickcapture.CaptureSurfaces.apply(container.context,container.manifest.enabled("quick-capture"),container.manifest.enabled("dictation"))
   if(!container.manifest.enabled("dictation")) container.context.getSystemService(android.content.pm.ShortcutManager::class.java).removeDynamicShortcuts(listOf("dictate"))
   container.preferences.edit().putString("plugin-catalog",current.catalog.toString()).putString("native-plugin-server",config.serverUrl.trimEnd('/')).apply()
  } ?: return false
  return true
 }
 fun refreshPluginManager()=pluginManager.refresh()
 internal val pluginReplicaEpoch get()=pluginEpoch.value
 internal val pluginReplicaEpochState:StateFlow<String> get()=pluginEpoch
 internal fun pluginWeb(path:String) {
  val config=auth.value ?: return
  val url=pluginWebUrl(config.serverUrl,config.httpConsent,path) ?: return
  container.context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW,android.net.Uri.parse(url)).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
 }
 fun task(scope:CoroutineScope=viewModelScope,block:suspend ()->Unit):Job = scope.launch { try { block() } catch(e:CancellationException) { throw e } catch(e:Exception) { if(e is ApiException && e.status==401) reauthenticate.value=true; message.value=when { e is ApiException -> apiErrorMessage(container.context.resources,e); e is LocalOperationException -> localOperationMessage(container.context.resources,e); e is IllegalArgumentException -> e.message.orEmpty(); else -> container.context.getString(R.string.sys_server_unreachable) } } }
 fun synchronize()=task { val result=container.sync.sync(); if(result==SyncStatus.NeedsSignIn) reauthenticate.value=true; refreshPluginManager()?.join() }
 suspend fun saveLocal(body:String,id:String?=null):String { android.os.Trace.beginSection("spunto.local-save");return try { repository.save(body,id) } finally { android.os.Trace.endSection() } }
 fun save(body:String,id:String?=null,done:(String)->Unit={})=task { val noteId=saveLocal(body,id);repository.clearDraft(id?:"capture",body);done(noteId) }
 suspend fun refreshHistory(id:String):HistoryRefreshResult { val result=historyRepository.refresh(id);if(result==HistoryRefreshResult.NeedsSignIn) reauthenticate.value=true;return result }
 fun restoreHistory(id:String,revision:RevisionEntity)=task { repository.restoreHistory(id,revision) }
 fun flags(id:String,pinned:Boolean?=null,archived:Boolean?=null,trashed:Boolean?=null,privateNote:Boolean?=null)=task { repository.setFlags(id,pinned,archived,trashed,privateNote) }
 fun toggleTask(note:Note,line:Int)=task { val lines=note.body.lines().toMutableList(); lines[line]=lines[line].replaceFirst(Regex("\\[[ xX]]"),if(lines[line].contains("[ ]")) "[x]" else "[ ]"); repository.update(note.id,lines.joinToString("\n")) }
 fun decide(id:String,decision:String)=task { repository.decideSuggestion(id,decision) }
 /** The sign-in form's problem; sign-in failures never reach [message] or [reauthenticate]. */
 internal val signInProblem=MutableStateFlow<SignInProblem?>(null)
 fun login(server:String,password:String,code:String,name:String,consent:Boolean)=viewModelScope.launch { signInProblem.value=null; try { signIn(server,password,code,name,consent);message.value="" } catch(e:CancellationException) { throw e } catch(e:Exception) { signInProblem.value=signInProblem(container.context.resources,e,code.isNotBlank()) } }
 private suspend fun signIn(server:String,password:String,code:String,name:String,consent:Boolean) {
  val previous=container.auth.config.first()
  require(previous==null || previous.replicaId==container.replicaId) {container.context.getString(R.string.sys_account_changed)}
  if(reauthenticate.value && notes.value.isNotEmpty()) require(previous?.serverUrl?.trimEnd('/')==server.trimEnd('/')) {container.context.getString(R.string.sys_same_server)}
  val config=AuthConfig(server.trimEnd('/'),"","",name,consent)
  val device=if(code.isNotBlank()) ApiClient(config).pairingExchange(code.trim(),name) else ApiClient(config).signIn(password,name)
  val accepted=config.accept(device)
  require(container.auth.config.first()==previous) {container.context.getString(R.string.sys_account_changed_pairing)}
  if(reauthenticate.value && previous?.hosted==true) require(previous.replicaId==accepted.replicaId) {container.context.getString(R.string.sys_same_account)}
  container.auth.save(accepted);reauthenticate.value=false
  if(accepted.replicaId==container.replicaId) synchronize()
 }
 internal fun retire() {viewModelScope.cancel()}
 /** Signs out at once; online, the server then revokes this phone's credential too, and offline it stays listed under Devices. */
 fun signOut()=task {
  val config=container.auth.config.first()?.takeIf {it.replicaId==container.replicaId};val reachable=online.value
  config?.let {container.auth.clear()};reauthenticate.value=false
  if(config!=null && reachable && !ApiClient(config).signOut()) message.value=container.context.getString(R.string.sys_signed_out_listed)
 }
 fun request(path:String,method:String="GET",body:JsonObject?=null,onResult:(JsonElement)->Unit={})=task { val config=auth.value ?: return@task; onResult(ApiClient(config).request(path,method,body)) }
 fun ask(question:String,continuation:JsonObject?=null,current:()->Boolean={true},onHandle:(JsonObject)->Unit={},onError:((String)->Unit)?=null,onComplete:()->Unit,onResult:(JsonElement)->Unit)=task {
  val config=auth.value
  val epoch=pluginReplicaEpoch
  fun valid()=config!=null && auth.value==config && pluginReplicaEpoch==epoch && container.manifest.enabled("ask") && current()
  try {
   completeAskRequest(request={config?.let {conversationAskRequest(question,continuation,{command,args -> ApiClient(it).request("/api/v1/plugins/ask/commands/$command","POST",args)},::valid,onHandle,conversationSupported=supportsAskConversation(container.manifest.value),noteActionsSupported=supportsAskNoteActions(container.manifest.value))}},onComplete=onComplete,onResult={if(valid()) onResult(it)})
  } catch(cancelled:CancellationException) {throw cancelled}
  catch(error:Exception) {
   if(valid() && onError!=null) onError(if(error is ApiException) apiErrorMessage(container.context.resources,error) else container.context.getString(R.string.core_ask_failed))
   else if(valid()) throw error
  }
 }
 fun fetch(path:String)=request(path) { remote.value=if(it is JsonObject) it else buildJsonObject { put("items",it) } }
 internal fun pluginToggle(plugin:NativePluginFacts,on:Boolean,accepted:List<String>?=null,onComplete:(PluginToggleResult)->Unit={})=pluginManager.toggle(plugin.id,on,accepted,onComplete=onComplete)
 fun draft(id:String,text:String)=task { repository.saveDraft(id,text) }
}
