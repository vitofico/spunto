package io.theficos.spunto.core.design
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
class MarkdownTest {
 @get:Rule val timeout=org.junit.rules.Timeout(10,java.util.concurrent.TimeUnit.MINUTES)
 @Test fun checklistLinesExcludeCodeAndKeepSourceOrder() {
  val body="Before\n\n```text\n- [ ] Code example\n```\n\n- [ ] First actual task\n- Ordinary item\n- [x] Second actual task\n\nAfter\n\n    - [ ] Indented code"
  assertEquals(listOf(6,8),checklistLines(body))
 }
 @Test fun nestedTasksKeepTheirOriginalSourceLine() {
  assertEquals(listOf(0,1),checklistLines("- [ ] Parent\n  - [x] Child"))
 }
 @Test fun aTapInRenderedTextLandsOnTheSameCharacterInTheSource() {
  val body="# Plan\n\nSecond with **bold** words, a [link](https://example.org) &amp; more\nnext line\n\n- item ![photo](/a/x) tail"
  val start=body.indexOf("Second");val end=body.indexOf("\n\n- item")
  val rendered="Second with bold words, a link & more\nnext line"
  fun at(word:String)=sourceOffset(body,start,end,rendered,rendered.indexOf(word))
  assertEquals(start,at("Second"));assertEquals(body.indexOf("bold"),at("bold"));assertEquals(body.indexOf("words"),at("words"))
  assertEquals(body.indexOf("link"),at("link"));assertEquals(body.indexOf("more"),at("more"));assertEquals(body.indexOf("next"),at("next"))
  assertEquals(end,sourceOffset(body,start,end,rendered,rendered.length))
  // An image's caption isn't in the source, so the tap is left unmapped rather than guessed.
  val item=body.indexOf("item");assertNull(sourceOffset(body,item,body.length,"item [Image: photo] tail",8))
 }
 @Test fun inlineLabelsMatchTheCoreTokenizerOutsideWords() {
  val text="Plan #Project/Spunto, (#idea) and #2026 but not mail#b or #a/b/c/d/e/f #città"
  assertEquals(listOf("#Project/Spunto","#idea","#città"),inlineLabels(text).map {text.substring(it.first,it.last+1)})
 }
}
