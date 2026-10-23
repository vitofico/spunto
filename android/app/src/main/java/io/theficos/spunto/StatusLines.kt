package io.theficos.spunto

import android.content.res.Resources
import androidx.compose.ui.platform.LocalContext

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.theficos.spunto.core.design.*
import io.theficos.spunto.data.sync.SyncStatus
import kotlinx.coroutines.delay

/** The one status line under the top bar, empty when all is well; the most pressing of: the latest message, sign-in, a conflict, a refused change, offline, a sync backlog, plain HTTP. */
@Composable fun StatusLines(model:SpuntoViewModel) {
 val resources=LocalContext.current.resources
 val auth by model.auth.collectAsState(); val connected by model.container.connected.collectAsState(); val sync by model.status.collectAsState(); val message by model.message.collectAsState(); val pending by model.pending.collectAsState()
 val conflicts by model.conflicts.collectAsState(); val nav=LocalNavigator.current
 // An edit is pending for a moment after every save; only a backlog that outlasts a few seconds is news.
 var backlog by remember { mutableStateOf(false) }
 LaunchedEffect(pending>0) { backlog=false;if(pending>0) {delay(5_000);backlog=true} }
 val conflict=conflicts.firstOrNull()?.takeUnless {nav.route=="note:${it.id}"}
 val refused by model.repository.refused.collectAsState(emptyMap()); val review=refused.keys.firstOrNull {nav.route!="note:$it"}
 Box(Modifier.fillMaxWidth().semantics {liveRegion=LiveRegionMode.Polite}) {
  when {
   message.isNotEmpty() -> StatusLine(message,resources.getString(R.string.settings_dismiss_message),dismiss=true) {model.message.value=""}
   auth!=null && sync==SyncStatus.NeedsSignIn -> StatusLine(resources.getString(R.string.settings_not_signed_in_sign_in_to_sync),resources.getString(R.string.settings_sign_in)) {model.reauthenticate.value=true}
   conflict!=null -> StatusLine(resources.getString(R.string.settings_edited_on_two_devices_both_versions_are),resources.getString(R.string.settings_compare),warn=true) {nav.open("note:${conflict.id}")}
   review!=null -> StatusLine(resources.getQuantityString(R.plurals.settings_refused_notes,refused.size,refused.size),resources.getString(R.string.settings_review),warn=true) {nav.open("note:$review")}
   auth!=null && !connected -> StatusLine(resources.getString(R.string.settings_offline_notes_stay_on_this_phone))
   auth!=null && (backlog || sync is SyncStatus.Waiting) && pending>0 -> SyncStatusNotice(pending,sync is SyncStatus.Waiting) {model.synchronize()}
   auth?.serverUrl?.startsWith("http://")==true -> StatusLine(resources.getString(R.string.settings_not_encrypted_this_server_uses_plain_http))
  }
 }
}
@Composable private fun StatusLine(text:String,action:String?=null,warn:Boolean=false,dismiss:Boolean=false,onAction:()->Unit={}) {
 val resources=LocalContext.current.resources
 Row(Modifier.fillMaxWidth().heightIn(min=48.dp).padding(start=SpuntoSpacing.s20,end=SpuntoSpacing.s8),horizontalArrangement=Arrangement.spacedBy(SpuntoSpacing.s8),verticalAlignment=Alignment.CenterVertically) {
  if(warn) Text("●",color=SpuntoColors.warn,style=MaterialTheme.typography.bodySmall)
  Text(text,Modifier.weight(1f),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant,maxLines=if(dismiss) 3 else 1,overflow=TextOverflow.Ellipsis)
  if(action!=null) {
   if(dismiss) IconButton(onClick=onAction,modifier=Modifier.size(48.dp).semantics {contentDescription=action}) {Text("×",style=MaterialTheme.typography.titleMedium)}
   else TextButton(onClick=onAction,modifier=Modifier.heightIn(min=48.dp)) {Text(action)}
  }
 }
}
@Composable internal fun SyncStatusNotice(pending:Int,unreachable:Boolean,onSync:()->Unit) {
 val resources=LocalContext.current.resources
 StatusLine(resources.getQuantityString(if(unreachable) R.plurals.settings_waiting_unreachable else R.plurals.settings_waiting_sync,pending,pending),resources.getString(R.string.settings_sync),onAction=onSync)
}
internal fun notesCount(resources:Resources,count:Int)=resources.getQuantityString(R.plurals.settings_notes,count,count)
/** On a note whose change the server refused: what happened, and the way out that keeps every word. */
@Composable internal fun RefusedChangeNotice(model:SpuntoViewModel,id:String,code:String) {
 val resources=LocalContext.current.resources
 val nav=LocalNavigator.current; val feedback=LocalFeedback.current
 Surface(color=MaterialTheme.colorScheme.surfaceVariant,contentColor=MaterialTheme.colorScheme.onSurface,shape=MaterialTheme.shapes.medium) {
  Column(Modifier.fillMaxWidth().padding(SpuntoSpacing.s16),verticalArrangement=Arrangement.spacedBy(SpuntoSpacing.s12)) {
   Row(horizontalArrangement=Arrangement.spacedBy(SpuntoSpacing.s8)) {
    Text("●",color=SpuntoColors.warn,style=MaterialTheme.typography.bodySmall)
    Text(refusedChangeMessage(resources,code),style=MaterialTheme.typography.bodyMedium)
   }
   // Shortening a note that is too long lets it sync, so only the other refusals need a new note.
   if(code!="too_large") Button(onClick={model.task {val copy=model.repository.keepRefusedAsNewNote(id);nav.back();nav.open("note:$copy");feedback.show(resources.getString(R.string.settings_kept_as_a_new_note))}},modifier=Modifier.heightIn(min=48.dp)) {Text(resources.getString(R.string.settings_keep_as_new_note))}
  }
 }
}
internal fun refusedChangeMessage(resources:Resources,code:String)=when(code) {
 "too_large" -> resources.getString(R.string.settings_this_note_is_too_long_to_sync)
 "not_found","id_conflict" -> resources.getString(R.string.settings_this_note_was_deleted_on_another_device)
 else -> resources.getString(R.string.settings_the_server_couldn_t_accept_this_change)
}
