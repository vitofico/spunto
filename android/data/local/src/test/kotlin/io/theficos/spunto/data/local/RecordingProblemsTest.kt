package io.theficos.spunto.data.local

import io.theficos.spunto.core.model.LocalOperationException
import io.theficos.spunto.core.model.LocalOperationProblem
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RecordingProblemsTest {
 @get:Rule val folder=TemporaryFolder()
 @Test fun malformedRecordingReturnsTypedGuidanceAndKeepsBytes() {
  val file=folder.newFile("synthetic.ogg").apply {writeText("incomplete recording fixture")}
  val original=file.readBytes()
  val failure=runCatching {inspectRecording(file,1_000_000)}.exceptionOrNull()
  assertTrue(failure is LocalOperationException)
  assertEquals(LocalOperationProblem.RecordingInvalid,(failure as LocalOperationException).problem)
  assertArrayEquals(original,file.readBytes())
 }
 @Test fun imageAttachmentLimitUsesAttachmentGuidance() {
  val file=folder.newFile("synthetic-image").apply {writeBytes(byteArrayOf(1,2,3))}
  val failure=runCatching {attachmentDigest(file,2)}.exceptionOrNull()
  assertTrue(failure is LocalOperationException)
  assertEquals(LocalOperationProblem.AttachmentLimitOrUnavailable,(failure as LocalOperationException).problem)
  assertArrayEquals(byteArrayOf(1,2,3),file.readBytes())
 }
}
