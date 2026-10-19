package io.theficos.spunto.data.local

import androidx.room.withTransaction
import io.theficos.spunto.core.model.uuidV7
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.serialization.json.*

data class RecordingPluginFacts(val captureAllowed:Boolean=false,val transcriptionActive:Boolean=false)

/** Catalog is owner intent/grants; enabled-only manifest is active contribution evidence. */
fun recordingPluginFacts(state:SyncStateEntity?):RecordingPluginFacts = recordingPluginFacts(state?.manifest?:"{}",state?.catalog?:"{}")
fun recordingPluginFacts(manifest:String,catalog:String):RecordingPluginFacts=runCatching {
 fun plugins(value:String):List<JsonObject> {
  val array=Json.parseToJsonElement(value).jsonObject["plugins"] as? JsonArray?:error("missing_plugins")
  return array.map {it as? JsonObject?:error("malformed_plugin")}
 }
 fun JsonObject.string(key:String):String?=(this[key] as? JsonPrimitive)?.takeIf {it.isString}?.content
 fun JsonObject.strings(key:String):Set<String> {
  val array=this[key] as? JsonArray?:error("missing_permissions")
  return array.map {(it as? JsonPrimitive)?.takeIf {it.isString}?.content?:error("malformed_permissions")}.toSet()
 }
 val active=plugins(manifest).filter {it.string("id")=="voice-memos"}
 val rows=plugins(catalog).filter {it.string("id")=="voice-memos"}
 if(rows.size!=1 || active.size>1) return@runCatching RecordingPluginFacts()
 val row=rows.single();val permissions=row.strings("permissions");val granted=row.strings("granted")
 val required=setOf("attachments.read","attachments.write","ai.transcribe","suggest")
 val allowed=row.string("switch")=="on" && row.string("state") in setOf("enabled","waiting") && permissions.containsAll(required) && granted.containsAll(permissions)
 RecordingPluginFacts(allowed,allowed && row.string("state")=="enabled" && active.size==1 && active.single().strings("permissions").containsAll(required))
}.getOrDefault(RecordingPluginFacts())

/** App-private journal: owner save is the only transition that queues recorded audio. */
class RecordingRepository(private val notes:NotesRepository,directory:File) {
 private val folder=directory.canonicalFile
 private val dao=notes.dao
 val drafts=dao.recordings()
 private fun owned(path:String):File {val file=File(path).canonicalFile;require(file.parentFile==folder) {"Recording path is outside this phone's recording store."};return file}
 suspend fun begin(draftId:String="capture",noteId:String?=null):RecordingDraftEntity=notes.db.withTransaction {
  val state=dao.state();require(recordingPluginFacts(state).captureAllowed) {"Enable Voice memos before recording."}
  if(noteId!=null) require(dao.get(noteId)!=null && dao.tombstone(noteId)==null && dao.get(noteId)?.trashed==false) {"This note is no longer available."}
  val initial=noteId?.let {dao.get(it)?.note()}
  val id=uuidV7();val row=RecordingDraftEntity(id,draftId,noteId,File(folder,"$id.ogg.partial").absolutePath,File(folder,"$id.ogg").absolutePath,state?.serverUrl.orEmpty(),"capturing",System.currentTimeMillis(),maximumBytes=state?.maxUploadBytes?:LEGACY_UPLOAD_LIMIT,baseBodyHash=initial?.bodyHash,baseVersion=initial?.serverRev)
  dao.putRecording(row);row
 }
 suspend fun complete(id:String,capturedText:Boolean=false):RecordingDraftEntity=withContext(Dispatchers.IO) {
  val before=dao.recording(id)?:error("Recording is unavailable.")
  require(before.state in setOf("capturing","interrupted","ready","recoverable")) {"Recording was discarded or retained."}
  val final=owned(before.finalPath);val partial=owned(before.localPath)
  val source=if(final.isFile) final else partial
  val metadata=if(before.state=="interrupted") recoverInterruptedRecording(source,before.maximumBytes) else inspectRecording(source,before.maximumBytes)
  FileOutputStream(source,true).use {it.fd.sync()}
  if(source!=final) {require(!final.exists());Files.move(source.toPath(),final.toPath(),StandardCopyOption.ATOMIC_MOVE)}
  syncRecordingDirectory(folder)
  notes.db.withTransaction {
   val current=dao.recording(id);require(current==before) {"Recording state changed."}
   before.copy(state=if(before.state=="interrupted") "recoverable" else "ready",durationMs=metadata.durationMs,byteSize=metadata.byteSize,sha256=metadata.sha256,capturedText=capturedText || before.capturedText,error=null).also {dao.putRecording(it)}
  }
 }
 suspend fun interrupted(id:String,reason:String="interrupted")=notes.db.withTransaction {
  dao.recording(id)?.takeIf {it.state in setOf("capturing","interrupted")}?.let {dao.putRecording(it.copy(state="interrupted",error=reason))}
 }
 /** Called on process start; does not create a recorder, network request or attachment. */
 suspend fun recover():List<RecordingDraftEntity> {
  for(row in dao.recordingSnapshots().filter {it.state in setOf("capturing","interrupted")}) {
   interrupted(row.id)
   try {complete(row.id,row.capturedText)} catch(cancelled:kotlinx.coroutines.CancellationException) {throw cancelled} catch(_:Exception) { /* Keep journal and partial bytes for explicit owner discard. */ }
  }
  return dao.recordingSnapshots().filter {it.state in setOf("interrupted","recoverable","ready")}
 }
 suspend fun retain(id:String,body:String,noteId:String?=null,privateNote:Boolean=false,captureDraft:DraftEntity?=null,sourceKind:String="android",editorWrite:EditorWrite?=null):String=withContext(Dispatchers.IO) {
  val before=dao.recording(id)?:error("Recording is unavailable.")
  if(before.state=="retained") return@withContext requireNotNull(before.noteId)
  require(before.state in setOf("ready","recoverable")) {"Finish or recover the recording before saving."}
  require(before.noteId==null || noteId==null || before.noteId==noteId) {"Keep this recording with its original note."}
  val targetId=noteId?:before.noteId
  editorWrite?.let {require(it.body==body && it.lease.draftId==before.draftId && it.lease.noteId==targetId) {"Use this recording’s current editor."}}
  val targetBefore=targetId?.let {dao.get(it)}
  val file=owned(before.finalPath);val metadata=inspectRecording(file,before.maximumBytes)
  require(metadata.sha256==before.sha256 && metadata.byteSize==before.byteSize) {"Recording bytes changed."}
  notes.retainRecordingEditor(editorWrite) { notes.db.withTransaction {
   require(dao.recording(id)==before) {"Recording state changed."}
   val root=dao.state()?.serverUrl.orEmpty()
   require(before.serverUrl.isEmpty() || root.isEmpty() || before.serverUrl==root) {"Reconnect to the recording's original server."}
   val target=noteId?:before.noteId
   if(target!=null) {val current=dao.get(target);require(current!=null && dao.tombstone(target)==null && !current.trashed) {"This note is no longer available."};require(targetBefore!=null && current.body==targetBefore.body && current.serverRev==targetBefore.serverRev) {"The note changed while the recording was being saved."};if(before.noteId==target && before.baseBodyHash!=null) require(current.note().bodyHash==before.baseBodyHash || current.body==body || editorWrite?.lease?.committedBody==current.body) {"The note changed during recording. Keep the current text before attaching the clip."}}
   val marker="[recording:$id]";val text=if(marker in body) body else if(body.isEmpty()) marker else body+"\n\n"+marker
   val saved=notes.save(text,target,sourceKind=sourceKind,sourceRef=if(target==null && sourceKind=="android" && before.capturedText) "dictation" else null)
   if(target==null && privateNote) notes.setFlags(saved,privateNote=true)
   val enabled=recordingPluginFacts(dao.state()).captureAllowed
   dao.putAttachment(AttachmentEntity(id,saved,file.absolutePath,"audio/ogg",kind="recording",durationMs=metadata.durationMs,byteSize=metadata.byteSize,sha256=metadata.sha256,serverUrl=before.serverUrl.ifEmpty {root},marker=marker,status=if(enabled) "queued" else "paused"))
   dao.putRecording(before.copy(state="retained",noteId=saved))
   captureDraft?.takeIf {it.id==before.draftId && it.body==body}?.let {dao.clearDraftSnapshot(it.id,it.body,it.updatedAt)}
   saved
  } }
 }
 /** Rejects deleting audio whose ownership was transferred to a note/attachment. */
 suspend fun discard(id:String)=withContext(Dispatchers.IO) {
  val files=notes.db.withTransaction {
   val row=dao.recording(id)?:return@withTransaction emptyList<File>()
   require(row.state!="retained") {"Remove the note attachment instead of discarding a retained recording."}
   dao.putRecording(row.copy(state="discarded"))
   listOf(owned(row.localPath),owned(row.finalPath)).filter {dao.attachmentReferences(it.absolutePath)==0}
  }
  files.forEach {it.delete()}
 }
}
