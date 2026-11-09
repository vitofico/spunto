package io.theficos.spunto

import android.content.res.Resources
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.room.withTransaction
import io.theficos.spunto.core.design.SpuntoSpacing
import io.theficos.spunto.core.model.Note
import io.theficos.spunto.core.plugins.objects
import io.theficos.spunto.core.plugins.string
import io.theficos.spunto.data.sync.ApiClient
import io.theficos.spunto.data.sync.ApiException
import io.theficos.spunto.data.sync.AuthConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

internal fun supportsNotePicker(manifest:JsonObject,pluginId:String):Boolean {
 val version=manifest.objects("plugins").firstOrNull {it.string("id")==pluginId}?.string("version")?.split('.') ?: return false
 val major=version.first().toIntOrNull() ?: return false
 val minor=version.getOrNull(1)?.toIntOrNull() ?: return false
 return major>1 || major==1 && minor>=1
}

internal class NotePickerSession private constructor(private val config:AuthConfig,private val epoch:String,private val lockRevision:Long,private val pluginId:String) {
 fun current(model:SpuntoViewModel)=model.workspaceCanWrite.value && model.auth.value==config && model.pluginReplicaEpoch==epoch &&
  model.container.appLock.state.value.let {it.unlocked && it.revision==lockRevision} &&
  model.container.manifest.enabled(pluginId) && supportsNotePicker(model.container.manifest.value,pluginId)

 suspend fun authorized(model:SpuntoViewModel):Boolean {
  val state=model.repository.dao.state() ?: return false
  return model.container.auth.config.first()==config && state.epoch==epoch &&
   state.replicaId==config.replicaId && state.serverUrl==config.serverUrl.trimEnd('/') && current(model)
 }

 fun api(model:SpuntoViewModel)=ApiClient(config).guarded {authorized(model)}

 suspend fun reportError(model:SpuntoViewModel,resources:Resources,error:Exception):String {
  if(!authorized(model)) throw CancellationException()
  if(error is ApiException && error.status==401) model.reauthenticate.value=true
  return notePickerError(resources,error)
 }

 suspend fun refresh(model:SpuntoViewModel) {
  if(!authorized(model)) throw CancellationException()
  model.synchronize().join()
  if(!authorized(model)) throw CancellationException()
 }

 suspend fun cleanSource(model:SpuntoViewModel,sourceId:String):Note {
  val resources=model.container.context.resources
  return model.container.database.withTransaction {
   val dao=model.repository.dao
   val source=dao.get(sourceId)?.note()
   require(source!=null && !source.trashed) {resources.getString(R.string.settings_this_note_is_no_longer_available)}
   require(!source.pending && source.serverRev>0) {resources.getString(R.string.settings_this_note_has_changes_waiting_to_sync)}
   val draft=dao.unsavedDraft(sourceId)
   require(draft==null || draft.body==source.body) {resources.getString(R.string.core_link_unsaved)}
   source
  }
 }

 companion object {
  fun open(model:SpuntoViewModel,pluginId:String):NotePickerSession? {
   val config=model.auth.value ?: return null
   val epoch=model.pluginReplicaEpoch.takeIf(String::isNotBlank) ?: return null
   return NotePickerSession(config,epoch,model.container.appLock.state.value.revision,pluginId).takeIf {it.current(model)}
  }
 }
}

@Composable internal fun NoteLinkPickerSheet(
 model:SpuntoViewModel,sourceId:String,sourceVersion:Int,session:NotePickerSession,onClose:()->Unit,onInserted:(Int)->Unit
) {
 val auth by model.auth.collectAsState()
 val epoch by model.pluginReplicaEpochState.collectAsState()
 val manifest by model.manifest.collectAsState()
 val gate by model.container.appLock.state.collectAsState()
 val online by model.online.collectAsState()
 val current=remember(auth,epoch,manifest,gate,session) {session.current(model)}
 if(!current) {
  LaunchedEffect(Unit) {onClose()}
  return
 }
 val api=remember(model,session) {session.api(model)}
 NoteLinkPicker(
  sourceId,online,
  search={query,cursor -> searchPickerNotes(api,query,cursor)},
  insert={target ->
   val source=session.cleanSource(model,sourceId)
   require(source.serverRev==sourceVersion) {model.container.context.getString(R.string.core_link_source_changed)}
   val result=api.post("/api/v1/plugins/links/commands/link",buildJsonObject {
    put("note_id",source.id)
    put("target_id",target.id)
    put("base_version",source.serverRev)
    put("target_version",target.serverRev)
    put("insert",true)
   })
   val receipt=when(result.string("text")) {
    "Link inserted." -> R.string.core_link_inserted
    "This note already links to the selected note." -> R.string.core_link_already_inserted
    else -> error("unexpected_link_receipt")
   }
   session.refresh(model)
   val local=model.repository.dao.get(source.id)
   if(!session.authorized(model)) throw CancellationException()
   val synced=if(receipt==R.string.core_link_inserted) (local?.serverRev ?: 0)>source.serverRev else {
    val body=local?.body.orEmpty()
    "](/n/${target.id})" in body || "](note://${target.id})" in body
   }
   if(synced) receipt else R.string.core_link_waiting_to_sync
  },
  reportError={resources,error -> session.reportError(model,resources,error)},
  onClose=onClose,onInserted=onInserted
 )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun NoteLinkPicker(
 sourceId:String,
 online:Boolean,
 search:suspend (query:String,cursor:String?)->JsonObject,
 insert:suspend (Note)->Int,
 reportError:suspend (Resources,Exception)->String,
 onClose:()->Unit,
 onInserted:(Int)->Unit
) {
 val resources=LocalContext.current.resources
 val scope=rememberCoroutineScope()
 var refresh by remember {mutableIntStateOf(0)}
 var inserting by remember {mutableStateOf(false)}
 var insertError by remember {mutableStateOf("")}
 val close={if(!inserting) onClose()}
 val sheet=rememberModalBottomSheetState(skipPartiallyExpanded=true,confirmValueChange={it!=SheetValue.Hidden || !inserting})
 ModalBottomSheet(onDismissRequest=close,sheetState=sheet) {
  Column(Modifier.fillMaxWidth().fillMaxHeight(.88f).imePadding().navigationBarsPadding().padding(horizontal=SpuntoSpacing.s20),
   verticalArrangement=Arrangement.spacedBy(SpuntoSpacing.s8)) {
   Text(stringResource(R.string.settings_bundled_title_11),style=MaterialTheme.typography.titleLarge,
    modifier=Modifier.semantics {heading()})
   NotePickerPanel(Modifier.weight(1f),setOf(sourceId),online,!inserting,refresh,search,reportError,
    onQueryChanged={insertError=""}) {selected ->
    if(insertError.isNotBlank()) Text(insertError,color=MaterialTheme.colorScheme.error,
     modifier=Modifier.semantics {liveRegion=LiveRegionMode.Polite})
    Row(Modifier.fillMaxWidth().padding(bottom=SpuntoSpacing.s8),horizontalArrangement=Arrangement.End) {
     TextButton(onClick=close,enabled=!inserting) {Text(stringResource(R.string.settings_cancel))}
     Button(enabled=online && selected!=null && !inserting,onClick={
      if(selected!=null && !inserting) {
       inserting=true;insertError=""
       scope.launch {
        try {onInserted(insert(selected))}
        catch(error:CancellationException) {throw error}
        catch(error:Exception) {
         insertError=reportError(resources,error)
         if(error is ApiException && (error.code=="version_conflict" || error.status==404)) refresh++
        }
        finally {inserting=false}
       }
      }
     }) {Text(stringResource(if(inserting) R.string.core_link_inserting else R.string.core_link_insert))}
    }
   }
  }
 }
}

internal suspend fun searchPickerNotes(api:ApiClient,query:String,cursor:String?):JsonObject =
 api.request("/api/v1/search",query=buildMap {
  put("q",query.trim())
  put("mode","keyword")
  put("include_archived","true")
  put("limit","24")
  cursor?.let {put("cursor",it)}
 }) as JsonObject

@Composable internal fun NotePickerPanel(
 modifier:Modifier,
 excludedIds:Set<String>,
 online:Boolean,
 enabled:Boolean,
 refreshKey:Any,
 search:suspend (query:String,cursor:String?)->JsonObject,
 reportError:suspend (Resources,Exception)->String,
 onQueryChanged:()->Unit={},
 actions:@Composable (Note?)->Unit
) {
 val resources=LocalContext.current.resources
 val locale=LocalConfiguration.current.locales[0]
 val dateFormat=remember(locale) {DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM).withLocale(locale)}
 val focus=LocalFocusManager.current
 var query by remember {mutableStateOf("")}
 var retry by remember {mutableIntStateOf(0)}
 var pages by remember(query,retry,online,refreshKey) {mutableStateOf(SearchPages())}
 var selected by remember(query,retry,online,refreshKey,excludedIds) {mutableStateOf<Note?>(null)}
 var loadRequests by remember(query,retry,online,refreshKey) {mutableIntStateOf(0)}
 var loading by remember(query,retry,online,refreshKey) {mutableStateOf(online)}
 var searchError by remember(query,retry,online,refreshKey) {mutableStateOf("")}

 LaunchedEffect(query,retry,online,refreshKey) {
  if(!online) return@LaunchedEffect
  val requested=query
  var loaded=loadRequests
  if(requested.isNotBlank()) delay(180)
  searchServerPages(request={cursor ->
   searchError=""
   loading=true
   try {search(requested,cursor)} finally {loading=false}
  },awaitMore={snapshotFlow {loadRequests}.first {it!=loaded};loaded=loadRequests},
   show={pages=it},failed={searchError=reportError(resources,it)})
 }

 Column(modifier.fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(SpuntoSpacing.s8)) {
  OutlinedTextField(query,{value -> if(value.length<=500) {selected=null;query=value;onQueryChanged()}},enabled=enabled,
   label={Text(stringResource(R.string.core_search_notes))},singleLine=true,modifier=Modifier.fillMaxWidth())
  if(online && query.isBlank()) Text(stringResource(R.string.core_picker_recent_notes),
   style=MaterialTheme.typography.labelLarge,modifier=Modifier.semantics {heading()})
  if(loading || !enabled) LinearProgressIndicator(Modifier.fillMaxWidth())
  val matches=pages.hits.filter {it.id !in excludedIds && !it.trashed}
  val terms=remember(query) {searchTerms(query)}
  LazyColumn(Modifier.weight(1f).fillMaxWidth().selectableGroup()) {
   if(!online) item {Text(stringResource(R.string.settings_connect_to_the_server_to_use_this))}
   else if(!loading && searchError.isBlank() && matches.isEmpty() && pages.cursor==null) item {
    Text(if(query.isBlank()) stringResource(R.string.core_picker_no_notes) else stringResource(R.string.core_search_empty,query.trim()))
   }
   items(matches,key={it.id}) {note ->
    val chosen=selected?.id==note.id
    Row(Modifier.fillMaxWidth().heightIn(min=56.dp).selectable(chosen,enabled=enabled,role=Role.RadioButton) {selected=note;focus.clearFocus()}
     .padding(vertical=SpuntoSpacing.s8),horizontalArrangement=Arrangement.spacedBy(SpuntoSpacing.s12),verticalAlignment=Alignment.CenterVertically) {
     RadioButton(selected=chosen,onClick=null,enabled=enabled)
     Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(SpuntoSpacing.s2)) {
      Text(note.title.ifBlank {stringResource(R.string.core_untitled)},style=MaterialTheme.typography.bodyLarge,maxLines=2,overflow=TextOverflow.Ellipsis)
      searchSnippet(note.body,terms)?.let {Text(it,style=MaterialTheme.typography.bodySmall,
       color=MaterialTheme.colorScheme.onSurfaceVariant,maxLines=2,overflow=TextOverflow.Ellipsis)}
      Text(Instant.ofEpochMilli(note.createdAt).atZone(ZoneId.systemDefault()).format(dateFormat),
       style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
     }
    }
   }
   if(searchError.isNotBlank()) item {
    Text(searchError,color=MaterialTheme.colorScheme.error,modifier=Modifier.semantics {liveRegion=LiveRegionMode.Polite})
    TextButton(enabled=enabled,onClick={if(pages.cursor==null) retry++ else loadRequests++}) {Text(stringResource(R.string.core_ask_retry))}
   } else if(pages.cursor!=null) item {
    TextButton(enabled=!loading && enabled,onClick={loadRequests++}) {Text(stringResource(R.string.core_show_more))}
   }
  }
  actions(selected)
 }
}

private fun notePickerError(resources:Resources,error:Exception):String=when(error) {
 is ApiException -> apiErrorMessage(resources,error)
 is IllegalArgumentException -> error.message ?: resources.getString(R.string.settings_check_the_tool_inputs)
 else -> resources.getString(R.string.settings_couldn_t_reach_the_server_check_your)
}
