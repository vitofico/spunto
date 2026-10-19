package io.theficos.spunto.data.sync
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.theficos.spunto.core.model.*
import io.theficos.spunto.data.local.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.net.URI
import okhttp3.OkHttpClient
@RunWith(RobolectricTestRunner::class) @Config(sdk=[28])
class RealServerIntegrationTest {
 @get:org.junit.Rule val timeout = org.junit.rules.Timeout(10, java.util.concurrent.TimeUnit.MINUTES)
 @Test(timeout=600_000) fun offlineEditsMergeAndRestoreAgainstDisposableServer()=runBlocking {
  val url=System.getProperty("spunto.integration.url","").orEmpty()
  Assume.assumeTrue("Use the opt-in disposable server fixture",url.isNotEmpty())
  require(URI(url).host in setOf("127.0.0.1","localhost","host.docker.internal")) { "Integration fixtures must use a local disposable server." }
  val loginConfig=AuthConfig(url,"","","Android synthetic integration",true)
  val client=OkHttpClient.Builder().addInterceptor { chain -> val request=if(URI(url).host=="host.docker.internal") chain.request().newBuilder().header("Host","localhost:${URI(url).port}").build() else chain.request();chain.proceed(request) }.build()
  val credential=ApiClient(loginConfig,client).signIn(System.getProperty("spunto.integration.password")?:error("Fixture password is required"),loginConfig.name)
  val config=loginConfig.copy(token=credential.token,deviceId=credential.id);val api=ApiClient(config,client)
  val db=Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(),SpuntoDatabase::class.java).allowMainThreadQueries().build()
  try {
   val repository=NotesRepository(db);val engine=SyncEngine(repository,{config}) { auth -> ApiClient(auth,client) }
   val id=repository.create("Synthetic integration first\nBase second")
   assertEquals("Synthetic integration first\nBase second",repository.dao.get(id)!!.body)
   assertTrue(engine.sync() is SyncStatus.Complete)
   api.post("/api/v1/notes/$id/append",buildJsonObject { put("text","SERVER added") })
   repository.update(id,"Synthetic integration PHONE\nBase second")
   assertTrue(engine.sync() is SyncStatus.Complete)
   val merged=repository.dao.get(id)!!.body
   assertTrue(merged.contains("PHONE"));assertTrue(merged.contains("SERVER added"))
   val history=HistoryRepository(repository,{config}) { auth -> ApiClient(auth,client) }
   assertEquals(HistoryRefreshResult.Refreshed(2),history.refresh(id))
   val canonical=repository.history(id).first();assertEquals(listOf(3,1),canonical.map { it.version });assertEquals(merged,canonical.first().body)
   assertEquals("Synthetic integration first\nBase second",canonical.last().body);assertTrue(canonical.all { it.historySource=="server" })
   val machineId=uuidV7();repository.putNote(Note(machineId,"Synthetic saved Ask output",machine=true,origin="plugin",sourceKind="plugin",sourceRef="ask",pinned=true,pinnedAt=System.currentTimeMillis()))
   val state=repository.dao.state()!!;repository.dao.putState(state.copy(epoch="synthetic-before-restore",cursor="synthetic-before-restore.0"))
   assertTrue(engine.sync() is SyncStatus.Complete)
   val restored=noteFromWire(api.get("/api/v1/notes/$machineId"))
   assertEquals("Synthetic saved Ask output",restored.body);assertTrue(restored.machine);assertEquals("plugin",restored.origin);assertTrue(restored.pinned)
   assertTrue(repository.dao.outbox().isEmpty())
  } finally {db.close();client.connectionPool.evictAll();client.dispatcher.executorService.shutdown()}
 }
}
