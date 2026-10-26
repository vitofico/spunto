package io.theficos.spunto

import io.theficos.spunto.core.model.labels

internal data class NotePreview(val title:String,val body:String,val bodyStartLine:Int)
/** Presentation only. Keeping the line offset makes checklist edits target the original source. */
internal fun notePreview(body:String,title:String):NotePreview {
 val lines=body.lines()
 val first=lines.indexOfFirst {it.isNotBlank()}.coerceAtLeast(0)
 val heading=lines.getOrElse(first) {""}.trim().replace(Regex("^#{1,6}\\s+"),"")
 val source=lines.getOrElse(first) {""}.trimStart()
 val interactive=source.startsWith("[") || source.startsWith("![") || source.startsWith("`") || source.startsWith(">") || Regex("^(?:[-*+]\\s|\\d+[.)]\\s)").containsMatchIn(source)
 val start=if(!interactive && heading==title.trim()) first+1 else 0
 val labelEnds=labels(body).associate {it.start to it.end}
 val lineStarts=listOf(0)+Regex("\\r\\n|\\n|\\r").findAll(body).map {it.range.last+1}.toList()
 fun labelOnlyLine(index:Int):Boolean {
  var at=lineStarts[index]
  val end=at+lines[index].length
  var found=false
  while(at<end) {
   if(body[at].isWhitespace()) at++ else {
    val labelEnd=labelEnds[at] ?: return false
    if(labelEnd>end) return false
    at=labelEnd;found=true
   }
  }
  return found
 }
 var end=lines.size
 while(end>start && (lines[end-1].isBlank() || labelOnlyLine(end-1))) end--
 return NotePreview(title.ifBlank {heading},lines.subList(start.coerceAtMost(end),end).joinToString("\n"),start)
}
