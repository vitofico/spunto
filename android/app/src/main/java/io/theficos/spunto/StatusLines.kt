package io.theficos.spunto

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import io.theficos.spunto.data.sync.SyncStatus

/** The lines under the top bar on every page: plain HTTP, offline, sync backlog and the latest message. */
@Composable fun StatusLines(model:SpuntoViewModel) {
 val auth by model.auth.collectAsState(); val online by model.online.collectAsState(); val sync by model.status.collectAsState(); val message by model.message.collectAsState(); val pending by model.pending.collectAsState()
 if(auth?.serverUrl?.startsWith("http://")==true) Text("Not encrypted · This server uses plain HTTP.",Modifier.fillMaxWidth().padding(12.dp),style=MaterialTheme.typography.bodySmall)
 if(!online && auth!=null) Text("Offline. Notes stay on this phone.",Modifier.padding(horizontal=20.dp),style=MaterialTheme.typography.bodySmall)
 if(pending>0 || sync is SyncStatus.Waiting) SyncStatusNotice(pending,sync is SyncStatus.Waiting) {model.synchronize()}
 if(message.isNotEmpty()) Row(Modifier.fillMaxWidth().padding(horizontal=20.dp),verticalAlignment=Alignment.CenterVertically) { Text(message,Modifier.weight(1f),style=MaterialTheme.typography.bodyMedium); TextButton(onClick={model.message.value=""},modifier=Modifier.semantics {contentDescription="Dismiss message"}) { Text("×") } }
}
@Composable internal fun SyncStatusNotice(pending:Int,unreachable:Boolean,onSync:()->Unit) {
 Row(Modifier.fillMaxWidth().padding(horizontal=20.dp),horizontalArrangement=Arrangement.spacedBy(12.dp),verticalAlignment=Alignment.CenterVertically) {
  Text("$pending notes waiting to sync${if(unreachable) " · Server unreachable" else ""}",Modifier.weight(1f),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
  TextButton(onClick=onSync,modifier=Modifier.heightIn(min=48.dp)) {Text("Sync")}
 }
}
