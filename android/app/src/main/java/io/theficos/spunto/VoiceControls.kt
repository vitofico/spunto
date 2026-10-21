@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package io.theficos.spunto

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.ui.res.painterResource
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import io.theficos.spunto.data.local.RecordingDraftEntity
import io.theficos.spunto.plugins.dictation.voice.*

internal fun recordingTime(milliseconds:Long):String {val seconds=milliseconds.coerceAtLeast(0)/1000;return "${seconds/60}:${(seconds%60).toString().padStart(2,'0')}"}
internal fun recordingActive(phase:RecordingPhase)=phase in setOf(RecordingPhase.Starting,RecordingPhase.Recording,RecordingPhase.Stopping)

@Composable internal fun ComposerSaveAction(enabled:Boolean,saving:Boolean,startingVoice:Boolean,onSave:()->Unit) {
 Button(onClick={if(!startingVoice) onSave()},enabled=enabled && !startingVoice,modifier=Modifier.heightIn(min=48.dp)) {Text(if(saving) "Saving…" else "Save")}
}
@Composable internal fun ComposerDictationAction(enabled:Boolean,listening:Boolean,startingVoice:Boolean,onTap:()->Unit) {
 IconButton(onClick={if(!startingVoice) onTap()},enabled=enabled && !startingVoice,modifier=Modifier.semantics {contentDescription=if(listening) "Stop dictation" else "Dictate"}) {
  if(listening) Box(Modifier.size(18.dp).background(MaterialTheme.colorScheme.primary,MaterialTheme.shapes.extraSmall)) else Icon(painterResource(R.drawable.ic_mic),contentDescription=null,modifier=Modifier.size(24.dp))
 }
}

@Composable fun VoiceControls(state:RecordingState,transcriptionActive:Boolean,pending:RecordingDraftEntity?,recoveries:List<RecordingDraftEntity>,enabled:Boolean,onStop:()->Unit,onDiscard:(String)->Unit,onRecover:(String)->Unit,saveAction:String="Save") {
 val active=recordingActive(state.phase)
 if(active || pending!=null || state.phase==RecordingPhase.Failed) Surface(color=MaterialTheme.colorScheme.surfaceContainer,shape=MaterialTheme.shapes.medium) {
  Column(Modifier.fillMaxWidth().padding(12.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
   val elapsed=recordingTime(if(active) state.durationMs else pending?.durationMs?:state.durationMs)
   Text(if(active) "Recording · $elapsed" else "Recording kept on this phone · $elapsed",Modifier.semantics {contentDescription=if(active) "Recording elapsed $elapsed" else "Recording duration $elapsed"})
   if(active) LinearProgressIndicator(progress={state.level.coerceIn(0f,1f)},modifier=Modifier.fillMaxWidth().semantics {contentDescription="Microphone level";stateDescription="${(state.level*100).toInt().coerceIn(0,100)} percent"})
   Text(if(active) state.message else if(state.phase==RecordingPhase.Failed && pending==null) state.message else "$saveAction to attach this clip. Discard keeps your text.",style=MaterialTheme.typography.bodySmall)
   if(!transcriptionActive) Text("Live text unavailable. Server transcription needs a transcription model; your recording is kept.",style=MaterialTheme.typography.bodySmall)
   FlowRow(horizontalArrangement=Arrangement.spacedBy(4.dp)) {
    if(active) TextButton(onClick=onStop,enabled=enabled && state.phase!=RecordingPhase.Stopping) {Text("Stop recording")}
    if(state.phase==RecordingPhase.Failed && pending==null) state.id?.let {id ->TextButton(onClick={onRecover(id)},enabled=enabled) {Text("Recover recording")}}
    (pending?.id?:state.id)?.let {id -> TextButton(onClick={onDiscard(id)},enabled=enabled && state.phase!=RecordingPhase.Stopping) {Text("Discard recording")}}
   }
  }
 }
 recoveries.forEach {row -> Surface(color=MaterialTheme.colorScheme.surfaceContainer,shape=MaterialTheme.shapes.medium) {
  Column(Modifier.fillMaxWidth().padding(12.dp)) {
   Text("Unfinished recording kept on this phone",style=MaterialTheme.typography.labelLarge)
   Text(if(row.state=="interrupted") "This clip could not be recovered. Your draft text is kept." else "Recover this clip to attach it with $saveAction, or discard it.",style=MaterialTheme.typography.bodySmall)
   FlowRow(horizontalArrangement=Arrangement.spacedBy(4.dp)) {
    if(row.state in setOf("ready","recoverable")) TextButton(onClick={onRecover(row.id)},enabled=enabled) {Text("Recover recording")}
    TextButton(onClick={onDiscard(row.id)},enabled=enabled) {Text("Discard recording")}
   }
  }
 } }
}
