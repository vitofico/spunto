package io.theficos.spunto

import org.junit.Assert.*
import org.junit.Test

class NotePreviewTest {
 @Test fun singleLineNoteRemainsVisibleInItsTitle() {
  val preview=notePreview("Remember the milk","Remember the milk")
  assertEquals("Remember the milk",preview.title);assertEquals("",preview.body)
 }
 @Test fun headingAndTrailingLabelsKeepOriginalChecklistOffsets() {
  val body="# Weekend plans\n\n- [ ] Book the train\n- [x] Browse the bookshop\n\n#ideas"
  val preview=notePreview(body,"Weekend plans")
  assertEquals(1,preview.bodyStartLine)
  assertEquals("\n- [ ] Book the train\n- [x] Browse the bookshop",preview.body)
  assertEquals(body.lines()[2],preview.body.lines()[1+0])
 }
 @Test fun abbreviatedTitleDoesNotDropTheOriginalFirstParagraph() {
  val body="A longer first line that carries the entire thought"
  assertEquals(body,notePreview(body,"A longer first line").body)
 }
 @Test fun aLeadingChecklistNeverLosesItsInteractiveFirstRow() {
  val body="- [ ] Book a train\n- [ ] Find a cafe"
  val preview=notePreview(body,"- [ ] Book a train")
  assertEquals(0,preview.bodyStartLine);assertEquals(body,preview.body)
 }

 @Test fun numericReferencesAndMixedInvalidTokensStayVisible() {
  for(tail in listOf("#12345","#ideas #12345","#ideas #bad//path")) {
   assertEquals("\n$tail",notePreview("Order reference\n\n$tail","Order reference").body)
  }
 }
 @Test fun fencedLabelTextIsNotReplacedByAnUnrelatedRealLabel() {
  val body="Order reference\n\n#ideas\n\n```\n#ideas"
  assertEquals("\n#ideas\n\n```\n#ideas",notePreview(body,"Order reference").body)
 }
 @Test fun actualLabelsStillTrimWithWindowsLineEndings() {
  assertEquals("\nKeep this",notePreview("Title\r\n\r\nKeep this\r\n\r\n#ideas #next","Title").body)
 }
}
