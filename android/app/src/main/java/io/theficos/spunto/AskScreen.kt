package io.theficos.spunto

import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import io.theficos.spunto.core.design.SpuntoSpacing

/** A direct destination for a temporary conversation grounded in the notes. */
@Composable fun AskScreen(model:SpuntoViewModel,onNote:(String)->Unit) {AskConversation(model,onNote)}

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
