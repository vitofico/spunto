package io.theficos.spunto.data.sync

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.theficos.spunto.data.local.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

@RunWith(RobolectricTestRunner::class) @Config(sdk=[28])
class AccountReplicaTest {
 private fun hosted(server:String="https://synthetic.invalid",user:String="alice",workspace:String="personal",token:String="synthetic-a")=AuthConfig(server,token,"device-$user","Synthetic phone",server.startsWith("http://"),user,workspace,"owner")
 @Test fun namespaceIncludesUserWorkspaceAndServerButSurvivesTokenRotation() {
  val a=hosted();assertNotEquals(a.replicaId,hosted(user="bob").replicaId);assertNotEquals(a.replicaId,hosted(workspace="shared").replicaId)
  assertEquals(a.replicaId,a.copy(token="rotated",deviceId="replacement").replicaId)
  assertNotEquals(a.replicaId,a.copy(serverUrl="https://another.invalid").replicaId)
  assertEquals("spunto.db",replicaDatabaseName("legacy"));assertNotEquals(replicaDatabaseName(a.replicaId),replicaDatabaseName("signed-out"))
 }
 @Test fun legacyCredentialShapeStillDecodesAndPartialHostedIdentityIsRejected() {
  val old=Json.decodeFromString<AuthConfig>("""{"serverUrl":"https://synthetic.invalid","token":"retained","deviceId":"old-device","name":"Retained phone"}""")
  assertFalse(old.hosted);assertEquals("legacy",old.replicaId)
  try {old.accept(DeviceToken("new","token","Phone",userId="alice"));fail("Incomplete identity")} catch(_:IllegalArgumentException) {}
 }
 @Test fun pairingUsesTheServerVerifiedAccountAndWorkspaceFields()=runBlocking {
  val server=MockWebServer();server.start()
  try {
   server.enqueue(MockResponse().setBody("""{"id":"device","token":"synthetic-token","name":"Phone","user_id":"alice","workspace_id":"shared","workspace_role":"editor"}"""))
   val base=AuthConfig(server.url("/").toString().trimEnd('/'),"","","Phone",true)
   val accepted=base.accept(ApiClient(base).pairingExchange("synthetic-code","Phone"))
   assertEquals("alice",accepted.userId);assertEquals("shared",accepted.workspaceId);assertEquals("editor",accepted.workspaceRole)
   assertEquals(hosted(base.serverUrl,user="alice",workspace="shared").replicaId,accepted.replicaId)
   val request=server.takeRequest();assertNull(request.getHeader("Authorization"));assertEquals("/api/v1/auth/pairing/exchange",request.path)
  } finally {server.shutdown()}
 }
 @Test fun restoredWorkersRequireTheOriginalIdentityAndCredential() {
  val a=hosted();val ticket=workerCredential(a)
  assertTrue(workerIdentityMatches(a,a.replicaId,ticket));assertFalse(workerIdentityMatches(hosted(user="bob"),a.replicaId,ticket))
  assertFalse(workerIdentityMatches(hosted(workspace="shared"),a.replicaId,ticket));assertFalse(workerIdentityMatches(a.copy(token="rotated"),a.replicaId,ticket))
  assertFalse(workerIdentityMatches(a,null,null));assertTrue(workerIdentityMatches(AuthConfig("https://synthetic.invalid","legacy","d","Phone"),null,null))
 }
 @Test fun differentIdentityAtSameServerNeverUploadsOrRemovesRetainedOutbox()=runBlocking {
  val context=ApplicationProvider.getApplicationContext<Context>();val unique=UUID.randomUUID().toString();val a=hosted(workspace=unique);val b=hosted(user="bob",workspace=unique)
  val names=listOf(replicaDatabaseName(a.replicaId),replicaDatabaseName(b.replicaId));var dbA=SpuntoDatabase.open(context,names[0]);val dbB=SpuntoDatabase.open(context,names[1])
  try {
   val repoA=NotesRepository(dbA,a.replicaId);val repoB=NotesRepository(dbB,b.replicaId);val id=repoA.create("Alice pending only");val before=repoA.dao.outbox().single()
   repoA.dao.putState(SyncStateEntity(serverUrl=a.serverUrl,epoch="old",cursor="old.1",replicaId=a.replicaId))
   var calls=0;assertEquals(SyncStatus.Waiting("different_identity"),SyncEngine(repoA,{b}) {calls++;error("Wrong account client")}.sync())
   assertEquals(0,calls);assertEquals(before,repoA.dao.outbox().single());assertTrue(repoB.dao.all().isEmpty())
   repoB.create("Bob pending only");assertEquals(listOf("Alice pending only"),repoA.dao.all().map {it.body})
   dbA.close();dbA=SpuntoDatabase.open(context,names[0]);assertEquals(id,dbA.dao().all().single().id);assertEquals(before,dbA.dao().outbox().single())
  } finally {dbA.close();dbB.close();names.forEach {context.deleteDatabase(it)}}
 }
 @Test fun latePushAndRestoreResponsesCannotClearAnotherIdentitysPendingChanges()=runBlocking {
  for(restore in listOf(false,true)) {
   val server=MockWebServer();server.start();val a=hosted(server.url("/").toString().trimEnd('/'),workspace=UUID.randomUUID().toString());val b=a.copy(userId="bob",workspaceId="bob")
   val context=ApplicationProvider.getApplicationContext<Context>();val name=replicaDatabaseName(a.replicaId);val db=SpuntoDatabase.open(context,name);val selected=AtomicReference<AuthConfig?>(a)
   try {
    val repo=NotesRepository(db,a.replicaId);val id=repo.create("Alice unsynced");val before=repo.dao.outbox().single();repo.dao.putState(SyncStateEntity(serverUrl=a.serverUrl,epoch="old",cursor="old.1",replicaId=a.replicaId))
    server.dispatcher=object:Dispatcher() {override fun dispatch(request:RecordedRequest):MockResponse {
     assertEquals("Bearer ${a.token}",request.getHeader("Authorization"))
     return when(request.requestUrl!!.encodedPath) {
      "/api/v1/meta" -> MockResponse().setBody("{\"sync_epoch\":\"${if(restore) "restored" else "old"}\"}")
      "/api/v1/sync/push" -> {selected.set(b);MockResponse().setBody("{\"epoch\":\"old\",\"results\":[{\"op_id\":\"${before.opId}\",\"status\":\"applied\",\"version\":1}]}")}
      "/api/v1/sync/reconcile" -> {selected.set(b);MockResponse().setBody("{\"epoch\":\"restored\",\"restore_ids\":[\"$id\"]}")}
      else -> error("A retired identity cannot continue syncing")
     }
    }}
    assertTrue(SyncEngine(repo,{selected.get()}).sync() is SyncStatus.Waiting)
    val retained=repo.dao.outbox().single();assertEquals(before.opId,retained.opId);assertEquals(before.payload,retained.payload);assertEquals("Alice unsynced",repo.dao.all().single().body);assertEquals("old",repo.dao.state()!!.epoch);assertEquals(2,server.requestCount)
   } finally {server.shutdown();db.close();context.deleteDatabase(name)}
  }
 }
 @Test fun hostedReplicaRefusesLocalAuthAtSameUrlForSyncMediaHistoryAndPublication()=runBlocking {
  val server=MockWebServer();server.start()
  val hosted=hosted(server.url("/").toString().trimEnd('/'),workspace=UUID.randomUUID().toString())
  val legacy=AuthConfig(hosted.serverUrl,"synthetic-local","local","Phone",true)
  val context=ApplicationProvider.getApplicationContext<Context>();val name=replicaDatabaseName(hosted.replicaId);val db=SpuntoDatabase.open(context,name)
  try {
   val repo=NotesRepository(db,hosted.replicaId);val id=repo.create("Hosted private pending");val before=repo.dao.outbox().single()
   repo.dao.putState(SyncStateEntity(serverUrl=hosted.serverUrl,epoch="e",cursor="e.1",replicaId=hosted.replicaId))
   repo.dao.putAttachment(AttachmentEntity(noteId=id,localPath="/never-read-for-another-identity",mime="image/png",serverUrl=hosted.serverUrl))
   var historyCalls=0
   assertEquals(HistoryRefreshResult.Cached("different_identity"),HistoryRepository(repo,{legacy}) {historyCalls++;ApiClient(it)}.refresh(id))
   val engine=SyncEngine(repo,{legacy}) {error("Wrong account cannot create a sync client")}
   assertEquals(SyncStatus.Waiting("different_identity"),engine.sync())
   AttachmentUploader(repo,legacy,ApiClient(legacy)) {legacy}.uploadAll()
   withTimeout(1000) {ChangeWaiter({legacy},repo,engine).run()}
   assertNull(readCommittedPluginPublication(repo,legacy) {legacy})
   assertFalse(workerIdentityMatches(legacy,hosted.replicaId,workerCredential(hosted)))
   assertEquals(0,historyCalls);assertEquals(0,server.requestCount);assertEquals(before,repo.dao.outbox().single())
   assertEquals("queued",repo.dao.attachmentSnapshots().single().status)
  } finally {server.shutdown();db.close();context.deleteDatabase(name)}
 }

}
