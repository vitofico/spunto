package io.theficos.spunto.core.design

import org.junit.Assert.*
import org.junit.Test

class ImageLinksTest {
 private val target="/a/00000000-0000-4000-8000-000000000001"
 @Test fun parsedImagesKeepSourcePositionsAndReferenceDestinations() {
  val body="A thought\n\n![first]($target)\n\n> ![second][photo]\n\n[photo]: $target"
  assertEquals(listOf(target to body.indexOf("![first]"),target to body.indexOf("![second]")),imageAttachmentReferences(body))
 }
 @Test fun codeHtmlExternalAndMalformedLinksNeverBecomeImages() {
  val image="![Image]($target)"
  val body="`$image`\n\n```markdown\n$image\n```\n\n    $image\n\n<div>\n$image\n</div>\n\n![external](https://example.test$target)\n\n![invalid](/a/1)"
  assertTrue(imageAttachmentReferences(body).isEmpty())
 }
}
