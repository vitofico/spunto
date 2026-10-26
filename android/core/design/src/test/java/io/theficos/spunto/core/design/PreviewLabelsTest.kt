package io.theficos.spunto.core.design

import org.commonmark.node.*
import org.commonmark.parser.IncludeSourceSpans
import org.commonmark.parser.Parser
import org.junit.Assert.*
import org.junit.Test

class PreviewLabelsTest {
 private val parser=Parser.builder().includeSourceSpans(IncludeSourceSpans.BLOCKS_AND_INLINES).build()
 private fun project(body:String)=hidePreviewLabels(parser.parse(body),body)
 private fun nodes(root:Node):List<Node> = buildList {fun walk(node:Node) {add(node);var child=node.firstChild;while(child!=null) {walk(child);child=child.next}};walk(root)}
 private fun text(root:Node)=nodes(root).filterIsInstance<Text>().joinToString("") {it.literal}

 @Test fun decodedEntitiesAndEscapesDoNotShiftWhichLabelIsHidden() {
  assertEquals("A & B ",text(project("A &amp; B #ideas")))
  assertEquals("#ideas and ",text(project("&#35;ideas and #ideas")))
  assertEquals("#ideas and ",text(project("\\#ideas and #ideas")))
 }
 @Test fun nestedAndUnicodeLabelsUseOriginalUtf16Spans() {
  assertEquals("😀 & ",text(project("😀 &amp; #cafe\u0301/plans")))
 }
 @Test fun codeNumericAndInvalidReferencesRemainLiteral() {
  val document=project("Keep `#ideas` and #12345 #bad//path #ideas")
  assertEquals("#ideas",nodes(document).filterIsInstance<Code>().single().literal)
  assertEquals("Keep  and #12345 #bad//path ",text(document))
 }
 @Test fun aTagOnlyLinkKeepsItsDestinationAndGetsAMeaningfulName() {
  val link=nodes(project("[#ideas](https://example.org)")).filterIsInstance<Link>().single()
  assertEquals("https://example.org",link.destination);assertEquals(link.destination,text(link))
 }
 @Test fun aTagOnlyImageKeepsItsDestinationAndGetsAMeaningfulAlt() {
  val image=nodes(project("![#ideas](/a/synthetic-image)")).filterIsInstance<Image>().single()
  assertEquals("/a/synthetic-image",image.destination);assertEquals(image.destination,text(image))
 }
 @Test fun aLinkWithOtherWordsKeepsThemAndItsAction() {
  val link=nodes(project("Read [the #ideas guide](https://example.org)")).filterIsInstance<Link>().single()
  assertEquals("the  guide",text(link));assertEquals("https://example.org",link.destination)
 }
 @Test fun aLabelInAnInvisibleDestinationDoesNotRewriteThatDestination() {
  val link=nodes(project("[jump](#ideas)")).filterIsInstance<Link>().single()
  assertEquals("jump",text(link));assertEquals("#ideas",link.destination)
 }
 @Test fun tagOnlyWrappersDoNotLeaveEmptyPunctuation() {
  for(body in listOf("(#ideas)","[#ideas]","(#ideas #later)","((#ideas))")) assertEquals(body,"",text(project(body)))
  assertEquals("Keep () ",text(project("Keep () #ideas")))
 }
 @Test fun aLeadingTagCannotManufactureATaskHeadingOrFence() {
  for(body in listOf("#ideas - [ ] This is prose","#ideas # This is prose","#ideas ```\nordinary text")) {
   val document=project(body)
   assertTrue(document.firstChild is Paragraph)
   assertFalse(nodes(document).any {it is BulletList || it is Heading || it is FencedCodeBlock || it is IndentedCodeBlock})
  }
 }
 @Test fun removingTagsKeepsActualListItemSourceLines() {
  val document=project("#ideas\n\n- [ ] First #tasks\n- [x] Second #tasks")
  val items=nodes(document).filterIsInstance<ListItem>()
  assertEquals(listOf(2,3),items.map {it.sourceSpans.first().lineIndex})
  assertEquals("[ ] First [x] Second ",items.joinToString("") {text(it)})
 }
}
