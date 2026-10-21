@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package io.theficos.spunto

import android.content.*
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.draw.alpha
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
 val manifest=model.manifest.collectAsState().value
 // Loading and missing differ: a note still being read shows nothing rather than "unavailable".
 val loaded by remember(id) { model.repository.observe(id).map { it to true } }.collectAsState(null to false)
 val note=loaded.first
 var editing by remember(id) { mutableStateOf(false) }
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
 LaunchedEffect(id) { if(model.historyFor.value==id) model.historyFor.value=null }
 val value=note?.takeIf {it.id==id} ?: run { if(loaded.second) Text("This note isn't on this phone.",Modifier.padding(20.dp),color=MaterialTheme.colorScheme.onSurfaceVariant);return }
 val online by model.online.collectAsState()
 fun finishEditing() {
  if(finishing || restoring) return
  val finish=finishEditor ?: return
  finishing=true
  editorScope.launch {
   try {finish();editing=false}
   catch(error:kotlinx.coroutines.CancellationException) {throw error}
   catch(error:Exception) {model.message.value=if(error is IllegalArgumentException || error is IllegalStateException) error.message?.takeIf(String::isNotBlank) ?: "Couldn't save on this phone. Your draft is still here." else "Couldn't save on this phone. Your draft is still here."}
   finally {finishing=false}
  }
 }
 DisposableEffect(id,editing,finishEditor,finishing,restoring) {
  onBackReady(if(editing) ({finishEditing()}) else null)
  onDispose {onBackReady(null)}
 }
 BackHandler(editing) {finishEditing()}
 TopBarActions {
  IconToggleButton(checked=value.pinned,onCheckedChange={model.flags(id,pinned=it)},enabled=!restoring,colors=IconButtonDefaults.iconToggleButtonColors(checkedContentColor=MaterialTheme.colorScheme.onSurface)) {Icon(painterResource(if(value.pinned) R.drawable.ic_pin_filled else R.drawable.ic_pin),contentDescription="Pin note",modifier=Modifier.size(24.dp))}
  if(!editing) IconButton(onClick={actions=true},enabled=!restoring) {Icon(painterResource(R.drawable.ic_more),contentDescription="More note actions",modifier=Modifier.size(24.dp))}
 }
 LaunchedEffect(history,online,id,value.serverRev) { if(history && online) historyResult=model.refreshHistory(id) }
 LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(20.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
  item {
   val meta=listOfNotNull(noteDay(value.createdAt),if(value.privateNote) "Private" else null,if(value.archived) "Archived" else null,if(value.trashed) "In trash" else null).joinToString(" · ")
   Text(meta.uppercase(),style=SpuntoType.overline,color=SpuntoColors.ink3,modifier=Modifier.semantics {contentDescription=meta})
  }
  if(value.conflictOf!=null && !value.trashed) item {ConflictCompare(model,value,onNote)}
  item {
   if(editing) Composer(model,id,value.body,onFinishReady={finishEditor=it},onRecordingSaved={editing=false},showSaveAction=false,trailing={
    Button(enabled=!finishing && !restoring && finishEditor!=null,onClick={finishEditing()},modifier=Modifier.heightIn(min=48.dp)) {Text(if(finishing) "Saving…" else "Done")}
   },onSave={editing=false})
   // Tapping the text edits it in place; links, labels and checkboxes keep their own taps.
   else Box(Modifier.fillMaxWidth().clickable(enabled=!value.trashed && !restoring,onClickLabel="Edit note",interactionSource=remember {MutableInteractionSource()},indication=null) {editing=true}) {
    Markdown(value.body,value.machine,onLink={if(it.startsWith("/n/")) onNote(it.substringAfter("/n/")) else if(recordingAttachmentLinks(value.body).any {link ->link.second==it}) recordingTarget=it},onLabel={nav.filter("label:$it")}) {model.toggleTask(value,it)}
   }
  }
  if(!editing) item {Suggestions(model,id,onNote)}
  item {NoteAttachments(model,id,value.body)}
  if(!editing) item {TextButton(onClick={about=true},contentPadding=PaddingValues(0.dp),modifier=Modifier.heightIn(min=48.dp)) {Text("About this note",style=MaterialTheme.typography.bodySmall,color=SpuntoColors.ink3)}}
 }
 if(actions) NoteActionsSheet(model,value,onDismiss={actions=false},onHistory={history=true},onCommandResult={commandResult=it},onTrashed=onClose)
 if(history) ModalBottomSheet(onDismissRequest={history=false},sheetState=rememberModalBottomSheetState(skipPartiallyExpanded=true)) {
  LazyColumn(Modifier.fillMaxWidth().navigationBarsPadding(),contentPadding=PaddingValues(start=20.dp,end=20.dp,bottom=24.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
   revisionHistoryItems(revisions,online,historyResult,editing || finishing || restoring,value.body) {revision ->
    if(!editing && !finishing && !restoring) {restoring=true;editorScope.launch {
     try {model.repository.restoreHistory(id,revision);history=false}
     catch(error:kotlinx.coroutines.CancellationException) {throw error}
     catch(error:Exception) {model.message.value="Couldn't restore this version. Your note and draft stay on this phone."}
     finally {restoring=false}
    }}
   }
  }
 }
 if(about) ModalBottomSheet(onDismissRequest={about=false},scrimColor=MaterialTheme.colorScheme.scrim) {Column(Modifier.padding(20.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)) {Text("About this note",style=MaterialTheme.typography.titleLarge);Text("Created ${noteTime(value.createdAt)}\nUpdated ${noteTime(value.updatedAt)}");value.sourceUrl?.let {Text(it)};PluginPanels(model,value,onNote);Spacer(Modifier.height(32.dp))}}
 recordingTarget?.takeIf {target ->recordingAttachmentLinks(value.body).any {it.second==target}}?.let {target ->
  val attachments by model.repository.dao.attachments(id).collectAsState(emptyList())
  val label=recordingAttachmentLinks(value.body).first {it.second==target}.first
  ModalBottomSheet(onDismissRequest={recordingTarget=null}) {Column(Modifier.padding(20.dp)) {AttachmentPlayer(model,target.substringAfterLast('/'),label,attachments.firstOrNull {it.kind=="recording" && it.remoteId==target.substringAfterLast('/')});Spacer(Modifier.height(32.dp))}}
 }
 commandResult?.takeIf {online && model.container.manifest.enabled(it.string("plugin_id"))}?.let {result ->CommandResultSheet(model,result,onNote,{commandResult=it},{commandResult=null})}
}

/** 03 R9: four core tiles, then plugin verbs between hairlines, then Private, History, export and trash. Shared by the note's ⋮ and a timeline long-press. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun NoteActionsSheet(model:SpuntoViewModel,note:Note,onDismiss:()->Unit,onHistory:()->Unit,onCommandResult:(JsonObject)->Unit,onTrashed:()->Unit={}) {
 val online by model.online.collectAsState()
 val feedback=LocalFeedback.current
 val context=LocalContext.current
 ModalBottomSheet(onDismissRequest=onDismiss,sheetState=rememberModalBottomSheetState(skipPartiallyExpanded=true)) {
  Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(bottom=16.dp)) {
   NoteExportActions(model,note,enabled=true) {share,save,available ->
    Row(Modifier.fillMaxWidth().padding(horizontal=12.dp,vertical=4.dp),horizontalArrangement=Arrangement.SpaceEvenly) {
     ActionTile(if(note.pinned) R.drawable.ic_pin_filled else R.drawable.ic_pin,if(note.pinned) "Unpin" else "Pin") {model.flags(note.id,pinned=!note.pinned);onDismiss()}
     ActionTile(R.drawable.ic_copy,"Copy") {
      val clip=ClipData.newPlainText(note.title,note.body)
      if(note.privateNote) clip.description.extras=android.os.PersistableBundle().apply {putBoolean(ClipDescription.EXTRA_IS_SENSITIVE,true)}
      context.getSystemService(ClipboardManager::class.java).setPrimaryClip(clip);feedback.show("Copied");onDismiss()
     }
     ActionTile(R.drawable.ic_share,"Share",enabled=available,onClick=share)
     ActionTile(R.drawable.ic_archive,if(note.archived) "Unarchive" else "Archive") {
      val archived=!note.archived;model.flags(note.id,archived=archived);onDismiss()
      feedback.show(if(archived) "Archived" else "Unarchived","Undo") {model.flags(note.id,archived=!archived)}
     }
    }
    if(online && !note.trashed) NoteCommands(model,note.id,privateNote=note.privateNote) {onDismiss();onCommandResult(it)}
    HorizontalDivider(Modifier.padding(vertical=4.dp),color=MaterialTheme.colorScheme.outlineVariant)
    Row(Modifier.fillMaxWidth().heightIn(min=56.dp).toggleable(note.privateNote,role=Role.Switch) {model.flags(note.id,privateNote=it)}.padding(horizontal=20.dp),horizontalArrangement=Arrangement.spacedBy(12.dp),verticalAlignment=Alignment.CenterVertically) {
     Column(Modifier.weight(1f)) {Text("Private",style=MaterialTheme.typography.bodyLarge);Text("Kept out of AI and network plugins",style=MaterialTheme.typography.bodySmall,color=SpuntoColors.ink3)}
     Switch(checked=note.privateNote,onCheckedChange=null)
    }
    NoteSheetRow("History") {onDismiss();onHistory()}
    NoteSheetRow("Save as Markdown",enabled=available,onClick=save)
    NoteSheetRow(if(note.trashed) "Restore from trash" else "Move to trash",destructive=!note.trashed) {
     val trashed=!note.trashed;model.flags(note.id,trashed=trashed);onDismiss()
     if(trashed) {feedback.show("Moved to trash","Undo") {model.flags(note.id,trashed=false)};onTrashed()} else feedback.show("Restored")
    }
   }
  }
 }
}
@Composable private fun ActionTile(icon:Int,label:String,enabled:Boolean=true,onClick:()->Unit) {
 Column(Modifier.widthIn(min=72.dp).clickable(enabled=enabled,role=Role.Button,onClick=onClick).padding(vertical=8.dp).alpha(if(enabled) 1f else .38f),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(6.dp)) {
  Icon(painterResource(icon),contentDescription=null,modifier=Modifier.size(24.dp),tint=MaterialTheme.colorScheme.onSurface)
  Text(label,style=MaterialTheme.typography.labelMedium)
 }
}
/** One sheet row in Inter, 56 dp tall, text on the same 20 dp edge as the tiles and switch above it. */
@Composable internal fun NoteSheetRow(label:String,trailing:String?=null,enabled:Boolean=true,destructive:Boolean=false,onClick:()->Unit) {
 Row(Modifier.fillMaxWidth().heightIn(min=56.dp).clickable(enabled=enabled,role=Role.Button,onClick=onClick).padding(horizontal=20.dp).alpha(if(enabled) 1f else .38f),horizontalArrangement=Arrangement.spacedBy(12.dp),verticalAlignment=Alignment.CenterVertically) {
  Text(label,Modifier.weight(1f),style=MaterialTheme.typography.bodyLarge,color=if(destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
  trailing?.let {Text(it,style=MaterialTheme.typography.bodySmall,color=SpuntoColors.ink3)}
 }
}

/** "Edited on two devices": the original stacked over this copy, lines found in only one of them highlighted, and three ways out. */
@OptIn(ExperimentalLayoutApi::class)
@Composable private fun ConflictCompare(model:SpuntoViewModel,copy:Note,onNote:(String)->Unit) {
 val originalId=copy.conflictOf ?: return
 val original by remember(originalId) {model.repository.observe(originalId)}.collectAsState(null)
 val kept by model.conflicts.collectAsState()
 var comparing by remember(copy.id) {mutableStateOf(kept.any {it.id==copy.id})}
 val feedback=LocalFeedback.current;val nav=LocalNavigator.current
 fun settle(message:String,keepCopy:Boolean) = model.task {
  if(keepCopy) model.repository.keepConflictCopy(copy.id) else model.repository.setFlags(copy.id,trashed=true)
  nav.back();nav.open("note:$originalId")
  feedback.show(message,"Undo") {model.flags(copy.id,trashed=false)}
 }
 Surface(color=MaterialTheme.colorScheme.surfaceVariant,contentColor=MaterialTheme.colorScheme.onSurface,shape=MaterialTheme.shapes.medium) {
  Column(Modifier.fillMaxWidth().padding(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
   Row(horizontalArrangement=Arrangement.spacedBy(8.dp),verticalAlignment=Alignment.CenterVertically) {
    Text("●",color=SpuntoColors.warn,style=MaterialTheme.typography.bodySmall)
    Text("Edited on two devices. Both versions are kept",Modifier.weight(1f),style=MaterialTheme.typography.bodyMedium)
    TextButton(onClick={comparing=!comparing},modifier=Modifier.heightIn(min=48.dp)) {Text(if(comparing) "Hide" else "Compare")}
   }
   if(comparing) {
    val saved=original
    if(saved==null) Text("The original isn't on this phone yet.",style=MaterialTheme.typography.bodySmall,color=SpuntoColors.ink3)
    else {
     ConflictVersion("The original",saved.body,copy.body)
     ConflictVersion("This copy",copy.body,saved.body)
     FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
      Button(onClick={settle("Kept this copy; it moved into the original",true)},modifier=Modifier.heightIn(min=48.dp)) {Text("Keep this copy")}
      OutlinedButton(onClick={settle("Copy moved to trash",false)},modifier=Modifier.heightIn(min=48.dp)) {Text("Keep the original")}
      TextButton(onClick={model.keepBothConflict(copy.id);comparing=false;feedback.show("Both notes stay","Open the original") {nav.open("note:$originalId")}},modifier=Modifier.heightIn(min=48.dp)) {Text("Keep both")}
     }
    }
   }
  }
 }
}
@Composable private fun ConflictVersion(label:String,body:String,other:String) {
 val otherLines=remember(other) {other.lines().map(String::trim).toSet()}
 Column(verticalArrangement=Arrangement.spacedBy(2.dp)) {
  Text(label.uppercase(),style=SpuntoType.overline,color=SpuntoColors.ink3,modifier=Modifier.semantics {heading()})
  body.lines().forEach {line ->
   val only=line.isNotBlank() && line.trim() !in otherLines
   Text(line.ifEmpty {" "},Modifier.fillMaxWidth().then(if(only) Modifier.background(SpuntoColors.highlight).semantics {contentDescription="Only in ${label.lowercase()}: $line"} else Modifier),style=SpuntoType.readingSmall,color=MaterialTheme.colorScheme.onSurface)
  }
 }
}

internal fun noteDay(at:Long)=Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("EEEE d MMMM · HH:mm"))
/** "just now", "5 min ago", "3 h ago", then the date. */
internal fun relativeTime(at:Long,now:Long=System.currentTimeMillis()):String {
 val minutes=(now-at)/60_000
 return when {minutes<1 -> "just now";minutes<60 -> "$minutes min ago";minutes<24*60 -> "${minutes/60} h ago";else -> noteTime(at)}
}
internal fun changeKindName(kind:String)=when(kind) {"create" -> "Created";"edit" -> "Edited";"append" -> "Added to";"merge" -> "Merged";"restore" -> "Restored";"accept" -> "Suggestion accepted";else -> kind.replace('_',' ').replaceFirstChar(Char::uppercase)}
/** One line per revision; tap to read it. Server versions keep their number; versions only on this phone have none yet, so numbers never shift after a sync. */
internal fun LazyListScope.revisionHistoryItems(revisions:List<RevisionEntity>,online:Boolean,result:HistoryRefreshResult?,editing:Boolean,current:String?=null,onRestore:(RevisionEntity)->Unit) {
 item {
  Text("History",style=MaterialTheme.typography.titleLarge,modifier=Modifier.padding(bottom=8.dp).semantics {heading()})
  if(!online || result is HistoryRefreshResult.Cached) Text("Showing history saved on this phone.",style=MaterialTheme.typography.bodySmall,color=SpuntoColors.ink3)
  if(editing) Text("Finish editing before restoring a version.",style=MaterialTheme.typography.bodySmall,color=SpuntoColors.ink3)
 }
 if(revisions.isEmpty()) item { Text("No saved revisions on this phone yet.",color=SpuntoColors.ink3) }
 items(revisions,key={it.id}) { rev ->
  var open by remember(rev.id) {mutableStateOf(false)}
  val isCurrent=current!=null && rev.body==current
  val name=if(rev.historySource=="server" && rev.version>0) "Version ${rev.version}" else "On this phone"
  val summary=listOf(name,relativeTime(rev.at),if(isCurrent) "Current" else changeKindName(rev.changeKind)).joinToString(" · ")
  Column {
   Row(Modifier.fillMaxWidth().heightIn(min=48.dp).clickable(onClickLabel=if(open) "Hide this version" else "Show this version") {open=!open},verticalAlignment=Alignment.CenterVertically) {
    Column(Modifier.weight(1f)) {
     Text(summary,style=MaterialTheme.typography.bodyMedium)
     if(!open) Text(titleOf(rev.body).ifBlank {"Empty note"},style=MaterialTheme.typography.bodySmall,color=SpuntoColors.ink3,maxLines=1,overflow=TextOverflow.Ellipsis)
    }
   }
   if(open) {
    Markdown(rev.body)
    if(!isCurrent) TextButton(onClick={onRestore(rev)},enabled=!editing,modifier=Modifier.heightIn(min=48.dp)) { Text("Restore this version") }
   }
   HorizontalDivider(color=MaterialTheme.colorScheme.outlineVariant)
  }
 }
}

@Composable fun NoteAttachments(model:SpuntoViewModel,id:String,body:String="") {
 val attachments by model.repository.dao.attachments(id).collectAsState(emptyList())
 val selected by model.auth.collectAsState()
 val auth=selected?.takeIf {!it.hosted || it.replicaId==model.container.replicaId}
 val links=remember(body) {recordingAttachmentLinks(body).distinctBy {it.second}}
 val linked=links.associate {it.second.substringAfterLast('/') to it.first}
 val localRecordings=attachments.filter {it.kind=="recording" && (it.marker.isNotEmpty() && it.marker in body || it.remoteId in linked)}
 Column(verticalArrangement=Arrangement.spacedBy(12.dp)) {
  Regex("!\\[[^]]*]\\((/a/[^)]+)\\)").findAll(body).forEach { match -> if(auth!=null) coil.compose.AsyncImage(model=coil.request.ImageRequest.Builder(model.container.context).data(auth!!.serverUrl+match.groupValues[1]).memoryCacheKey(model.container.replicaId+":"+auth!!.serverUrl+match.groupValues[1]).diskCacheKey(model.container.replicaId+":"+auth!!.serverUrl+match.groupValues[1]).addHeader("Authorization","Bearer ${auth!!.token}").build(),contentDescription="Note image",modifier=Modifier.fillMaxWidth().heightIn(max=360.dp)) }
  attachments.forEach { item ->
   val local=java.io.File(item.localPath)
   val url=if(local.exists()) local else item.remoteId?.let { remote -> auth?.let { it.serverUrl+"/a/$remote" } }
   if(url!=null && item.mime.startsWith("image/")) coil.compose.AsyncImage(model=coil.request.ImageRequest.Builder(model.container.context).data(url).memoryCacheKey(model.container.replicaId+":"+url.toString()).diskCacheKey(model.container.replicaId+":"+url.toString()).apply { if(url is String) auth?.let { addHeader("Authorization","Bearer ${it.token}") } }.build(),contentDescription="Note image",modifier=Modifier.fillMaxWidth().heightIn(max=360.dp))
  }
  localRecordings.forEach {item ->AttachmentPlayer(model,item.remoteId?:item.id,linked[item.remoteId]?:"Recording ${recordingTime(item.durationMs)}",item)}
  links.filter {(_,target) ->localRecordings.none {it.remoteId==target.substringAfterLast('/')}}.forEach {(label,target) ->AttachmentPlayer(model,target.substringAfterLast('/'),label)}
 }
}
