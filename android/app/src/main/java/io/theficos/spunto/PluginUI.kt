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
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.*
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalAccessibilityManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.theficos.spunto.core.design.*
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.FlowRow
import io.theficos.spunto.core.plugins.*
import io.theficos.spunto.data.sync.ApiPayload
import io.theficos.spunto.data.sync.ApiException
import io.theficos.spunto.data.local.PluginOutputEntity
import io.theficos.spunto.core.model.Note
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import androidx.lifecycle.viewModelScope
import androidx.compose.ui.platform.LocalUriHandler
import kotlinx.serialization.json.*

internal fun commandEnvelope(plugin:String,command:String,args:JsonObject,result:JsonElement)=buildJsonObject {
 put("plugin_id",plugin);put("command_id",command);put("command_args",args);put("payload",result)
}
private fun translationPreviewBody(data:JsonElement,components:List<JsonObject>):String? {
 val wrapper=data as? JsonObject ?: return null
 if(wrapper.string("plugin_id")!="translate" || wrapper.string("command_id")!="translate" ||
  components.none {it.string("type")=="command" && it.string("id")=="save-translation"}) return null
 return components.filter {it.string("type")=="markdown"}.joinToString("") {
  it.string("text").ifEmpty {it.string("markdown")}
 }.takeIf(String::isNotEmpty)
}

internal fun resultCopyText(resources:Resources,data:JsonElement):String {
 val components=outputComponents(data)
 val wrapper=data as? JsonObject
 if(wrapper?.string("plugin_id")=="context-packs" || wrapper?.string("plugin")=="context-packs") {
  val markdown=components.firstOrNull {it.string("type")=="markdown"}
  if((wrapper?.get("command_args") as? JsonObject)?.bool("export")==true) return markdown?.string("text").orEmpty()
  if(markdown==null || components.any {
   val args=it["args"] as? JsonObject
   it.string("type")=="command" && (it.string("id")=="packs" && args?.bool("export")==true ||
    it.string("id")=="edit" && args?.string("action")=="finish")
  }) return ""
 }
 translationPreviewBody(data,components)?.let {return it}
 return components.mapNotNull { component ->
  when(component.string("type")) {
   "text" -> component.string("text")
   "markdown" -> component.string("text").ifEmpty { component.string("markdown") }
   "diff" -> component.objects("hunks").joinToString("\n\n") {hunk ->val (before,after)=diffHunkTexts(hunk);resources.getString(R.string.settings_before_n_n_nafter_n ,before,after)}
   else -> null
  }
 }.joinToString("\n\n")
}

internal fun runCommand(model:SpuntoViewModel,plugin:String,id:String,args:JsonObject,onResult:(JsonObject)->Unit,onComplete:()->Unit={},scope:CoroutineScope=model.viewModelScope,onError:(String)->Unit={model.message.value=it},pickerSession:NotePickerSession?=null,requireOwner:Boolean=false)=model.task(scope) {
 val resources=model.container.context.resources
 val auth=model.auth.value
 val epoch=model.pluginReplicaEpoch
 val requestContext=currentCoroutineContext()
 var pendingAskHandle:JsonObject?=null
 fun current()=model.workspaceCanWrite.value && (!requireOwner && plugin!=DecisionJournalId || model.workspaceIsOwner.value) &&
  auth!=null && model.auth.value==auth && model.pluginReplicaEpoch==epoch && model.container.manifest.enabled(plugin) && pickerSession?.current(model)!=false
 suspend fun authorized()=current() && pickerSession?.authorized(model)!=false
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
  val api=pluginApi(auth!!,resources)
  val response=if(plugin=="ask" && id=="ask" && args.string("question").isNotBlank()) {
   val result=conversationAskRequest(args.string("question"),args.takeIf {it.string("conversation_id").isNotBlank()},{command,input -> api.request("/api/v1/plugins/ask/commands/$command","POST",input)},{current() && requestContext.isActive},{pendingAskHandle=it},conversationSupported=supportsAskConversation(model.container.manifest.value),noteActionsSupported=supportsAskNoteActions(model.container.manifest.value,auth.workspaceRole)) ?: return@task
   ApiPayload(result,null)
  } else api.guarded {authorized()}.requestPayload("/api/v1/plugins/$plugin/commands/$id","POST",args)
  if(!authorized()) return@task
  model.synchronize().join()
  if(authorized()) {
   val envelope=commandEnvelope(plugin,id,args,response.body).let {value ->
    response.contentLanguage?.let {JsonObject(value+("content_language" to JsonPrimitive(it)))} ?: value
   }
   val ownedResult=if(plugin==DecisionJournalId) JsonObject(envelope+mapOf(
    "authority" to JsonPrimitive(auth!!.serverUrl), "replica_epoch" to JsonPrimitive(epoch),
   )) else envelope
   onResult(ownedResult);pendingAskHandle=null
  }
 } catch(error:CancellationException) {throw error}
 catch(error:Exception) {
  if(authorized()) {
   if(error is ApiException && error.status==401) model.reauthenticate.value=true
   onError(when(error) {is ApiException -> pluginApiError(resources,error,id);is IllegalArgumentException -> error.message ?: resources.getString(R.string.settings_check_the_tool_inputs);is java.net.SocketTimeoutException -> resources.getString(R.string.settings_the_tool_took_too_long_try_again);else -> resources.getString(R.string.settings_couldn_t_reach_the_server_check_your)})
  }
 } finally {
  pendingAskHandle?.let {handle -> withContext(NonCancellable) {runCatching {pluginApi(auth!!,resources).request("/api/v1/plugins/ask/commands/clear-conversation","POST",buildJsonObject {put("conversation_id",handle.string("conversation_id"))})}}}
  onComplete()
 }
}

@Composable fun PluginPanels(model:SpuntoViewModel,note:Note,onNote:(String)->Unit) {
 val catalog by model.catalog.collectAsState()
 val resources=LocalContext.current.resources
 val language=LocalConfiguration.current.locales[0]
 val manifest=model.manifest.collectAsState().value
 val outputs by model.outputs.collectAsState()
 val online by model.online.collectAsState()
 val plugins=model.container.manifest.plugins().filter { model.container.manifest.enabled(it.string("id")) }.sortedBy { (it["position"] as? JsonPrimitive)?.intOrNull ?: Int.MAX_VALUE }
 plugins.forEach { plugin ->
  val id=plugin.string("id")
  // Backlinks sit under the note itself, in Linked notes, next to the links it makes.
  if(id in setOf("links",DecisionJournalId)) return@forEach
  val contribution=(plugin["contributes"] as? JsonObject)?.get("panel")
  val panel=(contribution as? JsonObject) ?: (contribution as? JsonArray)?.firstOrNull() as? JsonObject ?: return@forEach
  val stored=outputs.filter { it.noteId==note.id && it.kind=="annotation" }.map { payload(it.payload) }.filter { it.string("plugin")==id && it.string("kind")=="panel" }
  val components=stored.flatMap(::outputComponents).take(10).map {localizedStoredComponent(resources,id,it)}
  val live=panel.string("mode")=="live"
  if(live && (!online || note.privateNote) || !live && components.isEmpty()) return@forEach
  var expanded by remember(note.id,id) { mutableStateOf(model.container.preferences.getBoolean("panel-open:$id",false)) }
  var result by remember(note.id,id,note.serverRev,language) { mutableStateOf<JsonElement?>(null) }
  // Live sections load when the note opens, as on the web; until they have something to say they are absent (03 A5).
  LaunchedEffect(online,note.id,note.serverRev,note.privateNote,language) {
   if(live && online && !note.privateNote) {
    val auth=model.auth.value ?: return@LaunchedEffect
    val epoch=model.pluginReplicaEpoch
    fun current()=model.auth.value==auth && model.pluginReplicaEpoch==epoch && model.container.manifest.enabled(id)
    try {
     val response=pluginApi(auth,resources).guarded {current()}.request("/api/v1/plugins/$id/panel",query=mapOf("note_id" to note.id))
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
internal fun entryMetaText(outputs:List<PluginOutputEntity>,noteId:String,plugins:List<JsonObject>,resources:Resources):String {
 val active=plugins.filter { it.bool("enabled",true) && it.string("state") !in setOf("disabled","off","failed") }.associateBy { it.string("id") }
 return outputs.filter { it.noteId==noteId && it.kind=="annotation" }.map { payload(it.payload) }.filter { it.string("kind")=="meta" && it.string("plugin") in active }.sortedWith(compareBy<JsonObject> { (active[it.string("plugin")]?.get("position") as? JsonPrimitive)?.intOrNull ?: Int.MAX_VALUE }.thenBy { it.string("plugin") }).firstNotNullOfOrNull { data ->
  outputComponents(data).firstOrNull { it.string("type")=="text" }?.string("text")?.take(24)?.takeIf(String::isNotBlank)?.let { text ->
   when(data.string("plugin")) {
    "tasks" -> Regex("([0-9]+) of ([0-9]+) done").matchEntire(text)?.destructured?.let { (done,total) ->
     val completed=done.toIntOrNull()
     val count=total.toIntOrNull()
     if(completed!=null && count!=null) resources.getString(R.string.core_tasks_done,completed,count) else text
    } ?: text
    "link-enrichment" -> localizedReadingTime(resources,text)
    else -> text
   }
  }
 }.orEmpty()
}

private fun localizedReadingTime(resources:Resources,text:String):String = Regex("([0-9]+) min read").matchEntire(text)
 ?.groupValues?.get(1)?.toIntOrNull()?.let {resources.getString(R.string.plugin_minutes_read,it)} ?: text

private fun localizedStoredComponent(resources:Resources,plugin:String,component:JsonObject):JsonObject {
 if(plugin!="link-enrichment" || component.string("type")!="markdown") return component
 val field=if(component["text"]!=null) "text" else "markdown"
 val parts=component.string(field).split("\n\n",limit=3)
 if(parts.size<2 || !parts.first().startsWith("[") || "](" !in parts.first()) return component
 val readingTime=localizedReadingTime(resources,parts[1])
 if(readingTime==parts[1]) return component
 val text=listOf(parts[0],readingTime)+parts.drop(2)
 return JsonObject(component.mapValues { (key,value) ->
  if(key==field) JsonPrimitive(text.joinToString("\n\n")) else value
 })
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
   Text(title,Modifier.weight(1f,fill=false),style=MaterialTheme.typography.labelLarge,maxLines=2,overflow=androidx.compose.ui.text.style.TextOverflow.Ellipsis)
   Box(Modifier.size(48.dp).clickable(onClickLabel=resources.getString(R.string.settings_dismiss_suggestion),onClick=onDismiss).clearAndSetSemantics {},contentAlignment=Alignment.Center) {Text("×",color=SpuntoColors.ink3)}
  }
 }
}

/** 03 §5.1: one line of dashed pills; what does not fit folds into "+N", which unfolds the rest for this note only. Each pill dismisses on × or long-press; the grey "Dismiss" ending the row dismisses them all. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable fun ProposalLine(proposals:List<JsonObject>,pluginNames:Map<String,String>,onSelect:(JsonObject)->Unit,onDismiss:(String)->Unit,receipts:List<JsonObject> = emptyList(),onUndo:(String)->Unit={}) {
 val resources=LocalContext.current.resources
 var expanded by remember { mutableStateOf(false) }
 var previousOrder by remember {mutableStateOf(emptyList<String>())}
 FlowRow(horizontalArrangement=Arrangement.spacedBy(6.dp),verticalArrangement=Arrangement.spacedBy(6.dp),itemVerticalAlignment=Alignment.CenterVertically) {
  val entries=(proposals+receipts).let {items ->
   if(receipts.isEmpty() || previousOrder.isEmpty()) items
   else {
    val order=previousOrder.withIndex().associate {it.value to it.index}
    items.sortedBy {order[it.string("id")] ?: Int.MAX_VALUE}
   }
  }
  (if(expanded) entries else entries.take(3)).forEach { data ->
   if(data.string("status")=="accepted") FlowRow(verticalArrangement=Arrangement.spacedBy(4.dp),itemVerticalAlignment=Alignment.CenterVertically) {
    Text(receiptLabel(resources,data),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
    TextButton(onClick={onUndo(data.string("id"))}) {Text(resources.getString(R.string.settings_undo))}
    TextButton(onClick={onDismiss(data.string("id"))}) {Text(resources.getString(R.string.settings_dismiss))}
   } else ProposedPill(proposalLabel(resources,data),pluginNames[data.string("plugin")] ?: data.string("plugin"),data.string("action") in setOf("replace_body","merge_notes") && !data.bool("one_tap",false),{previousOrder=entries.map {it.string("id")};onSelect(data)},{onDismiss(data.string("id"))})
  }
  if(entries.size>3 && !expanded) Surface(shape=MaterialTheme.shapes.large,color=MaterialTheme.colorScheme.surfaceVariant,contentColor=MaterialTheme.colorScheme.onSurface,modifier=Modifier.heightIn(min=48.dp).clickable(onClickLabel=resources.getQuantityString(R.plurals.settings_more_suggestions,entries.size-3,entries.size-3)) {expanded=true}) {
   Box(Modifier.padding(horizontal=SpuntoSpacing.s12,vertical=SpuntoSpacing.s8)) {Text("+${entries.size-3}",style=MaterialTheme.typography.labelLarge)}
  }
  if(proposals.isNotEmpty()) TextButton(onClick={proposals.forEach {onDismiss(it.string("id"))}},modifier=Modifier.semantics {contentDescription=resources.getQuantityString(R.plurals.settings_dismiss_suggestions,proposals.size,proposals.size)}) {Text(resources.getString(R.string.settings_dismiss),style=MaterialTheme.typography.labelLarge,color=SpuntoColors.ink3)}
 }
}

@Composable fun Suggestions(model:SpuntoViewModel,note:Note,onNote:(String)->Unit,suppressedSuggestionId:String="") {
 val noteId=note.id
 val canWrite by model.workspaceCanWrite.collectAsState()
 if(!canWrite) return
 val catalog by model.catalog.collectAsState()
 val resources=LocalContext.current.resources
 val manifest=model.manifest.collectAsState().value
 val outputs by model.outputs.collectAsState()
 val queued by model.queuedSuggestionUndos.collectAsState()
 val remote by model.remote.collectAsState()
 val online by model.online.collectAsState()
 val masked=suppressedSuggestionId.ifBlank {if(online && model.container.manifest.enabled(remote.string("plugin_id"))) commandSuggestionId(remote) else ""}
 val feedback=LocalFeedback.current
 var selected by remember(noteId) { mutableStateOf<String?>(null) }
 var confirmedLabel by remember(noteId) {mutableStateOf<String?>(null)}
 val plugins=model.container.manifest.plugins()
 val counts=mutableMapOf<String,Int>()
 val noteProposals=remember(outputs,noteId) {outputs.filter {it.noteId==noteId && it.kind=="suggestion"}.map(::proposalData)}
 val proposals=noteProposals.filter { data ->
  val id=data.string("plugin");val count=(counts[id] ?: 0)+1
  (data.string("id")!=masked && data.string("status")=="pending" && model.container.manifest.enabled(id) && count<=3).also { if(it) counts[id]=count }
 }.sortedWith(compareByDescending<JsonObject> {it.string("action")=="set_title"}.thenBy { data -> plugins.firstOrNull { it.string("id")==data.string("plugin") }?.get("position")?.jsonPrimitive?.intOrNull ?: Int.MAX_VALUE }.thenByDescending { (it["confidence"] as? JsonPrimitive)?.doubleOrNull ?: 0.0 })
 val undo:(String)->Unit={model.decide(it,"undo")}
 val receipts=if(note.trashed) emptyList() else noteProposals.filter { data ->
  val receipt=data["receipt"] as? JsonObject ?: return@filter false
  val current=if(receipt.pluginFlag("local")==true) receipt.pluginLong("local_applied_at")==note.updatedAt else !note.pending
  current && data.string("status")=="accepted" && receipt.pluginFlag("undoable")==true && receipt.pluginLong("note_version")==note.serverRev.toLong()
 }
 val labelReceipts=receipts.filter {it.string("action")=="add_label"}
 val otherReceipts=receipts.filterNot {it.string("action")=="add_label"}
 val labelConfirmation=labelReceipts.firstOrNull {it.string("id")==confirmedLabel}
 val confirmationTime=LocalAccessibilityManager.current?.calculateRecommendedTimeoutMillis(4_000,containsText=true) ?: 4_000
 LaunchedEffect(confirmedLabel,confirmationTime) {
  if(confirmedLabel!=null) {delay(confirmationTime);confirmedLabel=null}
 }
 if(proposals.isNotEmpty() || otherReceipts.isNotEmpty() || labelReceipts.isNotEmpty()) Column {
  if(proposals.isNotEmpty() || otherReceipts.isNotEmpty()) ProposalLine(proposals,plugins.associate { it.string("id") to localizedPluginName(resources,it,catalog) },{data ->
   when {
    data.string("action")=="add_label" -> model.decide(data.string("id"),"accept") {confirmedLabel=data.string("id")}
    isOneTapProposal(data) -> acknowledgeSuggestion(model,data,resources,feedback,undo)
    else -> selected=data.string("id")
   }
  },{model.decide(it,"dismiss")},otherReceipts,undo)
  labelConfirmation?.let {Text(receiptLabel(resources,it),style=MaterialTheme.typography.bodySmall,
   color=MaterialTheme.colorScheme.onSurfaceVariant,modifier=Modifier.semantics {liveRegion=LiveRegionMode.Polite})}
  if(labelReceipts.isNotEmpty()) RecentLabelChanges(labelReceipts,undo)
 }
 if(noteId in queued) Text(resources.getString(R.string.plugin_undo_queued),style=MaterialTheme.typography.bodySmall,modifier=Modifier.semantics {liveRegion=LiveRegionMode.Polite})
 outputs.firstOrNull {it.noteId==noteId && it.kind in setOf("undo-refusal","review-refusal")}?.let { failure ->
  val scope=rememberCoroutineScope()
  val nav=LocalNavigator.current
  Column {
   Text(resources.getString(if(failure.kind=="undo-refusal") R.string.plugin_undo_stale else R.string.plugin_review_stale),style=MaterialTheme.typography.bodySmall,modifier=Modifier.semantics {liveRegion=LiveRegionMode.Polite})
   TextButton(onClick={
    model.historyFor.value=noteId
    if(nav.route!="note:$noteId") onNote(noteId)
    scope.launch {model.repository.dao.removeOutput(failure.kind,failure.id)}
   }) {Text(resources.getString(R.string.plugin_open_history))}
  }
 }
 selected?.let { id -> outputs.firstOrNull { it.id==id && it.kind=="suggestion" }?.let { SuggestionDecisionDialog(model,proposalData(it),onNote) { selected=null } } }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable internal fun RecentLabelChanges(receipts:List<JsonObject>,onUndo:(String)->Unit) {
 val resources=LocalContext.current.resources
 var expanded by remember {mutableStateOf(false)}
 TextButton(onClick={expanded=!expanded},contentPadding=PaddingValues(0.dp),modifier=Modifier.heightIn(min=48.dp).semantics {
  stateDescription=resources.getString(if(expanded) R.string.settings_expanded else R.string.settings_collapsed)
 }) {
  Icon(painterResource(R.drawable.ic_expand),contentDescription=null,modifier=Modifier.size(20.dp).rotate(if(expanded) 180f else 0f))
  Spacer(Modifier.width(SpuntoSpacing.s4))
  Text(resources.getString(R.string.note_recent_changes))
 }
 if(expanded) receipts.forEach {receipt ->
  FlowRow(horizontalArrangement=Arrangement.spacedBy(SpuntoSpacing.s8),itemVerticalAlignment=Alignment.CenterVertically) {
   Text(receiptLabel(resources,receipt),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
   TextButton(onClick={onUndo(receipt.string("id"))}) {Text(resources.getString(R.string.settings_undo))}
  }
 }
}

private fun isOneTapProposal(data:JsonObject)=data.string("action") in setOf("add_label","set_title") || data.bool("one_tap",false)

private fun proposalLabel(resources:Resources,data:JsonObject):String {
 val proposed=data["payload"] as? JsonObject
 val title=data.string("title")
 val stock=pluginPillCaption(resources,data.string("plugin"),title)
 if(stock!=title) return stock
 return title.ifBlank {
  when(data.string("action")) {
   "add_label" -> resources.getString(R.string.settings_add,"#"+proposed?.string("path").orEmpty().removePrefix("#"))
   "set_title" -> resources.getString(R.string.plugin_use_title,proposed?.string("title").orEmpty())
   "append_text" -> if(data.string("plugin")=="links") {
    val text=proposed?.string("text").orEmpty()
    val label=text.takeIf {it.startsWith("[") && "](/n/" in it}?.substringBeforeLast("](/n/")?.removePrefix("[")
    if(label.isNullOrBlank()) resources.getString(R.string.plugin_add_link) else resources.getString(R.string.plugin_add_link_to,label)
   } else resources.getString(R.string.settings_review_change)
   "replace_body" -> if(data.string("plugin")=="voice-memos" && data.bool("one_tap",false)) resources.getString(R.string.plugin_append_transcript) else resources.getString(R.string.settings_review_change)
   else -> resources.getString(R.string.settings_review_change)
  }
 }
}
private fun receiptLabel(resources:Resources,data:JsonObject):String {
 val proposed=data["payload"] as? JsonObject
 return when(data.string("action")) {
  "add_label" -> resources.getString(R.string.plugin_label_added,"#"+proposed?.string("path").orEmpty().removePrefix("#"))
  "set_title" -> resources.getString(R.string.plugin_title_applied,proposed?.string("title").orEmpty())
  "merge_notes" -> resources.getString(R.string.plugin_notes_merged)
  "replace_body" -> resources.getString(if(data.string("plugin")=="voice-memos") R.string.plugin_transcript_added else R.string.plugin_note_updated)
  "append_text" -> resources.getString(if(data.string("plugin")=="links") R.string.plugin_note_linked else R.string.plugin_text_added)
  else -> resources.getString(R.string.plugin_changes_applied)
 }
}
private fun acknowledgeSuggestion(model:SpuntoViewModel,data:JsonObject,resources:Resources,feedback:Feedback,onUndo:(String)->Unit) {
 val id=data.string("id")
 model.decide(id,"accept") {
  val cached=model.repository.dao.output("suggestion",id)
  val receipt=cached?.let {payload(it.payload)["receipt"] as? JsonObject}
  val appliedInReplica=receipt?.pluginFlag("undoable")==true
  if(appliedInReplica || data.string("action") !in setOf("add_label","set_title","replace_body")) {
   feedback.show(receiptLabel(resources,data),if(appliedInReplica) resources.getString(R.string.settings_undo) else null) {onUndo(id)}
  }
 }
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
 val canWrite by model.workspaceCanWrite.collectAsState()
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
      Text(proposalLabel(resources,data),style=MaterialTheme.typography.titleSmall)
      val plugin=data.string("plugin")
      Text(model.container.manifest.plugins().firstOrNull { it.string("id")==plugin }?.let {localizedPluginName(resources,it,catalog)} ?: plugin,style=MaterialTheme.typography.bodySmall)
      item.noteId?.let { RoomReference(model,it,"",onNote) }
      if(data.string("reason").isNotBlank()) Text(data.string("reason"),style=MaterialTheme.typography.bodySmall)
      Row { TextButton(enabled=canWrite,onClick={selected=item.id}) { Text(resources.getString(R.string.settings_review)) };TextButton(enabled=canWrite && (data.string("action")!="label_change" || online),onClick={model.decide(item.id,"dismiss")}) { Text(resources.getString(R.string.settings_dismiss)) } }
     }
    }
   }
   if(group.size>3) item { TextButton(onClick={expanded[kind]=expanded[kind]!=true}) { Text(if(expanded[kind]==true) resources.getString(R.string.settings_show_fewer) else resources.getQuantityString(R.plurals.settings_more_items,group.size-3,group.size-3)) } }
  }
 }
 selected?.let { id -> outputs.firstOrNull { it.id==id && it.kind=="suggestion" }?.let { SuggestionDecisionDialog(model,proposalData(it),onNote) { selected=null } } }
}

@Composable internal fun ResultNoteLink(title:String,reason:String="",onOpen:()->Unit) {
 OutlinedCard(onClick=onOpen,modifier=Modifier.fillMaxWidth().heightIn(min=56.dp)) {
  Row(Modifier.fillMaxWidth().padding(horizontal=SpuntoSpacing.s16,vertical=SpuntoSpacing.s12),
   verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(SpuntoSpacing.s12)) {
   Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(SpuntoSpacing.s4)) {
    Text(title,style=MaterialTheme.typography.titleMedium,maxLines=3,overflow=TextOverflow.Ellipsis)
    if(reason.isNotBlank()) Text(reason,style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
   }
   Text("›",style=MaterialTheme.typography.titleLarge,modifier=Modifier.clearAndSetSemantics {})
  }
 }
}

@Composable private fun RoomReference(model:SpuntoViewModel,id:String,reason:String,onNote:(String)->Unit) {
 val resources=LocalContext.current.resources
 val loaded by remember(model,id) { model.repository.observe(id).map {it to true} }.collectAsState(null to false)
 if(!loaded.second) return
 val note=loaded.first?.takeUnless {it.trashed}
 if(note==null) Text(resources.getString(R.string.settings_note_unavailable),style=MaterialTheme.typography.bodySmall)
 else ResultNoteLink(note.title.ifBlank {note.body.lineSequence().firstOrNull().orEmpty().take(100).ifBlank {resources.getString(R.string.settings_open_note)}},reason) {onNote(id)}
}

internal fun withoutRepeatedResultHeading(component:JsonObject,header:String):JsonObject {
 if(header.isBlank() || component.string("type")!="markdown") return component
 val field=if(component["text"]!=null) "text" else "markdown"
 val body=component.string(field)
 if(body.lineSequence().firstOrNull()?.trimEnd()!="# $header") return component
 return JsonObject(component+(field to JsonPrimitive(body.substringAfter('\n',"").trimStart('\r','\n'))))
}

/** One renderer for bare arrays, stored payloads and live command envelopes. */
@Composable fun RenderOutput(model:SpuntoViewModel,data:JsonElement,onNote:(String)->Unit,onResult:((JsonObject)->Unit)?=null,commandScope:CoroutineScope?=null,onProposalOpen:()->Unit={},hideSuggestion:String="",onRunning:(Boolean)->Unit={},headingShown:String="") {
 val catalog by model.catalog.collectAsState()
 val resources=LocalContext.current.resources
 val online by model.online.collectAsState()
 val outputs by model.outputs.collectAsState()
 val canWrite by model.workspaceCanWrite.collectAsState()
 val wrapper=data as? JsonObject ?: JsonObject(emptyMap())
 val plugin=wrapper.string("plugin").ifEmpty { wrapper.string("plugin_id") }
 val command=wrapper.string("command_id")
 val journal=plugin==DecisionJournalId
 val initial=wrapper["command_args"] as? JsonObject ?: JsonObject(emptyMap())
 val components=outputComponents(data)
 val translating=plugin=="translate" && command=="translate"
 val translatedLanguage=initial.string("language")
 val translatedBody=translationPreviewBody(data,components)
 val translationPreview=translatedLanguage in translationLanguages && translatedBody!=null
 val sourcePicker=sourcePickerState(plugin,command,initial,components)
 val deskPicker=plugin=="thinking-desk" && sourcePicker!=null
 val deskPickerChanged=deskPicker && (initial.string("add_id").isNotBlank() || initial.string("remove").isNotBlank() || initial["refresh"]==JsonPrimitive(true))
 val contextPacks=plugin=="context-packs"
 val packLibraryRows=if(contextPacks && command=="packs") contextPackLibraryRows(components) else emptyMap()
 val packPrimary=if(contextPacks) components.firstOrNull { component ->
  val args=component["args"] as? JsonObject
  component.string("type")=="command" && when(component.string("id")) {
   "edit" -> args?.string("action") in setOf("start","finish","save-note")
   "sources" -> args?.get("expected_revision")!=null
   "packs" -> args?.bool("export")==true
   else -> false
  }
 } else null
 val deskPrimary=when {
  deskPicker -> components.takeWhile {it.string("type")!="form"}.firstOrNull {it.string("type")=="command" && it.string("id")=="desk"}
  plugin=="thinking-desk" -> components.firstOrNull(::thinkingDeskWorkAction)
  else -> null
 }
 val pickerSupported=plugin in setOf("thinking-desk","context-packs") && supportsNotePicker(model.manifest.collectAsState().value,plugin)
 val pickerSession=if(pickerSupported) remember(plugin) {NotePickerSession.open(model,plugin)} else null
 val ownsPicker=if(pickerSession==null || !canWrite) false else {
  val auth by model.auth.collectAsState()
  val epoch by model.pluginReplicaEpochState.collectAsState()
  val gate by model.container.appLock.state.collectAsState()
  remember(auth,epoch,gate,pickerSession) {pickerSession.current(model)}
 }
 if(sourcePicker!=null && !ownsPicker) return
 val deliver:(JsonObject)->Unit=onResult ?: { model.remote.value=it }
 val ownScope=rememberCoroutineScope()
 val scope=commandScope ?: ownScope
 var running by remember(data) { mutableStateOf(false) }
 var error by remember(data) {mutableStateOf("")}
 var pickerRefresh by remember {mutableIntStateOf(0)}
 val run:(String,JsonObject)->Unit=run@{id,args ->
  if(running || !online || !canWrite || plugin.isBlank()) return@run
  val opensPicker=pickerSupported && opensSourcePicker(plugin,id,args)
  val usesPicker=opensPicker || sourcePicker!=null
  if(usesPicker && !ownsPicker) return@run
  val input=if(opensPicker) JsonObject(args+("picker" to JsonPrimitive(true))) else args
  running=true;error="";onRunning(true)
  runCommand(model,plugin,id,input,deliver,{running=false;onRunning(false)},scope,onError={
   error=it
   if(sourcePicker!=null && id=="sources") pickerRefresh++
  },pickerSession=pickerSession.takeIf {usesPicker},requireOwner=plugin==ThinkingDeskRoute)
 }
 val review:(String)->Unit={ id -> if(model.outputs.value.any { it.id==id && it.kind=="suggestion" }) { onProposalOpen();model.remote.value=buildJsonObject { put("suggestion_preview",id) } } else model.message.value=resources.getString(R.string.settings_sync_to_review_this_suggestion) }
 Column(verticalArrangement=Arrangement.spacedBy(SpuntoSpacing.s12)) {
  if(components.isEmpty() && command.isNotBlank()) Text(resources.getString(R.string.settings_this_result_has_no_preview_in_this),style=MaterialTheme.typography.bodySmall)
  if(translationPreview) {
   Text(resources.getString(R.string.plugin_translation_preview,translationLanguageName(resources,translatedLanguage)),style=MaterialTheme.typography.titleMedium,modifier=Modifier.semantics {heading()})
   Text(resources.getString(R.string.plugin_translation_linked_note),style=MaterialTheme.typography.bodySmall)
  }
  if(translatedBody!=null) Markdown(translatedBody,machine=true,onLink={if(it.startsWith("/n/")) onNote(it.substringAfter("/n/"))})
  components.filterNot {it.string("type")=="pill" && hideSuggestion.isNotBlank() && it.string("suggestion_id")==hideSuggestion}.forEachIndexed { index,raw ->
   if(index+1 in packLibraryRows) return@forEachIndexed
   packLibraryRows[index]?.let { title ->
    val args=raw["args"] as JsonObject
    TextButton(onClick={run("packs",args)},enabled=online && canWrite && !running) {Text(title)}
    return@forEachIndexed
   }
   if(translatedBody!=null && raw.string("type")=="markdown") return@forEachIndexed
   val component=if(index==0) withoutRepeatedResultHeading(raw,headingShown) else raw
   if(index==0 && deskPickerChanged && component.string("type")=="text") {
    Text(component.string("text"),modifier=Modifier.semantics {liveRegion=LiveRegionMode.Polite})
    return@forEachIndexed
   }
   if(component.string("type")=="form" && sourcePicker!=null && pickerSession!=null) {
    SourceNotePicker(model,sourcePicker,pickerSession,online,running,pickerRefresh,onQueryChanged={error=""}) {run(command,it)}
    if(error.isNotBlank()) Text(error,color=MaterialTheme.colorScheme.error,modifier=Modifier.semantics {liveRegion=LiveRegionMode.Polite})
    if((plugin=="thinking-desk" || contextPacks) && sourcePicker.selectedIds.isEmpty()) return@forEachIndexed
   }
   if(journal) when(component.string("type")) {
    "text" -> if(command=="journal") {
     Text(localizedJournalLabel(resources,component.string("text")))
     return@forEachIndexed
    }
    "form" -> {
     DecisionForm(component["schema"]!!.jsonObject,command,initial,online && canWrite,running) {run(command,JsonObject(initial+it))}
     if(!online && !running) DecisionSyncAction(model)
     return@forEachIndexed
    }
    "command" -> {
     if(online && canWrite) {
      val action=component.string("id")
      val args=component["args"] as? JsonObject ?: JsonObject(emptyMap())
      val label=when {
       action=="review-decision" && args.string("request_id").isNotBlank() -> R.string.decision_resume_review
       action=="review-decision" -> R.string.settings_bundled_title_13
       action=="journal" && args.string("outcome_cursor").isNotBlank() -> R.string.decision_more_outcomes
       action=="journal" && args.string("cursor").isNotBlank() -> R.string.decision_more_decisions
       else -> null
      }
      TextButton(onClick={run(action,args)},enabled=!running) {Text(label?.let(resources::getString) ?: component.string("title"))}
     }
     return@forEachIndexed
    }
    "notes" -> if(command in setOf("record-decision","review-decision")) {
     DecisionSavedOutput(model,component["ids"] as? JsonArray ?: JsonArray(emptyList()),command=="review-decision",onNote)
     return@forEachIndexed
    }
   }
   val output=if(wrapper.string("content_language").isBlank()) pluginFormComponent(resources,plugin,component) else component
   Vocabulary(output,onNote,review,run,online=online && canWrite && !running,
    choiceLabel={field,value -> pluginFormChoice(resources,plugin,field,value)},
    formSubmitOnChange=deskPicker,
    formLabel=when {
     translating -> resources.getString(R.string.settings_bundled_title_6)
     plugin=="translate" && command=="language-settings" -> resources.getString(R.string.core_save)
     else -> contextPackFormLabel(plugin,output) ?: thinkingDeskFormLabel(plugin,output)
    },
    primaryCommand=when {
     translationPreview -> "save-translation"
     component === packPrimary -> component.string("id")
     component === deskPrimary || plugin=="thinking-desk" && thinkingDeskStartAction(component) -> component.string("id")
     else -> null
    },
    onForm=if(canWrite && command.isNotBlank()) { args -> run(command,sourcePicker?.update(args) ?: JsonObject(initial+args)) } else null,
    formInitial=if(sourcePicker!=null) sourcePicker.args else initial,
    formHidden=if(sourcePicker!=null) setOf("selected_ids","max_sources") else initial.keys.filter { it=="note_id" }.toSet(),
    noteReference={id,reason -> RoomReference(model,id,if(journal && command=="journal") localizedJournalLabel(resources,reason) else reason,onNote)},proposalPill=if(!canWrite) null else { pill ->
     val id=pill.string("suggestion_id")
     val proposal=outputs.firstOrNull {it.id==id && it.kind=="suggestion"}?.let(::proposalData)
     val pending=proposal?.takeIf {it.string("status")=="pending" && it.string("plugin")==plugin}
     val oneTap=pending?.takeIf(::isOneTapProposal)
     if(proposal==null || pending!=null) {
      val feedback=LocalFeedback.current
      val label=oneTap?.let {proposalLabel(resources,it)} ?: pluginPillCaption(resources,plugin,pill.string("label"))
      val pluginName=model.container.manifest.plugins().firstOrNull {it.string("id")==plugin}?.let {localizedPluginName(resources,it,catalog)} ?: plugin
      ProposedPill(label,pluginName,oneTap==null && pending?.string("action") in setOf("replace_body","merge_notes"),{
       if(oneTap==null) review(id)
       else {
        acknowledgeSuggestion(model,oneTap,resources,feedback) {model.decide(it,"undo")}
        onProposalOpen()
       }
      },{model.decide(id,"dismiss")})
     }
    })
  }
  if(sourcePicker==null && error.isNotBlank()) Text(error,color=MaterialTheme.colorScheme.error,modifier=Modifier.semantics {liveRegion=LiveRegionMode.Polite})
  if(running) {
   LinearProgressIndicator(Modifier.fillMaxWidth())
   Text(resources.getString(when {
    journal && command=="journal" -> R.string.decision_opening
    journal -> R.string.settings_saving
    else -> R.string.plugin_working
   }),style=MaterialTheme.typography.bodySmall)
  }
 }
}

internal fun commandSuggestionId(result:JsonObject):String =
 if(result.string("plugin_id")=="tidy") outputComponents(result).singleOrNull {it.string("type")=="pill" && it.string("action")=="accept"}?.string("suggestion_id").orEmpty()
 else ""

internal fun commandSuccess(result:JsonObject):String? {
 val payload=result["payload"] as? JsonObject ?: return null
 return payload.pluginText("text")?.takeIf {
  it.isNotBlank() && payload.pluginText("type")=="text" && payload.pluginText("feedback")=="success" &&
   safeComponent(JsonObject(payload-"feedback"))?.pluginText("text")==it
 }
}

internal fun commandReviewMatches(result:JsonObject,proposal:JsonObject?,note:Note?,diff:JsonObject?):Boolean {
 if(proposal==null || note==null || note.pending || note.trashed) return false
 val args=result["command_args"] as? JsonObject ?: return false
 val version=proposal.pluginLong("note_version") ?: return false
 return proposal.string("id")==commandSuggestionId(result) &&
   proposal.string("status")=="pending" && proposal.string("action")=="replace_body" &&
   proposal.string("plugin")==result.string("plugin_id") &&
   proposal.string("note_id")==note.id && note.id==args.string("note_id") &&
   note.serverRev.toLong()==version && (diff==null || diff.pluginLong("base_version")==version)
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable fun CommandResultSheet(model:SpuntoViewModel,result:JsonObject,onNote:(String)->Unit,onResult:(JsonObject)->Unit,onClose:()->Unit,onReview:(()->Unit)?=null) {
 val feedback=LocalFeedback.current
 val success=commandSuccess(result)
 if(success!=null) {
  LaunchedEffect(result) {onClose();feedback.show(success)}
  return
 }
 val resources=LocalContext.current.resources
 val clipboard=LocalClipboardManager.current
 val manifest=model.manifest.collectAsState().value
 val catalog by model.catalog.collectAsState()
 val plugin=catalog.objects("plugins").firstOrNull { it.string("id")==result.string("plugin_id") }
 val sheetScope=rememberCoroutineScope()
 var active by remember { mutableStateOf(true) }
 var journalSubmitting by remember { mutableStateOf(false) }
 val close={active=false;sheetScope.cancel();onClose()}
 val outputs by model.outputs.collectAsState()
 val suggestionId=commandSuggestionId(result)
 val proposal=outputs.firstOrNull {it.id==suggestionId && it.kind=="suggestion"}?.let(::proposalData)
 val noteId=proposal?.string("note_id").orEmpty()
 val current by remember(noteId) {model.repository.observe(noteId)}.collectAsState(null)
 val diff=outputComponents(result).singleOrNull {it.string("type")=="diff"}
 val matching=commandReviewMatches(result,proposal,current,diff)
 var fullReview by remember(result) {mutableStateOf(false)}
 var applying by remember(result) {mutableStateOf(false)}
 var applyError by remember(result) {mutableStateOf("")}
 val focusManager=androidx.compose.ui.platform.LocalFocusManager.current
 val canWrite by model.workspaceCanWrite.collectAsState()
 val locked=journalSubmitting || applying
 val command=(plugin?.get("contributes") as? JsonObject)?.objects("commands")?.firstOrNull {it.string("id")==result.string("command_id")}
 val title=if(plugin!=null && command!=null && plugin.string("id") !in setOf("thinking-desk","context-packs")) localizedPluginCommand(resources,plugin,command,catalog)
  else plugin?.let {localizedPluginName(resources,it,catalog)} ?: resources.getString(R.string.settings_tool_result)
 val ask=result.string("plugin_id")=="ask" && supportsAskConversation(manifest) && askContinuation(result)!=null
 val maximumHeight=(LocalConfiguration.current.screenHeightDp * 0.88f).dp
 val height=if(ask) Modifier.height(maximumHeight) else Modifier.heightIn(max=maximumHeight)
 ModalBottomSheet(
  onDismissRequest={if(!locked) close()},scrimColor=MaterialTheme.colorScheme.scrim,
  containerColor=MaterialTheme.colorScheme.surface.copy(alpha=1f),
  sheetState=rememberModalBottomSheetState(skipPartiallyExpanded=true,confirmValueChange={it!=SheetValue.Hidden || !locked}),
 ) {
  Column(Modifier.fillMaxWidth().then(height).padding(horizontal=SpuntoSpacing.s20),
   verticalArrangement=Arrangement.spacedBy(SpuntoSpacing.s12)) {
   Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(SpuntoSpacing.s8)) {
    Text(title,modifier=Modifier.weight(1f).semantics {heading()},style=MaterialTheme.typography.titleLarge)
    TextButton(onClick=close,enabled=!locked,modifier=Modifier.heightIn(min=48.dp)) {Text(resources.getString(R.string.settings_close))}
   }
   HorizontalDivider(color=MaterialTheme.colorScheme.outlineVariant)
   if(ask) {
    Box(Modifier.weight(1f)) {AskConversation(model,{id -> close();onNote(id)},result,(result["command_args"] as? JsonObject)?.string("question").orEmpty())}
   } else {
    key(result) {
     Column(Modifier.weight(1f,fill=false).verticalScroll(rememberScrollState()).padding(bottom=SpuntoSpacing.s16),
      verticalArrangement=Arrangement.spacedBy(SpuntoSpacing.s16)) {
      ProvideTextStyle(MaterialTheme.typography.bodyLarge) {
       if(fullReview && matching && proposal!=null) SuggestionPreview(model,proposal)
       else RenderOutput(model,result,{id -> close();onNote(id)},{if(active) onResult(it)},commandScope=sheetScope,onProposalOpen=close,hideSuggestion=suggestionId,onRunning={if(journalResult(result)) journalSubmitting=it},headingShown=title)
      }
      (plugin?.get("ai_destinations") as? JsonObject)?.forEach { (_,raw) -> (raw as? JsonObject)?.let { destination -> Text(resources.getString(R.string.settings_at ,destination.string("model"),destination.string("host"),if(destination.bool("leaves_network")) resources.getString(R.string.settings_outside_your_network) else resources.getString(R.string.settings_on_your_network)),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant) } }
      if(suggestionId.isNotBlank() && !matching) Text(resources.getString(R.string.plugin_review_stale),style=MaterialTheme.typography.bodySmall)
      if(applyError.isNotBlank()) Text(applyError,color=MaterialTheme.colorScheme.error,modifier=Modifier.semantics {liveRegion=LiveRegionMode.Polite})
     }
    }
    val deskLanding=plugin?.string("id")=="thinking-desk" && outputComponents(result).any(::thinkingDeskStartAction)
    val copied=if(deskLanding || onReview!=null) "" else resultCopyText(resources,result)
    if(onReview!=null) TextButton(onClick={close();onReview()}) {
     Text(resources.getString(R.string.plugin_open_review))
    }
    if(matching || copied.isNotBlank()) {
     HorizontalDivider(color=MaterialTheme.colorScheme.outlineVariant)
     FlowRow(Modifier.fillMaxWidth().padding(bottom=SpuntoSpacing.s8),horizontalArrangement=Arrangement.spacedBy(SpuntoSpacing.s8)) {
      if(matching && proposal!=null) {
       if(diff==null && !fullReview) Button(onClick={fullReview=true}) {Text(resources.getString(R.string.plugin_review_changes))}
       else Button(enabled=!applying && canWrite,onClick={
        val auth=model.auth.value
        if(active && !applying && auth!=null) {
         applying=true
         applyError=""
         focusManager.clearFocus()
         val epoch=model.pluginReplicaEpoch
         fun current()=active && model.auth.value==auth && model.pluginReplicaEpoch==epoch && model.workspaceCanWrite.value
         sheetScope.launch {
          try {
           val response=pluginApi(auth,resources).guarded {current()}.post("/api/v1/suggestions/$suggestionId/accept",JsonObject(emptyMap()))
           model.synchronize().join()
           if(current()) {
            if(response.pluginFlag("applied")==true) {
             val receipt=(response["suggestion"] as? JsonObject)?.get("receipt") as? JsonObject
             feedback.show(receiptLabel(resources,proposal),resources.getString(R.string.settings_undo).takeIf {receipt?.pluginFlag("undoable")==true}) {model.decide(suggestionId,"undo")}
            }
            close()
           }
          } catch(error:CancellationException) {throw error}
          catch(error:Exception) {
           if(current()) {
            if(error is ApiException && error.status==401) model.reauthenticate.value=true
            if(error is ApiException && error.status==409) model.synchronize().join()
            applyError=if(error is ApiException) pluginApiError(resources,error) else resources.getString(R.string.sys_server_unreachable)
           }
          } finally {applying=false}
         }
        }
       }) {Text(resources.getString(if(applying) R.string.settings_saving else R.string.settings_apply_changes))}
      }
      if(copied.isNotBlank()) TextButton(onClick={clipboard.setText(AnnotatedString(copied));feedback.show(resources.getString(R.string.core_copied))},modifier=Modifier.heightIn(min=48.dp)) {Text(resources.getString(if(diff!=null) R.string.settings_copy_changes else R.string.settings_copy))}
     }
    }
   }
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
  "append_text" -> if(data.string("plugin")=="links") Text(resources.getString(R.string.core_link_legacy_preview)) else {
   Text(resources.getString(R.string.settings_append_this_text));Markdown(proposed.string("text"),machine=true)
  }
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
 val feedback=LocalFeedback.current
 val suggestionId=data.string("id")
 val link=action=="append_text" && data.string("plugin")=="links"
 val linkSupported=link && supportsNotePicker(model.manifest.collectAsState().value,"links")
 val canWrite by model.workspaceCanWrite.collectAsState()
 val auth by model.auth.collectAsState()
 val epoch by model.pluginReplicaEpochState.collectAsState()
 val gate by model.container.appLock.state.collectAsState()
 val linkSession=remember(suggestionId,linkSupported,auth,epoch,gate.revision,canWrite) {
  if(linkSupported && canWrite) NotePickerSession.open(model,"links") else null
 }
 val scope=rememberCoroutineScope()
 var acceptingLink by remember(suggestionId) {mutableStateOf(false)}
 var linkError by remember(suggestionId,linkSession) {mutableStateOf("")}
 val note by remember(noteId) { model.repository.observe(noteId) }.collectAsState(null)
 val source by remember(proposed.string("source_id")) { model.repository.observe(proposed.string("source_id")) }.collectAsState(null)
 val online by model.online.collectAsState()
 var previewAttempt by remember(suggestionId) {mutableIntStateOf(0)}
 var linkPreview by remember(suggestionId,linkSession,online,previewAttempt) {mutableStateOf<Note?>(null)}
 var loadingLink by remember(suggestionId,linkSession,online,previewAttempt) {mutableStateOf(linkSession!=null && online)}
 LaunchedEffect(suggestionId,noteId,linkSession,online,previewAttempt) {
  if(linkSession==null || !online) return@LaunchedEffect
  linkError=""
  try {
   linkPreview=loadLegacyLinkPreview(linkSession.api(model),noteId,suggestionId)
   if(linkPreview==null) linkError=resources.getString(R.string.settings_note_unavailable)
  } catch(error:CancellationException) {throw error}
  catch(error:Exception) {linkError=linkSession.reportError(model,resources,error)}
  finally {loadingLink=false}
 }
 val uri=LocalUriHandler.current
 val current=note
 val base=(data["note_version"] as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull
 val stale=action=="replace_body" && (current==null || current.pending || current.serverRev!=base)
 val eligible=current!=null && !current.trashed && !current.privateNote
 val canApply=canWrite && data.string("status")=="pending" && !stale && when(action) {
  "label_change" -> false
  "merge_notes" -> eligible && source!=null && !source!!.trashed && !source!!.privateNote
  "open" -> true
  else -> if(link) current!=null && !current.trashed && linkPreview!=null && online &&
   linkSession?.current(model)==true && !acceptingLink else eligible
 }
 AlertDialog(onDismissRequest={if(!acceptingLink) onClose()},title={Text(if(action=="merge_notes") resources.getString(R.string.settings_compare_notes) else resources.getString(R.string.settings_review_change))},text={Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(SpuntoSpacing.s12)) {
  if(!link) SuggestionPreview(model,data)
  else if(loadingLink) Text(resources.getString(R.string.core_loading),modifier=Modifier.semantics {liveRegion=LiveRegionMode.Polite})
  else linkPreview?.let { target ->
   Text(resources.getString(R.string.settings_destination),style=MaterialTheme.typography.labelLarge)
   Text(target.title.ifBlank {resources.getString(R.string.core_untitled)},style=MaterialTheme.typography.titleMedium)
   searchSnippet(target.body,emptyList())?.let { Text(it,style=MaterialTheme.typography.bodySmall) }
  }
  if(stale) Text(resources.getString(R.string.settings_this_note_changed_wait_for_a_fresh),style=MaterialTheme.typography.bodySmall)
  if(data.string("status")!="pending") Text(suggestionStatus(resources,data.string("status")),style=MaterialTheme.typography.bodySmall)
  if(link && !linkSupported) Text(resources.getString(R.string.core_link_upgrade_server))
  else if(link && !online) Text(resources.getString(R.string.settings_needs_a_connection))
  if(linkError.isNotBlank()) {
   Text(linkError,color=MaterialTheme.colorScheme.error,modifier=Modifier.semantics {liveRegion=LiveRegionMode.Polite})
   if(linkPreview==null && online && linkSession!=null)
    TextButton(onClick={previewAttempt++}) {Text(resources.getString(R.string.core_ask_retry))}
  }
 }},confirmButton={
  if(action=="label_change") TextButton(enabled=online,onClick={model.auth.value?.let { runCatching { uri.openUri(it.serverUrl.trimEnd('/')+"/review") } };onClose()}) { Text(if(online) resources.getString(R.string.settings_open_web_review) else resources.getString(R.string.settings_needs_a_connection)) }
  else if(link && linkSession!=null) TextButton(enabled=canApply,onClick={
   if(!acceptingLink) {
    acceptingLink=true;linkError=""
    scope.launch {
     try {
      linkSession.cleanSource(model,noteId)
      // A legacy proposal still needs the server to validate its current link and visibility.
      linkSession.api(model).request("/api/v1/suggestions/$suggestionId/accept","POST")
      linkSession.refresh(model)
      feedback.show(receiptLabel(resources,data))
      onClose()
     } catch(error:CancellationException) {throw error}
     catch(error:Exception) {
      if(error is ApiException && error.status==404) linkPreview=null
      linkError=linkSession.reportError(model,resources,error)
     }
     finally {acceptingLink=false}
    }
   }
  }) {Text(resources.getString(if(acceptingLink) R.string.core_link_inserting else R.string.settings_apply))}
  else TextButton(enabled=canApply,onClick={if(action=="open") proposed.string("note_id").takeIf(String::isNotBlank)?.let(onNote);acknowledgeSuggestion(model,data,resources,feedback) {model.decide(it,"undo")};onClose()}) { Text(if(action=="replace_body") resources.getString(R.string.settings_apply_changes) else resources.getString(R.string.settings_apply)) }
 },dismissButton={TextButton(enabled=canWrite && !acceptingLink && (action!="label_change" || online),onClick={model.decide(suggestionId,"dismiss");onClose()}) { Text(if(action=="replace_body") resources.getString(R.string.settings_keep_original) else resources.getString(R.string.settings_dismiss)) }})
}

internal fun suggestionStatus(resources:Resources,status:String)=resources.getString(when(status) {
 "pending" -> R.string.settings_suggestion_pending;"accepted" -> R.string.settings_suggestion_accepted;"dismissed" -> R.string.settings_suggestion_dismissed;else -> R.string.settings_suggestion_unavailable
})

private val globalToolCommands=mapOf(
 "digest" to "digest",
 "context-packs" to "packs",
)

internal fun globalTools(catalog:JsonObject,manifest:JsonObject):List<Pair<NativePluginFacts,JsonObject>> =
 catalog.objects("plugins").mapNotNull {raw ->
  val plugin=NativePluginFacts(raw)
  val commandId=globalToolCommands[plugin.id] ?: return@mapNotNull null
  val active=manifest.objects("plugins").any { it.string("id")==plugin.id && it.bool("enabled",true) }
  if(!plugin.wantedOn || plugin.state!="enabled" || !active) return@mapNotNull null
  plugin.contribution.objects("commands").firstOrNull {command ->
   command.string("id")==commandId && command.pluginStrings("where").orEmpty().any {it in setOf("global","palette")}
  }?.let {plugin to it}
 }.distinctBy {it.first.id}

@Composable internal fun GlobalToolsScreen(model:SpuntoViewModel) {
 val resources=LocalContext.current.resources
 val catalog by model.catalog.collectAsState()
 val online by model.online.collectAsState()
 val manifest by model.manifest.collectAsState()
 val tools=remember(catalog,manifest) {globalTools(catalog,manifest)}
 val scope=rememberCoroutineScope()
 var running by remember {mutableStateOf(false)}
 var error by remember {mutableStateOf("")}
 LazyColumn(contentPadding=PaddingValues(SpuntoSpacing.s20),verticalArrangement=Arrangement.spacedBy(SpuntoSpacing.s12)) {
  if(online) items(tools,key={it.first.id}) {(plugin,command) ->
   TextButton(enabled=!running,onClick={
    if(!running) {
     running=true
     error=""
     runCommand(model,plugin.id,command.string("id"),JsonObject(emptyMap()),{model.remote.value=it},{running=false},scope,onError={error=it})
    }
   }) {Text(localizedPluginCommand(resources,plugin.raw,command,catalog))}
  }
  else item {Text(resources.getString(R.string.settings_connect_to_the_server_to_use_tools))}
  if(running) item {LinearProgressIndicator(Modifier.fillMaxWidth())}
  if(error.isNotBlank()) item {Text(error,color=MaterialTheme.colorScheme.error,modifier=Modifier.semantics {liveRegion=LiveRegionMode.Polite})}
 }
}
