package io.theficos.spunto

import io.theficos.spunto.core.model.titleOf
import org.junit.Assert.*
import org.junit.Test

class NotePreviewTest {
 private fun preview(body:String)=notePreview(body,titleOf(body))

 @Test fun singleLineNoteRemainsVisibleOnlyInItsTitle() {
  val result=preview("Remember the milk")
  assertEquals("Remember the milk",result.title);assertEquals("",result.body)
 }
 @Test fun aCompleteFormattedFirstBlockBecomesOnePlainTitle() {
  for(body in listOf("**Remember milk**","Remember &amp; enjoy","# **Remember milk**")) {
   val result=preview(body)
   assertEquals(titleOf(body),result.title);assertEquals("",result.body)
  }
 }
 @Test fun abbreviatedTitleDoesNotRepeatTheOriginalFirstParagraph() {
  val body=List(30) {"A complete synthetic thought"}.joinToString(" ")
  val result=preview(body)
  assertTrue(titleOf(body).length<body.length)
  assertNull(result.title);assertEquals(body,result.body)
 }
 @Test fun aContinuationOfTheFirstParagraphStaysTogetherWithoutAnotherTitle() {
  val body="A synthetic thought\ncontinued on the next source line."
  val result=preview(body)
  assertNull(result.title);assertEquals(body,result.body)
 }
 @Test fun linksAndCodeKeepTheirOriginalInteractiveOrLiteralPresentationOnce() {
  for(body in listOf("Read [the guide](https://example.test)","Keep `literal code`","[the guide](https://example.test)","> A quotation","    Indented code")) {
   val result=preview(body)
   assertNull(result.title);assertEquals(body,result.body)
  }
 }
 @Test fun leadingNestedLabelsMoveIntoChipsWithoutRepeatingTheShortTitle() {
  val result=preview("#work/plans Remember the milk")
  assertEquals("Remember the milk",result.title);assertEquals("",result.body)
  assertEquals(listOf("work/plans"),result.labels)
 }
 @Test fun leadingNestedLabelsDoNotLeaveADuplicateTruncatedTitleOrIndentedCode() {
  val text=List(30) {"A complete synthetic thought"}.joinToString(" ")
  val result=preview("#work/plans #ideas #later #next $text")
  assertNull(result.title);assertEquals("#work/plans #ideas #later #next $text",result.body)
  assertEquals(listOf("work/plans","ideas","later","next"),result.labels)
 }
 @Test fun titleAndBodyLabelsAreChipsOnlyAndSameLabelHasOneChip() {
  val result=preview("#work Remember milk #ideas\n\nDiscuss #WORK with someone.\n\n#ideas")
  assertEquals("Remember milk",result.title)
  assertEquals("\nDiscuss #WORK with someone.\n\n#ideas",result.body)
  assertEquals(listOf("work","ideas"),result.labels)
 }
 @Test fun removingALeadingLabelDoesNotReinterpretTheRemainingBlock() {
  for(body in listOf("#ideas # Heading","#ideas - Prose","#ideas - [ ] Prose","#ideas ```\nordinary text","Remember (#ideas)")) {
   val result=preview(body)
   assertNull(body,result.title);assertEquals(body,result.body)
  }
 }
 @Test fun aLabelOnlyNoteHasNoRepeatedTitleButKeepsItsChip() {
  val result=preview("#work/plans #ideas")
  assertNull(result.title);assertEquals("",result.body)
  assertEquals(listOf("work/plans","ideas"),result.labels)
 }
 @Test fun headingAndTrailingLabelsKeepOriginalChecklistOffsets() {
  val body="# Weekend plans\n\n- [ ] Book the train\n- [x] Browse the bookshop\n\n#ideas"
  val result=preview(body)
  assertEquals("Weekend plans",result.title);assertEquals(1,result.bodyStartLine)
  assertEquals("\n- [ ] Book the train\n- [x] Browse the bookshop\n\n#ideas",result.body)
  assertEquals(body.lines()[2],result.body.lines()[1])
 }
 @Test fun aLeadingChecklistIsShownOnlyAsInteractiveBody() {
  val body="- [ ] Book a train\n- [ ] Find a cafe"
  val result=preview(body)
  assertNull(result.title);assertEquals(0,result.bodyStartLine);assertEquals(body,result.body)
 }
 @Test fun removingLabelsNeverShiftsTheTaskCallbackOntoAnotherSourceLine() {
  val body="# Weekend plans\r\n\r\n#ideas\r\n- [ ] Book a train #travel/rail\r\n- [x] Find a cafe #ideas"
  val result=preview(body)
  val shown=result.body.lines()
  val previewTask=shown.indexOfFirst {it.startsWith("- [ ]")}
  val originalLine=previewTask+result.bodyStartLine
  assertEquals(3,originalLine)
  val edited=body.lines().toMutableList().apply {this[originalLine]=this[originalLine].replaceFirst("[ ]","[x]")}
  assertEquals("- [x] Book a train #travel/rail",edited[3])
  assertEquals(body.lines().filterIndexed {index,_ ->index!=3},edited.filterIndexed {index,_ ->index!=3})
  assertEquals(listOf("ideas","travel/rail"),result.labels)
  assertEquals(body.lines().drop(result.bodyStartLine),result.body.lines())
 }
 @Test fun numericReferencesAndInvalidTokensStayVisibleAlongsideRealLabelChips() {
  val result=preview("Order reference\n\n#ideas #12345 #bad//path")
  assertEquals(listOf("ideas"),result.labels)
  assertTrue(result.body.contains("#12345"));assertTrue(result.body.contains("#bad//path"))
  assertTrue(result.body.contains("#ideas")) // Hiding occurs after parsing, never by rewriting source.
 }
 @Test fun actualLabelSpansDoNotRemoveInlineOrFencedCodeWithTheSameText() {
  val result=preview("Code\n\n`#ideas`\n\n```\n#ideas\n```\n\n#ideas")
  assertEquals(listOf("ideas"),result.labels)
  assertEquals("\n`#ideas`\n\n```\n#ideas\n```\n\n#ideas",result.body)
 }
 @Test fun anUnclosedFenceKeepsItsLabelTextAndDoesNotInventAChip() {
  val result=preview("Code\n\n```\n#ideas")
  assertTrue(result.labels.isEmpty());assertTrue(result.body.contains("#ideas"))
 }
 @Test fun labelRemovalKeepsOtherLinkTextAndDestinationsIntact() {
  val result=preview("Read [the guide](https://example.test) #reading")
  assertNull(result.title);assertEquals(listOf("reading"),result.labels)
  assertEquals("Read [the guide](https://example.test) #reading",result.body)
 }
}
