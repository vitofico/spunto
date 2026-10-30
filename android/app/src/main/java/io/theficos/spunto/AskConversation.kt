package io.theficos.spunto

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import io.theficos.spunto.core.design.SpuntoSpacing
import io.theficos.spunto.core.plugins.*
import kotlinx.coroutines.Job
import kotlinx.serialization.json.*

internal fun askCommand(data:JsonElement?,id:String):JsonObject?=outputComponents(data).firstOrNull {it.string("type")=="command" && it.string("id")==id}?.get("args") as? JsonObject
internal fun askContinuation(data:JsonElement?):JsonObject?=askCommand(data,"ask")?.takeIf {it.string("conversation_id").isNotBlank() && ((it["after_turn"] as? JsonPrimitive)?.intOrNull ?: -1)>=0}
internal fun askSources(data:JsonElement?):List<String> = outputComponents(data).filter {it.string("type")=="notes"}.flatMap {component -> ((component["ids"] ?: component["items"]) as? JsonArray).orEmpty().mapNotNull {source -> if(source is JsonPrimitive) source.contentOrNull else (source as? JsonObject)?.string("id")} }.filter {it.isNotBlank()}
private data class AskTurn(val id:Int,val question:String,val result:JsonObject)

/** Ephemeral state stays in the composition: leaving, switching accounts, or clearing discards it. */
@Composable internal fun AskConversation(model:SpuntoViewModel,onNote:(String)->Unit,initial:JsonObject?=null,initialQuestion:String="") {
 val resources=LocalContext.current.resources
 val auth by model.auth.collectAsState()
 val epoch by model.pluginReplicaEpochState.collectAsState()
 val manifest=model.manifest.collectAsState().value
 val online by model.online.collectAsState()
 val enabled=model.container.manifest.enabled("ask")
 val initialIdentity=remember {auth to epoch}
 key(auth,epoch) {
  val adopted=initial.takeIf {initialIdentity==(auth to epoch)}
  var turns by remember {mutableStateOf(adopted?.let {listOf(AskTurn(0,initialQuestion,it))}.orEmpty())}
  var continuation by remember {mutableStateOf(askContinuation(adopted))}
  var question by remember {mutableStateOf("")}
  var pending by remember {mutableStateOf<String?>(null)}
  var failure by remember {mutableStateOf<String?>(null)}
  var retry by remember {mutableStateOf("")}
  var revision by remember {mutableIntStateOf(0)}
  var active by remember {mutableStateOf(true)}
  var request by remember {mutableStateOf<Job?>(null)}
  val config=auth
  fun clearHandle(args:JsonObject?) {if(args!=null && model.auth.value==config && model.pluginReplicaEpoch==epoch) model.request("/api/v1/plugins/ask/commands/clear-conversation","POST",buildJsonObject {put("conversation_id",args.string("conversation_id"))})}
  fun discard() {revision++;request?.cancel();clearHandle(continuation);continuation=null;turns=emptyList();question="";pending=null;failure=null;retry=""}
  DisposableEffect(Unit) {onDispose {active=false;revision++;request?.cancel();clearHandle(continuation)}}
  LaunchedEffect(enabled) {if(!enabled) discard()}
  fun submit(submitted:String) {
   if(pending!=null || !enabled || !online || config==null) return
   val attempt=++revision
   pending=submitted;failure=null;retry=submitted;question=""
   fun current()=active && revision==attempt && model.auth.value==config && model.pluginReplicaEpoch==epoch && model.container.manifest.enabled("ask")
   request=model.ask(submitted,continuation,current=::current,onHandle={continuation=it},onError={if(current()) {failure=it;question=submitted;pending=null}},onComplete={if(current()) pending=null}) {raw ->
    val result=raw as? JsonObject
    if(current() && result!=null) {turns=(turns+AskTurn(attempt,submitted,result)).takeLast(20);continuation=askContinuation(result);pending=null;retry=""}
    else clearHandle(askContinuation(raw))
   }
  }
  LazyColumn(Modifier.fillMaxSize().imePadding(),contentPadding=PaddingValues(SpuntoSpacing.s20),verticalArrangement=Arrangement.spacedBy(SpuntoSpacing.s16)) {
   item {Text(resources.getString(R.string.core_ask_temporary),style=MaterialTheme.typography.bodySmall);TextButton(onClick={discard()}) {Text(resources.getString(R.string.core_new_conversation))}}
   items(turns,key={it.id}) {turn ->
    Column(verticalArrangement=Arrangement.spacedBy(SpuntoSpacing.s12)) {
     if(turn.question.isNotBlank()) Text(turn.question,style=MaterialTheme.typography.titleMedium)
     RenderOutput(model,buildJsonObject {put("items",JsonArray(outputComponents(turn.result).filter {it.string("type") in setOf("text","markdown")}))},onNote)
     val sources=askSources(turn.result)
     if(sources.isNotEmpty()) {
      Text(resources.getString(R.string.core_ask_sources),style=MaterialTheme.typography.titleSmall)
      sources.forEachIndexed {index,id -> AskSource(model,id,index+1,onNote)}
     }
     AskTurnActions(model,turn.result,onNote,current={active && model.auth.value==config && model.pluginReplicaEpoch==epoch && model.container.manifest.enabled("ask")})
     HorizontalDivider()
    }
   }
   pending?.let {submitted -> item {Text(submitted,style=MaterialTheme.typography.titleMedium);LinearProgressIndicator(Modifier.fillMaxWidth());Text(resources.getString(R.string.core_reading_notes))}}
   failure?.let {error -> item {Text(error,color=MaterialTheme.colorScheme.error);Text(resources.getString(R.string.core_ask_retry_hint));TextButton(onClick={submit(retry)}) {Text(resources.getString(R.string.core_ask_retry))}}}
   item {AskQuestionInput(question,enabled,online,pending!=null,{question=it},{submit(question.trim())},followup=turns.isNotEmpty())}
  }
 }
}

@Composable private fun AskSource(model:SpuntoViewModel,id:String,number:Int?,onNote:(String)->Unit) {
 val resources=LocalContext.current.resources
 val note by remember(id) {model.repository.observe(id)}.collectAsState(null)
 TextButton(onClick={onNote(id)}) {Text((number?.let {"[$it] "} ?: "")+(note?.title?.takeIf {it.isNotBlank()} ?: resources.getString(R.string.settings_open_note)))}
}

@Composable private fun AskTurnActions(model:SpuntoViewModel,result:JsonObject,onNote:(String)->Unit,current:()->Boolean) {
 val resources=LocalContext.current.resources
 val clipboard=LocalClipboardManager.current
 val scope=rememberCoroutineScope()
 var saving by remember {mutableStateOf(false)}
 var saved by remember {mutableStateOf<JsonObject?>(null)}
 var failure by remember {mutableStateOf("")}
 var copied by remember {mutableStateOf(false)}
 val online by model.online.collectAsState()
 val command=askCommand(result,"save-answer")
 Column {
  Row(horizontalArrangement=Arrangement.spacedBy(SpuntoSpacing.s12)) {
   TextButton(onClick={if(current()) {clipboard.setText(AnnotatedString(resultCopyText(resources,result)));copied=true}}) {Text(resources.getString(if(copied) R.string.core_ask_copied else R.string.settings_copy))}
   if(command!=null) TextButton(enabled=online && !saving && saved==null,onClick={
    if(current()) {saving=true;failure=""
    runCommand(model,"ask","save-answer",command,{if(current()) {if(askSources(it).isNotEmpty()) saved=it else failure=resultCopyText(resources,it).ifBlank {resources.getString(R.string.core_ask_failure)}}},{if(current()) saving=false},scope,onError={if(current()) failure=it})}
   }) {Text(resources.getString(if(saved!=null) R.string.core_ask_saved else if(saving) R.string.settings_working_this_can_take_a_moment_with else R.string.core_ask_save))}
  }
  askSources(saved).forEach {AskSource(model,it,null,onNote)}
  if(failure.isNotBlank()) Text(failure,color=MaterialTheme.colorScheme.error)
 }
}
