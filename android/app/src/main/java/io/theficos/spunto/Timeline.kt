@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class,androidx.compose.foundation.ExperimentalFoundationApi::class,androidx.compose.material3.ExperimentalMaterial3Api::class)
package io.theficos.spunto

import android.content.Context

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.zIndex
import androidx.compose.runtime.saveable.rememberSaveable
import kotlin.math.roundToInt
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.paging.LoadState
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.collectAsLazyPagingItems
import io.theficos.spunto.core.design.*
import io.theficos.spunto.core.model.*
import io.theficos.spunto.core.plugins.*
import io.theficos.spunto.data.local.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.*
import java.time.*
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

fun noteTime(at:Long,locale:java.util.Locale=java.util.Locale.getDefault())=Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("d MMM · HH:mm",locale))
fun payload(raw:String):JsonObject=runCatching { Json.parseToJsonElement(raw).jsonObject }.getOrDefault(JsonObject(emptyMap()))

/** Day headers (03 A1): Today, Yesterday, the weekday for the rest of the week, then one header per month. */
internal fun timelineGroup(context:Context,at:Long,today:LocalDate=LocalDate.now(),zone:ZoneId=ZoneId.systemDefault()):String {
 val day=Instant.ofEpochMilli(at).atZone(zone).toLocalDate()
 return when(ChronoUnit.DAYS.between(day,today)) {
  0L -> context.getString(R.string.core_today);1L -> context.getString(R.string.core_yesterday)
  in 2L..6L -> day.format(DateTimeFormatter.ofPattern("EEEE d MMMM",context.resources.configuration.locales[0]))
  else -> day.format(DateTimeFormatter.ofPattern("MMMM yyyy",context.resources.configuration.locales[0]))
 }
}
/** Under a day header an entry shows only its time; under a month or Pinned it shows the date too. */
internal fun entryTime(context:Context,at:Long,group:String?,today:LocalDate=LocalDate.now(),zone:ZoneId=ZoneId.systemDefault()):String =
 if(group!=null && group!=context.getString(R.string.core_pinned) && ChronoUnit.DAYS.between(Instant.ofEpochMilli(at).atZone(zone).toLocalDate(),today) in 0L..6L) Instant.ofEpochMilli(at).atZone(zone).format(DateTimeFormatter.ofPattern("HH:mm")) else noteTime(at,context.resources.configuration.locales[0])
internal data class TimelineRow(val header:String?,val index:Int,val group:String?)
/** Headers go before the first entry of each group; pinned notes, which the list puts first, form their own group. */
internal fun timelineRows(context:Context,notes:List<Note>,grouped:Boolean,today:LocalDate=LocalDate.now(),zone:ZoneId=ZoneId.systemDefault()):List<TimelineRow> {
 if(!grouped) return notes.indices.map { TimelineRow(null,it,null) }
 var previous:String?=null
 return notes.flatMapIndexed { index,note ->
  val group=if(note.pinned) context.getString(R.string.core_pinned) else timelineGroup(context,note.createdAt,today,zone)
  listOfNotNull(TimelineRow(group,index,group).takeIf {group!=previous},TimelineRow(null,index,group)).also {previous=group}
 }
}
private val taskLine=Regex("^\\s*(?:[-*+]|[0-9]+[.)]) \\[([ xX])] ")
/** What TalkBack reads for an entry (03 §10): time, title, labels, state and checklist progress. */
internal fun entryDescription(context:Context,note:Note,time:String):String {
 val tasks=note.body.lines().mapNotNull { taskLine.find(it)?.groupValues?.get(1) }
 return buildList {
  add(context.getString(R.string.core_entry_description,time,note.title.ifBlank { context.getString(R.string.core_untitled) }))
  labels(note.body).map { it.path.replace('/',' ') }.distinct().takeIf { it.isNotEmpty() }?.let { add(context.getString(R.string.core_entry_labels,it.joinToString(", "))) }
  if(note.pinned) add(context.getString(R.string.core_pinned))
  if(tasks.isNotEmpty()) add(context.getString(R.string.core_tasks_done,tasks.count { it!=" " },tasks.size))
  if(note.privateNote) add(context.getString(R.string.core_private))
  if(note.conflictOf!=null) add(context.getString(R.string.core_conflicted_copy))
  if(note.pending) add(context.getString(R.string.core_not_synced))
 }.joinToString(". ")
}

@Composable fun Timeline(model:SpuntoViewModel,onNote:(String)->Unit,onReview:()->Unit,from:String="",to:String="") {
 val context=LocalContext.current
 val range=remember(from,to) { runCatching { CreatedRange.parse(from,to) } }
 val spec=remember(range.getOrNull(),range.isSuccess) { TimelineSpec(created=range.getOrDefault(CreatedRange()),valid=range.isSuccess) }
 val page=model.paging(spec).collectAsLazyPagingItems()
 val outputs by model.outputs.collectAsState()
 val notes by model.notes.collectAsState()
 val proposals=waitingProposals(model,outputs).size
 val listState=rememberLazyListState()
 val feedback=LocalFeedback.current
 // Only a launch onto the timeline focuses the composer; opening the app on a note and coming back must not.
 val launchedOnNote=(LocalContext.current as? android.app.Activity)?.intent?.getStringExtra("noteId")!=null
 var savedId by remember { mutableStateOf<String?>(null) }
 val slipHidden=remember { mutableStateOf(model.container.preferences.getString("slip-hidden","").orEmpty()) }
 val slip=dailySlip(outputs,notes,model.container.manifest::enabled) { slipHidden.value=="${LocalDate.now()}/${it.id}" }
 val lead=1+(if(proposals>=3) 1 else 0)+(if(slip!=null) 1 else 0)
 // After Save the new entry, with its day header, is scrolled into view, with a brief context.getString(R.string.core_saved).
 LaunchedEffect(savedId) {
  val id=savedId ?: return@LaunchedEffect
  feedback.show(context.getString(R.string.core_saved))
  withTimeoutOrNull(3_000) { snapshotFlow { page.itemSnapshotList.items.indexOfFirst { it.id==id } }.first { it>=0 } }?.let { index ->
   // The first row for an entry is its group's header when it opens one.
   listState.animateScrollToItem(lead+timelineRows(context,page.itemSnapshotList.items.map { it.note() },grouped=true).indexOfFirst { it.index==index }.coerceAtLeast(0))
  }
  savedId=null
 }
 NoteList(model,page,grouped=true,listState,onNote,empty={
  if(from.isNotBlank() || to.isNotBlank()) Text(context.getString(R.string.core_no_date_notes),color=SpuntoColors.ink3)
  else { Text(context.getString(R.string.core_quiet_place),style=MaterialTheme.typography.titleLarge); Text(context.getString(R.string.core_share_hint),color=MaterialTheme.colorScheme.onSurfaceVariant) }
 }) {
  item(key="composer") { Composer(model,autoFocus=!launchedOnNote,onPlainCaptureSave={body,ref ->model.repository.create(body,sourceRef=ref).also { savedId=it }},onSave={model.saveLocal(it)}) }
  if(proposals>=3) item(key="review") { TextButton(onClick=onReview,modifier=Modifier.heightIn(min=48.dp)) { Text(context.resources.getQuantityString(R.plurals.core_suggestions_review,proposals,proposals)) } }
  slip?.let { (output,note) -> item(key="slip") { DailySlip(model,output,note,onNote) { slipHidden.value="${LocalDate.now()}/${output.id}";model.container.preferences.edit().putString("slip-hidden",slipHidden.value).apply() } } }
 }
}

/** The day's slip (03 §5, S5): its date, the note, Archive, and × for context.getString(R.string.core_not_today). */
@Composable private fun DailySlip(model:SpuntoViewModel,output:PluginOutputEntity,note:Note,onNote:(String)->Unit,onHide:()->Unit) {
 val context=LocalContext.current
 val locale=androidx.compose.ui.platform.LocalConfiguration.current.locales[0]
 val feedback=LocalFeedback.current
 Surface(color=MaterialTheme.colorScheme.surfaceVariant,contentColor=MaterialTheme.colorScheme.onSurface,shape=MaterialTheme.shapes.medium) {
  Column(Modifier.fillMaxWidth().clickable(onClickLabel=context.getString(R.string.core_open_note)) { onNote(note.id) }.padding(start=SpuntoSpacing.s16,bottom=SpuntoSpacing.s8)) {
   Row(verticalAlignment=Alignment.CenterVertically) {
    Text(context.getString(R.string.core_from_notes,Instant.ofEpochMilli(note.createdAt).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("d MMMM yyyy",locale))).uppercase(),Modifier.weight(1f).padding(top=SpuntoSpacing.s12),style=SpuntoType.overline,color=SpuntoColors.ink3)
    IconButton(onClick={ onHide();model.request("/api/v1/slip/dismiss","POST",buildJsonObject { put("plugin_id",payload(output.payload).string("plugin"));put("note_id",note.id) }) },modifier=Modifier.semantics { contentDescription=context.getString(R.string.core_not_today) }) { Text("×",color=SpuntoColors.ink3) }
   }
   Box(Modifier.padding(end=SpuntoSpacing.s16)) { Markdown(note.body.take(600)) }
   TextButton(onClick={ model.flags(note.id,archived=true);feedback.show(context.getString(R.string.core_archived),context.getString(R.string.core_undo)) { model.flags(note.id,archived=false) } },modifier=Modifier.heightIn(min=48.dp)) { Text(context.getString(R.string.core_archive)) }
  }
 }
}

/** The paged entry list shared by the timeline and every filter: sticky headers, long-press actions, swipe to archive. */
@Composable private fun NoteList(model:SpuntoViewModel,page:LazyPagingItems<NoteEntity>,grouped:Boolean,listState:LazyListState,onNote:(String)->Unit,restore:Boolean=false,empty:@Composable ColumnScope.()->Unit,lead:LazyListScope.()->Unit={}) {
 val context=LocalContext.current
 var actionsFor by remember { mutableStateOf<String?>(null) }
 // Rows and the notes they point at come from one snapshot, so a shrinking list never leaves a row past its end.
 val snapshot=page.itemSnapshotList.items
 val notes=remember(snapshot) { snapshot.map { it.note() } }
 val rows=remember(notes,grouped) { timelineRows(context,notes,grouped) }
 LazyColumn(Modifier.fillMaxSize(),state=listState,contentPadding=PaddingValues(start=SpuntoSpacing.s20,end=SpuntoSpacing.s20,bottom=SpuntoSpacing.s20),verticalArrangement=Arrangement.spacedBy(SpuntoSpacing.s20)) {
  lead()
  // Paging reports NotLoading before the first page arrives, so wait until the list has been read once.
  if(page.itemCount==0 && page.loadState.refresh is LoadState.NotLoading && page.loadState.source.refresh is LoadState.NotLoading && page.itemSnapshotList.isEmpty() && page.loadState.append.endOfPaginationReached) item(key="empty") { Column(verticalArrangement=Arrangement.spacedBy(SpuntoSpacing.s8),content=empty) }
  rows.forEach { row ->
   val note=notes[row.index]
   if(row.header!=null) stickyHeader(key="h:${note.id}",contentType="header") {
    // Drawn above the entries that scroll under it.
    Text(row.header.uppercase(),Modifier.zIndex(1f).fillMaxWidth().background(MaterialTheme.colorScheme.background).padding(top=SpuntoSpacing.s12,bottom=SpuntoSpacing.s4).semantics { heading();contentDescription=row.header },style=SpuntoType.overline,color=SpuntoColors.ink3)
   } else item(key=note.id,contentType="entry") {
    if(row.index<page.itemCount) page[row.index] // tells the pager how far the reader has got, so the next page loads
    NoteEntry(model,note,onNote,entryTime(context,note.createdAt,row.group),onActions={ actionsFor=note.id },restore=restore)
   }
  }
 }
 actionsFor?.let { id -> page.itemSnapshotList.items.firstOrNull { it.id==id }?.note() }?.let { note ->
  NoteActionsSheet(model,note,onDismiss={ actionsFor=null },onHistory={ model.historyFor.value=note.id;onNote(note.id) },onCommandResult={ model.remote.value=it })
 }
}

@Composable fun NoteEntry(model:SpuntoViewModel,note:Note,onNote:(String)->Unit,time:String?=null,onActions:(()->Unit)?=null,restore:Boolean=false) {
 val context=LocalContext.current
 val locale=androidx.compose.ui.platform.LocalConfiguration.current.locales[0]
 val outputs by model.outputs.collectAsState()
 val meta=entryMetaText(outputs,note.id,model.container.manifest.plugins())
 val nav=LocalNavigator.current
 val feedback=LocalFeedback.current
 fun archive(archived:Boolean) { model.flags(note.id,archived=archived);feedback.show(if(archived) context.getString(R.string.core_archived) else context.getString(R.string.core_unarchived),context.getString(R.string.core_undo)) { model.flags(note.id,archived=!archived) } }
 fun trash() { model.flags(note.id,trashed=true);feedback.show(context.getString(R.string.core_moved_trash),context.getString(R.string.core_undo)) { model.flags(note.id,trashed=false) } }
 val entry=@Composable {
  Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background).combinedClickable(role=Role.Button,onClickLabel=context.getString(R.string.core_open),onLongClickLabel=context.getString(R.string.core_more_actions),onLongClick=onActions,onClick={ onNote(note.id) }).semantics {
   contentDescription=entryDescription(context,note,time ?: noteTime(note.createdAt,locale))
   customActions=listOfNotNull(
    CustomAccessibilityAction(if(note.pinned) context.getString(R.string.core_unpin) else context.getString(R.string.core_pin)) { model.flags(note.id,pinned=!note.pinned);true },
    CustomAccessibilityAction(if(note.archived) context.getString(R.string.core_unarchive) else context.getString(R.string.core_archive)) { archive(!note.archived);true },
    if(note.trashed) CustomAccessibilityAction(context.getString(R.string.core_restore)) { model.flags(note.id,trashed=false);true } else CustomAccessibilityAction(context.getString(R.string.core_move_trash)) { trash();true },
    onActions?.let { CustomAccessibilityAction(context.getString(R.string.core_more_actions)) { it();true } },
   )
  },verticalArrangement=Arrangement.spacedBy(SpuntoSpacing.s8)) {
   // Meta line (03 §5.1): pin glyph, time, sync ring, the one plugin token; sans in ink-3.
   Row(horizontalArrangement=Arrangement.spacedBy(6.dp),verticalAlignment=Alignment.CenterVertically) {
    if(note.pinned) Icon(painterResource(R.drawable.ic_pin),contentDescription=null,modifier=Modifier.size(14.dp),tint=SpuntoColors.ink3)
    Text(listOfNotNull(time ?: noteTime(note.createdAt,locale),meta.takeIf(String::isNotEmpty)).joinToString(" · "),Modifier.weight(1f),style=MaterialTheme.typography.labelSmall,color=SpuntoColors.ink3,maxLines=1,overflow=androidx.compose.ui.text.style.TextOverflow.Ellipsis)
    if(note.pending) Text("◯",style=MaterialTheme.typography.labelSmall,color=SpuntoColors.ink3)
    if(onActions!=null) IconButton(onClick=onActions) {Icon(painterResource(R.drawable.ic_more),contentDescription=context.getString(R.string.core_more_actions),modifier=Modifier.size(20.dp),tint=MaterialTheme.colorScheme.onSurfaceVariant)}
   }
   FoldedBody(note.body) { text -> Markdown(text,note.machine,onLink={ if(it.startsWith("/n/")) onNote(it.substringAfter("/n/")) else if(it.startsWith("/a/")) onNote(note.id) },onLabel={ nav.filter("label:$it") }) { model.toggleTask(note,it) } }
   val provenance=buildList {
    if(note.privateNote) add(context.getString(R.string.core_private))
    if(note.updatedViaAgent && note.sourceKind!="agent") add(context.getString(R.string.core_edited_agent))
    if(note.sourceKind in setOf("agent","plugin")) add((if(note.machine) "✦ " else "")+context.getString(R.string.core_via,note.sourceRef ?: context.getString(if(note.sourceKind=="agent") R.string.core_agent else R.string.core_plugin)))
   }
   if(note.conflictOf!=null || provenance.isNotEmpty()) Row(horizontalArrangement=Arrangement.spacedBy(6.dp),verticalAlignment=Alignment.CenterVertically) {
    if(note.conflictOf!=null) { Text("●",style=MaterialTheme.typography.labelSmall,color=SpuntoColors.warn);Text(context.getString(R.string.core_conflict_compare),style=MaterialTheme.typography.labelSmall,color=SpuntoColors.ink3) }
    if(provenance.isNotEmpty()) Text((if(note.conflictOf!=null) "· " else "")+provenance.joinToString(" · "),style=MaterialTheme.typography.labelSmall,color=SpuntoColors.ink3)
   }
   Suggestions(model,note.id,onNote)
   if(note.machine) Text(context.getString(R.string.core_machine_written,note.sourceRef ?: context.getString(R.string.core_plugin_indefinite)),style=MaterialTheme.typography.bodySmall,color=SpuntoColors.ink3)
   if(restore) TextButton(onClick={ model.flags(note.id,trashed=false);feedback.show(context.getString(R.string.core_restored)) },modifier=Modifier.heightIn(min=48.dp)) { Text(context.getString(R.string.core_restore)) }
   HorizontalDivider(color=MaterialTheme.colorScheme.outlineVariant)
  }
 }
 if(note.archived || note.trashed || onActions==null) { entry();return }
 // Swipe right archives; the visible route is the long-press sheet and the accessibility action.
 val swipe=rememberSwipeToDismissBoxState(confirmValueChange={ if(it==SwipeToDismissBoxValue.StartToEnd) archive(true);false })
 SwipeToDismissBox(swipe,enableDismissFromEndToStart=false,backgroundContent={
  Row(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.primaryContainer).padding(horizontal=SpuntoSpacing.s20),horizontalArrangement=Arrangement.spacedBy(SpuntoSpacing.s8),verticalAlignment=Alignment.CenterVertically) {
   Icon(painterResource(R.drawable.ic_archive),contentDescription=null,tint=MaterialTheme.colorScheme.onPrimaryContainer);Text(context.getString(R.string.core_archive),color=MaterialTheme.colorScheme.onPrimaryContainer)
  }
 }) { entry() }
}

/** 03 §5.1: a long note folds after 8 lines, fading out, and context.getString(R.string.core_show_more) in ink-3 unfolds it in place (the note page never folds). Folded, only the first 1,200 characters are laid out. */
@Composable internal fun FoldedBody(body:String,content:@Composable (String)->Unit) {
 val context=LocalContext.current
 var expanded by rememberSaveable { mutableStateOf(false) };var overflows by remember { mutableStateOf(false) }
 val line=with(LocalDensity.current) { SpuntoType.reading.lineHeight.toPx() };val paper=MaterialTheme.colorScheme.background
 // Clip and fade outside the height cut, so they see the folded size.
 Box(Modifier.clipToBounds().drawWithContent { drawContent();if(overflows && !expanded) drawRect(Brush.verticalGradient(listOf(Color.Transparent,paper),startY=size.height-2*line,endY=size.height)) }.layout { measurable,constraints ->
  val full=measurable.measure(constraints.copy(maxHeight=Constraints.Infinity));val limit=(8*line).roundToInt()
  overflows=body.length>1200 || full.height>limit
  layout(full.width,if(expanded) full.height else minOf(full.height,limit)) { full.place(0,0) }
 }) { content(if(expanded) body else body.take(1200)) }
 if(overflows) TextButton(onClick={ expanded=!expanded },contentPadding=PaddingValues(0.dp),modifier=Modifier.heightIn(min=48.dp)) { Text(if(expanded) context.getString(R.string.core_show_less) else context.getString(R.string.core_show_more),style=MaterialTheme.typography.bodySmall,color=SpuntoColors.ink3) }
}

@Composable fun FilteredTimeline(model:SpuntoViewModel,route:String,from:String="",to:String="",onNote:(String)->Unit) {
 val context=LocalContext.current
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
 val page=model.paging(spec).collectAsLazyPagingItems()
 val trash=route=="trash"
 NoteList(model,page,grouped=!trash,rememberLazyListState(),onNote,restore=trash,empty={
  when {
   from.isNotBlank() || to.isNotBlank() -> Text(context.getString(R.string.core_no_date_notes),color=SpuntoColors.ink3)
   trash -> { Text(context.getString(R.string.core_trash_empty),style=MaterialTheme.typography.titleLarge);Text(context.getString(R.string.core_trash_retention),color=MaterialTheme.colorScheme.onSurfaceVariant) }
   route=="archive" -> { Text(context.getString(R.string.core_archive_empty),style=MaterialTheme.typography.titleLarge);Text(context.getString(R.string.core_archive_hint),color=MaterialTheme.colorScheme.onSurfaceVariant) }
   route.startsWith("label:") -> Text(context.getString(R.string.core_label_empty,route.substringAfter(':')),style=MaterialTheme.typography.titleLarge)
   else -> Text(context.getString(R.string.core_nothing_here),color=SpuntoColors.ink3)
  }
 }) {
  if(trash && page.itemCount>0) item(key="trash-line") { Text(context.getString(R.string.core_trash_retention),Modifier.padding(top=SpuntoSpacing.s12),style=MaterialTheme.typography.bodySmall,color=SpuntoColors.ink3) }
 }
}
