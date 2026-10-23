package io.theficos.spunto

import android.content.res.Resources
import androidx.compose.ui.platform.LocalContext

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.theficos.spunto.data.sync.*
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*
import io.theficos.spunto.core.design.SpuntoSpacing

internal data class AiStatus(val ai:JsonObject,val loading:Boolean,val error:String)
/** The server's AI status, fetched while online; offline it falls back to the profiles cached with the plugin catalog. */
@Composable internal fun rememberAiStatus(model:SpuntoViewModel):AiStatus {
 val resources=LocalContext.current.resources
 val auth by model.auth.collectAsState();val online by model.online.collectAsState();val cached by model.pluginAi.collectAsState()
 var ai by remember(auth) {mutableStateOf<JsonObject?>(null)};var loading by remember(auth) {mutableStateOf(false)};var error by remember(auth) {mutableStateOf("")}
 LaunchedEffect(auth,online) {
  val config=auth ?: return@LaunchedEffect
  if(!online) return@LaunchedEffect
  loading=true;error=""
  try {ai=ApiClient(config).ai()}
  catch(cancelled:CancellationException) {throw cancelled}
  catch(failure:Exception) {
   error=if(failure is ApiException) failure.detail.ifBlank {resources.getString(R.string.settings_couldn_t_load_ai_status_from_your)} else resources.getString(R.string.settings_couldn_t_reach_your_server_ai_status)
   if(failure is ApiException && failure.status==401) model.reauthenticate.value=true
  } finally {loading=false}
 }
 return AiStatus(ai ?: cached,loading,error)
}

/** Read-only on the phone (03 A9): models, where they run and the search index. Choosing models and running a check need the owner's browser session. */
@Composable internal fun AiSettingsSection(status:AiStatus,online:Boolean,onWeb:(String)->Unit) {
 val resources=LocalContext.current.resources
 SettingsSection(resources.getString(R.string.settings_ai))
 val ai=status.ai
 val off=ai.isNotEmpty() && (ai["profiles"] as? JsonArray).isNullOrEmpty()
 when {
  ai.isEmpty() && status.loading -> SettingsRow(resources.getString(R.string.settings_checking_ai_on_your_server))
  off -> SettingsRow(resources.getString(R.string.settings_ai_is_off),resources.getString(R.string.settings_everything_else_works_to_turn_it_on))
  else -> aiCapabilityCards(resources,ai).forEach {card -> SettingsRow(card.title,aiCardSummary(resources,card))}
 }
 if(ai["indexing"]!=null && !off) SettingsRow(resources.getString(R.string.settings_ai_search),aiIndexDescription(resources,ai))
 if(status.error.isNotBlank()) Text(status.error,Modifier.padding(horizontal=SpuntoSpacing.s20,vertical=SpuntoSpacing.s4),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
 SettingsRow(resources.getString(R.string.settings_ai_models),resources.getString(R.string.settings_choose_models_and_run_a_check_in),enabled=online,onClick={onWeb("/settings/ai")})
}
