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
import kotlinx.serialization.json.*
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
@RunWith(RobolectricTestRunner::class) @Config(sdk=[28])
class MigrationTest {
 @get:org.junit.Rule val timeout = org.junit.rules.Timeout(10, java.util.concurrent.TimeUnit.MINUTES)
 @Test fun legacyAndCurrentSchemasRetainWordsDraftsAndImmutableOutbox()=runBlocking {
  val context=ApplicationProvider.getApplicationContext<Context>()
  val template=Room.inMemoryDatabaseBuilder(context,SpuntoDatabase::class.java).allowMainThreadQueries().build()
  val schema=template.openHelper.writableDatabase
  val tables=listOf("notes","notes_fts","outbox","drafts","revisions","labels","sync_state","plugin_output","tombstones","attachments","sync_log","refresh_notes")
  val ddl=tables.associateWith { table -> schema.query("SELECT sql FROM sqlite_master WHERE name=?",arrayOf(table)).use { it.moveToFirst();it.getString(0) } }
  val supporting=schema.query("SELECT sql FROM sqlite_master WHERE type IN ('index','trigger') AND sql IS NOT NULL").use { cursor -> buildList { while(cursor.moveToNext()) add(cursor.getString(0)) } }
  val legacySupporting=supporting.filterNot { it.contains("view_memberships") || it.contains("index_notes_trashed_") }
  template.close()
  for(version in listOf(1,2,3)) for(hasBinding in listOf(false,true)) {
   val name="migration-$version-$hasBinding.db";context.deleteDatabase(name)
   val file=context.getDatabasePath(name);file.parentFile!!.mkdirs()
   val id=uuidV7();val op=OutboxOperation(type="note.create",noteId=id,body="Migration owner words #test\n- [ ] keep")
   val payload=Json.encodeToString(op)
   SQLiteDatabase.openOrCreateDatabase(file,null).use { old ->
    for((table,originalSql) in ddl) {
     if(version==1 && table=="refresh_notes")continue
     var sql=if(version<3) originalSql.replace(", `updatedViaAgent` INTEGER NOT NULL","") else originalSql
     sql=sql.replace(", `openTasks` INTEGER NOT NULL DEFAULT 0","")
     if(table=="attachments") sql=sql.replace(Regex(", `(?:kind|durationMs|byteSize|sha256|serverUrl|marker|status|error|serverEpoch)` [^,)]*"),"")
     if(table=="revisions") sql=sql.replace(", `historySource` TEXT NOT NULL DEFAULT 'legacy'","").replace(", `lastEditAt` INTEGER NOT NULL DEFAULT 0","").replace(", `changedBy` TEXT","").replace(", `changeKind` TEXT NOT NULL DEFAULT 'edit'","")
     if(table=="sync_state") {sql=sql.replace(", `maxUploadBytes` INTEGER NOT NULL DEFAULT 5000000","");if(!hasBinding)sql=sql.replace(", `serverUrl` TEXT NOT NULL","")}
     old.execSQL(sql)
    }
    legacySupporting.forEach(old::execSQL)
    old.insertOrThrow("notes",null,ContentValues().apply {put("id",id);put("body",op.body);put("title","Migration owner words #test");put("foldedTitle","migration owner words #test");put("foldedBody","migration owner words #test");put("createdAt",100L);put("updatedAt",101L);put("serverRev",0);put("pinned",0);put("archived",0);put("trashed",0);put("privateNote",1);put("machine",0);put("sourceKind","android");put("pending",1);put("pinnedAt",0L);put("archivedAt",0L);put("trashedAt",0L);put("privateAt",101L);put("origin","owner");if(version>=3)put("updatedViaAgent",0)})
    old.insertOrThrow("plugin_output",null,ContentValues().apply {put("kind","annotation");put("id","legacy-member");put("noteId",id);put("payload",buildJsonObject {put("plugin_id","migration-plugin");put("kind","member:reviewed")}.toString())})
    old.insertOrThrow("revisions",null,ContentValues().apply {put("id","legacy-revision");put("noteId",id);put("body","Earlier preserved owner words");put("at",99L);put("version",1)})
    old.insertOrThrow("outbox",null,ContentValues().apply {put("opId",op.opId);put("noteId",id);put("payload",payload);put("createdAt",102L);put("sent",1)})
    old.insertOrThrow("drafts",null,ContentValues().apply {put("id","capture");put("body","Draft words still waiting");put("updatedAt",103L)})
    old.insertOrThrow("sync_state",null,ContentValues().apply {put("id",1);put("epoch","old");put("cursor","old.5");put("pluginsRev",2);put("manifest","{}");put("catalog","{}");if(hasBinding)put("serverUrl","https://previous.example")})
    old.version=version
   }
   val upgraded=Room.databaseBuilder(context,SpuntoDatabase::class.java,name).addMigrations(MIGRATION_1_2,MIGRATION_2_3,MIGRATION_3_4,MIGRATION_4_5,MIGRATION_5_6).allowMainThreadQueries().build()
   try {
    val dao=upgraded.dao();assertEquals(op.body,dao.get(id)!!.body);assertTrue(dao.get(id)!!.privateNote);assertFalse(dao.get(id)!!.updatedViaAgent);assertEquals(1,dao.get(id)!!.openTasks)
    assertEquals(payload,dao.outbox().single().payload);assertTrue(dao.outbox().single().sent);assertEquals("Draft words still waiting",dao.draft("capture").first()!!.body)
    assertEquals(if(hasBinding)"https://previous.example" else "",dao.state()!!.serverUrl);assertEquals("old.5",dao.state()!!.cursor)
    val revision=dao.history(id).first().single();assertEquals("Earlier preserved owner words",revision.body);assertEquals("legacy",revision.historySource);assertEquals(0L,revision.lastEditAt)
    assertEquals(emptyList<ServerRevisionEntity>(),dao.serverHistory(id).first());assertNull(dao.historyCache(id).first())
    assertEquals(id,NotesRepository(upgraded).search("migration").single().id)
    assertEquals(id,dao.filteredNotes(timelineQuery(TimelineSpec(kind=TimelineKind.PLUGIN,pluginId="migration-plugin",filter=buildJsonObject {put("member","reviewed")}))).single().id)
   } finally {upgraded.close();context.deleteDatabase(name)}
  }
 }
 @Test fun actualRetainedEmulatorDatabaseUpgradesWithoutDroppingReplicaState()=runBlocking {
  val context=ApplicationProvider.getApplicationContext<Context>();val name="actual-emulator-legacy.db";context.deleteDatabase(name)
  val file=context.getDatabasePath(name);file.parentFile!!.mkdirs()
  javaClass.getResourceAsStream("/legacy-v1.db")!!.use { input -> file.outputStream().use { output -> input.copyTo(output) } }
  var drafts:List<String> = emptyList();var outbox:List<String> = emptyList();var count=0
  SQLiteDatabase.openDatabase(file.absolutePath,null,SQLiteDatabase.OPEN_READONLY).use { old ->
   assertEquals(1,old.version)
   drafts=old.rawQuery("SELECT id || ':' || body FROM drafts ORDER BY id",null).use { cursor -> buildList {while(cursor.moveToNext())add(cursor.getString(0))} }
   outbox=old.rawQuery("SELECT payload FROM outbox ORDER BY createdAt, rowid",null).use { cursor -> buildList {while(cursor.moveToNext())add(cursor.getString(0))} }
   count=old.rawQuery("SELECT COUNT(*) FROM notes",null).use {it.moveToFirst();it.getInt(0)}
  }
  val upgraded=Room.databaseBuilder(context,SpuntoDatabase::class.java,name).addMigrations(MIGRATION_1_2,MIGRATION_2_3,MIGRATION_3_4,MIGRATION_4_5,MIGRATION_5_6).allowMainThreadQueries().build()
  try {val db=upgraded.openHelper.writableDatabase
   assertEquals(count,upgraded.dao().all().size);assertEquals(outbox,upgraded.dao().outbox().map {it.payload})
   val retainedDrafts=db.query("SELECT id || ':' || body FROM drafts ORDER BY id").use {cursor->buildList{while(cursor.moveToNext())add(cursor.getString(0))}}
   assertEquals(drafts,retainedDrafts);assertEquals(6,db.version)
  } finally {upgraded.close();context.deleteDatabase(name)}
 }

}
