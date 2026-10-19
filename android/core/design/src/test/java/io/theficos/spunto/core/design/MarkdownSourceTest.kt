package io.theficos.spunto.core.design

import org.junit.Assert.*
import org.junit.Test

class MarkdownSourceTest {
 @Test fun realHeadingsEmphasisAndCodeUseOriginalSourceRanges() {
  val body="# Heading\n\nWords **strong** and *soft* with `code`.\n\n```kotlin\n#inside *literal*\n```\n"
  val spans=markdownSourceSpans(body)
  assertEquals(listOf("# Heading"),spans.filter {it.kind==MarkdownSourceKind.Heading}.map {body.substring(it.start,it.end)})
  assertEquals(listOf("**strong**"),spans.filter {it.kind==MarkdownSourceKind.Strong}.map {body.substring(it.start,it.end)})
  assertEquals(listOf("*soft*"),spans.filter {it.kind==MarkdownSourceKind.Emphasis}.map {body.substring(it.start,it.end)})
  assertTrue(spans.any {it.kind==MarkdownSourceKind.Code && body.substring(it.start,it.end)=="`code`"})
  assertTrue(spans.any {it.kind==MarkdownSourceKind.Code && body.substring(it.start,it.end)=="#inside *literal*"})
 }
 @Test fun unicodeCrLfAndLoneCrKeepTheirOriginalUtf16Positions() {
  for(ending in listOf("\n","\r\n","\r")) {
   val body="🙂 first${ending}# È italiano${ending}${ending}`🙂 code`"
   val spans=markdownSourceSpans(body)
   val heading=spans.single {it.kind==MarkdownSourceKind.Heading}
   assertEquals(body.indexOf('#'),heading.start)
   assertEquals("# È italiano",body.substring(heading.start,heading.end))
   val code=spans.single {it.kind==MarkdownSourceKind.Code}
   assertEquals(body.indexOf('`'),code.start)
   assertEquals("`🙂 code`",body.substring(code.start,code.end))
  }
 }
 @Test fun labelsEscapesAndRawHtmlAreNotMarkdownHeadings() {
  assertTrue(markdownSourceSpans("#idea plain\n\\# Escaped\n\n<div># Literal *HTML*</div>").isEmpty())
 }
 @Test fun unfinishedFencesAndIndentedCodeDoNotStyleTheirContentsAsMarkdown() {
  for(body in listOf("```\n# Heading\n**strong**","    # Heading\n    **strong**")) {
   val spans=markdownSourceSpans(body)
   assertTrue(spans.isNotEmpty())
   assertTrue(spans.all {it.kind==MarkdownSourceKind.Code && it.start>=0 && it.end<=body.length})
  }
 }
}
