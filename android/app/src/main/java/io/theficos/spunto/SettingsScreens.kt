@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class,androidx.compose.material3.ExperimentalMaterial3Api::class)
package io.theficos.spunto

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
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import io.theficos.spunto.core.design.SpuntoType
import io.theficos.spunto.core.plugins.*
import io.theficos.spunto.data.sync.SyncStatus
import io.theficos.spunto.plugins.dictation.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*

/** 03 A9: one page of sections. Appearance, Plugins and each plugin's page open on their own; owner-only pages open in the web app. */
@Composable fun SettingsScreen(model:SpuntoViewModel,appearance:String,onRoute:(String)->Unit) {
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
  val target=ownerWebSettingsTarget(model.auth.value,replica?.serverUrl,model.online.value,section,needsSignIn)
  val url=target.url ?: return feedback.show(target.message)
  try {context.startActivity(Intent(Intent.ACTION_VIEW,Uri.parse(url)))} catch(_:ActivityNotFoundException) {feedback.show("No browser is available to open the web app.")}
 }
 LazyColumn(contentPadding=PaddingValues(bottom=24.dp)) {
  item {
   // Appearance and Plugins open their own pages, so their rows lead the page without a heading that repeats them.
   Spacer(Modifier.height(8.dp))
   SettingsRow("Appearance",appearance,onClick={onRoute("appearance")})
   SettingsRow("Plugins",when {plugins.valid -> "${plugins.onCount} of ${plugins.rows.size} on";auth==null -> "Connect your server to choose plugins";else -> "Sync to load your plugins"},onClick={onRoute("plugins")})
  }
  item {
   SettingsSection("Server")
   val config=auth
   if(config==null) SettingsRow("Connect to your server","Sign in or scan a pairing code. Your notes stay on this phone.",onClick={onRoute("login")})
   else {
    SettingsRow(config.serverUrl.removePrefix("https://"),"This phone is “${config.name}”")
    if(needsSignIn) SettingsRow("Sign in again","Sync is paused until this phone signs in again.",onClick={onRoute("login")})
    SettingsRow("Devices","Remove this phone or others in the web app",enabled=online,onClick={model.pluginWeb("/settings/devices")})
    SettingsRow("Profile","Your display name, in the web app",onClick={ownerPage("profile")})
    SettingsRow("Sign out","Your notes stay on this phone",onClick={signOut=true})
   }
  }
  item {
   SettingsSection("Sync")
   val running=status==SyncStatus.Running
   SettingsRow("Sync now",syncSummary(if(auth==null) SyncStatus.SignedOut else status,pending,online,logs.firstOrNull {it.outcome=="complete"}?.at),enabled=auth!=null && online && !needsSignIn && !running,onClick={model.synchronize()}) {if(running) CircularProgressIndicator(Modifier.size(20.dp),strokeWidth=2.dp)}
   SettingsRow("Sync activity","Recent sync attempts, for troubleshooting",onClick={activity=!activity},expanded=activity)
   if(activity) Column(Modifier.padding(horizontal=20.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
    if(logs.isEmpty()) Text("No sync activity yet.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
    logs.take(20).forEach {log -> Text("${noteTime(log.at)} · ${log.outcome.replace('_',' ')}${log.reason?.takeIf {it.isNotBlank()}?.let {" · ${it.replace('_',' ')}"}.orEmpty()}",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}
    if(logs.isNotEmpty()) TextButton(onClick={context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Spunto sync log",logs.joinToString("\n") {"${noteTime(it.at)} ${it.outcome} ${it.reason.orEmpty()}"}))},modifier=Modifier.heightIn(min=48.dp)) {Text("Copy diagnostic log")}
   }
  }
  if(auth!=null) item {AiSettingsSection(ai,online,model::pluginWeb)}
  item {
   SettingsSection("Privacy")
   SettingsSwitchRow("App lock","Unlock with biometrics or the screen lock. This phone only.",phoneLock.enabled) {(context as? LockedActivity)?.setPhoneLock(it) ?: feedback.show("Open this setting in the main app.")}
  }
  item {
   SettingsSection("About")
   SettingsRow("Export","Download all synced notes as a ZIP, in the web app",onClick={ownerPage("data")})
   SettingsRow("Show the guide","The four first-run steps again",onClick={onRoute("guide")})
   SettingsRow("Spunto $version".trim(),"Free software under the AGPL-3.0 licence")
  }
 }
 if(signOut) AlertDialog(onDismissRequest={signOut=false},title={Text("Sign out?")},text={Text(signOutText(pending,online))},confirmButton={TextButton(onClick={model.signOut();signOut=false}) {Text("Sign out")}},dismissButton={TextButton(onClick={signOut=false}) {Text("Cancel")}})
}
/** Online, signing out revokes this phone's credential; offline, it says where the phone stays listed. */
internal fun signOutText(pending:Int,online:Boolean)=(if(pending>0) "${if(pending==1) "1 change hasn't" else "$pending changes haven't"} synced yet. ${if(pending==1) "It stays" else "They stay"} on this phone and ${if(pending==1) "syncs" else "sync"} after you sign in again." else "Your notes stay on this phone.")+
 if(online) " This phone also leaves Devices in the web app." else " This phone stays listed under Devices in the web app until you remove it there."

@Composable internal fun SettingsSection(title:String) {
 Text(title.uppercase(),Modifier.fillMaxWidth().padding(start=20.dp,end=20.dp,top=24.dp,bottom=4.dp).semantics {heading()},style=SpuntoType.overline,color=MaterialTheme.colorScheme.onSurfaceVariant)
}
/** A title and a one-line summary in ink-2; tappable when [onClick] is set. [expanded] makes it a disclosure. */
@Composable internal fun SettingsRow(title:String,summary:String?=null,enabled:Boolean=true,onClick:(()->Unit)?=null,expanded:Boolean?=null,trailing:(@Composable ()->Unit)?=null) {
 val clickable=if(onClick==null) Modifier else Modifier.clickable(enabled=enabled,onClickLabel=null,onClick=onClick).semantics {if(expanded!=null) stateDescription=if(expanded) "Expanded" else "Collapsed"}
 Row(Modifier.fillMaxWidth().heightIn(min=56.dp).then(clickable).padding(horizontal=20.dp,vertical=10.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(16.dp)) {
  Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(2.dp)) {
   val alpha=if(enabled) 1f else .5f
   Text(title,style=MaterialTheme.typography.bodyLarge,color=MaterialTheme.colorScheme.onSurface.copy(alpha=alpha))
   summary?.takeIf {it.isNotBlank()}?.let {Text(it,style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha=alpha))}
  }
  trailing?.invoke()
 }
}
@Composable internal fun SettingsSwitchRow(title:String,summary:String?,checked:Boolean,enabled:Boolean=true,onChange:(Boolean)->Unit) {
 Row(Modifier.fillMaxWidth().heightIn(min=56.dp).toggleable(checked,enabled=enabled,role=androidx.compose.ui.semantics.Role.Switch,onValueChange=onChange).padding(horizontal=20.dp,vertical=10.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(16.dp)) {
  Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(2.dp)) {
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
 val plugin=projection.rows.firstOrNull {it.id==state.id}
 if(plugin!=null && !plugin.removed) NativePluginPermissionDialog(plugin,online,busy,state.error,{state.id=null},{
  model.pluginToggle(plugin,true,plugin.permissions) {result -> when(result) {
   is PluginToggleResult.Applied -> {state.id=null;state.error=""}
   PluginToggleResult.ReviewPermissions -> state.error="Permissions changed. Review the current list before accepting."
   is PluginToggleResult.Failed -> state.error=result.code
  }}
 },model::pluginWeb)
}

@Composable fun PluginsScreen(model:SpuntoViewModel,onPlugin:(String)->Unit) {
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
 var onlyOn by rememberSaveable {mutableStateOf(false)}
 val quiet=MaterialTheme.colorScheme.onSurfaceVariant
 LazyColumn(contentPadding=PaddingValues(horizontal=20.dp,vertical=16.dp)) {
  item {
   Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
    Text("Everything beyond notes, labels and search. Switch one on and it takes its place; switch it off and it leaves the screen, while its data and any notes it wrote stay.",style=MaterialTheme.typography.bodyMedium)
    Text("New plugins are added by whoever runs the server.",style=MaterialTheme.typography.bodySmall,color=quiet)
    Row(verticalAlignment=Alignment.CenterVertically) {
     Text(if(ai.isNotEmpty()) pluginAiSummary(ai).joinToString(" · ") else "AI model summary unavailable.",Modifier.weight(1f),style=MaterialTheme.typography.bodySmall,color=quiet)
     TextButton(onClick={onWeb("/settings/ai")},enabled=online,modifier=Modifier.heightIn(min=48.dp)) {Text("AI settings")}
    }
    FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {FilterChip(!onlyOn,{onlyOn=false},label={Text("All ${projection.rows.size}")});FilterChip(onlyOn,{onlyOn=true},label={Text("On ${projection.onCount}")})}
    if(!online) Text("Changes need a connection.",style=MaterialTheme.typography.bodySmall,color=quiet)
    if(!projection.valid) Text("Plugin catalog unavailable. Connect to refresh it.",style=MaterialTheme.typography.bodySmall,color=quiet)
    NativePluginUndo(undo,projection.rows.firstOrNull {it.id==undo?.pluginId}?.name ?: "Plugin",online,busy,onUndo)
   }
  }
  pluginGroups.forEach {group ->
   val total=projection.rows.filter {it.category==group};val entries=total.filter {!onlyOn || it.wantedOn}
   if(entries.isNotEmpty()) {
    item(key="group:$group") {Text("${group.replaceFirstChar {it.uppercase()}} · ${total.count {it.wantedOn}} of ${total.size} on".uppercase(),Modifier.padding(top=24.dp,bottom=4.dp).semantics {heading()},style=SpuntoType.overline,color=quiet)}
    items(entries,key={it.id}) {plugin -> NativePluginRow(plugin,online,busy,{onToggle(plugin,it)},{onSettings(plugin.id)},{onReview(plugin.id)},onWeb)}
   }
  }
 }
}

/** A plugin's own page (03 §6.4, R22): header with the switch, its settings, then the host-drawn AI, Run, Activity, Access and data sections. */
@Composable fun PluginScreen(model:SpuntoViewModel,id:String) {
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
   catch(_:Exception) {answer(false);model.message.value="Plugin result is unavailable. Refresh and try again."}
   finally {answer(false)}
  }}
 )}
 val commandPending by commandOwner.pending.collectAsState()
 LaunchedEffect(auth,id,epoch,plugin?.raw?.pluginText("api"),plugin?.raw?.pluginText("version"),plugin?.removed) {commandOwner.reset();actions.select(id)}
 DisposableEffect(actions,id) {onDispose {commandOwner.reset();actions.closePage()}}
 if(plugin==null) {Text("This plugin is unavailable. Sync to refresh your plugins.",Modifier.padding(20.dp));return}
 val quiet=MaterialTheme.colorScheme.onSurfaceVariant
 LazyColumn(contentPadding=PaddingValues(horizontal=20.dp,vertical=8.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
  item {
   Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(16.dp)) {
    Text(plugin.name,Modifier.weight(1f).semantics {heading()},style=MaterialTheme.typography.titleLarge)
    if(!plugin.removed && plugin.pin==EnvironmentPin.Unmanaged && plugin.valid) PluginSwitch(plugin,online,busy) {on -> if(on) enable.review(plugin.id) else model.pluginToggle(plugin,false)}
   }
   Text(plugin.description,Modifier.padding(top=4.dp),style=MaterialTheme.typography.bodyMedium)
   Text(listOf(plugin.category.replaceFirstChar {it.uppercase()},plugin.origin,plugin.raw.pluginText("version").orEmpty()).filter {it.isNotBlank()}.joinToString(" · "),Modifier.padding(top=4.dp),style=MaterialTheme.typography.bodySmall,color=quiet)
   if(plugin.permissionReviewRequired) TextButton(onClick={enable.review(plugin.id)},enabled=online && !busy && plugin.canEnable,modifier=Modifier.heightIn(min=48.dp)) {Text("Review updated permissions")}
   if(!online) Text("Changes need a connection.",Modifier.padding(top=4.dp),style=MaterialTheme.typography.bodySmall,color=quiet)
  }
  if(!plugin.removed && plugin.id=="dictation") item {DictationSettings(model,readOnly=!online)}
  if(!plugin.removed && page.id==plugin.id) item {Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
   if(page.loading && page.page.schema.isEmpty()) Text("Loading settings…",style=MaterialTheme.typography.bodySmall,color=quiet)
   if(page.error.isNotEmpty()) Text(page.error,style=MaterialTheme.typography.bodySmall,color=quiet)
   NativePluginSettingsValues(plugin,page.page,online,model::pluginWeb)
  }}
  // Facts read as quiet body text under their headings; buttons keep their own colour.
  item {CompositionLocalProvider(LocalContentColor provides quiet) {ProvideTextStyle(MaterialTheme.typography.bodyMedium) {Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {NativePluginDetailFacts(plugin,online,commandPending,{command ->
   if(online && plugin.activeContribution) commandOwner.start({deliver,complete ->
    runCommand(model,plugin.id,command.pluginText("id").orEmpty(),JsonObject(emptyMap()),deliver,onComplete=complete,scope=commandScope)
   },{model.remote.value=it})
  },model::pluginWeb)}}}}
 }
 PluginEnableDialog(model,enable,projection,online,busy)
}

@Composable fun DictationSettings(model:SpuntoViewModel,readOnly:Boolean=false) {
 val context=model.container.context; val scope=rememberCoroutineScope();val feedback=LocalFeedback.current
 val device=remember { DeviceSpeechPreferences(context) }; val settings by device.settings.collectAsState(SpeechPreferences())
 val quiet=MaterialTheme.colorScheme.onSurfaceVariant
 Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
  PluginHeading("On this phone")
  if(!PlatformSpeechEngine.onDeviceAvailable(context)) {
   Text("This phone has no on-device recognizer. The mic stays hidden until you allow online recognition.",style=MaterialTheme.typography.bodyMedium)
   var consent by remember { mutableStateOf(false) }
   Row(horizontalArrangement=Arrangement.spacedBy(16.dp),verticalAlignment=Alignment.CenterVertically) { Text("Use online recognition",Modifier.weight(1f),style=MaterialTheme.typography.bodyMedium); Switch(settings.onlineConsent,{if(it) consent=true else scope.launch { device.update(online=false) }},enabled=!readOnly,colors=spuntoSwitchColors(),modifier=Modifier.semantics {contentDescription="Use online recognition"}) }
   if(consent && !readOnly) AlertDialog(onDismissRequest={consent=false},title={Text("Use online recognition?")},text={Text("Audio goes to the phone's speech provider, usually Google. It does not go to your Spunto server.")},confirmButton={TextButton(onClick={scope.launch { device.update(online=true) };consent=false}) { Text("Allow on this phone") }},dismissButton={TextButton(onClick={consent=false}) { Text("Cancel") }})
  } else Text("Audio stays on this phone. Dictation keeps no recordings.",style=MaterialTheme.typography.bodyMedium,color=quiet)
  Text("Language",style=MaterialTheme.typography.bodyMedium)
  val languages=listOf("it-IT" to "Italian","en-US" to "English")
  SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
   languages.forEachIndexed { index,(tag,label) -> SegmentedButton(selected=settings.language==tag,onClick={scope.launch { device.update(language=tag); val result=kotlinx.coroutines.withTimeoutOrNull(5000) { PlatformSpeechEngine.languageSupport(context,tag) }; feedback.show(when(result) { LanguageAvailability.Ready -> "$label is ready offline."; LanguageAvailability.Download -> "$label needs a one-time download to work offline."; else -> "Couldn't check whether $label works offline." }) }},shape=SegmentedButtonDefaults.itemShape(index,languages.size),enabled=!readOnly) { Text(label) } }
  }
  TextButton(onClick={scope.launch { feedback.show("Download requested. Checking the phone's language packs…"); val result=PlatformSpeechEngine.download(context,settings.language); feedback.show(if(result==LanguageAvailability.Ready) "Language ready offline." else "The download is still pending. Try again later.") }},enabled=!readOnly,modifier=Modifier.heightIn(min=48.dp)) { Text("Download for offline use") }
 }
}
