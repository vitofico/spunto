package io.theficos.spunto.data.local

import org.commonmark.node.Image
import org.commonmark.node.Node
import org.commonmark.parser.IncludeSourceSpans
import org.commonmark.parser.Parser
import java.util.UUID

/** Only an existing explicit canonical image link proves a legacy attachment's marker. */
internal fun canonicalLegacyImageMarker(body:String,remoteId:String):String? {
 if(runCatching {UUID.fromString(remoteId).toString()==remoteId}.getOrDefault(false).not()) return null
 val lines=body.lines();val matches=mutableSetOf<String>()
 fun visit(node:Node) {
  if(node is Image && node.destination=="/a/$remoteId") {
   node.sourceSpans.singleOrNull()?.let {span ->
    val line=lines.getOrNull(span.lineIndex)
    if(line!=null && span.columnIndex>=0 && span.columnIndex+span.length<=line.length) {
     val raw=line.substring(span.columnIndex,span.columnIndex+span.length)
     if(raw.startsWith("![") && raw.endsWith("](/a/$remoteId)")) matches+=raw
    }
   }
  }
  var child=node.firstChild;while(child!=null) {visit(child);child=child.next}
 }
 visit(Parser.builder().includeSourceSpans(IncludeSourceSpans.BLOCKS_AND_INLINES).build().parse(body))
 return matches.singleOrNull()
}
