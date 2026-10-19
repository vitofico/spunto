package io.theficos.spunto.data.sync

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.theficos.spunto.core.model.uuidV7
import io.theficos.spunto.data.local.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class) @Config(sdk=[28])
class LegacyImageMigrationEpochTest {
 @get:Rule val timeout=org.junit.rules.Timeout(10,java.util.concurrent.TimeUnit.MINUTES)
 private data class Legacy(val noteId:String,val imageId:String,val remoteId:String,val file:File,val body:String)
 private suspend fun migrated(context:Context,name:String,root:String,folder:File):Pair<SpuntoDatabase,List<Legacy>> {
  val template=Room.inMemoryDatabaseBuilder(context,SpuntoDatabase::class.java).build();val schema=template.openHelper.writableDatabase
  val ddl=schema.query("SELECT name,sql FROM sqlite_master WHERE type='table' AND sql IS NOT NULL AND name NOT IN ('android_metadata','room_master_table','sqlite_sequence','recording_drafts') AND name NOT LIKE 'notes_fts_%'").use {cursor ->buildList {while(cursor.moveToNext()) add(cursor.getString(0) to cursor.getString(1))}}
  val extra=schema.query("SELECT sql FROM sqlite_master WHERE type IN ('index','trigger') AND sql IS NOT NULL").use {cursor ->buildList {while(cursor.moveToNext()) add(cursor.getString(0))}};template.close()
  val rows=listOf("linked","missing","trashed","tombstone").map {kind ->
   val id=uuidV7();val image=uuidV7();val remote=uuidV7();val marker="![Synthetic legacy image](/a/$remote)"
   Legacy(id,image,remote,File(folder,"$image.jpg").apply {writeBytes(byteArrayOf(-1,-40,-1,1))},if(kind=="missing") "Owner removed the legacy link" else "Original owner words\n\n$marker")
  }
  context.deleteDatabase(name);val path=context.getDatabasePath(name);path.parentFile!!.mkdirs()
  SQLiteDatabase.openOrCreateDatabase(path,null).use {old ->
   for((table,raw) in ddl) {
    var sql=raw
    if(table=="attachments") sql=sql.replace(Regex(", `(?:kind|durationMs|byteSize|sha256|serverUrl|marker|status|error|serverEpoch)` [^,)]*"),"")
    if(table=="sync_state") sql=sql.replace(", `maxUploadBytes` INTEGER NOT NULL DEFAULT 5000000","")
    old.execSQL(sql)
   }
   extra.forEach(old::execSQL)
   for((index,row) in rows.withIndex()) {
    old.insertOrThrow("notes",null,ContentValues().apply {put("id",row.noteId);put("body",row.body);put("title","Synthetic legacy image");put("foldedTitle","synthetic legacy image");put("foldedBody",row.body);put("createdAt",1L);put("updatedAt",2L);put("serverRev",2);put("pinned",0);put("archived",0);put("trashed",if(index==2) 1 else 0);put("privateNote",1);put("machine",0);put("sourceKind","android");put("pending",0);put("pinnedAt",0L);put("archivedAt",0L);put("trashedAt",if(index==2) 2L else 0L);put("privateAt",1L);put("origin","owner");put("updatedViaAgent",0);put("openTasks",0)})
    old.insertOrThrow("attachments",null,ContentValues().apply {put("id",row.imageId);put("noteId",row.noteId);put("localPath",row.file.absolutePath);put("mime","image/jpeg");put("remoteId",row.remoteId)})
    if(index==3) old.insertOrThrow("tombstones",null,ContentValues().apply {put("id",row.noteId);put("at",2L)})
   }
   old.insertOrThrow("sync_state",null,ContentValues().apply {put("id",1);put("epoch","old");put("cursor","old.2");put("pluginsRev",0);put("manifest","{\"plugins\":[]}");put("catalog","{\"plugins\":[]}");put("serverUrl",root)})
   old.version=4
  }
  return Room.databaseBuilder(context,SpuntoDatabase::class.java,name).addMigrations(MIGRATION_4_5,MIGRATION_5_6).build() to rows
 }
 @Test fun realFourToFiveMigrationThenEpochRestoresOnlyLinkedSameRootImageAndKeepsConcurrentWordsFlags()=runBlocking {
  val context=ApplicationProvider.getApplicationContext<Context>();val server=MockWebServer();server.start()
  val config=AuthConfig(server.url("/").toString().trimEnd('/'),"synthetic-token","device","Test",true);val folder=kotlin.io.path.createTempDirectory("legacy-epoch").toFile();val name="legacy-image-epoch.db"
  val (db,rows)=migrated(context,name,config.serverUrl,folder);val repo=NotesRepository(db);val gate=CompletableDeferred<Unit>();val entered=CompletableDeferred<Unit>();var syncing:Deferred<SyncStatus>?=null
  try {
   val linked=rows.first();val marker="![Synthetic legacy image](/a/${linked.remoteId})";val before=repo.dao.attachment(linked.imageId)!!
   assertEquals(config.serverUrl,before.serverUrl);assertEquals(marker,before.marker);assertEquals("old",before.serverEpoch)
   for(row in rows.drop(1)) {assertEquals("",repo.dao.attachment(row.imageId)!!.serverUrl);assertEquals("",repo.dao.attachment(row.imageId)!!.marker)}
   assertEquals(SyncStatus.Waiting("different_server"),SyncEngine(repo,{config.copy(serverUrl="http://different.invalid:18215")}).sync());assertEquals(0,server.requestCount);assertEquals(before,repo.dao.attachment(linked.imageId))
   val remote=uuidV7();val bodies=mutableMapOf<String,String>();val versions=mutableMapOf<String,Int>();val privateFlags=mutableMapOf<String,Boolean>();val trashedFlags=mutableMapOf<String,Boolean>();val pushes=mutableListOf<JsonObject>();var uploads=0
   server.dispatcher=object:Dispatcher() {override fun dispatch(request:RecordedRequest):MockResponse {
    val output=when(request.requestUrl!!.encodedPath) {
     "/api/v1/meta"->"{\"sync_epoch\":\"new\",\"max_upload_bytes\":20000000}"
     "/api/v1/sync/reconcile"->buildJsonObject {put("epoch","new");put("restore_ids",buildJsonArray {rows.dropLast(1).forEach {add(it.noteId)}})}.toString()
     "/api/v1/attachments"->{uploads++;entered.complete(Unit);runBlocking {gate.await()};"{\"id\":\"$remote\",\"snippet\":\"![Synthetic legacy image](/a/$remote)\"}"}
     "/api/v1/plugins/manifest","/api/v1/plugins"->"{\"plugins\":[]}"
     "/api/v1/sync/pull"->"{\"epoch\":\"new\",\"cursor\":\"new.9\",\"has_more\":false,\"plugins_rev\":0,\"items\":[]}"
     "/api/v1/sync/push"->{val operations=Json.parseToJsonElement(request.body.readUtf8()).jsonObject.getValue("ops").jsonArray.map {it.jsonObject};pushes.addAll(operations)
      buildJsonObject {put("epoch","new");put("results",buildJsonArray {operations.forEach {op ->val id=op.getValue("note_id").jsonPrimitive.content;val version=(versions[id]?:0)+1;versions[id]=version
       op["body"]?.jsonPrimitive?.content?.let {bodies[id]=it};op["private"]?.jsonPrimitive?.boolean?.let {privateFlags[id]=it};op["trashed"]?.jsonPrimitive?.boolean?.let {trashedFlags[id]=it}
       add(buildJsonObject {put("op_id",op.getValue("op_id"));put("status","applied");put("version",version);put("note",buildJsonObject {put("id",id);put("body",bodies.getValue(id));put("version",version);put("private",privateFlags[id]?:true);put("trashed",trashedFlags[id]?:false)})})
      }})}.toString()}
     else->error("Unexpected path")
    };return MockResponse().setBody(output)
   }}
   syncing=async(Dispatchers.Default) {SyncEngine(repo,{config}).sync()}
   withTimeout(3000) {entered.await()};repo.update(linked.noteId,"Later owner typing\n\n$marker");repo.setFlags(linked.noteId,privateNote=false);gate.complete(Unit)
   assertTrue(withTimeout(5000) {syncing!!.await()} is SyncStatus.Complete)
   assertEquals(1,uploads);val note=repo.dao.get(linked.noteId)!!
   assertEquals("Later owner typing\n\n![Synthetic legacy image](/a/$remote)",note.body);assertFalse(note.privateNote)
   assertEquals(remote,repo.dao.attachment(linked.imageId)!!.remoteId);assertEquals("uploaded",repo.dao.attachment(linked.imageId)!!.status);assertTrue(repo.dao.outbox().isEmpty())
   assertEquals(linked.body,pushes.first {it["note_id"]?.jsonPrimitive?.content==linked.noteId && it["type"]?.jsonPrimitive?.content=="note.restore"}.getValue("body").jsonPrimitive.content)
   assertEquals(1,pushes.first {it["note_id"]?.jsonPrimitive?.content==linked.noteId && it["type"]?.jsonPrimitive?.content=="note.update"}.getValue("base_version").jsonPrimitive.int)
   for(row in rows.drop(1)) assertEquals(row.remoteId,repo.dao.attachment(row.imageId)!!.remoteId)
   assertEquals(rows[1].body,repo.dao.get(rows[1].noteId)!!.body);assertTrue(repo.dao.get(rows[2].noteId)!!.trashed);assertNotNull(repo.dao.tombstone(rows[3].noteId))
  } finally {gate.complete(Unit);syncing?.cancelAndJoin();db.close();context.deleteDatabase(name);server.shutdown();folder.listFiles()?.forEach {it.delete()};folder.delete()}
 }
 @Test fun unboundLegacyReplicaNeverGuessesImageOwnershipFromNewCredentials()=runBlocking {
  val context=ApplicationProvider.getApplicationContext<Context>();val folder=kotlin.io.path.createTempDirectory("unbound-legacy").toFile();val name="unbound-legacy-image.db"
  val (db,rows)=migrated(context,name,"",folder)
  try {for(row in rows) {val attachment=db.dao().attachment(row.imageId)!!;assertEquals("",attachment.serverUrl);assertEquals("",attachment.marker);assertEquals(row.remoteId,attachment.remoteId)}}
  finally {db.close();context.deleteDatabase(name);folder.listFiles()?.forEach {it.delete()};folder.delete()}
 }
}
