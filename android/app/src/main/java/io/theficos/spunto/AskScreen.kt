@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package io.theficos.spunto

import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import kotlinx.coroutines.Job
import kotlinx.serialization.json.*
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.semantics.error
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.unit.dp
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import io.theficos.spunto.core.design.SpuntoSpacing

/** A direct destination for a temporary conversation grounded in the notes. */
@Composable fun AskScreen(model:SpuntoViewModel,onNote:(String)->Unit,collectionId:String?=null) {
 val manifest=model.manifest.collectAsState().value
 when {
  collectionId!=null && !supportsConversationalAsk(manifest) -> Text(LocalContext.current.getString(R.string.core_ask_collection_unavailable),Modifier.padding(SpuntoSpacing.s20))
  supportsAskConversation(manifest) -> AskConversation(model,onNote,fullPage=true,collectionId=collectionId)
  else -> LegacyAskScreen(model,onNote)
 }
}

/** A fresh question and the normal result sheet for servers without conversation commands. */
@Composable private fun LegacyAskScreen(model:SpuntoViewModel,onNote:(String)->Unit) {
 val context=LocalContext.current
 val auth by model.auth.collectAsState()
 val epoch by model.pluginReplicaEpochState.collectAsState()
 val manifest=model.manifest.collectAsState().value
 val online by model.online.collectAsState()
 val enabled=libraryCommandAvailable(manifest,"ask","ask")
 key(auth,epoch) {
  var question by remember {mutableStateOf("")}
  var result by remember {mutableStateOf<JsonObject?>(null)}
  var asking by remember {mutableStateOf(false)}
  var failure by remember {mutableStateOf("")}
  var request by remember {mutableStateOf<Job?>(null)}
  var active by remember {mutableStateOf(true)}
  var revision by remember {mutableIntStateOf(0)}
  fun discard() {revision++;request?.cancel();question="";result=null;asking=false;failure=""}
  DisposableEffect(Unit) {onDispose {active=false;revision++;request?.cancel()}}
  LaunchedEffect(enabled) {if(!enabled) discard()}
  Column(Modifier.fillMaxSize().imePadding().padding(SpuntoSpacing.s20),verticalArrangement=Arrangement.spacedBy(SpuntoSpacing.s16)) {
   AskQuestionInput(question,enabled,online,asking,{question=it},{
    val config=model.auth.value
    if(config!=null && online && enabled) {
     val submitted=question.trim();val attempt=++revision;asking=true;failure=""
     fun current()=active && revision==attempt && model.auth.value==config && model.pluginReplicaEpoch==epoch && model.container.manifest.enabled("ask")
     request=model.ask(submitted,current=::current,onError={if(current()) failure=it},onComplete={if(current()) asking=false}) {output ->
      if(current()) result=commandEnvelope("ask","ask",buildJsonObject {put("question",submitted)},output)
     }
    }
   })
   if(asking) LinearProgressIndicator(Modifier.fillMaxWidth())
   if(failure.isNotBlank()) Text(failure,color=MaterialTheme.colorScheme.error)
  }
  result?.let {output -> CommandResultSheet(model,output,onNote,onResult={result=it},onClose={result=null})}
 }
}

@Composable internal fun AskQuestionInput(question:String,enabled:Boolean,online:Boolean,asking:Boolean,onQuestion:(String)->Unit,onAsk:()->Unit,followup:Boolean=false,noteActions:Boolean=false,compact:Boolean=false,limit:Int=500,canSend:Boolean=true,focus:FocusRequester=remember {FocusRequester()}) {
 val context=LocalContext.current
 val questionLength=question.codePointCount(0,question.length)
 val keyboard=LocalSoftwareKeyboardController.current
 Column(verticalArrangement=Arrangement.spacedBy(SpuntoSpacing.s12)) {
  if(!compact) Text(context.getString(if(noteActions && followup) R.string.core_ask_continue else if(noteActions) R.string.core_ask_notes else if(followup) R.string.core_ask_followup else R.string.core_find_answer),style=MaterialTheme.typography.headlineSmall)
  if(!compact) Text(context.getString(if(noteActions) R.string.core_ask_message_hint else R.string.core_ask_hint),style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
  when {
   !enabled -> Text(context.getString(R.string.core_ask_off),style=MaterialTheme.typography.bodyMedium)
   !online -> Text(context.getString(R.string.core_ask_connect),style=MaterialTheme.typography.bodyMedium)
  }
  if(!compact && noteActions && !followup && question.isBlank()) {
   Text(context.getString(R.string.core_ask_starters),style=MaterialTheme.typography.labelLarge)
   FlowRow(horizontalArrangement=Arrangement.spacedBy(SpuntoSpacing.s8)) {
    listOf(R.string.core_ask_start_question to R.string.core_question_example,R.string.core_ask_start_create to R.string.core_ask_prompt_create,R.string.core_ask_start_edit to R.string.core_ask_prompt_edit).forEach {(label,prompt) ->
     SuggestionChip(enabled=enabled && !asking,onClick={if(question.isBlank()) {onQuestion(context.getString(prompt));focus.requestFocus();keyboard?.show()}},label={Text(context.getString(label))})
    }
   }
  }
  val fieldDescription=context.getString(if(noteActions) R.string.core_ask_message_notes else R.string.core_question_notes)
  val placeholder=context.getString(if(noteActions && followup) R.string.core_ask_followup_placeholder else if(noteActions) R.string.core_ask_message_placeholder else R.string.core_question_example)
  val sendEnabled=enabled && online && !asking && canSend && question.isNotBlank() && questionLength<=limit
  val sendLabel=context.getString(when {
   asking -> if(noteActions) R.string.core_ask_action_pending else R.string.core_reading_notes
   noteActions || compact -> R.string.core_ask_send_message
   followup -> R.string.core_ask_send
   else -> R.string.core_ask_notes
  })
  if(compact) {
   val colors=MaterialTheme.colorScheme
   var focused by remember {mutableStateOf(false)}
   val counter=context.getString(if(limit==500) R.string.core_question_characters else R.string.core_ask_message_characters,questionLength)
   Surface(shape=RoundedCornerShape(24.dp),color=colors.surfaceContainerLow,
    border=BorderStroke(if(focused) 2.dp else 1.dp,when {questionLength>limit -> colors.error;focused -> colors.primary;else -> colors.outlineVariant})) {
    Row(Modifier.fillMaxWidth().padding(start=16.dp,end=6.dp,top=4.dp,bottom=4.dp),verticalAlignment=Alignment.Bottom) {
     BasicTextField(value=question,onValueChange=onQuestion,enabled=enabled && !asking,maxLines=4,
      textStyle=MaterialTheme.typography.bodyLarge.copy(color=colors.onSurface),
      cursorBrush=SolidColor(colors.primary),
      modifier=Modifier.weight(1f).align(Alignment.CenterVertically).focusRequester(focus)
       .onFocusChanged {focused=it.isFocused}.semantics {contentDescription=fieldDescription;if(questionLength>limit) error(counter)}.padding(vertical=8.dp),
      decorationBox={inner ->
       Box {
        if(question.isEmpty()) Text(placeholder,style=MaterialTheme.typography.bodyLarge,color=colors.onSurfaceVariant,maxLines=2)
        inner()
       }
      })
     Button(onClick=onAsk,enabled=sendEnabled,contentPadding=PaddingValues(horizontal=16.dp)) {Text(context.getString(R.string.core_ask_send_message))}
    }
   }
   if(questionLength>limit-50) Text(counter,
    style=MaterialTheme.typography.labelSmall,color=if(questionLength>limit) colors.error else colors.onSurfaceVariant)
  } else {
   OutlinedTextField(question,onQuestion,Modifier.fillMaxWidth().focusRequester(focus).semantics {contentDescription=fieldDescription},
    label={Text(context.getString(if(noteActions) R.string.core_ask_message else R.string.core_your_question))},placeholder={Text(placeholder)},
    minLines=3,maxLines=6,enabled=enabled && !asking,isError=questionLength>limit,
    supportingText={if(questionLength>limit-50) Text(context.getString(if(limit==500) R.string.core_question_characters else R.string.core_ask_message_characters,questionLength))})
   Button(onClick=onAsk,enabled=sendEnabled,modifier=Modifier.fillMaxWidth()) {Text(sendLabel)}
  }
 }
}
