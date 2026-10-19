package io.theficos.spunto.data.local

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.theficos.spunto.core.model.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class) @Config(sdk=[28])
class RecordingMigrationTest {
 @get:Rule val timeout=org.junit.rules.Timeout(10,java.util.concurrent.TimeUnit.MINUTES)
 @Test fun numberedFourToFivePreservesRetainedReplicaAndImageMetadata()=runBlocking {
  val context=ApplicationProvider.getApplicationContext<Context>();val template=Room.inMemoryDatabaseBuilder(context,SpuntoDatabase::class.java).build()
  val schema=template.openHelper.writableDatabase
  val ddl=schema.query("SELECT name,sql FROM sqlite_master WHERE type='table' AND sql IS NOT NULL AND name NOT IN ('android_metadata','room_master_table','sqlite_sequence','recording_drafts') AND name NOT LIKE 'notes_fts_%'").use {cursor->buildList {while(cursor.moveToNext()) add(cursor.getString(0) to cursor.getString(1))}}
  val extra=schema.query("SELECT sql FROM sqlite_master WHERE type IN ('index','trigger') AND sql IS NOT NULL").use {cursor->buildList {while(cursor.moveToNext()) add(cursor.getString(0))}}
  template.close();val name="synthetic-v4-recordings.db";context.deleteDatabase(name);val file=context.getDatabasePath(name);file.parentFile!!.mkdirs()
  val id=uuidV7();val image=uuidV7();val operation=OutboxOperation(type="note.update",noteId=id,body="Private pending owner words",baseVersion=3);val payload=Json.encodeToString(operation)
  SQLiteDatabase.openOrCreateDatabase(file,null).use {old ->
   for((table,raw) in ddl) {
    var sql=raw
    if(table=="attachments") sql=sql.replace(Regex(", `(?:kind|durationMs|byteSize|sha256|serverUrl|marker|status|error|serverEpoch)` [^,)]*"),"")
    if(table=="sync_state") sql=sql.replace(", `maxUploadBytes` INTEGER NOT NULL DEFAULT 5000000","")
    old.execSQL(sql)
   }
   extra.forEach(old::execSQL)
   old.insertOrThrow("notes",null,ContentValues().apply {put("id",id);put("body",operation.body);put("title","Private owner words");put("foldedTitle","private owner words");put("foldedBody","private pending owner words");put("createdAt",1L);put("updatedAt",2L);put("serverRev",3);put("pinned",1);put("archived",0);put("trashed",0);put("privateNote",1);put("machine",0);put("sourceKind","android");put("pending",1);put("pinnedAt",1L);put("archivedAt",0L);put("trashedAt",0L);put("privateAt",1L);put("origin","owner");put("updatedViaAgent",0);put("openTasks",0)})
   old.insertOrThrow("attachments",null,ContentValues().apply {put("id",image);put("noteId",id);put("localPath","/synthetic/local-image.jpg");put("mime","image/jpeg");put("remoteId","synthetic-remote")})
   old.insertOrThrow("outbox",null,ContentValues().apply {put("opId",operation.opId);put("noteId",id);put("payload",payload);put("createdAt",3L);put("sent",1)})
   old.insertOrThrow("drafts",null,ContentValues().apply {put("id","capture");put("body","Synthetic retained draft");put("updatedAt",4L)})
   old.insertOrThrow("sync_state",null,ContentValues().apply {put("id",1);put("epoch","e");put("cursor","e.42");put("pluginsRev",7);put("manifest","{}");put("catalog","{}");put("serverUrl","https://same.example")})
   old.execSQL("INSERT INTO server_revisions VALUES (?,?,?,?,?,?,?,?)",arrayOf("https://same.example","e",id,3,"Canonical owner words",1L,"device","edit"))
   old.execSQL("INSERT INTO history_cache VALUES (?,?,?,?,?)",arrayOf("https://same.example","e",id,2L,3));old.version=4
  }
  val upgraded=Room.databaseBuilder(context,SpuntoDatabase::class.java,name).addMigrations(MIGRATION_4_5,MIGRATION_5_6).allowMainThreadQueries().build()
  try {
   val dao=upgraded.dao();assertEquals(6,upgraded.openHelper.writableDatabase.version);assertEquals(operation.body,dao.get(id)!!.body);assertTrue(dao.get(id)!!.privateNote);assertTrue(dao.get(id)!!.pinned)
   assertEquals(payload,dao.outbox().single().payload);assertTrue(dao.outbox().single().sent);assertEquals("Synthetic retained draft",dao.draft("capture").first()!!.body)
   assertEquals("e.42",dao.state()!!.cursor);assertEquals("https://same.example",dao.state()!!.serverUrl);assertEquals(LEGACY_UPLOAD_LIMIT,dao.state()!!.maxUploadBytes)
   assertEquals("Canonical owner words",dao.serverHistory(id).first().single().body);assertEquals(3,dao.historyCache(id).first()!!.maxVersion)
   val attachment=dao.attachment(image)!!;assertEquals("image",attachment.kind);assertEquals("uploaded",attachment.status);assertEquals("synthetic-remote",attachment.remoteId);assertEquals("/synthetic/local-image.jpg",attachment.localPath);assertEquals(0L,attachment.durationMs)
   assertTrue(dao.recordingSnapshots().isEmpty())
  } finally {upgraded.close();context.deleteDatabase(name)}
 }
}
