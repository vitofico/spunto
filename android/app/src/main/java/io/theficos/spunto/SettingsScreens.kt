@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class,androidx.compose.material3.ExperimentalMaterial3Api::class)
package io.theficos.spunto

import android.content.res.Resources

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import io.theficos.spunto.core.design.SpuntoSpacing
import io.theficos.spunto.core.design.SpuntoType
import io.theficos.spunto.core.plugins.*
import io.theficos.spunto.data.sync.SyncStatus
import io.theficos.spunto.plugins.dictation.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*

/** 03 A9: one page of sections. Appearance, Plugins and each plugin's page open on their own; owner-only pages open in the web app. */
@Composable fun SettingsScreen(model:SpuntoViewModel,appearance:String,onRoute:(String)->Unit) {
 val locale=LocalConfiguration.current.locales[0]
 val resources=LocalContext.current.resources
 val context=LocalContext.current;val feedback=LocalFeedback.current
 val auth by model.auth.collectAsState();val online by model.online.collectAsState();val reauth by model.reauthenticate.collectAsState();val status by model.status.collectAsState();val pending by model.pending.collectAsState()
 val catalog by model.catalog.collectAsState();val manifest by model.manifest.collectAsState()
 val replica by model.repository.dao.observeState().collectAsState(initial=null);val logs by model.repository.dao.logs().collectAsState(emptyList())
 val phoneLock by model.container.appLock.state.collectAsState()
 var signOut by remember {mutableStateOf(false)};var activity by rememberSaveable {mutableStateOf(false)}
 val ai=rememberAiStatus(model)
 val plugins=nativePluginCatalog(catalog,manifest)
 val needsSignIn=reauth || status==SyncStatus.NeedsSignIn
 val version=remember {runCatching {context.packageManager.getPackageInfo(context.packageName,0).versionName}.getOrNull().orEmpty()}
 fun ownerPage(section:String) {
  val target=ownerWebSettingsTarget(context,model.auth.value,replica?.serverUrl,model.online.value,section,needsSignIn)
  val url=target.url ?: return feedback.show(target.message)
  try {context.startActivity(Intent(Intent.ACTION_VIEW,Uri.parse(url)))} catch(_:ActivityNotFoundException) {feedback.show(resources.getString(R.string.settings_no_browser_is_available_to_open_the))}
 }
 LazyColumn(contentPadding=PaddingValues(bottom=SpuntoSpacing.s24)) {
  item {
   // Appearance and Plugins open their own pages, so their rows lead the page without a heading that repeats them.
   Spacer(Modifier.height(SpuntoSpacing.s8))
   SettingsRow(resources.getString(R.string.settings_appearance),appearance,onClick={onRoute("appearance")})
   val language=locale.getDisplayName(locale).replaceFirstChar {it.titlecase(locale)}
   val modern=android.os.Build.VERSION.SDK_INT>=33
   SettingsRow(resources.getString(R.string.settings_language),resources.getString(R.string.settings_language_current,language,resources.getString(if(modern) R.string.settings_language_system else R.string.settings_language_older)),onClick={
    if(modern) try {context.startActivity(Intent(android.provider.Settings.ACTION_APP_LOCALE_SETTINGS,Uri.parse("package:${context.packageName}")))} catch(_:ActivityNotFoundException) {feedback.show(resources.getString(R.string.settings_language_unavailable))}
    else try {context.startActivity(Intent(android.provider.Settings.ACTION_LOCALE_SETTINGS))} catch(_:ActivityNotFoundException) {feedback.show(resources.getString(R.string.settings_language_unavailable))}
   })
   SettingsRow(resources.getString(R.string.settings_plugins),when {plugins.valid -> resources.getString(R.string.settings_of_on ,plugins.onCount,plugins.rows.size);auth==null -> resources.getString(R.string.settings_connect_your_server_to_choose_plugins);else -> resources.getString(R.string.settings_sync_to_load_your_plugins)},onClick={onRoute("plugins")})
  }
  item {
   SettingsSection(resources.getString(R.string.settings_server))
   val config=auth
   if(config==null) SettingsRow(resources.getString(R.string.settings_connect_to_your_server),resources.getString(R.string.settings_sign_in_or_scan_a_pairing_code),onClick={onRoute("login")})
   else {
    SettingsRow(config.serverUrl.removePrefix("https://"),resources.getString(R.string.settings_this_phone_is ,config.name))
    if(needsSignIn) SettingsRow(resources.getString(R.string.settings_sign_in_again),resources.getString(R.string.settings_sync_is_paused_until_this_phone_signs),onClick={onRoute("login")})
    SettingsRow(resources.getString(R.string.settings_devices),resources.getString(R.string.settings_remove_this_phone_or_others_in_the),enabled=online,onClick={model.pluginWeb("/settings/devices")})
    SettingsRow(resources.getString(R.string.settings_profile),resources.getString(R.string.settings_your_display_name_in_the_web_app),onClick={ownerPage("profile")})
    SettingsRow(resources.getString(R.string.settings_sign_out),resources.getString(R.string.settings_your_notes_stay_on_this_phone),onClick={signOut=true})
   }
  }
  item {
   SettingsSection(resources.getString(R.string.settings_sync))
   val running=status==SyncStatus.Running
   SettingsRow(resources.getString(R.string.settings_sync_now),syncSummary(resources,if(auth==null) SyncStatus.SignedOut else status,pending,online,logs.firstOrNull {it.outcome=="complete"}?.at),enabled=auth!=null && online && !needsSignIn && !running,onClick={model.synchronize()}) {if(running) CircularProgressIndicator(Modifier.size(20.dp),strokeWidth=2.dp)}
   SettingsRow(resources.getString(R.string.settings_sync_activity),resources.getString(R.string.settings_recent_sync_attempts_for_troubleshooting),onClick={activity=!activity},expanded=activity)
   if(activity) Column(Modifier.padding(horizontal=SpuntoSpacing.s20),verticalArrangement=Arrangement.spacedBy(SpuntoSpacing.s8)) {
    if(logs.isEmpty()) Text(resources.getString(R.string.settings_no_sync_activity_yet),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
    logs.take(20).forEach {log -> Text("${noteTime(log.at,locale)} · ${log.outcome.replace('_',' ')}${log.reason?.takeIf {it.isNotBlank()}?.let {" · ${it.replace('_',' ')}"}.orEmpty()}",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}
    if(logs.isNotEmpty()) TextButton(onClick={context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(resources.getString(R.string.settings_spunto_sync_log),logs.joinToString("\n") {"${noteTime(it.at,locale)} ${it.outcome} ${it.reason.orEmpty()}"}))},modifier=Modifier.heightIn(min=48.dp)) {Text(resources.getString(R.string.settings_copy_diagnostic_log))}
   }
  }
  if(auth!=null) item {AiSettingsSection(ai,online,model::pluginWeb)}
  item {
   SettingsSection(resources.getString(R.string.settings_privacy))
   SettingsSwitchRow(resources.getString(R.string.settings_app_lock),resources.getString(R.string.settings_unlock_with_biometrics_or_the_screen_lock),phoneLock.enabled) {(context as? LockedActivity)?.setPhoneLock(it) ?: feedback.show(resources.getString(R.string.settings_open_this_setting_in_the_main_app))}
  }
  item {
   SettingsSection(resources.getString(R.string.settings_about))
   SettingsRow(resources.getString(R.string.settings_export),resources.getString(R.string.settings_download_all_synced_notes_as_a_zip),onClick={ownerPage("data")})
   SettingsRow(resources.getString(R.string.settings_show_the_guide),resources.getString(R.string.settings_the_four_first_run_steps_again),onClick={onRoute("guide")})
   SettingsRow("Spunto $version".trim(),resources.getString(R.string.settings_free_software_under_the_agpl_3_0))
  }
 }
 if(signOut) AlertDialog(onDismissRequest={signOut=false},title={Text(resources.getString(R.string.settings_sign_out_b1155))},text={Text(signOutText(resources,pending,online))},confirmButton={TextButton(onClick={model.signOut();signOut=false}) {Text(resources.getString(R.string.settings_sign_out))}},dismissButton={TextButton(onClick={signOut=false}) {Text(resources.getString(R.string.settings_cancel))}})
}
/** Online, signing out revokes this phone's credential; offline, it says where the phone stays listed. */
internal fun signOutText(resources:Resources,pending:Int,online:Boolean)=
 (if(pending>0) resources.getQuantityString(R.plurals.settings_sign_out_pending,pending,pending) else resources.getString(R.string.settings_notes_stay)) + " " + resources.getString(if(online) R.string.settings_sign_out_online else R.string.settings_sign_out_offline)

@Composable internal fun SettingsSection(title:String) {
 val resources=LocalContext.current.resources
 Text(title.uppercase(),Modifier.fillMaxWidth().padding(start=SpuntoSpacing.s20,end=SpuntoSpacing.s20,top=SpuntoSpacing.s24,bottom=SpuntoSpacing.s4).semantics {heading()},style=SpuntoType.overline,color=MaterialTheme.colorScheme.onSurfaceVariant)
}
/** A title and a one-line summary in ink-2; tappable when [onClick] is set. [expanded] makes it a disclosure. */
@Composable internal fun SettingsRow(title:String,summary:String?=null,enabled:Boolean=true,onClick:(()->Unit)?=null,expanded:Boolean?=null,trailing:(@Composable ()->Unit)?=null) {
 val resources=LocalContext.current.resources
 val clickable=if(onClick==null) Modifier else Modifier.clickable(enabled=enabled,onClickLabel=null,onClick=onClick).semantics {if(expanded!=null) stateDescription=if(expanded) resources.getString(R.string.settings_expanded) else resources.getString(R.string.settings_collapsed)}
 Row(Modifier.fillMaxWidth().heightIn(min=56.dp).then(clickable).padding(horizontal=SpuntoSpacing.s20,vertical=10.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(SpuntoSpacing.s16)) {
  Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(SpuntoSpacing.s2)) {
   val alpha=if(enabled) 1f else .5f
   Text(title,style=MaterialTheme.typography.bodyLarge,color=MaterialTheme.colorScheme.onSurface.copy(alpha=alpha))
   summary?.takeIf {it.isNotBlank()}?.let {Text(it,style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha=alpha))}
  }
  trailing?.invoke()
 }
}
@Composable internal fun SettingsSwitchRow(title:String,summary:String?,checked:Boolean,enabled:Boolean=true,onChange:(Boolean)->Unit) {
 val resources=LocalContext.current.resources
 Row(Modifier.fillMaxWidth().heightIn(min=56.dp).toggleable(checked,enabled=enabled,role=androidx.compose.ui.semantics.Role.Switch,onValueChange=onChange).padding(horizontal=SpuntoSpacing.s20,vertical=10.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(SpuntoSpacing.s16)) {
  Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(SpuntoSpacing.s2)) {
   val alpha=if(enabled) 1f else .5f
   Text(title,style=MaterialTheme.typography.bodyLarge,color=MaterialTheme.colorScheme.onSurface.copy(alpha=alpha))
   summary?.let {Text(it,style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha=alpha))}
  }
  Switch(checked,null,enabled=enabled,colors=spuntoSwitchColors())
 }
}

/** Switching one on reviews its permissions first (03 §6.4); the same sheet serves the list and a plugin's page. */
@Stable internal class PluginEnableState {
 var id by mutableStateOf<String?>(null);var error by mutableStateOf("")
 fun review(plugin:String) {id=plugin;error=""}
}
@Composable internal fun PluginEnableDialog(model:SpuntoViewModel,state:PluginEnableState,projection:NativePluginCatalog,online:Boolean,busy:Boolean) {
 val resources=LocalContext.current.resources
 val plugin=projection.rows.firstOrNull {it.id==state.id}
 if(plugin!=null && !plugin.removed) NativePluginPermissionDialog(plugin,online,busy,state.error,{state.id=null},{
  model.pluginToggle(plugin,true,plugin.permissions) {result -> when(result) {
   is PluginToggleResult.Applied -> {state.id=null;state.error=""}
   PluginToggleResult.ReviewPermissions -> state.error=resources.getString(R.string.settings_permissions_changed_review_the_current_list_before)
   is PluginToggleResult.Failed -> state.error=result.code
  }}
 },model::pluginWeb)
}

@Composable fun PluginsScreen(model:SpuntoViewModel,onPlugin:(String)->Unit) {
 val resources=LocalContext.current.resources
 val catalog by model.catalog.collectAsState();val manifest by model.manifest.collectAsState();val ai by model.pluginAi.collectAsState()
 val auth by model.auth.collectAsState();val online by model.online.collectAsState()
 val actions=model.pluginManager;val busy by actions.busy.collectAsState();val undo by actions.undo.collectAsState()
 val projection=nativePluginCatalog(catalog,manifest)
 val enable=remember(auth) {PluginEnableState()}
 LaunchedEffect(auth,online) {if(auth!=null && online) model.refreshPluginManager()}
 NativePluginCatalogList(projection,ai,online,busy,undo,{plugin,on -> if(on) enable.review(plugin.id) else model.pluginToggle(plugin,false)},onPlugin,enable::review,{actions.undo(enable::review)},model::pluginWeb)
 PluginEnableDialog(model,enable,projection,online,busy)
}
@Composable internal fun NativePluginCatalogList(projection:NativePluginCatalog,ai:JsonObject,online:Boolean,busy:Boolean,undo:PluginUndo?,onToggle:(NativePluginFacts,Boolean)->Unit,onSettings:(String)->Unit,onReview:(String)->Unit,onUndo:()->Unit,onWeb:(String)->Unit) {
 val resources=LocalContext.current.resources
 var onlyOn by rememberSaveable {mutableStateOf(false)}
 val quiet=MaterialTheme.colorScheme.onSurfaceVariant
 LazyColumn(contentPadding=PaddingValues(horizontal=SpuntoSpacing.s20,vertical=SpuntoSpacing.s16)) {
  item {
   Column(verticalArrangement=Arrangement.spacedBy(SpuntoSpacing.s8)) {
    Text(resources.getString(R.string.settings_everything_beyond_notes_labels_and_search_switch),style=MaterialTheme.typography.bodyMedium)
    Text(resources.getString(R.string.settings_new_plugins_are_added_by_whoever_runs),style=MaterialTheme.typography.bodySmall,color=quiet)
    Row(verticalAlignment=Alignment.CenterVertically) {
     Text(if(ai.isNotEmpty()) pluginAiSummary(resources,ai).joinToString(" · ") else resources.getString(R.string.settings_ai_model_summary_unavailable),Modifier.weight(1f),style=MaterialTheme.typography.bodySmall,color=quiet)
     TextButton(onClick={onWeb("/settings/ai")},enabled=online,modifier=Modifier.heightIn(min=48.dp)) {Text(resources.getString(R.string.settings_ai_settings))}
    }
    FlowRow(horizontalArrangement=Arrangement.spacedBy(SpuntoSpacing.s8)) {FilterChip(!onlyOn,{onlyOn=false},label={Text(resources.getString(R.string.settings_all ,projection.rows.size))});FilterChip(onlyOn,{onlyOn=true},label={Text(resources.getString(R.string.settings_on ,projection.onCount))})}
    if(!online) Text(resources.getString(R.string.settings_changes_need_a_connection),style=MaterialTheme.typography.bodySmall,color=quiet)
    if(!projection.valid) Text(resources.getString(R.string.settings_plugin_catalog_unavailable_connect_to_refresh_it),style=MaterialTheme.typography.bodySmall,color=quiet)
    NativePluginUndo(undo,projection.rows.firstOrNull {it.id==undo?.pluginId}?.let {localizedPluginName(resources,it.raw)} ?: resources.getString(R.string.settings_plugin),online,busy,onUndo)
   }
  }
  pluginGroups.forEach {group ->
   val total=projection.rows.filter {it.category==group};val entries=total.filter {!onlyOn || it.wantedOn}
   if(entries.isNotEmpty()) {
    item(key="group:$group") {Text(resources.getString(R.string.settings_of_on_5ce1c ,pluginCategoryName(resources,group),total.count {it.wantedOn},total.size).uppercase(),Modifier.padding(top=SpuntoSpacing.s24,bottom=SpuntoSpacing.s4).semantics {heading()},style=SpuntoType.overline,color=quiet)}
    items(entries,key={it.id}) {plugin -> NativePluginRow(plugin,online,busy,{onToggle(plugin,it)},{onSettings(plugin.id)},{onReview(plugin.id)},onWeb)}
   }
  }
 }
}

/** A plugin's own page (03 §6.4, R22): header with the switch, its settings, then the host-drawn AI, Run, Activity, Access and data sections. */
@Composable fun PluginScreen(model:SpuntoViewModel,id:String) {
 val resources=LocalContext.current.resources
 val catalog by model.catalog.collectAsState();val manifest by model.manifest.collectAsState()
 val auth by model.auth.collectAsState();val online by model.online.collectAsState()
 val actions=model.pluginManager;val busy by actions.busy.collectAsState();val page by actions.page.collectAsState()
 val projection=nativePluginCatalog(catalog,manifest)
 val plugin=projection.rows.firstOrNull {it.id==id}
 val enable=remember(auth) {PluginEnableState()}
 val epoch by model.pluginReplicaEpochState.collectAsState()
 val commandScope=rememberCoroutineScope()
 val commandOwner=remember(model,commandScope,id) {NativePluginCommandOwner(
  identity={PluginCommandIdentity(model.auth.value,model.pluginReplicaEpoch,id)},
  validate={expected,done -> commandScope.launch {
   var answered=false
   fun answer(allowed:Boolean) {if(!answered) {answered=true;done(allowed)}}
   try {
    val config=expected.credentials as? io.theficos.spunto.data.sync.AuthConfig
    val committed=if(config!=null) io.theficos.spunto.data.sync.readCommittedPluginPublication(model.repository,config) {model.container.auth.config.first()} else null
    if(committed!=null && committed.owner.epoch==expected.epoch) model.container.manifest.withCurrentPublication {
     io.theficos.spunto.core.model.processPluginPublications.withCurrent {owner,_ -> answer(owner==committed.owner && model.auth.value==config && model.container.manifest.enabled(expected.selectedId.orEmpty()))}
    } else answer(false)
   } catch(cancelled:kotlinx.coroutines.CancellationException) {throw cancelled}
   catch(_:Exception) {answer(false);model.message.value=resources.getString(R.string.settings_plugin_result_is_unavailable_refresh_and_try)}
   finally {answer(false)}
  }}
 )}
 val commandPending by commandOwner.pending.collectAsState()
 LaunchedEffect(auth,id,epoch,plugin?.raw?.pluginText("api"),plugin?.raw?.pluginText("version"),plugin?.removed) {commandOwner.reset();actions.select(id)}
 DisposableEffect(actions,id) {onDispose {commandOwner.reset();actions.closePage()}}
 if(plugin==null) {Text(resources.getString(R.string.settings_this_plugin_is_unavailable_sync_to_refresh),Modifier.padding(SpuntoSpacing.s20));return}
 val quiet=MaterialTheme.colorScheme.onSurfaceVariant
 LazyColumn(contentPadding=PaddingValues(horizontal=SpuntoSpacing.s20,vertical=SpuntoSpacing.s8),verticalArrangement=Arrangement.spacedBy(SpuntoSpacing.s12)) {
  item {
   Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(SpuntoSpacing.s16)) {
    Text(localizedPluginName(resources,plugin.raw),Modifier.weight(1f).semantics {heading()},style=MaterialTheme.typography.titleLarge)
    if(!plugin.removed && plugin.pin==EnvironmentPin.Unmanaged && plugin.valid) PluginSwitch(plugin,online,busy) {on -> if(on) enable.review(plugin.id) else model.pluginToggle(plugin,false)}
   }
   Text(localizedPluginDescription(resources,plugin.raw),Modifier.padding(top=SpuntoSpacing.s4),style=MaterialTheme.typography.bodyMedium)
   Text(listOf(pluginCategoryName(resources,plugin.category),plugin.origin(resources),plugin.raw.pluginText("version").orEmpty()).filter {it.isNotBlank()}.joinToString(" · "),Modifier.padding(top=SpuntoSpacing.s4),style=MaterialTheme.typography.bodySmall,color=quiet)
   if(plugin.permissionReviewRequired) TextButton(onClick={enable.review(plugin.id)},enabled=online && !busy && plugin.canEnable,modifier=Modifier.heightIn(min=48.dp)) {Text(resources.getString(R.string.settings_review_updated_permissions))}
   if(!online) Text(resources.getString(R.string.settings_changes_need_a_connection),Modifier.padding(top=SpuntoSpacing.s4),style=MaterialTheme.typography.bodySmall,color=quiet)
  }
  if(!plugin.removed && plugin.id=="dictation") item {DictationSettings(model,readOnly=!online)}
  if(!plugin.removed && page.id==plugin.id) item {Column(verticalArrangement=Arrangement.spacedBy(SpuntoSpacing.s8)) {
   if(page.loading && page.page.schema.isEmpty()) Text(resources.getString(R.string.settings_loading_settings),style=MaterialTheme.typography.bodySmall,color=quiet)
   if(page.error.isNotEmpty()) Text(page.error,style=MaterialTheme.typography.bodySmall,color=quiet)
   NativePluginSettingsValues(plugin,page.page,online,model::pluginWeb)
  }}
  // Facts read as quiet body text under their headings; buttons keep their own colour.
  item {CompositionLocalProvider(LocalContentColor provides quiet) {ProvideTextStyle(MaterialTheme.typography.bodyMedium) {Column(verticalArrangement=Arrangement.spacedBy(SpuntoSpacing.s8)) {NativePluginDetailFacts(plugin,online,commandPending,{command ->
   if(online && plugin.activeContribution) commandOwner.start({deliver,complete ->
    runCommand(model,plugin.id,command.pluginText("id").orEmpty(),JsonObject(emptyMap()),deliver,onComplete=complete,scope=commandScope)
   },{model.remote.value=it})
  },model::pluginWeb)}}}}
 }
 PluginEnableDialog(model,enable,projection,online,busy)
}

@Composable fun DictationSettings(model:SpuntoViewModel,readOnly:Boolean=false) {
 val resources=LocalContext.current.resources
 val context=model.container.context; val scope=rememberCoroutineScope();val feedback=LocalFeedback.current
 val device=remember { DeviceSpeechPreferences(context) }; val settings by device.settings.collectAsState(SpeechPreferences())
 val quiet=MaterialTheme.colorScheme.onSurfaceVariant
 Column(verticalArrangement=Arrangement.spacedBy(SpuntoSpacing.s8)) {
  PluginHeading(resources.getString(R.string.settings_on_this_phone))
  if(!PlatformSpeechEngine.onDeviceAvailable(context)) {
   Text(resources.getString(R.string.settings_this_phone_has_no_on_device_recognizer),style=MaterialTheme.typography.bodyMedium)
   var consent by remember { mutableStateOf(false) }
   Row(horizontalArrangement=Arrangement.spacedBy(SpuntoSpacing.s16),verticalAlignment=Alignment.CenterVertically) { Text(resources.getString(R.string.settings_use_online_recognition),Modifier.weight(1f),style=MaterialTheme.typography.bodyMedium); Switch(settings.onlineConsent,{if(it) consent=true else scope.launch { device.update(online=false) }},enabled=!readOnly,colors=spuntoSwitchColors(),modifier=Modifier.semantics {contentDescription=resources.getString(R.string.settings_use_online_recognition)}) }
   if(consent && !readOnly) AlertDialog(onDismissRequest={consent=false},title={Text(resources.getString(R.string.settings_use_online_recognition_2b727))},text={Text(resources.getString(R.string.settings_audio_goes_to_the_phone_s_speech))},confirmButton={TextButton(onClick={scope.launch { device.update(online=true) };consent=false}) { Text(resources.getString(R.string.settings_allow_on_this_phone)) }},dismissButton={TextButton(onClick={consent=false}) { Text(resources.getString(R.string.settings_cancel)) }})
  } else Text(resources.getString(R.string.settings_audio_stays_on_this_phone_dictation_keeps),style=MaterialTheme.typography.bodyMedium,color=quiet)
  Text(resources.getString(R.string.settings_language),style=MaterialTheme.typography.bodyMedium)
  val languages=listOf("it-IT" to resources.getString(R.string.settings_italian),"en-US" to resources.getString(R.string.settings_english))
  SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
   languages.forEachIndexed { index,(tag,label) -> SegmentedButton(selected=settings.language==tag,onClick={scope.launch { device.update(language=tag); val result=kotlinx.coroutines.withTimeoutOrNull(5000) { PlatformSpeechEngine.languageSupport(context,tag) }; feedback.show(when(result) { LanguageAvailability.Ready -> resources.getString(R.string.settings_is_ready_offline ,label); LanguageAvailability.Download -> resources.getString(R.string.settings_needs_a_one_time_download_to_work ,label); else -> resources.getString(R.string.settings_couldn_t_check_whether_works_offline ,label) }) }},shape=SegmentedButtonDefaults.itemShape(index,languages.size),enabled=!readOnly) { Text(label) } }
  }
  TextButton(onClick={scope.launch { feedback.show(resources.getString(R.string.settings_download_requested_checking_the_phone_s_language)); val result=PlatformSpeechEngine.download(context,settings.language); feedback.show(if(result==LanguageAvailability.Ready) resources.getString(R.string.settings_language_ready_offline) else resources.getString(R.string.settings_the_download_is_still_pending_try_again)) }},enabled=!readOnly,modifier=Modifier.heightIn(min=48.dp)) { Text(resources.getString(R.string.settings_download_for_offline_use)) }
 }
}
