package io.theficos.spunto.core.plugins

enum class TextChangeKind { Unchanged, Removed, Added }
data class TextChange(val kind:TextChangeKind,val text:String)

/** Preserve both texts exactly, with a bounded word comparison for long notes. */
fun textChanges(before:String,after:String):List<TextChange> {
 if(before==after) return listOf(TextChange(TextChangeKind.Unchanged,before))
 val token=Regex("\\s+|[^\\s]+")
 val old=token.findAll(before).map {it.value}.toList()
 val new=token.findAll(after).map {it.value}.toList()
 val changes=mutableListOf<TextChange>()
 var active:TextChangeKind?=null
 val buffer=StringBuilder()
 fun flush() {active?.let {if(buffer.isNotEmpty()) changes+=TextChange(it,buffer.toString())};buffer.setLength(0)}
 fun append(kind:TextChangeKind,text:String) {
  if(text.isEmpty()) return
  if(active!=kind) {flush();active=kind}
  buffer.append(text)
 }
 var prefix=0
 while(prefix<old.size && prefix<new.size && old[prefix]==new[prefix]) prefix++
 var suffix=0
 while(suffix<old.size-prefix && suffix<new.size-prefix && old[old.lastIndex-suffix]==new[new.lastIndex-suffix]) suffix++
 append(TextChangeKind.Unchanged,old.take(prefix).joinToString(""))
 val a=old.subList(prefix,old.size-suffix)
 val b=new.subList(prefix,new.size-suffix)
 if(a.isEmpty()) append(TextChangeKind.Added,b.joinToString(""))
 else if(b.isEmpty()) append(TextChangeKind.Removed,a.joinToString(""))
 else if(a.size.toLong()*b.size.toLong()>250_000L) {
  append(TextChangeKind.Removed,a.joinToString(""));append(TextChangeKind.Added,b.joinToString(""))
 } else {
  val lengths=Array(a.size+1) {IntArray(b.size+1)}
  for(i in a.indices.reversed()) for(j in b.indices.reversed()) lengths[i][j]=if(a[i]==b[j]) 1+lengths[i+1][j+1] else maxOf(lengths[i+1][j],lengths[i][j+1])
  var i=0;var j=0
  while(i<a.size || j<b.size) {
   when {
    i<a.size && j<b.size && a[i]==b[j] -> {append(TextChangeKind.Unchanged,a[i]);i++;j++}
    i<a.size && (j==b.size || lengths[i+1][j]>=lengths[i][j+1]) -> {append(TextChangeKind.Removed,a[i]);i++}
    else -> {append(TextChangeKind.Added,b[j]);j++}
   }
  }
 }
 append(TextChangeKind.Unchanged,old.takeLast(suffix).joinToString(""))
 flush()
 return changes
}

/** Hide only long unchanged passages; every inserted and removed character remains visible. */
fun focusedTextChanges(changes:List<TextChange>):List<TextChange> = changes.mapIndexed { index,change ->
 if(change.kind!=TextChangeKind.Unchanged || change.text.length<=240) change
 else change.copy(text=when(index) {
  0 -> "…\n"+change.text.takeLast(120)
  changes.lastIndex -> change.text.take(120)+"\n…"
  else -> change.text.take(100)+"\n… unchanged text …\n"+change.text.takeLast(100)
 })
}

/** Colour cannot expose an empty paragraph or a removed space on its own. */
fun whitespaceChangeSummaries(changes:List<TextChange>):List<String> = listOf(TextChangeKind.Removed,TextChangeKind.Added).flatMap {kind ->
 val whitespace=changes.filter {it.kind==kind && it.text.all(Char::isWhitespace)}.joinToString("") {it.text}
 val breaks=Regex("\\r\\n|\\r|\\n").findAll(whitespace).count()
 val spaces=whitespace.count {it!='\r' && it!='\n' && it!='\t'}
 val tabs=whitespace.count {it=='\t'}
 val verb=if(kind==TextChangeKind.Added) "Added" else "Removed"
 listOf(breaks to "line break",spaces to "space",tabs to "tab").filter {it.first>0}.map {(count,unit) -> "$verb $count $unit${if(count==1) "" else "s"}"}
}

/** The combined-lines vocabulary prefixes changes, while paired hunks carry plain text. */
fun diffHunkTexts(hunk:kotlinx.serialization.json.JsonObject):Pair<String,String> {
 fun lines(key:String)=(hunk[key] as? kotlinx.serialization.json.JsonArray)?.mapNotNull {(it as? kotlinx.serialization.json.JsonPrimitive)?.content}.orEmpty()
 if(hunk["lines"] is kotlinx.serialization.json.JsonArray) {
  val combined=lines("lines")
  return combined.filterNot {it.startsWith('+')}.joinToString("\n") {if(it.startsWith('-') || it.startsWith(' ')) it.drop(1) else it} to combined.filterNot {it.startsWith('-')}.joinToString("\n") {if(it.startsWith('+') || it.startsWith(' ')) it.drop(1) else it}
 }
 return lines("old_lines").joinToString("\n") to lines("new_lines").joinToString("\n")
}
