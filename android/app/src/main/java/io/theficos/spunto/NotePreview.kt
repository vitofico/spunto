package io.theficos.spunto

import io.theficos.spunto.core.model.labels
import io.theficos.spunto.core.model.titleOf

internal data class NotePreview(val title:String?,val body:String,val bodyStartLine:Int,val labels:List<String>)
/** Display-only extraction. Body source stays intact; the Markdown renderer hides labels after parsing. */
internal fun notePreview(body:String,title:String):NotePreview {
 val tokens=labels(body)
 val lines=body.lines()
 val withoutLabels=buildString {
  var at=0
  for(token in tokens) {append(body,at,token.start);at=token.end}
  append(body,at,body.length)
 }.lines().mapIndexed {index,line ->
  // Removing a leading label must not turn the remaining text into an indented code block.
  val indent=lines[index].takeWhile {it==' ' || it=='\t'}.length
  line.take(indent)+line.drop(indent).trimStart(' ','\t')
 }
 val first=lines.indexOfFirst {it.isNotBlank()}.coerceAtLeast(0)
 val source=lines.getOrElse(first) {""}.trimStart()
 val heading=Regex("^#{1,6}\\s+").containsMatchIn(source)
 val indented=lines.getOrElse(first) {""}.let {it.startsWith("    ") || it.startsWith('\t')}
 val lineStarts=listOf(0)+Regex("\\r\\n|\\n|\\r").findAll(body).map {it.range.last+1}.toList()
 val wrappedLabel=tokens.any {it.start in lineStarts[first] until lineStarts[first]+lines[first].length && it.start>0 && body[it.start-1]=='('}
 val interactive=indented || wrappedLabel || source.contains('[') || source.contains('`') || source.startsWith(">") || source.startsWith("<") || Regex("^(?:[-*+]\\s|\\d+[.)]\\s|~{3,})").containsMatchIn(source)
 // A derived/truncated title is not a second passage. Links, code and tasks stay actionable in the body.
 val complete=heading || lines.getOrNull(first+1).isNullOrBlank()
 val cleared=withoutLabels[first].trimStart()
 val newBlock=!heading && cleared!=source && Regex("^(?:#{1,6}\\s|>\\s*|[-*+]\\s|\\d+[.)]\\s|`{3,}|~{3,}|<)").containsMatchIn(cleared)
 val separate=source.isNotBlank() && !interactive && !newBlock && complete && titleOf(lines[first],Int.MAX_VALUE)==title.trim()
 val displayTitle=if(separate) titleOf(withoutLabels[first],Int.MAX_VALUE).replace(Regex("[ \t]+")," ").trim().ifBlank {null} else null
 val start=if(separate) first+1 else 0
 var end=lines.size
 while(end>start && lines[end-1].isBlank()) end--
 return NotePreview(displayTitle,lines.subList(start.coerceAtMost(end),end).joinToString("\n"),start,tokens.distinctBy {it.key}.map {it.path})
}
