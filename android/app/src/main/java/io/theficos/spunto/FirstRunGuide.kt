package io.theficos.spunto

import android.content.Context
import android.content.SharedPreferences
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp

internal const val firstRunGuideCompletedKey="completed-v1"
internal fun firstRunGuidePreferences(context:Context):SharedPreferences = context.applicationContext.getSharedPreferences("first-run-guide",Context.MODE_PRIVATE)
internal class FirstRunGuideStore(private val preferences:SharedPreferences) {
 val completed:Boolean get()=preferences.getBoolean(firstRunGuideCompletedKey,false)
 fun complete() {preferences.edit().putBoolean(firstRunGuideCompletedKey,true).apply()}
}
internal fun firstRunGuideEligible(action:String?,hasData:Boolean,initialNoteId:String?):Boolean = action=="android.intent.action.MAIN" && !hasData && initialNoteId==null

/** Only an eligible timeline or an explicit Settings replay enters this gate. */
@Composable internal fun FirstRunGuideGate(
 store:FirstRunGuideStore,eligible:Boolean,replay:Boolean=false,canConnect:Boolean=false,
 onStartWriting:()->Unit,onSkipReplay:()->Unit={},onConnect:()->Unit={},content:@Composable ()->Unit,
) {
 var completed by rememberSaveable {mutableStateOf(store.completed)}
 if(replay || eligible && !completed) {
  fun finish(action:()->Unit) {store.complete();completed=true;action()}
  FirstRunGuide(onSkip={finish {if(replay) onSkipReplay()}},onStart={finish(onStartWriting)},onConnect=if(canConnect) ({finish(onConnect)}) else null)
 } else content()
}

private data class GuideStep(val title:String,val body:String,val example:String,val caption:String)
private val guideSteps=listOf(
 GuideStep("Catch a thought.","Write a quick note, then tap Save. It is kept on this phone, even without a connection.","Ask about the little bookshop.","A note can be a sentence, a list or a longer thought."),
 GuideStep("Labels live in your words.","Type # in a note to add a label. Use / for a nested label. Open All notes to browse them.","A thought for the weekend #ideas\n\nA place to try #life/food","Labels stay in the note text. One note can have several."),
 GuideStep("Find it when you need it.","Search works by keyword on this phone. With a configured provider and semantic search enabled, your server can also find similar meaning while connected.","Search: little bookshop","Keyword search works offline. Meaning search is optional."),
 GuideStep("Connect when you are ready.","Keep writing offline, or sync with your own server using its HTTPS address. Hosted accounts sign in in the browser, then create a pairing QR in Settings → Devices.","This phone → your own server","A server is optional for local notes. Plain HTTP needs an explicit warning."),
)

@Composable internal fun FirstRunGuide(onSkip:()->Unit,onStart:()->Unit,onConnect:(()->Unit)?=null) {
 var step by rememberSaveable {mutableIntStateOf(0)}
 val current=guideSteps[step]
 val scroll=rememberScrollState()
 val keyboard=LocalSoftwareKeyboardController.current
 val focus=LocalFocusManager.current
 fun leave(action:()->Unit) {focus.clearFocus();keyboard?.hide();action()}
 LaunchedEffect(Unit) {focus.clearFocus();keyboard?.hide()}
 LaunchedEffect(step) {scroll.scrollTo(0)}
 BackHandler {if(step>0) step--}
 Surface(Modifier.fillMaxSize()) {
  BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding(),contentAlignment=Alignment.TopCenter) {
   val compact=maxHeight<600.dp || LocalDensity.current.fontScale>1.3f
   Column(Modifier.widthIn(max=560.dp).fillMaxWidth().fillMaxHeight().padding(horizontal=24.dp,vertical=16.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
     Text("Getting started",Modifier.weight(1f),style=MaterialTheme.typography.labelLarge,color=MaterialTheme.colorScheme.onSurfaceVariant)
     TextButton(onClick={leave(onSkip)},modifier=Modifier.heightIn(min=48.dp)) {Text("Skip")}
    }
    val actions:@Composable ()->Unit={
     GuideActions(step,compact,onBack={step--},onNext={if(step==guideSteps.lastIndex) leave(onStart) else step++},onConnect=if(onConnect!=null) ({leave(onConnect)}) else null)
    }
    // Short screens and enlarged text give the explanation and actions one scroll area.
    Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(scroll),verticalArrangement=Arrangement.spacedBy(24.dp)) {
     GuideContent(step,current)
     if(compact) actions()
    }
    if(!compact) actions()
   }
  }
 }
}

@Composable private fun GuideContent(step:Int,current:GuideStep) {
 Column(Modifier.fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(20.dp)) {
  Text("Step ${step+1} of ${guideSteps.size}",style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.onSurfaceVariant,modifier=Modifier.semantics {liveRegion=LiveRegionMode.Polite})
  LinearProgressIndicator(progress={(step+1).toFloat()/guideSteps.size},modifier=Modifier.fillMaxWidth().semantics {contentDescription="Guide progress"})
  Text(current.title,style=MaterialTheme.typography.titleLarge,modifier=Modifier.semantics {heading()})
  Text(current.body,style=MaterialTheme.typography.bodyMedium)
  Surface(shape=MaterialTheme.shapes.medium,color=MaterialTheme.colorScheme.surfaceContainer) {
   Column(Modifier.fillMaxWidth().padding(20.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
    Text("Example · not a saved note",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
    Text(current.example,style=MaterialTheme.typography.bodyLarge)
    Text(current.caption,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
   }
  }
  Spacer(Modifier.height(4.dp))
 }
}

@Composable private fun GuideActions(step:Int,compact:Boolean,onBack:()->Unit,onNext:()->Unit,onConnect:(()->Unit)?) {
 Column(Modifier.fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(8.dp)) {
  if(step==guideSteps.lastIndex && onConnect!=null) OutlinedButton(onClick=onConnect,modifier=Modifier.fillMaxWidth().heightIn(min=48.dp)) {Text("Connect to a server")}
  if(compact) {
   Button(onClick=onNext,modifier=Modifier.fillMaxWidth().heightIn(min=48.dp)) {Text(if(step==guideSteps.lastIndex) "Start writing" else "Next")}
   TextButton(enabled=step>0,onClick=onBack,modifier=Modifier.fillMaxWidth().heightIn(min=48.dp)) {Text("Back")}
  } else Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(12.dp),verticalAlignment=Alignment.CenterVertically) {
   TextButton(enabled=step>0,onClick=onBack,modifier=Modifier.heightIn(min=48.dp)) {Text("Back")}
   Button(onClick=onNext,modifier=Modifier.weight(1f).heightIn(min=48.dp)) {Text(if(step==guideSteps.lastIndex) "Start writing" else "Next")}
  }
 }
}
