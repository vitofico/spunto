package io.theficos.spunto

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import io.theficos.spunto.data.sync.*
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*

@Composable fun StatusScreen(model:SpuntoViewModel)=ServerStatusScreen(model,false)
@Composable fun AISettingsScreen(model:SpuntoViewModel)=ServerStatusScreen(model,true)

@Composable private fun ServerStatusScreen(model:SpuntoViewModel,aiOnly:Boolean) {
 val status by model.status.collectAsState();val notes by model.notes.collectAsState();val labels by model.labels.collectAsState();val pending by model.pending.collectAsState()
 val auth by model.auth.collectAsState();val online by model.online.collectAsState();val logs by model.repository.dao.logs().collectAsState(emptyList())
 var ai by remember(auth) {mutableStateOf(JsonObject(emptyMap()))}
 var loading by remember(auth) {mutableStateOf(false)};var error by remember(auth) {mutableStateOf("")}
 var refresh by remember {mutableIntStateOf(0)};var checkProvider by remember {mutableStateOf(false)}
 var diagnostics by remember {mutableStateOf(false)}
 LaunchedEffect(auth,online,refresh) {
  val config=auth ?: return@LaunchedEffect
  if(!online) return@LaunchedEffect
  loading=true;error=""
  try {
   val api=ApiClient(config)
   if(checkProvider) {checkProvider=false;api.post("/api/v1/ai/check",JsonObject(emptyMap()))}
   ai=api.ai()
  } catch(cancelled:CancellationException) {throw cancelled}
  catch(failure:Exception) {
   error=if(failure is ApiException && failure.status==403) "AI settings are managed by your space owner." else if(failure is ApiException) pluginCommandErrorMessage(failure.status,failure.code) else "Couldn't reach your server. Check the connection and retry."
   if(failure is ApiException && failure.status==401) model.reauthenticate.value=true
  } finally {loading=false}
 }
 LazyColumn(contentPadding=PaddingValues(20.dp),verticalArrangement=Arrangement.spacedBy(20.dp)) {
  if(!aiOnly) item {
   StatusCard(syncStatusTitle(status,pending)) {
    Text(syncStatusDescription(status,pending,online),color=MaterialTheme.colorScheme.onSurfaceVariant)
    Text("${notes.size} notes · ${labels.size} labels",style=MaterialTheme.typography.bodySmall)
    if(status==SyncStatus.Running) LinearProgressIndicator(Modifier.fillMaxWidth())
    TextButton(onClick={model.synchronize();refresh++},enabled=auth!=null && online && !loading && status!=SyncStatus.Running) {Text("Sync and refresh")}
   }
  }
  item {
   StatusCard(if(aiOnly) "Choose your AI models" else "AI on your server") {
    Text(if(aiOnly) "Choose which models write, chat and search for this space. Your server lists the models its configured providers offer." else "See the selected models and check whether your server can reach them.",color=MaterialTheme.colorScheme.onSurfaceVariant)
    if(auth==null) Text("Connect your server in Devices to use AI.")
    else {
     if(!online) Text("You're offline. Connect to refresh AI status.")
     if(error.isNotBlank()) Text(error,color=MaterialTheme.colorScheme.error)
     if(loading) {LinearProgressIndicator(Modifier.fillMaxWidth());Text("Contacting your AI providers…",style=MaterialTheme.typography.bodySmall)}
     if(ai.isEmpty() && !loading && error.isBlank() && online) Text("AI status hasn't loaded yet.")
     TextButton(onClick={model.pluginWeb("/settings/ai")},enabled=online) {Text("Open AI model settings")}
     Text("Sign in as the space owner in your browser. Provider addresses and credentials are configured by the server operator.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
     Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
      TextButton(onClick={checkProvider=false;refresh++},enabled=online && !loading) {Text("Refresh status")}
      if((ai["can_manage"] as? JsonPrimitive)?.booleanOrNull==true) TextButton(onClick={checkProvider=true;refresh++},enabled=online && !loading) {Text("Check AI connection")}
     }
    }
   }
  }
  items(aiCapabilityCards(ai).takeIf {ai.isNotEmpty()}.orEmpty(),key={it.capability}) {card -> AiCapabilityStatus(card)}
  if(ai.isNotEmpty()) item {StatusCard("AI search index") {Text(aiIndexDescription(ai),color=MaterialTheme.colorScheme.onSurfaceVariant)}}
  if(!aiOnly) item {
   TextButton(onClick={diagnostics=!diagnostics}) {Text(if(diagnostics) "Hide sync activity" else "Show sync activity")}
   if(diagnostics) Column(verticalArrangement=Arrangement.spacedBy(12.dp)) {
    Text("Recent sync activity",style=MaterialTheme.typography.titleMedium)
    if(logs.isEmpty()) Text("No sync activity yet.")
    logs.take(20).forEach {log -> Text("${noteTime(log.at)} · ${log.outcome.replace('_',' ')}${log.reason?.takeIf {it.isNotBlank()}?.let {" · ${it.replace('_',' ')}"}.orEmpty()}",style=MaterialTheme.typography.bodySmall)}
    TextButton(onClick={model.container.context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Spunto sync log",logs.joinToString("\n") {"${noteTime(it.at)} ${it.outcome} ${it.reason.orEmpty()}"}))}) {Text("Copy diagnostic log")}
   }
  }
 }
}
@Composable internal fun AiCapabilityStatus(card:AiCapabilityCard) {
 StatusCard(card.title) {
  Text(card.model,style=MaterialTheme.typography.titleMedium)
  if(card.destination.isNotBlank()) Text(card.destination,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
  Text(card.state,style=MaterialTheme.typography.labelLarge,color=if(card.healthy==false) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
  Text(card.detail,style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
 }
}
@Composable private fun StatusCard(title:String,content:@Composable ColumnScope.()->Unit) {
 OutlinedCard(Modifier.fillMaxWidth()) {Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {Text(title,style=MaterialTheme.typography.titleMedium);content()}}
}
