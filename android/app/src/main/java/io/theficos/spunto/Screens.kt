@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package io.theficos.spunto

import android.content.*
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import androidx.compose.foundation.clickable
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.activity.compose.BackHandler
import androidx.paging.compose.collectAsLazyPagingItems
import io.theficos.spunto.core.design.*
import io.theficos.spunto.core.model.*
import io.theficos.spunto.core.plugins.*
import io.theficos.spunto.data.sync.*
import io.theficos.spunto.data.local.*
import io.theficos.spunto.plugins.quickcapture.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import java.time.*
import java.time.format.DateTimeFormatter

fun noteTime(at:Long)=Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("d MMM · HH:mm"))
fun payload(raw:String):JsonObject=runCatching { Json.parseToJsonElement(raw).jsonObject }.getOrDefault(JsonObject(emptyMap()))
@OptIn(ExperimentalMaterial3Api::class)
@Composable fun SpuntoApp(model:SpuntoViewModel,initialNoteId:String?=null,guideEligible:Boolean=false) {
 var route by rememberSaveable(initialNoteId) { mutableStateOf(initialNoteId?.let { "note:$it" } ?: "timeline") }
 val guideStore=remember {FirstRunGuideStore(firstRunGuidePreferences(model.container.context))}
 var theme by remember { mutableStateOf(model.container.preferences.getString("theme","carta")!!) }
 var themeOverride by remember { mutableStateOf(model.container.preferences.contains("theme")) }
 var accent by remember { mutableStateOf(model.container.preferences.getString("theme-accent",null)) }
 var mode by remember { mutableStateOf(model.container.preferences.getString("mode","system")!!) }
 var black by remember { mutableStateOf(model.container.preferences.getBoolean("black",false)) }
 var wallpaper by remember { mutableStateOf(model.container.preferences.getBoolean("wallpaper",false)) }
 var readingFace by remember { mutableStateOf(normalizedReadingFace(model.container.preferences.getString("reading-face","serif").orEmpty())) }
 var readingSize by remember { mutableStateOf(normalizedReadingSize(model.container.preferences.getString("reading-size","M").orEmpty())) }
 val auth by model.auth.collectAsState(); val reauth by model.reauthenticate.collectAsState(); val sync by model.status.collectAsState(); val message by model.message.collectAsState(); val pending by model.pending.collectAsState()
 var navigationOpen by remember { mutableStateOf(false) }
 val navigationLabels by model.labels.collectAsState()
 val replicaState by model.repository.dao.observeState().collectAsState(initial=null)
 val manifest=model.manifest.collectAsState().value
 val manifestRaw=remember(manifest) { manifest.toString() }
 val manifestFingerprint=remember(manifestRaw) { themeManifestFingerprint(manifestRaw) }
 var validatedManifest by remember { mutableStateOf<Pair<String,ThemeManifest>?>(null) }
 val appearanceManifest=validatedManifest?.takeIf { it.first==manifestFingerprint }?.second
 LaunchedEffect(manifestRaw) { validatedManifest=manifestFingerprint to withContext(Dispatchers.Default) { decodeThemeManifest(manifestRaw) } }
 val online by model.online.collectAsState()
 LaunchedEffect(manifest) {
  model.container.manifest.withCurrentPublication {
  val quick=model.container.manifest.enabled("quick-capture"); val dictate=model.container.manifest.enabled("dictation")
  CaptureSurfaces.apply(model.container.context,quick,dictate)
  val manager=model.container.context.getSystemService(ShortcutManager::class.java)
  val share=ShortcutInfo.Builder(model.container.context,"share-note").setShortLabel("spunto").setCategories(setOf("io.theficos.spunto.SHARE")).setIntent(CaptureSurfaces.intent(model.container.context).setAction(Intent.ACTION_SEND)).build()
  manager.dynamicShortcuts=listOf(share)+if(dictate) listOf(ShortcutInfo.Builder(model.container.context,"dictate").setShortLabel("Dictate a note").setIntent(CaptureSurfaces.intent(model.container.context,true).setAction(Intent.ACTION_VIEW)).build()) else emptyList()
  }
 }
 fun navigateBack() {
  route=when(route) {
   "appearance","plugins","devices","status","labels","data","profile" -> "settings"
   else -> "timeline"
  }
 }
 BackHandler(route!="timeline" && !navigationOpen) { navigateBack() }
 BackHandler(navigationOpen) { navigationOpen=false }
 val management=sharedManagementTarget(auth,replicaState?.serverUrl,online,needsSignIn=reauth || sync==SyncStatus.NeedsSignIn)
 fun openManagement(labelPath:String?=null) {
  val target=sharedManagementTarget(model.auth.value,replicaState?.serverUrl,model.online.value,labelPath,model.reauthenticate.value || model.status.value==SyncStatus.NeedsSignIn)
  val url=target.url
  if(url==null) {model.message.value=target.message;return}
  try {
   model.container.context.startActivity(Intent(Intent.ACTION_VIEW,android.net.Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
   navigationOpen=false
  } catch(_:ActivityNotFoundException) {model.message.value="No browser is available to open shared settings."}
  catch(_:SecurityException) {model.message.value="Couldn't open shared settings in your browser."}
 }
 val dark=mode=="dark" || mode=="system" && isSystemInDarkTheme()
 val instance=manifest["theme"] as? JsonObject
 val requestedTheme=if(themeOverride) theme else appearanceManifest?.instance?.id ?: instance?.string("id")?.takeIf { it.isNotBlank() } ?: "carta"
 val requestedAccent=if(themeOverride) accent else appearanceManifest?.instance?.accent ?: instance?.string("accent")?.takeIf { it.isNotBlank() }
 val snapshot=remember(manifestFingerprint,themeOverride,theme,accent) { readSelectedThemeSnapshot(model.container.preferences,manifestFingerprint) }
 val activeSelection=if(appearanceManifest!=null) {
  if(themeOverride) resolveThemeSelection(theme,accent,appearanceManifest.catalog) else appearanceManifest.instance
 } else snapshot ?: resolveThemeSelection(requestedTheme,requestedAccent)
 LaunchedEffect(appearanceManifest,themeOverride,requestedTheme,requestedAccent,activeSelection) {
  if(appearanceManifest!=null) storeSelectedThemeSnapshot(model.container.preferences,SelectedThemeSnapshot(themeOverride,requestedTheme,requestedAccent,manifestFingerprint,activeSelection))
 }
 SpuntoTheme(activeSelection.id,mode,black,wallpaper,pack=activeSelection.mode(dark),readingFace=readingFace,readingSize=readingSize) {
  FirstRunGuideGate(guideStore,eligible=guideEligible && initialNoteId==null && route=="timeline" && !reauth,replay=route=="guide",canConnect=auth==null,onStartWriting={route="timeline"},onSkipReplay={route="settings"},onConnect={route="login"},content=appContent@{
  if(reauth || route=="login") {
   LoginScreen(model,reauth || sync==SyncStatus.NeedsSignIn,onDone={model.reauthenticate.value=false;route="timeline"}); return@appContent
  }
  Scaffold(topBar={TopAppBar(title={
   if(isLabelsNavigationRoute(route)) TextButton(onClick={navigationOpen=true},modifier=Modifier.semantics {contentDescription="Choose labels and views"}) {Text("${navigationTitle(route,navigationLabels,manifest)} ▾",style=MaterialTheme.typography.titleMedium,color=MaterialTheme.colorScheme.onSurface,maxLines=1,overflow=TextOverflow.Ellipsis)}
   else Text(if(route.startsWith("note:")) "Note" else route.substringBefore(':').replaceFirstChar {it.uppercase()})
  },navigationIcon={if(!isLabelsNavigationRoute(route)) TextButton(onClick=::navigateBack) {Text("Back")}},actions={
   TextButton(onClick={route="search"}) {Text("Search")}
   if(route=="timeline") IconButton(onClick={route="settings"},modifier=Modifier.size(48.dp)) {
    Icon(painterResource(R.drawable.ic_settings),contentDescription="Settings",modifier=Modifier.size(24.dp),tint=MaterialTheme.colorScheme.onSurfaceVariant)
   }
  })}) { padding ->
   Column(Modifier.fillMaxSize().padding(padding)) {
    if(navigationOpen) LabelsSheet(LabelsNavigationSnapshot(route,navigationLabels,manifest),onRoute={requested ->navigationOpen=false;route=requested},onSettings={navigationOpen=false;route="settings"},onManageLabels={openManagement(it)},onManageViews={openManagement()},onDismiss={navigationOpen=false},managementAvailable=management.url!=null,managementMessage=management.message)
    if(auth?.serverUrl?.startsWith("http://")==true) Text("Not encrypted · This server uses plain HTTP.",Modifier.fillMaxWidth().padding(12.dp),style=MaterialTheme.typography.bodySmall)
    if(!online && auth!=null) Text("Offline. Notes stay on this phone.",Modifier.padding(horizontal=20.dp),style=MaterialTheme.typography.bodySmall)
    if(pending>0 || sync is SyncStatus.Waiting) SyncStatusNotice(pending,sync is SyncStatus.Waiting) {model.synchronize()}
    if(message.isNotEmpty()) Row(Modifier.fillMaxWidth().padding(horizontal=20.dp),verticalAlignment=Alignment.CenterVertically) { Text(message,Modifier.weight(1f),style=MaterialTheme.typography.bodyMedium); TextButton(onClick={model.message.value=""},modifier=Modifier.semantics {contentDescription="Dismiss message"}) { Text("×") } }
    when {
     route=="timeline" -> Timeline(model,onNote={route="note:$it"},onReview={route="review"})
     route.startsWith("note:") -> NoteScreen(model,route.substringAfter(':'),onClose={route="timeline"},onNote={route="note:$it"})
     route=="search" -> SearchScreen(model,onNote={route="note:$it"})
     route=="labels" -> LabelsScreen(model) { route="label:$it" }
     route=="settings" -> SettingsLanding(onRoute={route=it},onConnect={route="login"},isConnected=auth!=null)
     route=="archive" || route=="trash" || route.startsWith("label:") || route.startsWith("view:") -> FilteredTimeline(model,route) { route="note:$it" }
     route=="review" -> ReviewScreen(model) { route="note:$it" }
     route=="appearance" -> AppearanceScreen(requestedTheme,mode,black,wallpaper,{ choice ->
      val selected=resolveThemeSelection(choice,null,appearanceManifest?.catalog)
      applyDeviceTheme(model.container.preferences,choice,null,selected,manifestFingerprint);theme=choice;accent=null;themeOverride=true
     },{mode=it;model.container.preferences.edit().putString("mode",it).apply()},{black=it;model.container.preferences.edit().putBoolean("black",it).apply()},{wallpaper=it;model.container.preferences.edit().putBoolean("wallpaper",it).apply()},themeOverride=themeOverride,onUseInstance={
      val selected=appearanceManifest?.instance ?: resolveThemeSelection(instance?.string("id") ?: "carta",instance?.string("accent")?.takeIf { it.isNotBlank() })
      applyDeviceTheme(model.container.preferences,null,null,selected,manifestFingerprint);themeOverride=false;accent=null
     },catalog=appearanceManifest?.catalog ?: ThemeCatalog(builtInThemeCards,false),accent=if(themeOverride) accent else activeSelection.accent,onAccent={ choice ->
      val selected=resolveThemeSelection(requestedTheme,choice,appearanceManifest?.catalog)
      applyDeviceTheme(model.container.preferences,requestedTheme,choice,selected,manifestFingerprint);theme=requestedTheme;accent=choice;themeOverride=true
     },instanceName=appearanceManifest?.let { value -> value.catalog.cards.firstOrNull { it.id==value.instance.id }?.name } ?: builtInThemes.firstOrNull { it.id==instance?.string("id") }?.name ?: "Carta",readingFace=readingFace,readingSize=readingSize,onReadingFace={readingFace=it;model.container.preferences.edit().putString("reading-face",it).apply()},onReadingSize={readingSize=it;model.container.preferences.edit().putString("reading-size",it).apply()})
     route=="plugins" -> PluginsScreen(model)
     route=="devices" -> DevicesScreen(model,onLogin={route="login"})
     route=="data" || route=="profile" -> OwnerWebSettingsScreen(model,route)
     route=="status" -> StatusScreen(model)
    }
    val remote by model.remote.collectAsState()
    if(online && remote.string("plugin_id").isNotBlank() && model.container.manifest.enabled(remote.string("plugin_id"))) CommandResultSheet(model,remote,{route="note:$it"},{model.remote.value=it},{model.remote.value=JsonObject(emptyMap())})
    if(remote.string("suggestion_preview").isNotBlank()) {
     val outputs by model.outputs.collectAsState()
     outputs.firstOrNull { it.id==remote.string("suggestion_preview") && it.kind=="suggestion" && model.container.manifest.enabled(payload(it.payload).string("plugin")) }?.let { item -> SuggestionDecisionDialog(model,proposalData(item),{route="note:$it"},{model.remote.value=JsonObject(emptyMap())}) }
    }
   }
  }
  })
 }
}
@Composable internal fun SyncStatusNotice(pending:Int,unreachable:Boolean,onSync:()->Unit) {
 Row(Modifier.fillMaxWidth().padding(horizontal=20.dp),horizontalArrangement=Arrangement.spacedBy(12.dp),verticalAlignment=Alignment.CenterVertically) {
  Text("$pending notes waiting to sync${if(unreachable) " · Server unreachable" else ""}",Modifier.weight(1f),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
  TextButton(onClick=onSync,modifier=Modifier.heightIn(min=48.dp)) {Text("Sync")}
 }
}
@Composable fun Timeline(model:SpuntoViewModel,onNote:(String)->Unit,onReview:()->Unit) {
 val manifest=model.manifest.collectAsState().value
 var from by remember { mutableStateOf("") };var to by remember { mutableStateOf("") };var dates by remember { mutableStateOf(false) }
 val range=remember(from,to) { runCatching { CreatedRange.parse(from,to) } }
 val spec=remember(range.getOrNull(),range.isSuccess) { TimelineSpec(created=range.getOrDefault(CreatedRange()),valid=range.isSuccess) }
 val paging=remember(spec) { model.repository.paging(spec) }
 val page=paging.collectAsLazyPagingItems()
 val outputs by model.outputs.collectAsState()
 val notes by model.notes.collectAsState()
 val proposals=outputs.count { it.kind=="suggestion" && payload(it.payload).string("status")=="pending" && model.container.manifest.enabled(payload(it.payload).string("plugin")) }
 LazyColumn(Modifier.fillMaxSize().imePadding(),contentPadding=PaddingValues(20.dp),verticalArrangement=Arrangement.spacedBy(20.dp)) {
  item { Composer(model,onPlainCaptureSave={body,ref ->model.repository.create(body,sourceRef=ref)},onSave={model.saveLocal(it)}) }
  if(proposals>=3) item { TextButton(onClick=onReview) { Text("$proposals suggestions · Review") } }
  item { CreatedDateControls(from,to,dates,{from=it},{to=it},{dates=!dates},range.isFailure) }
  outputs.filter { it.kind=="annotation" && payload(it.payload).string("kind")=="slip" && model.container.manifest.enabled(payload(it.payload).string("plugin")) && notes.any { note -> note.id==it.noteId && !note.privateNote && !note.trashed } }.take(1).forEach { output -> item { Surface(color=MaterialTheme.colorScheme.surfaceVariant) { Column(Modifier.padding(16.dp)) { Text("From your notes",style=MaterialTheme.typography.labelSmall); notes.firstOrNull { it.id==output.noteId }?.let { Markdown(it.body.take(600));Row { TextButton(onClick={onNote(it.id)}) { Text("Open") };TextButton(onClick={model.flags(it.id,archived=true)}) { Text("Archive") } } } ?: RenderOutput(model,payload(output.payload),onNote) } } } }
  if(page.itemCount==0) item { if(from.isNotBlank() || to.isNotBlank()) Text("No notes in this date range.") else { Text("A quiet place for loose ideas.",style=MaterialTheme.typography.titleLarge); Text("Share anything to spunto from any app, or select text and choose Save to spunto.",color=MaterialTheme.colorScheme.onSurfaceVariant) } }
  items(page.itemCount,key={index -> page.peek(index)?.id ?: index}) { index -> page[index]?.note()?.let { NoteEntry(model,it,onNote) } }
 }
}
@Composable fun NoteEntry(model:SpuntoViewModel,note:Note,onNote:(String)->Unit) {
 val manifest=model.manifest.collectAsState().value
 val outputs by model.outputs.collectAsState()
 val meta=entryMetaText(outputs,note.id,model.container.manifest.plugins())
 Column(Modifier.fillMaxWidth().clickable { onNote(note.id) }.semantics { contentDescription="Note, ${noteTime(note.createdAt)}, ${note.title}" },verticalArrangement=Arrangement.spacedBy(8.dp)) {
  Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(12.dp),verticalAlignment=Alignment.CenterVertically) {
   Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(4.dp)) {
    Text("${if(note.pending) "◯ " else ""}${noteTime(note.createdAt)}${if(note.pinned) " · Pinned" else ""}",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
    if(meta.isNotEmpty()) Text(meta,style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
   }
   TextButton(onClick={onNote(note.id)},modifier=Modifier.heightIn(min=48.dp)) {Text("Open")}
  }
  Markdown(note.body.take(1200),note.machine,onLink={if(it.startsWith("/n/")) onNote(it.substringAfter("/n/")) else if(it.startsWith("/a/")) onNote(note.id)}) { model.toggleTask(note,it) }
  val provenance=buildList {
   if(note.privateNote) add("Private")
   if(note.conflictOf!=null) add("Conflicted copy")
   if(note.updatedViaAgent && note.sourceKind!="agent") add("Edited via agent")
   if(note.sourceKind in setOf("agent","plugin")) add("${if(note.machine) "✦ via" else "via"} ${note.sourceRef ?: note.sourceKind}")
  }
  if(provenance.isNotEmpty()) Text(provenance.joinToString(" · "),style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
  Suggestions(model,note.id,onNote)
  if(note.machine) Text("Written by ${note.sourceRef ?: "a plugin"}. Plugins leave it out until you edit it.",style=MaterialTheme.typography.bodySmall)
  HorizontalDivider(color=MaterialTheme.colorScheme.outlineVariant)
 }
}
@OptIn(ExperimentalMaterial3Api::class)
@Composable fun NoteScreen(model:SpuntoViewModel,id:String,onClose:()->Unit,onNote:(String)->Unit) {
 val manifest=model.manifest.collectAsState().value
 val note by model.repository.observe(id).collectAsState(null)
 var editing by remember(id) { mutableStateOf(false) }; var history by remember(id) { mutableStateOf(false) }; var about by remember(id) { mutableStateOf(false) }; var commandResult by remember(id) { mutableStateOf<JsonObject?>(null) }
 val editorScope=rememberCoroutineScope()
 var finishing by remember(id) {mutableStateOf(false)}
 var restoring by remember(id) {mutableStateOf(false)}
 var finishEditor by remember(id) {mutableStateOf<(suspend ()->Unit)?>(null)}
 val revisions by model.repository.history(id).collectAsState(emptyList())
 var historyResult by remember(id) { mutableStateOf<HistoryRefreshResult?>(null) }
 var recordingTarget by remember(id) {mutableStateOf<String?>(null)}
 val value=note?.takeIf {it.id==id} ?: run { Text("Note unavailable.");return }
 val online by model.online.collectAsState()
 LaunchedEffect(history,online,id,value.serverRev) { if(history && online) historyResult=model.refreshHistory(id) }
 LazyColumn(Modifier.fillMaxSize().imePadding(),contentPadding=PaddingValues(20.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
  item { if(editing) Composer(model,id,value.body,onFinishReady={finishEditor=it},onRecordingSaved={editing=false},onSave={editing=false}) else Markdown(value.body,value.machine,onLink={if(it.startsWith("/n/")) onNote(it.substringAfter("/n/")) else if(recordingAttachmentLinks(value.body).any {link ->link.second==it}) recordingTarget=it}) { model.toggleTask(value,it) } }
  item { FlowRow(horizontalArrangement=Arrangement.spacedBy(4.dp)) { TextButton(enabled=!finishing && !restoring && (!editing || finishEditor!=null),onClick={
   if(finishing || restoring) return@TextButton
   if(!editing) editing=true else { finishing=true;editorScope.launch {
    try { finishEditor?.invoke();editing=false }
    catch(error:kotlinx.coroutines.CancellationException) {throw error}
    catch(error:Exception) {model.message.value="Couldn't save on this phone. Your draft is still here."}
    finally {finishing=false}
   } }
  }) { Text(if(finishing) "Saving…" else if(editing) "Read" else "Edit") }; TextButton(onClick={about=true}) { Text("About this note") }; TextButton(onClick={history=!history}) { Text("History") } } }
  item { NoteExportActions(model,value,enabled=!editing && !finishing && !restoring) }
  item { FlowRow(horizontalArrangement=Arrangement.spacedBy(4.dp)) { TextButton(onClick={model.flags(id,pinned=!value.pinned)}) { Text(if(value.pinned) "Unpin" else "Pin") }; TextButton(onClick={model.flags(id,archived=!value.archived)}) { Text(if(value.archived) "Unarchive" else "Archive") }; TextButton(onClick={model.flags(id,trashed=!value.trashed);onClose()}) { Text(if(value.trashed) "Restore" else "Trash") } } }
  item { Row(horizontalArrangement=Arrangement.spacedBy(16.dp),verticalAlignment=Alignment.CenterVertically) { Text("Private · Plugins never read this note",Modifier.weight(1f),style=MaterialTheme.typography.bodyMedium); Switch(checked=value.privateNote,onCheckedChange={model.flags(id,privateNote=it)},modifier=Modifier.semantics {contentDescription="Private note"}) } }
  if(!editing) item { Suggestions(model,id,onNote) }
  if(online) item { NoteCommands(model,id) { commandResult=it } }
  item { NoteAttachments(model,id,value.body) }
  if(history) revisionHistoryItems(revisions,online,historyResult,editing || finishing || restoring) { revision ->
   if(!editing && !finishing && !restoring) { restoring=true;editorScope.launch {
    try {model.repository.restoreHistory(id,revision)}
    catch(error:kotlinx.coroutines.CancellationException) {throw error}
    catch(error:Exception) {model.message.value="Couldn't restore this revision. Your note and draft stay on this phone."}
    finally {restoring=false}
   } }
  }
 }
 if(about) ModalBottomSheet(onDismissRequest={about=false},scrimColor=MaterialTheme.colorScheme.scrim) { Column(Modifier.padding(20.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)) { Text("About this note",style=MaterialTheme.typography.titleLarge); Text("Created ${noteTime(value.createdAt)}\nUpdated ${noteTime(value.updatedAt)}");value.sourceUrl?.let { Text(it) };PluginPanels(model,value,onNote);Spacer(Modifier.height(32.dp)) } }
 recordingTarget?.takeIf {target ->recordingAttachmentLinks(value.body).any {it.second==target}}?.let {target ->
  val attachments by model.repository.dao.attachments(id).collectAsState(emptyList())
  val label=recordingAttachmentLinks(value.body).first {it.second==target}.first
  ModalBottomSheet(onDismissRequest={recordingTarget=null}) {Column(Modifier.padding(20.dp)) {AttachmentPlayer(model,target.substringAfterLast('/'),label,attachments.firstOrNull {it.kind=="recording" && it.remoteId==target.substringAfterLast('/')});Spacer(Modifier.height(32.dp))}}
 }
 commandResult?.takeIf { online && model.container.manifest.enabled(it.string("plugin_id")) }?.let { result -> CommandResultSheet(model,result,onNote,{commandResult=it},{commandResult=null}) }
}

internal fun LazyListScope.revisionHistoryItems(revisions:List<RevisionEntity>,online:Boolean,result:HistoryRefreshResult?,editing:Boolean,onRestore:(RevisionEntity)->Unit) {
 item { Text("Revision history",style=MaterialTheme.typography.titleLarge);if(!online || result is HistoryRefreshResult.Cached) Text("Showing history saved on this phone.",style=MaterialTheme.typography.bodySmall);if(editing) Text("Finish editing before restoring a revision.",style=MaterialTheme.typography.bodySmall) }
 if(revisions.isEmpty()) item { Text("No saved revisions on this phone yet.") }
 items(revisions,key={it.id}) { rev -> Column { Text((if(rev.version>0) "Version "+rev.version+" · " else "Local revision · ")+noteTime(rev.at));Text(if(rev.historySource=="server") rev.changeKind.replace('_',' ') else "Saved on this phone",style=MaterialTheme.typography.bodySmall);Markdown(rev.body);TextButton(onClick={onRestore(rev)},enabled=!editing) { Text("Restore this revision") };HorizontalDivider() } }
}
@Composable fun FilteredTimeline(model:SpuntoViewModel,route:String,onNote:(String)->Unit) {
 val manifest by model.manifest.collectAsState()
 var from by remember(route) { mutableStateOf("") };var to by remember(route) { mutableStateOf("") };var dates by remember(route) { mutableStateOf(false) }
 val range=remember(from,to) { runCatching { CreatedRange.parse(from,to) } }
 val today=LocalDate.now(ZoneOffset.UTC)
 val spec=remember(route,manifest,range.getOrNull(),range.isSuccess,today) {
  val created=range.getOrDefault(CreatedRange())
  when {
   route=="archive" -> TimelineSpec(kind=TimelineKind.ARCHIVE,created=created,valid=range.isSuccess)
   route=="trash" -> TimelineSpec(kind=TimelineKind.TRASH,created=created,valid=range.isSuccess)
   route.startsWith("label:") -> TimelineSpec(kind=TimelineKind.LABEL,label=route.substringAfter(':'),created=created,valid=range.isSuccess)
   else -> { val parts=route.split(':',limit=3);val view=resolveViewRoute(manifest,route);TimelineSpec(kind=TimelineKind.PLUGIN,pluginId=parts.getOrNull(1).orEmpty(),filter=view?.filter ?: JsonObject(emptyMap()),created=created,valid=range.isSuccess && view!=null) }
  }
 }
 val paging=remember(spec) { model.repository.paging(spec) }
 val page=paging.collectAsLazyPagingItems()
 LazyColumn(contentPadding=PaddingValues(20.dp),verticalArrangement=Arrangement.spacedBy(20.dp)) {
  item { CreatedDateControls(from,to,dates,{from=it},{to=it},{dates=!dates},range.isFailure) }
  if(page.itemCount==0) item { Text(if(from.isNotBlank() || to.isNotBlank()) "No notes in this date range." else "Nothing here yet.") }
  items(page.itemCount,key={index -> page.peek(index)?.id ?: index}) { index -> page[index]?.note()?.let { NoteEntry(model,it,onNote) } }
 }
}
@Composable internal fun CreatedDateControls(from:String,to:String,expanded:Boolean,onFrom:(String)->Unit,onTo:(String)->Unit,onExpand:()->Unit,invalid:Boolean) {
 Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
  Row { TextButton(onClick=onExpand) { Text(if(from.isNotBlank() || to.isNotBlank()) "Dates · Filtered" else "Dates") };if(from.isNotBlank() || to.isNotBlank()) TextButton(onClick={onFrom("");onTo("")}) { Text("Clear dates") } }
  if(expanded) { OutlinedTextField(from,onFrom,Modifier.fillMaxWidth(),label={Text("Created from · YYYY-MM-DD")},singleLine=true,isError=invalid);OutlinedTextField(to,onTo,Modifier.fillMaxWidth(),label={Text("Created to · YYYY-MM-DD")},singleLine=true,isError=invalid) }
  if(invalid) Text("Use dates in YYYY-MM-DD, with From on or before To.",color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall)
 }
}
@OptIn(ExperimentalMaterial3Api::class)
@Composable fun SearchScreen(model:SpuntoViewModel,onNote:(String)->Unit) {
 var query by remember { mutableStateOf("") }; var results by remember { mutableStateOf<List<Note>>(emptyList()) }; var meaning by remember { mutableStateOf<Set<String>>(emptySet()) }
 var answer by remember { mutableStateOf<JsonObject?>(null) }; var asking by remember { mutableStateOf(false) }; var dates by remember { mutableStateOf(false) }; var from by remember { mutableStateOf("") }; var to by remember { mutableStateOf("") }
 val labels by model.labels.collectAsState(); val auth by model.auth.collectAsState(); val sync by model.status.collectAsState()
 val manifest=model.manifest.collectAsState().value
 val online by model.online.collectAsState()
 val localNotes by model.notes.collectAsState()
 LaunchedEffect(online,manifest) { if(!online || !model.container.manifest.enabled("ask")) answer=null }
 LaunchedEffect(query,from,to,auth,online,localNotes) {
  results=model.repository.search(query).filter { note -> (from.isEmpty() || runCatching { note.createdAt>=LocalDate.parse(from).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli() }.getOrDefault(true)) && (to.isEmpty() || runCatching { note.createdAt<LocalDate.parse(to).plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli() }.getOrDefault(true)) }
  meaning=emptySet()
  if(online && query.isNotBlank()) {
   kotlinx.coroutines.delay(150)
   runCatching { ApiClient(auth!!).request("/api/v1/search",query=buildMap { put("q",query); if(from.isNotBlank()) put("from",from); if(to.isNotBlank()) put("to",to) }).jsonObject }.onSuccess { page -> results=mergeSearchResults(results,page.objects("hits").map(::noteFromWire),localNotes);meaning=page.objects("hits").filter { it.string("match")=="meaning" }.map { it.string("id") }.toSet() }
  }
 }
 Column(Modifier.padding(20.dp)) {
  OutlinedTextField(query,{query=it},Modifier.fillMaxWidth(),label={Text("Search notes")},singleLine=true)
  TextButton(onClick={dates=!dates}) { Text("Date filter") }
  if(dates) { OutlinedTextField(from,{from=it},label={Text("From · YYYY-MM-DD")},singleLine=true);OutlinedTextField(to,{to=it},label={Text("To · YYYY-MM-DD")},singleLine=true) }
  if(!online) Text("Offline. Searching this phone by keyword.",style=MaterialTheme.typography.bodySmall)
  if(online && query.trim().split(Regex("\\s+")).size>=3 && model.container.manifest.enabled("ask")) TextButton(enabled=!asking,onClick={asking=true; model.ask(query,onComplete={asking=false}) { if(model.online.value && model.container.manifest.enabled("ask")) answer=it.jsonObject }}) { Text(if(asking) "Reading your notes…" else "Ask your notes") }
  if(query.isEmpty()) labels.take(8).forEach { TextButton(onClick={query="#${it.path}"}) { Text("#${it.path}") } }
  LazyColumn(verticalArrangement=Arrangement.spacedBy(16.dp)) { items(results,key={it.id}) { note -> if(note.id in meaning) Text("≈ similar meaning",style=MaterialTheme.typography.bodySmall); NoteEntry(model,note,onNote) }; if(query.isNotEmpty() && results.isEmpty()) item { Text("No notes match this search.") } }
 }
 answer?.let { output -> ModalBottomSheet(onDismissRequest={answer=null},scrimColor=MaterialTheme.colorScheme.scrim) { Column(Modifier.padding(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) { RenderOutput(model,JsonObject(output+mapOf("plugin_id" to JsonPrimitive("ask"))),onNote); if(online && model.container.manifest.enabled("ask") && output.string("answer_id").isNotEmpty()) Button(onClick={model.request("/api/v1/plugins/ask/commands/save-answer","POST",buildJsonObject { put("answer_id",output.string("answer_id")) }) { model.synchronize();answer=null }}) { Text("Save as note") };TextButton(onClick={answer=null}) { Text("Close") } } } }
}
@Composable fun LabelsScreen(model:SpuntoViewModel,onLabel:(String)->Unit) {
 val auth by model.auth.collectAsState()
 val labels by model.labels.collectAsState();val online by model.online.collectAsState();val reauth by model.reauthenticate.collectAsState()
 val stateFlow=remember(model.repository) {model.repository.dao.observeState()};val state by stateFlow.collectAsState(null)
 var query by remember {mutableStateOf("")};var selected by remember {mutableStateOf<String?>(null)}
 val latestLabels by rememberUpdatedState(labels)
 val rows=remember(labels,query) {labelSheetRows(labels,query,null)}
 LazyColumn(contentPadding=PaddingValues(20.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
  item {OutlinedTextField(query,{query=it},Modifier.fillMaxWidth(),label={Text("Find a label")},singleLine=true)}
  if(rows.isEmpty()) item {Text(if(labels.isEmpty()) "Labels grow as you write. Type # in a note." else "No labels match this search.")}
  labelTreeItems(rows,onLabel={key ->if(latestLabels.any {it.key==key}) onLabel(key)},onManageLabel={selected=it})
 }
 selected?.let {path ->
  val target=sharedManagementTarget(auth,state?.serverUrl,online,path,reauth)
  AlertDialog(onDismissRequest={selected=null},title={Text("Manage #$path")},text={Column {Text("Rename and merge labels in the web app, where you can review all affected notes.");Text(target.message,Modifier.padding(top=12.dp),style=MaterialTheme.typography.bodySmall)}},confirmButton={TextButton(enabled=target.url!=null && labels.any {it.path==path},onClick={
   val current=sharedManagementTarget(model.auth.value,state?.serverUrl,model.online.value,path,model.reauthenticate.value)
   current.url?.let {url ->try {model.container.context.startActivity(Intent(Intent.ACTION_VIEW,android.net.Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));selected=null} catch(_:Exception) {model.message.value="The web app could not be opened."}}
  }) {Text("Manage in the web app")}},dismissButton={TextButton(onClick={selected=null}) {Text("Cancel")}})
 }
}
@Composable fun AppearanceScreen(theme:String,mode:String,black:Boolean,wallpaper:Boolean,onTheme:(String)->Unit,onMode:(String)->Unit,onBlack:(Boolean)->Unit,onWallpaper:(Boolean)->Unit,themeOverride:Boolean=true,onUseInstance:(()->Unit)?=null,catalog:ThemeCatalog=ThemeCatalog(builtInThemeCards,false),accent:String?=null,onAccent:((String?)->Unit)?=null,instanceName:String="Carta",readingFace:String="serif",readingSize:String="M",onReadingFace:(String)->Unit={},onReadingSize:(String)->Unit={}) {
 val dark=mode=="dark" || mode=="system" && isSystemInDarkTheme()
 val selectedCard=catalog.cards.firstOrNull { it.id==theme && it.available }
 val selectedAccent=selectedCard?.accents?.firstOrNull { it.id==accent }?.id
 val accentChoices=themeAccentChoices(selectedCard)
 LazyColumn(contentPadding=PaddingValues(20.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
  item {
   Text("Appearance",style=MaterialTheme.typography.titleLarge)
   Text(if(themeOverride) "Theme on this phone" else "Using the instance theme",style=MaterialTheme.typography.bodyMedium)
   if(themeOverride) Text("Other devices use ${boundedThemeText(instanceName,80)}.",style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
   if(onUseInstance!=null) Row(verticalAlignment=Alignment.CenterVertically) {
    Text("Use a different theme on this phone",Modifier.weight(1f),style=MaterialTheme.typography.bodyMedium)
    Switch(checked=themeOverride,onCheckedChange={if(it) onTheme(theme) else onUseInstance()},modifier=Modifier.semantics { contentDescription="Use a different theme on this phone" })
   }
  }
  items(catalog.cards,key={it.id}) { choice ->
   if(choice.available) {
    val chosen=theme==choice.id
    val palette=resolveThemeSelection(choice.id,if(chosen) accent else null,catalog).mode(dark)
    SpuntoTheme(choice.id,mode,pack=palette,readingFace=readingFace,readingSize=readingSize) {
     Surface(Modifier.fillMaxWidth().clickable(role=Role.RadioButton) { onTheme(choice.id) }.semantics { selected=chosen },shape=MaterialTheme.shapes.medium,color=MaterialTheme.colorScheme.surfaceContainer,border=if(chosen) BorderStroke(2.dp,MaterialTheme.colorScheme.primary) else null) {
      Column(Modifier.padding(20.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
       Text(choice.name+if(chosen) " ✓" else "",style=MaterialTheme.typography.titleLarge)
       if(choice.description.isNotBlank()) Text(choice.description,style=MaterialTheme.typography.bodyMedium)
       Text("14:32 · Today",style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
       Text("A quiet place for loose ideas.",style=MaterialTheme.typography.bodyLarge)
       FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
        Surface(shape=MaterialTheme.shapes.small,color=MaterialTheme.colorScheme.primaryContainer) { Text("idea",Modifier.padding(horizontal=8.dp,vertical=4.dp),style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onPrimaryContainer) }
        val proposalColour=MaterialTheme.colorScheme.onSurfaceVariant
        Box(Modifier.drawBehind { drawRoundRect(proposalColour,cornerRadius=androidx.compose.ui.geometry.CornerRadius(4.dp.toPx()),style=Stroke(width=1.dp.toPx(),pathEffect=PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(),3.dp.toPx())))) }.padding(horizontal=8.dp,vertical=4.dp)) { Text("Suggested label",style=MaterialTheme.typography.bodyMedium,color=proposalColour) }
       }
       Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) { listOf("paper","ink","accent","accent-soft","highlight").forEach { role -> Box(Modifier.size(20.dp).background(tokenColor(palette.getValue(role)),CircleShape).semantics { contentDescription="${choice.name} $role swatch" }) } }
      }
     }
    }
   } else Surface(Modifier.fillMaxWidth().semantics(mergeDescendants=true) { disabled() },shape=MaterialTheme.shapes.medium,color=MaterialTheme.colorScheme.surfaceContainer) {
    Column(Modifier.padding(20.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) { Text(choice.name,style=MaterialTheme.typography.titleLarge);Text("Unavailable · ${boundedThemeText(choice.reason ?: "Colour pack is unavailable.")}",style=MaterialTheme.typography.bodyMedium) }
   }
  }
  if(accentChoices.isNotEmpty() && onAccent!=null) item {
   Text("Accent",style=MaterialTheme.typography.bodyMedium)
   FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
    accentChoices.forEach { choice -> FilterChip(selected=selectedAccent==choice.id,onClick={onAccent(choice.id)},label={Text(choice.name)}) }
   }
  }
  item {
   Text("Mode",style=MaterialTheme.typography.bodyMedium)
   FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) { listOf("system","light","dark").forEach { choice -> FilterChip(selected=mode==choice,onClick={onMode(choice)},label={Text(choice.replaceFirstChar { it.uppercase() })}) } }
  }
  item {
   Text("Reading face",style=MaterialTheme.typography.bodyMedium)
   FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) { listOf("serif" to "Serif","sans" to "Sans").forEach { (value,label) -> FilterChip(selected=normalizedReadingFace(readingFace)==value,onClick={onReadingFace(value)},label={Text(label)}) } }
   Text("Text size",style=MaterialTheme.typography.bodyMedium)
   FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) { listOf("S","M","L").forEach { value -> FilterChip(selected=normalizedReadingSize(readingSize)==value,onClick={onReadingSize(value)},label={Text(value)}) } }
   Text("A thought worth keeping, in your own words.",Modifier.padding(vertical=12.dp),style=readingTypography(readingFace,readingSize).bodyLarge)
  }
  item { Row(horizontalArrangement=Arrangement.spacedBy(16.dp),verticalAlignment=Alignment.CenterVertically) { Text("Pure black",Modifier.weight(1f),style=MaterialTheme.typography.bodyMedium);Switch(black,onBlack,modifier=Modifier.semantics {contentDescription="Pure black"}) };Row(horizontalArrangement=Arrangement.spacedBy(16.dp),verticalAlignment=Alignment.CenterVertically) { Text("Wallpaper colours",Modifier.weight(1f),style=MaterialTheme.typography.bodyMedium);Switch(wallpaper,onWallpaper,modifier=Modifier.semantics {contentDescription="Wallpaper colours"}) } }
 }
}

@Composable fun NoteAttachments(model:SpuntoViewModel,id:String,body:String="") {
 val attachments by model.repository.dao.attachments(id).collectAsState(emptyList())
 val selected by model.auth.collectAsState()
 val auth=selected?.takeIf {!it.hosted || it.replicaId==model.container.replicaId}
 val links=remember(body) {recordingAttachmentLinks(body).distinctBy {it.second}}
 val linked=links.associate {it.second.substringAfterLast('/') to it.first}
 val localRecordings=attachments.filter {it.kind=="recording" && (it.marker.isNotEmpty() && it.marker in body || it.remoteId in linked)}
 Column(verticalArrangement=Arrangement.spacedBy(12.dp)) {
  Regex("!\\[[^]]*]\\((/a/[^)]+)\\)").findAll(body).forEach { match -> if(auth!=null) coil.compose.AsyncImage(model=coil.request.ImageRequest.Builder(model.container.context).data(auth!!.serverUrl+match.groupValues[1]).memoryCacheKey(model.container.replicaId+":"+auth!!.serverUrl+match.groupValues[1]).diskCacheKey(model.container.replicaId+":"+auth!!.serverUrl+match.groupValues[1]).addHeader("Authorization","Bearer ${auth!!.token}").build(),contentDescription="Note image",modifier=Modifier.fillMaxWidth().heightIn(max=360.dp)) }
  attachments.forEach { item ->
   val local=java.io.File(item.localPath)
   val url=if(local.exists()) local else item.remoteId?.let { remote -> auth?.let { it.serverUrl+"/a/$remote" } }
   if(url!=null && item.mime.startsWith("image/")) coil.compose.AsyncImage(model=coil.request.ImageRequest.Builder(model.container.context).data(url).memoryCacheKey(model.container.replicaId+":"+url.toString()).diskCacheKey(model.container.replicaId+":"+url.toString()).apply { if(url is String) auth?.let { addHeader("Authorization","Bearer ${it.token}") } }.build(),contentDescription="Note image",modifier=Modifier.fillMaxWidth().heightIn(max=360.dp))
  }
  localRecordings.forEach {item ->AttachmentPlayer(model,item.remoteId?:item.id,linked[item.remoteId]?:"Recording ${recordingTime(item.durationMs)}",item)}
  links.filter {(_,target) ->localRecordings.none {it.remoteId==target.substringAfterLast('/')}}.forEach {(label,target) ->AttachmentPlayer(model,target.substringAfterLast('/'),label)}
 }
}

/** REST relevance order with authoritative unsynced bodies and pending local matches. */
internal fun mergeSearchResults(localMatches:List<Note>,remote:List<Note>,replica:List<Note>):List<Note> {
 val pending=replica.filter { it.pending }.associateBy { it.id }
 return (remote.mapNotNull { hit -> pending[hit.id]?.takeUnless { it.trashed } ?: hit.takeUnless { pending[hit.id]?.trashed==true } } + localMatches.filter { it.pending && !it.trashed }).distinctBy { it.id }
}
