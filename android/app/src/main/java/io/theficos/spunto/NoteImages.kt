package io.theficos.spunto

import io.theficos.spunto.core.design.imageAttachmentReferences
import io.theficos.spunto.data.local.AttachmentEntity

internal data class NoteImage(val target:String?,val local:AttachmentEntity?)
/** Parsed body references own visibility/order; retained rows only supply offline bytes. */
internal fun noteImages(body:String,attachments:List<AttachmentEntity>):List<NoteImage> {
 val images=attachments.filter {it.kind=="image" && it.mime.startsWith("image/")}
 return imageAttachmentReferences(body).mapNotNull { (target,offset) ->
  if(target.startsWith("/a/")) {
   val local=images.firstOrNull {it.remoteId==target.substringAfterLast('/') || it.marker.isNotEmpty() && body.startsWith(it.marker,offset)}
   NoteImage(target,local)
  } else images.firstOrNull {it.remoteId==null && it.marker==target}?.let {NoteImage(null,it)}
 }
}
