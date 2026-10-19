package io.theficos.spunto.data.sync

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.theficos.spunto.core.model.*
import io.theficos.spunto.data.local.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class) @Config(sdk=[28])
class RecordingEpochTest {
 @get:Rule val timeout=org.junit.rules.Timeout(10,java.util.concurrent.TimeUnit.MINUTES)
 @Test fun restoreEpochReuploadsImmutableRecordingAndRebasesOnlyLaterLinkEdit()=runBlocking {
  val server=MockWebServer();server.start();val db=Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(),SpuntoDatabase::class.java).allowMainThreadQueries().build()
  val repo=NotesRepository(db);val config=AuthConfig(server.url("/").toString().trimEnd('/'),"synthetic-token","device","Test",true)
  val folder=kotlin.io.path.createTempDirectory("synthetic-epoch").toFile();val file=File(folder,"synthetic.ogg")
  try {
   file.writeBytes(File(System.getProperty("spunto.fixtures")?:"/spec/fixtures","recording-synthetic-silence.ogg").readBytes())
   val metadata=inspectRecording(file,20_000_000);val id=uuidV7();val oldRemote=uuidV7();val remote=uuidV7();val oldLink="[Recording 0:02](/a/$oldRemote)";val original="Owner words retained\n\n"+oldLink
   repo.putNote(Note(id,original,serverRev=2,privateNote=true));repo.dao.putState(SyncStateEntity(epoch="old",cursor="old.1",serverUrl=config.serverUrl,manifest="{\"plugins_rev\":0,\"plugins\":[]}",catalog="""{"plugins_rev":0,"plugins":[{"id":"voice-memos","switch":"on","state":"waiting","permissions":["attachments.read","attachments.write","ai.transcribe","suggest"],"granted":["attachments.read","attachments.write","ai.transcribe","suggest"],"ai_destinations":{}}]}"""))
   repo.dao.putAttachment(AttachmentEntity(noteId=id,localPath=file.absolutePath,mime="audio/ogg",remoteId=oldRemote,kind="recording",durationMs=metadata.durationMs,byteSize=metadata.byteSize,sha256=metadata.sha256,serverUrl=config.serverUrl,marker=oldLink,status="uploaded",serverEpoch="old"))
   val pushes=mutableListOf<JsonObject>();var version=0
   server.dispatcher=object:Dispatcher() {override fun dispatch(request:RecordedRequest):MockResponse {
    val body=when(request.requestUrl!!.encodedPath) {
     "/api/v1/meta"->"{\"sync_epoch\":\"new\",\"max_upload_bytes\":20000000}"
     "/api/v1/sync/reconcile"->"{\"epoch\":\"new\",\"restore_ids\":[\"$id\"]}"
     "/api/v1/plugins/manifest"->"""{"plugins_rev":1,"plugins":[]}"""
     "/api/v1/plugins"->"""{"plugins_rev":1,"plugins":[{"id":"voice-memos","switch":"on","state":"waiting","permissions":["attachments.read","attachments.write","ai.transcribe","suggest"],"granted":["attachments.read","attachments.write","ai.transcribe","suggest"],"ai_destinations":{}}]}"""
     "/api/v1/attachments"->"{\"id\":\"$remote\",\"snippet\":\"[synthetic.ogg](/a/$remote)\"}"
     "/api/v1/sync/pull"->"{\"epoch\":\"new\",\"cursor\":\"new.2\",\"has_more\":false,\"plugins_rev\":1,\"items\":[]}"
     "/api/v1/sync/push"->{val ops=Json.parseToJsonElement(request.body.readUtf8()).jsonObject.getValue("ops").jsonArray.map {it.jsonObject};pushes.addAll(ops)
      buildJsonObject {put("epoch","new");put("results",buildJsonArray {ops.forEach {op->version++;add(buildJsonObject {put("op_id",op.getValue("op_id"));put("status","applied");put("version",version);put("note",buildJsonObject {put("id",id);put("body",op.getValue("body"));put("version",version);put("private",true)})})}})}.toString()}
     else->error("Unexpected path")
    }
    return MockResponse().setBody(body)
   }}
   assertTrue(SyncEngine(repo,{config}).sync() is SyncStatus.Complete)
   assertEquals(1,Json.parseToJsonElement(repo.dao.state()!!.catalog).jsonObject.getValue("plugins_rev").jsonPrimitive.int)
   assertEquals(listOf("note.restore","note.update"),pushes.map {it.getValue("type").jsonPrimitive.content});assertEquals(original,pushes.first().getValue("body").jsonPrimitive.content)
   assertEquals(1,pushes.last().getValue("base_version").jsonPrimitive.int)
   assertEquals("Owner words retained\n\n[Recording 0:02](/a/$remote)",repo.dao.get(id)!!.body);assertTrue(repo.dao.get(id)!!.privateNote);assertTrue(repo.dao.outbox().isEmpty());assertEquals(20_000_000L,repo.dao.state()!!.maxUploadBytes)
   assertEquals(remote,repo.dao.attachmentSnapshots().single().remoteId);assertEquals(metadata.sha256,inspectRecording(file,20_000_000).sha256)
  } finally {server.shutdown();db.close();file.delete();folder.delete()}
 }
}
