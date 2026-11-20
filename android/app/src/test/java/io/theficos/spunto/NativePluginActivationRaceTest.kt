package io.theficos.spunto

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.theficos.spunto.core.model.*
import io.theficos.spunto.data.local.*
import io.theficos.spunto.data.sync.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/** The production controller and Room publication path share a completed server toggle with sync. */
@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(application=android.app.Application::class,sdk=[28])
class NativePluginActivationRaceTest {
 private class Harness(val requestedOn:Boolean,val syncedRevision:Long,val syncedOn:Boolean,val syncedPermissions:List<String>,val refreshedOn:Boolean?=null) {
  val config=AuthConfig("https://synthetic.invalid","synthetic-token","synthetic-device","Synthetic",userId="synthetic-owner",workspaceId="synthetic-workspace",workspaceRole="owner")
  val db=Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(),SpuntoDatabase::class.java).allowMainThreadQueries().build()
  val repository=NotesRepository(db,config.replicaId);val fence=PluginPublicationFence()
  val scope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined)
  val permissions=listOf("notes.read")
  lateinit var current:PluginPublication
  var owner:AuthConfig?=config
  var epoch="a"
  var onRefresh:()->Unit={}
  var refreshRevision=4L
  var acceptRefresh=true
  var acknowledgementError:Exception?=null
  var calls=0;var refreshes=0;val grants=mutableListOf<List<String>>();val messages=mutableListOf<String>()
  private fun row(on:Boolean,permissions:List<String>)=pluginFixture(on=on,state=if(on) "enabled" else "available",permissions=permissions)
  private fun manifest(row:JsonObject,revision:Long)=pluginCatalogFixture(*if(row.pluginText("switch")=="on") arrayOf(row) else emptyArray(),revision=revision)
  private fun adopt(value:PluginPublication?):Boolean {if(value==null) return false;current=value;return true}
  suspend fun prepare() {
   val row=row(!requestedOn,permissions)
   repository.dao.putState(SyncStateEntity(serverUrl=config.serverUrl,replicaId=config.replicaId,epoch="a",cursor="a.3",pluginsRev=3,manifest=manifest(row,3).toString(),catalog=pluginCatalogFixture(row).toString()))
   current=readCommittedPluginPublication(repository,config,fence) {config}!!.publication!!
  }
  private val backend=object:NativePluginBackend {
   override suspend fun manifest():JsonObject {
    refreshes++
    onRefresh()
    return manifest(row(refreshedOn ?: error("Synthetic refresh outage"),permissions),refreshRevision)
   }
   override suspend fun catalog()=pluginCatalogFixture(row(checkNotNull(refreshedOn),permissions),revision=refreshRevision)
   override suspend fun ai()=JsonObject(emptyMap())
   override suspend fun settings(id:String)=JsonObject(emptyMap())
   override suspend fun toggle(id:String,on:Boolean,permissions:List<String>):JsonObject {
    calls++;grants+=permissions
    val receipt=JsonObject(row(on,this@Harness.permissions)+("plugins_rev" to JsonPrimitive(4)))
    val synced=row(syncedOn,syncedPermissions)
    // The long-poll/sync wins while the mutation response is still in flight.
    check(adopt(persistPluginSnapshot(repository,config,"a",manifest(synced,syncedRevision),pluginCatalogFixture(synced,revision=syncedRevision),fence) {config}))
    return receipt
   }
  }
  val actions=NativePluginManagerActions(settingsTestResources(),scope,{owner},{true},{epoch},{backend},{nativePluginCatalog(current.catalog,current.manifest)},
   {_,snapshot -> acceptRefresh && adopt(persistPluginSnapshot(repository,config,snapshot.epoch,snapshot.manifest,snapshot.catalog,fence) {config})},
   {_,epoch,row -> acknowledgementError?.let {throw it};adopt(acknowledgePluginSnapshot(repository,config,epoch,row,fence) {config})},
   {_,_ -> NativePluginPage()},{_,_,_ ->},{_,_ ->},{messages+=it})
  fun close() {scope.cancel();db.close()}
 }
 @Test fun conflictingEqualRevisionCatalogIsReconciledWithoutReplayingServerWrite()=runBlocking {
  for(on in listOf(true,false)) {
   val h=Harness(on,4,!on,listOf("notes.read"),refreshedOn=on);try {
    h.prepare();var result:PluginToggleResult?=null
    h.actions.toggle("voice-memos",on,h.permissions) {result=it}!!.join()
    assertTrue(result is PluginToggleResult.Applied);assertFalse((result as PluginToggleResult.Applied).refreshFailed)
    assertEquals(1,h.calls);assertEquals(1,h.refreshes)
    val actual=nativePluginCatalog(h.current.catalog,h.current.manifest).rows.single()
    assertEquals(on,actual.wantedOn);assertEquals(on,actual.activeContribution)
    assertTrue(h.messages.isEmpty());assertFalse(h.actions.busy.value)
   } finally {h.close()}
  }
 }
 @Test fun successfulWriteWithUnreconciledCatalogReportsUnavailableRefreshAndClearsBusy()=runBlocking {
  val h=Harness(true,4,false,listOf("notes.read"));try {
   h.prepare();var result:PluginToggleResult?=null
   h.actions.toggle("voice-memos",true,h.permissions) {result=it}!!.join()
   assertTrue(result is PluginToggleResult.Applied);assertTrue((result as PluginToggleResult.Applied).refreshFailed)
   assertEquals(1,h.calls);assertEquals(1,h.refreshes)
   assertFalse(nativePluginCatalog(h.current.catalog,h.current.manifest).rows.single().wantedOn)
   assertEquals(listOf(settingsTestResources().getString(R.string.settings_switch_saved_refresh_unavailable)),h.messages)
   assertFalse(h.actions.busy.value);assertNull(h.actions.undo.value)
  } finally {h.close()}
 }
 @Test fun staleOrRejectedRefreshCannotConfirmDisableOrOfferUndo()=runBlocking {
  for(accepted in listOf(true,false)) {
   val h=Harness(false,4,true,listOf("notes.read"),refreshedOn=false);try {
    h.prepare();h.acceptRefresh=accepted
    if(accepted) h.refreshRevision=3
    var result:PluginToggleResult?=null
    h.actions.toggle("voice-memos",false) {result=it}!!.join()
    assertTrue(result is PluginToggleResult.Applied);assertTrue((result as PluginToggleResult.Applied).refreshFailed)
    assertEquals(1,h.calls);assertEquals(1,h.refreshes)
    assertEquals(4L,h.current.revision);assertNull(h.actions.undo.value)
    assertEquals(listOf(settingsTestResources().getString(R.string.settings_switch_saved_refresh_unavailable)),h.messages)
   } finally {h.close()}
  }
 }
 @Test fun localAcknowledgementFailureStillRefreshesAfterSuccessfulServerWrite()=runBlocking {
  val h=Harness(true,4,false,listOf("notes.read"),refreshedOn=true);try {
   h.prepare();h.acknowledgementError=IllegalStateException("Synthetic Room failure")
   var result:PluginToggleResult?=null
   h.actions.toggle("voice-memos",true,h.permissions) {result=it}!!.join()
   assertTrue(result is PluginToggleResult.Applied);assertFalse((result as PluginToggleResult.Applied).refreshFailed)
   assertEquals(1,h.calls);assertEquals(1,h.refreshes);assertTrue(h.messages.isEmpty())
   assertTrue(nativePluginCatalog(h.current.catalog,h.current.manifest).rows.single().activeContribution)
  } finally {h.close()}
 }
 @Test fun cancellationDuringAcknowledgementPropagatesWithoutPublishingAResult()=runBlocking {
  val h=Harness(true,4,false,listOf("notes.read"),refreshedOn=true);try {
   h.prepare();h.acknowledgementError=CancellationException("Synthetic cancellation")
   var result:PluginToggleResult?=null
   val job=h.actions.toggle("voice-memos",true,h.permissions) {result=it}!!
   job.join()
   assertTrue(job.isCancelled);assertNull(result);assertTrue(h.messages.isEmpty())
   assertEquals(1,h.calls);assertEquals(0,h.refreshes);assertFalse(h.actions.busy.value)
  } finally {h.close()}
 }
 @Test fun refreshedStateAndReceiptAreSuppressedAfterOwnerOrEpochChange()=runBlocking {
  for(changeOwner in listOf(true,false)) {
   val h=Harness(false,4,true,listOf("notes.read"),refreshedOn=false);try {
    h.prepare()
    h.onRefresh={if(changeOwner) {h.owner=h.config.copy(token="rotated");h.actions.invalidate()} else h.epoch="restored"}
    var result:PluginToggleResult?=null
    h.actions.toggle("voice-memos",false) {result=it}!!.join()
    assertNull(result);assertNull(h.actions.undo.value);assertTrue(h.messages.isEmpty())
    assertEquals(1,h.calls);assertTrue(nativePluginCatalog(h.current.catalog,h.current.manifest).rows.single().wantedOn)
   } finally {h.close()}
  }
 }
 @Test fun completedEnableAfterEqualOrNewerSyncReturnsAppliedAndPreservesCommandsWithoutRefresh()=runBlocking {
  for(revision in listOf(4L,5L)) {
   val h=Harness(true,revision,true,listOf("notes.read"));try {
    h.prepare();var result:PluginToggleResult?=null
    h.actions.toggle("voice-memos",true,h.permissions) {result=it}!!.join()
    assertTrue(result is PluginToggleResult.Applied);assertTrue((result as PluginToggleResult.Applied).refreshFailed)
    assertEquals(1,h.calls);assertEquals(listOf(h.permissions),h.grants)
    assertEquals(revision,h.current.revision);assertTrue(nativePluginCatalog(h.current.catalog,h.current.manifest).rows.single().activeContribution)
    assertEquals(h.current.manifest.toString(),h.repository.dao.state()!!.manifest)
    assertFalse(h.actions.busy.value);assertNull(h.actions.undo.value)
    assertFalse(h.messages.any {it.contains("facts changed")})
   } finally {h.close()}
  }
 }
 @Test fun subsequentServerChangeClosesSuccessfulEnableWithCurrentStateFeedback()=runBlocking {
  val h=Harness(true,5,false,listOf("notes.read","notes.create"));try {
   h.prepare();var result:PluginToggleResult?=null
   h.actions.toggle("voice-memos",true,h.permissions) {result=it}!!.join()
   assertTrue(result is PluginToggleResult.Applied);assertEquals(1,h.calls)
   val actual=nativePluginCatalog(h.current.catalog,h.current.manifest).rows.single()
   assertFalse(actual.wantedOn);assertEquals(listOf("notes.read","notes.create"),actual.permissions)
   assertEquals(5L,h.current.revision);assertNull(h.actions.undo.value)
   assertTrue(h.messages.any {it==settingsTestResources().getString(R.string.settings_the_plugin_changed_on_the_server_its)})
  } finally {h.close()}
 }
 @Test fun supersededDisableReceiptNeverOffersUndoFromOldRevisionOrGrants()=runBlocking {
  val h=Harness(false,5,false,listOf("notes.read","notes.create"));try {
   h.prepare();var result:PluginToggleResult?=null
   h.actions.toggle("voice-memos",false) {result=it}!!.join()
   assertTrue(result is PluginToggleResult.Applied);assertEquals(1,h.calls)
   assertNull(h.actions.undo.value);assertEquals(5L,h.current.revision)
   assertEquals(listOf("notes.read","notes.create"),nativePluginCatalog(h.current.catalog,h.current.manifest).rows.single().permissions)
  } finally {h.close()}
 }
}
