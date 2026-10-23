@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package io.theficos.spunto

import android.content.res.Resources
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.*
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import io.theficos.spunto.core.design.*
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.FlowRow
import io.theficos.spunto.core.plugins.*
import io.theficos.spunto.data.sync.ApiClient
import io.theficos.spunto.data.sync.ApiException
import io.theficos.spunto.data.local.PluginOutputEntity
import io.theficos.spunto.core.model.Note
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import androidx.lifecycle.viewModelScope
import androidx.compose.ui.platform.LocalUriHandler
import kotlinx.serialization.json.*

internal fun commandEnvelope(plugin:String,command:String,args:JsonObject,result:JsonElement)=buildJsonObject {
 put("plugin_id",plugin);put("command_id",command);put("command_args",args);put("payload",result)
}
internal fun resultCopyText(resources:Resources,data:JsonElement):String=outputComponents(data).mapNotNull { component ->
 when(component.string("type")) {
  "text" -> component.string("text")
  "markdown" -> component.string("text").ifEmpty { component.string("markdown") }
  "diff" -> component.objects("hunks").joinToString("\n\n") {hunk ->val (before,after)=diffHunkTexts(hunk);resources.getString(R.string.settings_before_n_n_nafter_n ,before,after)}
  else -> null
 }
}.joinToString("\n\n")

internal fun runCommand(model:SpuntoViewModel,plugin:String,id:String,args:JsonObject,onResult:(JsonObject)->Unit,onComplete:()->Unit={},scope:CoroutineScope=model.viewModelScope,onError:(String)->Unit={model.message.value=it})=model.task(scope) {
 val resources=model.container.context.resources
 val auth=model.auth.value
 val epoch=model.pluginReplicaEpoch
 fun current()=auth!=null && model.auth.value==auth && model.pluginReplicaEpoch==epoch && model.container.manifest.enabled(plugin)
 try {
  require(Regex("[a-z][a-z0-9-]{1,23}").matches(plugin) && Regex("[a-z][a-z0-9-]{0,127}").matches(id)) { resources.getString(R.string.settings_this_command_is_unavailable) }
  if(!current()) return@task
  require(model.online.value) {resources.getString(R.string.settings_connect_to_the_server_to_use_this)}
  args.string("note_id").takeIf(String::isNotBlank)?.let {noteId ->
   val note=model.repository.observe(noteId).first()
   require(note!=null && !note.trashed) {resources.getString(R.string.settings_this_note_is_no_longer_available)}
   if(note.pending || note.serverRev==0) {
    model.synchronize().join()
    if(!current()) return@task
    val synced=model.repository.observe(noteId).first()
    require(synced!=null && !synced.trashed && !synced.pending && synced.serverRev>0) {resources.getString(R.string.settings_this_note_has_changes_waiting_to_sync)}
   }
  }
  val result=ApiClient(auth!!).guarded {current()}.request("/api/v1/plugins/$plugin/commands/$id","POST",args)
  if(!current()) return@task
  model.synchronize().join()
  if(current()) onResult(commandEnvelope(plugin,id,args,result))
 } catch(error:CancellationException) {throw error}
 catch(error:Exception) {
  if(current()) {
   if(error is ApiException && error.status==401) model.reauthenticate.value=true
   onError(when(error) {is ApiException -> apiErrorMessage(resources,error);is IllegalArgumentException -> error.message ?: resources.getString(R.string.settings_check_the_tool_inputs);is java.net.SocketTimeoutException -> resources.getString(R.string.settings_the_tool_took_too_long_try_again);else -> resources.getString(R.string.settings_couldn_t_reach_the_server_check_your)})
  }
 } finally { onComplete() }
}

@Composable fun PluginPanels(model:SpuntoViewModel,note:Note,onNote:(String)->Unit) {
 val catalog by model.catalog.collectAsState()
 val resources=LocalContext.current.resources
 val manifest=model.manifest.collectAsState().value
 val outputs by model.outputs.collectAsState()
 val online by model.online.collectAsState()
 val plugins=model.container.manifest.plugins().filter { model.container.manifest.enabled(it.string("id")) }.sortedBy { (it["position"] as? JsonPrimitive)?.intOrNull ?: Int.MAX_VALUE }
 plugins.forEach { plugin ->
  val id=plugin.string("id")
  val contribution=(plugin["contributes"] as? JsonObject)?.get("panel")
  val panel=(contribution as? JsonObject) ?: (contribution as? JsonArray)?.firstOrNull() as? JsonObject ?: return@forEach
  val stored=outputs.filter { it.noteId==note.id && it.kind=="annotation" }.map { payload(it.payload) }.filter { it.string("plugin")==id && it.string("kind")=="panel" }
  val components=stored.flatMap(::outputComponents).take(10)
  val live=panel.string("mode")=="live"
  if(live && (!online || note.privateNote) || !live && components.isEmpty()) return@forEach
  var expanded by remember(note.id,id) { mutableStateOf(model.container.preferences.getBoolean("panel-open:$id",false)) }
  var result by remember(note.id,id,note.serverRev) { mutableStateOf<JsonElement?>(null) }
  // Live sections load when the note opens, as on the web; until they have something to say they are absent (03 A5).
  LaunchedEffect(online,note.id,note.serverRev,note.privateNote) {
   if(live && online && !note.privateNote) {
    val auth=model.auth.value ?: return@LaunchedEffect
    val epoch=model.pluginReplicaEpoch
    fun current()=model.auth.value==auth && model.pluginReplicaEpoch==epoch && model.container.manifest.enabled(id)
    try {
     val response=ApiClient(auth).guarded {current()}.request("/api/v1/plugins/$id/panel",query=mapOf("note_id" to note.id))
     if(current()) result=response
    } catch(cancelled:CancellationException) {throw cancelled}
    catch(failure:Exception) {if(failure is ApiException && failure.status==401 && current()) model.reauthenticate.value=true}
   }
  }
  val content=if(live) result else JsonArray(components)
  if(content==null || outputComponents(content).isEmpty()) return@forEach
  Column(verticalArrangement=Arrangement.spacedBy(SpuntoSpacing.s8)) {
   TextButton(onClick={expanded=!expanded;model.container.preferences.edit().putBoolean("panel-open:$id",expanded).apply()},modifier=Modifier.heightIn(min=48.dp).semantics {stateDescription=if(expanded) resources.getString(R.string.settings_open) else resources.getString(R.string.settings_closed)}) { Text(localizedPluginPanel(resources,plugin,panel,catalog).uppercase(),style=SpuntoType.overline,color=SpuntoColors.ink3) }
   if(expanded) RenderOutput(model,buildJsonObject {put("plugin_id",id);put("payload",content)},onNote)
  }
 }
}

internal fun proposalData(item:PluginOutputEntity)=JsonObject(payload(item.payload)+mapOf("id" to JsonPrimitive(item.id))+if(item.noteId!=null) mapOf("note_id" to JsonPrimitive(item.noteId)) else emptyMap())
internal fun labelChangeSummary(resources:Resources,proposed:JsonObject):String {
 val ids=proposed["note_ids"] as? JsonArray
 return when {
  ids!=null -> resources.getQuantityString(R.plurals.settings_add_label_notes,ids.size,proposed.string("new"),ids.size)
  proposed["new"] is JsonNull -> resources.getString(R.string.settings_remove_from_affected_notes_including_nested_labels ,proposed.string("old"))
  else -> resources.getString(R.string.settings_rename_or_merge_into ,proposed.string("old"),proposed.string("new"))
 }
}
internal fun entryMetaText(outputs:List<PluginOutputEntity>,noteId:String,plugins:List<JsonObject>):String {
 val active=plugins.filter { it.bool("enabled",true) && it.string("state") !in setOf("disabled","off","failed") }.associateBy { it.string("id") }
 return outputs.filter { it.noteId==noteId && it.kind=="annotation" }.map { payload(it.payload) }.filter { it.string("kind")=="meta" && it.string("plugin") in active }.sortedWith(compareBy<JsonObject> { (active[it.string("plugin")]?.get("position") as? JsonPrimitive)?.intOrNull ?: Int.MAX_VALUE }.thenBy { it.string("plugin") }).firstNotNullOfOrNull { data -> outputComponents(data).firstOrNull { it.string("type")=="text" }?.string("text")?.take(24)?.takeIf(String::isNotBlank) }.orEmpty()
}

@Composable fun ProposedPill(title:String,pluginName:String,changesWords:Boolean,onClick:()->Unit,onDismiss:()->Unit) {
 val resources=LocalContext.current.resources
 val accent=MaterialTheme.colorScheme.primary
 Surface(shape=MaterialTheme.shapes.large,color=MaterialTheme.colorScheme.surface,modifier=Modifier.drawWithContent {
  drawContent()
  drawRoundRect(accent,cornerRadius=CornerRadius(16.dp.toPx()),style=Stroke(1.dp.toPx(),pathEffect=PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(),3.dp.toPx()))))
 }.semantics { contentDescription=resources.getString(if(changesWords) R.string.settings_proposed_wording else R.string.settings_proposed_addition,title,pluginName);customActions=listOf(CustomAccessibilityAction(resources.getString(R.string.settings_dismiss_suggestion)) { onDismiss();true }) }.combinedClickable(onLongClickLabel=resources.getString(R.string.settings_dismiss_suggestion),onLongClick=onDismiss,onClick=onClick)) {
  Row(Modifier.padding(start=SpuntoSpacing.s12),horizontalArrangement=Arrangement.spacedBy(6.dp),verticalAlignment=Alignment.CenterVertically) {
   if(changesWords) androidx.compose.foundation.Canvas(Modifier.size(16.dp)) {
    val stroke=1.5.dp.toPx()
    drawLine(accent,androidx.compose.ui.geometry.Offset(size.width*.18f,size.height*.84f),androidx.compose.ui.geometry.Offset(size.width*.72f,size.height*.30f),stroke)
    drawLine(accent,androidx.compose.ui.geometry.Offset(size.width*.58f,size.height*.19f),androidx.compose.ui.geometry.Offset(size.width*.84f,size.height*.45f),stroke)
   } else Text("+",color=accent)
   Text(title,style=MaterialTheme.typography.labelLarge)
   Box(Modifier.size(40.dp).clickable(onClickLabel=resources.getString(R.string.settings_dismiss_suggestion),onClick=onDismiss).clearAndSetSemantics {},contentAlignment=Alignment.Center) {Text("×",color=SpuntoColors.ink3)}
  }
 }
}

/** 03 §5.1: one line of dashed pills; what does not fit folds into "+N", which unfolds the rest for this note only. Each pill dismisses on × or long-press; the grey "Dismiss" ending the row dismisses them all. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable fun ProposalLine(proposals:List<JsonObject>,pluginNames:Map<String,String>,onSelect:(JsonObject)->Unit,onDismiss:(String)->Unit) {
 val resources=LocalContext.current.resources
 var expanded by remember { mutableStateOf(false) }
 FlowRow(horizontalArrangement=Arrangement.spacedBy(6.dp),verticalArrangement=Arrangement.spacedBy(6.dp),itemVerticalAlignment=Alignment.CenterVertically) {
  (if(expanded) proposals else proposals.take(3)).forEach { data ->
   ProposedPill(data.string("title").ifEmpty { (data["payload"] as? JsonObject)?.string("path")?.takeIf(String::isNotBlank)?.let { resources.getString(R.string.settings_add ,it) } ?: resources.getString(R.string.settings_review_change) },pluginNames[data.string("plugin")] ?: data.string("plugin"),data.string("action") in setOf("replace_body","merge_notes"),{onSelect(data)},{onDismiss(data.string("id"))})
  }
  if(proposals.size>3 && !expanded) Surface(shape=MaterialTheme.shapes.large,color=MaterialTheme.colorScheme.surfaceVariant,contentColor=MaterialTheme.colorScheme.onSurface,modifier=Modifier.heightIn(min=40.dp).clickable(onClickLabel=resources.getQuantityString(R.plurals.settings_more_suggestions,proposals.size-3,proposals.size-3)) {expanded=true}) {
   Box(Modifier.padding(horizontal=SpuntoSpacing.s12,vertical=SpuntoSpacing.s8)) {Text("+${proposals.size-3}",style=MaterialTheme.typography.labelLarge)}
  }
  TextButton(onClick={proposals.forEach {onDismiss(it.string("id"))}},modifier=Modifier.semantics {contentDescription=resources.getQuantityString(R.plurals.settings_dismiss_suggestions,proposals.size,proposals.size)}) {Text(resources.getString(R.string.settings_dismiss),style=MaterialTheme.typography.labelLarge,color=SpuntoColors.ink3)}
 }
}

@Composable fun Suggestions(model:SpuntoViewModel,noteId:String,onNote:(String)->Unit) {
 val catalog by model.catalog.collectAsState()
 val resources=LocalContext.current.resources
 val manifest=model.manifest.collectAsState().value
 val outputs by model.outputs.collectAsState()
 var selected by remember(noteId) { mutableStateOf<String?>(null) }
 val plugins=model.container.manifest.plugins()
 val counts=mutableMapOf<String,Int>()
 val proposals=outputs.filter { it.noteId==noteId && it.kind=="suggestion" }.map(::proposalData).filter { data ->
  val id=data.string("plugin");val count=(counts[id] ?: 0)+1
  (data.string("status")=="pending" && model.container.manifest.enabled(id) && count<=3).also { if(it) counts[id]=count }
 }.sortedWith(compareBy<JsonObject> { data -> plugins.firstOrNull { it.string("id")==data.string("plugin") }?.get("position")?.jsonPrimitive?.intOrNull ?: Int.MAX_VALUE }.thenByDescending { (it["confidence"] as? JsonPrimitive)?.doubleOrNull ?: 0.0 })
 if(proposals.isEmpty()) return
 ProposalLine(proposals,plugins.associate { it.string("id") to localizedPluginName(resources,it,catalog) },{data -> if(data.string("action")=="add_label") model.decide(data.string("id"),"accept") else selected=data.string("id") },{model.decide(it,"dismiss")})
 selected?.let { id -> outputs.firstOrNull { it.id==id && it.kind=="suggestion" }?.let { SuggestionDecisionDialog(model,proposalData(it),onNote) { selected=null } } }
}

/** Suggestions waiting in Review: pending, from a plugin that is still on. */
internal fun waitingProposals(model:SpuntoViewModel,outputs:List<PluginOutputEntity>)=outputs.filter { it.kind=="suggestion" && payload(it.payload).string("status")=="pending" && model.container.manifest.enabled(payload(it.payload).string("plugin")) }
private fun proposalKind(resources:Resources,action:String)=when(action) { "add_label","label_change" -> resources.getString(R.string.settings_labels);"merge_notes" -> resources.getString(R.string.settings_duplicate_notes);"replace_body" -> resources.getString(R.string.settings_wording_changes);"set_title" -> resources.getString(R.string.settings_titles);"append_text" -> resources.getString(R.string.settings_links_and_additions);else -> resources.getString(R.string.settings_other_suggestions) }
@Composable fun ReviewScreen(model:SpuntoViewModel,onNote:(String)->Unit) {
 val catalog by model.catalog.collectAsState()
 val resources=LocalContext.current.resources
 val manifest=model.manifest.collectAsState().value
 val outputs by model.outputs.collectAsState()
 val online by model.online.collectAsState()
 var selected by remember { mutableStateOf<String?>(null) }
 val expanded=remember { mutableStateMapOf<String,Boolean>() }
 val proposals=waitingProposals(model,outputs)
 LazyColumn(contentPadding=PaddingValues(SpuntoSpacing.s20),verticalArrangement=Arrangement.spacedBy(SpuntoSpacing.s16)) {
  if(proposals.isEmpty()) item { Text(resources.getString(R.string.settings_all_caught_up),style=MaterialTheme.typography.titleLarge) }
  proposals.groupBy { proposalKind(resources,payload(it.payload).string("action")) }.forEach { (kind,group) ->
   item { Text(kind,style=MaterialTheme.typography.titleMedium) }
   items(if(expanded[kind]==true) group else group.take(3),key={it.id}) { item ->
    val data=proposalData(item)
    Surface(color=MaterialTheme.colorScheme.surfaceContainer,shape=MaterialTheme.shapes.medium) {
     Column(Modifier.padding(SpuntoSpacing.s16),verticalArrangement=Arrangement.spacedBy(SpuntoSpacing.s8)) {
      Text(data.string("title").ifBlank { resources.getString(R.string.settings_review_change) },style=MaterialTheme.typography.titleSmall)
      val plugin=data.string("plugin")
      Text(model.container.manifest.plugins().firstOrNull { it.string("id")==plugin }?.let {localizedPluginName(resources,it,catalog)} ?: plugin,style=MaterialTheme.typography.bodySmall)
      item.noteId?.let { RoomReference(model,it,"",onNote) }
      if(data.string("reason").isNotBlank()) Text(data.string("reason"),style=MaterialTheme.typography.bodySmall)
      Row { TextButton(onClick={selected=item.id}) { Text(resources.getString(R.string.settings_review)) };TextButton(enabled=data.string("action")!="label_change" || online,onClick={model.decide(item.id,"dismiss")}) { Text(resources.getString(R.string.settings_dismiss)) } }
     }
    }
   }
   if(group.size>3) item { TextButton(onClick={expanded[kind]=expanded[kind]!=true}) { Text(if(expanded[kind]==true) resources.getString(R.string.settings_show_fewer) else resources.getQuantityString(R.plurals.settings_more_items,group.size-3,group.size-3)) } }
  }
 }
 selected?.let { id -> outputs.firstOrNull { it.id==id && it.kind=="suggestion" }?.let { SuggestionDecisionDialog(model,proposalData(it),onNote) { selected=null } } }
}

@Composable private fun RoomReference(model:SpuntoViewModel,id:String,reason:String,onNote:(String)->Unit) {
 val resources=LocalContext.current.resources
 val note by remember(id) { model.repository.observe(id) }.collectAsState(null)
 if(note==null || note!!.trashed) Text(resources.getString(R.string.settings_note_unavailable),style=MaterialTheme.typography.bodySmall)
 else Column { TextButton(onClick={onNote(id)}) { Text(note!!.title.ifBlank { note!!.body.lineSequence().firstOrNull().orEmpty().take(100).ifBlank { resources.getString(R.string.settings_open_note) } }) };if(reason.isNotBlank()) Text(reason,style=MaterialTheme.typography.bodySmall) }
}

/** One renderer for bare arrays, stored payloads and live command envelopes. */
@Composable fun RenderOutput(model:SpuntoViewModel,data:JsonElement,onNote:(String)->Unit,onResult:((JsonObject)->Unit)?=null,commandScope:CoroutineScope?=null,onProposalOpen:()->Unit={}) {
 val catalog by model.catalog.collectAsState()
 val resources=LocalContext.current.resources
 val online by model.online.collectAsState()
 val outputs by model.outputs.collectAsState()
 val wrapper=data as? JsonObject ?: JsonObject(emptyMap())
 val plugin=wrapper.string("plugin").ifEmpty { wrapper.string("plugin_id") }
 val command=wrapper.string("command_id")
 val initial=wrapper["command_args"] as? JsonObject ?: JsonObject(emptyMap())
 val deliver:(JsonObject)->Unit=onResult ?: { model.remote.value=it }
 val ownScope=rememberCoroutineScope()
 val scope=commandScope ?: ownScope
 var running by remember(data) { mutableStateOf(false) }
 var error by remember(data) {mutableStateOf("")}
 val run:(String,JsonObject)->Unit={ id,args -> if(!running && online && plugin.isNotBlank()) { running=true;error="";runCommand(model,plugin,id,args,deliver,{running=false},scope,onError={error=it}) } }
 val review:(String)->Unit={ id -> if(model.outputs.value.any { it.id==id && it.kind=="suggestion" }) { onProposalOpen();model.remote.value=buildJsonObject { put("suggestion_preview",id) } } else model.message.value=resources.getString(R.string.settings_sync_to_review_this_suggestion) }
 Column(verticalArrangement=Arrangement.spacedBy(SpuntoSpacing.s12)) {
  val components=outputComponents(data)
  if(components.isEmpty() && command.isNotBlank()) Text(resources.getString(R.string.settings_this_result_has_no_preview_in_this),style=MaterialTheme.typography.bodySmall)
  components.forEach { component ->
   Vocabulary(component,onNote,review,run,online=online && !running,
    onForm=if(command.isNotBlank()) { args -> run(command,JsonObject(initial+args)) } else null,formInitial=initial,formHidden=initial.keys.filter { it=="note_id" }.toSet(),noteReference={id,reason -> RoomReference(model,id,reason,onNote)},proposalPill={ pill ->
     val id=pill.string("suggestion_id");val proposal=outputs.firstOrNull { it.id==id && it.kind=="suggestion" }?.let(::proposalData)
     ProposedPill(pill.string("label"),model.container.manifest.plugins().firstOrNull { it.string("id")==plugin }?.let {localizedPluginName(resources,it,catalog)} ?: plugin,proposal?.string("action") in setOf("replace_body","merge_notes"),{review(id)},{model.decide(id,"dismiss")})
    })
  }
  if(error.isNotBlank()) Text(error,color=MaterialTheme.colorScheme.error)
  if(running) {LinearProgressIndicator(Modifier.fillMaxWidth());Text(resources.getString(R.string.settings_working_this_can_take_a_moment_with),style=MaterialTheme.typography.bodySmall)}
 }
}

@Composable fun CommandResultSheet(model:SpuntoViewModel,result:JsonObject,onNote:(String)->Unit,onResult:(JsonObject)->Unit,onClose:()->Unit) {
 val resources=LocalContext.current.resources
 val clipboard=LocalClipboardManager.current
 val catalog by model.catalog.collectAsState()
 val plugin=catalog.objects("plugins").firstOrNull { it.string("id")==result.string("plugin_id") }
 val sheetScope=rememberCoroutineScope()
 var active by remember { mutableStateOf(true) }
 val close={active=false;sheetScope.cancel();onClose()}
 ModalBottomSheet(onDismissRequest=close,scrimColor=MaterialTheme.colorScheme.scrim) {
  Column(Modifier.padding(SpuntoSpacing.s20).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(SpuntoSpacing.s12)) {
   val command=(plugin?.get("contributes") as? JsonObject)?.objects("commands")?.firstOrNull {it.string("id")==result.string("command_id")}
   Text(if(plugin!=null && command!=null) localizedPluginCommand(resources,plugin,command,catalog) else plugin?.let {localizedPluginName(resources,it,catalog)} ?: resources.getString(R.string.settings_tool_result),style=MaterialTheme.typography.titleLarge)
   RenderOutput(model,result,{id -> close();onNote(id)},{if(active) onResult(it)},commandScope=sheetScope,onProposalOpen=close)
   (plugin?.get("ai_destinations") as? JsonObject)?.forEach { (_,raw) -> (raw as? JsonObject)?.let { destination -> Text(resources.getString(R.string.settings_at ,destination.string("model"),destination.string("host"),if(destination.bool("leaves_network")) resources.getString(R.string.settings_outside_your_network) else resources.getString(R.string.settings_on_your_network)),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant) } }
   val copied=resultCopyText(resources,result)
   Row(horizontalArrangement=Arrangement.spacedBy(SpuntoSpacing.s12)) {
    if(copied.isNotBlank()) TextButton(onClick={clipboard.setText(AnnotatedString(copied))}) {Text(if(outputComponents(result).any {it.string("type")=="diff"}) resources.getString(R.string.settings_copy_changes) else resources.getString(R.string.settings_copy))}
    TextButton(onClick=close) { Text(resources.getString(R.string.settings_close)) }
   }
   Spacer(Modifier.height(SpuntoSpacing.s24))
  }
 }
}

@Composable fun SuggestionPreview(model:SpuntoViewModel,data:JsonObject) {
 val resources=LocalContext.current.resources
 val proposed=data["payload"] as? JsonObject ?: return
 val action=data.string("action")
 val id=if(action=="merge_notes") proposed.string("target_id") else data.string("note_id")
 val target by remember(id) { model.repository.observe(id) }.collectAsState(null)
 when(action) {
  "add_label" -> Text(resources.getString(R.string.settings_add ,proposed.string("path")))
  "append_text" -> { Text(resources.getString(R.string.settings_append_this_text));Markdown(proposed.string("text"),machine=true) }
  "replace_body" -> { Text(resources.getString(R.string.settings_based_on_version ,data["note_version"] ?: resources.getString(R.string.settings_unknown)),style=MaterialTheme.typography.bodySmall);if(target==null) Text(resources.getString(R.string.settings_note_unavailable)) else TextChangePreview(target!!.body,proposed.string("body")) }
  "set_title" -> Text(resources.getString(R.string.settings_title ,proposed.string("title")))
  "label_change" -> { Text(labelChangeSummary(resources,proposed));Text(resources.getString(R.string.settings_review_the_affected_notes_in_the_web),style=MaterialTheme.typography.bodySmall) }
  "merge_notes" -> { val source by remember(proposed.string("source_id")) { model.repository.observe(proposed.string("source_id")) }.collectAsState(null);Text(resources.getString(R.string.settings_compare),style=MaterialTheme.typography.titleLarge);Text(resources.getString(R.string.settings_the_source_will_be_appended_to_the));Text(resources.getString(R.string.settings_destination),style=MaterialTheme.typography.titleSmall);Text(target?.body ?: resources.getString(R.string.settings_note_unavailable),fontFamily=monoFont());Text(resources.getString(R.string.settings_source),style=MaterialTheme.typography.titleSmall);Text(source?.body ?: resources.getString(R.string.settings_note_unavailable),fontFamily=monoFont()) }
 }
 if(data.string("reason").isNotBlank()) Text(data.string("reason"),style=MaterialTheme.typography.bodySmall)
}

@Composable fun SuggestionDecisionDialog(model:SpuntoViewModel,data:JsonObject,onNote:(String)->Unit,onClose:()->Unit) {
 val resources=LocalContext.current.resources
 val action=data.string("action")
 val proposed=data["payload"] as? JsonObject ?: JsonObject(emptyMap())
 val noteId=if(action=="merge_notes") proposed.string("target_id") else data.string("note_id")
 val note by remember(noteId) { model.repository.observe(noteId) }.collectAsState(null)
 val source by remember(proposed.string("source_id")) { model.repository.observe(proposed.string("source_id")) }.collectAsState(null)
 val online by model.online.collectAsState()
 val uri=LocalUriHandler.current
 val current=note
 val base=(data["note_version"] as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull
 val stale=action=="replace_body" && (current==null || current.pending || current.serverRev!=base)
 val eligible=current!=null && !current.trashed && !current.privateNote
 val canApply=data.string("status")=="pending" && !stale && when(action) {
  "label_change" -> false
  "merge_notes" -> eligible && source!=null && !source!!.trashed && !source!!.privateNote
  "open" -> true
  else -> eligible
 }
 AlertDialog(onDismissRequest=onClose,title={Text(if(action=="merge_notes") resources.getString(R.string.settings_compare_notes) else resources.getString(R.string.settings_review_change))},text={Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(SpuntoSpacing.s12)) {
  SuggestionPreview(model,data)
  if(stale) Text(resources.getString(R.string.settings_this_note_changed_wait_for_a_fresh),style=MaterialTheme.typography.bodySmall)
  if(data.string("status")!="pending") Text(suggestionStatus(resources,data.string("status")),style=MaterialTheme.typography.bodySmall)
 }},confirmButton={
  if(action=="label_change") TextButton(enabled=online,onClick={model.auth.value?.let { runCatching { uri.openUri(it.serverUrl.trimEnd('/')+"/review") } };onClose()}) { Text(if(online) resources.getString(R.string.settings_open_web_review) else resources.getString(R.string.settings_needs_a_connection)) }
  else TextButton(enabled=canApply,onClick={if(action=="open") proposed.string("note_id").takeIf(String::isNotBlank)?.let(onNote);model.decide(data.string("id"),"accept");onClose()}) { Text(if(action=="replace_body") resources.getString(R.string.settings_apply_changes) else resources.getString(R.string.settings_apply)) }
 },dismissButton={TextButton(enabled=action!="label_change" || online,onClick={model.decide(data.string("id"),"dismiss");onClose()}) { Text(if(action=="replace_body") resources.getString(R.string.settings_keep_original) else resources.getString(R.string.settings_dismiss)) }})
}

@Composable fun NoteCommands(model:SpuntoViewModel,noteId:String,privateNote:Boolean=false,onResult:(JsonObject)->Unit) {
 val catalog by model.catalog.collectAsState()
 val resources=LocalContext.current.resources
 val manifest=model.manifest.collectAsState().value
 val online by model.online.collectAsState()
 var expanded by remember(noteId) { mutableStateOf(false) }
 var running by remember(noteId) { mutableStateOf(false) }
 var query by remember(noteId) {mutableStateOf("")}
 var error by remember(noteId) {mutableStateOf("")}
 var attempt by remember(noteId) {mutableStateOf<Job?>(null)}
 val scope=rememberCoroutineScope()
 val locale=LocalConfiguration.current.locales[0]
 val commands=remember(manifest,catalog,privateNote,locale) {noteTools(resources,model.container.manifest.plugins(),privateNote,catalog)}
 if(commands.isEmpty()) return
 fun run(tool:NoteTool) {running=true;error="";attempt=runCommand(model,tool.pluginId,tool.id,buildJsonObject {put("note_id",noteId)},{expanded=false;onResult(it)},{running=false},scope,onError={error=it})}
 // 03 R9: plugin verbs between hairlines, each with its plugin's name in grey; six here, the rest under More.
 HorizontalDivider(Modifier.padding(vertical=SpuntoSpacing.s4),color=MaterialTheme.colorScheme.outlineVariant)
 commands.take(6).forEach {tool -> NoteSheetRow(tool.title,tool.pluginName,enabled=online && !running) {run(tool)}}
 if(commands.size>6) NoteSheetRow(resources.getString(R.string.settings_more),enabled=online && !running) {expanded=true;query="";error=""}
 if(running && !expanded) Text(resources.getString(R.string.settings_working_this_can_take_a_moment_with),Modifier.padding(horizontal=SpuntoSpacing.s20),style=MaterialTheme.typography.bodySmall,color=SpuntoColors.ink3)
 if(error.isNotBlank() && !expanded) Text(error,Modifier.padding(horizontal=SpuntoSpacing.s20),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.error)
 if(expanded) ModalBottomSheet(onDismissRequest={attempt?.cancel();expanded=false}) {
  Column(Modifier.fillMaxWidth().padding(horizontal=SpuntoSpacing.s20),verticalArrangement=Arrangement.spacedBy(SpuntoSpacing.s12)) {
   Text(resources.getString(R.string.settings_note_tools),style=MaterialTheme.typography.titleLarge)
   if(!online) Text(resources.getString(R.string.settings_connect_to_the_server_to_use_tools),style=MaterialTheme.typography.bodySmall)
   if(commands.size>5) OutlinedTextField(value=query,onValueChange={query=it},label={Text(resources.getString(R.string.settings_find_a_tool))},singleLine=true,modifier=Modifier.fillMaxWidth())
   if(error.isNotBlank()) Text(error,color=MaterialTheme.colorScheme.error)
   if(running) {LinearProgressIndicator(Modifier.fillMaxWidth());Text(resources.getString(R.string.settings_working_this_can_take_a_moment_with),style=MaterialTheme.typography.bodySmall)}
   val visible=commands.filter {it.title.contains(query,true) || it.pluginName.contains(query,true)}
   if(visible.isEmpty()) Text(resources.getString(R.string.settings_no_tools_match_your_search))
   LazyColumn(Modifier.weight(1f,false),contentPadding=PaddingValues(bottom=SpuntoSpacing.s24)) {
    items(visible,key={it.pluginId+"/"+it.id}) {tool ->
     ListItem(headlineContent={Text(tool.title)},trailingContent={Text(tool.pluginName,style=MaterialTheme.typography.bodySmall,color=SpuntoColors.ink3)},modifier=Modifier.clickable(enabled=online && !running) {run(tool)})
    }
   }
   TextButton(onClick={attempt?.cancel();expanded=false}) {Text(if(running) resources.getString(R.string.settings_cancel) else resources.getString(R.string.settings_close))}
   Spacer(Modifier.height(SpuntoSpacing.s12))
  }
 }
}

internal fun suggestionStatus(resources:Resources,status:String)=resources.getString(when(status) {
 "pending" -> R.string.settings_suggestion_pending;"accepted" -> R.string.settings_suggestion_accepted;"dismissed" -> R.string.settings_suggestion_dismissed;else -> R.string.settings_suggestion_unavailable
})
