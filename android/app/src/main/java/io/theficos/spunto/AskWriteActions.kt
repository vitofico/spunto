package io.theficos.spunto

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import io.theficos.spunto.core.design.SpuntoSpacing
import io.theficos.spunto.core.plugins.*
import kotlinx.serialization.json.*
import kotlinx.coroutines.CoroutineScope

internal data class AskWriteAction(val title:String,val args:JsonObject) {
 val id get()=args.string("action_id")
 val stage get()=args.string("stage")
}
internal fun askWriteAction(data:JsonElement?):AskWriteAction? {
 val components=outputComponents(data)
 return components.firstNotNullOfOrNull {item ->
  val args=item["args"] as? JsonObject
  if(item.string("type")!="command" || item.string("id")!="save-answer" || args==null || args.keys!=setOf("action_id","stage") || (args["action_id"] as? JsonPrimitive)?.isString!=true || args.string("action_id").isBlank() || args.string("stage") !in setOf("preview","undo","done")) null
  else if(args.string("stage")=="preview" && components.none {it.string("type")=="markdown" && it.string("text").ifEmpty {it.string("markdown")}.isNotBlank()}) null
  else AskWriteAction(item.string("title"),args)
 }
}
internal fun askWriteAcknowledgement(requested:AskWriteAction,data:JsonElement?):AskWriteAction?=askWriteAction(data)?.takeIf {it.id==requested.id && (it.stage=="done" || requested.stage=="preview" && it.stage=="undo")}
internal fun askAnswerSave(data:JsonElement?):JsonObject?=askCommand(data,"save-answer")?.takeIf {it.keys==setOf("answer_id") && it.string("answer_id").isNotBlank()}

/** Owned by the temporary conversation, so lazy-item disposal cannot reset consent or Undo. */
internal class AskWriteState(initial:AskWriteAction) {
 var action by mutableStateOf(initial)
 var result by mutableStateOf<JsonObject?>(null)
 var pending by mutableStateOf(false)
 var cancelled by mutableStateOf(false)
 var failure by mutableStateOf("")
 fun accept(requested:AskWriteAction,response:JsonObject):Boolean {
  val accepted=askWriteAcknowledgement(requested,response) ?: return false
  action=accepted;result=response;cancelled=false;failure=""
  return true
 }
}

@Composable internal fun AskWriteActionControls(action:AskWriteAction,pending:Boolean,cancelled:Boolean,onConfirm:()->Unit,onCancel:()->Unit,enabled:Boolean=true) {
 val resources=LocalContext.current.resources
 when {
  cancelled -> Text(resources.getString(R.string.core_ask_action_cancelled))
  action.stage=="done" -> Text(resources.getString(R.string.core_ask_undo_complete))
  else -> Column(verticalArrangement=Arrangement.spacedBy(SpuntoSpacing.s8)) {
   if(action.stage=="preview") Text(resources.getString(R.string.core_ask_preview_hint),style=MaterialTheme.typography.bodySmall)
   Row(horizontalArrangement=Arrangement.spacedBy(SpuntoSpacing.s12)) {
    Button(enabled=enabled && !pending,onClick=onConfirm) {Text(resources.getString(if(pending) R.string.core_ask_action_pending else if(action.stage=="undo") R.string.core_ask_undo else if(action.title=="Create note") R.string.core_ask_create_note else R.string.core_ask_apply_change))}
    if(action.stage=="preview") TextButton(enabled=!pending,onClick=onCancel) {Text(resources.getString(R.string.settings_cancel))}
   }
  }
 }
}

/** The server owns the proposed body and all mutation/version information behind the receipt. */
@Composable internal fun AskWriteActions(model:SpuntoViewModel,state:AskWriteState,scope:CoroutineScope,onNote:(String)->Unit,current:()->Boolean) {
 val resources=LocalContext.current.resources
 val online by model.online.collectAsState()
 Column(verticalArrangement=Arrangement.spacedBy(SpuntoSpacing.s8)) {
  state.result?.let {response -> RenderOutput(model,buildJsonObject {put("items",JsonArray(outputComponents(response).filter {it.string("type") in setOf("text","markdown","notes")}))},onNote)}
  AskWriteActionControls(state.action,state.pending,state.cancelled,onConfirm={
   if(current() && online && !state.pending && !state.cancelled && state.action.stage!="done") {
    state.pending=true;state.failure=""
    val requested=state.action
    runCommand(model,"ask","save-answer",requested.args,{response ->
     if(current() && !state.accept(requested,response)) state.failure=resultCopyText(resources,response).ifBlank {resources.getString(R.string.core_ask_action_failed)}
    },{if(current()) state.pending=false},scope,onError={if(current()) state.failure=it})
   }
  },onCancel={if(current() && !state.pending) state.cancelled=true},enabled=online)
  if(state.failure.isNotBlank()) Text(state.failure,color=MaterialTheme.colorScheme.error)
 }
}
