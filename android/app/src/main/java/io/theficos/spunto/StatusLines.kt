package io.theficos.spunto

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

/** The one status line under the top bar, empty when all is well; the most pressing of: the latest message, sign-in, a conflict, offline, a sync backlog, plain HTTP. */
@Composable fun StatusLines(model:SpuntoViewModel) {
 val auth by model.auth.collectAsState(); val connected by model.container.connected.collectAsState(); val sync by model.status.collectAsState(); val message by model.message.collectAsState(); val pending by model.pending.collectAsState()
 val conflicts by model.conflicts.collectAsState(); val nav=LocalNavigator.current
 // An edit is pending for a moment after every save; only a backlog that outlasts a few seconds is news.
 var backlog by remember { mutableStateOf(false) }
 LaunchedEffect(pending>0) { backlog=false;if(pending>0) {delay(5_000);backlog=true} }
 val conflict=conflicts.firstOrNull()?.takeUnless {nav.route=="note:${it.id}"}
 Box(Modifier.fillMaxWidth().semantics {liveRegion=LiveRegionMode.Polite}) {
  when {
   message.isNotEmpty() -> StatusLine(message,"Dismiss message",dismiss=true) {model.message.value=""}
   auth!=null && sync==SyncStatus.NeedsSignIn -> StatusLine("Not signed in · Sign in to sync","Sign in") {model.reauthenticate.value=true}
   conflict!=null -> StatusLine("Edited on two devices. Both versions are kept","Compare",warn=true) {nav.open("note:${conflict.id}")}
   auth!=null && !connected -> StatusLine("Offline · notes stay on this phone")
   auth!=null && (backlog || sync is SyncStatus.Waiting) && pending>0 -> SyncStatusNotice(pending,sync is SyncStatus.Waiting) {model.synchronize()}
   auth?.serverUrl?.startsWith("http://")==true -> StatusLine("Not encrypted · This server uses plain HTTP.")
  }
 }
}
@Composable private fun StatusLine(text:String,action:String?=null,warn:Boolean=false,dismiss:Boolean=false,onAction:()->Unit={}) {
 Row(Modifier.fillMaxWidth().heightIn(min=48.dp).padding(start=20.dp,end=8.dp),horizontalArrangement=Arrangement.spacedBy(8.dp),verticalAlignment=Alignment.CenterVertically) {
  if(warn) Text("●",color=SpuntoColors.warn,style=MaterialTheme.typography.bodySmall)
  Text(text,Modifier.weight(1f),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant,maxLines=if(dismiss) 3 else 1,overflow=TextOverflow.Ellipsis)
  if(action!=null) {
   if(dismiss) IconButton(onClick=onAction,modifier=Modifier.size(48.dp).semantics {contentDescription=action}) {Text("×",style=MaterialTheme.typography.titleMedium)}
   else TextButton(onClick=onAction,modifier=Modifier.heightIn(min=48.dp)) {Text(action)}
  }
 }
}
@Composable internal fun SyncStatusNotice(pending:Int,unreachable:Boolean,onSync:()->Unit) =
 StatusLine("${notesCount(pending)} waiting to sync${if(unreachable) " · Server unreachable" else ""}","Sync",onAction=onSync)
internal fun notesCount(count:Int)=if(count==1) "1 note" else "$count notes"
