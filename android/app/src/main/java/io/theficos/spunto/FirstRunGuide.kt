package io.theficos.spunto

import androidx.compose.ui.platform.LocalContext

import android.content.Context
import android.content.SharedPreferences
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
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
import io.theficos.spunto.core.design.SpuntoSpacing
import io.theficos.spunto.core.design.SpuntoType

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
private fun guideSteps(context:android.content.Context)=listOf(
 GuideStep(context.getString(R.string.core_guide_catch),context.getString(R.string.core_guide_catch_body),context.getString(R.string.core_guide_bookshop),context.getString(R.string.core_guide_note_caption)),
 GuideStep(context.getString(R.string.core_guide_labels),context.getString(R.string.core_guide_labels_body),context.getString(R.string.core_guide_labels_example),context.getString(R.string.core_guide_labels_caption)),
 GuideStep(context.getString(R.string.core_guide_find),context.getString(R.string.core_guide_find_body),context.getString(R.string.core_guide_search_example),context.getString(R.string.core_guide_find_caption)),
 GuideStep(context.getString(R.string.core_guide_sync),context.getString(R.string.core_guide_sync_body),"https://spunto.example.org",context.getString(R.string.core_guide_sync_caption)),
)

@Composable internal fun FirstRunGuide(onSkip:()->Unit,onStart:()->Unit,onConnect:(()->Unit)?=null) {
 val context=LocalContext.current
 var step by rememberSaveable {mutableIntStateOf(0)}
 val guideSteps=guideSteps(context)
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
   Column(Modifier.widthIn(max=560.dp).fillMaxWidth().fillMaxHeight().padding(horizontal=SpuntoSpacing.s24,vertical=SpuntoSpacing.s16),verticalArrangement=Arrangement.spacedBy(SpuntoSpacing.s16)) {
    // The one progress indicator; the last step offers no Skip, only its own actions.
    Row(Modifier.fillMaxWidth().heightIn(min=48.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(SpuntoSpacing.s12)) {
     Text(context.getString(R.string.core_guide_progress,step+1,guideSteps.size),Modifier.weight(1f).semantics {contentDescription=context.getString(R.string.core_guide_step,step+1,guideSteps.size);liveRegion=LiveRegionMode.Polite},style=MaterialTheme.typography.labelLarge,color=MaterialTheme.colorScheme.onSurfaceVariant)
     if(step<guideSteps.lastIndex) TextButton(onClick={leave(onSkip)},modifier=Modifier.heightIn(min=48.dp)) {Text(context.getString(R.string.core_skip))}
    }
    val actions:@Composable ()->Unit={
     GuideActions(step,compact,onBack={step--},onNext={if(step==guideSteps.lastIndex) leave(onStart) else step++},onConnect=if(onConnect!=null) ({leave(onConnect)}) else null)
    }
    // Short screens and enlarged text give the explanation and actions one scroll area.
    Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(scroll),verticalArrangement=Arrangement.spacedBy(SpuntoSpacing.s24)) {
     GuideContent(step,if(step==guideSteps.lastIndex && onConnect==null) current.copy(caption=context.getString(R.string.core_already_connected)) else current)
     if(compact) actions()
    }
    if(!compact) actions()
   }
  }
 }
}

@Composable private fun GuideContent(step:Int,current:GuideStep) {
 Column(Modifier.fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(SpuntoSpacing.s20)) {
  Text(current.title,style=MaterialTheme.typography.titleLarge,modifier=Modifier.semantics {heading()})
  Text(current.body,style=MaterialTheme.typography.bodyMedium)
  // A light quote, not a card: the example reads like note text and the caption says what it shows.
  Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min),horizontalArrangement=Arrangement.spacedBy(SpuntoSpacing.s16)) {
   Box(Modifier.width(2.dp).fillMaxHeight().background(MaterialTheme.colorScheme.outlineVariant))
   Column(verticalArrangement=Arrangement.spacedBy(SpuntoSpacing.s8)) {
    Text(current.example,style=SpuntoType.reading)
    Text(current.caption,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
   }
  }
  Spacer(Modifier.height(SpuntoSpacing.s4))
 }
}

@Composable private fun GuideActions(step:Int,compact:Boolean,onBack:()->Unit,onNext:()->Unit,onConnect:(()->Unit)?) {
 val context=LocalContext.current
 val guideSteps=guideSteps(context)
 Column(Modifier.fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(SpuntoSpacing.s8)) {
  if(step==guideSteps.lastIndex && onConnect!=null) OutlinedButton(onClick=onConnect,modifier=Modifier.fillMaxWidth().heightIn(min=48.dp)) {Text(context.getString(R.string.core_connect_server))}
  if(compact) {
   Button(onClick=onNext,modifier=Modifier.fillMaxWidth().heightIn(min=48.dp)) {Text(if(step==guideSteps.lastIndex) context.getString(R.string.core_start_writing) else context.getString(R.string.core_next))}
   TextButton(enabled=step>0,onClick=onBack,modifier=Modifier.fillMaxWidth().heightIn(min=48.dp)) {Text(context.getString(R.string.core_back))}
  } else Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(SpuntoSpacing.s12),verticalAlignment=Alignment.CenterVertically) {
   TextButton(enabled=step>0,onClick=onBack,modifier=Modifier.heightIn(min=48.dp)) {Text(context.getString(R.string.core_back))}
   Button(onClick=onNext,modifier=Modifier.weight(1f).heightIn(min=48.dp)) {Text(if(step==guideSteps.lastIndex) context.getString(R.string.core_start_writing) else context.getString(R.string.core_next))}
  }
 }
}
