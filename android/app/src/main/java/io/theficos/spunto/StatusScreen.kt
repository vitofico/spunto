package io.theficos.spunto

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.theficos.spunto.data.sync.*
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*

internal data class AiStatus(val ai:JsonObject,val loading:Boolean,val error:String)
/** The server's AI status, fetched while online; offline it falls back to the profiles cached with the plugin catalog. */
@Composable internal fun rememberAiStatus(model:SpuntoViewModel):AiStatus {
 val auth by model.auth.collectAsState();val online by model.online.collectAsState();val cached by model.pluginAi.collectAsState()
 var ai by remember(auth) {mutableStateOf<JsonObject?>(null)};var loading by remember(auth) {mutableStateOf(false)};var error by remember(auth) {mutableStateOf("")}
 LaunchedEffect(auth,online) {
  val config=auth ?: return@LaunchedEffect
  if(!online) return@LaunchedEffect
  loading=true;error=""
  try {ai=ApiClient(config).ai()}
  catch(cancelled:CancellationException) {throw cancelled}
  catch(failure:Exception) {
   error=if(failure is ApiException) failure.detail.ifBlank {"Couldn't load AI status from your server."} else "Couldn't reach your server. AI status shows when you're connected."
   if(failure is ApiException && failure.status==401) model.reauthenticate.value=true
  } finally {loading=false}
 }
 return AiStatus(ai ?: cached,loading,error)
}

/** Read-only on the phone (03 A9): models, where they run and the search index. Choosing models and running a check need the owner's browser session. */
@Composable internal fun AiSettingsSection(status:AiStatus,online:Boolean,onWeb:(String)->Unit) {
 SettingsSection("AI")
 val ai=status.ai
 val off=ai.isNotEmpty() && (ai["profiles"] as? JsonArray).isNullOrEmpty()
 when {
  ai.isEmpty() && status.loading -> SettingsRow("Checking AI on your server…")
  off -> SettingsRow("AI is off","Everything else works. To turn it on, set the SPUNTO_AI_* variables and restart the server.")
  else -> aiCapabilityCards(ai).forEach {card -> SettingsRow(card.title,aiCardSummary(card))}
 }
 if(ai["indexing"]!=null && !off) SettingsRow("AI search",aiIndexDescription(ai))
 if(status.error.isNotBlank()) Text(status.error,Modifier.padding(horizontal=20.dp,vertical=4.dp),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
 SettingsRow("AI models","Choose models and run a check in the web app",enabled=online,onClick={onWeb("/settings/ai")})
}
