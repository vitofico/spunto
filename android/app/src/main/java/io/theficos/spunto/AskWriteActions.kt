@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package io.theficos.spunto

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
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
 val created=initial.title=="Create note"
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

/** The complete preview stays intact; only the exact duplicate server hint is omitted. */
internal fun askWritePreview(data:JsonElement?):JsonObject=buildJsonObject {
 put("items",JsonArray(outputComponents(data).filter {item ->
  item.string("type") in setOf("text","markdown") && !(item.string("type")=="text" && item.string("text")=="Preview only. Nothing changes until you confirm.")
 }))
}

@Composable internal fun AskWritePreviewContent(data:JsonElement?,onNote:(String)->Unit={}) {
 Column(verticalArrangement=Arrangement.spacedBy(SpuntoSpacing.s12)) {outputComponents(askWritePreview(data)).forEach {Vocabulary(it,onNote)}}
}

@Composable internal fun AskWriteActionControls(action:AskWriteAction,pending:Boolean,cancelled:Boolean,onConfirm:()->Unit,onCancel:()->Unit,enabled:Boolean=true,created:Boolean=action.title=="Create note") {
 val resources=LocalContext.current.resources
 Column(Modifier.semantics {liveRegion=LiveRegionMode.Polite},verticalArrangement=Arrangement.spacedBy(SpuntoSpacing.s8)) {
  when {
   cancelled -> Text(resources.getString(R.string.core_ask_action_cancelled))
   action.stage=="done" -> Text(resources.getString(if(created) R.string.core_ask_create_undone else R.string.core_ask_edit_undone))
   else -> {
    if(action.stage=="preview") {
     Text(resources.getString(R.string.core_ask_preview_hint),style=MaterialTheme.typography.bodySmall)
     Text(resources.getString(R.string.core_ask_refine_hint),style=MaterialTheme.typography.bodySmall)
    }
    else {
     Text(resources.getString(if(created) R.string.core_ask_note_created else R.string.core_ask_change_applied),style=MaterialTheme.typography.titleSmall)
     Text(resources.getString(if(created) R.string.core_ask_undo_create_hint else R.string.core_ask_undo_edit_hint),style=MaterialTheme.typography.bodySmall)
    }
    FlowRow(horizontalArrangement=Arrangement.spacedBy(SpuntoSpacing.s12)) {
     if(action.stage=="preview") Button(enabled=enabled && !pending,onClick=onConfirm) {Text(resources.getString(if(pending) R.string.core_ask_action_pending else if(created) R.string.core_ask_create_note else R.string.core_ask_apply_change))}
     else OutlinedButton(enabled=enabled && !pending,onClick=onConfirm) {Text(resources.getString(if(pending) R.string.core_ask_action_pending else R.string.core_ask_undo))}
     if(action.stage=="preview") TextButton(enabled=!pending,onClick=onCancel) {Text(resources.getString(R.string.settings_cancel))}
    }
   }
  }
 }
}

/** The server owns the proposed body and all mutation/version information behind the receipt. */
@Composable internal fun AskWriteActions(model:SpuntoViewModel,state:AskWriteState,preview:JsonObject,scope:CoroutineScope,onNote:(String)->Unit,current:()->Boolean) {
 val resources=LocalContext.current.resources
 val online by model.online.collectAsState()
 var expanded by remember(state) {mutableStateOf(false)}
 val showPreview=state.action.stage=="preview" && !state.cancelled || expanded
 Card(Modifier.fillMaxWidth(),colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.surfaceContainerLow)) {
 Column(Modifier.padding(SpuntoSpacing.s16),verticalArrangement=Arrangement.spacedBy(SpuntoSpacing.s12)) {
  Text(resources.getString(if(state.created) R.string.core_ask_new_note else R.string.core_ask_note_changes),style=MaterialTheme.typography.titleMedium)
  if(showPreview) {
  AskWritePreviewContent(preview,onNote)
  val related=outputComponents(preview).filter {it.string("type")=="notes"}
  if(related.isNotEmpty()) {
   Text(resources.getString(R.string.core_ask_related_notes),style=MaterialTheme.typography.labelLarge)
   RenderOutput(model,buildJsonObject {put("items",JsonArray(related))},onNote)
  }
  HorizontalDivider()
  }
  AskWriteActionControls(state.action,state.pending,state.cancelled,onConfirm={
   if(current() && online && !state.pending && !state.cancelled && state.action.stage!="done") {
    state.pending=true;state.failure=""
    val requested=state.action
    runCommand(model,"ask","save-answer",requested.args,{response ->
     if(current() && !state.accept(requested,response)) state.failure=resultCopyText(resources,response).ifBlank {resources.getString(R.string.core_ask_action_failed)}
    },{if(current()) state.pending=false},scope,onError={if(current()) state.failure=it})
   }
  },onCancel={if(current() && !state.pending) state.cancelled=true},enabled=online,created=state.created)
  if(state.action.stage!="preview") state.result?.let {response ->
   askSources(response).firstOrNull()?.let {id -> TextButton(onClick={if(current()) onNote(id)}) {Text(resources.getString(R.string.settings_open_note))}}
  }
  if(state.action.stage!="preview" || state.cancelled) TextButton(onClick={expanded=!expanded}) {Text(resources.getString(if(expanded) R.string.core_ask_hide_preview else R.string.core_ask_view_preview))}
  if(state.failure.isNotBlank()) Text(state.failure,Modifier.semantics {liveRegion=LiveRegionMode.Polite},color=MaterialTheme.colorScheme.error)
 }
 }
}
