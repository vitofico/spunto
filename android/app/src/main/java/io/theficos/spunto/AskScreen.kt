package io.theficos.spunto

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Job
import kotlinx.serialization.json.*
import io.theficos.spunto.core.design.SpuntoSpacing

/** A direct destination for Ask; each question is grounded in a fresh search of the notes. */
@Composable fun AskScreen(model:SpuntoViewModel,onNote:(String)->Unit) {
 val manifest by model.manifest.collectAsState()
 val online by model.online.collectAsState()
 val auth by model.auth.collectAsState()
 val epoch by model.pluginReplicaEpochState.collectAsState()
 val enabled=model.container.manifest.enabled("ask")
 var question by rememberSaveable(auth,epoch) {mutableStateOf("")}
 var asked by remember(auth,epoch) {mutableStateOf("")}
 var answer by remember(auth,epoch) {mutableStateOf<JsonObject?>(null)}
 var asking by remember(auth,epoch) {mutableStateOf(false)}
 var failed by remember(auth,epoch) {mutableStateOf(false)}
 var request by remember(auth,epoch) {mutableStateOf<Job?>(null)}
 val keyboard=LocalSoftwareKeyboardController.current
 LaunchedEffect(auth,epoch,manifest) {if(!enabled || auth==null) {request?.cancel();answer=null;asked="";asking=false;failed=false}}
 DisposableEffect(auth,epoch) {onDispose {request?.cancel()}}
 LazyColumn(Modifier.fillMaxSize().imePadding(),contentPadding=PaddingValues(SpuntoSpacing.s20),verticalArrangement=Arrangement.spacedBy(SpuntoSpacing.s20)) {
  item {
   AskQuestionInput(question,enabled,online,asking,onQuestion={question=it},onAsk={
    val config=model.auth.value
    if(config!=null && model.online.value && model.container.manifest.enabled("ask")) {
     val submitted=question.trim();asked=submitted;answer=null;failed=false;asking=true;keyboard?.hide()
     request=model.ask(submitted,onComplete={asking=false;if(answer==null && model.auth.value==config && model.pluginReplicaEpoch==epoch && model.container.manifest.enabled("ask")) failed=true}) {result ->
      if(model.auth.value==config && model.pluginReplicaEpoch==epoch && model.container.manifest.enabled("ask")) answer=result as? JsonObject
     }
    }
   })
  }
  if(asking) item {Row(horizontalArrangement=Arrangement.spacedBy(SpuntoSpacing.s12),verticalAlignment=Alignment.CenterVertically) {CircularProgressIndicator(Modifier.size(20.dp),strokeWidth=2.dp);Text("Reading your notes…")}}
  if(failed && !asking) item {Text("Couldn't answer this question. Check the connection and Settings → AI models, then try again.",color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodyMedium)}
  answer?.takeIf {enabled}?.let {output ->
   item {HorizontalDivider();Text(asked,Modifier.padding(top=SpuntoSpacing.s20),style=MaterialTheme.typography.titleMedium)}
   item {RenderOutput(model,JsonObject(output+mapOf("plugin_id" to JsonPrimitive("ask"))),onNote)}
  }
 }
}

@Composable internal fun AskQuestionInput(question:String,enabled:Boolean,online:Boolean,asking:Boolean,onQuestion:(String)->Unit,onAsk:()->Unit) {
 val questionLength=question.codePointCount(0,question.length)
 Column(verticalArrangement=Arrangement.spacedBy(SpuntoSpacing.s12)) {
  Text("Find an answer in your notes",style=MaterialTheme.typography.headlineSmall)
  Text("Ask a question in your own words. Each question searches your notes and includes sources. Private notes stay private.",style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
  when {
   !enabled -> Text("Ask is switched off. Turn on Ask in Settings → Plugins.",style=MaterialTheme.typography.bodyMedium)
   !online -> Text("Connect to your server to ask your notes.",style=MaterialTheme.typography.bodyMedium)
  }
  OutlinedTextField(question,onQuestion,Modifier.fillMaxWidth().semantics {contentDescription="Question for your notes"},label={Text("Your question")},placeholder={Text("What did I decide about…?")},minLines=3,maxLines=6,enabled=enabled && !asking,isError=questionLength>500,supportingText={if(questionLength>450) Text("$questionLength / 500 characters")})
  Button(onClick=onAsk,enabled=enabled && online && !asking && question.isNotBlank() && questionLength<=500,modifier=Modifier.fillMaxWidth()) {Text(if(asking) "Reading your notes…" else "Ask your notes")}
 }
}
