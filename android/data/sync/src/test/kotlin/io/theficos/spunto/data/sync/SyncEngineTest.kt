package io.theficos.spunto.data.sync
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.theficos.spunto.data.local.*
import io.theficos.spunto.core.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
@RunWith(RobolectricTestRunner::class) @Config(sdk=[28])
class SyncEngineTest {
 @get:org.junit.Rule val timeout = org.junit.rules.Timeout(10, java.util.concurrent.TimeUnit.MINUTES)
 private lateinit var db:SpuntoDatabase
 private lateinit var repo:NotesRepository
 private lateinit var server:MockWebServer
 @Before fun setup() {db=Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(),SpuntoDatabase::class.java).allowMainThreadQueries().build();repo=NotesRepository(db);server=MockWebServer();server.start()}
 @After fun stop() {server.shutdown();db.close()}
 private fun engine(on:NotesRepository=repo)=SyncEngine(on,{AuthConfig(server.url("/").toString(),"synthetic","d","Phone",true)})
 private fun response(body:String,code:Int=200)=MockResponse().setResponseCode(code).setBody(body)
 private fun note(id:String,body:String,version:Int=1)=buildJsonObject {put("id",id);put("body",body);put("version",version);put("created_at","2026-10-14T00:00:00Z");put("updated_at","2026-10-14T00:00:00Z")}
 @Test fun unauthorizedPreservesEntireReplicaAndOutbox()=runBlocking {
  val id=repo.create("offline");val before=repo.dao.outbox();server.enqueue(response("""{"code":"unauthorized"}""",401))
  assertEquals(SyncStatus.NeedsSignIn,engine().sync());assertEquals("offline",repo.dao.get(id)!!.body);assertEquals(before,repo.dao.outbox())
 }
 @Test fun ackCannotReplaceEditsMadeDuringPush()=runBlocking {
  val id=repo.create("first");val waiting=CountDownLatch(1);val release=CountDownLatch(1)
  server.dispatcher=object:Dispatcher(){override fun dispatch(request:RecordedRequest):MockResponse {
   return when(request.requestUrl!!.encodedPath){
    "/api/v1/meta"->response("""{"sync_epoch":"e"}""")
    "/api/v1/sync/push"->{val ops=Json.parseToJsonElement(request.body.readUtf8()).jsonObject["ops"]!!.jsonArray;val op=ops.single().jsonObject;if(op["type"]!!.jsonPrimitive.content=="note.create"){waiting.countDown();release.await(10,TimeUnit.SECONDS)};val body=op["body"]!!.jsonPrimitive.content;response(buildJsonObject {put("epoch","e");put("results",buildJsonArray{add(buildJsonObject {put("op_id",op["op_id"]!!);put("status","applied");put("version",if(body=="first")1 else 2);put("note",note(id,body,if(body=="first")1 else 2))})})}.toString())}
    "/api/v1/sync/pull"->response("""{"epoch":"e","cursor":"e.2","has_more":false,"plugins_rev":0,"items":[]}""")
    "/api/v1/plugins","/api/v1/plugins/manifest"->response("""{"plugins":[],"plugins_rev":0}""")
    else->response("{}",404)
   }
  }}
  val work=async(Dispatchers.Default){engine().sync()};assertTrue(withContext(Dispatchers.IO){waiting.await(10,TimeUnit.SECONDS)});repo.update(id,"second");release.countDown();assertTrue(work.await() is SyncStatus.Complete)
  assertEquals("second",repo.dao.get(id)!!.body);assertEquals(2,repo.dao.get(id)!!.serverRev);assertTrue(repo.dao.outbox().isEmpty())
 }
 @Test fun completeSyncThatFindsNothingNewIsNotRepeated()=runBlocking {
  // A server whose change_seq moved without a row (a plugin switch) used to answer every wait with "changed".
  val url=server.url("/").toString().trimEnd('/');repo.dao.putState(SyncStateEntity(epoch="e",cursor="e.4",pluginsRev=1,serverUrl=url))
  var pulls=0;val waits=java.util.Collections.synchronizedList(mutableListOf<String>())
  server.dispatcher=object:Dispatcher(){override fun dispatch(request:RecordedRequest):MockResponse=when(request.requestUrl!!.encodedPath){
   "/api/v1/meta"->response("""{"sync_epoch":"e"}""")
   "/api/v1/sync/pull"->{pulls++;response("""{"epoch":"e","cursor":"e.4","has_more":false,"plugins_rev":1,"items":[]}""")}
   "/api/v1/changes/wait"->{val cursor=request.requestUrl!!.queryParameter("cursor")!!;waits+=cursor;response("""{"cursor":"e.5","plugins_rev":1,"changed":${cursor!="e.5"}}""").setHeadersDelay(50,TimeUnit.MILLISECONDS)}
   "/api/v1/plugins","/api/v1/plugins/manifest"->response("""{"plugins":[]}""")
   else->response("{}",404)
  }}
  val waiter=launch(Dispatchers.Default) {ChangeWaiter({AuthConfig(server.url("/").toString(),"synthetic","d","Phone",true)},repo,engine()).run()}
  withContext(Dispatchers.IO) {Thread.sleep(1500)};waiter.cancelAndJoin()
  assertEquals(1,pulls);assertEquals("e.4",waits.first());assertTrue(waits.size>1 && waits.drop(1).all {it=="e.5"})
 }
 @Test fun openEditorCrossingAWebEditKeepsBothAndTypesIntoTheCopy()=runBlocking {
  val id=uuidV7();val copy=uuidV7();repo.putNote(Note(id,"Phone words",serverRev=1));repo.dao.putState(SyncStateEntity(epoch="e",cursor="e.0"))
  val editor=repo.beginEditor(id);repo.openEditor(editor)
  // Background sync runs on its own repository, as the sync worker does, so it can't reach the editor directly.
  val worker=NotesRepository(repo.db,repo.replicaId)
  var pull=buildJsonArray {add(buildJsonObject {put("kind","note");put("change_seq",2);put("data",note(id,"Web words",2))})}
  val bases=mutableListOf<Int?>()
  server.dispatcher=object:Dispatcher(){override fun dispatch(request:RecordedRequest):MockResponse=when(request.requestUrl!!.encodedPath){
   "/api/v1/meta"->response("""{"sync_epoch":"e"}""")
   "/api/v1/notes/$id"->response(note(id,"Web words",2).toString())
   "/api/v1/sync/push"->{val op=Json.parseToJsonElement(request.body.readUtf8()).jsonObject["ops"]!!.jsonArray.single().jsonObject;synchronized(bases) {bases+=op["base_version"]?.jsonPrimitive?.intOrNull}
    response(buildJsonObject{put("epoch","e");put("results",buildJsonArray{add(buildJsonObject{put("op_id",op["op_id"]!!);put("status","conflict");put("version",2);put("conflict_id",copy);put("note",JsonObject(note(copy,op["body"]!!.jsonPrimitive.content,1)+mapOf("conflict_of" to JsonPrimitive(id))))})})}.toString())}
   "/api/v1/sync/pull"->response(buildJsonObject {put("epoch","e");put("cursor","e.2");put("has_more",false);put("plugins_rev",0);put("items",pull)}.toString()).also { pull=JsonArray(emptyList()) }
   "/api/v1/plugins","/api/v1/plugins/manifest"->response("""{"plugins":[]}""")
   else->response("{}",404)
  }}
  assertTrue(engine(worker).sync() is SyncStatus.Complete);assertEquals("Web words",repo.dao.get(id)!!.body)
  assertTrue(repo.persistEditor(repo.editorWrite(editor,"Phone words, more"),commit=true))
  assertTrue(engine(worker).sync() is SyncStatus.Complete)
  // The idle editor keeps its words and moves onto the copy instead of taking in the web edit.
  assertNull(repo.syncedForEditor(editor,"Phone words, more"));assertEquals(copy,editor.noteId)
  assertEquals(listOf<Int?>(1),bases);assertEquals("Web words",repo.dao.get(id)!!.body)
  assertEquals(listOf(copy to id),repo.conflicts.first().map {it.id to it.conflictOf});assertEquals("Phone words, more",repo.dao.get(copy)!!.body)
  // Later typing goes to the copy, not over the web edit again.
  assertTrue(repo.finishEditor(repo.editorWrite(editor,"Phone words, more and more")));assertEquals(copy,editor.noteId)
  val ops=repo.dao.outbox().map {Json.decodeFromString<OutboxOperation>(it.payload)}
  assertEquals(listOf(Triple("note.update",copy,1)),ops.map {Triple(it.type,it.noteId,it.baseVersion)});assertEquals("Web words",repo.dao.get(id)!!.body)
 }
 @Test fun conflictIsSurfacedAndKeepingTheCopySettlesItThroughTheOutbox()=runBlocking {
  val id=uuidV7();val copy=uuidV7();repo.putNote(Note(id,"saved",serverRev=1));repo.dao.putState(SyncStateEntity(epoch="e",cursor="e.0"));repo.update(id,"phone")
  server.dispatcher=object:Dispatcher(){override fun dispatch(request:RecordedRequest):MockResponse=when(request.requestUrl!!.encodedPath){
   "/api/v1/meta"->response("""{"sync_epoch":"e"}""")
   "/api/v1/notes/$id"->response(note(id,"other device",2).toString())
   "/api/v1/sync/push"->{val op=Json.parseToJsonElement(request.body.readUtf8()).jsonObject["ops"]!!.jsonArray.single().jsonObject
    response(buildJsonObject{put("epoch","e");put("results",buildJsonArray{add(buildJsonObject{put("op_id",op["op_id"]!!);put("status","conflict");put("version",2);put("conflict_id",copy);put("note",JsonObject(note(copy,"phone",1)+mapOf("conflict_of" to JsonPrimitive(id))))})})}.toString())}
   "/api/v1/sync/pull"->response("""{"epoch":"e","cursor":"e.2","has_more":false,"plugins_rev":0,"items":[]}""")
   "/api/v1/plugins","/api/v1/plugins/manifest"->response("""{"plugins":[]}""")
   else->response("{}",404)
  }}
  assertTrue(engine().sync() is SyncStatus.Complete)
  assertEquals(listOf(copy to id),repo.conflicts.first().map {it.id to it.conflictOf});assertEquals("other device",repo.dao.get(id)!!.body)
  repo.keepConflictCopy(copy)
  assertEquals("phone",repo.dao.get(id)!!.body);assertTrue(repo.dao.get(copy)!!.trashed);assertTrue(repo.conflicts.first().isEmpty())
  val ops=repo.dao.outbox().map {Json.decodeFromString<OutboxOperation>(it.payload)}
  assertEquals(listOf("note.update" to id,"note.flags" to copy),ops.map {it.type to it.noteId});assertEquals(2,ops.first().baseVersion)
 }
 @Test fun restoreIncludesChangedReplicaButSkipsKnownTombstones()=runBlocking {
  val id=repo.create("survives restore");val deleted=repo.create("deleted");repo.dao.outbox().forEach{repo.dao.removeOutbox(it.opId)};repo.dao.putTombstone(TombstoneEntity(deleted,1));repo.dao.putState(SyncStateEntity(epoch="old",cursor="old.5"))
  server.dispatcher=object:Dispatcher(){override fun dispatch(request:RecordedRequest):MockResponse=when(request.requestUrl!!.encodedPath){
   "/api/v1/meta"->response("""{"sync_epoch":"new"}""")
   "/api/v1/sync/reconcile"->{val notes=Json.parseToJsonElement(request.body.readUtf8()).jsonObject["notes"]!!.jsonArray;assertEquals(listOf(id),notes.map{it.jsonObject["id"]!!.jsonPrimitive.content});response("""{"epoch":"new","restore_ids":["$id"]}""")}
   "/api/v1/sync/push"->{val op=Json.parseToJsonElement(request.body.readUtf8()).jsonObject["ops"]!!.jsonArray.single().jsonObject;assertEquals("note.restore",op["type"]!!.jsonPrimitive.content);response("""{"epoch":"new","results":[{"op_id":${op["op_id"]},"status":"applied","version":1}]}""")}
   "/api/v1/sync/pull"->response("""{"epoch":"new","cursor":"new.1","has_more":false,"plugins_rev":0,"items":[]}""")
   "/api/v1/plugins","/api/v1/plugins/manifest"->response("""{"plugins":[]}""")
   else->response("{}",404)
  }}
  assertTrue(engine().sync() is SyncStatus.Complete);assertEquals("new.1",repo.dao.state()!!.cursor);assertEquals("survives restore",repo.dao.get(id)!!.body)
 }
 @Test fun typingAndFlagsDuringRestoreRemainQueuedUntilTheirOwnAck()=runBlocking {
  val id=repo.create("snapshot");repo.dao.putState(SyncStateEntity(epoch="old",cursor="old.9"));val paused=CountDownLatch(1);val release=CountDownLatch(1)
  var serverBody="";var archived=false;var version=0
  server.dispatcher=object:Dispatcher(){override fun dispatch(request:RecordedRequest):MockResponse=when(request.requestUrl!!.encodedPath){
   "/api/v1/meta"->response("""{"sync_epoch":"e"}""")
   "/api/v1/sync/reconcile"->response("""{"epoch":"e","restore_ids":["$id"]}""")
   "/api/v1/sync/push"->{
    val ops=Json.parseToJsonElement(request.body.readUtf8()).jsonObject["ops"]!!.jsonArray
    val results=ops.map { element -> val op=element.jsonObject;val type=op["type"]!!.jsonPrimitive.content
     if(type=="note.restore") {paused.countDown();release.await(10,TimeUnit.SECONDS);assertEquals(1,ops.size)}
     if(type=="note.update") assertEquals(1,op["base_version"]!!.jsonPrimitive.int)
     op["body"]?.jsonPrimitive?.content?.let {serverBody=it};op["archived"]?.jsonPrimitive?.boolean?.let{archived=it};version++
     buildJsonObject {put("op_id",op["op_id"]!!);put("status","applied");put("version",version);put("note",JsonObject(note(id,serverBody,version)+mapOf("archived" to JsonPrimitive(archived))))}
    };response(buildJsonObject{put("epoch","e");put("results",JsonArray(results))}.toString())
   }
   "/api/v1/sync/pull"->response("""{"epoch":"e","cursor":"e.3","has_more":false,"plugins_rev":0,"items":[]}""")
   "/api/v1/plugins","/api/v1/plugins/manifest"->response("""{"plugins":[]}""")
   else->response("{}",404)
  }}
  val job=async(Dispatchers.Default){engine().sync()};assertTrue(withContext(Dispatchers.IO){paused.await(10,TimeUnit.SECONDS)})
  repo.update(id,"later edit");repo.setFlags(id,archived=true);assertEquals(4,repo.dao.outbox().size);release.countDown();assertTrue(job.await() is SyncStatus.Complete)
  assertEquals("later edit",repo.dao.get(id)!!.body);assertTrue(repo.dao.get(id)!!.archived);assertTrue(repo.dao.outbox().isEmpty())
 }

 @Test fun restoreKeepsMachineProvenanceAndFlagClocks()=runBlocking {
  val id=uuidV7();repo.putNote(Note(id,"Synthetic Ask output",machine=true,origin="plugin",sourceKind="plugin",sourceRef="ask",serverRev=3,pinned=true,pinnedAt=100,privateAt=50));repo.dao.putState(SyncStateEntity(epoch="old",cursor="old.9"))
  server.dispatcher=object:Dispatcher(){override fun dispatch(request:RecordedRequest):MockResponse=when(request.requestUrl!!.encodedPath){
   "/api/v1/meta"->response("""{"sync_epoch":"e"}""")
   "/api/v1/sync/reconcile"->response("""{"epoch":"e","restore_ids":["$id"]}""")
   "/api/v1/sync/push"->{val op=Json.parseToJsonElement(request.body.readUtf8()).jsonObject["ops"]!!.jsonArray.single().jsonObject;assertTrue(op["machine"]!!.jsonPrimitive.boolean);assertEquals("plugin",op["origin"]!!.jsonPrimitive.content);assertEquals(100,op["pinned_at"]!!.jsonPrimitive.int);assertEquals(50,op["private_at"]!!.jsonPrimitive.int);response("""{"epoch":"e","results":[{"op_id":${op["op_id"]},"status":"applied","version":1}]}""")}
   "/api/v1/sync/pull"->response("""{"epoch":"e","cursor":"e.1","has_more":false,"plugins_rev":0,"items":[]}""")
   "/api/v1/plugins","/api/v1/plugins/manifest"->response("""{"plugins":[]}""")
   else->response("{}",404)
  }}
  assertTrue(engine().sync() is SyncStatus.Complete);assertTrue(repo.dao.get(id)!!.machine);assertEquals("plugin",repo.dao.get(id)!!.origin)
 }

 @Test fun lostCreateAcknowledgementRebasesLaterTypingBeforeSending()=runBlocking {
  val id=repo.create("first");var createId="";var pushCount=0;var serverBody="first"
  server.dispatcher=object:Dispatcher(){override fun dispatch(request:RecordedRequest):MockResponse=when(request.requestUrl!!.encodedPath){
   "/api/v1/meta"->response("""{"sync_epoch":"e"}""")
   "/api/v1/sync/push"->{val ops=Json.parseToJsonElement(request.body.readUtf8()).jsonObject["ops"]!!.jsonArray;assertEquals(1,ops.size);val op=ops.single().jsonObject;pushCount++
    when(pushCount){
     1->{createId=op["op_id"]!!.jsonPrimitive.content;response("""{"code":"unavailable"}""",503)}
     2->{assertEquals(createId,op["op_id"]!!.jsonPrimitive.content);response(buildJsonObject{put("epoch","e");put("results",buildJsonArray{add(buildJsonObject{put("op_id",createId);put("status","duplicate");put("original",buildJsonObject{put("status","applied");put("version",1);put("note",note(id,"first",1))})})})}.toString())}
     else->{assertEquals("note.update",op["type"]!!.jsonPrimitive.content);assertEquals(1,op["base_version"]!!.jsonPrimitive.int);serverBody=op["body"]!!.jsonPrimitive.content;response(buildJsonObject{put("epoch","e");put("results",buildJsonArray{add(buildJsonObject{put("op_id",op["op_id"]!!);put("status","applied");put("version",2);put("note",note(id,serverBody,2))})})}.toString())}
    }
   }
   "/api/v1/sync/pull"->response("""{"epoch":"e","cursor":"e.2","has_more":false,"plugins_rev":0,"items":[]}""")
   "/api/v1/plugins","/api/v1/plugins/manifest"->response("""{"plugins":[]}""")
   else->response("{}",404)
  }}
  assertTrue(engine().sync() is SyncStatus.Waiting);repo.update(id,"latest");assertTrue(engine().sync() is SyncStatus.Complete)
  assertEquals("latest",serverBody);assertEquals("latest",repo.dao.get(id)!!.body);assertTrue(repo.dao.outbox().isEmpty());assertEquals(3,pushCount)
 }
 @Test fun restoredConflictRebasesNewTypingToCopyVersion()=runBlocking {
  val original=uuidV7();val copy=uuidV7();repo.putNote(Note(original,"restored",serverRev=8));repo.dao.putState(SyncStateEntity(epoch="old",cursor="old.9"));val paused=CountDownLatch(1);val release=CountDownLatch(1);var serverCopy="restored"
  server.dispatcher=object:Dispatcher(){override fun dispatch(request:RecordedRequest):MockResponse=when(request.requestUrl!!.encodedPath){
   "/api/v1/meta"->response("""{"sync_epoch":"e"}""")
   "/api/v1/sync/reconcile"->response("""{"epoch":"e","restore_ids":["$original"]}""")
   "/api/v1/notes/$original"->response(note(original,"SERVER original",5).toString())
   "/api/v1/sync/push"->{val op=Json.parseToJsonElement(request.body.readUtf8()).jsonObject["ops"]!!.jsonArray.single().jsonObject
    if(op["type"]!!.jsonPrimitive.content=="note.restore") {paused.countDown();release.await(10,TimeUnit.SECONDS);response(buildJsonObject{put("epoch","e");put("results",buildJsonArray{add(buildJsonObject{put("op_id",op["op_id"]!!);put("status","conflict");put("version",5);put("conflict_id",copy);put("note",JsonObject(note(copy,"restored",1)+mapOf("conflict_of" to JsonPrimitive(original))))})})}.toString())}
    else {assertEquals(copy,op["note_id"]!!.jsonPrimitive.content);assertEquals(1,op["base_version"]!!.jsonPrimitive.int);serverCopy=op["body"]!!.jsonPrimitive.content;response(buildJsonObject{put("epoch","e");put("results",buildJsonArray{add(buildJsonObject{put("op_id",op["op_id"]!!);put("status","applied");put("version",2);put("note",JsonObject(note(copy,serverCopy,2)+mapOf("conflict_of" to JsonPrimitive(original))))})})}.toString())}
   }
   "/api/v1/sync/pull"->response("""{"epoch":"e","cursor":"e.2","has_more":false,"plugins_rev":0,"items":[]}""")
   "/api/v1/plugins","/api/v1/plugins/manifest"->response("""{"plugins":[]}""")
   else->response("{}",404)
  }}
  val job=async(Dispatchers.Default){engine().sync()};assertTrue(withContext(Dispatchers.IO){paused.await(10,TimeUnit.SECONDS)});repo.update(original,"new typing");release.countDown();assertTrue(job.await() is SyncStatus.Complete)
  assertEquals("SERVER original",repo.dao.get(original)!!.body);assertEquals("new typing",serverCopy);assertEquals("new typing",repo.dao.get(copy)!!.body);assertEquals(original,repo.dao.get(copy)!!.conflictOf);assertTrue(repo.dao.outbox().isEmpty())
 }

 @Test fun differentServerCannotReceivePreviousReplica()=runBlocking {val id=repo.create("private synthetic replica");val before=repo.dao.outbox();repo.dao.putState(SyncStateEntity(epoch="old",cursor="old.9",serverUrl="https://previous.example"));assertEquals(SyncStatus.Waiting("different_server"),engine().sync());assertEquals(0,server.requestCount);assertEquals(before,repo.dao.outbox());assertEquals("private synthetic replica",repo.dao.get(id)!!.body)}

 @Test fun mergedAckAndFlagAckNeverAdvanceAnUnseenBodyAncestor()=runBlocking {
  val id=uuidV7();val copy=uuidV7();repo.putNote(Note(id,"A\nB",serverRev=1));repo.dao.putState(SyncStateEntity(epoch="e",cursor="e.0"));repo.update(id,"A phone\nB");repo.setFlags(id,archived=true)
  val paused=CountDownLatch(1);val release=CountDownLatch(1);val merged="A phone\nB\nSERVER";var calls=0
  server.dispatcher=object:Dispatcher(){override fun dispatch(request:RecordedRequest):MockResponse=when(request.requestUrl!!.encodedPath){
   "/api/v1/meta"->response("""{"sync_epoch":"e"}""")
   "/api/v1/notes/$id"->response(note(id,merged,4).toString())
   "/api/v1/sync/push"->{val op=Json.parseToJsonElement(request.body.readUtf8()).jsonObject["ops"]!!.jsonArray.single().jsonObject;calls++
    val result=when(calls){
     1->buildJsonObject{put("op_id",op["op_id"]!!);put("status","merged");put("version",3);put("body",merged);put("note",note(id,merged,3))}
     2->{assertEquals("note.flags",op["type"]!!.jsonPrimitive.content);paused.countDown();release.await(10,TimeUnit.SECONDS);buildJsonObject{put("op_id",op["op_id"]!!);put("status","applied");put("version",4);put("note",note(id,merged,4))}}
     else->{assertEquals(1,op["base_version"]!!.jsonPrimitive.int);buildJsonObject{put("op_id",op["op_id"]!!);put("status","conflict");put("version",4);put("conflict_id",copy);put("note",JsonObject(note(copy,op["body"]!!.jsonPrimitive.content,1)+mapOf("conflict_of" to JsonPrimitive(id))))}}
    };response(buildJsonObject{put("epoch","e");put("results",buildJsonArray{add(result)})}.toString())
   }
   "/api/v1/sync/pull"->response("""{"epoch":"e","cursor":"e.4","has_more":false,"plugins_rev":0,"items":[]}""")
   "/api/v1/plugins","/api/v1/plugins/manifest"->response("""{"plugins":[]}""")
   else->response("{}",404)
  }}
  val job=async(Dispatchers.Default){engine().sync()};assertTrue(withContext(Dispatchers.IO){paused.await(10,TimeUnit.SECONDS)});repo.update(id,"A phone typed\nB");release.countDown();assertTrue(job.await() is SyncStatus.Complete)
  assertEquals(merged,repo.dao.get(id)!!.body);assertEquals("A phone typed\nB",repo.dao.get(copy)!!.body);assertEquals(3,calls);assertTrue(repo.dao.outbox().isEmpty())
 }

 @Test fun restoredPendingAppendTitleAndMergeDecideBeforeSnapshotsWithoutDoubling()=runBlocking {
  for(action in listOf("append_text","set_title","merge_notes")) for(mode in listOf("present","missing_proposal","missing_target")) {
   val id=uuidV7();val source=uuidV7();val proposal=uuidV7();val original="Original owner first line\nOriginal owner second line";val extra="Source owner words"
   repo.putNote(Note(id,original,serverRev=1));if(action=="merge_notes") repo.putNote(Note(source,extra,serverRev=1))
   val payload=when(action){"append_text"->buildJsonObject{put("text","APPENDED")};"set_title"->buildJsonObject{put("title","Title")};else->buildJsonObject{put("source_id",source);put("target_id",id);put("source_version",1);put("target_version",1)}}
   val proposalData=buildJsonObject{put("action",action);put("payload",payload);put("status","pending")}
   repo.dao.putOutput(PluginOutputEntity("suggestion",proposal,id,proposalData.toString()));repo.decideSuggestion(proposal,"accept")
   val expected=repo.dao.get(id)!!.body;var serverBody=if(mode=="missing_target") "" else original;var decisions=0;var restores=0;var sourceTrashed=false
   repo.dao.putState(SyncStateEntity(epoch="old",cursor="old.9"))
   server.dispatcher=object:Dispatcher(){override fun dispatch(request:RecordedRequest):MockResponse=when(request.requestUrl!!.encodedPath){
    "/api/v1/meta"->response("""{"sync_epoch":"e"}""")
    "/api/v1/sync/reconcile"->response(buildJsonObject{put("epoch","e");put("restore_ids",buildJsonArray{add(id);if(action=="merge_notes")add(source)})}.toString())
    "/api/v1/sync/push"->{val ops=Json.parseToJsonElement(request.body.readUtf8()).jsonObject["ops"]!!.jsonArray;val results=ops.map { element -> val op=element.jsonObject
     if(op["type"]!!.jsonPrimitive.content=="suggestion.decide") {assertEquals(0,restores);decisions++;if(mode!="present") buildJsonObject{put("op_id",op["op_id"]!!);put("status","rejected");put("code","not_found")}else{serverBody=when(action){"append_text"->serverBody+"\n\nAPPENDED";"set_title"->"# Title\n\n"+serverBody;else->serverBody+"\n\n"+extra};if(action=="merge_notes")sourceTrashed=true;buildJsonObject{put("op_id",op["op_id"]!!);put("status","applied");put("version",2);put("note",note(id,serverBody,2))}}}
     else {assertEquals("note.restore",op["type"]!!.jsonPrimitive.content);assertEquals(1,decisions);restores++;val target=op["note_id"]!!.jsonPrimitive.content;val body=op["body"]!!.jsonPrimitive.content;if(target==id){serverBody=body}else if(target==source){sourceTrashed=op["trashed"]!!.jsonPrimitive.boolean};buildJsonObject{put("op_id",op["op_id"]!!);put("status","applied");put("version",2);put("note",JsonObject(note(target,body,2)+mapOf("trashed" to JsonPrimitive(target==source && sourceTrashed))))}}
    };response(buildJsonObject{put("epoch","e");put("results",JsonArray(results))}.toString())}
    "/api/v1/sync/pull"->response("""{"epoch":"e","cursor":"e.3","has_more":false,"plugins_rev":0,"items":[]}""")
    "/api/v1/plugins","/api/v1/plugins/manifest"->response("""{"plugins":[]}""")
    else->response("{}",404)
   }}
   assertTrue(engine().sync() is SyncStatus.Complete);assertEquals(expected,serverBody);assertTrue(serverBody.contains(original));assertEquals(1,decisions)
   if(action=="append_text")assertEquals(1,Regex("APPENDED").findAll(serverBody).count())
   if(action=="set_title")assertEquals(1,Regex("# Title").findAll(serverBody).count())
   if(action=="merge_notes"){assertEquals(1,Regex(extra).findAll(serverBody).count());assertTrue(sourceTrashed)}
   // Rejected decisions remain visible locally, then isolate the next synthetic case.
   repo.dao.outbox().forEach { queued -> if(queued.error!=null) repo.dao.removeOutbox(queued.opId) }
  }
 }

 private fun refusingServer(pushed:MutableList<JsonObject>,pull:JsonArray=JsonArray(emptyList()),notes:Map<String,JsonObject> =emptyMap(),result:(JsonObject)->JsonObject)=object:Dispatcher(){override fun dispatch(request:RecordedRequest):MockResponse=when(val path=request.requestUrl!!.encodedPath){
  "/api/v1/meta"->response("""{"sync_epoch":"e"}""")
  "/api/v1/sync/push"->{val ops=Json.parseToJsonElement(request.body.readUtf8()).jsonObject["ops"]!!.jsonArray.map {it.jsonObject};synchronized(pushed) {pushed+=ops};response(buildJsonObject {put("epoch","e");put("results",JsonArray(ops.map {op ->JsonObject(result(op)+("op_id" to op["op_id"]!!))}))}.toString())}
  "/api/v1/sync/pull"->response(buildJsonObject {put("epoch","e");put("cursor","e.2");put("has_more",false);put("plugins_rev",0);put("items",pull)}.toString())
  "/api/v1/plugins","/api/v1/plugins/manifest"->response("""{"plugins":[]}""")
  else->notes[path.removePrefix("/api/v1/notes/")]?.let {response(it.toString())} ?: response("{}",404)
 }}
 private fun applied(op:JsonObject,version:Int=(op["base_version"]?.jsonPrimitive?.int ?: 0)+1)=buildJsonObject {put("status","applied");put("version",version);put("note",note(op["note_id"]!!.jsonPrimitive.content,op["body"]!!.jsonPrimitive.content,version))}
 @Test fun refusedChangeLeavesTheWaitingCountKeepsItsWordsAndSettlesAsANewNote()=runBlocking {
  // Edited on this phone while another device deleted it: the server refuses the edit, and nothing can retry it.
  val id=uuidV7();repo.putNote(Note(id,"Server words",serverRev=1));repo.update(id,"Phone words");val pushed=mutableListOf<JsonObject>()
  val newer=note(id,"Newer server words",2)
  server.dispatcher=refusingServer(pushed,buildJsonArray {add(buildJsonObject {put("kind","note");put("change_seq",2);put("data",newer)})},mapOf(id to newer)) {op ->if(op["note_id"]!!.jsonPrimitive.content==id) buildJsonObject {put("status","rejected");put("code","not_found")} else applied(op)}
  assertEquals(SyncStatus.Complete(0),engine().sync());assertEquals(SyncStatus.Complete(0),engine().sync())
  assertEquals(1,pushed.size);assertEquals(0,repo.pendingCount.first());assertEquals(mapOf(id to "not_found"),repo.refused.first())
  assertEquals("Phone words",repo.dao.get(id)!!.body)
  val copy=repo.keepRefusedAsNewNote(id)
  assertEquals(emptyMap<String,String>(),repo.refused.first());assertEquals("Phone words",repo.dao.get(copy)!!.body);assertEquals(1,repo.pendingCount.first())
  assertEquals(SyncStatus.Complete(0),engine().sync())
  assertEquals("Newer server words",repo.dao.get(id)!!.body);assertFalse(repo.dao.get(id)!!.pending);assertFalse(repo.dao.get(copy)!!.pending);assertTrue(repo.dao.outbox().isEmpty())
 }
 @Test fun shortenedNotesSyncAfterTheServerRefusedThemAsTooLong()=runBlocking {
  val fresh=repo.create("x".repeat(100_001));val known=uuidV7();repo.putNote(Note(known,"Server words",serverRev=1));repo.update(known,"y".repeat(100_001));val pushed=mutableListOf<JsonObject>()
  server.dispatcher=refusingServer(pushed) {op ->if(op["body"]!!.jsonPrimitive.content.length>100_000) buildJsonObject {put("status","rejected");put("code","too_large")} else applied(op)}
  assertEquals(SyncStatus.Complete(0),engine().sync());assertEquals(mapOf(fresh to "too_large",known to "too_large"),repo.refused.first())
  repo.update(fresh,"Short now");repo.update(known,"Short too")
  assertEquals(SyncStatus.Complete(0),engine().sync())
  assertEquals(listOf("note.create","note.update","note.create","note.update"),pushed.map {it["type"]!!.jsonPrimitive.content})
  assertEquals(emptyMap<String,String>(),repo.refused.first());assertTrue(repo.dao.outbox().isEmpty())
  assertEquals("Short now",repo.dao.get(fresh)!!.body);assertEquals("Short too",repo.dao.get(known)!!.body);assertFalse(repo.dao.get(fresh)!!.pending);assertFalse(repo.dao.get(known)!!.pending)
 }
 @Test fun refusedDecisionLetsTheServerCopyWin()=runBlocking {
  val id=uuidV7();val proposal=uuidV7();repo.putNote(Note(id,"Owner words",serverRev=1));val pushed=mutableListOf<JsonObject>()
  repo.dao.putOutput(PluginOutputEntity("suggestion",proposal,id,buildJsonObject {put("action","append_text");put("payload",buildJsonObject {put("text","Machine words")});put("status","pending")}.toString()))
  repo.decideSuggestion(proposal,"accept");assertTrue(repo.dao.get(id)!!.body.endsWith("Machine words"))
  server.dispatcher=refusingServer(pushed,notes=mapOf(id to note(id,"Owner words\n\nDecided on the web",2))) {buildJsonObject {put("status","rejected");put("code","suggestion_decided")}}
  assertEquals(SyncStatus.Complete(0),engine().sync())
  assertEquals(emptyMap<String,String>(),repo.refused.first());assertTrue(repo.dao.outbox().isEmpty())
  assertEquals("Owner words\n\nDecided on the web",repo.dao.get(id)!!.body);assertFalse(repo.dao.get(id)!!.pending)
 }
 @Test fun decisionRefusedByAnOlderBuildIsSettledNotShownAsARefusedNote()=runBlocking {
  val id=uuidV7();val proposal=uuidV7();repo.putNote(Note(id,"Owner words",serverRev=1));val pushed=mutableListOf<JsonObject>()
  repo.dao.putOutput(PluginOutputEntity("suggestion",proposal,id,buildJsonObject {put("action","append_text");put("payload",buildJsonObject {put("text","Machine words")});put("status","pending")}.toString()))
  repo.decideSuggestion(proposal,"accept");repo.dao.reject(repo.dao.outbox().single().opId,"suggestion_decided")
  assertEquals(mapOf(id to "suggestion_decided"),repo.refused.first())
  server.dispatcher=refusingServer(pushed,notes=mapOf(id to note(id,"Owner words\n\nDecided on the web",2))) {applied(it)}
  assertEquals(SyncStatus.Complete(0),engine().sync())
  assertTrue(pushed.isEmpty());assertEquals(emptyMap<String,String>(),repo.refused.first());assertTrue(repo.dao.outbox().isEmpty())
  assertEquals("Owner words\n\nDecided on the web",repo.dao.get(id)!!.body);assertFalse(repo.dao.get(id)!!.pending)
 }
}
