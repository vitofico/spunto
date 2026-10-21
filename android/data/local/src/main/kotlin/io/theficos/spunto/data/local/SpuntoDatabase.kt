package io.theficos.spunto.data.local

import android.content.Context
import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteQuery
import androidx.paging.PagingSource
import kotlinx.coroutines.flow.Flow
import io.theficos.spunto.core.model.*
import kotlinx.serialization.json.Json

@Entity(tableName = "notes", indices = [Index(value = ["id"], unique = true),Index(value=["trashed","archived","pinned","createdAt","id"]),Index(value=["trashed","createdAt","id"]),Index(value=["trashed","pinned","createdAt","id"]),Index(value=["trashed","trashedAt","createdAt","id"])])
data class NoteEntity(@PrimaryKey(autoGenerate = true) val rowid: Long = 0, val id: String, val body: String, val title: String, val foldedTitle: String, val foldedBody: String, val createdAt: Long, val updatedAt: Long, val serverRev: Int = 0, val pinned: Boolean = false, val archived: Boolean = false, val trashed: Boolean = false, val privateNote: Boolean = false, val machine: Boolean = false, val conflictOf: String? = null, val sourceUrl: String? = null, val sourceKind: String = "android", val sourceRef: String? = null, val pending: Boolean = false, val syncError: String? = null, val pinnedAt: Long = 0, val archivedAt: Long = 0, val trashedAt: Long = 0, val privateAt: Long = 0, val origin: String = "owner", val updatedViaAgent: Boolean = false, @ColumnInfo(defaultValue="0") val openTasks:Int=0) {
 fun note() = Note(id,body,title,createdAt,updatedAt,serverRev,pinned,archived,trashed,privateNote,machine,conflictOf,sourceUrl,sourceKind,sourceRef,pending,syncError,pinnedAt,archivedAt,trashedAt,privateAt,origin,updatedViaAgent)
 companion object { fun from(note: Note, rowid: Long = 0) = NoteEntity(rowid,note.id,note.body,note.title,fold(note.title),fold(note.body),note.createdAt,note.updatedAt,note.serverRev,note.pinned,note.archived,note.trashed,note.privateNote,note.machine,note.conflictOf,note.sourceUrl,note.sourceKind,note.sourceRef,note.pending,note.syncError,note.pinnedAt,note.archivedAt,note.trashedAt,note.privateAt,note.origin,note.updatedViaAgent,openTaskCount(note.body)) }
}
@Fts4(contentEntity = NoteEntity::class, tokenizer = FtsOptions.TOKENIZER_UNICODE61)
@Entity(tableName = "notes_fts")
data class NoteFts(val foldedTitle: String, val foldedBody: String)
@Entity(tableName = "outbox")
data class OutboxEntity(@PrimaryKey val opId: String, val noteId: String?, val payload: String, val createdAt: Long, val sent: Boolean = false, val error: String? = null)
@Entity(tableName = "drafts")
data class DraftEntity(@PrimaryKey val id: String, val body: String, val updatedAt: Long)
@Entity(tableName = "revisions")
data class RevisionEntity(@PrimaryKey val id: String = uuidV7(), val noteId: String, val body: String, val at: Long, val version: Int, @ColumnInfo(defaultValue="'legacy'") val historySource:String="legacy", @ColumnInfo(defaultValue="0") val lastEditAt:Long=0, val changedBy:String?=null, @ColumnInfo(defaultValue="'edit'") val changeKind:String="edit")
@Entity(tableName="server_revisions",primaryKeys=["serverUrl","epoch","noteId","version"])
data class ServerRevisionEntity(val serverUrl:String,val epoch:String,val noteId:String,val version:Int,val body:String,val changedAt:Long,val changedBy:String?,val changeKind:String)
@Entity(tableName="history_cache",primaryKeys=["serverUrl","epoch","noteId"])
data class HistoryCacheEntity(val serverUrl:String,val epoch:String,val noteId:String,val fetchedAt:Long,val maxVersion:Int)
@Entity(tableName = "labels", primaryKeys = ["noteId", "key"])
data class LabelEntity(val noteId: String, val key: String, val path: String)
@Entity(tableName = "sync_state")
data class SyncStateEntity(@PrimaryKey val id: Int = 1, val epoch: String = "", val cursor: String = "", val pluginsRev: Int = 0, val manifest: String = "{}", val catalog: String = "{}", val serverUrl: String = "", @ColumnInfo(defaultValue="5000000") val maxUploadBytes:Long=5_000_000L, @ColumnInfo(defaultValue="''") val replicaId:String="")
@Entity(tableName = "plugin_output", primaryKeys = ["kind", "id"])
data class PluginOutputEntity(val kind: String, val id: String, val noteId: String?, val payload: String)
@Entity(tableName="view_memberships",indices=[Index(value=["pluginId","viewId","noteId"])])
data class ViewMembershipEntity(@PrimaryKey val outputId:String,val noteId:String,val pluginId:String,val viewId:String)
@Entity(tableName = "tombstones")
data class TombstoneEntity(@PrimaryKey val id: String, val at: Long)
@Entity(tableName = "attachments")
data class AttachmentEntity(@PrimaryKey val id:String=uuidV7(),val noteId:String,val localPath:String,val mime:String,val remoteId:String?=null,
 @ColumnInfo(defaultValue="'image'") val kind:String="image",@ColumnInfo(defaultValue="0") val durationMs:Long=0,
 @ColumnInfo(defaultValue="0") val byteSize:Long=0,@ColumnInfo(defaultValue="''") val sha256:String="",
 @ColumnInfo(defaultValue="''") val serverUrl:String="",@ColumnInfo(defaultValue="''") val marker:String="",
 @ColumnInfo(defaultValue="'queued'") val status:String="queued",val error:String?=null,@ColumnInfo(defaultValue="''") val serverEpoch:String="")
@Entity(tableName="recording_drafts")
data class RecordingDraftEntity(@PrimaryKey val id:String,val draftId:String,val noteId:String?,val localPath:String,val finalPath:String,val serverUrl:String,val state:String,val createdAt:Long,val durationMs:Long=0,val byteSize:Long=0,val sha256:String="",val maximumBytes:Long=5_000_000L,val capturedText:Boolean=false,val error:String?=null,val baseBodyHash:String?=null,val baseVersion:Int?=null)
@Entity(tableName = "refresh_notes")
data class RefreshNoteEntity(@PrimaryKey val id: String)
@Entity(tableName = "sync_log")
data class SyncLogEntity(@PrimaryKey(autoGenerate = true) val id: Long = 0, val at: Long, val outcome: String, val reason: String?)

data class RankedNote(@Embedded val note: NoteEntity, val info: ByteArray)
@Dao
interface SpuntoDao {
 @Query("SELECT * FROM notes ORDER BY pinned DESC, createdAt DESC") fun notes(): Flow<List<NoteEntity>>
 @Query("SELECT * FROM notes WHERE trashed=0 AND archived=0 ORDER BY pinned DESC, createdAt DESC") fun paging(): PagingSource<Int,NoteEntity>
 @RawQuery(observedEntities=[NoteEntity::class,LabelEntity::class,ViewMembershipEntity::class]) fun filteredPaging(query:SupportSQLiteQuery):PagingSource<Int,NoteEntity>
 @RawQuery(observedEntities=[NoteEntity::class,LabelEntity::class,ViewMembershipEntity::class]) suspend fun filteredNotes(query:SupportSQLiteQuery):List<NoteEntity>
 @Query("SELECT * FROM notes WHERE id=:id") fun observe(id: String): Flow<NoteEntity?>
 @Query("SELECT * FROM notes WHERE conflictOf IS NOT NULL AND trashed=0 ORDER BY updatedAt DESC") fun conflicts(): Flow<List<NoteEntity>>
 @Query("SELECT * FROM notes WHERE id=:id") suspend fun get(id: String): NoteEntity?
 @Query("SELECT * FROM notes") suspend fun all(): List<NoteEntity>
 @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun put(note: NoteEntity): Long
 @Query("DELETE FROM notes WHERE id=:id") suspend fun removeNoteRow(id:String)
 @Query("DELETE FROM revisions WHERE noteId=:id") suspend fun clearLocalHistory(id:String)
 @Query("DELETE FROM server_revisions WHERE noteId=:id") suspend fun clearCachedHistory(id:String)
 @Query("DELETE FROM history_cache WHERE noteId=:id") suspend fun clearHistoryCache(id:String)
 @Query("DELETE FROM view_memberships WHERE noteId=:id") suspend fun clearNoteMemberships(id:String)
 @Transaction suspend fun remove(id:String) { removeNoteRow(id);clearLocalHistory(id);clearCachedHistory(id);clearHistoryCache(id);clearNoteMemberships(id) }
 @Query("SELECT notes.*, matchinfo(notes_fts, 'pcnalx') AS info FROM notes JOIN notes_fts ON notes.rowid=notes_fts.rowid WHERE notes_fts MATCH :query AND notes.trashed=0") suspend fun search(query: String): List<RankedNote>
 @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putOutbox(op: OutboxEntity)
 @Query("SELECT * FROM outbox ORDER BY createdAt, rowid") suspend fun outbox(): List<OutboxEntity>
 @Query("DELETE FROM outbox WHERE opId=:id") suspend fun removeOutbox(id: String)
 @Query("UPDATE outbox SET sent=1 WHERE opId IN (:ids)") suspend fun markSent(ids: List<String>)
 @Query("UPDATE outbox SET error=:error WHERE opId=:id") suspend fun reject(id: String,error: String)
 @Query("SELECT COUNT(*) FROM outbox WHERE error IS NULL") fun pendingCount(): Flow<Int>
 @Query("SELECT * FROM outbox WHERE error IS NOT NULL ORDER BY createdAt,rowid") fun refusedOutbox():Flow<List<OutboxEntity>>
 @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putDraft(draft: DraftEntity)
 @Query("SELECT * FROM drafts WHERE id=:id") fun draft(id: String): Flow<DraftEntity?>
 @Query("SELECT * FROM drafts WHERE id=:id") suspend fun draftSnapshot(id:String):DraftEntity?
 @Query("DELETE FROM drafts WHERE id=:id AND body=:body AND updatedAt=:updatedAt") suspend fun clearDraftSnapshot(id:String,body:String,updatedAt:Long)
 @Query("DELETE FROM drafts WHERE id=:id AND body=:body") suspend fun clearDraft(id: String, body: String)
 @Insert suspend fun putRevision(revision: RevisionEntity)
 @Query("SELECT * FROM revisions WHERE noteId=:id ORDER BY at DESC") fun history(id: String): Flow<List<RevisionEntity>>
 @Query("SELECT * FROM revisions WHERE noteId=:id ORDER BY lastEditAt DESC, at DESC, id DESC LIMIT 1") suspend fun latestRevision(id:String):RevisionEntity?
 @Query("UPDATE revisions SET lastEditAt=:at WHERE id=:id") suspend fun touchRevision(id:String,at:Long)
 @Query("SELECT * FROM outbox ORDER BY createdAt,rowid") fun observeOutbox():Flow<List<OutboxEntity>>
 @Insert(onConflict=OnConflictStrategy.REPLACE) suspend fun putServerRevisions(revisions:List<ServerRevisionEntity>)
 @Query("DELETE FROM server_revisions WHERE serverUrl=:serverUrl AND epoch=:epoch AND noteId=:noteId") suspend fun clearServerRevisions(serverUrl:String,epoch:String,noteId:String)
 @Query("SELECT r.* FROM server_revisions r JOIN sync_state s ON r.serverUrl=s.serverUrl AND r.epoch=s.epoch WHERE s.id=1 AND r.noteId=:id ORDER BY r.version DESC") fun serverHistory(id:String):Flow<List<ServerRevisionEntity>>
 @Insert(onConflict=OnConflictStrategy.REPLACE) suspend fun putHistoryCache(cache:HistoryCacheEntity)
 @Query("SELECT h.* FROM history_cache h JOIN sync_state s ON h.serverUrl=s.serverUrl AND h.epoch=s.epoch WHERE s.id=1 AND h.noteId=:id") suspend fun historyCacheSnapshot(id:String):HistoryCacheEntity?
 @Query("SELECT h.* FROM history_cache h JOIN sync_state s ON h.serverUrl=s.serverUrl AND h.epoch=s.epoch WHERE s.id=1 AND h.noteId=:id") fun historyCache(id:String):Flow<HistoryCacheEntity?>
 @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putLabels(labels: List<LabelEntity>)
 @Query("DELETE FROM labels WHERE noteId=:id") suspend fun clearLabels(id: String)
 @Query("""
  WITH RECURSIVE hierarchy(noteId, key, path, tailKey, tailPath, depth) AS (
   SELECT l.noteId,
    CASE WHEN instr(l.key,'/')=0 THEN l.key ELSE substr(l.key,1,instr(l.key,'/')-1) END,
    CASE WHEN instr(l.path,'/')=0 THEN l.path ELSE substr(l.path,1,instr(l.path,'/')-1) END,
    CASE WHEN instr(l.key,'/')=0 THEN '' ELSE substr(l.key,instr(l.key,'/')+1) END,
    CASE WHEN instr(l.path,'/')=0 THEN '' ELSE substr(l.path,instr(l.path,'/')+1) END, 1
   FROM labels l JOIN notes n ON n.id=l.noteId
   WHERE n.trashed=0 AND n.archived=0 AND NOT EXISTS (SELECT 1 FROM tombstones t WHERE t.id=n.id)
   UNION ALL
   SELECT noteId,
    key||'/'||CASE WHEN instr(tailKey,'/')=0 THEN tailKey ELSE substr(tailKey,1,instr(tailKey,'/')-1) END,
    path||'/'||CASE WHEN instr(tailPath,'/')=0 THEN tailPath ELSE substr(tailPath,1,instr(tailPath,'/')-1) END,
    CASE WHEN instr(tailKey,'/')=0 THEN '' ELSE substr(tailKey,instr(tailKey,'/')+1) END,
    CASE WHEN instr(tailPath,'/')=0 THEN '' ELSE substr(tailPath,instr(tailPath,'/')+1) END, depth+1
   FROM hierarchy WHERE tailKey!='' AND depth<5
  )
  SELECT key, MIN(path) AS path, COUNT(DISTINCT noteId) AS count
  FROM hierarchy GROUP BY key ORDER BY key
 """) fun labels(): Flow<List<LabelCount>>
 /** The "All notes" count beside the sheet row: the notes the label counts above see. */
 @Query("SELECT COUNT(*) FROM notes n WHERE n.trashed=0 AND n.archived=0 AND NOT EXISTS (SELECT 1 FROM tombstones t WHERE t.id=n.id)") fun noteCount(): Flow<Int>
 /** L1 order: labels on notes edited since [since] first, then by overall use; a use is a note outside the trash that carries the label. */
 @Query("""
  SELECT l.key, MIN(l.path) AS path, COUNT(DISTINCT l.noteId) AS count FROM labels l JOIN notes n ON n.id=l.noteId
  WHERE n.trashed=0 AND NOT EXISTS (SELECT 1 FROM tombstones t WHERE t.id=n.id)
  GROUP BY l.key ORDER BY COUNT(DISTINCT CASE WHEN n.updatedAt>=:since THEN l.noteId END) DESC, count DESC, l.key LIMIT :limit
 """) fun labelsByUse(since: Long, limit: Int): Flow<List<LabelCount>>
 @Query("SELECT * FROM sync_state WHERE id=1") suspend fun state(): SyncStateEntity?
 @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putState(state: SyncStateEntity)
 @Query("SELECT * FROM sync_state WHERE id=1") fun observeState(): Flow<SyncStateEntity?>
 @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putOutputRow(output:PluginOutputEntity)
 @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putMembership(membership:ViewMembershipEntity)
 @Query("DELETE FROM view_memberships WHERE outputId=:id") suspend fun clearMembership(id:String)
 @Transaction suspend fun putOutput(output:PluginOutputEntity) { putOutputRow(output);if(output.kind=="annotation") { clearMembership(output.id);viewMembership(output)?.let { putMembership(it) } } }
 @Query("DELETE FROM plugin_output WHERE kind=:kind AND id=:id") suspend fun removeOutputRow(kind:String,id:String)
 @Transaction suspend fun removeOutput(kind:String,id:String) { removeOutputRow(kind,id);if(kind=="annotation") clearMembership(id) }
 @Query("SELECT * FROM plugin_output WHERE kind=:kind AND id=:id") suspend fun output(kind: String,id: String): PluginOutputEntity?
 @Query("SELECT * FROM plugin_output") fun outputs(): Flow<List<PluginOutputEntity>>
 @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putTombstone(tombstone: TombstoneEntity)
 @Query("SELECT * FROM tombstones WHERE id=:id") suspend fun tombstone(id: String): TombstoneEntity?
 @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putAttachment(attachment: AttachmentEntity)
 @Query("SELECT * FROM attachments WHERE remoteId IS NULL AND status IN ('queued','uploading','failed','paused')") suspend fun pendingAttachments(): List<AttachmentEntity>
 @Query("SELECT * FROM attachments WHERE noteId=:id") fun attachments(id: String): Flow<List<AttachmentEntity>>
 @Query("SELECT * FROM attachments") suspend fun attachmentSnapshots():List<AttachmentEntity>
 @Query("SELECT * FROM attachments WHERE id=:id") suspend fun attachment(id:String):AttachmentEntity?
 @Query("DELETE FROM attachments WHERE id=:id") suspend fun removeAttachment(id:String)
 @Query("SELECT COUNT(*) FROM attachments WHERE localPath=:path") suspend fun attachmentReferences(path:String):Int
 @Insert(onConflict=OnConflictStrategy.REPLACE) suspend fun putRecording(draft:RecordingDraftEntity)
 @Query("SELECT * FROM recording_drafts WHERE localPath=:path OR finalPath=:path LIMIT 1") suspend fun recordingFile(path:String):RecordingDraftEntity?
 @Query("SELECT * FROM recording_drafts WHERE id=:id") suspend fun recording(id:String):RecordingDraftEntity?
 @Query("SELECT * FROM recording_drafts WHERE state IN ('capturing','interrupted','ready','recoverable') ORDER BY createdAt") fun recordings():Flow<List<RecordingDraftEntity>>
 @Query("SELECT * FROM recording_drafts WHERE state IN ('capturing','interrupted','ready','recoverable') ORDER BY createdAt") suspend fun recordingSnapshots():List<RecordingDraftEntity>
 @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putRefresh(note: RefreshNoteEntity)
 @Query("SELECT * FROM refresh_notes") suspend fun refreshNotes(): List<RefreshNoteEntity>
 @Query("DELETE FROM refresh_notes WHERE id=:id") suspend fun removeRefresh(id: String)
 @Insert suspend fun log(event: SyncLogEntity)
 @Query("DELETE FROM sync_log WHERE id NOT IN (SELECT id FROM sync_log ORDER BY id DESC LIMIT 200)") suspend fun trimLog()
 @Query("SELECT * FROM sync_log ORDER BY id DESC LIMIT 200") fun logs(): Flow<List<SyncLogEntity>>
}
@Database(entities=[NoteEntity::class, NoteFts::class, OutboxEntity::class, DraftEntity::class, RevisionEntity::class, ServerRevisionEntity::class, HistoryCacheEntity::class, ViewMembershipEntity::class, LabelEntity::class, SyncStateEntity::class, PluginOutputEntity::class, TombstoneEntity::class, AttachmentEntity::class, RecordingDraftEntity::class, RefreshNoteEntity::class, SyncLogEntity::class], version=6, exportSchema=false)
abstract class SpuntoDatabase: RoomDatabase() {
 abstract fun dao(): SpuntoDao
 companion object { fun open(context: Context,name:String="spunto.db") = Room.databaseBuilder(context.applicationContext,SpuntoDatabase::class.java,name).enableMultiInstanceInvalidation().addMigrations(MIGRATION_1_2,MIGRATION_2_3,MIGRATION_3_4,MIGRATION_4_5,MIGRATION_5_6).build() }
}
private fun ensureColumn(db:SupportSQLiteDatabase,table:String,column:String,definition:String) {
 val present=db.query("PRAGMA table_info(`$table`)").use { cursor -> var found=false;while(cursor.moveToNext()) if(cursor.getString(cursor.getColumnIndexOrThrow("name"))==column) found=true;found }
 if(!present) db.execSQL("ALTER TABLE `$table` ADD COLUMN `$column` $definition")
}
val MIGRATION_1_2=object:Migration(1,2) {
 override fun migrate(db:SupportSQLiteDatabase) {
  ensureColumn(db,"sync_state","catalog","TEXT NOT NULL DEFAULT '{}'")
  ensureColumn(db,"sync_state","serverUrl","TEXT NOT NULL DEFAULT ''")
  db.execSQL("CREATE TABLE IF NOT EXISTS refresh_notes (id TEXT NOT NULL PRIMARY KEY)")
 }
}
val MIGRATION_2_3=object:Migration(2,3) {
 override fun migrate(db:SupportSQLiteDatabase) {
  ensureColumn(db,"sync_state","catalog","TEXT NOT NULL DEFAULT '{}'")
  ensureColumn(db,"sync_state","serverUrl","TEXT NOT NULL DEFAULT ''")
  ensureColumn(db,"notes","updatedViaAgent","INTEGER NOT NULL DEFAULT 0")
 }
}
val MIGRATION_3_4=object:Migration(3,4) {
 override fun migrate(db:SupportSQLiteDatabase) {
  ensureColumn(db,"sync_state","serverUrl","TEXT NOT NULL DEFAULT ''")
  ensureColumn(db,"notes","openTasks","INTEGER NOT NULL DEFAULT 0")
  db.query("SELECT rowid,body FROM notes").use { rows -> while(rows.moveToNext()) db.execSQL("UPDATE notes SET openTasks=? WHERE rowid=?",arrayOf(openTaskCount(rows.getString(1)),rows.getLong(0))) }
  db.execSQL("CREATE INDEX IF NOT EXISTS index_notes_trashed_archived_pinned_createdAt_id ON notes(trashed,archived,pinned,createdAt,id)")
  db.execSQL("CREATE INDEX IF NOT EXISTS index_notes_trashed_createdAt_id ON notes(trashed,createdAt,id)")
  db.execSQL("CREATE INDEX IF NOT EXISTS index_notes_trashed_pinned_createdAt_id ON notes(trashed,pinned,createdAt,id)")
  db.execSQL("CREATE INDEX IF NOT EXISTS index_notes_trashed_trashedAt_createdAt_id ON notes(trashed,trashedAt,createdAt,id)")
  ensureColumn(db,"revisions","historySource","TEXT NOT NULL DEFAULT 'legacy'")
  ensureColumn(db,"revisions","lastEditAt","INTEGER NOT NULL DEFAULT 0")
  ensureColumn(db,"revisions","changedBy","TEXT")
  ensureColumn(db,"revisions","changeKind","TEXT NOT NULL DEFAULT 'edit'")
  db.execSQL("CREATE TABLE IF NOT EXISTS server_revisions (serverUrl TEXT NOT NULL, epoch TEXT NOT NULL, noteId TEXT NOT NULL, version INTEGER NOT NULL, body TEXT NOT NULL, changedAt INTEGER NOT NULL, changedBy TEXT, changeKind TEXT NOT NULL, PRIMARY KEY(serverUrl,epoch,noteId,version))")
  db.execSQL("CREATE TABLE IF NOT EXISTS history_cache (serverUrl TEXT NOT NULL, epoch TEXT NOT NULL, noteId TEXT NOT NULL, fetchedAt INTEGER NOT NULL, maxVersion INTEGER NOT NULL, PRIMARY KEY(serverUrl,epoch,noteId))")
  db.execSQL("CREATE TABLE IF NOT EXISTS view_memberships (outputId TEXT NOT NULL PRIMARY KEY,noteId TEXT NOT NULL,pluginId TEXT NOT NULL,viewId TEXT NOT NULL)")
  db.execSQL("CREATE INDEX IF NOT EXISTS index_view_memberships_pluginId_viewId_noteId ON view_memberships(pluginId,viewId,noteId)")
  db.query("SELECT id,noteId,payload FROM plugin_output WHERE kind='annotation'").use { rows -> while(rows.moveToNext()) viewMembership(PluginOutputEntity("annotation",rows.getString(0),if(rows.isNull(1)) null else rows.getString(1),rows.getString(2)))?.let { member -> db.execSQL("INSERT OR REPLACE INTO view_memberships VALUES (?,?,?,?)",arrayOf(member.outputId,member.noteId,member.pluginId,member.viewId)) } }
 }
}

val MIGRATION_4_5=object:Migration(4,5) {
 override fun migrate(db:SupportSQLiteDatabase) {
  ensureColumn(db,"sync_state","maxUploadBytes","INTEGER NOT NULL DEFAULT 5000000")
  ensureColumn(db,"attachments","kind","TEXT NOT NULL DEFAULT 'image'")
  ensureColumn(db,"attachments","durationMs","INTEGER NOT NULL DEFAULT 0")
  ensureColumn(db,"attachments","byteSize","INTEGER NOT NULL DEFAULT 0")
  ensureColumn(db,"attachments","sha256","TEXT NOT NULL DEFAULT ''")
  ensureColumn(db,"attachments","serverUrl","TEXT NOT NULL DEFAULT ''")
  ensureColumn(db,"attachments","marker","TEXT NOT NULL DEFAULT ''")
  ensureColumn(db,"attachments","status","TEXT NOT NULL DEFAULT 'queued'")
  ensureColumn(db,"attachments","error","TEXT")
  ensureColumn(db,"attachments","serverEpoch","TEXT NOT NULL DEFAULT ''")
  db.execSQL("UPDATE attachments SET status='uploaded' WHERE remoteId IS NOT NULL")
  // A retained binding and an actual live canonical link are required; never infer a root from new credentials.
  val binding=db.query("SELECT serverUrl,epoch FROM sync_state WHERE id=1").use {row ->if(row.moveToFirst()) row.getString(0) to row.getString(1) else null}
  if(binding!=null && binding.first.isNotEmpty()) db.query("SELECT a.id,a.remoteId,n.body FROM attachments a JOIN notes n ON n.id=a.noteId WHERE a.kind='image' AND a.mime LIKE 'image/%' AND a.status='uploaded' AND a.serverUrl='' AND a.marker='' AND n.trashed=0 AND NOT EXISTS (SELECT 1 FROM tombstones t WHERE t.id=n.id)").use {rows ->
   while(rows.moveToNext()) canonicalLegacyImageMarker(rows.getString(2),rows.getString(1))?.let {marker ->db.execSQL("UPDATE attachments SET serverUrl=?,serverEpoch=?,marker=? WHERE id=?",arrayOf(binding.first,binding.second,marker,rows.getString(0)))}
  }

  db.execSQL("CREATE TABLE IF NOT EXISTS recording_drafts (id TEXT NOT NULL PRIMARY KEY,draftId TEXT NOT NULL,noteId TEXT,localPath TEXT NOT NULL,finalPath TEXT NOT NULL,serverUrl TEXT NOT NULL,state TEXT NOT NULL,createdAt INTEGER NOT NULL,durationMs INTEGER NOT NULL,byteSize INTEGER NOT NULL,sha256 TEXT NOT NULL,maximumBytes INTEGER NOT NULL,capturedText INTEGER NOT NULL,error TEXT,baseBodyHash TEXT,baseVersion INTEGER)")
 }
}

val MIGRATION_5_6=object:Migration(5,6) {
 override fun migrate(db:SupportSQLiteDatabase) {ensureColumn(db,"sync_state","replicaId","TEXT NOT NULL DEFAULT ''")}
}
