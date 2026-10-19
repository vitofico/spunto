@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package io.theficos.spunto

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.theficos.spunto.core.plugins.*
import io.theficos.spunto.plugins.dictation.*
import kotlinx.serialization.json.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first

@Composable fun PluginsScreen(model:SpuntoViewModel) {
 val catalog by model.catalog.collectAsState();val manifest by model.manifest.collectAsState();val ai by model.pluginAi.collectAsState()
 val auth by model.auth.collectAsState();val online by model.online.collectAsState()
 val actions=model.pluginManager;val busy by actions.busy.collectAsState();val undo by actions.undo.collectAsState();val page by actions.page.collectAsState()
 val projection=nativePluginCatalog(catalog,manifest)
 var onlyOn by remember {mutableStateOf(false)};var enableId by remember(auth) {mutableStateOf<String?>(null)};var enableError by remember {mutableStateOf("")}
 var selectedId by remember(auth) {mutableStateOf<String?>(null)}
 val epoch by model.pluginReplicaEpochState.collectAsState()
 val commandScope=rememberCoroutineScope()
 val commandOwner=remember(model,commandScope) {NativePluginCommandOwner(
  identity={PluginCommandIdentity(model.auth.value,model.pluginReplicaEpoch,selectedId)},
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
 fun open(id:String) {commandOwner.reset();selectedId=id}
 fun close() {commandOwner.reset();selectedId=null;actions.closePage()}
 fun review(id:String) {enableId=id;enableError=""}
 fun toggle(plugin:NativePluginFacts,on:Boolean) {if(on) review(plugin.id) else model.pluginToggle(plugin,false)}
 LaunchedEffect(auth,online) {if(auth!=null && online) model.refreshPluginManager()}
 val selectedContract=projection.rows.firstOrNull {it.id==selectedId}
 LaunchedEffect(auth,selectedId,epoch,selectedContract?.raw?.pluginText("api"),selectedContract?.raw?.pluginText("version"),selectedContract?.removed) {
  commandOwner.reset()
  if(selectedId!=null) actions.select(selectedId!!) else actions.closePage()
 }
 DisposableEffect(actions) {onDispose {commandOwner.reset();actions.closePage()}}
 LazyColumn(contentPadding=PaddingValues(20.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
  item {
   Text("Everything beyond notes, labels and search. Switch one on and it takes its place; switch it off and it leaves the screen, while its data and any notes it wrote stay.")
   Text("New plugins are added by whoever runs the server.",style=MaterialTheme.typography.bodySmall)
   if(ai.isNotEmpty()) pluginAiSummary(ai).forEach {Text(it,style=MaterialTheme.typography.bodySmall)} else Text("AI model summary unavailable.",style=MaterialTheme.typography.bodySmall)
   TextButton(onClick={model.pluginWeb("/settings/ai")},enabled=online) {Text("AI settings in the web app · owner sign-in")}
   FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {FilterChip(!onlyOn,{onlyOn=false},label={Text("All ${projection.rows.size}")});FilterChip(onlyOn,{onlyOn=true},label={Text("On ${projection.onCount}")})}
   if(!online) Text("Changes need a connection.")
   if(!projection.valid) Text("Plugin catalog unavailable. Connect to refresh it.")
   NativePluginUndo(undo,online,busy) {actions.undo(::review)}
  }
  pluginGroups.forEach {group ->
   val total=projection.rows.filter {it.category==group};val entries=total.filter {!onlyOn || it.wantedOn}
   if(entries.isNotEmpty()) {
    item {Text("${group.replaceFirstChar {it.uppercase()}} · ${total.count {it.wantedOn}} of ${total.size} on",style=MaterialTheme.typography.labelSmall)}
    items(entries,key={it.id}) {plugin -> NativePluginRow(plugin,online,busy,{toggle(plugin,it)},{open(plugin.id)},{review(plugin.id)},model::pluginWeb)}
   }
  }
 }
 val enable=projection.rows.firstOrNull {it.id==enableId}
 if(enable!=null && !enable.removed) NativePluginPermissionDialog(enable,online,busy,enableError,{enableId=null},{
  model.pluginToggle(enable,true,enable.permissions) {result -> when(result) {
   is PluginToggleResult.Applied -> {enableId=null;enableError=""}
   PluginToggleResult.ReviewPermissions -> enableError="Permissions changed. Review the current list before accepting."
   is PluginToggleResult.Failed -> enableError=result.code
  }}
 },model::pluginWeb)
 val selected=projection.rows.firstOrNull {it.id==selectedId}
 selected?.let {plugin -> AlertDialog(onDismissRequest=::close,title={Text(plugin.name)},text={LazyColumn(verticalArrangement=Arrangement.spacedBy(16.dp)) {
  item {Text(plugin.description);Text("${plugin.category.replaceFirstChar {it.uppercase()}} · ${plugin.origin} · ${plugin.raw.pluginText("version").orEmpty()}",style=MaterialTheme.typography.bodySmall)
   if(!plugin.removed && plugin.pin==EnvironmentPin.Unmanaged) Switch(plugin.wantedOn,{toggle(plugin,it)},enabled=online && !busy && (plugin.canDisable || plugin.canEnable),modifier=Modifier.semantics {contentDescription="${plugin.name} wanted on"})
   if(plugin.permissionReviewRequired) TextButton(onClick={review(plugin.id)},enabled=online && !busy && plugin.canEnable) {Text("Review updated permissions")}
   if(!online) Text("Changes need a connection.")
  }
  if(!plugin.removed && plugin.id=="dictation") item {DictationSettings(model,readOnly=!online)}
  if(!plugin.removed) item {if(page.id==plugin.id) {
   if(page.loading) Text("Loading settings…")
   if(page.error.isNotEmpty()) Text(page.error)
   NativePluginSettingsValues(plugin,page.page,online,model::pluginWeb)
  }}
  item {NativePluginDetailFacts(plugin,online,commandPending,{command ->
   if(online && plugin.activeContribution) commandOwner.start({deliver,complete ->
    runCommand(model,plugin.id,command.pluginText("id").orEmpty(),JsonObject(emptyMap()),deliver,onComplete=complete,scope=commandScope)
   },{model.remote.value=it})
  },model::pluginWeb)}
 }},confirmButton={TextButton(onClick=::close) {Text("Done")}})}
}
@Composable fun DictationSettings(model:SpuntoViewModel,readOnly:Boolean=false) {
 val context=model.container.context; val scope=rememberCoroutineScope()
 val device=remember { DeviceSpeechPreferences(context) }; val settings by device.settings.collectAsState(SpeechPreferences())
 Column {
  Text("This phone only")
  if(readOnly) Text("Changes need a connection.")
  if(!PlatformSpeechEngine.onDeviceAvailable(context)) {
   Text("This phone has no on-device recognizer. The mic stays hidden until you allow online recognition.")
   var consent by remember { mutableStateOf(false) }
   Row(horizontalArrangement=Arrangement.spacedBy(16.dp),verticalAlignment=Alignment.CenterVertically) { Text("Use online recognition",Modifier.weight(1f),style=MaterialTheme.typography.bodyMedium); Switch(settings.onlineConsent,{if(!readOnly) {if(it) consent=true else scope.launch { device.update(online=false) }}},enabled=!readOnly,modifier=Modifier.semantics {contentDescription="Use online recognition"}) }
   if(consent && !readOnly) AlertDialog(onDismissRequest={consent=false},title={Text("Use online recognition?")},text={Text("Audio goes to the phone's speech provider, usually Google. It does not go to your Spunto server.")},confirmButton={TextButton(onClick={scope.launch { device.update(online=true) };consent=false}) { Text("Allow on this phone") }},dismissButton={TextButton(onClick={consent=false}) { Text("Cancel") }})
  } else Text("Audio stays on this phone. Dictation keeps no recordings.")
  FlowRow(horizontalArrangement=Arrangement.spacedBy(4.dp)) { listOf("it-IT" to "Italian","en-US" to "English").forEach { (tag,label) -> TextButton(onClick={scope.launch { device.update(language=tag); val result=kotlinx.coroutines.withTimeoutOrNull(5000) { PlatformSpeechEngine.languageSupport(context,tag) }; model.message.value=when(result) { LanguageAvailability.Ready -> "$label is ready offline."; LanguageAvailability.Download -> "$label needs a one-time download to work offline."; else -> "$label availability could not be confirmed." } }},enabled=!readOnly) { Text(label) } } }
  TextButton(onClick={scope.launch { model.message.value="Download requested. Checking the phone's language packs…"; val result=PlatformSpeechEngine.download(context,settings.language); model.message.value=if(result==LanguageAvailability.Ready) "Language ready offline." else "Download is still pending. Try again later." }},enabled=!readOnly) { Text("Download offline language") }
 }
}
@Composable fun DevicesScreen(model:SpuntoViewModel,onLogin:()->Unit) {
 val auth by model.auth.collectAsState(); val data by model.remote.collectAsState(); val pending by model.pending.collectAsState(); var signout by remember { mutableStateOf(false) }
 val phoneLock by model.container.appLock.state.collectAsState()
 val lockActivity=androidx.compose.ui.platform.LocalContext.current as? LockedActivity

 LazyColumn(contentPadding=PaddingValues(20.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
  item { Text(auth?.serverUrl ?: "No server connected",style=MaterialTheme.typography.titleLarge); Text("${auth?.name ?: "This phone"} · $pending notes waiting to sync"); TextButton(onClick=onLogin) { Text(if(auth==null) "Sign in or pair" else "Pair or sign in again") } }
  item { Column(verticalArrangement=Arrangement.spacedBy(8.dp)) { Row(horizontalArrangement=Arrangement.spacedBy(16.dp),verticalAlignment=Alignment.CenterVertically) { Text("Unlock with biometrics or screen lock",Modifier.weight(1f),style=MaterialTheme.typography.bodyMedium);Switch(phoneLock.enabled,{lockActivity?.setPhoneLock(it) ?: run {model.message.value="Open this setting in the main app."}},modifier=Modifier.semantics {contentDescription="Phone app lock"}) };Text("This phone only. Notes need an unlock when you return. Background sync continues; no owner password is stored.",style=MaterialTheme.typography.bodySmall) } }
  if(auth!=null) item { TextButton(onClick={model.container.context.startActivity(Intent(Intent.ACTION_VIEW,android.net.Uri.parse(auth!!.serverUrl+"/settings/devices")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))}) { Text("Manage devices in the web app") } }

  if(auth!=null) item { TextButton(onClick={signout=true}) { Text("Sign out") } }
 }
 if(signout) AlertDialog(onDismissRequest={signout=false},title={Text("Sign out?")},text={Text(if(pending>0) "$pending changes have not synced. They stay on this phone and will sync after you sign in again." else "Your local notes stay on this phone.")},confirmButton={TextButton(onClick={model.signOut();signout=false}) { Text("Sign out") }},dismissButton={TextButton(onClick={signout=false}) { Text("Cancel") }})
}
@Composable fun StatusScreen(model:SpuntoViewModel) {
 val status by model.status.collectAsState(); val notes by model.notes.collectAsState(); val labels by model.labels.collectAsState(); val pending by model.pending.collectAsState()
 val data by model.remote.collectAsState(); val logs by model.repository.dao.logs().collectAsState(emptyList()); val auth by model.auth.collectAsState()
 LaunchedEffect(auth) { if(auth!=null) model.fetch("/api/v1/ai") }
 LazyColumn(contentPadding=PaddingValues(20.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
  item { Text("Status",style=MaterialTheme.typography.titleLarge); Text(status.toString()); Text("${notes.size} notes · ${labels.size} labels · ${pending} changes waiting to sync"); TextButton(onClick={model.synchronize();model.fetch("/api/v1/ai")}) { Text("Sync and refresh") } }
  items(data.entries.toList()) { (key,value) -> Column { Text(key.replace('_',' ').replaceFirstChar { it.uppercase() },style=MaterialTheme.typography.titleSmall); Text(value.toString().take(3000)) } }
  item { Text("Sync log",style=MaterialTheme.typography.titleLarge); TextButton(onClick={val manager=model.container.context.getSystemService(ClipboardManager::class.java);manager.setPrimaryClip(ClipData.newPlainText("Spunto sync log",logs.joinToString("\n") { "${noteTime(it.at)} ${it.outcome} ${it.reason.orEmpty()}" }))}) { Text("Copy log") } }
  items(logs) { log -> Text("${noteTime(log.at)} · ${log.outcome} · ${log.reason.orEmpty()}",style=MaterialTheme.typography.bodySmall) }
 }
}
