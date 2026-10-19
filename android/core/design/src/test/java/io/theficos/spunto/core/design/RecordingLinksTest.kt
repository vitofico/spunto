package io.theficos.spunto.core.design

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingLinksTest {
 private val first="/a/00000000-0000-4000-8000-000000000001"
 private val second="/a/00000000-0000-4000-8000-000000000002"
 @Test fun realLinksKeepLabelsTargetsAndSourceOrderIncludingReferences() {
  val body="[Recording 0:31]($first)\n\n> [Recording **10:02**][clip]\n\n[clip]: $second\n\n[Recording 0:31]($first)"
  assertEquals(listOf("Recording 0:31" to first,"Recording 10:02" to second,"Recording 0:31" to first),recordingAttachmentLinks(body))
 }
 @Test fun codeImagesAndRawHtmlCannotCreatePlayers() {
  val link="[Recording 0:31]($first)"
  val body="`$link`\n\n```markdown\n$link\n```\n\n    $link\n\n!$link\n\n<div>\n$link\n</div>\n\n<a href=\"$first\">Recording 0:31</a>"
  assertEquals(emptyList<Pair<String,String>>(),recordingAttachmentLinks(body))
 }
 @Test fun externalMalformedAndNonRecordingTargetsCannotCreatePlayers() {
  val body=listOf("[Recording 0:31](https://example.test$first)","[Recording 0:31]($first?token=fixture)","[Recording 0:31]($first#clip)","[Recording 0:31](/a/1)","[Recording 0:61]($first)","[Recording 0:1]($first)","[Image]($first)","[Recording 0:31](/n/00000000-0000-4000-8000-000000000001)").joinToString("\n\n")
  assertEquals(emptyList<Pair<String,String>>(),recordingAttachmentLinks(body))
 }
 @Test fun internalAttachmentRoutingKeepsTheExistingNoteCallbackContract() {
  assertTrue(internalMarkdownTarget(first))
  assertTrue(internalMarkdownTarget("/n/00000000-0000-4000-8000-000000000001"))
  assertFalse(internalMarkdownTarget("https://example.test$first"))
  assertFalse(internalMarkdownTarget("$first?token=fixture"))
  assertFalse(internalMarkdownTarget("/a/1"))
 }
}
