package io.theficos.spunto.data.sync
import io.theficos.spunto.core.model.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.*
import org.junit.*
import org.junit.Assert.*
import okhttp3.OkHttpClient
import okhttp3.EventListener
import okhttp3.Call
import java.util.concurrent.TimeUnit
import java.util.concurrent.ConcurrentLinkedQueue
import okio.Buffer
import okio.ForwardingSource
import okio.buffer
class ApiClientTest {
 @get:org.junit.Rule val timeout = org.junit.rules.Timeout(10, java.util.concurrent.TimeUnit.MINUTES)
 private val server=MockWebServer()
 @Before fun start() { server.start() }
 @After fun stop() { server.shutdown() }
 private fun api(token:String="test-synthetic")=ApiClient(AuthConfig(server.url("/").toString(),token,"device","Test",true))
 @Test fun pushUsesExactDtoAndBearerHeader() = runBlocking {
  server.enqueue(MockResponse().setBody("""{"epoch":"e","results":[{"op_id":"op","status":"applied","version":1}]}"""))
  assertEquals(1,api().push("e",listOf(OutboxOperation(opId="op",type="note.create",noteId="id",body="synthetic",createdAt=123,sourceKind="android"))).results.single().version)
  val req=server.takeRequest();assertEquals("Bearer test-synthetic",req.getHeader("Authorization"));assertEquals("/api/v1/sync/push",req.path)
  val op=Json.parseToJsonElement(req.body.readUtf8()).jsonObject["ops"]!!.jsonArray.single().jsonObject;assertEquals(123,op["created_at"]!!.jsonPrimitive.int);assertEquals("id",op["note_id"]!!.jsonPrimitive.content);assertFalse(op.containsKey("noteId"))
 }
 @Test fun signInUsesVersionedServerPath() = runBlocking {server.enqueue(MockResponse().setBody("""{"id":"d","token":"fixture","name":"Phone"}"""));api("").signIn("synthetic-password","Phone");assertEquals("/api/v1/auth/devices",server.takeRequest().path)}
 @Test fun signOutRevokesOnlyThisPhonesCredentialAndSaysWhenItCouldNot() = runBlocking {
  server.enqueue(MockResponse().setResponseCode(204));assertTrue(api().signOut())
  val request=server.takeRequest();assertEquals("POST",request.method);assertEquals("/api/v1/auth/logout",request.path);assertEquals("Bearer test-synthetic",request.getHeader("Authorization"))
  server.enqueue(MockResponse().setResponseCode(401).setBody("""{"code":"unauthenticated"}"""));assertTrue(api().signOut())
  server.enqueue(MockResponse().setResponseCode(503));assertFalse(api().signOut())
 }
 @Test fun unauthorizedRemainsTypedFailure() = runBlocking { server.enqueue(MockResponse().setResponseCode(401).setBody("""{"code":"unauthorized"}"""));try {api().meta();fail("Expected401") } catch(e:ApiException) { assertEquals(401,e.status);assertEquals("unauthorized",e.code) } }
 @Test fun pairingExchangesCodeInBody() = runBlocking { server.enqueue(MockResponse().setBody("""{"id":"d","token":"fixture","name":"Phone"}"""));assertEquals("d",api("").pairingExchange("synthetic","Phone").id); val request=server.takeRequest();assertEquals("/api/v1/auth/pairing/exchange",request.path);assertNull(request.getHeader("Authorization")) }
 @Test fun duplicateOriginalDoesNotRequireAnotherOperationId() = runBlocking { server.enqueue(MockResponse().setBody("""{"epoch":"e","results":[{"op_id":"op","status":"duplicate","original":{"status":"applied","version":1}}]}""")); assertEquals("applied",api().push("e",listOf(OutboxOperation(opId="op",type="note.create",noteId="id",body="fixture"))).results.single().original!!.status) }
 @Test fun cancelledLongPollClosesPromptly() = runBlocking { server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE));val job=launch(Dispatchers.Default){api().waitForChanges("e.1",2)};withContext(Dispatchers.IO){server.takeRequest()};withTimeout(2000){job.cancelAndJoin()} }
 @Test fun credentialPostNeverFollowsRedirectToAnotherServer() = runBlocking {val destination=MockWebServer();destination.start();try {server.enqueue(MockResponse().setResponseCode(307).setHeader("Location",destination.url("/steal")));try {api("").signIn("synthetic-password","Phone");fail("Redirect must be rejected")}catch(e:ApiException){assertEquals(307,e.status)};assertEquals(0,destination.requestCount)}finally{destination.shutdown()} }
 @Test fun latestAgentWireMetadataIsNormalized() { assertTrue(noteFromWire(buildJsonObject {put("id","fixture");put("body","owner-created");put("origin","owner");put("updated_via_agent",true)}).updatedViaAgent) }
 @Test fun plaintextNeedsExplicitConsent() { try {ApiClient(AuthConfig(server.url("/").toString(),"","",""));fail() } catch(e:IllegalArgumentException) {assertTrue(e.message!!.contains("Confirm"))} }
 @Test fun onlyExactSlowCommandPostsUseExtendedActualCallAndReadDeadlines() = runBlocking {
  val callTimeouts=ConcurrentLinkedQueue<Long>();val readTimeouts=ConcurrentLinkedQueue<Int>()
  val client=OkHttpClient.Builder().readTimeout(35,TimeUnit.SECONDS).callTimeout(40,TimeUnit.SECONDS)
   .eventListener(object:EventListener() { override fun callStart(call:Call) { callTimeouts.add(TimeUnit.NANOSECONDS.toSeconds(call.timeout().timeoutNanos())) } })
   .addInterceptor { chain -> readTimeouts.add(chain.readTimeoutMillis());chain.proceed(chain.request()) }.build()
  val api=ApiClient(AuthConfig(server.url("/").toString(),"test-synthetic","device","Test",true),client)
  try {
   val cases=listOf(
    Triple("POST","/api/v1/plugins/ask/commands/ask",true),
    Triple("POST","/api/v1/plugins/label-suggestions/commands/suggest-now",true),
    Triple("POST","/api/v1/plugins/label-gardener/commands/review-now",true),
    Triple("GET","/api/v1/plugins/ask/commands/ask",false),
    Triple("GET","/api/v1/plugins/label-suggestions/commands/suggest-now",false),
    Triple("GET","/api/v1/plugins/label-gardener/commands/review-now",false),
    Triple("POST","/api/v1/plugins/ask/commands/ask/",false),
    Triple("POST","/api/v1/plugins/label-suggestions/commands/suggest-now/",false),
    Triple("POST","/api/v1/plugins/label-gardener/commands/review-now/",false),
    Triple("POST","/api/v1/plugins/label-suggestions/commands/suggest-now-extra",true),
    Triple("POST","/api/v1/plugins/label-gardener/commands/review-now-extra",true),
    Triple("POST","/api/v1/sync/push",false),
    Triple("GET","/api/v1/changes/wait",false),
    Triple("POST","/api/v1/plugins/other/commands/ask",true),
    Triple("POST","/api/v1/plugins/other/commands/suggest-now",true),
    Triple("POST","/api/v1/plugins/other/commands/review-now",true),
    Triple("POST","/api/v1/plugins/tidy/commands/tidy",true),
    Triple("POST","/api/v1/plugins/translate/commands/translate",true),
    Triple("POST","/api/v1/plugins/develop/commands/develop",true),
    Triple("POST","/api/v1/ai/check",true),
   )
   cases.forEach { (method,path,_) ->
    server.enqueue(MockResponse().setBody("{}"));api.request(path,method)
   }
   assertEquals(cases.map {if(it.third) 240L else 40L},callTimeouts.toList())
   assertEquals(cases.map {if(it.third) 240_000 else 35_000},readTimeouts.toList())
  } finally { client.connectionPool.evictAll();client.dispatcher.executorService.shutdown() }
 }
 @Test fun delayedAskBodySucceedsBeyondInjectedNormalDeadline() = runBlocking {
  // Scale the normal deadline down for a fast regression; the preceding test asserts real35/40s values.
  val client=OkHttpClient.Builder().readTimeout(100,TimeUnit.MILLISECONDS).callTimeout(150,TimeUnit.MILLISECONDS).build()
  val api=ApiClient(AuthConfig(server.url("/").toString(),"test-synthetic","device","Test",true),client)
  try {
   server.enqueue(MockResponse().setBody("""{"answer":"Synthetic result"}""").setBodyDelay(300,TimeUnit.MILLISECONDS))
   assertEquals("Synthetic result",api.post("/api/v1/plugins/ask/commands/ask",buildJsonObject { put("question","Synthetic question") })["answer"]!!.jsonPrimitive.content)
   assertEquals("Bearer test-synthetic",server.takeRequest().getHeader("Authorization"))
   server.enqueue(MockResponse().setBody("{}").setBodyDelay(300,TimeUnit.MILLISECONDS))
   try { api.meta();fail("Ordinary requests must retain their short injected deadline") } catch(expected:java.io.IOException) { }
  } finally { client.connectionPool.evictAll();client.dispatcher.executorService.shutdown() }
 }
 @Test fun extendedAskStillCancelsPromptly() = runBlocking {
  server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
  val job=launch(Dispatchers.Default) { api().post("/api/v1/plugins/ask/commands/ask",buildJsonObject { put("question","Synthetic question") }) }
  withContext(Dispatchers.IO) { server.takeRequest() }
  withTimeout(2000) { job.cancelAndJoin() }
 }
 @Test fun delayedTagCommandBodiesSucceedBeyondInjectedNormalDeadline() = runBlocking {
  val client=OkHttpClient.Builder().readTimeout(100,TimeUnit.MILLISECONDS).callTimeout(150,TimeUnit.MILLISECONDS).build()
  val api=ApiClient(AuthConfig(server.url("/").toString(),"test-synthetic","device","Test",true),client)
  try {
   listOf("/api/v1/plugins/label-suggestions/commands/suggest-now","/api/v1/plugins/label-gardener/commands/review-now").forEach { path ->
    server.enqueue(MockResponse().setBody("""{"type":"text","text":"Synthetic tag result"}""").setBodyDelay(300,TimeUnit.MILLISECONDS))
    assertEquals("Synthetic tag result",api.post(path,buildJsonObject {put("note_id","synthetic-note")})["text"]!!.jsonPrimitive.content)
    val request=server.takeRequest(2,TimeUnit.SECONDS)!!
    assertEquals(path,request.path);assertEquals("Bearer test-synthetic",request.getHeader("Authorization"))
   }
  } finally {client.connectionPool.evictAll();client.dispatcher.executorService.shutdown()}
 }
 @Test fun extendedTagCommandsStillCancelPromptlyBeforeHeaders() = runBlocking {
  listOf("/api/v1/plugins/label-suggestions/commands/suggest-now","/api/v1/plugins/label-gardener/commands/review-now").forEach { path ->
   val started=CompletableDeferred<Call>()
   val client=OkHttpClient.Builder().eventListener(object:EventListener(){override fun callStart(call:Call){started.complete(call)}}).build()
   val api=ApiClient(AuthConfig(server.url("/").toString(),"test-synthetic","device","Test",true),client)
   server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
   val job=launch(Dispatchers.Default){api.post(path,buildJsonObject {put("note_id","synthetic-note")})}
   var actual:Call?=null
   try {
    actual=withTimeout(2000){started.await()}
    assertNotNull(withContext(Dispatchers.IO){server.takeRequest(2,TimeUnit.SECONDS)})
    withTimeout(2000){job.cancelAndJoin()}
    assertTrue("Cancellation must close the actual extended tag HTTP call",actual.isCanceled())
   } finally {
    job.cancel();actual?.cancel();withTimeout(2000){job.join()}
    client.connectionPool.evictAll();client.dispatcher.executorService.shutdown()
   }
  }
 }
 @Test fun extendedTagCommandsRetainRedirectRejection() = runBlocking {
  val destination=MockWebServer();destination.start()
  try {
   listOf("/api/v1/plugins/label-suggestions/commands/suggest-now","/api/v1/plugins/label-gardener/commands/review-now").forEach { path ->
    server.enqueue(MockResponse().setResponseCode(307).setHeader("Location",destination.url("/steal")))
    try {api().post(path,buildJsonObject {put("note_id","synthetic-note")});fail("Redirect must be rejected")}
    catch(error:ApiException){assertEquals(307,error.status)}
   }
   assertEquals(0,destination.requestCount)
  } finally {destination.shutdown()}
 }
 @Test fun cancellationAfterHeadersClosesActualCallDuringOrdinaryAndExtendedCommandBodyReads() = runBlocking {
  listOf("GET" to "/api/v1/meta","POST" to "/api/v1/plugins/ask/commands/ask","POST" to "/api/v1/plugins/label-suggestions/commands/suggest-now","POST" to "/api/v1/plugins/label-gardener/commands/review-now").forEach { (method,path) ->
   val headers=CompletableDeferred<Unit>();val reading=CompletableDeferred<Call>()
   val client=OkHttpClient.Builder().readTimeout(35,TimeUnit.SECONDS).callTimeout(40,TimeUnit.SECONDS).eventListener(object:EventListener() {
    override fun responseHeadersEnd(call:Call,response:okhttp3.Response) { headers.complete(Unit) }
   }).addInterceptor { chain ->
    val response=chain.proceed(chain.request())
    val original=response.body ?: return@addInterceptor response
    val source=object:ForwardingSource(original.source()) {
     override fun read(sink:Buffer,byteCount:Long):Long {
      // Signal before the underlying socket read, while the delayed body is still pending.
      reading.complete(chain.call())
      return super.read(sink,byteCount)
     }
    }.buffer()
    response.newBuilder().body(object:okhttp3.ResponseBody() {
     override fun contentType()=original.contentType()
     override fun contentLength()=original.contentLength()
     override fun source()=source
    }).build()
   }.build()
   val api=ApiClient(AuthConfig(server.url("/").toString(),"test-synthetic","device","Test",true),client)
   server.enqueue(MockResponse().setBody("{}").setBodyDelay(2,TimeUnit.SECONDS))
   val job=launch(Dispatchers.Default) { api.request(path,method) }
   var actual:Call?=null
   try {
    actual=withTimeout(2000) { reading.await() }
    assertTrue("Response headers must have arrived before cancellation",headers.isCompleted)
    assertFalse("The delayed response must not be decoded before cancellation",job.isCompleted)
    withTimeout(1000) { job.cancelAndJoin() }
    assertTrue("Cancellation must close the real HTTP call while its body is pending",actual.isCanceled())
    assertTrue(job.isCompleted)
   } finally {
    job.cancel();actual?.cancel();withTimeout(2000) { job.join() }
    client.connectionPool.evictAll();client.dispatcher.executorService.shutdown()
   }
  }
 }
 @Test fun privateAttachmentDownloadIsBoundedAuthenticatedAndAtomicallyCached()=runBlocking {
  val folder=kotlin.io.path.createTempDirectory("synthetic-download").toFile();val target=java.io.File(folder,"clip.ogg");val id=io.theficos.spunto.core.model.uuidV7()
  try {
   server.enqueue(MockResponse().setBody("Synthetic audio bytes"));api().downloadAttachment(id,target,100)
   assertEquals("Synthetic audio bytes",target.readText());val request=server.takeRequest();assertEquals("/a/$id",request.path);assertEquals("Bearer test-synthetic",request.getHeader("Authorization"));assertEquals(listOf("clip.ogg"),folder.listFiles()!!.map {it.name})
  } finally {folder.listFiles()?.forEach {it.delete()};folder.delete()}
 }
 @Test fun oversizedAttachmentDownloadNeverPublishesAFile()=runBlocking {
  val folder=kotlin.io.path.createTempDirectory("synthetic-download-limit").toFile();val target=java.io.File(folder,"clip.ogg")
  try {server.enqueue(MockResponse().setBody("Too many synthetic bytes"));val error=runCatching {api().downloadAttachment(io.theficos.spunto.core.model.uuidV7(),target,4)}.exceptionOrNull();assertTrue(error is ApiException);assertFalse(target.exists());assertTrue(folder.listFiles().isNullOrEmpty())}
  finally {folder.listFiles()?.forEach {it.delete()};folder.delete()}
 }
 @Test fun canceledAttachmentDownloadKeepsNoPartialCache()=runBlocking {
  val folder=kotlin.io.path.createTempDirectory("synthetic-download-cancel").toFile();val target=java.io.File(folder,"clip.ogg")
  try {
   server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE));val download=launch(Dispatchers.Default) {api().downloadAttachment(io.theficos.spunto.core.model.uuidV7(),target,100)}
   withContext(Dispatchers.IO) {server.takeRequest()};withTimeout(2000) {download.cancelAndJoin()};assertFalse(target.exists());assertTrue(folder.listFiles().isNullOrEmpty())
  } finally {folder.listFiles()?.forEach {it.delete()};folder.delete()}
 }

}
