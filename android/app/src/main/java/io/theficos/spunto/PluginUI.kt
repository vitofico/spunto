@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package io.theficos.spunto

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.*
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.theficos.spunto.core.design.Markdown
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
internal fun resultCopyText(data:JsonElement):String=outputComponents(data).mapNotNull { component ->
 when(component.string("type")) {
  "text" -> component.string("text")
  "markdown" -> component.string("text").ifEmpty { component.string("markdown") }
  "diff" -> component.objects("hunks").joinToString("\n\n") {hunk ->val (before,after)=diffHunkTexts(hunk);"Before:\n$before\n\nAfter:\n$after"}
  else -> null
 }
}.joinToString("\n\n")

internal fun runCommand(model:SpuntoViewModel,plugin:String,id:String,args:JsonObject,onResult:(JsonObject)->Unit,onComplete:()->Unit={},scope:CoroutineScope=model.viewModelScope,onError:(String)->Unit={model.message.value=it})=model.task(scope) {
 val auth=model.auth.value
 val epoch=model.pluginReplicaEpoch
 fun current()=auth!=null && model.auth.value==auth && model.pluginReplicaEpoch==epoch && model.container.manifest.enabled(plugin)
 try {
  require(Regex("[a-z][a-z0-9-]{1,23}").matches(plugin) && Regex("[a-z][a-z0-9-]{0,127}").matches(id)) { "This command is unavailable." }
  if(!current()) return@task
  require(model.online.value) {"Connect to the server to use this tool."}
  args.string("note_id").takeIf(String::isNotBlank)?.let {noteId ->
   val note=model.repository.observe(noteId).first()
   require(note!=null && !note.trashed) {"This note is no longer available."}
   if(note.pending || note.serverRev==0) {
    model.synchronize().join()
    if(!current()) return@task
    val synced=model.repository.observe(noteId).first()
    require(synced!=null && !synced.trashed && !synced.pending && synced.serverRev>0) {"This note has changes waiting to sync. Sync it before running a tool so it uses your latest text."}
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
   onError(when(error) {is ApiException -> pluginCommandErrorMessage(error.status,error.code);is IllegalArgumentException -> error.message ?: "Check the tool inputs.";is java.net.SocketTimeoutException -> "The tool took too long. Try again, or choose a faster model in Settings → AI.";else -> "Couldn't reach the server. Check your connection, then retry."})
  }
 } finally { onComplete() }
}

@Composable fun PluginPanels(model:SpuntoViewModel,note:Note,onNote:(String)->Unit) {
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
  var error by remember(note.id,id,note.serverRev,online) {mutableStateOf("")}
  var loading by remember(note.id,id) {mutableStateOf(false)}
  var retry by remember(note.id,id) {mutableIntStateOf(0)}
  LaunchedEffect(online,note.id,note.serverRev,note.privateNote,expanded,retry) {
   if(live && online && !note.privateNote && expanded) {
    val auth=model.auth.value ?: return@LaunchedEffect
    val epoch=model.pluginReplicaEpoch
    fun current()=model.auth.value==auth && model.pluginReplicaEpoch==epoch && model.container.manifest.enabled(id)
    loading=true;error=""
    try {
     val response=ApiClient(auth).guarded {current()}.request("/api/v1/plugins/$id/panel",query=mapOf("note_id" to note.id))
     if(current()) result=response
    } catch(cancelled:CancellationException) {throw cancelled}
    catch(failure:Exception) {if(current()) {
     error=if(failure is ApiException) pluginCommandErrorMessage(failure.status,failure.code) else "Couldn't load this panel. Check your connection and retry."
     if(failure is ApiException && failure.status==401) model.reauthenticate.value=true
    }} finally {loading=false}
   }
  }
  Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
   TextButton(onClick={expanded=!expanded;model.container.preferences.edit().putBoolean("panel-open:$id",expanded).apply()}) { Text(panel.string("title").ifBlank { plugin.string("name") }+if(expanded) " −" else " +") }
   if(expanded) {
    val content=if(live) result else JsonArray(components)
    when {
     loading -> LinearProgressIndicator(Modifier.fillMaxWidth())
     error.isNotBlank() -> {Text(error,color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall);TextButton(onClick={retry++}) {Text("Retry")}}
     content!=null && outputComponents(content).isNotEmpty() -> RenderOutput(model,buildJsonObject {put("plugin_id",id);put("payload",content)},onNote)
     else -> Text("Nothing to show yet.",style=MaterialTheme.typography.bodySmall)
    }
   }
  }
 }
}

internal fun proposalData(item:PluginOutputEntity)=JsonObject(payload(item.payload)+mapOf("id" to JsonPrimitive(item.id))+if(item.noteId!=null) mapOf("note_id" to JsonPrimitive(item.noteId)) else emptyMap())
internal fun labelChangeSummary(proposed:JsonObject):String {
 val ids=proposed["note_ids"] as? JsonArray
 return when {
  ids!=null -> "Add #${proposed.string("new")} to ${ids.size} selected unlabeled notes."
  proposed["new"] is JsonNull -> "Remove #${proposed.string("old")} from affected notes, including nested labels."
  else -> "Rename or merge #${proposed.string("old")} into #${proposed.string("new")}."
 }
}
internal fun entryMetaText(outputs:List<PluginOutputEntity>,noteId:String,plugins:List<JsonObject>):String {
 val active=plugins.filter { it.bool("enabled",true) && it.string("state") !in setOf("disabled","off","failed") }.associateBy { it.string("id") }
 return outputs.filter { it.noteId==noteId && it.kind=="annotation" }.map { payload(it.payload) }.filter { it.string("kind")=="meta" && it.string("plugin") in active }.sortedWith(compareBy<JsonObject> { (active[it.string("plugin")]?.get("position") as? JsonPrimitive)?.intOrNull ?: Int.MAX_VALUE }.thenBy { it.string("plugin") }).firstNotNullOfOrNull { data -> outputComponents(data).firstOrNull { it.string("type")=="text" }?.string("text")?.take(24)?.takeIf(String::isNotBlank) }.orEmpty()
}

@Composable fun ProposedPill(title:String,pluginName:String,changesWords:Boolean,onClick:()->Unit,onDismiss:()->Unit) {
 val accent=MaterialTheme.colorScheme.primary
 Surface(shape=MaterialTheme.shapes.large,color=MaterialTheme.colorScheme.surface,modifier=Modifier.drawWithContent {
  drawContent()
  drawRoundRect(accent,cornerRadius=CornerRadius(16.dp.toPx()),style=Stroke(1.dp.toPx(),pathEffect=PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(),3.dp.toPx()))))
 }.semantics { contentDescription="$title · $pluginName · proposed ${if(changesWords) "wording change" else "addition"}";customActions=listOf(CustomAccessibilityAction("Dismiss suggestion") { onDismiss();true }) }.clickable(onClick=onClick)) {
  Row(Modifier.padding(horizontal=12.dp,vertical=8.dp),horizontalArrangement=Arrangement.spacedBy(6.dp)) {
   if(changesWords) androidx.compose.foundation.Canvas(Modifier.size(16.dp)) {
    val stroke=1.5.dp.toPx()
    drawLine(accent,androidx.compose.ui.geometry.Offset(size.width*.18f,size.height*.84f),androidx.compose.ui.geometry.Offset(size.width*.72f,size.height*.30f),stroke)
    drawLine(accent,androidx.compose.ui.geometry.Offset(size.width*.58f,size.height*.19f),androidx.compose.ui.geometry.Offset(size.width*.84f,size.height*.45f),stroke)
   } else Text("+",color=accent)
   Text(title,style=MaterialTheme.typography.labelLarge)
  }
 }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable fun ProposalLine(proposals:List<JsonObject>,pluginNames:Map<String,String>,onSelect:(JsonObject)->Unit,onDismiss:(String)->Unit) {
 var expanded by remember { mutableStateOf(false) }
 @Composable fun chips() {
  (if(expanded) proposals else proposals.take(3)).forEach { data ->
   ProposedPill(data.string("title").ifEmpty { (data["payload"] as? JsonObject)?.string("path")?.takeIf(String::isNotBlank)?.let { "Add #$it" } ?: "Review change" },pluginNames[data.string("plugin")] ?: data.string("plugin"),data.string("action") in setOf("replace_body","merge_notes"),{onSelect(data)},{onDismiss(data.string("id"))})
  }
  if(proposals.size>3) TextButton(onClick={expanded=!expanded}) { Text(if(expanded) "Show fewer" else "+${proposals.size-3}") }
 }
 if(expanded) FlowRow(horizontalArrangement=Arrangement.spacedBy(6.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) { chips() }
 else Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(6.dp)) { chips() }
 TextButton(onClick={proposals.forEach { onDismiss(it.string("id")) }}) { Text("Dismiss all") }
}

@Composable fun Suggestions(model:SpuntoViewModel,noteId:String,onNote:(String)->Unit) {
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
 ProposalLine(proposals,plugins.associate { it.string("id") to it.string("name") },{data -> if(data.string("action")=="add_label") model.decide(data.string("id"),"accept") else selected=data.string("id") },{model.decide(it,"dismiss")})
 selected?.let { id -> outputs.firstOrNull { it.id==id && it.kind=="suggestion" }?.let { SuggestionDecisionDialog(model,proposalData(it),onNote) { selected=null } } }
}

private fun proposalKind(action:String)=when(action) { "add_label","label_change" -> "Labels";"merge_notes" -> "Duplicate notes";"replace_body" -> "Wording changes";"set_title" -> "Titles";"append_text" -> "Links and additions";else -> "Other suggestions" }
@Composable fun ReviewScreen(model:SpuntoViewModel,onNote:(String)->Unit) {
 val manifest=model.manifest.collectAsState().value
 val outputs by model.outputs.collectAsState()
 val online by model.online.collectAsState()
 var selected by remember { mutableStateOf<String?>(null) }
 val expanded=remember { mutableStateMapOf<String,Boolean>() }
 val proposals=outputs.filter { it.kind=="suggestion" && payload(it.payload).string("status")=="pending" && model.container.manifest.enabled(payload(it.payload).string("plugin")) }
 LazyColumn(contentPadding=PaddingValues(20.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
  if(proposals.isEmpty()) item { Text("All caught up.",style=MaterialTheme.typography.titleLarge) }
  proposals.groupBy { proposalKind(payload(it.payload).string("action")) }.forEach { (kind,group) ->
   item { Text(kind,style=MaterialTheme.typography.titleMedium) }
   items(if(expanded[kind]==true) group else group.take(3),key={it.id}) { item ->
    val data=proposalData(item)
    Surface(color=MaterialTheme.colorScheme.surfaceContainer,shape=MaterialTheme.shapes.medium) {
     Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
      Text(data.string("title").ifBlank { "Review change" },style=MaterialTheme.typography.titleSmall)
      val plugin=data.string("plugin")
      Text(model.container.manifest.plugins().firstOrNull { it.string("id")==plugin }?.string("name") ?: plugin,style=MaterialTheme.typography.bodySmall)
      item.noteId?.let { RoomReference(model,it,"",onNote) }
      if(data.string("reason").isNotBlank()) Text(data.string("reason"),style=MaterialTheme.typography.bodySmall)
      Row { TextButton(onClick={selected=item.id}) { Text("Review") };TextButton(enabled=data.string("action")!="label_change" || online,onClick={model.decide(item.id,"dismiss")}) { Text("Dismiss") } }
     }
    }
   }
   if(group.size>3) item { TextButton(onClick={expanded[kind]=expanded[kind]!=true}) { Text(if(expanded[kind]==true) "Show fewer" else "+${group.size-3} more") } }
  }
 }
 selected?.let { id -> outputs.firstOrNull { it.id==id && it.kind=="suggestion" }?.let { SuggestionDecisionDialog(model,proposalData(it),onNote) { selected=null } } }
}

@Composable private fun RoomReference(model:SpuntoViewModel,id:String,reason:String,onNote:(String)->Unit) {
 val note by remember(id) { model.repository.observe(id) }.collectAsState(null)
 if(note==null || note!!.trashed) Text("Note unavailable",style=MaterialTheme.typography.bodySmall)
 else Column { TextButton(onClick={onNote(id)}) { Text(note!!.title.ifBlank { note!!.body.lineSequence().firstOrNull().orEmpty().take(100).ifBlank { "Open note" } }) };if(reason.isNotBlank()) Text(reason,style=MaterialTheme.typography.bodySmall) }
}

/** One renderer for bare arrays, stored payloads and live command envelopes. */
@Composable fun RenderOutput(model:SpuntoViewModel,data:JsonElement,onNote:(String)->Unit,onResult:((JsonObject)->Unit)?=null,commandScope:CoroutineScope?=null,onProposalOpen:()->Unit={}) {
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
 val review:(String)->Unit={ id -> if(model.outputs.value.any { it.id==id && it.kind=="suggestion" }) { onProposalOpen();model.remote.value=buildJsonObject { put("suggestion_preview",id) } } else model.message.value="Sync to review this suggestion." }
 Column(verticalArrangement=Arrangement.spacedBy(12.dp)) {
  val components=outputComponents(data)
  if(components.isEmpty() && command.isNotBlank()) Text("This result has no preview in this app. Check Review for suggestions or open the web app.",style=MaterialTheme.typography.bodySmall)
  components.forEach { component ->
   Vocabulary(component,onNote,review,run,online=online && !running,
    onForm=if(command.isNotBlank()) { args -> run(command,JsonObject(initial+args)) } else null,formInitial=initial,formHidden=initial.keys.filter { it=="note_id" }.toSet(),noteReference={id,reason -> RoomReference(model,id,reason,onNote)},proposalPill={ pill ->
     val id=pill.string("suggestion_id");val proposal=outputs.firstOrNull { it.id==id && it.kind=="suggestion" }?.let(::proposalData)
     ProposedPill(pill.string("label"),model.container.manifest.plugins().firstOrNull { it.string("id")==plugin }?.string("name") ?: plugin,proposal?.string("action") in setOf("replace_body","merge_notes"),{review(id)},{model.decide(id,"dismiss")})
    })
  }
  if(error.isNotBlank()) Text(error,color=MaterialTheme.colorScheme.error)
  if(running) {LinearProgressIndicator(Modifier.fillMaxWidth());Text("Working… This can take a moment with AI.",style=MaterialTheme.typography.bodySmall)}
 }
}

@Composable fun CommandResultSheet(model:SpuntoViewModel,result:JsonObject,onNote:(String)->Unit,onResult:(JsonObject)->Unit,onClose:()->Unit) {
 val clipboard=LocalClipboardManager.current
 val catalog by model.catalog.collectAsState()
 val plugin=catalog.objects("plugins").firstOrNull { it.string("id")==result.string("plugin_id") }
 val sheetScope=rememberCoroutineScope()
 var active by remember { mutableStateOf(true) }
 val close={active=false;sheetScope.cancel();onClose()}
 ModalBottomSheet(onDismissRequest=close,scrimColor=MaterialTheme.colorScheme.scrim) {
  Column(Modifier.padding(20.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)) {
   val command=(plugin?.get("contributes") as? JsonObject)?.objects("commands")?.firstOrNull {it.string("id")==result.string("command_id")}
   Text(command?.string("title")?.takeIf(String::isNotBlank) ?: plugin?.string("name")?.takeIf(String::isNotBlank) ?: "Tool result",style=MaterialTheme.typography.titleLarge)
   RenderOutput(model,result,{id -> close();onNote(id)},{if(active) onResult(it)},commandScope=sheetScope,onProposalOpen=close)
   (plugin?.get("ai_destinations") as? JsonObject)?.forEach { (_,raw) -> (raw as? JsonObject)?.let { destination -> Text("${destination.string("model")} at ${destination.string("host")} · ${if(destination.bool("leaves_network")) "outside your network" else "on your network"}",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant) } }
   val copied=resultCopyText(result)
   Row(horizontalArrangement=Arrangement.spacedBy(12.dp)) {
    if(copied.isNotBlank()) TextButton(onClick={clipboard.setText(AnnotatedString(copied))}) {Text(if(outputComponents(result).any {it.string("type")=="diff"}) "Copy changes" else "Copy")}
    TextButton(onClick=close) { Text("Close") }
   }
   Spacer(Modifier.height(24.dp))
  }
 }
}

@Composable fun SuggestionPreview(model:SpuntoViewModel,data:JsonObject) {
 val proposed=data["payload"] as? JsonObject ?: return
 val action=data.string("action")
 val id=if(action=="merge_notes") proposed.string("target_id") else data.string("note_id")
 val target by remember(id) { model.repository.observe(id) }.collectAsState(null)
 when(action) {
  "add_label" -> Text("Add #${proposed.string("path")}")
  "append_text" -> { Text("Append this text:");Markdown(proposed.string("text"),machine=true) }
  "replace_body" -> { Text("Based on version ${data["note_version"] ?: "unknown"}",style=MaterialTheme.typography.bodySmall);if(target==null) Text("Note unavailable") else TextChangePreview(target!!.body,proposed.string("body")) }
  "set_title" -> Text("Title: ${proposed.string("title")}")
  "label_change" -> { Text(labelChangeSummary(proposed));Text("Review the affected notes in the web app before applying.",style=MaterialTheme.typography.bodySmall) }
  "merge_notes" -> { val source by remember(proposed.string("source_id")) { model.repository.observe(proposed.string("source_id")) }.collectAsState(null);Text("Compare",style=MaterialTheme.typography.titleLarge);Text("The source will be appended to the destination and moved to Trash.");Text("Destination",style=MaterialTheme.typography.titleSmall);Text(target?.body ?: "Note unavailable",fontFamily=FontFamily.Monospace);Text("Source",style=MaterialTheme.typography.titleSmall);Text(source?.body ?: "Note unavailable",fontFamily=FontFamily.Monospace) }
 }
 if(data.string("reason").isNotBlank()) Text(data.string("reason"),style=MaterialTheme.typography.bodySmall)
}

@Composable fun SuggestionDecisionDialog(model:SpuntoViewModel,data:JsonObject,onNote:(String)->Unit,onClose:()->Unit) {
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
 AlertDialog(onDismissRequest=onClose,title={Text(if(action=="merge_notes") "Compare notes" else "Review change")},text={Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)) {
  SuggestionPreview(model,data)
  if(stale) Text("This note changed. Wait for a fresh suggestion.",style=MaterialTheme.typography.bodySmall)
  if(data.string("status")!="pending") Text("This suggestion is ${data.string("status").ifEmpty { "unavailable" }}.",style=MaterialTheme.typography.bodySmall)
 }},confirmButton={
  if(action=="label_change") TextButton(enabled=online,onClick={model.auth.value?.let { runCatching { uri.openUri(it.serverUrl.trimEnd('/')+"/review") } };onClose()}) { Text(if(online) "Open web Review" else "Needs a connection") }
  else TextButton(enabled=canApply,onClick={if(action=="open") proposed.string("note_id").takeIf(String::isNotBlank)?.let(onNote);model.decide(data.string("id"),"accept");onClose()}) { Text(if(action=="replace_body") "Apply changes" else "Apply") }
 },dismissButton={TextButton(enabled=action!="label_change" || online,onClick={model.decide(data.string("id"),"dismiss");onClose()}) { Text(if(action=="replace_body") "Keep original" else "Dismiss") }})
}

@Composable fun NoteCommands(model:SpuntoViewModel,noteId:String,onResult:(JsonObject)->Unit) {
 val manifest=model.manifest.collectAsState().value
 val online by model.online.collectAsState()
 var expanded by remember(noteId) { mutableStateOf(false) }
 var running by remember(noteId) { mutableStateOf(false) }
 var query by remember(noteId) {mutableStateOf("")}
 var error by remember(noteId) {mutableStateOf("")}
 var attempt by remember(noteId) {mutableStateOf<Job?>(null)}
 val scope=rememberCoroutineScope()
 val commands=remember(manifest) {noteTools(model.container.manifest.plugins())}
 if(commands.isEmpty()) return
 TextButton(onClick={expanded=true;query="";error=""}) {Text("Tools")}
 if(expanded) ModalBottomSheet(onDismissRequest={attempt?.cancel();expanded=false}) {
  Column(Modifier.fillMaxWidth().padding(horizontal=20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
   Text("Note tools",style=MaterialTheme.typography.titleLarge)
   if(!online) Text("Connect to the server to use tools.",style=MaterialTheme.typography.bodySmall)
   if(commands.size>5) OutlinedTextField(value=query,onValueChange={query=it},label={Text("Find a tool")},singleLine=true,modifier=Modifier.fillMaxWidth())
   if(error.isNotBlank()) Text(error,color=MaterialTheme.colorScheme.error)
   if(running) {LinearProgressIndicator(Modifier.fillMaxWidth());Text("Working… This can take a moment with AI.",style=MaterialTheme.typography.bodySmall)}
   val visible=commands.filter {it.title.contains(query,true) || it.pluginName.contains(query,true)}
   if(visible.isEmpty()) Text("No tools match your search.")
   LazyColumn(Modifier.weight(1f,false),contentPadding=PaddingValues(bottom=24.dp)) {
    items(visible,key={it.pluginId+"/"+it.id}) {tool ->
     ListItem(headlineContent={Text(tool.title)},supportingContent=if(commands.count {it.title==tool.title}>1) {{Text(tool.pluginName)}} else null,modifier=Modifier.clickable(enabled=online && !running) {
      running=true;error=""
      attempt=runCommand(model,tool.pluginId,tool.id,buildJsonObject {put("note_id",noteId)},{expanded=false;onResult(it)},{running=false},scope,onError={error=it})
     })
    }
   }
   TextButton(onClick={attempt?.cancel();expanded=false}) {Text(if(running) "Cancel" else "Close")}
   Spacer(Modifier.height(12.dp))
  }
 }
}
