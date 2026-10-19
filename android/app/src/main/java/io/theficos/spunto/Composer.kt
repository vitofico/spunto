package io.theficos.spunto

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.ReportDrawnWhen
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.focus.*
import androidx.compose.ui.platform.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.*
import androidx.compose.ui.text.input.*
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.*
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.room.withTransaction
import io.theficos.spunto.core.design.*
import io.theficos.spunto.core.model.LabelCount
import io.theficos.spunto.core.plugins.*
import kotlinx.serialization.json.*
import io.theficos.spunto.plugins.dictation.*
import io.theficos.spunto.plugins.dictation.voice.*
import io.theficos.spunto.data.local.*
import kotlinx.coroutines.*

@Composable fun Composer(model:SpuntoViewModel,id:String="capture",initial:String="",draftId:String=id,autoFocus:Boolean=true,autoDictate:Boolean=false,canSaveEmpty:Boolean=false,onTextChanged:(String)->Unit={},onFinishReady:((suspend ()->Unit)?)->Unit={},allowVoiceCapture:Boolean=draftId=="capture" || id!="capture",onPlainCaptureSave:(suspend (String,String?)->Unit)?=null,onRecordingSaved:suspend (String)->Unit={},startNewParagraph:Boolean=false,onSave:suspend (String)->Unit) {
 var lease by remember(id,draftId) {mutableStateOf(model.repository.beginEditor(id.takeUnless {it=="capture"},draftId))}
 val editorLease=lease
 val latestLease by rememberUpdatedState(lease)
 val voice=remember(id,draftId) {VoiceCaptureController(model) {latestLease}}
 val sessionRecording by voice.state.collectAsState()
 var retainedId by remember(id,draftId) {mutableStateOf<String?>(null)}
 val recording=if(sessionRecording.id==retainedId) RecordingState() else sessionRecording
 val journal by model.container.recordings.drafts.collectAsState(emptyList())
 var recoveredId by remember(id,draftId) {mutableStateOf<String?>(null)}
 val pendingRecording=journal.firstOrNull {it.id==(recoveredId?:recording.id) && it.state in setOf("ready","recoverable")}
 val recoveries=journal.filter {it.draftId==draftId && it.id!=recording.id && it.id!=recoveredId && it.state in setOf("ready","recoverable","interrupted")}
 val context=LocalContext.current
 val view=LocalView.current
 val scope=rememberCoroutineScope()
 val keyboard=LocalSoftwareKeyboardController.current
 var ready by remember(id) { mutableStateOf(false) }
 var focused by remember(id) { mutableStateOf(false) }
 ReportDrawnWhen { ready && (autoDictate || !autoFocus || focused) }
 var committed by remember(id,draftId) { mutableStateOf(initial) }
 var saving by remember(id,draftId) { mutableStateOf(false) }
 var closing by remember(id,draftId) { mutableStateOf(false) }
 var saved by remember(id,draftId) { mutableStateOf(false) }
 var dictatedText by remember(id,draftId) {mutableStateOf(false)}
 var dictationInitial by remember(id,draftId) {mutableStateOf<String?>(null)}
 var ownerRevision by remember(id,draftId) {mutableStateOf(0L)}
 var retaining by remember(id,draftId) {mutableStateOf(false)}
 var recordingNeedsPermission by remember {mutableStateOf(false)}
 var permissionAction by remember {mutableStateOf("dictation")}
 var startingVoice by remember {mutableStateOf(false)}
 var value by remember(id) { mutableStateOf(TextFieldValue(initial,TextRange(initial.length))) }
 val labelCounts by model.labels.collectAsState()
 val networkOnline by model.online.collectAsState()
 val focus=remember { FocusRequester() }
 val preferences=model.container.preferences
 var language by remember { mutableStateOf(preferences.getString("dictation-language",if(java.util.Locale.getDefault().language=="it") "it-IT" else "en-US")!!) }
 val device=remember { DeviceSpeechPreferences(context) }
 val deviceSettings by device.settings.collectAsState(SpeechPreferences())
 val online=deviceSettings.onlineConsent
 LaunchedEffect(deviceSettings.language) { language=deviceSettings.language }
 val manifest=model.manifest.collectAsState().value
 val catalog=model.catalog.collectAsState().value
 val voiceFacts=remember(manifest,catalog) {recordingPluginFacts(manifest.toString(),catalog.toString())}
 SideEffect {voice.policy(voiceFacts)}
 LaunchedEffect(Unit) {model.container.recordingRecovery.await()}
 val dictationEnabled=model.container.manifest.enabled("dictation")
 val mic=dictationEnabled && PlatformSpeechEngine.available(context,online)
 var sessionHolder by remember { mutableStateOf<DictationSession?>(null) }
 val session=remember(id,draftId) { DictationSession(scope,{PlatformSpeechEngine(context)},PlatformAudioFocus(context) { sessionHolder?.stopAndKeep() },{text -> val write=model.repository.editorWrite(lease,text);model.container.scope.launch { model.repository.persistEditor(write) } },{code -> model.task { model.repository.record("dictation",code.toString()) } }).also { sessionHolder=it } }
 val dictation by session.state.collectAsState()
 var preparation by remember { mutableStateOf<Job?>(null) }
 val latestEnabled by rememberUpdatedState(dictationEnabled)
 LaunchedEffect(dictationEnabled) { if(!dictationEnabled) { preparation?.cancel();session.stopAndKeep() } }
 var autoAvailable by remember { mutableStateOf(false) }
 var wordsBefore by remember { mutableStateOf(0) }
 var previousPhase by remember { mutableStateOf(DictationPhase.Idle) }
 var permissionAsked by remember { mutableStateOf(preferences.getBoolean("mic-asked",false)) }
 var permanent by remember { mutableStateOf(false) }
 fun start() {
  if(startingVoice || voice.hasSession || pendingRecording!=null) return
  saved=false
  dictationInitial=value.text
  keyboard?.hide()
  val manager=context.getSystemService(android.view.accessibility.AccessibilityManager::class.java)
  wordsBefore=value.text.trim().split(Regex("\\s+")).count { it.isNotEmpty() }
  preparation?.cancel()
  preparation=scope.launch {
   val selected=language
   val capability=async { PlatformSpeechEngine.supportsAuto(context) }
   if(selected=="auto") autoAvailable=capability.await()
   if(!latestEnabled) return@launch
   if(manager.isTouchExplorationEnabled) { view.announceForAccessibility("Listening in ${if(selected=="it-IT") "Italian" else if(selected=="auto") "Italian and English" else "English"}");delay(800) }
   if(!latestEnabled) return@launch
   session.start(value.text,value.selection.start,value.selection.end,if(selected=="auto") "it-IT" else selected,ContextCompat.checkSelfPermission(context,Manifest.permission.RECORD_AUDIO)==PackageManager.PERMISSION_GRANTED,SpeechOptions(online=online && !PlatformSpeechEngine.onDeviceAvailable(context),auto=selected=="auto" && autoAvailable))
   if(selected!="auto") autoAvailable=capability.await()
  }
 }
 fun startRecording() {
  if(!allowVoiceCapture || !voiceFacts.captureAllowed || android.os.Build.VERSION.SDK_INT<33) return
  if(startingVoice || recordingActive(voice.state.value.phase) || voice.state.value.recording!=null) return
  startingVoice=true;saved=false;recordingNeedsPermission=false;keyboard?.hide();preparation?.cancel()
  scope.launch {
   try {
    if(session.state.value.phase in setOf(DictationPhase.Starting,DictationPhase.Listening,DictationPhase.Stopping)) session.stopForSave()
    voice.start(value.text,value.selection.start,value.selection.end,language)
   } catch(cancelled:CancellationException) {throw cancelled}
   catch(error:Exception) {model.message.value=if(error is IllegalArgumentException || error is IllegalStateException) error.message.orEmpty() else "Couldn't start recording. Your text is kept."}
   finally {startingVoice=false}
  }
 }
 val permission=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
  permissionAsked=true; preferences.edit().putBoolean("mic-asked",true).apply()
  permanent=!granted && !(context as Activity).shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)
  if(permissionAction=="recording") {if(granted) startRecording() else recordingNeedsPermission=true}
  else if(granted) start() else session.start(value.text,value.selection.start,value.selection.end,language,false)
 }
 fun micTap() { if(startingVoice) return;permissionAction="dictation";if(ContextCompat.checkSelfPermission(context,Manifest.permission.RECORD_AUDIO)==PackageManager.PERMISSION_GRANTED) start() else permission.launch(Manifest.permission.RECORD_AUDIO) }
 fun recordTap() {permissionAction="recording";if(ContextCompat.checkSelfPermission(context,Manifest.permission.RECORD_AUDIO)==PackageManager.PERMISSION_GRANTED) startRecording() else permission.launch(Manifest.permission.RECORD_AUDIO)}
 LaunchedEffect(id,draftId) {
  // Room's observed note can lag a completed restore. Open from one saved snapshot,
  // keeping an unsent draft ahead of the saved body without replacing active typing.
  val (draft,current)=model.container.database.withTransaction {
   model.repository.dao.draftSnapshot(draftId) to if(id=="capture") null else model.repository.dao.get(id)?.body
  }
  val body=draft?.body?.takeIf {it.isNotEmpty()} ?: ((current ?: initial)+if(startNewParagraph && current!=null) "\n\n" else "")
  value=TextFieldValue(body,TextRange(body.length))
  committed=current ?: initial
  ready=true
  androidx.compose.runtime.withFrameNanos { }
  if(autoDictate && mic) micTap() else if(autoFocus) { focus.requestFocus(); keyboard?.show() }
 }
 LaunchedEffect(dictation.phase) { if(previousPhase in setOf(DictationPhase.Listening,DictationPhase.Stopping) && dictation.phase !in setOf(DictationPhase.Listening,DictationPhase.Stopping)) { val words=(dictation.text.trim().split(Regex("\\s+")).count { it.isNotEmpty() }-wordsBefore).coerceAtLeast(0);view.announceForAccessibility("Stopped. $words words added.") };previousPhase=dictation.phase }
 LaunchedEffect(dictation.text) { if(!saved && !voice.hasSession && dictation.phase!=DictationPhase.NeedsPermission && dictation.text.isNotEmpty()) {if(dictation.text!=value.text) dictatedText=true;value=TextFieldValue(dictation.text,TextRange(dictation.text.length))} }
 LaunchedEffect(recording.text,recording.textRevision) {
  val snapshot=RecordingTextSnapshot(recording.text,recording.textRevision)
  if(!saved && recording.id!=null && voice.isCurrent(snapshot) && value.text!=snapshot.text) value=TextFieldValue(snapshot.text,TextRange(snapshot.text.length))
 }
 suspend fun commitEditor(write:io.theficos.spunto.data.local.EditorWrite):Boolean = try {
  model.repository.persistEditor(write,commit=true)
 } catch(error:CancellationException) {throw error}
 catch(error:Exception) {
  model.message.value=if(error is IllegalArgumentException) error.message.orEmpty() else "Couldn't save on this phone. Your draft is still here."
  false
 }
 LaunchedEffect(value.text,ready,saving) {
  if(!ready || saved) return@LaunchedEffect
  onTextChanged(value.text)
  if(saving) return@LaunchedEffect
  val body=value.text;val write=model.repository.editorWrite(lease,body)
  delay(300);model.repository.persistEditor(write)
  if(id!="capture" && body!=committed) { delay(4700);if(commitEditor(write)) committed=body }
 }
 val latestText by rememberUpdatedState(value.text)
 val latestReady by rememberUpdatedState(ready)
 val latestSaved by rememberUpdatedState(saved)
 val latestSaving by rememberUpdatedState(saving)
 LaunchedEffect(id,draftId) { if(id!="capture") while(true) { delay(60_000); if(ready && !saved && !latestSaving && latestText!=committed) { val body=latestText;val write=model.repository.editorWrite(lease,body);if(commitEditor(write)) committed=body } } }
 DisposableEffect(editorLease) {
  onFinishReady {
   closing=true
   try {
    val active=session.state.value.phase in setOf(DictationPhase.Starting,DictationPhase.Listening,DictationPhase.Stopping)
    voice.onForegroundChanged(false);if(voice.hasSession) voice.stop()
    val body=if(voice.hasSession) voice.textSnapshot().text else if(active) session.stopForSave() else latestText
    if(latestReady && !latestSaved) check(model.repository.finishEditor(model.repository.editorWrite(editorLease,body))) {"Editor is no longer active."}
    else model.repository.abandonEditor(editorLease)
   } catch(error:Exception) {closing=false;throw error}
  }
  onDispose {
   onFinishReady(null)
   if(editorLease!==latestLease) model.repository.abandonEditor(editorLease)
   else if(latestReady && !latestSaved) {
    val active=session.state.value.phase in setOf(DictationPhase.Starting,DictationPhase.Listening,DictationPhase.Stopping)
    session.stopAndKeep()
    voice.onForegroundChanged(false)
    val finalText=if(active) session.state.value.text else latestText
    model.container.scope.launch {
     try {if(voice.hasSession) voice.stop();val body=if(voice.hasSession) voice.textSnapshot().text else finalText;model.repository.finishEditor(model.repository.editorWrite(editorLease,body))}
     catch(error:CancellationException) {throw error}
     catch(error:Exception) {
      model.repository.abandonEditor(editorLease)
      model.message.value=if(error is IllegalArgumentException) error.message.orEmpty() else "Couldn't save on this phone. Your draft is still here."
     }
    }
   } else model.repository.abandonEditor(editorLease)
  }
 }
 val owner=LocalLifecycleOwner.current
 DisposableEffect(owner) { val observer=LifecycleEventObserver { _,event -> if(event==Lifecycle.Event.ON_STOP) {session.stopAndKeep();voice.onForegroundChanged(false)} else if(event==Lifecycle.Event.ON_START) voice.onForegroundChanged(true) }; owner.lifecycle.addObserver(observer); onDispose { owner.lifecycle.removeObserver(observer); session.stopAndKeep();voice.onForegroundChanged(false) } }
 val listening=dictation.phase in setOf(DictationPhase.Starting,DictationPhase.Listening,DictationPhase.Stopping)
 fun ownerValue(new:TextFieldValue) {
  ownerRevision++;if(listening) session.stopAndKeep()
  voice.ownerEdit(new.text,new.selection.start,new.selection.end)
  if(saving && !voice.hasSession) {val write=model.repository.editorWrite(lease,new.text);model.container.scope.launch {model.repository.persistEditor(write)}}
  saved=false;value=new
 }
 val prefix=Regex("(?:^|\\s)#([\\p{L}\\p{N}_/-]*)$").find(value.text.take(value.selection.start))?.groupValues?.get(1)
 Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
  Surface(shape=MaterialTheme.shapes.medium,color=MaterialTheme.colorScheme.surfaceContainer) {
   Column(verticalArrangement=Arrangement.spacedBy(4.dp)) {
   Box(Modifier.fillMaxWidth().padding(16.dp).heightIn(min=96.dp)) {
    if(value.text.isEmpty()) Text("What's on your mind?",color=MaterialTheme.colorScheme.onSurfaceVariant,style=MaterialTheme.typography.bodyLarge)
    BasicTextField(value=value,enabled=ready && !closing && (id=="capture" || !saving),onValueChange={ownerValue(it)},textStyle=MaterialTheme.typography.bodyLarge.copy(color=MaterialTheme.colorScheme.onSurface),modifier=Modifier.fillMaxWidth().focusRequester(focus).onFocusChanged { focused=it.isFocused }.semantics { contentDescription="Note text" },visualTransformation=MarkdownEditorHighlight(MaterialTheme.colorScheme.primary,MaterialTheme.colorScheme.surfaceContainer,if(voice.hasSession) recording.partialSpan else dictation.partialSpan,MaterialTheme.colorScheme.onSurfaceVariant))
   }
  if(id!="capture" && value.text.take(value.selection.start).endsWith("/") && networkOnline) {
   val commands=model.container.manifest.plugins().filter { model.container.manifest.enabled(it.string("id")) }.flatMap { plugin -> (plugin["contributes"] as? kotlinx.serialization.json.JsonObject)?.objects("commands").orEmpty().filter { command -> (command["where"] as? kotlinx.serialization.json.JsonArray)?.any { it.jsonPrimitive.content=="slash" }==true }.take(3).map { plugin to it } }
   Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal=8.dp)) { commands.take(6).forEach { (plugin,command) -> TextButton(onClick={if(id!="capture") model.pluginCommand(plugin.string("id"),command.string("id"),kotlinx.serialization.json.buildJsonObject { put("note_id",id) })}) { Text(command.string("title")) } } }
  }
  if(prefix!=null) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal=8.dp),horizontalArrangement=Arrangement.spacedBy(8.dp),verticalAlignment=Alignment.CenterVertically) {
   labelCounts.filter { it.key.startsWith(prefix,true) }.sortedByDescending { it.count }.take(3).forEach { label -> SuggestionChip(onClick={val caret=value.selection.start; val start=caret-prefix.length; val text=value.text.replaceRange(start,caret,label.path+" "); ownerValue(TextFieldValue(text,TextRange(start+label.path.length+1)))},label={Text("#${label.path}")}) }
   if(prefix.isNotEmpty() && labelCounts.none { it.key.equals(prefix,true) }) Text("New label #$prefix",style=MaterialTheme.typography.labelMedium)
  }
  ComposerToolbar(tools={
    TextButton(onClick={ownerValue(TextFieldValue(value.text+" #",TextRange(value.text.length+2)));focus.requestFocus()},enabled=ready && !closing) { Text("# Label") }
    if(mic) ComposerDictationAction(ready && !closing && !voice.hasSession && pendingRecording==null,listening,startingVoice) {if(listening) session.stopAndKeep() else micTap()}
    if(allowVoiceCapture && voiceFacts.captureAllowed && android.os.Build.VERSION.SDK_INT>=33) TextButton(onClick={recordTap()},enabled=ready && !closing && !saving && !startingVoice && !listening && !recordingActive(recording.phase) && pendingRecording==null && recoveries.isEmpty()) {Text("Record voice memo")}
   },save={
   ComposerSaveAction(enabled=ready && !closing && !saving && (value.text.isNotBlank() || listening || recordingActive(recording.phase) || pendingRecording!=null || canSaveEmpty),saving=saving,startingVoice=startingVoice,onSave={
    if(startingVoice || saving) return@ComposerSaveAction
    val tappedRevision=ownerRevision
    val tappedWrite=model.repository.editorWrite(lease,value.text)
    retaining=recordingActive(voice.state.value.phase) || voice.state.value.recording!=null || pendingRecording!=null
    scope.launch {
    if(saving) return@launch
    saving=true
    try {
     if(retaining) {
      if(voice.hasSession) voice.stop()
      check(ownerRevision==tappedRevision) {"The editor changed. Save the current text with this recording."}
      val clipId=recoveredId?:voice.state.value.recording?.id?:pendingRecording?.id?:error("Recording could not be finalized. Recover or discard it before saving.")
      val body=if(voice.hasSession) voice.textSnapshot().text else tappedWrite.body
      val write=if(body==tappedWrite.body && !voice.hasSession) tappedWrite else model.repository.editorWrite(lease,body)
      val noteId=voice.retain(clipId,write)
      retainedId=clipId
      recoveredId=null
      val acknowledged=ownerRevision==tappedRevision
      if(id=="capture") {
       lease=model.repository.beginEditor(null,draftId)
       if(acknowledged) {saved=true;value=TextFieldValue("");dictatedText=false}
       else {saved=false;model.repository.persistEditor(model.repository.editorWrite(lease,value.text))}
      } else {saved=true}
      onRecordingSaved(noteId)
     } else {
      val body=if(listening) session.stopForSave() else value.text
      if(body.isNotBlank() || canSaveEmpty) {
       if(id!="capture") check(model.repository.finishEditor(model.repository.editorWrite(lease,body))) { "Editor is no longer active." }
       val dictated=dictatedText || listening && body.isNotBlank() && body!=dictationInitial
       if(id=="capture" && onPlainCaptureSave!=null) onPlainCaptureSave(body,if(dictated) "dictation" else null) else onSave(body)
       if(id=="capture") model.container.scope.launch { runCatching { model.repository.clearDraft(draftId,body) } }
       if(value.text==body) { saved=true;value=TextFieldValue("");dictatedText=false }
       if(autoFocus) focus.requestFocus()
      }
     }
    } catch(cancelled:CancellationException) {throw cancelled}
    catch(error:Exception) { model.message.value=if(error is IllegalArgumentException || error is IllegalStateException) error.message.orEmpty() else "Couldn't save on this phone. Your draft is still here." }
    finally { saving=false;retaining=false }
   }})
  })
   }
  }
  if(allowVoiceCapture && voiceFacts.captureAllowed && android.os.Build.VERSION.SDK_INT<33) Text("Voice recording needs Android 13 or newer. You can still type, or use dictation when available.",style=MaterialTheme.typography.bodySmall)
  VoiceControls(recording,voiceFacts.transcriptionActive,pendingRecording,recoveries,ready && !closing && !saving,onStop={voice.stopCapture()},onDiscard={clip ->scope.launch {try {voice.discard(clip);if(recoveredId==clip) recoveredId=null} catch(cancelled:CancellationException) {throw cancelled} catch(error:Exception) {model.message.value="Couldn't discard this clip. It is kept on this phone."}}},onRecover={clip ->scope.launch {try {if(journal.firstOrNull {it.id==clip}?.state !in setOf("ready","recoverable")) voice.recover(clip);recoveredId=clip} catch(cancelled:CancellationException) {throw cancelled} catch(error:Exception) {model.message.value="Couldn't recover this clip. Your recording and text are kept."}}})
  pendingRecording?.let {row ->AttachmentPlayer(model,row.id,"Recording ${recordingTime(row.durationMs)}",AttachmentEntity(row.id,row.noteId.orEmpty(),row.finalPath,"audio/ogg",kind="recording",durationMs=row.durationMs,byteSize=row.byteSize,sha256=row.sha256,serverUrl=row.serverUrl,status="local-only"))}
  if(recordingNeedsPermission) Row {Text("Allow microphone permission to record.",Modifier.weight(1f));TextButton(onClick={permissionAction="recording";if(permanent) context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:${context.packageName}"))) else permission.launch(Manifest.permission.RECORD_AUDIO)}) {Text(if(permanent) "Open settings" else "Allow")}}
  if(dictationEnabled && !voice.hasSession && (listening || dictation.message.isNotEmpty() && (dictation.phase!=DictationPhase.Idle || dictation.message.startsWith("Didn't catch")))) {
   Text(dictation.message,color=MaterialTheme.colorScheme.onSurfaceVariant)
   Row { TextButton(onClick={scope.launch { val wasListening=listening; session.stopAndKeep(); language=when(language) { "it-IT" -> "en-US";"en-US" -> if(autoAvailable) "auto" else "it-IT"; else -> "it-IT" };device.update(language=language); if(wasListening) start() }}) { Text(when(language) { "it-IT" -> "Italian"; "en-US" -> "English";else -> "Auto" }) }; TextButton(onClick={session.stopAndKeep();focus.requestFocus();keyboard?.show()}) { Text("Keyboard") }
    if(dictation.phase==DictationPhase.NeedsPermission) TextButton(onClick={if(permanent) context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:${context.packageName}"))) else permission.launch(Manifest.permission.RECORD_AUDIO)}) { Text(if(permanent) "Open settings" else "Allow") }
    if(dictation.phase==DictationPhase.NeedsLanguage) TextButton(onClick={scope.launch { PlatformSpeechEngine.download(context,language) }}) { Text("Download") }
   }
  }
 }
}

/** Keep the primary action visible while optional capture tools can scroll. */
@Composable internal fun ComposerToolbar(tools:@Composable RowScope.()->Unit,save:@Composable ()->Unit) {
 Row(Modifier.fillMaxWidth().padding(start=4.dp,end=8.dp,bottom=8.dp),horizontalArrangement=Arrangement.spacedBy(8.dp),verticalAlignment=Alignment.CenterVertically) {
  Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()),verticalAlignment=Alignment.CenterVertically,content=tools)
  save()
 }
}
