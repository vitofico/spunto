package io.theficos.spunto.core.plugins

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class TextChangesTest {
 private fun verify(before:String,after:String):List<TextChange> {
  val result=textChanges(before,after)
  assertEquals(before,result.filter {it.kind!=TextChangeKind.Added}.joinToString("") {it.text})
  assertEquals(after,result.filter {it.kind!=TextChangeKind.Removed}.joinToString("") {it.text})
  return result
 }
 @Test fun changesPreservePunctuationWhitespaceAndUnicodeExactly() {
  val changes=verify("Hello  world\n#ideas 😀","Hello, world!\n\n#ideas 😀")
  assertTrue(changes.any {it.kind==TextChangeKind.Removed && "Hello" in it.text})
  assertTrue(changes.any {it.kind==TextChangeKind.Added && "Hello," in it.text})
  listOf("" to "Added","Removed" to "","same" to "same","a\nb\nc" to "a\nc").forEach {(before,after) -> verify(before,after)}
 }
 @Test fun comparisonStaysBoundedOnLargeUnrelatedPassages() {
  val changes=verify("Start "+"old ".repeat(1000)+" End","Start "+"new ".repeat(1000)+" End")
  assertEquals(4,changes.size)
  assertEquals(TextChange(TextChangeKind.Unchanged,"Start "),changes.first())
  assertEquals(TextChange(TextChangeKind.Unchanged,"  End"),changes.last())
 }
 @Test(timeout=2000) fun largeOneSidedChangesStayLinearAndKeepEveryCharacter() {
  val body="word \n".repeat(50_000)
  assertEquals(listOf(TextChange(TextChangeKind.Added,body)),verify("",body))
  assertEquals(listOf(TextChange(TextChangeKind.Removed,body)),verify(body,""))
 }
 @Test fun focusingNeverHidesAnEdit() {
  val changes=verify("a".repeat(1000)+" removed "+"b".repeat(1000),"a".repeat(1000)+" added "+"b".repeat(1000))
  val focused=focusedTextChanges(changes)
  assertEquals(changes.filter {it.kind!=TextChangeKind.Unchanged},focused.filter {it.kind!=TextChangeKind.Unchanged})
  assertTrue(focused.sumOf {it.text.length}<changes.sumOf {it.text.length})
 }
 @Test fun arbitrarySmallEditsRoundTripBothSides() {
  val random=kotlin.random.Random(42)
  val words=listOf("cat","dog","hello,","#ideas","😀"," ","  ","\n")
  repeat(200) {verify(List(random.nextInt(30)) {words.random(random)}.joinToString(""),List(random.nextInt(30)) {words.random(random)}.joinToString(""))}
 }
 @Test fun combinedLinesAreComparedWithoutDiffMarkers() {
  val hunk=Json.parseToJsonElement("""{"lines":[" same","-Original words","+Proposed words"," last"]}""").jsonObject
  assertEquals("same\nOriginal words\nlast" to "same\nProposed words\nlast",diffHunkTexts(hunk))
 }
 @Test fun whitespaceOnlyEditsDescribeTheVisibleChange() {
  assertEquals(listOf("Removed 2 spaces","Added 1 space"),whitespaceChangeSummaries(verify("a  b","a b")))
  assertEquals(listOf("Added 1 line break"),whitespaceChangeSummaries(verify("Paragraph","Paragraph\n")))
 }
}
