package io.theficos.spunto.data.local

import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.sqlite.db.SupportSQLiteQuery
import io.theficos.spunto.core.model.labelKey
import kotlinx.serialization.json.*
import org.commonmark.node.*
import org.commonmark.parser.IncludeSourceSpans
import org.commonmark.parser.Parser
import java.time.*

enum class TimelineKind { HOME, ARCHIVE, TRASH, LABEL, PLUGIN }
data class CreatedRange(val fromInclusive:Long?=null,val beforeExclusive:Long?=null) {
 init { require(fromInclusive==null || beforeExclusive==null || fromInclusive<beforeExclusive) { "From must be on or before To." } }
 companion object {
  fun parse(from:String,to:String,zone:ZoneId=ZoneId.systemDefault()):CreatedRange = CreatedRange(
   from.takeIf { it.isNotBlank() }?.let { LocalDate.parse(it.trim()).atStartOfDay(zone).toInstant().toEpochMilli() },
   to.takeIf { it.isNotBlank() }?.let { LocalDate.parse(it.trim()).plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() })
 }
}
data class TimelineSpec(val kind:TimelineKind=TimelineKind.HOME,val label:String="",val pluginId:String="",val filter:JsonObject=JsonObject(emptyMap()),val created:CreatedRange=CreatedRange(),val now:Long=System.currentTimeMillis(),val valid:Boolean=true)
/** Closed server filter language; all user values bind as parameters, never SQL. */
fun timelineQuery(spec:TimelineSpec):SupportSQLiteQuery = runCatching {
 val clauses=mutableListOf<String>();val args=mutableListOf<Any?>()
 if(!spec.valid) clauses+="0"
 if(spec.kind!=TimelineKind.TRASH) clauses+="n.trashed=0"
 fun label(path:String) { val key=labelKey(path);clauses+="EXISTS (SELECT 1 FROM labels l WHERE l.noteId=n.id AND (l.key=? OR substr(l.key,1,length(?)+1)=?||'/'))";args.addAll(listOf(key,key,key)) }
 when(spec.kind) {
  TimelineKind.HOME -> clauses+="n.archived=0"
  TimelineKind.ARCHIVE -> clauses+="n.archived=1"
  TimelineKind.TRASH -> clauses+="n.trashed=1"
  TimelineKind.LABEL -> { clauses+="n.archived=0";label(spec.label) }
  TimelineKind.PLUGIN -> {
   require(spec.pluginId.isNotEmpty())
   val allowed=setOf("labels","no_labels","source","open_tasks","pinned","archived","days","created_within_days","member","not_member")
   require(spec.filter.keys.all { it in allowed })
   for((key,value) in spec.filter) when(key) {
    "labels" -> { val paths=value as? JsonArray ?: error("invalid_labels");paths.forEach { require(it is JsonPrimitive && it.isString);label(it.content) } }
    "no_labels" -> { val none=(value as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull ?: error("invalid_boolean");clauses+=(if(none) "NOT " else "")+"EXISTS (SELECT 1 FROM labels l WHERE l.noteId=n.id)" }
    "source" -> {
     val source=(value as? JsonPrimitive)?.takeIf { it.isString }?.content ?: error("invalid_source")
     require(source in setOf("owner","web","android","share","selection","api","plugin","import","agent") || source.startsWith("plugin:"))
     when { source=="owner" -> clauses+="n.origin='owner'";source=="agent" -> clauses+="(n.sourceKind='agent' OR n.updatedViaAgent=1)";source.startsWith("plugin:") -> { clauses+="n.sourceRef=?";args+=source.substringAfter(':') };else -> { clauses+="n.sourceKind=?";args+=source } }
    }
    "open_tasks","pinned","archived" -> { val active=(value as? JsonPrimitive)?.takeUnless {it.isString}?.booleanOrNull ?: error("invalid_boolean");if(key=="open_tasks") clauses+=if(active) "n.openTasks>0" else "n.openTasks=0" else { clauses+="n."+key+"=?";args+=if(active) 1 else 0 } }
    "days","created_within_days" -> { val days=(value as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull ?: error("invalid_days");require(days in 1L..36500L);val midnight=Instant.ofEpochMilli(spec.now).atZone(ZoneOffset.UTC).toLocalDate().atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();clauses+="n.createdAt>=?";args+=midnight-(days-1)*86400000 }
    "member","not_member" -> { val view=(value as? JsonPrimitive)?.takeIf { it.isString }?.content ?: error("invalid_member");require(view.isNotEmpty());clauses+=(if(key=="not_member") "NOT " else "")+"EXISTS (SELECT 1 FROM view_memberships m WHERE m.noteId=n.id AND m.pluginId=? AND m.viewId=?)";args.addAll(listOf(spec.pluginId,view)) }
   }
  }
 }
 spec.created.fromInclusive?.let { clauses+="n.createdAt>=?";args+=it }
 spec.created.beforeExclusive?.let { clauses+="n.createdAt<?";args+=it }
 val order=when(spec.kind) { TimelineKind.HOME,TimelineKind.LABEL -> "n.pinned DESC,n.createdAt DESC,n.id DESC";TimelineKind.TRASH -> "n.trashedAt DESC,n.createdAt DESC,n.id DESC";else -> "n.createdAt DESC,n.id DESC" }
 SimpleSQLiteQuery("SELECT n.* FROM notes n WHERE "+clauses.joinToString(" AND ")+" ORDER BY "+order,args.toTypedArray())
}.getOrElse { SimpleSQLiteQuery("SELECT n.* FROM notes n WHERE 0") }

fun viewMembership(output:PluginOutputEntity):ViewMembershipEntity?=runCatching {
 if(output.kind!="annotation") return null
 val data=Json.parseToJsonElement(output.payload).jsonObject
 val kind=data["kind"]?.jsonPrimitive?.content.orEmpty();val plugin=(data["plugin_id"] ?: data["plugin"])?.jsonPrimitive?.content.orEmpty()
 val note=output.noteId ?: data["note_id"]?.jsonPrimitive?.content ?: return null
 if(!kind.startsWith("member:") || kind.substringAfter(':').isEmpty() || plugin.isEmpty()) return null
 ViewMembershipEntity(output.id,note,plugin,kind.substringAfter(':'))
}.getOrNull()

private val taskParser=Parser.builder().includeSourceSpans(IncludeSourceSpans.BLOCKS_AND_INLINES).build()
/** Server task_counts counts leading inline checkbox lines, excluding fenced and inline code. */
fun openTaskCount(body:String):Int {
 val lines=body.lines();var count=0
 fun walk(node:Node) {
  if(node is Paragraph || node is Heading) {
   val codeRanges=mutableListOf<SourceSpan>()
   fun codeSpans(child:Node) { if(child is Code) codeRanges.addAll(child.sourceSpans);var next=child.firstChild;while(next!=null) { codeSpans(next);next=next.next } }
   codeSpans(node)
   node.sourceSpans.forEach { span ->
    val raw=lines.getOrNull(span.lineIndex).orEmpty().drop(span.columnIndex).take(span.length)
    val heading=if(node is Heading) Regex("^ {0,3}#{1,6}\\s+").find(raw)?.value?.length ?: 0 else 0
    val content=raw.drop(heading)
    Regex("^\\s*\\[ ]\\s").find(content)?.let {
     val column=span.columnIndex+heading+content.indexOf('[')
     if(codeRanges.none { it.lineIndex==span.lineIndex && column>=it.columnIndex && column<it.columnIndex+it.length }) count++
    }
   }
  }
  var child=node.firstChild;while(child!=null) { walk(child);child=child.next }
 }
 walk(taskParser.parse(body));return count
}
