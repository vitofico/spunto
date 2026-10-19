package io.theficos.spunto.data.sync

import androidx.room.withTransaction
import io.theficos.spunto.data.local.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/** Upload immutable bytes outside Room, then acknowledge only an unchanged live marker/queue item. */
class AttachmentUploader(
 private val repository:NotesRepository,
 private val config:AuthConfig,
 private val api:ApiClient,
 private val currentConfig:suspend ()->AuthConfig?={config},
) {
 private val root=config.serverUrl.trimEnd('/')
 private val dao=repository.dao
 suspend fun uploadAll()=withContext(Dispatchers.IO) {
  if(!config.ownsReplica(repository.replicaId)) return@withContext
  for(candidate in dao.pendingAttachments()) {
   var spool:File?=null;var claimed:AttachmentEntity?=null
   try {
    val state=dao.state()?:SyncStateEntity()
    if(state.serverUrl!=root || candidate.serverUrl.isNotEmpty() && candidate.serverUrl!=root) continue
    if(candidate.kind=="recording" && !recordingPluginFacts(state).captureAllowed) {pause(candidate,"voice_memos_disabled");continue}
    val maximum=if(candidate.kind=="image") minOf(state.maxUploadBytes,IMAGE_IMPORT_LIMIT) else state.maxUploadBytes
    require(candidate.kind in setOf("image","recording"))
    val source=File(candidate.localPath)
    val metadata=if(candidate.kind=="recording") inspectRecording(source,maximum).let {it.byteSize to it.sha256} else attachmentDigest(source,maximum)
    if(candidate.sha256.isNotEmpty() && candidate.sha256!=metadata.second) throw IllegalArgumentException("attachment_changed")
    val uploadFile=File.createTempFile("spunto-upload-",".bin",source.parentFile);spool=uploadFile
    val digest=MessageDigest.getInstance("SHA-256");var total=0L
    source.inputStream().use {input -> uploadFile.outputStream().use {output -> val buffer=ByteArray(32*1024);while(true) {val n=input.read(buffer);if(n<0) break;total+=n;require(total<=maximum);digest.update(buffer,0,n);output.write(buffer,0,n)}}}
    val hash=digest.digest().joinToString("") {"%02x".format(it)}
    require(total==metadata.first && hash==metadata.second) {"attachment_changed"}
    claimed=repository.db.withTransaction {
     val item=dao.attachment(candidate.id)?:return@withTransaction null
     if(item!=candidate || dao.state()?.serverUrl!=root || currentConfig()!=config) return@withTransaction null
     val note=dao.get(item.noteId)
     if(note==null || note.trashed || dao.tombstone(item.noteId)!=null) {dao.putAttachment(item.copy(status="blocked",error="note_unavailable"));return@withTransaction null}
     if(item.kind=="recording" && !recordingPluginFacts(dao.state()).captureAllowed) {dao.putAttachment(item.copy(status="paused",error="voice_memos_disabled"));return@withTransaction null}
     var marker=item.marker
     if(marker.isEmpty() && item.kind=="image") {marker="[image:${item.id}]";repository.update(note.id,note.body+"\n\n"+marker)}
     if(marker.isEmpty() || marker !in dao.get(note.id)!!.body) {dao.putAttachment(item.copy(status="blocked",error="marker_removed"));return@withTransaction null}
     item.copy(serverUrl=root,marker=marker,byteSize=total,sha256=hash,status="uploading",error=null).also {dao.putAttachment(it)}
    }
    val sent=claimed?:continue
    val response=api.uploadNamed(uploadFile.absolutePath,sent.mime,File(sent.localPath).name.removeSuffix(".partial"))
    val remote=(response["id"] as? JsonPrimitive)?.takeIf {it.isString}?.content?:error("attachment_id_missing")
    require(UUID.fromString(remote).toString()==remote) {"attachment_id_invalid"}
    if(currentConfig()!=config) {pause(sent,"credentials_changed");continue}
    repository.db.withTransaction {
     val item=dao.attachment(sent.id)?:return@withTransaction
     if(item!=sent) return@withTransaction
     val note=dao.get(sent.noteId)
     if(dao.state()?.serverUrl!=root || note==null || note.trashed || dao.tombstone(sent.noteId)!=null || sent.marker !in note.body) {dao.putAttachment(item.copy(status="blocked",error="target_changed"));return@withTransaction}
     if(item.kind=="recording" && !recordingPluginFacts(dao.state()).captureAllowed) {dao.putAttachment(item.copy(status="paused",error="voice_memos_disabled"));return@withTransaction}
     val seconds=item.durationMs/1000
     val snippet=(response["snippet"] as? JsonPrimitive)?.takeIf {it.isString}?.content
     val markdown=if(item.kind=="recording") "[Recording ${seconds/60}:${(seconds%60).toString().padStart(2,'0')}](/a/$remote)" else snippet?.takeIf {it.startsWith("![") && it.endsWith("](/a/$remote)")}?:"![Image](/a/$remote)"
     repository.update(note.id,note.body.replace(sent.marker,markdown))
     dao.putAttachment(item.copy(remoteId=remote,status="uploaded",marker=markdown,error=null,serverEpoch=dao.state()?.epoch.orEmpty()))
    }
   } catch(cancelled:CancellationException) {withContext(NonCancellable) {claimed?.let {pause(it,"interrupted")}};throw cancelled}
   catch(error:Exception) {
    val code=if(error is ApiException && error.status==401) "auth_required" else if(error is IllegalArgumentException) "attachment_invalid" else "upload_failed"
    repository.db.withTransaction {val item=dao.attachment(candidate.id);if(item==claimed || claimed==null && item==candidate) item?.let {dao.putAttachment(it.copy(status="failed",error=code))}}
    if(error is ApiException || error !is IllegalArgumentException) throw error
   } finally {spool?.delete()}
  }
 }
 private suspend fun pause(snapshot:AttachmentEntity,reason:String)=repository.db.withTransaction {
  dao.attachment(snapshot.id)?.takeIf {it==snapshot}?.let {dao.putAttachment(it.copy(status="paused",error=reason))}
 }
}
