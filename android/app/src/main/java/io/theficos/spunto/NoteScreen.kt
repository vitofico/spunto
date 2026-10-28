@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package io.theficos.spunto

import android.content.*
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import java.time.*
import java.time.format.DateTimeFormatter
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.activity.compose.BackHandler
import io.theficos.spunto.core.design.*
import io.theficos.spunto.core.model.*
import io.theficos.spunto.core.plugins.*
import io.theficos.spunto.data.sync.*
import io.theficos.spunto.data.local.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun NoteScreen(model:SpuntoViewModel,id:String,onClose:()->Unit,onNote:(String)->Unit,onBackReady:((()->Unit)?)->Unit={}) {
 val context=LocalContext.current
 val locale=androidx.compose.ui.platform.LocalConfiguration.current.locales[0]
 val manifest=model.manifest.collectAsState().value
 // Loading and missing differ: a note still being read shows nothing rather than "unavailable".
 val loaded by remember(id) { model.repository.observe(id).map { it to true } }.collectAsState(null to false)
 val note=loaded.first
 var editing by remember(id) { mutableStateOf(false) }
 var caret by remember(id) { mutableStateOf<Int?>(null) }
 var actions by remember(id) { mutableStateOf(false) }
 var history by remember(id) { mutableStateOf(model.historyFor.value==id) }
 var about by remember(id) { mutableStateOf(false) }
 var commandResult by remember(id) { mutableStateOf<JsonObject?>(null) }
 val editorScope=rememberCoroutineScope()
 var finishing by remember(id) {mutableStateOf(false)}
 var restoring by remember(id) {mutableStateOf(false)}
 var finishEditor by remember(id) {mutableStateOf<(suspend ()->Unit)?>(null)}
 val revisions by model.repository.history(id).collectAsState(emptyList())
 var historyResult by remember(id) { mutableStateOf<HistoryRefreshResult?>(null) }
 var recordingTarget by remember(id) {mutableStateOf<String?>(null)}
 val nav=LocalNavigator.current
 val doneInTopBar=LocalTopBar.current!=null
 LaunchedEffect(id) { if(model.historyFor.value==id) model.historyFor.value=null }
 val value=note?.takeIf {it.id==id} ?: run { if(loaded.second) Text(context.getString(R.string.core_note_missing),Modifier.padding(SpuntoSpacing.s20),color=MaterialTheme.colorScheme.onSurfaceVariant);return }
 val online by model.online.collectAsState()
 val refused by model.repository.refused.collectAsState(emptyMap())
 fun finishEditing() {
  if(finishing || restoring) return
  val finish=finishEditor ?: return
  finishing=true
  editorScope.launch {
   try {finish();editing=false}
   catch(error:kotlinx.coroutines.CancellationException) {throw error}
   catch(error:Exception) {model.message.value=if(error is LocalOperationException) localOperationMessage(context.resources,error) else if(error is IllegalArgumentException || error is IllegalStateException) error.message?.takeIf(String::isNotBlank) ?: context.getString(R.string.core_save_failed) else context.getString(R.string.core_save_failed)}
   finally {finishing=false}
  }
 }
 DisposableEffect(id,editing,finishEditor,finishing,restoring) {
  onBackReady(if(editing) ({finishEditing()}) else null)
  onDispose {onBackReady(null)}
 }
 BackHandler(editing) {finishEditing()}
 TopBarActions {
  IconToggleButton(checked=value.pinned,onCheckedChange={model.flags(id,pinned=it)},enabled=!restoring,colors=IconButtonDefaults.iconToggleButtonColors(checkedContentColor=MaterialTheme.colorScheme.onSurface)) {Icon(painterResource(if(value.pinned) R.drawable.ic_pin_filled else R.drawable.ic_pin),contentDescription=context.getString(R.string.core_pin_note),modifier=Modifier.size(24.dp))}
  if(editing && doneInTopBar) NoteDoneAction(finishing,!finishing && !restoring && finishEditor!=null,::finishEditing)
  else if(!editing) IconButton(onClick={actions=true},enabled=!restoring) {Icon(painterResource(R.drawable.ic_more),contentDescription=context.getString(R.string.core_note_actions),modifier=Modifier.size(24.dp))}
 }
 LaunchedEffect(history,online,id,value.serverRev) { if(history && online) historyResult=model.refreshHistory(id) }
 LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(SpuntoSpacing.s20),verticalArrangement=Arrangement.spacedBy(SpuntoSpacing.s16)) {
  item {
   val meta=listOfNotNull(noteDay(value.createdAt,locale),if(value.privateNote) context.getString(R.string.core_private) else null,if(value.archived) context.getString(R.string.core_archived) else null,if(value.trashed) context.getString(R.string.core_in_trash) else null).joinToString(" · ")
   Text(meta.uppercase(),style=SpuntoType.overline,color=SpuntoColors.ink3,modifier=Modifier.semantics {contentDescription=meta})
  }
  if(value.conflictOf!=null && !value.trashed) item {ConflictCompare(model,value,onNote)}
  refused[id]?.let {code -> item {RefusedChangeNotice(model,id,code)}}
  item {
   if(editing) Composer(model,id,value.body,caret=caret,onFinishReady={finishEditor=it},onRecordingSaved={editing=false},showSaveAction=false,
    trailing=if(doneInTopBar) null else ({NoteDoneAction(finishing,!finishing && !restoring && finishEditor!=null,::finishEditing)}),onSave={editing=false})
   // Tapping the text edits it in place with the caret where the finger landed (A5); links, labels and checkboxes keep their own taps.
   // Each touch first forgets the last place, so a tap beside the text opens the editor at the end.
   else Box(Modifier.fillMaxWidth().pointerInput(Unit) {awaitEachGesture {awaitFirstDown(requireUnconsumed=false,pass=PointerEventPass.Initial);caret=null}}.clickable(enabled=!value.trashed && !restoring,onClickLabel=context.getString(R.string.core_edit_note),interactionSource=remember {MutableInteractionSource()},indication=null) {editing=true}) {
    Markdown(value.body,value.machine,onLink={if(it.startsWith("/n/")) onNote(it.substringAfter("/n/")) else if(recordingAttachmentLinks(value.body).any {link ->link.second==it}) recordingTarget=it},onLabel={nav.filter("label:$it")},onText={caret=it}) {model.toggleTask(value,it)}
   }
  }
  if(!editing) item {Suggestions(model,id,onNote)}
  item {NoteAttachments(model,id,value.body)}
  if(!editing) item {TextButton(onClick={about=true},contentPadding=PaddingValues(0.dp),modifier=Modifier.heightIn(min=48.dp)) {Text(context.getString(R.string.core_about_note),style=MaterialTheme.typography.bodySmall,color=SpuntoColors.ink3)}}
 }
 if(actions) NoteActionsSheet(model,value,onDismiss={actions=false},onHistory={history=true},onCommandResult={commandResult=it},onTrashed=onClose)
 if(history) ModalBottomSheet(onDismissRequest={history=false},sheetState=rememberModalBottomSheetState(skipPartiallyExpanded=true)) {
  LazyColumn(Modifier.fillMaxWidth().navigationBarsPadding(),contentPadding=PaddingValues(start=SpuntoSpacing.s20,end=SpuntoSpacing.s20,bottom=SpuntoSpacing.s24),verticalArrangement=Arrangement.spacedBy(SpuntoSpacing.s4)) {
   revisionHistoryItems(context,revisions,online,historyResult,editing || finishing || restoring,value.body) {revision ->
    if(!editing && !finishing && !restoring) {restoring=true;editorScope.launch {
     try {model.repository.restoreHistory(id,revision);history=false}
     catch(error:kotlinx.coroutines.CancellationException) {throw error}
     catch(error:Exception) {model.message.value=context.getString(R.string.core_restore_failed)}
     finally {restoring=false}
    }}
   }
  }
 }
 if(about) ModalBottomSheet(onDismissRequest={about=false},scrimColor=MaterialTheme.colorScheme.scrim) {Column(Modifier.padding(SpuntoSpacing.s20).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(SpuntoSpacing.s12)) {Text(context.getString(R.string.core_about_note),style=MaterialTheme.typography.titleLarge);Text(context.getString(R.string.core_created_updated,noteTime(value.createdAt,locale),noteTime(value.updatedAt,locale)));value.sourceUrl?.let {Text(it)};PluginPanels(model,value,onNote);Spacer(Modifier.height(SpuntoSpacing.s32))}}
 recordingTarget?.takeIf {target ->recordingAttachmentLinks(value.body).any {it.second==target}}?.let {target ->
  val attachments by model.repository.dao.attachments(id).collectAsState(emptyList())
  val label=recordingAttachmentLinks(value.body).first {it.second==target}.first
  ModalBottomSheet(onDismissRequest={recordingTarget=null}) {Column(Modifier.padding(SpuntoSpacing.s20)) {AttachmentPlayer(model,target.substringAfterLast('/'),label,attachments.firstOrNull {it.kind=="recording" && it.remoteId==target.substringAfterLast('/')});Spacer(Modifier.height(SpuntoSpacing.s32))}}
 }
 commandResult?.takeIf {online && model.container.manifest.enabled(it.string("plugin_id"))}?.let {result ->CommandResultSheet(model,result,onNote,{commandResult=it},{commandResult=null})}
}

/** The complete action label remains accessible when a long saving state needs an ellipsis in a narrow app bar. */
@Composable internal fun NoteDoneAction(saving:Boolean,enabled:Boolean,onDone:()->Unit) {
 val context=LocalContext.current
 Button(onClick=onDone,enabled=enabled,modifier=Modifier.padding(end=SpuntoSpacing.s8).widthIn(max=160.dp).heightIn(min=48.dp),
  contentPadding=PaddingValues(horizontal=SpuntoSpacing.s16,vertical=SpuntoSpacing.s12)) {
  Text(context.getString(if(saving) R.string.core_saving else R.string.core_done),maxLines=1,overflow=TextOverflow.Ellipsis)
 }
}

/** 03 R9: four core tiles, then plugin verbs between hairlines, then Private, History, export and trash. Shared by the note's ⋮ and a timeline long-press. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun NoteActionsSheet(model:SpuntoViewModel,note:Note,onDismiss:()->Unit,onHistory:()->Unit,onCommandResult:(JsonObject)->Unit,onTrashed:()->Unit={}) {
 val online by model.online.collectAsState()
 val feedback=LocalFeedback.current
 val context=LocalContext.current
 ModalBottomSheet(onDismissRequest=onDismiss,sheetState=rememberModalBottomSheetState(skipPartiallyExpanded=true)) {
  Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(bottom=SpuntoSpacing.s16)) {
   NoteExportActions(model,note,enabled=true) {share,save,available ->
    Row(Modifier.fillMaxWidth().padding(horizontal=SpuntoSpacing.s12,vertical=SpuntoSpacing.s4),horizontalArrangement=Arrangement.SpaceEvenly) {
     ActionTile(if(note.pinned) R.drawable.ic_pin_filled else R.drawable.ic_pin,if(note.pinned) context.getString(R.string.core_unpin) else context.getString(R.string.core_pin)) {model.flags(note.id,pinned=!note.pinned);onDismiss()}
     ActionTile(R.drawable.ic_copy,context.getString(R.string.core_copy)) {
      val clip=ClipData.newPlainText(note.title,note.body)
      if(note.privateNote) clip.description.extras=android.os.PersistableBundle().apply {putBoolean(ClipDescription.EXTRA_IS_SENSITIVE,true)}
      context.getSystemService(ClipboardManager::class.java).setPrimaryClip(clip);feedback.show(context.getString(R.string.core_copied));onDismiss()
     }
     ActionTile(R.drawable.ic_share,context.getString(R.string.core_share),enabled=available,onClick=share)
     ActionTile(R.drawable.ic_archive,if(note.archived) context.getString(R.string.core_unarchive) else context.getString(R.string.core_archive)) {
      val archived=!note.archived;model.flags(note.id,archived=archived);onDismiss()
      feedback.show(if(archived) context.getString(R.string.core_archived) else context.getString(R.string.core_unarchived),context.getString(R.string.core_undo)) {model.flags(note.id,archived=!archived)}
     }
    }
    if(online && !note.trashed) NoteCommands(model,note.id,privateNote=note.privateNote) {onDismiss();onCommandResult(it)}
    HorizontalDivider(Modifier.padding(vertical=SpuntoSpacing.s4),color=MaterialTheme.colorScheme.outlineVariant)
    Row(Modifier.fillMaxWidth().heightIn(min=56.dp).toggleable(note.privateNote,role=Role.Switch) {model.flags(note.id,privateNote=it)}.padding(horizontal=SpuntoSpacing.s20),horizontalArrangement=Arrangement.spacedBy(SpuntoSpacing.s12),verticalAlignment=Alignment.CenterVertically) {
     Column(Modifier.weight(1f)) {Text(context.getString(R.string.core_private),style=MaterialTheme.typography.bodyLarge);Text(context.getString(R.string.core_private_hint),style=MaterialTheme.typography.bodySmall,color=SpuntoColors.ink3)}
     Switch(checked=note.privateNote,onCheckedChange=null)
    }
    NoteSheetRow(context.getString(R.string.core_history)) {onDismiss();onHistory()}
    NoteSheetRow(context.getString(R.string.core_save_markdown),enabled=available,onClick=save)
    NoteSheetRow(if(note.trashed) context.getString(R.string.core_restore_trash) else context.getString(R.string.core_move_trash),destructive=!note.trashed) {
     val trashed=!note.trashed;model.flags(note.id,trashed=trashed);onDismiss()
     if(trashed) {feedback.show(context.getString(R.string.core_moved_trash),context.getString(R.string.core_undo)) {model.flags(note.id,trashed=false)};onTrashed()} else feedback.show(context.getString(R.string.core_restored))
    }
   }
  }
 }
}
@Composable private fun ActionTile(icon:Int,label:String,enabled:Boolean=true,onClick:()->Unit) {
 Column(Modifier.widthIn(min=72.dp).clickable(enabled=enabled,role=Role.Button,onClick=onClick).padding(vertical=SpuntoSpacing.s8).alpha(if(enabled) 1f else .38f),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(6.dp)) {
  Icon(painterResource(icon),contentDescription=null,modifier=Modifier.size(24.dp),tint=MaterialTheme.colorScheme.onSurface)
  Text(label,style=MaterialTheme.typography.labelMedium)
 }
}
/** One sheet row in Inter, 56 dp tall, text on the same 20 dp edge as the tiles and switch above it. */
@Composable internal fun NoteSheetRow(label:String,trailing:String?=null,enabled:Boolean=true,destructive:Boolean=false,onClick:()->Unit) {
 Row(Modifier.fillMaxWidth().heightIn(min=56.dp).clickable(enabled=enabled,role=Role.Button,onClick=onClick).padding(horizontal=SpuntoSpacing.s20).alpha(if(enabled) 1f else .38f),horizontalArrangement=Arrangement.spacedBy(SpuntoSpacing.s12),verticalAlignment=Alignment.CenterVertically) {
  Text(label,Modifier.weight(1f),style=MaterialTheme.typography.bodyLarge,color=if(destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
  trailing?.let {Text(it,style=MaterialTheme.typography.bodySmall,color=SpuntoColors.ink3)}
 }
}

/** "Edited on two devices": the original stacked over this copy, lines found in only one of them highlighted, and three ways out. */
@OptIn(ExperimentalLayoutApi::class)
@Composable private fun ConflictCompare(model:SpuntoViewModel,copy:Note,onNote:(String)->Unit) {
 val context=LocalContext.current
 val originalId=copy.conflictOf ?: return
 val original by remember(originalId) {model.repository.observe(originalId)}.collectAsState(null)
 val kept by model.conflicts.collectAsState()
 var comparing by remember(copy.id) {mutableStateOf(kept.any {it.id==copy.id})}
 val feedback=LocalFeedback.current;val nav=LocalNavigator.current
 fun settle(message:String,keepCopy:Boolean) = model.task {
  if(keepCopy) model.repository.keepConflictCopy(copy.id) else model.repository.setFlags(copy.id,trashed=true)
  nav.back();nav.open("note:$originalId")
  feedback.show(message,context.getString(R.string.core_undo)) {model.flags(copy.id,trashed=false)}
 }
 Surface(color=MaterialTheme.colorScheme.surfaceVariant,contentColor=MaterialTheme.colorScheme.onSurface,shape=MaterialTheme.shapes.medium) {
  Column(Modifier.fillMaxWidth().padding(SpuntoSpacing.s16),verticalArrangement=Arrangement.spacedBy(SpuntoSpacing.s12)) {
   Row(horizontalArrangement=Arrangement.spacedBy(SpuntoSpacing.s8),verticalAlignment=Alignment.CenterVertically) {
    Text("●",color=SpuntoColors.warn,style=MaterialTheme.typography.bodySmall)
    Text(context.getString(R.string.core_two_devices),Modifier.weight(1f),style=MaterialTheme.typography.bodyMedium)
    TextButton(onClick={comparing=!comparing},modifier=Modifier.heightIn(min=48.dp)) {Text(if(comparing) context.getString(R.string.core_hide) else context.getString(R.string.core_compare))}
   }
   if(comparing) {
    val saved=original
    if(saved==null) Text(context.getString(R.string.core_original_missing),style=MaterialTheme.typography.bodySmall,color=SpuntoColors.ink3)
    else {
     ConflictVersion(context.getString(R.string.core_original),saved.body,copy.body,R.string.core_only_original)
     ConflictVersion(context.getString(R.string.core_this_copy),copy.body,saved.body,R.string.core_only_copy)
     FlowRow(horizontalArrangement=Arrangement.spacedBy(SpuntoSpacing.s8),verticalArrangement=Arrangement.spacedBy(SpuntoSpacing.s8)) {
      Button(onClick={settle(context.getString(R.string.core_kept_copy),true)},modifier=Modifier.heightIn(min=48.dp)) {Text(context.getString(R.string.core_keep_copy))}
      OutlinedButton(onClick={settle(context.getString(R.string.core_copy_trashed),false)},modifier=Modifier.heightIn(min=48.dp)) {Text(context.getString(R.string.core_keep_original))}
      TextButton(onClick={model.keepBothConflict(copy.id);comparing=false;feedback.show(context.getString(R.string.core_both_stay),context.getString(R.string.core_open_original)) {nav.open("note:$originalId")}},modifier=Modifier.heightIn(min=48.dp)) {Text(context.getString(R.string.core_keep_both))}
     }
    }
   }
  }
 }
}
@Composable private fun ConflictVersion(label:String,body:String,other:String,description:Int) {
 val context=LocalContext.current
 val otherLines=remember(other) {other.lines().map(String::trim).toSet()}
 Column(verticalArrangement=Arrangement.spacedBy(SpuntoSpacing.s2)) {
  Text(label.uppercase(),style=SpuntoType.overline,color=SpuntoColors.ink3,modifier=Modifier.semantics {heading()})
  body.lines().forEach {line ->
   val only=line.isNotBlank() && line.trim() !in otherLines
   Text(line.ifEmpty {" "},Modifier.fillMaxWidth().then(if(only) Modifier.background(SpuntoColors.highlight).semantics {contentDescription=context.getString(description,line)} else Modifier),style=SpuntoType.readingSmall,color=MaterialTheme.colorScheme.onSurface)
  }
 }
}

internal fun noteDay(at:Long,locale:java.util.Locale=java.util.Locale.getDefault())=Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("EEEE d MMMM · HH:mm",locale))
/** "just now", "5 min ago", "3 h ago", then the date. */
internal fun relativeTime(context:Context,at:Long,now:Long=System.currentTimeMillis()):String {
 val minutes=(now-at)/60_000
 return when {minutes<1 -> context.getString(R.string.core_just_now);minutes<60 -> context.resources.getQuantityString(R.plurals.core_minutes_ago,minutes.toInt(),minutes);minutes<24*60 -> context.resources.getQuantityString(R.plurals.core_hours_ago,(minutes/60).toInt(),minutes/60);else -> noteTime(at,context.resources.configuration.locales[0])}
}
internal fun changeKindName(context:Context,kind:String)=when(kind) {"create" -> context.getString(R.string.core_created);"edit" -> context.getString(R.string.core_edited);"append" -> context.getString(R.string.core_added_to);"merge" -> context.getString(R.string.core_merged);"restore" -> context.getString(R.string.core_restored);"accept" -> context.getString(R.string.core_suggestion_accepted);else -> kind.replace('_',' ').replaceFirstChar(Char::uppercase)}
/** One line per revision; tap to read it. Server versions keep their number; versions only on this phone have none yet, so numbers never shift after a sync. */
internal fun LazyListScope.revisionHistoryItems(context:Context,revisions:List<RevisionEntity>,online:Boolean,result:HistoryRefreshResult?,editing:Boolean,current:String?=null,onRestore:(RevisionEntity)->Unit) {
 item {
  Text(context.getString(R.string.core_history),style=MaterialTheme.typography.titleLarge,modifier=Modifier.padding(bottom=SpuntoSpacing.s8).semantics {heading()})
  if(!online || result is HistoryRefreshResult.Cached) Text(context.getString(R.string.core_history_local),style=MaterialTheme.typography.bodySmall,color=SpuntoColors.ink3)
  if(editing) Text(context.getString(R.string.core_history_finish),style=MaterialTheme.typography.bodySmall,color=SpuntoColors.ink3)
 }
 if(revisions.isEmpty()) item { Text(context.getString(R.string.core_no_revisions),color=SpuntoColors.ink3) }
 items(revisions,key={it.id}) { rev ->
  var open by remember(rev.id) {mutableStateOf(false)}
  val isCurrent=current!=null && rev.body==current
  val name=if(rev.historySource=="server" && rev.version>0) context.getString(R.string.core_version,rev.version) else context.getString(R.string.core_on_phone)
  val summary=listOf(name,relativeTime(context,rev.at),if(isCurrent) context.getString(R.string.core_current) else changeKindName(context,rev.changeKind)).joinToString(" · ")
  Column {
   Row(Modifier.fillMaxWidth().heightIn(min=48.dp).clickable(onClickLabel=if(open) context.getString(R.string.core_hide_version) else context.getString(R.string.core_show_version)) {open=!open},verticalAlignment=Alignment.CenterVertically) {
    Column(Modifier.weight(1f)) {
     Text(summary,style=MaterialTheme.typography.bodyMedium)
     if(!open) Text(titleOf(rev.body).ifBlank {context.getString(R.string.core_empty_note)},style=MaterialTheme.typography.bodySmall,color=SpuntoColors.ink3,maxLines=1,overflow=TextOverflow.Ellipsis)
    }
   }
   if(open) {
    Markdown(rev.body)
    if(!isCurrent) TextButton(onClick={onRestore(rev)},enabled=!editing,modifier=Modifier.heightIn(min=48.dp)) { Text(context.getString(R.string.core_restore_version)) }
   }
   HorizontalDivider(color=MaterialTheme.colorScheme.outlineVariant)
  }
 }
}

@Composable fun NoteAttachments(model:SpuntoViewModel,id:String,body:String="") {
 val context=LocalContext.current
 val attachments by model.repository.dao.attachments(id).collectAsState(emptyList())
 val selected by model.auth.collectAsState()
 val auth=selected?.takeIf {!it.hosted || it.replicaId==model.container.replicaId}
 val links=remember(body) {recordingAttachmentLinks(body).distinctBy {it.second}}
 val linked=links.associate {it.second.substringAfterLast('/') to it.first}
 val localRecordings=attachments.filter {it.kind=="recording" && (it.marker.isNotEmpty() && it.marker in body || it.remoteId in linked)}
 Column(verticalArrangement=Arrangement.spacedBy(SpuntoSpacing.s12)) {
  remember(body,attachments) {noteImages(body,attachments)}.forEach { image ->
   val local=image.local?.let {java.io.File(it.localPath)}?.takeIf {it.isFile}
   val url=local ?: image.target?.let {target ->auth?.let {it.serverUrl+target}}
   if(url!=null) coil.compose.AsyncImage(model=coil.request.ImageRequest.Builder(model.container.context).data(url).memoryCacheKey(model.container.replicaId+":"+url.toString()).diskCacheKey(model.container.replicaId+":"+url.toString()).apply {if(url is String) auth?.let {addHeader("Authorization","Bearer ${it.token}")}}.build(),contentDescription=context.getString(R.string.core_note_image),modifier=Modifier.fillMaxWidth().heightIn(max=360.dp))
  }
  localRecordings.forEach {item ->AttachmentPlayer(model,item.remoteId?:item.id,linked[item.remoteId]?:context.getString(R.string.core_recording_time,recordingTime(item.durationMs)),item)}
  links.filter {(_,target) ->localRecordings.none {it.remoteId==target.substringAfterLast('/')}}.forEach {(label,target) ->AttachmentPlayer(model,target.substringAfterLast('/'),label)}
 }
}
