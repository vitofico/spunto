@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package io.theficos.spunto

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.room.withTransaction
import io.theficos.spunto.core.model.Note
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import io.theficos.spunto.core.design.SpuntoSpacing

internal data class ExportableNote(val note:Note,val stamp:NoteExportStamp)
/** Read the saved local revision, never the editor buffer, under the current replica authority. */
internal suspend fun readNoteForExport(model:SpuntoViewModel,id:String):ExportableNote? {
 if(!model.container.appLock.state.value.unlocked) return null
 val credentials=model.container.auth.config.first()
 val result=model.container.database.withTransaction {
  val note=model.repository.dao.get(id)?.note() ?: return@withTransaction null
  val draft=model.repository.dao.unsavedDraft(id)?.body
  if(draft!=null && draft!=note.body) return@withTransaction null
  val replica=model.repository.dao.state()
  ExportableNote(note,noteExportStamp(note,draft,credentials,replica?.serverUrl,replica?.epoch))
 }
 return result?.takeIf {model.container.auth.config.first()==credentials && model.container.appLock.state.value.unlocked}
}
internal fun shareNoteIntent(note:Note)=Intent(Intent.ACTION_SEND).apply {
 type="text/plain"
 putExtra(Intent.EXTRA_TEXT,note.body)
 putExtra(Intent.EXTRA_SUBJECT,note.title)
}
private enum class ExportAction {Share,Save}
private data class ExportApproval(val action:ExportAction,val stamp:NoteExportStamp)
private data class SelectedDocument(val request:NoteExportSession.Request,val uri:Uri)

/** [content] gets Share and Save actions and whether they can run now; by default two text buttons. */
@Composable internal fun NoteExportActions(model:SpuntoViewModel,note:Note,enabled:Boolean,content:@Composable (share:()->Unit,save:()->Unit,available:Boolean)->Unit={share,save,available ->
 Row(horizontalArrangement=Arrangement.spacedBy(SpuntoSpacing.s8)) {TextButton(onClick=share,enabled=available) {Text(androidx.compose.ui.res.stringResource(R.string.core_share))};TextButton(onClick=save,enabled=available) {Text(androidx.compose.ui.res.stringResource(R.string.core_save_markdown_short))}}
}) {
 val credentials by model.auth.collectAsState()
 val replica by model.repository.dao.observeState().collectAsState(initial=null)
 val unsaved=remember(note.id) {model.repository.dao.observeUnsavedDraft(note.id)}.collectAsState(initial=null).value?.body
 // A different registration cannot consume the previous note/account's picker result.
 key(note.id,credentials,replica?.serverUrl,replica?.epoch) {
  NoteExportActionsForOwner(model,note,enabled && (unsaved==null || unsaved==note.body),noteExportStamp(note,unsaved,credentials,replica?.serverUrl,replica?.epoch),content)
 }
}
@Composable private fun NoteExportActionsForOwner(model:SpuntoViewModel,note:Note,enabled:Boolean,stamp:NoteExportStamp,content:@Composable (()->Unit,()->Unit,Boolean)->Unit) {
 val context=LocalContext.current
 val scope=rememberCoroutineScope()
 val gate by model.container.appLock.state.collectAsState()
 val session=remember {NoteExportSession()}
 var request by remember {mutableStateOf<NoteExportSession.Request?>(null)}
 var selected by remember {mutableStateOf<SelectedDocument?>(null)}
 var approval by remember {mutableStateOf<ExportApproval?>(null)}
 var busy by remember {mutableStateOf(false)}
 val currentStamp by rememberUpdatedState(stamp)
 val currentEnabled by rememberUpdatedState(enabled)
 fun cancel() {session.cancel();request=null;selected=null;approval=null}
 fun allowed(expected:NoteExportStamp)=currentEnabled && currentStamp==expected && model.container.appLock.state.value.unlocked
 fun changed() {cancel();model.message.value=context.getString(R.string.core_export_changed)}
 DisposableEffect(session) {onDispose {session.cancel()}}
 LaunchedEffect(stamp,enabled) {if(!enabled || request?.stamp?.let {it!=stamp}==true || approval?.stamp?.let {it!=stamp}==true) cancel()}
 val documentContract=remember {object:ActivityResultContracts.CreateDocument("text/markdown") {
  override fun createIntent(context:Context,input:String)=super.createIntent(context,input).addCategory(Intent.CATEGORY_OPENABLE)
 }}
 val document=rememberLauncherForActivityResult(documentContract) {uri ->
  val expected=request
  if(uri==null) cancel()
  else if(expected!=null && uri.scheme=="content" && currentEnabled && currentStamp==expected.stamp) selected=SelectedDocument(expected,uri)
  else changed()
 }
 fun begin(action:ExportAction,approved:NoteExportStamp?=null) {
  if(busy || request!=null || !enabled || !gate.unlocked) return
  busy=true
  scope.launch {
   try {
    val fresh=readNoteForExport(model,note.id)
    if(fresh==null || !allowed(fresh.stamp) || approved!=null && approved!=fresh.stamp) {changed();return@launch}
    if(fresh.note.privateNote && approved==null) {approval=ExportApproval(action,fresh.stamp);return@launch}
    approval=null
    if(action==ExportAction.Share) {
     context.startActivity(Intent.createChooser(shareNoteIntent(fresh.note),context.getString(R.string.core_share_note)))
    } else {
     val attempt=session.begin(fresh.stamp);request=attempt
     document.launch(markdownFileName(context,fresh.note.title))
    }
   } catch(cancelled:CancellationException) {throw cancelled}
   catch(_:ActivityNotFoundException) {cancel();model.message.value=context.getString(R.string.core_export_no_app)}
   catch(_:Exception) {cancel();model.message.value=context.getString(R.string.core_export_failed)}
   finally {busy=false}
  }
 }
 Column {content({begin(ExportAction.Share)},{begin(ExportAction.Save)},enabled && gate.unlocked && !busy && request==null)}
 if(gate.unlocked) approval?.let {pending ->
  AlertDialog(onDismissRequest={approval=null},title={Text(if(pending.action==ExportAction.Share) context.getString(R.string.core_share_private) else context.getString(R.string.core_save_private))},text={Text(context.getString(R.string.core_private_export_warning))},confirmButton={TextButton(onClick={begin(pending.action,pending.stamp)},enabled=!busy) {Text(context.getString(R.string.core_continue))}},dismissButton={TextButton(onClick={approval=null}) {Text(context.getString(R.string.core_cancel))}})
 }
 if(gate.unlocked) selected?.let {destination ->
  AlertDialog(onDismissRequest={cancel()},title={Text(context.getString(R.string.core_save_selected_question))},text={Text(context.getString(R.string.core_save_selected_warning))},confirmButton={TextButton(enabled=!busy,onClick={
   if(busy) return@TextButton
   busy=true
   scope.launch {
    try {
     val fresh=readNoteForExport(model,note.id)
     if(fresh==null || !allowed(fresh.stamp) || !session.owns(destination.request,fresh.stamp,model.container.appLock.state.value.unlocked)) {changed();return@launch}
     withContext(Dispatchers.IO) {
      check(allowed(fresh.stamp) && session.owns(destination.request,fresh.stamp,model.container.appLock.state.value.unlocked))
      context.contentResolver.openOutputStream(destination.uri,"wt")?.use {output ->
       // A document provider may block while the replica or account changes.
       val afterOpen=readNoteForExport(model,note.id)
       ensureActive()
       check(afterOpen?.stamp==fresh.stamp && allowed(fresh.stamp) && session.owns(destination.request,fresh.stamp,model.container.appLock.state.value.unlocked))
       writeNoteMarkdown(output,fresh.note.body) {ensureActive();allowed(fresh.stamp) && session.owns(destination.request,fresh.stamp,model.container.appLock.state.value.unlocked)}
      } ?: error(context.getString(R.string.core_document_unavailable))
     }
     cancel();model.message.value=context.getString(R.string.core_markdown_saved)
    } catch(cancelled:CancellationException) {throw cancelled}
    catch(_:Exception) {cancel();model.message.value=context.getString(R.string.core_save_incomplete)}
    finally {busy=false}
   }
  }) {Text(context.getString(R.string.core_save_selected))}},dismissButton={TextButton(onClick={cancel()},enabled=!busy) {Text(context.getString(R.string.core_cancel))}})
 }
}
