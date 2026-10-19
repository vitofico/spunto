package io.theficos.spunto.data.sync

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.theficos.spunto.core.model.*
import io.theficos.spunto.data.local.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.*
import okhttp3.OkHttpClient
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class) @Config(sdk=[28])
class HistoryRepositoryTest {
 @get:Rule val timeout=org.junit.rules.Timeout(10,TimeUnit.MINUTES)
 private lateinit var db:SpuntoDatabase
 private lateinit var repository:NotesRepository
 private lateinit var server:MockWebServer
 private lateinit var config:AuthConfig
 private lateinit var history:HistoryRepository
 private lateinit var noteId:String
 @Before fun setup()=runBlocking {
  db=Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(),SpuntoDatabase::class.java).allowMainThreadQueries().build()
  repository=NotesRepository(db);server=MockWebServer();server.start()
  config=AuthConfig(server.url("/").toString().trimEnd('/'),"synthetic-history-token","device-history","History phone",true)
  noteId=uuidV7();repository.putNote(Note(noteId,"Server latest",serverRev=5,privateNote=true))
  repository.dao.putState(SyncStateEntity(epoch="e",cursor="e.5",serverUrl=config.serverUrl))
  history=HistoryRepository(repository,{ config })
 }
 @After fun close() { db.close();server.shutdown() }
 private fun body(vararg revisions:Pair<Int,String>)=buildJsonArray { revisions.forEach { (version,text) -> add(buildJsonObject {
  put("version",version);put("body",text);put("change_kind",if(version==1) "create" else "edit");put("changed_by",if(version==1) "owner-device" else "agent-credential");put("changed_at",Instant.ofEpochMilli(version*1000L).toString())
 }) } }.toString()
 private fun reply(vararg revisions:Pair<Int,String>) { server.enqueue(MockResponse().setBody(body(*revisions)));server.enqueue(MockResponse().setBody("{\"sync_epoch\":\"e\"}")) }
 private suspend fun seedCache() {
  repository.dao.putServerRevisions(listOf(ServerRevisionEntity(config.serverUrl,"e",noteId,5,"Cached latest",5000,"owner-device","edit")))
  repository.dao.putHistoryCache(HistoryCacheEntity(config.serverUrl,"e",noteId,6000,5))
 }
 @Test fun canonicalRowsHydratePrivateNoteAndReplaceCoalescedVersionsExactly()=runBlocking {
  reply(1 to "Initial owner text",2 to "Intermediate text",5 to "Server latest")
  assertEquals(HistoryRefreshResult.Refreshed(3),history.refresh(noteId))
  assertEquals(listOf(5,2,1),repository.history(noteId).first().map { it.version })
  assertEquals("agent-credential",repository.history(noteId).first().first().changedBy)
  val request=server.takeRequest();assertEquals("GET",request.method);assertEquals("/api/v1/notes/"+noteId+"/revisions",request.path);assertEquals("Bearer synthetic-history-token",request.getHeader("Authorization"))
  assertEquals("/api/v1/meta",server.takeRequest().path)
  reply(1 to "Initial owner text",5 to "Server latest")
  assertEquals(HistoryRefreshResult.Refreshed(2),history.refresh(noteId))
  assertEquals(listOf(5,1),repository.history(noteId).first().map { it.version })
  assertEquals("Server latest",repository.dao.get(noteId)!!.body);assertTrue(repository.dao.get(noteId)!!.privateNote);assertTrue(repository.dao.outbox().isEmpty())
 }
 @Test fun typingWhileHistoryIsInFlightPreservesItsExactOutboxAndBody()=runBlocking {
  val entered=CountDownLatch(1);val release=CountDownLatch(1)
  server.dispatcher=object:Dispatcher() { override fun dispatch(request:RecordedRequest):MockResponse = if(request.path!!.endsWith("/revisions")) { entered.countDown();release.await(5,TimeUnit.SECONDS);MockResponse().setBody(body(1 to "Initial",6 to "Other device latest")) } else MockResponse().setBody("{\"sync_epoch\":\"e\"}") }
  val refreshing=async(Dispatchers.Default) { history.refresh(noteId) }
  try {
   assertTrue(entered.await(2,TimeUnit.SECONDS));repository.update(noteId,"Owner typed during history")
   val queued=repository.dao.outbox().single();release.countDown()
   assertEquals(HistoryRefreshResult.Refreshed(2),refreshing.await())
   assertEquals(queued,repository.dao.outbox().single());assertEquals("Owner typed during history",repository.dao.get(noteId)!!.body)
   assertTrue(repository.history(noteId).first().any { it.body=="Server latest" });assertTrue(repository.history(noteId).first().any { it.body=="Other device latest" })
  } finally { release.countDown();refreshing.cancelAndJoin() }
 }
 @Test fun authenticationAndNetworkFailuresKeepCacheAndPendingWords()=runBlocking {
  seedCache();repository.update(noteId,"Unsynced owner text");val pending=repository.dao.outbox()
  server.enqueue(MockResponse().setResponseCode(401).setBody("{\"code\":\"revoked\"}"))
  assertEquals(HistoryRefreshResult.NeedsSignIn,history.refresh(noteId))
  server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
  val client=OkHttpClient.Builder().retryOnConnectionFailure(false).build()
  try { assertTrue(HistoryRepository(repository,{config}) { auth -> ApiClient(auth,client) }.refresh(noteId) is HistoryRefreshResult.Cached) } finally { client.connectionPool.evictAll();client.dispatcher.executorService.shutdown() }
  assertEquals(pending,repository.dao.outbox());assertEquals("Unsynced owner text",repository.dao.get(noteId)!!.body)
  assertTrue(repository.history(noteId).first().any { it.body=="Cached latest" })
 }
 @Test fun changedEpochOrServerBindingDiscardsAnInFlightResponse()=runBlocking {
  for(changeEpoch in listOf(false,true)) {
   repository.dao.putState(SyncStateEntity(epoch="e",cursor="e.5",serverUrl=config.serverUrl))
   val entered=CountDownLatch(1);val release=CountDownLatch(1)
   server.dispatcher=object:Dispatcher() { override fun dispatch(request:RecordedRequest):MockResponse = if(request.path!!.endsWith("/revisions")) { entered.countDown();release.await(5,TimeUnit.SECONDS);MockResponse().setBody(body(5 to "Stale scoped body")) } else MockResponse().setBody("{\"sync_epoch\":\"e\"}") }
   val refreshing=async(Dispatchers.Default) { history.refresh(noteId) }
   try {
    assertTrue(entered.await(2,TimeUnit.SECONDS));repository.dao.putState(SyncStateEntity(epoch=if(changeEpoch) "restored" else "e",serverUrl=if(changeEpoch) config.serverUrl else "https://other.example"));release.countDown()
    assertEquals(HistoryRefreshResult.Cached("stale_history"),refreshing.await());assertTrue(repository.dao.serverHistory(noteId).first().isEmpty())
   } finally { release.countDown();refreshing.cancelAndJoin() }
  }
 }
 @Test fun restoredServerEpochAndOlderVersionCannotReplaceExistingCache()=runBlocking {
  seedCache();server.enqueue(MockResponse().setBody(body(1 to "Old backup")));server.enqueue(MockResponse().setBody("{\"sync_epoch\":\"restored\"}"))
  assertEquals(HistoryRefreshResult.Cached("stale_history"),history.refresh(noteId))
  reply(1 to "Old ancestor")
  assertEquals(HistoryRefreshResult.Cached("stale_history"),history.refresh(noteId))
  assertEquals("Cached latest",repository.history(noteId).first().single().body)
 }
 @Test fun purgeDuringReadDoesNotCacheOrResurrectTheNote()=runBlocking {
  seedCache();repository.dao.putRevision(RevisionEntity(noteId=noteId,body="Earlier private words",at=1,version=1))
  val entered=CountDownLatch(1);val release=CountDownLatch(1)
  server.dispatcher=object:Dispatcher() { override fun dispatch(request:RecordedRequest):MockResponse = if(request.path!!.endsWith("/revisions")) { entered.countDown();release.await(5,TimeUnit.SECONDS);MockResponse().setBody(body(5 to "Purged words")) } else MockResponse().setBody("{\"sync_epoch\":\"e\"}") }
  val refreshing=async(Dispatchers.Default) { history.refresh(noteId) }
  try {
   assertTrue(entered.await(2,TimeUnit.SECONDS));repository.dao.putTombstone(TombstoneEntity(noteId,10));repository.dao.remove(noteId);release.countDown()
   assertEquals(HistoryRefreshResult.Cached("stale_history"),refreshing.await());assertNull(repository.dao.get(noteId));assertTrue(repository.history(noteId).first().isEmpty());assertTrue(repository.dao.history(noteId).first().isEmpty());assertTrue(repository.dao.serverHistory(noteId).first().isEmpty());assertNull(repository.dao.historyCache(noteId).first());assertTrue(repository.dao.outbox().isEmpty())
  } finally { release.countDown();refreshing.cancelAndJoin() }
 }
 @Test fun credentialRotationDuringReadCannotInstallTheOldResponse()=runBlocking {
  seedCache();val entered=CountDownLatch(1);val release=CountDownLatch(1)
  server.dispatcher=object:Dispatcher() { override fun dispatch(request:RecordedRequest):MockResponse = if(request.path!!.endsWith("/revisions")) { entered.countDown();release.await(5,TimeUnit.SECONDS);MockResponse().setBody(body(5 to "Old credential response")) } else MockResponse().setBody("{\"sync_epoch\":\"e\"}") }
  val refreshing=async(Dispatchers.Default) { history.refresh(noteId) }
  try { assertTrue(entered.await(2,TimeUnit.SECONDS));config=config.copy(token="rotated-synthetic-token");release.countDown()
   assertEquals(HistoryRefreshResult.Cached("credentials_changed"),refreshing.await());assertEquals("Cached latest",repository.history(noteId).first().single().body)
  } finally { release.countDown();refreshing.cancelAndJoin() }
 }

}
