package io.theficos.spunto.core.design
import org.junit.Assert.assertEquals
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
 @Test fun inlineLabelsMatchTheCoreTokenizerOutsideWords() {
  val text="Plan #Project/Spunto, (#idea) and #2026 but not mail#b or #a/b/c/d/e/f #città"
  assertEquals(listOf("#Project/Spunto","#idea","#città"),inlineLabels(text).map {text.substring(it.first,it.last+1)})
 }
}
