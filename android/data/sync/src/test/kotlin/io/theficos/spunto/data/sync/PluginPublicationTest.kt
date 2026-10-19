package io.theficos.spunto.data.sync

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.theficos.spunto.core.model.*
import io.theficos.spunto.data.local.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Real isolated Room and production cache persistence; held responses use no network. */
@RunWith(RobolectricTestRunner::class) @Config(sdk=[28])
class PluginPublicationTest {
 private lateinit var db:SpuntoDatabase
 private lateinit var repository:NotesRepository
 private val config=AuthConfig("https://synthetic.invalid","","synthetic-device","Synthetic")
 private fun manifest(rev:Int,on:Boolean)=Json.parseToJsonElement("""{"plugins_rev":$rev,"plugins":${if(on) "[{\"id\":\"dictation\",\"enabled\":true}]" else "[]"}}""").jsonObject
 private fun catalog(rev:Int,on:Boolean)=Json.parseToJsonElement("""{"plugins_rev":$rev,"plugins":[{"id":"dictation","switch":"${if(on) "on" else "off"}"}]}""").jsonObject
 @Before fun setup()=runBlocking {
  processPluginPublications.invalidate()
  db=Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(),SpuntoDatabase::class.java).allowMainThreadQueries().build();repository=NotesRepository(db)
  repository.dao.putState(SyncStateEntity(serverUrl=config.serverUrl,epoch="a",cursor="a.7",pluginsRev=3,manifest=manifest(3,true).toString(),catalog=catalog(3,true).toString()))
  repository.putNote(Note("synthetic-note","Words survive plugin switches",privateNote=true))
 }
 @After fun close() {db.close();processPluginPublications.invalidate()}
 @Test fun heldOldPairAfterAcknowledgedOffCannotReviveRoomContributions()=runBlocking {
  val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
  val old=async {entered.complete(Unit);release.await();persistPluginSnapshot(repository,config,"a",manifest(3,true),catalog(3,true)) {config}}
  entered.await()
  val row=Json.parseToJsonElement("""{"id":"dictation","switch":"off","plugins_rev":4}""").jsonObject
  assertNotNull(acknowledgePluginSnapshot(repository,config,"a",row) {config})
  release.complete(Unit);old.await()
  val state=repository.dao.state()!!;assertEquals(4L,PluginPublicationFence.revision(Json.parseToJsonElement(state.catalog).jsonObject));assertTrue(Json.parseToJsonElement(state.manifest).jsonObject["plugins"]!!.jsonArray.isEmpty());assertEquals("off",Json.parseToJsonElement(state.catalog).jsonObject["plugins"]!!.jsonArray.single().jsonObject["switch"]!!.jsonPrimitive.content)
  assertEquals("a.7",state.cursor);assertTrue(repository.dao.get("synthetic-note")!!.privateNote);assertEquals("Words survive plugin switches",repository.dao.get("synthetic-note")!!.body)
 }
 @Test fun oldEpochAndCredentialResponsesCannotPersistIntoRestoredOrDifferentOwnerState()=runBlocking {
  val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>();var current:AuthConfig?=config
  val held=async {entered.complete(Unit);release.await();persistPluginSnapshot(repository,config,"a",manifest(9,true),catalog(9,true)) {current}}
  entered.await();val old=repository.dao.state()!!
  repository.dao.putState(old.copy(epoch="b",cursor="b.0",manifest="{}",catalog="{}"))
  assertNotNull(persistPluginSnapshot(repository,config,"b",manifest(1,false),catalog(1,false)) {current})
  release.complete(Unit);assertNull(held.await());assertEquals("b",repository.dao.state()!!.epoch);assertEquals(1L,PluginPublicationFence.revision(Json.parseToJsonElement(repository.dao.state()!!.catalog).jsonObject))
  current=config.copy(deviceId="new-device")
  assertNull(persistPluginSnapshot(repository,config,"b",manifest(10,true),catalog(10,true)) {current});assertNull(acknowledgePluginSnapshot(repository,config,"b",Json.parseToJsonElement("""{"id":"dictation","switch":"on","plugins_rev":11}""").jsonObject) {current})
 }
 @Test fun mismatchedPairNeverReplacesCurrentValidReplica()=runBlocking {
  val state=repository.dao.state()!!
  persistPluginSnapshot(repository,config,"a",manifest(4,false),catalog(3,false)) {config}
  assertEquals(state.manifest,repository.dao.state()!!.manifest);assertEquals(state.catalog,repository.dao.state()!!.catalog)
 }
 @Test fun heldWriteBeforeCommitStaysPrivateAndCancellationRollsBack()=runBlocking {
  val fence=PluginPublicationFence()
  val committed=readCommittedPluginPublication(repository,config,fence) {config}!!
  val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
  val row=Json.parseToJsonElement("""{"id":"dictation","switch":"off","plugins_rev":4}""").jsonObject
  val job=launch(Dispatchers.IO) {acknowledgePluginSnapshot(repository,config,"a",row,fence,PluginPublicationHooks(beforeCommit={entered.complete(Unit);release.await()})) {config}}
  entered.await()
  assertEquals(3L,fence.current(committed.owner)!!.revision)
  assertEquals(1,fence.current(committed.owner)!!.manifest["plugins"]!!.jsonArray.size)
  job.cancel();release.complete(Unit);job.join()
  assertEquals(3L,PluginPublicationFence.revision(Json.parseToJsonElement(repository.dao.state()!!.catalog).jsonObject));assertEquals(3L,fence.current(committed.owner)!!.revision)
 }
 @Test fun failedCommitCannotExposeFutureStateAndSuccessfulCommitMayBeObservedBeforeCallerResumes()=runBlocking {
  val fence=PluginPublicationFence();val committed=readCommittedPluginPublication(repository,config,fence) {config}!!
  val row=Json.parseToJsonElement("""{"id":"dictation","switch":"off","plugins_rev":4}""").jsonObject
  val failure=runCatching {acknowledgePluginSnapshot(repository,config,"a",row,fence,PluginPublicationHooks(beforeCommit={throw IllegalStateException("synthetic write failure")})) {config}}
  assertTrue(failure.isFailure);assertEquals(3L,fence.current(committed.owner)!!.revision);assertEquals(3L,PluginPublicationFence.revision(Json.parseToJsonElement(repository.dao.state()!!.catalog).jsonObject))
  val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
  val job=async(Dispatchers.IO) {acknowledgePluginSnapshot(repository,config,"a",row,fence,PluginPublicationHooks(afterCommit={entered.complete(Unit);release.await()})) {config}}
  entered.await();assertEquals(3L,fence.current(committed.owner)!!.revision)
  assertEquals(4L,PluginPublicationFence.revision(Json.parseToJsonElement(repository.dao.state()!!.catalog).jsonObject))
  val actual=readCommittedPluginPublication(repository,config,fence) {config}!!
  assertEquals(4L,actual.publication!!.revision)
  release.complete(Unit);assertNotNull(job.await())
 }
 @Test fun delayedOldEmissionReadsAuthoritativeRestoredEpochAndCannotRebindOldTicket()=runBlocking {
  val fence=PluginPublicationFence();val old=readCommittedPluginPublication(repository,config,fence) {config}!!
  val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
  val delayed=async {entered.complete(Unit);release.await();readCommittedPluginPublication(repository,config,fence) {config}}
  entered.await();val state=repository.dao.state()!!
  repository.dao.putState(state.copy(epoch="b",manifest=manifest(1,false).toString(),catalog=catalog(1,false).toString()))
  val fresh=readCommittedPluginPublication(repository,config,fence) {config}!!
  release.complete(Unit);val resumed=delayed.await()!!
  assertEquals("b",resumed.owner.epoch);assertEquals(fresh.owner,resumed.owner);assertNull(fence.current(old.owner));assertNull(fence.publish(old.owner,manifest(9,true),catalog(9,true)))
 }
 @Test fun credentialChangeWhileWriteIsHeldRollsBackBeforePublicExposure()=runBlocking {
  val fence=PluginPublicationFence();var current:AuthConfig?=config
  val old=readCommittedPluginPublication(repository,config,fence) {current}!!
  val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
  val row=Json.parseToJsonElement("""{"id":"dictation","switch":"off","plugins_rev":4}""").jsonObject
  val job=async(SupervisorJob()+Dispatchers.IO) {runCatching {acknowledgePluginSnapshot(repository,config,"a",row,fence,PluginPublicationHooks(beforeCommit={entered.complete(Unit);release.await()})) {current}}}
  entered.await();current=config.copy(deviceId="replacement-device");release.complete(Unit)
  assertTrue(job.await().isFailure);assertEquals(3L,PluginPublicationFence.revision(Json.parseToJsonElement(repository.dao.state()!!.catalog).jsonObject));assertEquals(3L,fence.current(old.owner)!!.revision)
  val replacement=readCommittedPluginPublication(repository,current,fence) {current}!!
  assertNotEquals(old.owner,replacement.owner);assertNull(fence.current(old.owner))
 }
}
