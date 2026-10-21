@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package io.theficos.spunto

import android.content.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
 val note by model.repository.observe(id).collectAsState(null)
 var editing by remember(id) { mutableStateOf(false) }
 var actions by remember(id) { mutableStateOf(false) }
 var history by remember(id) { mutableStateOf(false) }
 var about by remember(id) { mutableStateOf(false) }
 var commandResult by remember(id) { mutableStateOf<JsonObject?>(null) }
 val editorScope=rememberCoroutineScope()
 var finishing by remember(id) {mutableStateOf(false)}
 var restoring by remember(id) {mutableStateOf(false)}
 var finishEditor by remember(id) {mutableStateOf<(suspend ()->Unit)?>(null)}
 val revisions by model.repository.history(id).collectAsState(emptyList())
 var historyResult by remember(id) { mutableStateOf<HistoryRefreshResult?>(null) }
 var recordingTarget by remember(id) {mutableStateOf<String?>(null)}
 val value=note?.takeIf {it.id==id} ?: run { Text("Note unavailable.");return }
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
 LaunchedEffect(history,online,id,value.serverRev) { if(history && online) historyResult=model.refreshHistory(id) }
 Column(Modifier.fillMaxSize()) {
  Surface(tonalElevation=1.dp) {
   Row(Modifier.fillMaxWidth().padding(start=20.dp,end=8.dp,top=4.dp,bottom=4.dp),horizontalArrangement=Arrangement.spacedBy(8.dp),verticalAlignment=Alignment.CenterVertically) {
    Column(Modifier.weight(1f)) {
     Text(if(editing) "Editing" else noteTime(value.createdAt),style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
     val details=if(editing) "Changes save on this phone" else listOfNotNull(if(value.privateNote) "Private" else null,if(value.pinned) "Pinned" else null,if(value.archived) "Archived" else null,if(value.trashed) "In trash" else null).joinToString(" · ")
     if(details.isNotBlank()) Text(details,style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant,maxLines=1,overflow=TextOverflow.Ellipsis)
    }
    FilledTonalButton(enabled=!finishing && !restoring && (!editing || finishEditor!=null),onClick={if(editing) finishEditing() else editing=true}) {Text(if(finishing) "Saving…" else if(editing) "Done" else "Edit")}
    if(!editing) IconButton(onClick={actions=true},enabled=!restoring) {Icon(painterResource(R.drawable.ic_more),contentDescription="More note actions",modifier=Modifier.size(24.dp))}
   }
  }
  LazyColumn(Modifier.weight(1f).fillMaxWidth().imePadding(),contentPadding=PaddingValues(20.dp),verticalArrangement=Arrangement.spacedBy(20.dp)) {
   item {
    if(editing) Composer(model,id,value.body,onFinishReady={finishEditor=it},onRecordingSaved={editing=false},showSaveAction=false,onSave={editing=false})
    else Markdown(value.body,value.machine,onLink={if(it.startsWith("/n/")) onNote(it.substringAfter("/n/")) else if(recordingAttachmentLinks(value.body).any {link ->link.second==it}) recordingTarget=it}) {model.toggleTask(value,it)}
   }
   if(!editing) item {Suggestions(model,id,onNote)}
   item {NoteAttachments(model,id,value.body)}
  }
 }
 if(actions) ModalBottomSheet(onDismissRequest={actions=false},sheetState=rememberModalBottomSheetState(skipPartiallyExpanded=true)) {
  Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(horizontal=20.dp).padding(bottom=24.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
   Text("Note actions",style=MaterialTheme.typography.headlineSmall)
   if(online && !value.trashed) NoteCommands(model,id,privateNote=value.privateNote) {actions=false;commandResult=it}
   if(value.privateNote) Text("AI and network tools are unavailable for private notes.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
   NoteAction("About this note") {actions=false;about=true}
   NoteAction("History") {actions=false;history=true}
   HorizontalDivider()
   NoteAction(if(value.pinned) "Unpin" else "Pin") {model.flags(id,pinned=!value.pinned);actions=false}
   NoteAction(if(value.archived) "Unarchive" else "Archive") {model.flags(id,archived=!value.archived);actions=false}
   Row(Modifier.fillMaxWidth().padding(vertical=8.dp),horizontalArrangement=Arrangement.spacedBy(12.dp),verticalAlignment=Alignment.CenterVertically) {
    Column(Modifier.weight(1f)) {Text("Private note",style=MaterialTheme.typography.bodyLarge);Text("Excluded from AI and network plugins",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}
    Switch(checked=value.privateNote,onCheckedChange={model.flags(id,privateNote=it)},modifier=Modifier.semantics {contentDescription="Private note"})
   }
   HorizontalDivider()
   NoteExportActions(model,value,enabled=!editing && !finishing && !restoring)
   HorizontalDivider()
   NoteAction(if(value.trashed) "Restore from trash" else "Move to trash",destructive=!value.trashed) {model.flags(id,trashed=!value.trashed);actions=false;onClose()}
  }
 }
 if(history) ModalBottomSheet(onDismissRequest={history=false},sheetState=rememberModalBottomSheetState(skipPartiallyExpanded=true)) {
  LazyColumn(Modifier.fillMaxWidth().navigationBarsPadding(),contentPadding=PaddingValues(20.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
   item {TextButton(onClick={history=false}) {Text("Close history")}}
   revisionHistoryItems(revisions,online,historyResult,editing || finishing || restoring) {revision ->
    if(!editing && !finishing && !restoring) {restoring=true;editorScope.launch {
     try {model.repository.restoreHistory(id,revision)}
     catch(error:kotlinx.coroutines.CancellationException) {throw error}
     catch(error:Exception) {model.message.value="Couldn't restore this revision. Your note and draft stay on this phone."}
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
@Composable private fun NoteAction(label:String,destructive:Boolean=false,onClick:()->Unit) {
 TextButton(onClick=onClick,modifier=Modifier.fillMaxWidth(),contentPadding=PaddingValues(horizontal=12.dp,vertical=12.dp),colors=ButtonDefaults.textButtonColors(contentColor=if(destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)) {Text(label,Modifier.fillMaxWidth(),style=MaterialTheme.typography.bodyLarge)}
}

internal fun LazyListScope.revisionHistoryItems(revisions:List<RevisionEntity>,online:Boolean,result:HistoryRefreshResult?,editing:Boolean,onRestore:(RevisionEntity)->Unit) {
 item { Text("Revision history",style=MaterialTheme.typography.titleLarge);if(!online || result is HistoryRefreshResult.Cached) Text("Showing history saved on this phone.",style=MaterialTheme.typography.bodySmall);if(editing) Text("Finish editing before restoring a revision.",style=MaterialTheme.typography.bodySmall) }
 if(revisions.isEmpty()) item { Text("No saved revisions on this phone yet.") }
 items(revisions,key={it.id}) { rev -> Column { Text((if(rev.version>0) "Version "+rev.version+" · " else "Local revision · ")+noteTime(rev.at));Text(if(rev.historySource=="server") rev.changeKind.replace('_',' ') else "Saved on this phone",style=MaterialTheme.typography.bodySmall);Markdown(rev.body);TextButton(onClick={onRestore(rev)},enabled=!editing) { Text("Restore this revision") };HorizontalDivider() } }
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
