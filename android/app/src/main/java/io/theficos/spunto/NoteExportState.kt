package io.theficos.spunto

import io.theficos.spunto.core.model.Note
import java.security.MessageDigest

/** Only fingerprints and authority are retained across a document-picker round trip. */
internal data class NoteExportStamp(val noteId:String,val revision:String,val draft:String?,val credentials:Any?,val server:String?,val epoch:String?)
internal fun exportDigest(value:String)=MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
internal fun noteExportStamp(note:Note,draft:String?,credentials:Any?,server:String?,epoch:String?)=NoteExportStamp(
 note.id,exportDigest(listOf(note.body,note.title,note.updatedAt,note.serverRev,note.privateNote,note.trashed).joinToString("\u0000")),draft?.let(::exportDigest),credentials,server,epoch,
)
internal fun markdownFileName(title:String):String {
 val stem=title.filter {it.isLetterOrDigit() || it in " -_"}.trim().take(80).trim().ifBlank {"note"}
 return "$stem.md"
}
internal class NoteExportSession {
 internal class Request(val stamp:NoteExportStamp)
 @Volatile private var pending:Request?=null
 fun begin(stamp:NoteExportStamp)=Request(stamp).also {pending=it}
 fun cancel() {pending=null}
 fun owns(request:Request,current:NoteExportStamp?,unlocked:Boolean)=unlocked && pending===request && request.stamp==current
}

internal fun writeNoteMarkdown(output:java.io.OutputStream,body:String,allowed:()->Boolean) {
 val bytes=body.toByteArray(Charsets.UTF_8)
 check(allowed())
 for(offset in bytes.indices step 8192) {check(allowed());output.write(bytes,offset,minOf(8192,bytes.size-offset))}
 output.flush()
}
