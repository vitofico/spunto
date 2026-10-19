package io.theficos.spunto.data.sync

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.theficos.spunto.core.model.*
import io.theficos.spunto.data.local.*
import kotlinx.coroutines.*
import okhttp3.mockwebserver.*
import kotlinx.serialization.json.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class) @Config(sdk=[28])
class AttachmentUploaderTest {
 @get:Rule val timeout=org.junit.rules.Timeout(10,java.util.concurrent.TimeUnit.MINUTES)
 private val server=MockWebServer()
 private lateinit var db:SpuntoDatabase;private lateinit var repo:NotesRepository;private lateinit var config:AuthConfig;private lateinit var folder:File
 @Before fun setup() {server.start();db=Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(),SpuntoDatabase::class.java).allowMainThreadQueries().build();repo=NotesRepository(db);config=AuthConfig(server.url("/").toString().trimEnd('/'),"synthetic-token","device","Test",true);folder=kotlin.io.path.createTempDirectory("synthetic-uploads").toFile()}
 @After fun close() {server.shutdown();db.close();folder.listFiles()?.forEach {it.delete()};folder.delete()}
 private suspend fun item(privateNote:Boolean=false):AttachmentEntity {
  repo.dao.putState(SyncStateEntity(epoch="e",serverUrl=config.serverUrl,maxUploadBytes=20_000_000,manifest="{\"plugins\":[]}",catalog="""{"plugins":[{"id":"voice-memos","switch":"on","state":"waiting","permissions":["attachments.read","attachments.write","ai.transcribe","suggest"],"granted":["attachments.read","attachments.write","ai.transcribe","suggest"],"ai_destinations":{}}]}"""))
  val note=uuidV7();repo.putNote(Note(note,"Owner comment",privateNote=privateNote));val file=File(folder,uuidV7()+".ogg")
  val fixture=File(System.getProperty("spunto.fixtures")?:"/spec/fixtures","recording-synthetic-silence.ogg");file.writeBytes(fixture.readBytes())
  val id=repo.queueAttachment(note,file.absolutePath,"audio/ogg","recording");return repo.dao.attachment(id)!!
 }
 private fun response(remote:String=uuidV7())=MockResponse().setBody("""{"id":"$remote","snippet":"[synthetic.ogg](/a/$remote)"}""")
 @Test fun immutableAudioUploadUsesBearerAndRecordingDurationLinkWithPrivateFlagPreserved()=runBlocking {
  val item=item(true);val remote=uuidV7();server.enqueue(response(remote));AttachmentUploader(repo,config,ApiClient(config)).uploadAll()
  val note=repo.dao.get(item.noteId)!!;assertTrue(note.privateNote);assertEquals("Owner comment\n\n[Recording 0:02](/a/$remote)",note.body)
  assertEquals("uploaded",repo.dao.attachment(item.id)!!.status);assertEquals("e",repo.dao.attachment(item.id)!!.serverEpoch)
  val request=server.takeRequest();assertEquals("/api/v1/attachments",request.path);assertEquals("Bearer synthetic-token",request.getHeader("Authorization"));assertTrue(request.body.readUtf8().contains("audio/ogg"))
  assertTrue(folder.listFiles()!!.none {it.name.startsWith("spunto-upload-")})
 }
 @Test fun concurrentTypingIsPreservedWhileOnlyTheExactMarkerIsAcknowledged()=runBlocking {
  val item=item();val gate=CompletableDeferred<Unit>();server.dispatcher=object:Dispatcher() {override fun dispatch(request:RecordedRequest):MockResponse {runBlocking {gate.await()};return response()}}
  val upload=async(Dispatchers.Default) {AttachmentUploader(repo,config,ApiClient(config)).uploadAll()}
  withContext(Dispatchers.IO) {server.takeRequest()};repo.update(item.noteId,"Newer owner typing\n"+item.marker);gate.complete(Unit);withTimeout(3000) {upload.await()}
  assertTrue(repo.dao.get(item.noteId)!!.body.startsWith("Newer owner typing\n[Recording"));assertEquals("uploaded",repo.dao.attachment(item.id)!!.status)
 }
 @Test fun discardPurgeAndMarkerRemovalDuringUploadNeverRelinkOrResurrect()=runBlocking {
  for(mode in listOf("discard","purge","marker","trash")) {
   val item=item();val gate=CompletableDeferred<Unit>();server.dispatcher=object:Dispatcher() {override fun dispatch(request:RecordedRequest):MockResponse {runBlocking {gate.await()};return response()}}
   val upload=async(Dispatchers.Default) {AttachmentUploader(repo,config,ApiClient(config)).uploadAll()};withContext(Dispatchers.IO) {server.takeRequest()}
   when(mode) {"discard"->repo.discardAttachment(item.id);"purge"->{repo.dao.putTombstone(TombstoneEntity(item.noteId,1));repo.dao.remove(item.noteId)};"marker"->repo.update(item.noteId,"Owner removed link");else->repo.setFlags(item.noteId,trashed=true)}
   val body=repo.dao.get(item.noteId)?.body;gate.complete(Unit);withTimeout(3000) {upload.await()};assertEquals(mode,body,repo.dao.get(item.noteId)?.body)
   if(mode=="purge") assertNull(repo.dao.get(item.noteId));if(mode=="discard") assertNull(repo.dao.attachment(item.id)) else assertEquals("blocked",repo.dao.attachment(item.id)!!.status)
  }
 }
 @Test fun unauthorizedKeepsExactLocalBytesMarkerAndQueueForRetry()=runBlocking {
  val item=item();val before=File(item.localPath).readBytes();server.enqueue(MockResponse().setResponseCode(401))
  assertTrue(runCatching {AttachmentUploader(repo,config,ApiClient(config)).uploadAll()}.exceptionOrNull() is ApiException)
  assertEquals("auth_required",repo.dao.attachment(item.id)!!.error);assertTrue(item.marker in repo.dao.get(item.noteId)!!.body);assertArrayEquals(before,File(item.localPath).readBytes());assertNull(repo.dao.attachment(item.id)!!.remoteId)
  server.enqueue(response());AttachmentUploader(repo,config,ApiClient(config)).uploadAll();assertEquals("uploaded",repo.dao.attachment(item.id)!!.status)
 }
 @Test fun disabledOrDifferentInstanceDoesNotUploadAndMidflightCredentialChangeDoesNotLink()=runBlocking {
  val item=item();repo.dao.putState(repo.dao.state()!!.copy(manifest="{\"plugins\":[]}",catalog="{\"plugins\":[]}"));AttachmentUploader(repo,config,ApiClient(config)).uploadAll();assertEquals(0,server.requestCount);assertEquals("paused",repo.dao.attachment(item.id)!!.status)
  repo.dao.putState(repo.dao.state()!!.copy(serverUrl="https://other.example",manifest="{\"plugins\":[]}",catalog="""{"plugins":[{"id":"voice-memos","switch":"on","state":"waiting","permissions":["attachments.read","attachments.write","ai.transcribe","suggest"],"granted":["attachments.read","attachments.write","ai.transcribe","suggest"],"ai_destinations":{}}]}"""));AttachmentUploader(repo,config,ApiClient(config)).uploadAll();assertEquals(0,server.requestCount)
  repo.dao.putState(repo.dao.state()!!.copy(serverUrl=config.serverUrl));var current=config;val gate=CompletableDeferred<Unit>();server.dispatcher=object:Dispatcher() {override fun dispatch(request:RecordedRequest):MockResponse {runBlocking {gate.await()};return response()}}
  val upload=async(Dispatchers.Default) {AttachmentUploader(repo,config,ApiClient(config)) {current}.uploadAll()};withContext(Dispatchers.IO) {server.takeRequest()};current=config.copy(token="rotated-synthetic");gate.complete(Unit);withTimeout(3000) {upload.await()}
  assertTrue(item.marker in repo.dao.get(item.noteId)!!.body);assertEquals("credentials_changed",repo.dao.attachment(item.id)!!.error);assertNull(repo.dao.attachment(item.id)!!.remoteId)
 }
 @Test fun ownerDisableDuringNetworkPausesAudioWithoutLosingBytesOrCreatingLink()=runBlocking {
  val item=item();val gate=CompletableDeferred<Unit>();server.dispatcher=object:Dispatcher() {override fun dispatch(request:RecordedRequest):MockResponse {runBlocking {gate.await()};return response()}}
  val upload=async(Dispatchers.Default) {AttachmentUploader(repo,config,ApiClient(config)).uploadAll()};withContext(Dispatchers.IO) {server.takeRequest()};repo.dao.putState(repo.dao.state()!!.copy(manifest="{\"plugins\":[]}",catalog="{\"plugins\":[]}"));gate.complete(Unit);withTimeout(3000) {upload.await()}
  assertTrue(item.marker in repo.dao.get(item.noteId)!!.body);assertEquals("paused",repo.dao.attachment(item.id)!!.status);assertTrue(File(item.localPath).exists())
 }
 @Test fun legacyImageQueueConsumesServerSnippetWithoutAudioOrImageLinkDuplication()=runBlocking {
  repo.dao.putState(SyncStateEntity(epoch="e",serverUrl=config.serverUrl))
  val id=uuidV7();repo.putNote(Note(id,"Existing image comment"));val file=File(folder,"synthetic.jpg").apply {writeBytes(byteArrayOf(-1,-40,-1,0))}
  val image=AttachmentEntity(noteId=id,localPath=file.absolutePath,mime="image/jpeg");repo.dao.putAttachment(image)
  val remote=uuidV7();server.enqueue(MockResponse().setBody("""{"id":"$remote","snippet":"![Synthetic image](/a/$remote)"}"""))
  AttachmentUploader(repo,config,ApiClient(config)).uploadAll()
  assertEquals("Existing image comment\n\n![Synthetic image](/a/$remote)",repo.dao.get(id)!!.body);assertEquals("uploaded",repo.dao.attachment(image.id)!!.status)
  assertTrue(server.takeRequest().body.readUtf8().contains("filename=\"synthetic.jpg\""))
 }
 @Test fun cancellationClosesNetworkAndKeepsQueuedImmutableBytesAndMarker()=runBlocking {
  val item=item();server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
  val upload=launch(Dispatchers.Default) {AttachmentUploader(repo,config,ApiClient(config)).uploadAll()}
  withContext(Dispatchers.IO) {server.takeRequest()};withTimeout(2000) {upload.cancelAndJoin()}
  assertEquals("paused",repo.dao.attachment(item.id)!!.status);assertEquals("interrupted",repo.dao.attachment(item.id)!!.error)
  assertTrue(item.marker in repo.dao.get(item.noteId)!!.body);assertEquals(item.sha256,attachmentDigest(File(item.localPath),20_000_000).second);assertTrue(folder.listFiles()!!.none {it.name.startsWith("spunto-upload-")})
 }
 @Test fun changedLocalBlobCannotUploadOrReplaceItsNoteMarker()=runBlocking {
  val item=item();File(item.localPath).appendBytes(byteArrayOf(0));val body=repo.dao.get(item.noteId)!!.body
  AttachmentUploader(repo,config,ApiClient(config)).uploadAll()
  assertEquals(0,server.requestCount);assertEquals("failed",repo.dao.attachment(item.id)!!.status);assertEquals(body,repo.dao.get(item.noteId)!!.body);assertNull(repo.dao.attachment(item.id)!!.remoteId)
 }

}
