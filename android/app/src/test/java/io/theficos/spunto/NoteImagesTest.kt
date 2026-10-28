package io.theficos.spunto

import io.theficos.spunto.data.local.AttachmentEntity
import org.junit.Assert.*
import org.junit.Test

class NoteImagesTest {
 private val remote="00000000-0000-4000-8000-000000000001"
 private val link="![Synthetic image](/a/$remote)"
 private val uploaded=AttachmentEntity("local","note","/synthetic/image","image/png",remoteId=remote,marker=link,status="uploaded")
 @Test fun uploadedReferenceUsesOneLocalImageAndRemovingItHidesRetainedBytes() {
  assertEquals(listOf(NoteImage("/a/$remote",uploaded)),noteImages(link,listOf(uploaded)))
  assertTrue(noteImages("Owner removed the image",listOf(uploaded)).isEmpty())
  assertEquals(listOf(NoteImage("/a/$remote",uploaded)),noteImages(link,listOf(uploaded)))
 }
 @Test fun queuedImagesAndRemoteImagesFollowSourceOrderWithoutDroppingEpochRecoveryBytes() {
  val queued=uploaded.copy(id="queued",remoteId=null,marker="[image:00000000-0000-4000-8000-000000000002]",status="queued")
  val body=queued.marker+"\n\n"+link
  assertEquals(listOf(NoteImage(null,queued),NoteImage("/a/$remote",uploaded)),noteImages(body,listOf(uploaded,queued)))
  assertEquals(listOf(NoteImage("/a/$remote",null)),noteImages(link,emptyList()))
  assertEquals(listOf(NoteImage("/a/$remote",uploaded.copy(remoteId=null,status="queued"))),noteImages(link,listOf(uploaded.copy(remoteId=null,status="queued"))))
 }
 @Test fun imageSyntaxInsideCodeDoesNotShowARemoteOrRetainedImage() {
  val restored=uploaded.copy(remoteId=null,status="queued")
  val queued=uploaded.copy(remoteId=null,marker="[image:00000000-0000-4000-8000-000000000002]",status="queued")
  val code="`$link`\n\n```\n$link\n${queued.marker}\n```\n\n`${queued.marker}`"
  assertTrue(noteImages(code,listOf(uploaded,restored,queued)).isEmpty())
 }
}
