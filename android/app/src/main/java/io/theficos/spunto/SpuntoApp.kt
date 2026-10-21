@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package io.theficos.spunto

import android.content.*
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.activity.compose.BackHandler
import io.theficos.spunto.core.design.*
import io.theficos.spunto.core.model.*
import io.theficos.spunto.core.plugins.*
import io.theficos.spunto.data.sync.*
import io.theficos.spunto.data.local.*
import io.theficos.spunto.plugins.quickcapture.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun SpuntoApp(model:SpuntoViewModel,initialNoteId:String?=null,guideEligible:Boolean=false) {
 val pages=rememberSaveableStateHolder()
 val nav=rememberNavigator(initialNoteId,pages)
 val route=nav.route
 val guideStore=remember {FirstRunGuideStore(firstRunGuidePreferences(model.container.context))}
 var theme by remember { mutableStateOf(model.container.preferences.getString("theme","carta")!!) }
 var themeOverride by remember { mutableStateOf(model.container.preferences.contains("theme")) }
 var accent by remember { mutableStateOf(model.container.preferences.getString("theme-accent",null)) }
 var mode by remember { mutableStateOf(model.container.preferences.getString("mode","system")!!) }
 var black by remember { mutableStateOf(model.container.preferences.getBoolean("black",false)) }
 var wallpaper by remember { mutableStateOf(model.container.preferences.getBoolean("wallpaper",false)) }
 var readingFace by remember { mutableStateOf(normalizedReadingFace(model.container.preferences.getString("reading-face","serif").orEmpty())) }
 var readingSize by remember { mutableStateOf(normalizedReadingSize(model.container.preferences.getString("reading-size","M").orEmpty())) }
 val auth by model.auth.collectAsState(); val reauth by model.reauthenticate.collectAsState(); val sync by model.status.collectAsState()
 var navigationOpen by remember { mutableStateOf(false) }
 var noteBack by remember(route) {mutableStateOf<(() -> Unit)?>(null)}
 var filtersOpen by remember { mutableStateOf(false) }
 val filterable=route in setOf("timeline","archive","trash","search") || route.startsWith("label:") || route.startsWith("view:")
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
 // While a note is being edited, Back finishes the edit before leaving.
 fun navigateBack() { if(route.startsWith("note:") && noteBack!=null) noteBack?.invoke() else nav.back() }
 BackHandler(nav.canGoBack && !navigationOpen) { navigateBack() }
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
 val topBar=remember { TopBarSlot() }
 val snackbar=remember { SnackbarHostState() };val feedbackScope=rememberCoroutineScope();val feedback=remember { Feedback(snackbar,feedbackScope) }
 CompositionLocalProvider(LocalNavigator provides nav,LocalTopBar provides topBar,LocalFeedback provides feedback) {
 SpuntoTheme(activeSelection.id,mode,black,wallpaper,pack=activeSelection.mode(dark),readingFace=readingFace,readingSize=readingSize) {
  FirstRunGuideGate(guideStore,eligible=guideEligible && initialNoteId==null && route=="timeline" && !reauth,replay=route=="guide",canConnect=auth==null,onStartWriting={nav.home()},onSkipReplay={nav.back()},onConnect={nav.open("login")},content=appContent@{
  if(reauth || route=="login") {
   LoginScreen(model,reauth || sync==SyncStatus.NeedsSignIn,onDone={model.reauthenticate.value=false;nav.home()}); return@appContent
  }
  // Each stack entry keeps its own saved state (list scroll, date filter, field text) until it is popped.
  // key() gives every page fresh nodes: reusing the previous page's nodes left its pixels on screen.
  key(nav.stateKey) { pages.SaveableStateProvider(nav.stateKey) {
  var createdFrom by rememberSaveable { mutableStateOf("") }
  var createdTo by rememberSaveable { mutableStateOf("") }
  Scaffold(topBar={TopAppBar(title={
   if(isLabelsNavigationRoute(route)) TextButton(onClick={navigationOpen=true},modifier=Modifier.semantics {contentDescription="Choose labels and views"}) {Text("${navigationTitle(route,navigationLabels,manifest)} ▾",style=MaterialTheme.typography.titleMedium,color=MaterialTheme.colorScheme.onSurface,maxLines=1,overflow=TextOverflow.Ellipsis)}
   else routeTitle(route)?.let { Text(it,maxLines=1,overflow=TextOverflow.Ellipsis) }
  },navigationIcon={if(!isLabelsNavigationRoute(route)) IconButton(onClick=::navigateBack) {Icon(painterResource(R.drawable.ic_arrow_back),contentDescription="Back",modifier=Modifier.size(24.dp))}},actions={
   if((isLabelsNavigationRoute(route) || route=="search") && model.container.manifest.enabled("ask")) TextButton(onClick={nav.open("ask")},modifier=Modifier.semantics {contentDescription="Ask your notes"}) {Text("Ask")}
   if(isLabelsNavigationRoute(route)) IconButton(onClick={nav.open("search")}) {Icon(painterResource(R.drawable.ic_search),contentDescription="Search",modifier=Modifier.size(24.dp))}
   if(filterable) IconButton(onClick={filtersOpen=true}) {
    BadgedBox(badge={if(createdFrom.isNotBlank() || createdTo.isNotBlank()) Badge()}) {Icon(painterResource(R.drawable.ic_filter),contentDescription=if(createdFrom.isNotBlank() || createdTo.isNotBlank()) "Filters applied" else "Filter notes",modifier=Modifier.size(24.dp))}
   }
   if(route=="timeline") IconButton(onClick={nav.open("settings")},modifier=Modifier.size(48.dp)) {
    Icon(painterResource(R.drawable.ic_settings),contentDescription="Settings",modifier=Modifier.size(24.dp),tint=MaterialTheme.colorScheme.onSurfaceVariant)
   }
   topBar.actions?.invoke(this)
  },windowInsets=WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal+WindowInsetsSides.Top))},snackbarHost={SnackbarHost(snackbar)},contentWindowInsets=WindowInsets.safeDrawing) { padding ->
   // Clear of bars, cutouts and the keyboard; on wide screens the page stays a readable column.
   Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding),contentAlignment=Alignment.TopCenter) { Column(Modifier.widthIn(max=640.dp).fillMaxSize()) {
    if(filtersOpen && filterable) NoteFiltersSheet(createdFrom,createdTo,onDismiss={filtersOpen=false},onApply={from,to ->createdFrom=from;createdTo=to;filtersOpen=false})
    if(navigationOpen) LabelsSheet(LabelsNavigationSnapshot(route,navigationLabels,manifest),onRoute={requested ->navigationOpen=false;nav.filter(requested)},onSettings={navigationOpen=false;nav.open("settings")},onManageLabels={openManagement(it)},onManageViews={openManagement()},onDismiss={navigationOpen=false},managementAvailable=management.url!=null,managementMessage=management.message)
    StatusLines(model)
    val openNote:(String)->Unit={nav.open("note:$it")}
    when {
     route=="timeline" -> Timeline(model,onNote=openNote,onReview={nav.open("review")},from=createdFrom,to=createdTo)
     route.startsWith("note:") -> NoteScreen(model,route.substringAfter(':'),onClose={nav.back()},onNote=openNote,onBackReady={noteBack=it})
     route=="search" -> SearchScreen(model,onNote=openNote,from=createdFrom,to=createdTo)
     route=="ask" -> AskScreen(model,onNote=openNote)
     route=="labels" -> LabelsScreen(model) { nav.open("label:$it") }
     route=="settings" -> SettingsLanding(onRoute={nav.open(it)},onConnect={nav.open("login")},isConnected=auth!=null)
     route=="archive" || route=="trash" || route.startsWith("label:") || route.startsWith("view:") -> FilteredTimeline(model,route,from=createdFrom,to=createdTo,onNote=openNote)
     route=="review" -> ReviewScreen(model,openNote)
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
     route=="devices" -> DevicesScreen(model,onLogin={nav.open("login")})
     route=="data" || route=="profile" -> OwnerWebSettingsScreen(model,route)
     route=="status" -> StatusScreen(model)
     route=="ai" -> AISettingsScreen(model)
    }
    val remote by model.remote.collectAsState()
    if(online && remote.string("plugin_id").isNotBlank() && model.container.manifest.enabled(remote.string("plugin_id"))) CommandResultSheet(model,remote,openNote,{model.remote.value=it},{model.remote.value=JsonObject(emptyMap())})
    if(remote.string("suggestion_preview").isNotBlank()) {
     val outputs by model.outputs.collectAsState()
     outputs.firstOrNull { it.id==remote.string("suggestion_preview") && it.kind=="suggestion" && model.container.manifest.enabled(payload(it.payload).string("plugin")) }?.let { item -> SuggestionDecisionDialog(model,proposalData(item),openNote,{model.remote.value=JsonObject(emptyMap())}) }
    }
   } }
  }
  } }
  })
 }
 }
}
