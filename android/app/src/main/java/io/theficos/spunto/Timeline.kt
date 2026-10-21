@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package io.theficos.spunto

import android.content.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.paging.compose.collectAsLazyPagingItems
import io.theficos.spunto.core.design.*
import io.theficos.spunto.core.model.*
import io.theficos.spunto.core.plugins.*
import io.theficos.spunto.data.sync.*
import io.theficos.spunto.data.local.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import java.time.*
import java.time.format.DateTimeFormatter

fun noteTime(at:Long)=Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("d MMM · HH:mm"))
fun payload(raw:String):JsonObject=runCatching { Json.parseToJsonElement(raw).jsonObject }.getOrDefault(JsonObject(emptyMap()))
@Composable fun Timeline(model:SpuntoViewModel,onNote:(String)->Unit,onReview:()->Unit,from:String="",to:String="") {
 val manifest=model.manifest.collectAsState().value
 val range=remember(from,to) { runCatching { CreatedRange.parse(from,to) } }
 val spec=remember(range.getOrNull(),range.isSuccess) { TimelineSpec(created=range.getOrDefault(CreatedRange()),valid=range.isSuccess) }
 val paging=remember(spec) { model.repository.paging(spec) }
 val page=paging.collectAsLazyPagingItems()
 val outputs by model.outputs.collectAsState()
 val notes by model.notes.collectAsState()
 val proposals=outputs.count { it.kind=="suggestion" && payload(it.payload).string("status")=="pending" && model.container.manifest.enabled(payload(it.payload).string("plugin")) }
 LazyColumn(Modifier.fillMaxSize().imePadding(),contentPadding=PaddingValues(20.dp),verticalArrangement=Arrangement.spacedBy(20.dp)) {
  item { Composer(model,onPlainCaptureSave={body,ref ->model.repository.create(body,sourceRef=ref)},onSave={model.saveLocal(it)}) }
  if(proposals>=3) item { TextButton(onClick=onReview) { Text("$proposals suggestions · Review") } }
  outputs.filter { it.kind=="annotation" && payload(it.payload).string("kind")=="slip" && model.container.manifest.enabled(payload(it.payload).string("plugin")) && notes.any { note -> note.id==it.noteId && !note.privateNote && !note.trashed } }.take(1).forEach { output -> item { Surface(color=MaterialTheme.colorScheme.surfaceVariant) { Column(Modifier.padding(16.dp)) { Text("From your notes",style=MaterialTheme.typography.labelSmall); notes.firstOrNull { it.id==output.noteId }?.let { Markdown(it.body.take(600));Row { TextButton(onClick={onNote(it.id)}) { Text("Open") };TextButton(onClick={model.flags(it.id,archived=true)}) { Text("Archive") } } } ?: RenderOutput(model,payload(output.payload),onNote) } } } }
  if(page.itemCount==0) item { if(from.isNotBlank() || to.isNotBlank()) Text("No notes in this date range.") else { Text("A quiet place for loose ideas.",style=MaterialTheme.typography.titleLarge); Text("Share anything to spunto from any app, or select text and choose Save to spunto.",color=MaterialTheme.colorScheme.onSurfaceVariant) } }
  items(page.itemCount,key={index -> page.peek(index)?.id ?: index}) { index -> page[index]?.note()?.let { NoteEntry(model,it,onNote) } }
 }
}
@Composable fun NoteEntry(model:SpuntoViewModel,note:Note,onNote:(String)->Unit) {
 val manifest=model.manifest.collectAsState().value
 val outputs by model.outputs.collectAsState()
 val meta=entryMetaText(outputs,note.id,model.container.manifest.plugins())
 Column(Modifier.fillMaxWidth().clickable { onNote(note.id) }.semantics { contentDescription="Note, ${noteTime(note.createdAt)}, ${note.title}" },verticalArrangement=Arrangement.spacedBy(8.dp)) {
  Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(12.dp),verticalAlignment=Alignment.CenterVertically) {
   Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(4.dp)) {
    Text("${if(note.pending) "◯ " else ""}${noteTime(note.createdAt)}${if(note.pinned) " · Pinned" else ""}",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
    if(meta.isNotEmpty()) Text(meta,style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
   }
   TextButton(onClick={onNote(note.id)},modifier=Modifier.heightIn(min=48.dp)) {Text("Open")}
  }
  Markdown(note.body.take(1200),note.machine,onLink={if(it.startsWith("/n/")) onNote(it.substringAfter("/n/")) else if(it.startsWith("/a/")) onNote(note.id)}) { model.toggleTask(note,it) }
  val provenance=buildList {
   if(note.privateNote) add("Private")
   if(note.conflictOf!=null) add("Conflicted copy")
   if(note.updatedViaAgent && note.sourceKind!="agent") add("Edited via agent")
   if(note.sourceKind in setOf("agent","plugin")) add("${if(note.machine) "✦ via" else "via"} ${note.sourceRef ?: note.sourceKind}")
  }
  if(provenance.isNotEmpty()) Text(provenance.joinToString(" · "),style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
  Suggestions(model,note.id,onNote)
  if(note.machine) Text("Written by ${note.sourceRef ?: "a plugin"}. Plugins leave it out until you edit it.",style=MaterialTheme.typography.bodySmall)
  HorizontalDivider(color=MaterialTheme.colorScheme.outlineVariant)
 }
}
@Composable fun FilteredTimeline(model:SpuntoViewModel,route:String,from:String="",to:String="",onNote:(String)->Unit) {
 val manifest by model.manifest.collectAsState()
 val range=remember(from,to) { runCatching { CreatedRange.parse(from,to) } }
 val today=LocalDate.now(ZoneOffset.UTC)
 val spec=remember(route,manifest,range.getOrNull(),range.isSuccess,today) {
  val created=range.getOrDefault(CreatedRange())
  when {
   route=="archive" -> TimelineSpec(kind=TimelineKind.ARCHIVE,created=created,valid=range.isSuccess)
   route=="trash" -> TimelineSpec(kind=TimelineKind.TRASH,created=created,valid=range.isSuccess)
   route.startsWith("label:") -> TimelineSpec(kind=TimelineKind.LABEL,label=route.substringAfter(':'),created=created,valid=range.isSuccess)
   else -> { val parts=route.split(':',limit=3);val view=resolveViewRoute(manifest,route);TimelineSpec(kind=TimelineKind.PLUGIN,pluginId=parts.getOrNull(1).orEmpty(),filter=view?.filter ?: JsonObject(emptyMap()),created=created,valid=range.isSuccess && view!=null) }
  }
 }
 val paging=remember(spec) { model.repository.paging(spec) }
 val page=paging.collectAsLazyPagingItems()
 LazyColumn(contentPadding=PaddingValues(20.dp),verticalArrangement=Arrangement.spacedBy(20.dp)) {
  if(page.itemCount==0) item { Text(if(from.isNotBlank() || to.isNotBlank()) "No notes in this date range." else "Nothing here yet.") }
  items(page.itemCount,key={index -> page.peek(index)?.id ?: index}) { index -> page[index]?.note()?.let { NoteEntry(model,it,onNote) } }
 }
}
