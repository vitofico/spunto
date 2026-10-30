package io.theficos.spunto

import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import kotlinx.coroutines.Job
import kotlinx.serialization.json.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import io.theficos.spunto.core.design.SpuntoSpacing

/** A direct destination for a temporary conversation grounded in the notes. */
@Composable fun AskScreen(model:SpuntoViewModel,onNote:(String)->Unit) {
 val manifest=model.manifest.collectAsState().value
 if(supportsAskConversation(manifest)) AskConversation(model,onNote) else LegacyAskScreen(model,onNote)
}

/** A fresh question and the normal result sheet for servers without conversation commands. */
@Composable private fun LegacyAskScreen(model:SpuntoViewModel,onNote:(String)->Unit) {
 val context=LocalContext.current
 val auth by model.auth.collectAsState()
 val epoch by model.pluginReplicaEpochState.collectAsState()
 val manifest=model.manifest.collectAsState().value
 val online by model.online.collectAsState()
 val enabled=model.container.manifest.enabled("ask")
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

@Composable internal fun AskQuestionInput(question:String,enabled:Boolean,online:Boolean,asking:Boolean,onQuestion:(String)->Unit,onAsk:()->Unit,followup:Boolean=false) {
 val context=LocalContext.current
 val questionLength=question.codePointCount(0,question.length)
 Column(verticalArrangement=Arrangement.spacedBy(SpuntoSpacing.s12)) {
  Text(context.getString(if(followup) R.string.core_ask_followup else R.string.core_find_answer),style=MaterialTheme.typography.headlineSmall)
  Text(context.getString(R.string.core_ask_hint),style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
  when {
   !enabled -> Text(context.getString(R.string.core_ask_off),style=MaterialTheme.typography.bodyMedium)
   !online -> Text(context.getString(R.string.core_ask_connect),style=MaterialTheme.typography.bodyMedium)
  }
  OutlinedTextField(question,onQuestion,Modifier.fillMaxWidth().semantics {contentDescription=context.getString(R.string.core_question_notes)},label={Text(context.getString(R.string.core_your_question))},placeholder={Text(context.getString(R.string.core_question_example))},minLines=3,maxLines=6,enabled=enabled && !asking,isError=questionLength>500,supportingText={if(questionLength>450) Text(context.getString(R.string.core_question_characters,questionLength))})
  Button(onClick=onAsk,enabled=enabled && online && !asking && question.isNotBlank() && questionLength<=500,modifier=Modifier.fillMaxWidth()) {Text(if(asking) context.getString(R.string.core_reading_notes) else context.getString(if(followup) R.string.core_ask_send else R.string.core_ask_notes))}
 }
}
