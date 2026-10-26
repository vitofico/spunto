package io.theficos.spunto.core.design

import io.theficos.spunto.core.model.labels
import org.commonmark.internal.util.Escaping
import org.commonmark.node.*

/** Hide metadata in parsed text, never in Markdown source: deleting a tag must not create a new block. */
internal fun hidePreviewLabels(document:Node,body:String):Node {
 val tokens=labels(body)
 fun content(node:Node):String=buildString {
  fun walk(current:Node) {
   when(current) {is Text ->append(current.literal);is Code ->append(current.literal);else -> {var child=current.firstChild;while(child!=null) {walk(child);child=child.next}}}
  }
  walk(node)
 }
 fun walk(node:Node):Boolean {
  var changed=false
  var child=node.firstChild
  while(child!=null) {changed=walk(child) || changed;child=child.next}
  if(node is Text) {
   val ranges=mutableListOf<IntRange>()
   var renderedStart=0
   for(span in node.sourceSpans) {
    val raw=body.substring(span.inputIndex,span.inputIndex+span.length)
    var rawAt=0
    var decoded=renderedStart
    var tokenIndex=tokens.binarySearchBy(span.inputIndex) {it.start}.let {if(it<0) -it-1 else it}
    while(tokenIndex<tokens.size && tokens[tokenIndex].start<span.inputIndex+span.length) {
     val token=tokens[tokenIndex++]
     if(token.end>span.inputIndex+span.length) continue
     var start=token.start-span.inputIndex
     var end=token.end-span.inputIndex
     // Parentheses/brackets that only enclosed a tag should not remain as empty visual debris.
     while(start>0 && end<raw.length && ((raw[start-1]=='(' && raw[end]==')') || (raw[start-1]=='[' && raw[end]==']'))) {start--;end++}
     val from=decoded+Escaping.unescapeString(raw.substring(rawAt,start)).length
     val to=from+Escaping.unescapeString(raw.substring(start,end)).length
     rawAt=end;decoded=to
     if(from<to && to<=node.literal.length) ranges+=from until to
    }
    renderedStart=decoded+Escaping.unescapeString(raw.substring(rawAt)).length
   }
   if(ranges.isNotEmpty()) {
    val original=node.literal
    node.literal=buildString {
     var at=0
     for(range in ranges.sortedBy {it.first}) {if(range.first>at) append(original,at,range.first);at=maxOf(at,range.last+1)}
     append(original,at,original.length)
    }
    changed=true
    if(ranges.any {0 in it}) node.literal=node.literal.trimStart()
    val before=original.trim();val after=node.literal.trim()
    if(after in listOf("()","[]","( )","[ ]") && before.firstOrNull()==after.firstOrNull() && before.lastOrNull()==after.lastOrNull()) node.literal=""
   }
  }
  // A tag-only link/image still needs a meaningful accessible name and its original destination.
  if(changed && (node is Link || node is Image) && content(node).isBlank()) {
   val destination=if(node is Link) node.destination else (node as Image).destination
   node.appendChild(Text(destination))
  }
  return changed
 }
 walk(document)
 return document
}
