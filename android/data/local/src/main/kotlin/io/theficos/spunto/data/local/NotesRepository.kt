package io.theficos.spunto.data.local

import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import androidx.paging.Pager
import androidx.paging.PagingConfig
import io.theficos.spunto.core.model.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.combine
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.*

/** A live editor owns ordered draft writes; retired editors cannot overwrite a restored note. */
class EditorLease internal constructor(val noteId:String?,val draftId:String) {
 internal val sequence=AtomicLong()
 internal var active=true
 internal var accepted=0L
 internal var committedBody:String?=null
}
/** Unsaved words only when written after the note's last change; an older draft was saved already or overtaken by a sync. */
fun DraftEntity.unsavedOver(noteUpdatedAt:Long?)=body.isNotEmpty() && (noteUpdatedAt==null || updatedAt>noteUpdatedAt)
data class EditorWrite internal constructor(val lease:EditorLease,val body:String,internal val sequence:Long)

class NotesRepository(val db: SpuntoDatabase, val replicaId:String="", private val onChange: () -> Unit = {}) {
 val dao = db.dao()
 private val editorMutex=Mutex()
 private val editors=mutableSetOf<EditorLease>()
 fun beginEditor(noteId:String?,draftId:String=noteId?:"capture"):EditorLease = synchronized(editors) {
  editors.filter { it.draftId==draftId }.forEach { it.active=false };editors.removeAll {it.draftId==draftId}
  EditorLease(noteId,draftId).also { editors+=it }
 }
 fun abandonEditor(lease:EditorLease) { synchronized(editors) {lease.active=false;editors.remove(lease)} }
 fun editorWrite(lease:EditorLease,body:String)=EditorWrite(lease,body,lease.sequence.incrementAndGet())
 /** Allocation occurs before dispatch; delayed callbacks cannot replace a newer accepted write. */
 suspend fun persistEditor(write:EditorWrite,commit:Boolean=false):Boolean=editorMutex.withLock { persistEditorLocked(write,commit) }
 private suspend fun persistEditorLocked(write:EditorWrite,commit:Boolean):Boolean {
  val lease=write.lease
  if(!synchronized(editors) {lease.active} || write.sequence<lease.accepted) return false
  val unavailable=db.withTransaction {
   val missing=commit && lease.noteId!=null && (dao.get(lease.noteId)==null || dao.tombstone(lease.noteId)!=null)
   // A disappeared note must not erase the last editor words; only the note/outbox commit is refused.
   dao.putDraft(DraftEntity(lease.draftId,write.body,System.currentTimeMillis()))
   if(commit && !missing && lease.noteId!=null) {
    if(dao.get(lease.noteId)?.body!=write.body) save(write.body,lease.noteId)
    // The note holds these words now; a draft left behind would reopen over newer synced text.
    dao.clearDraft(lease.draftId,write.body)
   }
   missing
  }
  lease.accepted=write.sequence
  require(!unavailable) { "This note is no longer available." }
  if(commit && lease.noteId!=null) lease.committedBody=write.body
  return true
 }
 /** Read/Save awaits this commit before the UI exposes Restore. Disposal may safely repeat it. */
 suspend fun finishEditor(write:EditorWrite):Boolean=editorMutex.withLock {
  val accepted=persistEditorLocked(write,commit=true)
  if(accepted) synchronized(editors) { write.lease.active=false;editors.remove(write.lease) }
  accepted
 }
 /** Recording Save retires only the exact editor write whose body was attached. */
 internal suspend fun <T> retainRecordingEditor(write:EditorWrite?,save:suspend ()->T):T=editorMutex.withLock {
  if(write==null) return@withLock save()
  val lease=write.lease
  fun current()=synchronized(editors) {lease.active && write.sequence>=lease.accepted && write.sequence==lease.sequence.get()}
  require(current()) {"The editor changed. Save the current text with this recording."}
  val result=db.withTransaction {
   dao.putDraft(DraftEntity(lease.draftId,write.body,System.currentTimeMillis()))
   val value=save()
   require(current()) {"The editor changed while this recording was saved. Try again."}
   dao.draftSnapshot(lease.draftId)?.takeIf {it.body==write.body}?.let {dao.clearDraftSnapshot(it.id,it.body,it.updatedAt)}
   value
  }
  synchronized(editors) {lease.accepted=write.sequence;lease.active=false;editors.remove(lease)}
  result
 }
 val notes = dao.notes().map { rows -> rows.map { it.note() } }
 val pendingCount = dao.pendingCount()
 /** Notes with a change the server refused, and its reason: kept here, out of the waiting count, until the owner settles them. */
 val refused = dao.refusedOutbox().map { rows -> rows.filter { it.noteId!=null }.associate { it.noteId!! to it.error!! } }
 /** The way out of a refused change that never loses words: the text becomes a new note, and the original follows the server again. */
 suspend fun keepRefusedAsNewNote(id:String):String = db.withTransaction {
  val note=dao.get(id)?.note(); require(note!=null) { "This note is no longer available." }
  val copy=save(note.body)
  if(note.pinned || note.privateNote) setFlags(copy,pinned=note.pinned.takeIf { it },privateNote=note.privateNote.takeIf { it })
  dao.outbox().filter { it.noteId==id }.forEach { dao.removeOutbox(it.opId) }
  if(note.serverRev==0 || dao.tombstone(id)!=null) { dao.remove(id);dao.clearLabels(id) } else dao.putRefresh(RefreshNoteEntity(id))
  copy
 }
 fun timeline() = notes
 fun observe(id: String) = dao.observe(id).map { it?.note() }
 fun paging(spec:TimelineSpec=TimelineSpec()) = Pager(PagingConfig(pageSize=40,enablePlaceholders=false)) { dao.filteredPaging(timelineQuery(spec)) }.flow
 fun labels() = dao.labels()
 /** Conflicted copies the owner has not settled; settling one is an ordinary edit plus a trash flag, so it syncs like any change. */
 val conflicts = dao.conflicts().map { rows -> rows.map { it.note() } }
 suspend fun keepConflictCopy(copyId:String) = db.withTransaction {
  val copy=dao.get(copyId)?.note(); val original=copy?.conflictOf?.let { dao.get(it) }
  require(copy!=null && original!=null && dao.tombstone(original.id)==null) { "The original note is no longer on this phone." }
  save(copy.body,original.id); setFlags(copyId,trashed=true)
 }
 /** Canonical server rows own coalescing; pending local snapshots stay available offline. */
 fun history(id:String) = combine(dao.history(id),dao.serverHistory(id),dao.historyCache(id),dao.observe(id),dao.observeOutbox()) { local,server,cache,note,outbox ->
  if(note==null) emptyList() else {
   val pendingBody=outbox.any { row -> runCatching {
    val op=Json.decodeFromString<OutboxOperation>(row.payload)
    (op.noteId==id || id in op.affectedNoteIds) && (op.type in setOf("note.create","note.update","note.restore") || op.type=="suggestion.decide" && op.decision=="accept")
   }.getOrDefault(true) }
   val canonical=server.map { row -> RevisionEntity(id="server/"+row.epoch+"/"+row.version,noteId=id,body=row.body,at=row.changedAt,version=row.version,historySource="server",lastEditAt=row.changedAt,changedBy=row.changedBy,changeKind=row.changeKind) }
   val retained=if(cache!=null && cache.maxVersion>=note.serverRev && !pendingBody) local.filter { row -> row.historySource=="legacy" || (row.version==0 || row.historySource=="local" && row.changeKind=="restore") && canonical.none { it.body==row.body } } else local
   (canonical+retained).distinctBy { it.version to it.body }.sortedWith(compareByDescending<RevisionEntity> { it.at }.thenByDescending { it.version })
  }
 }
 fun draft(id: String = "capture") = dao.draft(id)
 fun outputs() = dao.outputs()
 suspend fun saveDraft(id: String = "capture", body: String) = editorMutex.withLock { dao.putDraft(DraftEntity(id,body,System.currentTimeMillis())) }
 suspend fun clearDraft(id: String, body: String) = editorMutex.withLock {dao.clearDraft(id,body)}
 suspend fun create(body: String, sourceUrl: String? = null, sourceKind: String = "android", sourceRef: String? = null): String = save(body,sourceUrl=sourceUrl,sourceKind=sourceKind,sourceRef=sourceRef)
 suspend fun update(id: String,body: String) { save(body,id) }
 suspend fun restoreHistory(id:String,revision:RevisionEntity) {
  require(revision.noteId==id)
  editorMutex.withLock {
   db.withTransaction {
    val before=dao.get(id)
    require(before!=null && dao.tombstone(id)==null) { "This note is no longer available." }
    val acknowledged=dao.draftSnapshot(id)?.takeIf { it.body==before.body }
    save(revision.body,id,historyKind="restore")
    acknowledged?.let { dao.clearDraftSnapshot(it.id,it.body,it.updatedAt) }
   }
   synchronized(editors) { editors.filter { it.noteId==id }.forEach { it.active=false };editors.removeAll { it.noteId==id } }
  }
 }
 suspend fun save(body: String,id: String? = null,sourceUrl: String? = null,sourceKind: String = "android",sourceRef: String? = null,historyKind:String="edit"): String {
  require(body.toByteArray().size <= 1_000_000) { "Note is too large." }
  val noteId = id ?: uuidV7()
  db.withTransaction {
   val old = dao.get(noteId); val now = System.currentTimeMillis()
   // A note whose creation the server refused (too long, say) is created again by its next save.
   val refusedCreate=dao.outbox().filter { it.noteId==noteId && it.error!=null && Json.decodeFromString<OutboxOperation>(it.payload).type=="note.create" }.onEach { dao.removeOutbox(it.opId) }
   val note = old?.note()?.copy(body=body,title=titleOf(body),updatedAt=now,pending=true,syncError=null,machine=old.machine && old.body==body,updatedViaAgent=false) ?: Note(noteId,body,sourceUrl=sourceUrl,sourceKind=sourceKind,sourceRef=sourceRef,pending=true)
   putNote(note,localEdit=true,historyKind=historyKind)
   enqueue(OutboxOperation(type=if(old==null || refusedCreate.isNotEmpty()) "note.create" else "note.update",noteId=noteId,body=body,baseVersion=old?.serverRev,createdAt=note.createdAt,sourceUrl=note.sourceUrl,sourceKind=note.sourceKind,sourceRef=note.sourceRef))
  }; onChange(); return noteId
 }
 suspend fun setFlags(id: String,pinned: Boolean?=null,archived: Boolean?=null,trashed: Boolean?=null,privateNote: Boolean?=null) {
  db.withTransaction { val note = dao.get(id)?.note() ?: return@withTransaction; val now=System.currentTimeMillis(); putNote(note.copy(pinned=pinned?:note.pinned,archived=archived?:note.archived,trashed=trashed?:note.trashed,privateNote=privateNote?:note.privateNote,updatedAt=now,pending=true,updatedViaAgent=false,pinnedAt=if(pinned!=null) now else note.pinnedAt,archivedAt=if(archived!=null) now else note.archivedAt,trashedAt=if(trashed!=null) now else note.trashedAt,privateAt=if(privateNote!=null) now else note.privateAt)); enqueue(OutboxOperation(type="note.flags",noteId=id,at=now,pinned=pinned,archived=archived,trashed=trashed,privateNote=privateNote)) }; onChange()
 }
 suspend fun setState(id: String,pinned: Boolean?=null,archived: Boolean?=null,trashed: Boolean?=null,privateNote: Boolean?=null) = setFlags(id,pinned,archived,trashed,privateNote)
 suspend fun search(query: String): List<Note> = withContext(Dispatchers.Default) {
  val context=currentCoroutineContext()
  val checkActive={context.ensureActive()}
  checkActive()
  val parsed=searchQuery(query)
  checkActive()
  val notes=parsed.expression?.let { expression ->
   rankSearchRows(dao.search(expression),checkActive).map { row ->checkActive();row.note()}
  } ?: if(parsed.labelKeys.isNotEmpty()) {
   val rows=dao.all().filter {row ->checkActive();!row.trashed}
   val order=compareByDescending<NoteEntity> {it.updatedAt}
   rows.sortedWith(Comparator {left,right ->checkActive();order.compare(left,right)})
    .map {row ->checkActive();row.note()}
  } else emptyList()
  if(parsed.labelKeys.isEmpty()) {
   checkActive()
   return@withContext notes
  }
  val result=notes.filter { note ->
   checkActive()
   val keys=labels(note.body).map {token ->checkActive();token.key}
   checkActive()
   parsed.labelKeys.all {label ->
    checkActive()
    keys.any {key ->checkActive();key==label || key.startsWith("$label/")}
   }
  }
  checkActive()
  result
 }
 suspend fun putNote(note:Note,localEdit:Boolean=false,historyKind:String="edit") {
  val old=dao.get(note.id)
  if(old!=null && old.body!=note.body) {
   val now=System.currentTimeMillis();val previous=dao.latestRevision(note.id);val knownServer=dao.historyCacheSnapshot(note.id)
   // Only a contiguous offline edit burst is known to have the same actor and ancestor.
   val coalesce=(knownServer==null || knownServer.maxVersion<=old.serverRev) && localEdit && historyKind=="edit" && old.pending && previous?.historySource=="local" && previous.changeKind=="edit" && previous.version==old.serverRev && now-previous.lastEditAt in 0L until 600_000L
   if(coalesce) dao.touchRevision(previous!!.id,now)
   else dao.putRevision(RevisionEntity(noteId=note.id,body=old.body,at=now,version=old.serverRev,historySource=if(localEdit) "local" else "replica",lastEditAt=now,changeKind=historyKind))
  }
  dao.put(NoteEntity.from(note,old?.rowid?:0));dao.clearLabels(note.id);dao.putLabels(labels(note.body).distinctBy { it.key }.map { LabelEntity(note.id,it.key,it.path) })
 }
 suspend fun enqueue(op: OutboxOperation) {
  val queued=dao.outbox().filter { !it.sent && it.error==null }
  val compacted=compactOutbox(queued.map { Json.decodeFromString<OutboxOperation>(it.payload) }+op)
  queued.forEach { dao.removeOutbox(it.opId) }
  compacted.forEachIndexed { index,item -> dao.putOutbox(OutboxEntity(item.opId,item.noteId,Json.encodeToString(item),queued.firstOrNull()?.createdAt?.plus(index)?:System.currentTimeMillis()+index)) }
 }
 suspend fun decideSuggestion(id: String, decision: String) {
  require(decision in listOf("accept","dismiss"))
  var rejection:String?=null
  var unchangedDecision=false
  db.withTransaction {
   val cached=dao.output("suggestion",id)
   val data=cached?.let { Json.parseToJsonElement(it.payload).jsonObject }
   val noteId=cached?.noteId
   if(decision=="accept") {
    val status=(data?.get("status") as? JsonPrimitive)?.takeIf {it.isString}?.content
    if(status=="accepted") {unchangedDecision=true;return@withTransaction}
    if(status!="pending") {
     unchangedDecision=true
     rejection="This suggestion is no longer pending. Review a current proposal instead."
     return@withTransaction
    }
   }
   val actionAt=System.currentTimeMillis()
   val affected=mutableSetOf<String>();noteId?.let { affected+=it }
   if(decision=="accept" && data!=null) {
    val payload=data["payload"]?.jsonObject?:JsonObject(emptyMap())
    fun text(key:String)=payload[key]?.jsonPrimitive?.content.orEmpty()
    val note=noteId?.let { dao.get(it)?.note() }
    val action=data["action"]?.jsonPrimitive?.content
    fun version(value:JsonElement?)=(value as? JsonPrimitive)?.takeUnless {it.isString}?.intOrNull?.takeIf {it>0}
    suspend fun live(id:String?)=id?.let {candidate -> dao.get(candidate)?.takeIf { !it.privateNote && !it.trashed && dao.tombstone(candidate)==null }}
    suspend fun bodyPending(target:String)=dao.outbox().any { row -> runCatching {
     val op=Json.decodeFromString<OutboxOperation>(row.payload)
     (op.noteId==target || target in op.affectedNoteIds) && (op.type in setOf("note.create","note.update","note.restore") || op.type=="suggestion.decide" && op.decision=="accept")
    }.getOrDefault(true) }
    suspend fun reject(message:String,supersede:Boolean=true) {
     rejection=message
     if(supersede && cached!=null) dao.putOutput(cached.copy(payload=JsonObject(data+mapOf("status" to JsonPrimitive("superseded"))).toString()))
    }
    if(action=="label_change") {
     reject("Review label changes in the authenticated web Review page to check their impact.",supersede=false)
     return@withTransaction
    }
    if(action=="replace_body") {
     val current=live(noteId)
     val proposedVersion=version(data["note_version"])
     val proposedBody=payload["body"] as? JsonPrimitive
     if(current==null || proposedVersion==null || current.serverRev!=proposedVersion || bodyPending(current.id) || proposedBody==null || !proposedBody.isString || proposedBody.content.toByteArray().size>1_000_000) {
      reject("This replacement is superseded. Compare the current note before applying a new proposal.")
      return@withTransaction
     }
    }
    val mergeSource=if(action=="merge_notes") live(text("source_id")) else null
    val mergeTarget=if(action=="merge_notes") live(text("target_id")) else null
    if(action=="merge_notes" && (mergeSource==null || mergeTarget==null || mergeSource.id==mergeTarget.id || noteId!=mergeTarget.id || version(payload["source_version"])!=mergeSource.serverRev || version(payload["target_version"])!=mergeTarget.serverRev || bodyPending(mergeSource.id) || bodyPending(mergeTarget.id) || (mergeTarget.body+"\n\n"+mergeSource.body).toByteArray().size>1_000_000)) {
     reject("This merge is superseded. Compare both live notes again before merging.")
     return@withTransaction
    }
    val body=when(action) {
     "add_label" -> note?.let { setLabels(it.body,listOf(text("path"))) }
     "append_text" -> note?.body?.plus("\n\n"+text("text"))
     "replace_body" -> text("body")
     "set_title" -> note?.body?.let { "# "+text("title")+"\n\n"+it }
     "merge_notes" -> { val source=mergeSource!!;val target=mergeTarget!!;affected+=source.id;affected+=target.id;val merged=target.body+"\n\n"+source.body;putNote(target.note().copy(body=merged,title=titleOf(merged),pending=true,updatedAt=actionAt,machine=false,updatedViaAgent=false),localEdit=true,historyKind="accept");putNote(source.note().copy(trashed=true,pending=true,updatedAt=actionAt,trashedAt=actionAt,updatedViaAgent=false),localEdit=true,historyKind="accept");null }
     else -> null
    }
    if(note!=null && body!=null) putNote(note.copy(body=body,title=titleOf(body),pending=true,updatedAt=actionAt,machine=note.machine && note.body==body,updatedViaAgent=false),localEdit=true,historyKind="accept")
   }
   if(cached!=null && data!=null) dao.putOutput(cached.copy(payload=JsonObject(data+mapOf("status" to JsonPrimitive(if(decision=="accept") "accepted" else "dismissed"))).toString()))
   enqueue(OutboxOperation(type="suggestion.decide",noteId=noteId,suggestionId=id,decision=decision,affectedNoteIds=affected.toList()))
  }; if(!unchangedDecision) onChange();rejection?.let {throw IllegalArgumentException(it)}
 }
 suspend fun queueImage(noteId:String,path:String,mime:String):String = db.withTransaction {
  val note=dao.get(noteId);require(note!=null && !note.trashed && dao.tombstone(noteId)==null) {"This note is no longer available."}
  require(mime.startsWith("image/"))
  require(java.io.File(path).isFile && java.io.File(path).length() in 1..IMAGE_IMPORT_LIMIT) {"Choose an image of at most 5 MB."}
  val id=uuidV7();val marker="[image:$id]"
  save(note.body+"\n\n"+marker,noteId)
  dao.putAttachment(AttachmentEntity(id,noteId,path,mime,marker=marker,serverUrl=dao.state()?.serverUrl.orEmpty()))
  onChange();id
 }
 suspend fun queueAttachment(noteId:String,path:String,mime:String,kind:String):String=kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
  require(kind in setOf("image","recording"))
  if(kind=="image") return@withContext queueImage(noteId,path,mime)
  require(mime in setOf("audio/ogg","audio/opus"))
  val maximum=dao.state()?.maxUploadBytes?:LEGACY_UPLOAD_LIMIT
  val file=java.io.File(path).canonicalFile
  val metadata=inspectRecording(file,maximum)
  db.withTransaction {
   val note=dao.get(noteId);require(note!=null && !note.trashed && dao.tombstone(noteId)==null) {"This note is no longer available."}
   dao.recordingFile(file.absolutePath)?.let {require(it.state=="retained") {"Retain the recording from its original journal before reusing its bytes."}}
   val id=uuidV7();val marker="[recording:$id]";save(note.body+"\n\n"+marker,noteId)
   val state=dao.state();dao.putAttachment(AttachmentEntity(id,noteId,file.absolutePath,mime,kind="recording",durationMs=metadata.durationMs,byteSize=metadata.byteSize,sha256=metadata.sha256,serverUrl=state?.serverUrl.orEmpty(),marker=marker,status=if(recordingPluginFacts(state).captureAllowed) "queued" else "paused"))
   onChange();id
  }
 }
 suspend fun discardAttachment(id:String)=db.withTransaction {
  val item=dao.attachment(id)?:return@withTransaction
  dao.removeAttachment(id)
  dao.get(item.noteId)?.takeIf {dao.tombstone(it.id)==null && item.marker.isNotEmpty() && item.marker in it.body}?.let {save(it.body.replace(item.marker,""),it.id)}
  onChange()
 }
 suspend fun record(outcome: String,reason: String?=null) = db.withTransaction { dao.log(SyncLogEntity(at=System.currentTimeMillis(),outcome=outcome,reason=reason)); dao.trimLog() }
}
