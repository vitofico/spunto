@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package io.theficos.spunto

import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController

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
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
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
@Composable fun SpuntoApp(model:SpuntoViewModel,initialNoteId:String?=null,guideEligible:Boolean=false,initialActivity:Boolean=false) {
 val context=LocalContext.current
 val pages=rememberSaveableStateHolder()
 val nav=rememberNavigator(initialNoteId,pages,initialActivity)
 val route=nav.route
 val guideStore=remember {FirstRunGuideStore(firstRunGuidePreferences(model.container.context))}
 val focusManager=LocalFocusManager.current
 val keyboard=LocalSoftwareKeyboardController.current
 var focusCapture by rememberSaveable { mutableStateOf(false) }
 var captureExpanded by rememberSaveable { mutableStateOf(false) }
 var theme by remember { mutableStateOf(model.container.preferences.getString("theme","carta")!!) }
 var themeOverride by remember { mutableStateOf(model.container.preferences.contains("theme")) }
 var accent by remember { mutableStateOf(model.container.preferences.getString("theme-accent",null)) }
 var mode by remember { mutableStateOf(model.container.preferences.getString("mode","system")!!) }
 var black by remember { mutableStateOf(model.container.preferences.getBoolean("black",false)) }
 var backgroundStyle by remember { mutableStateOf(normalizedBackgroundStyle(model.container.preferences.getString("background-style","artwork"))) }
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
 val catalog=model.catalog.collectAsState().value
 val manifestRaw=remember(manifest) { manifest.toString() }
 val manifestFingerprint=remember(manifestRaw) { themeManifestFingerprint(manifestRaw) }
 var validatedManifest by remember { mutableStateOf<Pair<String,ThemeManifest>?>(null) }
 val appearanceManifest=validatedManifest?.takeIf { it.first==manifestFingerprint }?.second
 LaunchedEffect(manifestRaw) { validatedManifest=manifestFingerprint to withContext(Dispatchers.Default) { decodeThemeManifest(manifestRaw) } }
 val online by model.online.collectAsState()
 val writable by model.workspaceCanWrite.collectAsState()
 val editingCapture=route=="timeline" && writable && captureExpanded
 fun closeCapture() {
  if(captureExpanded) {
   focusManager.clearFocus()
   keyboard?.hide()
  }
  captureExpanded=false
  focusCapture=false
 }
 LaunchedEffect(writable) { if(!writable) closeCapture() }
 val owner by model.workspaceIsOwner.collectAsState()
 val epoch by model.pluginReplicaEpochState.collectAsState()
 val canManageCollections=owner && auth!=null && collectionsAvailable(manifest)
 val canManageDecisions=owner && auth!=null && libraryCommandAvailable(manifest,DecisionJournalId,"journal")
 val deskAvailable=thinkingDeskAvailable(manifest,owner && auth!=null)
 val canAsk=owner && auth!=null && libraryCommandAvailable(manifest,"ask","ask")
 val canViewStats=owner && auth!=null && libraryCommandAvailable(manifest,"library-stats","summary")
 var statsOpen by remember(auth,epoch) {mutableStateOf(false)}
 LaunchedEffect(canViewStats) {if(!canViewStats) statsOpen=false}
 val navigationLabel=context.getString(if(deskAvailable || canViewStats || canManageCollections || canManageDecisions) R.string.core_open_navigation else R.string.core_choose_labels)
 val deskSession=if(deskAvailable && nav.contains(ThinkingDeskRoute)) remember(auth,epoch) { ThinkingDeskSession() } else null
 LaunchedEffect(manifest) {
  model.container.manifest.withCurrentPublication {
  val quick=model.container.manifest.enabled("quick-capture"); val dictate=model.container.manifest.enabled("dictation")
  CaptureSurfaces.apply(model.container.context,quick,dictate)
  val manager=model.container.context.getSystemService(ShortcutManager::class.java)
  val share=ShortcutInfo.Builder(model.container.context,"share-note").setShortLabel("spunto").setCategories(setOf("io.theficos.spunto.SHARE")).setIntent(CaptureSurfaces.intent(model.container.context).setAction(Intent.ACTION_SEND)).build()
  manager.dynamicShortcuts=listOf(share)+if(dictate) listOf(ShortcutInfo.Builder(model.container.context,"dictate").setShortLabel(context.getString(R.string.core_dictate_note)).setIntent(CaptureSurfaces.intent(model.container.context,true).setAction(Intent.ACTION_VIEW)).build()) else emptyList()
  }
 }
 // While a note is being edited, Back finishes the edit before leaving.
 fun navigateBack() {
  when {
   route.substringBefore(':') in setOf("note","note-edit") && noteBack!=null -> noteBack?.invoke()
   route==ThinkingDeskRoute && deskSession?.busy==true -> Unit
   route==ThinkingDeskRoute && deskSession?.insideDesk==true -> deskSession.reset()
   else -> nav.back()
  }
 }
 BackHandler(nav.canGoBack && !navigationOpen) { navigateBack() }
 BackHandler(navigationOpen) { navigationOpen=false }
 BackHandler(editingCapture && !navigationOpen && !reauth) { closeCapture() }
 val management=sharedManagementTarget(context,auth,replicaState?.serverUrl,online,needsSignIn=reauth || sync==SyncStatus.NeedsSignIn)
 fun openManagement(labelPath:String?=null) {
  val target=sharedManagementTarget(context,model.auth.value,replicaState?.serverUrl,model.online.value,labelPath,model.reauthenticate.value || model.status.value==SyncStatus.NeedsSignIn)
  val url=target.url
  if(url==null) {model.message.value=target.message;return}
  try {
   model.container.context.startActivity(Intent(Intent.ACTION_VIEW,android.net.Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
   navigationOpen=false
  } catch(_:ActivityNotFoundException) {model.message.value=context.getString(R.string.core_no_browser)}
  catch(_:SecurityException) {model.message.value=context.getString(R.string.core_open_shared_failed)}
 }
 LaunchedEffect(mode) { applyWindowNightMode(model.container.context,mode) }
 val dark=mode=="dark" || mode=="system" && isSystemInDarkTheme()
 val instance=manifest["theme"] as? JsonObject
 val requestedTheme=if(themeOverride) theme else appearanceManifest?.instance?.id ?: instance?.string("id")?.takeIf { it.isNotBlank() } ?: "carta"
 val requestedAccent=if(themeOverride) accent else appearanceManifest?.instance?.accent ?: instance?.string("accent")?.takeIf { it.isNotBlank() }
 val snapshot=remember(manifestFingerprint,themeOverride,theme,accent) { readSelectedThemeSnapshot(model.container.preferences,manifestFingerprint) }
 val activeSelection=if(appearanceManifest!=null) {
  if(themeOverride) resolveThemeSelection(theme,accent,appearanceManifest.catalog) else appearanceManifest.instance
 } else snapshot ?: resolveThemeSelection(requestedTheme,requestedAccent)
 LaunchedEffect(activeSelection,wallpaper) {
  // 03 A11: the widget takes the wallpaper's paper and accent only when the app does.
  val context=model.container.context;val (day,night)=if(wallpaper && android.os.Build.VERSION.SDK_INT>=31) dynamicLightColorScheme(context) to dynamicDarkColorScheme(context) else null to null
  fun Map<String,String>.over(scheme:ColorScheme?)=scheme?.let { this+mapOf("paper" to it.background,"accent" to it.primary).mapValues { (_,color) -> "#%06X".format(color.toArgb() and 0xFFFFFF) } } ?: this
  CaptureSurfaces.palette(context,activeSelection.light.over(day),activeSelection.dark.over(night))
 }
 LaunchedEffect(appearanceManifest,themeOverride,requestedTheme,requestedAccent,activeSelection) {
  if(appearanceManifest!=null) storeSelectedThemeSnapshot(model.container.preferences,SelectedThemeSnapshot(themeOverride,requestedTheme,requestedAccent,manifestFingerprint,activeSelection))
 }
 val topBar=remember { TopBarSlot() };val bottomBar=remember { BottomBarSlot() }
 val snackbar=remember { SnackbarHostState() };val feedbackScope=rememberCoroutineScope();val feedback=remember { Feedback(snackbar,feedbackScope) }
 CompositionLocalProvider(LocalNavigator provides nav,LocalTopBar provides topBar,LocalBottomBar provides bottomBar,LocalFeedback provides feedback) {
 SpuntoTheme(activeSelection.id,mode,black,wallpaper,pack=activeSelection.mode(dark),readingFace=readingFace,readingSize=readingSize,backgroundStyle=backgroundStyle) {
  FirstRunGuideGate(guideStore,eligible=guideEligible && initialNoteId==null && route=="timeline" && !reauth,replay=route=="guide",canConnect=auth==null,onStartWriting={
   nav.home()
   if(writable) focusCapture=true else context.startActivity(localCaptureIntent(context))
  },onSkipReplay={nav.back()},onConnect={nav.open("login")},content=appContent@{
  if(reauth || route=="login") {
   LoginScreen(model,reauth || sync==SyncStatus.NeedsSignIn,onDone={model.reauthenticate.value=false;nav.home()}); return@appContent
  }
  // Each stack entry keeps its own saved state (list scroll, date filter, field text) until it is popped.
  // key() gives every page fresh nodes: reusing the previous page's nodes left its pixels on screen.
  key(nav.stateKey) { pages.SaveableStateProvider(nav.stateKey) {
  var createdFrom by rememberSaveable { mutableStateOf("") }
  var createdTo by rememberSaveable { mutableStateOf("") }
  val brandedHeader=route=="timeline" && !editingCapture && !WindowInsets.isImeVisible
  val headerActions:@Composable RowScope.()->Unit={
   if(route=="timeline" && canAsk) AskNotesButton {nav.open("ask")}
   if(isLabelsNavigationRoute(route)) IconButton(onClick={nav.open("search")}) {Icon(painterResource(R.drawable.ic_search),contentDescription=context.getString(R.string.core_search),modifier=Modifier.size(24.dp))}
   if(filterable) IconButton(onClick={filtersOpen=true}) {
    BadgedBox(badge={if(createdFrom.isNotBlank() || createdTo.isNotBlank()) Badge()}) {Icon(painterResource(R.drawable.ic_filter),contentDescription=if(createdFrom.isNotBlank() || createdTo.isNotBlank()) context.getString(R.string.core_filters_applied) else context.getString(R.string.core_filter_notes),modifier=Modifier.size(24.dp))}
   }
   if(route=="timeline") IconButton(onClick={nav.open("settings")},modifier=Modifier.size(48.dp)) {
    Icon(painterResource(R.drawable.ic_settings),contentDescription=context.getString(R.string.core_settings),modifier=Modifier.size(24.dp),tint=MaterialTheme.colorScheme.onSurfaceVariant)
   }
   topBar.actions?.invoke(this)
  }
  val headerTitle:@Composable (Boolean,Modifier)->Unit={compact,modifier ->
   if(isLabelsNavigationRoute(route)) TextButton(onClick={navigationOpen=true},modifier=modifier.semantics {contentDescription=navigationLabel}) {Text("${navigationTitle(context,route,navigationLabels,manifest,catalog)} ▾",style=if(compact) MaterialTheme.typography.labelMedium else MaterialTheme.typography.titleMedium,color=MaterialTheme.colorScheme.onSurface,maxLines=1,overflow=TextOverflow.Ellipsis)}
   else routeTitle(context,route)?.let { Text(it,modifier=modifier,style=MaterialTheme.typography.titleMedium,maxLines=1,overflow=TextOverflow.Ellipsis) }
  }
  Scaffold(modifier=Modifier.spuntoBackground(enabled=filterable && route!="timeline" || route in setOf("settings","appearance","plugins","status",WorkspaceModelsRoute,WhatsNewRoute)),containerColor=androidx.compose.ui.graphics.Color.Transparent,contentColor=MaterialTheme.colorScheme.onSurface,topBar={
   if(editingCapture) TopAppBar(
    title={ Text(context.getString(R.string.new_note)) },
    navigationIcon={ IconButton(onClick=::closeCapture) {
     Icon(painterResource(R.drawable.ic_arrow_back),contentDescription=context.getString(R.string.core_back))
    } },
    windowInsets=WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal+WindowInsetsSides.Top),
   )
   else if(brandedHeader) {
    val density=LocalDensity.current
    val textMeasurer=rememberTextMeasurer()
    Column(Modifier.fillMaxWidth().spuntoBackground()
     .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top+WindowInsetsSides.Horizontal))) {
     BoxWithConstraints(Modifier.fillMaxWidth()) {
      val wordmarkSize=when {
       density.fontScale>1.3f && maxWidth<360.dp -> 23.sp
       density.fontScale>1.3f || maxWidth<300.dp -> 27.sp
       maxWidth<360.dp -> 34.sp
       else -> 40.sp
      }
      val brandStyle=MaterialTheme.typography.displayLarge.copy(fontSize=wordmarkSize,
       lineHeight=wordmarkSize*1.15f,fontWeight=FontWeight.SemiBold,letterSpacing=(-2.3).sp)
      // Leave room for the wordmark and the actual translated label after accounting for padding and icons.
      val labelFits=canAsk && density.fontScale<=1.3f && run {
       val brandWidth=textMeasurer.measure("spunto.",style=brandStyle,maxLines=1).size.width
       val labelWidth=textMeasurer.measure(context.getString(R.string.core_ask),style=MaterialTheme.typography.labelLarge,maxLines=1).size.width
       val chromeWidth=with(density) {(32.dp+96.dp+48.dp+6.dp).roundToPx()}
       brandWidth+labelWidth+chromeWidth<=constraints.maxWidth
      }
      Row(Modifier.fillMaxWidth().padding(start=20.dp,end=12.dp).heightIn(min=64.dp),verticalAlignment=Alignment.CenterVertically) {
       SpuntoWordmark(Modifier.weight(1f),style=brandStyle)
       IconButton(onClick={nav.open("search")},modifier=Modifier.size(48.dp)) {Icon(painterResource(R.drawable.ic_search),contentDescription=context.getString(R.string.core_search),modifier=Modifier.size(21.dp),tint=MaterialTheme.colorScheme.onSurfaceVariant)}
       if(canAsk) AskNotesButton(showLabel=labelFits) {nav.open("ask")}
       IconButton(onClick={nav.open("settings")},modifier=Modifier.size(48.dp)) {Icon(painterResource(R.drawable.ic_settings_outline),contentDescription=context.getString(R.string.core_settings),modifier=Modifier.size(21.dp),tint=MaterialTheme.colorScheme.onSurfaceVariant)}
      }
     }
     Row(Modifier.fillMaxWidth().padding(start=20.dp,end=12.dp),verticalAlignment=Alignment.CenterVertically) {
      Box(Modifier.weight(1f)) {
       TextButton(onClick={navigationOpen=true},contentPadding=PaddingValues(0.dp),modifier=Modifier.heightIn(min=48.dp).semantics {contentDescription=navigationLabel}) {
        Text(navigationTitle(context,route,navigationLabels,manifest,catalog),style=SpuntoType.reading.copy(fontSize=25.sp,lineHeight=31.sp,fontWeight=FontWeight.SemiBold),color=MaterialTheme.colorScheme.onSurface,maxLines=2,overflow=TextOverflow.Ellipsis)
        Spacer(Modifier.width(6.dp))
        Icon(painterResource(R.drawable.ic_expand),contentDescription=null,modifier=Modifier.size(16.dp))
       }
      }
      val filtered=createdFrom.isNotBlank() || createdTo.isNotBlank()
      IconButton(onClick={filtersOpen=true},modifier=Modifier.size(48.dp)) {
       BadgedBox(badge={if(filtered) Badge()}) {Icon(painterResource(R.drawable.ic_filter),contentDescription=context.getString(if(filtered) R.string.core_filters_applied else R.string.core_filter_notes),modifier=Modifier.size(21.dp),tint=MaterialTheme.colorScheme.onSurfaceVariant)}
      }
      val hasTools=owner && online && globalTools(catalog,manifest).isNotEmpty()
      if(hasTools) Box {
       var more by remember {mutableStateOf(false)}
       IconButton(onClick={more=true},modifier=Modifier.size(48.dp)) {Icon(painterResource(R.drawable.ic_more),contentDescription=context.getString(R.string.core_more_actions),modifier=Modifier.size(18.dp).rotate(90f),tint=MaterialTheme.colorScheme.onSurfaceVariant)}
       DropdownMenu(expanded=more,onDismissRequest={more=false}) {
        DropdownMenuItem(text={Text(context.getString(R.string.plugin_global_tools))},onClick={more=false;nav.open("tools")})
       }
      }
     }
    }
   }
   else TopAppBar(title={headerTitle(false,Modifier)},navigationIcon={if(!isLabelsNavigationRoute(route)) IconButton(onClick=::navigateBack) {Icon(painterResource(R.drawable.ic_arrow_back),contentDescription=context.getString(R.string.core_back),modifier=Modifier.size(24.dp))}},actions=headerActions,windowInsets=WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal+WindowInsetsSides.Top))
  },bottomBar={bottomBar.content?.let { bar -> Surface(color=MaterialTheme.colorScheme.background) { Box(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars)),contentAlignment=Alignment.Center) { Box(Modifier.widthIn(max=640.dp)) { bar() } } } }},
   snackbarHost={if(route!="timeline" || editingCapture) SnackbarHost(snackbar)},contentWindowInsets=WindowInsets.safeDrawing) { padding ->
   // Clear of bars, cutouts and the keyboard; on wide screens the page stays a readable column.
   Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding),contentAlignment=Alignment.TopCenter) { Column(Modifier.widthIn(max=640.dp).fillMaxSize()) {
    if(filtersOpen && filterable) NoteFiltersSheet(createdFrom,createdTo,onDismiss={filtersOpen=false},onApply={from,to ->createdFrom=from;createdTo=to;filtersOpen=false})
    if(navigationOpen) LabelsSheet(LabelsNavigationSnapshot(route,navigationLabels,manifest,catalog),onRoute={requested ->navigationOpen=false;nav.filter(requested)},onSettings={navigationOpen=false;nav.open("settings")},onManageLabels={openManagement(it)},onManageViews={openManagement()},onDismiss={navigationOpen=false},managementAvailable=management.url!=null,managementMessage=management.message,reviewWaiting=waitingProposals(model,model.outputs.collectAsState().value).size,allNotes=model.noteCount.collectAsState().value,onCollections=if(canManageCollections) {{navigationOpen=false;nav.open(CollectionsRoute)}} else null,onThinkingDesk=if(deskAvailable) {{navigationOpen=false;nav.open(ThinkingDeskRoute)}} else null,onLibraryStats=if(canViewStats) {{navigationOpen=false;statsOpen=true}} else null,onDecisions=if(canManageDecisions) {{navigationOpen=false;nav.open(DecisionManagerRoute)}} else null)
    StatusLines(model)
    if(!editingCapture && filterable && (createdFrom.isNotBlank() || createdTo.isNotBlank())) DateFilterPill(createdFrom,createdTo) {createdFrom="";createdTo=""}
    val openNote:(String)->Unit={nav.open("note:$it")}
    when {
     route=="timeline" -> Timeline(model,onNote=openNote,onReview={nav.open("review")},from=createdFrom,to=createdTo,
      focusCapture=focusCapture,onCaptureFocused={focusCapture=false},captureExpanded=editingCapture,
      onExpandCapture={ captureExpanded=true;focusCapture=true },onCollapseCapture=::closeCapture,
      snackbarContent={SnackbarHost(snackbar)})
     route.substringBefore(':') in setOf("note","note-edit") -> NoteScreen(model,route.substringAfter(':'),onClose={nav.back()},onNote=openNote,onBackReady={noteBack=it},startEditing=route.startsWith("note-edit:"))
     route=="search" -> SearchScreen(model,onNote=openNote,from=createdFrom,to=createdTo)
     route=="tools" -> if(owner) GlobalToolsScreen(model)
     route.substringBefore(':')==CollectionsRoute -> CollectionsScreen(model,route,onNote=openNote)
     route==ThinkingDeskRoute -> if(deskSession!=null) ThinkingDeskScreen(model,deskSession,openNote)
      else Text(context.getString(R.string.settings_this_plugin_is_unavailable_sync_to_refresh),Modifier.padding(SpuntoSpacing.s20))
     route=="ask" || route.startsWith("ask:") -> if(canAsk) AskScreen(model,onNote=openNote,collectionId=route.substringAfter(':',"").ifBlank {null})
      else Text(context.getString(R.string.settings_this_plugin_is_unavailable_sync_to_refresh),Modifier.padding(SpuntoSpacing.s20))
     route==ActivityRoute -> SharedActivityScreen(model,openNote)
     route==WorkspaceModelsRoute -> WorkspaceModelsScreen(model)
     route=="settings" -> SettingsScreen(model,appearanceSummary(context.resources,activeSelection.id,appearanceManifest?.catalog,themeOverride,mode,readingFace,readingSize),onRoute={nav.open(it)})
     route==WhatsNewRoute -> WhatsNewScreen(onAppearance={nav.open("appearance")})
     route==DecisionManagerRoute || route==DecisionViewRoute || route.startsWith(DecisionDetailPrefix) ->
      DecisionManager(model,route.takeIf {it.startsWith(DecisionDetailPrefix)}?.removePrefix(DecisionDetailPrefix),
       {nav.open(DecisionDetailPrefix+it)},openNote,{nav.open("note-edit:$it")},{nav.back()})
     route=="archive" || route=="trash" || route.startsWith("label:") || route.startsWith("view:") -> FilteredTimeline(model,route,from=createdFrom,to=createdTo,onNote=openNote)
     route=="review" -> ReviewScreen(model,openNote)
     route=="appearance" -> AppearanceScreen(requestedTheme,mode,black,wallpaper,{ choice ->
      val selected=resolveThemeSelection(choice,null,appearanceManifest?.catalog)
      applyDeviceTheme(model.container.preferences,choice,null,selected,manifestFingerprint,clearWallpaper=true);theme=choice;accent=null;themeOverride=true;wallpaper=false
     },{mode=it;model.container.preferences.edit().putString("mode",it).apply()},{black=it;model.container.preferences.edit().putBoolean("black",it).apply()},{wallpaper=it;model.container.preferences.edit().putBoolean("wallpaper",it).apply()},themeOverride=themeOverride,onUseInstance={
      val selected=appearanceManifest?.instance ?: resolveThemeSelection(instance?.string("id") ?: "carta",instance?.string("accent")?.takeIf { it.isNotBlank() })
      applyDeviceTheme(model.container.preferences,null,null,selected,manifestFingerprint,clearWallpaper=true);themeOverride=false;accent=null;wallpaper=false
     },catalog=appearanceManifest?.catalog ?: ThemeCatalog(builtInThemeCards,false),accent=if(themeOverride) accent else activeSelection.accent,onAccent={ choice ->
      val selected=resolveThemeSelection(requestedTheme,choice,appearanceManifest?.catalog)
      applyDeviceTheme(model.container.preferences,requestedTheme,choice,selected,manifestFingerprint,clearWallpaper=true);theme=requestedTheme;accent=choice;themeOverride=true;wallpaper=false
     },instanceName=appearanceManifest?.let { value -> value.catalog.cards.firstOrNull { it.id==value.instance.id }?.name } ?: builtInThemes.firstOrNull { it.id==instance?.string("id") }?.name ?: "Carta",readingFace=readingFace,readingSize=readingSize,onReadingFace={readingFace=it;model.container.preferences.edit().putString("reading-face",it).apply()},onReadingSize={readingSize=it;model.container.preferences.edit().putString("reading-size",it).apply()},backgroundStyle=backgroundStyle,onBackgroundStyle={backgroundStyle=it;model.container.preferences.edit().putString("background-style",it).apply()})
     route=="plugins" -> PluginsScreen(model) { nav.open("plugin:$it") }
     route.startsWith("plugin:") -> PluginScreen(model,route.substringAfter(':'),onReview={nav.open("review")})
    }
    if(statsOpen && canViewStats) LibraryStatsDialog(model,openNote,{statsOpen=false})
    val remote by model.remote.collectAsState()
    val decisionAccess=owner && auth?.serverUrl==remote.string("authority") && remote.string("replica_epoch")==epoch &&
     model.container.manifest.enabled(DecisionJournalId)
    val journal=journalResult(remote)
    LaunchedEffect(writable,decisionAccess,journal) {
     if(!writable || journal && !decisionAccess) model.remote.value=JsonObject(emptyMap())
    }
    if(writable && (if(journal) decisionAccess else online) && remote.string("plugin_id").isNotBlank() && model.container.manifest.enabled(remote.string("plugin_id"))) {
     val onReview:(()->Unit)?=if(route=="plugin:label-gardener" && remote.string("plugin_id")=="label-gardener" && remote.string("command_id")=="review-now") {{nav.open("review")}} else null
     CommandResultSheet(model,remote,openNote,{model.remote.value=it},{model.remote.value=JsonObject(emptyMap())},onReview=onReview)
    }
    if(writable && remote.string("suggestion_preview").isNotBlank()) {
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
