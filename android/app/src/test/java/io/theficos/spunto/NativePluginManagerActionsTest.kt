package io.theficos.spunto

import io.theficos.spunto.core.model.*
import io.theficos.spunto.data.sync.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(application=android.app.Application::class,sdk=[28])
class NativePluginManagerActionsTest {
 private class Harness {
  val scope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined)
  var config:AuthConfig?=AuthConfig("https://synthetic.invalid","","synthetic-device","Synthetic")
  var epoch="a";var online=true;var clock=0L
  val fence=PluginPublicationFence();val ticket=fence.bind(config!!.serverUrl,epoch)
  var catalog=pluginCatalogFixture(pluginFixture(state="enabled",permissions=listOf("notes.read")))
  var manifest=pluginCatalogFixture(pluginFixture(state="enabled",permissions=listOf("notes.read")))
  var serverCatalog=catalog;var serverManifest=manifest
  var toggleError:ApiException?=null;var refreshError=false;var calls=0;var settingsCalls=0;var publications=0;var cached=0
  var actualSwitch:String?=null;var omitRevision=false;var beforeBackend:()->Unit={}
  var manifestGate:CompletableDeferred<Unit>?=null;val manifestEntered=CompletableDeferred<Unit>()
  val pageGates=mutableMapOf<String,CompletableDeferred<Unit>>()
  val pageEntered=mutableMapOf<String,CompletableDeferred<Unit>>()
  val pages=mutableMapOf<String,JsonObject>()
  val errors=mutableListOf<String>();val accepted=mutableListOf<List<String>>()
  init {fence.publish(ticket,manifest,catalog)}
  val backend=object:NativePluginBackend {
   override suspend fun catalog():JsonObject=serverCatalog
   override suspend fun manifest():JsonObject {
    if(refreshError) throw IllegalStateException("synthetic unavailable")
    val value=serverManifest
    manifestGate?.let {val gate=it;manifestGate=null;manifestEntered.complete(Unit);withContext(NonCancellable) {gate.await()}}
    return value
   }
   override suspend fun ai()=JsonObject(emptyMap())
   override suspend fun settings(id:String):JsonObject {
    settingsCalls++;pageEntered.getOrPut(id) {CompletableDeferred()}.complete(Unit)
    pageGates[id]?.let {withContext(NonCancellable) {it.await()}}
    return pages[id] ?: Json.parseToJsonElement("""{"schema":{"type":"object","properties":{"language":{"type":"string"}}},"values":{"language":"$id"}}""").jsonObject
   }
   override suspend fun toggle(id:String,on:Boolean,permissions:List<String>):JsonObject {
    calls++;accepted+=permissions;toggleError?.let {throw it}
    val current=serverCatalog["plugins"]!!.jsonArray.first {it.jsonObject.pluginText("id")==id}.jsonObject
    val switch=actualSwitch ?: if(on) "on" else "off"
    val revision=serverCatalog.pluginLong("plugins_rev")!!+1
    val row=JsonObject(current+mapOf("switch" to JsonPrimitive(switch),"state" to JsonPrimitive(if(switch=="on") "waiting" else "available")))
    serverCatalog=pluginCatalogFixture(row,revision=revision);serverManifest=pluginCatalogFixture(revision=revision)
    return if(omitRevision) row else JsonObject(row+("plugins_rev" to JsonPrimitive(revision)))
   }
  }
  val actions=NativePluginManagerActions(settingsTestResources(),scope,{config},{online},{epoch},{beforeBackend();backend},{nativePluginCatalog(catalog,manifest)},
   {_,snapshot -> fence.publish(ticket,snapshot.manifest,snapshot.catalog)?.let {catalog=it.catalog;manifest=it.manifest;publications++;true} ?: false},
   {_,_,row -> fence.acknowledge(ticket,row)?.let {catalog=it.catalog;manifest=it.manifest;true} ?: false},
   {_,_ -> NativePluginPage()},{_,_,_ -> cached++},{_,_ ->},{errors+=it},{clock})
  fun close()=scope.cancel()
 }
 @Test fun onlyAcknowledgedOffOffersUndoAndReenableUsesExactExistingGrants()=runBlocking {
  val h=Harness();try {
   var result:PluginToggleResult?=null
   h.actions.toggle("voice-memos",false) {result=it}!!.join()
   assertTrue(result is PluginToggleResult.Applied);assertNotNull(h.actions.undo.value);assertFalse(nativePluginCatalog(h.catalog,h.manifest).rows.single().wantedOn);assertTrue(h.manifest["plugins"]!!.jsonArray.isEmpty())
   h.actions.undo {fail("No permission expansion")}
   assertEquals(2,h.calls);assertEquals(listOf(emptyList<String>(),listOf("notes.read")),h.accepted);assertNull(h.actions.undo.value)
   assertTrue(nativePluginCatalog(h.catalog,h.manifest).rows.single().wantedOn);assertFalse(nativePluginCatalog(h.catalog,h.manifest).rows.single().activeContribution)
  } finally {h.close()}
 }
 @Test fun undoOfChangedContractRequiresReviewWithoutSendingExpandedScopes()=runBlocking {
  val h=Harness();try {
   h.actions.toggle("voice-memos",false)!!.join()
   h.catalog=pluginCatalogFixture(pluginFixture(on=false,state="available",permissions=listOf("notes.read","notes.create")),revision=5)
   var review:String?=null;h.actions.undo {review=it}
   assertEquals("voice-memos",review);assertEquals(1,h.calls);assertNull(h.actions.undo.value)
  } finally {h.close()}
 }
 @Test fun failedAndUnacknowledgedSwitchesNeverOfferUndo()=runBlocking {
  listOf(401,403,409).forEach {status -> val h=Harness();try {
   h.toggleError=ApiException(status,if(status==409) "pinned_by_environment" else "owner_required")
   var result:PluginToggleResult?=null;h.actions.toggle("voice-memos",false) {result=it}!!.join()
   assertTrue(result is PluginToggleResult.Failed);assertNull(h.actions.undo.value);assertTrue(nativePluginCatalog(h.catalog,h.manifest).rows.single().wantedOn);assertFalse(h.actions.busy.value)
  } finally {h.close()}}
  val h=Harness();try {h.omitRevision=true;h.actions.toggle("voice-memos",false)!!.join();assertNull(h.actions.undo.value);assertTrue(nativePluginCatalog(h.catalog,h.manifest).rows.single().wantedOn)} finally {h.close()}
 }
 @Test fun permissionConflictRefreshesCurrentContractWithoutAutomaticRetry()=runBlocking {
  val h=Harness();try {
   h.catalog=pluginCatalogFixture(pluginFixture(on=false,state="available",permissions=listOf("notes.read")))
   h.toggleError=ApiException(409,"permissions_changed")
   h.serverCatalog=pluginCatalogFixture(pluginFixture(on=false,state="available",permissions=listOf("notes.read","notes.create")),revision=4);h.serverManifest=pluginCatalogFixture(revision=4)
   var result:PluginToggleResult?=null;h.actions.toggle("voice-memos",true,listOf("notes.read")) {result=it}!!.join()
   assertEquals(PluginToggleResult.ReviewPermissions,result);assertEquals(1,h.calls);assertEquals(listOf("notes.read","notes.create"),nativePluginCatalog(h.catalog,h.manifest).rows.single().permissions);assertNull(h.actions.undo.value)
  } finally {h.close()}
 }
 @Test fun ackRemainsOffIfRefreshFailsAndUnexpectedCurrentOnHasNoUndo()=runBlocking {
  val h=Harness();try {
   h.refreshError=true;var result:PluginToggleResult?=null;h.actions.toggle("voice-memos",false) {result=it}!!.join()
   assertTrue((result as PluginToggleResult.Applied).refreshFailed);assertFalse(nativePluginCatalog(h.catalog,h.manifest).rows.single().wantedOn);assertNotNull(h.actions.undo.value)
  } finally {h.close()}
  val later=Harness();try {later.actualSwitch="on";later.actions.toggle("voice-memos",false)!!.join();assertTrue(nativePluginCatalog(later.catalog,later.manifest).rows.single().wantedOn);assertNull(later.actions.undo.value)} finally {later.close()}
 }
 @Test fun heldOldRefreshCannotPublishAfterNewOffOrCredentialChange()=runBlocking {
  val h=Harness();try {
   val gate=CompletableDeferred<Unit>();h.manifestGate=gate;val old=h.actions.refresh()!!;h.manifestEntered.await()
   h.actions.toggle("voice-memos",false)!!.join();val publications=h.publications
   gate.complete(Unit);old.join();assertEquals(publications,h.publications);assertFalse(nativePluginCatalog(h.catalog,h.manifest).rows.single().wantedOn)
   h.actions.invalidate();h.config=h.config!!.copy(serverUrl="https://other.synthetic");assertNull(h.actions.undo.value)
  } finally {h.close()}
 }
 @Test fun oldSelectedPageAndOwnerCannotReplaceNewPageOrWriteCache()=runBlocking {
  val h=Harness();try {
   h.catalog=pluginCatalogFixture(pluginFixture("plugin-one"),pluginFixture("plugin-two"))
   val oldGate=CompletableDeferred<Unit>();h.pageGates["plugin-one"]=oldGate
   h.actions.select("plugin-one");h.pageEntered.getValue("plugin-one").await()
   h.actions.select("plugin-two");assertEquals("plugin-two",h.actions.page.value.id);assertEquals(1,h.cached)
   oldGate.complete(Unit);yield();assertEquals("plugin-two",h.actions.page.value.id);assertEquals(1,h.cached)
   h.online=false;h.actions.select("plugin-one");assertFalse(h.actions.page.value.loading);assertEquals(2,h.settingsCalls);assertNull(h.actions.toggle("plugin-two",false))
   h.actions.invalidate();assertNull(h.actions.page.value.id)
  } finally {h.close()}
 }
 @Test fun heldPageCannotCacheAfterOwnerChangeAndRemovedNeverLoadsSettings()=runBlocking {
  val h=Harness();try {
   val gate=CompletableDeferred<Unit>();h.pageGates["voice-memos"]=gate
   h.actions.select("voice-memos");h.pageEntered.getValue("voice-memos").await()
   h.config=h.config!!.copy(deviceId="replacement-device");h.actions.invalidate()
   val jobs=h.scope.coroutineContext[Job]!!.children.toList();gate.complete(Unit);jobs.forEach {it.join()}
   assertEquals(0,h.cached);assertNull(h.actions.page.value.id)
   h.catalog=pluginCatalogFixture(pluginFixture(state="removed",on=false))
   h.actions.select("voice-memos");assertEquals(1,h.settingsCalls);assertFalse(h.actions.page.value.loading);assertNull(h.actions.toggle("voice-memos",true,listOf("ai.transcribe")))
  } finally {h.close()}
 }
 @Test fun expiredOrPinnedUndoCannotSendAnotherToggle()=runBlocking {
  val h=Harness();try {
   h.actions.toggle("voice-memos",false)!!.join();h.clock=7000
   h.actions.undo {fail("Expired receipt cannot grant")};assertEquals(1,h.calls);assertNull(h.actions.undo.value)
  } finally {h.close()}
  val pinned=Harness();try {
   pinned.actions.toggle("voice-memos",false)!!.join()
   val old=pinned.catalog["plugins"]!!.jsonArray.single().jsonObject
   pinned.catalog=pluginCatalogFixture(JsonObject(old+("pinned_by_env" to JsonPrimitive(true))),revision=5)
   pinned.actions.undo {fail("Pinned receipt cannot grant")};assertEquals(1,pinned.calls);assertNull(pinned.actions.undo.value)
  } finally {pinned.close()}
 }
 @Test fun clearingRetainedGrantsWithSameDeclaredPermissionsForcesUndoReview()=runBlocking {
  val h=Harness();try {
   h.actions.toggle("voice-memos",false)!!.join()
   val row=h.catalog["plugins"]!!.jsonArray.single().jsonObject
   h.catalog=pluginCatalogFixture(JsonObject(row+("granted" to JsonArray(emptyList()))),revision=5)
   var review:String?=null;h.actions.undo {review=it}
   assertEquals("voice-memos",review);assertEquals(1,h.calls)
   // Fresh explicit consent remains authorized for exactly the current declaration.
   h.actions.toggle("voice-memos",true,listOf("notes.read"))!!.join();assertEquals(2,h.calls)
  } finally {h.close()}
 }
 @Test fun undoMutationRechecksLatestGrantsAfterAdapterAcquisition()=runBlocking {
  val h=Harness();try {
   h.actions.toggle("voice-memos",false)!!.join()
   h.beforeBackend={
    h.beforeBackend={}
    val row=h.catalog["plugins"]!!.jsonArray.single().jsonObject
    h.catalog=pluginCatalogFixture(JsonObject(row+("granted" to JsonArray(emptyList()))),revision=5)
   }
   var review:String?=null;h.actions.undo {review=it}
   assertEquals("voice-memos",review);assertEquals(1,h.calls);assertFalse(h.actions.busy.value)
  } finally {h.close()}
 }

}
