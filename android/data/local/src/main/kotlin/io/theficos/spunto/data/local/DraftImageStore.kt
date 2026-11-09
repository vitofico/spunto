package io.theficos.spunto.data.local

import androidx.room.withTransaction
import io.theficos.spunto.core.model.LocalOperationProblem
import io.theficos.spunto.core.model.requireLocal
import io.theficos.spunto.core.model.uuidV7
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

/** Image files are owned by this replica until the draft transaction promotes them to note attachments. */
class DraftImageStore(private val notes:NotesRepository,private val folder:File) {
 private val dao=notes.dao
 fun observe(draftId:String)=dao.draftImages(draftId)

 suspend fun add(lease:EditorLease,mime:String,authorize:suspend ()->Unit={},open:()->InputStream)=withContext(Dispatchers.IO) {
  require(mime.startsWith("image/"))
  folder.mkdirs()
  val id=uuidV7()
  val partial=File(folder,"$id$processSuffix")
  val image=File(folder,"$id.image")
  try {
   // The provider may stream from cloud storage. No database writer is held during the read.
   open().use {input -> copyPickedImage(input,partial)}
   notes.editCaptureImages(lease) {draftId ->
    authorize()
    val position=dao.draftImageSnapshots(draftId).size
    requireLocal(position<MAX_DRAFT_IMAGES,LocalOperationProblem.DraftImageLimit)
    Files.move(partial.toPath(),image.toPath(),StandardCopyOption.ATOMIC_MOVE)
    syncRecordingDirectory(folder)
    dao.putDraftImage(DraftImageEntity(id,draftId,image.absolutePath,mime,position))
   }
  } finally {
   partial.delete()
  }
 }

 suspend fun clear(lease:EditorLease)=withContext(Dispatchers.IO) {
  val removed=notes.editCaptureImages(lease) {draftId ->
   dao.draftImageSnapshots(draftId).also {dao.clearDraftImages(draftId)}
  }
  prune(removed.map {File(it.localPath)})
 }

 /** A crash can leave a file between rename and SQL commit, or between SQL removal and unlink. */
 suspend fun recover()=withContext(Dispatchers.IO) {
  prune(folder.listFiles().orEmpty().filter {file ->
   file.name.endsWith(".image") || file.name.endsWith(".partial") && !file.name.endsWith(processSuffix)
  })
 }

 private suspend fun prune(candidates:List<File>) {
  if(candidates.isEmpty()) return
  notes.db.withTransaction {
   val references=dao.draftImageFileReferences().toHashSet()
   candidates.forEach {file ->
    if(file.parentFile==folder && file.absolutePath !in references && file.isFile) file.delete()
   }
  }
 }

 private companion object {
  const val MAX_DRAFT_IMAGES=10
  // Only a previous process's partial copies can be recovered as orphans.
  val processSuffix=".${UUID.randomUUID()}.partial"
 }
}

suspend fun copyPickedImage(input:InputStream,file:File) {
 val context=currentCoroutineContext()
 FileOutputStream(file).use {output ->
  val buffer=ByteArray(32*1024)
  var size=0L
  while(true) {
   context.ensureActive()
   val count=input.read(buffer,0,minOf(buffer.size.toLong(),IMAGE_IMPORT_LIMIT-size+1).toInt())
   if(count<0) break
   size+=count
   requireLocal(size<=IMAGE_IMPORT_LIMIT,LocalOperationProblem.ImageTooLarge)
   output.write(buffer,0,count)
  }
  requireLocal(size>0,LocalOperationProblem.ImageTooLarge)
  output.fd.sync()
 }
}
