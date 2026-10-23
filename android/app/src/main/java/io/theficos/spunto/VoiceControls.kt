@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package io.theficos.spunto

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.ui.res.painterResource
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import io.theficos.spunto.data.local.RecordingDraftEntity
import io.theficos.spunto.plugins.dictation.voice.*
import io.theficos.spunto.core.design.SpuntoSpacing

internal fun recordingTime(milliseconds:Long):String {val seconds=milliseconds.coerceAtLeast(0)/1000;return "${seconds/60}:${(seconds%60).toString().padStart(2,'0')}"}
internal fun recordingActive(phase:RecordingPhase)=phase in setOf(RecordingPhase.Starting,RecordingPhase.Recording,RecordingPhase.Stopping)

@Composable internal fun ComposerSaveAction(enabled:Boolean,saving:Boolean,startingVoice:Boolean,onSave:()->Unit) {
 val context=LocalContext.current
 Button(onClick={if(!startingVoice) onSave()},enabled=enabled && !startingVoice,modifier=Modifier.heightIn(min=48.dp)) {Text(context.getString(if(saving) R.string.sys_saving else R.string.sys_save))}
}
@Composable internal fun ComposerDictationAction(enabled:Boolean,listening:Boolean,startingVoice:Boolean,onTap:()->Unit) {
 val context=LocalContext.current
 IconButton(onClick={if(!startingVoice) onTap()},enabled=enabled && !startingVoice,modifier=Modifier.semantics {contentDescription=context.getString(if(listening) R.string.sys_stop_dictation else R.string.sys_dictate)}) {
  if(listening) Box(Modifier.size(18.dp).background(MaterialTheme.colorScheme.primary,MaterialTheme.shapes.extraSmall)) else Icon(painterResource(R.drawable.ic_mic),contentDescription=null,modifier=Modifier.size(24.dp))
 }
}

@Composable fun VoiceControls(state:RecordingState,transcriptionActive:Boolean,pending:RecordingDraftEntity?,recoveries:List<RecordingDraftEntity>,enabled:Boolean,onStop:()->Unit,onDiscard:(String)->Unit,onRecover:(String)->Unit,saveAction:String?=null) {
 val context=LocalContext.current
 val action=saveAction ?: context.getString(R.string.sys_save)
 val active=recordingActive(state.phase)
 if(active || pending!=null || state.phase==RecordingPhase.Failed) Surface(color=MaterialTheme.colorScheme.surfaceContainer,shape=MaterialTheme.shapes.medium) {
  Column(Modifier.fillMaxWidth().padding(SpuntoSpacing.s12),verticalArrangement=Arrangement.spacedBy(SpuntoSpacing.s8)) {
   val elapsed=recordingTime(if(active) state.durationMs else pending?.durationMs?:state.durationMs)
   Text(context.getString(if(active) R.string.sys_recording_time else R.string.sys_recording_kept_time,elapsed),Modifier.semantics {contentDescription=context.getString(if(active) R.string.sys_recording_elapsed else R.string.sys_recording_duration,elapsed)})
   if(active) LinearProgressIndicator(progress={state.level.coerceIn(0f,1f)},modifier=Modifier.fillMaxWidth().semantics {contentDescription=context.getString(R.string.sys_microphone_level);stateDescription=context.getString(R.string.sys_percent,(state.level*100).toInt().coerceIn(0,100))})
   Text(if(active || state.phase==RecordingPhase.Failed && pending==null) recordingMessage(context,state) else context.getString(R.string.sys_attach_recording,action),style=MaterialTheme.typography.bodySmall)
   if(!transcriptionActive) Text(context.getString(R.string.sys_live_text_unavailable),style=MaterialTheme.typography.bodySmall)
   FlowRow(horizontalArrangement=Arrangement.spacedBy(SpuntoSpacing.s4)) {
    if(active) TextButton(onClick=onStop,enabled=enabled && state.phase!=RecordingPhase.Stopping) {Text(context.getString(R.string.sys_stop_recording))}
    if(state.phase==RecordingPhase.Failed && pending==null) state.id?.let {id ->TextButton(onClick={onRecover(id)},enabled=enabled) {Text(context.getString(R.string.sys_recover_recording))}}
    (pending?.id?:state.id)?.let {id -> TextButton(onClick={onDiscard(id)},enabled=enabled && state.phase!=RecordingPhase.Stopping) {Text(context.getString(R.string.sys_discard_recording))}}
   }
  }
 }
 recoveries.forEach {row -> Surface(color=MaterialTheme.colorScheme.surfaceContainer,shape=MaterialTheme.shapes.medium) {
  Column(Modifier.fillMaxWidth().padding(SpuntoSpacing.s12)) {
   Text(context.getString(R.string.sys_unfinished_recording),style=MaterialTheme.typography.labelLarge)
   Text(if(row.state=="interrupted") context.getString(R.string.sys_clip_unrecoverable) else context.getString(R.string.sys_recover_clip,action),style=MaterialTheme.typography.bodySmall)
   FlowRow(horizontalArrangement=Arrangement.spacedBy(SpuntoSpacing.s4)) {
    if(row.state in setOf("ready","recoverable")) TextButton(onClick={onRecover(row.id)},enabled=enabled) {Text(context.getString(R.string.sys_recover_recording))}
    TextButton(onClick={onDiscard(row.id)},enabled=enabled) {Text(context.getString(R.string.sys_discard_recording))}
   }
  }
 } }
}
