package io.theficos.spunto.core.design

import org.commonmark.node.*
import org.commonmark.parser.IncludeSourceSpans
import org.commonmark.parser.Parser

enum class MarkdownSourceKind { Heading, Code, Emphasis, Strong }
data class MarkdownSourceSpan(val kind:MarkdownSourceKind,val start:Int,val end:Int)
private val sourceParser=Parser.builder().includeSourceSpans(IncludeSourceSpans.BLOCKS_AND_INLINES).build()

/** Styling ranges in the original UTF-16 source. Delimiters and line endings stay editable. */
fun markdownSourceSpans(body:String):List<MarkdownSourceSpan> {
 val offsets=mutableListOf(0)
 var index=0
 while(index<body.length) {
  when(body[index]) {
   '\r' -> {if(index+1<body.length && body[index+1]=='\n') index++;offsets.add(index+1)}
   '\n' -> offsets.add(index+1)
  }
  index++
 }
 val ranges=mutableListOf<MarkdownSourceSpan>()
 fun visit(node:Node) {
  val kind=when(node) {
   is Heading -> MarkdownSourceKind.Heading
   is Code,is FencedCodeBlock,is IndentedCodeBlock -> MarkdownSourceKind.Code
   is StrongEmphasis -> MarkdownSourceKind.Strong
   is Emphasis -> MarkdownSourceKind.Emphasis
   else -> null
  }
  if(kind!=null) node.sourceSpans.forEach {span ->
   offsets.getOrNull(span.lineIndex)?.let {lineStart ->
    val start=lineStart+span.columnIndex
    val end=start+span.length
    if(start>=0 && end>start && end<=body.length) ranges.add(MarkdownSourceSpan(kind,start,end))
   }
  }
  if(kind==MarkdownSourceKind.Code || node is HtmlBlock || node is HtmlInline) return
  var child=node.firstChild
  while(child!=null) {visit(child);child=child.next}
 }
 visit(sourceParser.parse(body))
 return ranges
}
