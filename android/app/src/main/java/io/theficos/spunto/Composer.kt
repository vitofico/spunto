@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package io.theficos.spunto

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.ReportDrawnWhen
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontStyle
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

@Composable fun Composer(model:SpuntoViewModel,id:String="capture",initial:String="",draftId:String=id,autoFocus:Boolean=true,autoDictate:Boolean=false,canSaveEmpty:Boolean=false,onTextChanged:(String)->Unit={},onFinishReady:((suspend ()->Unit)?)->Unit={},allowVoiceCapture:Boolean=draftId=="capture" || id!="capture",onPlainCaptureSave:(suspend (String,String?)->String?)?=null,onRecordingSaved:suspend (String)->Unit={},startNewParagraph:Boolean=false,showSaveAction:Boolean=true,boxed:Boolean=id=="capture",tools:Boolean=true,placeholder:String="What's on your mind?",trailing:(@Composable ()->Unit)?=null,caret:Int?=null,onSave:suspend (String)->Unit) {
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
 // Focus once per page visit: returning from a note, a rotation or a permission dialog restores this flag, so the keyboard stays down.
 var focusSpent by rememberSaveable(id,draftId) { mutableStateOf(false) }
 ReportDrawnWhen { ready && (autoDictate || !autoFocus || focused || focusSpent) }
 var images by remember(id,draftId) { mutableStateOf(emptyList<Pair<java.io.File,String>>()) }
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
   model.repository.dao.unsavedDraft(draftId,id) to if(id=="capture") null else model.repository.dao.get(id)?.body
  }
  val body=draft?.body ?: ((current ?: initial)+if(startNewParagraph && current!=null) "\n\n" else "")
  // The caret opens where the reader tapped (A5) unless a draft changed the text; otherwise at the end.
  value=TextFieldValue(body,TextRange(caret?.takeIf { body==(current ?: initial) }?.coerceIn(0,body.length) ?: body.length))
  committed=current ?: initial
  ready=true
  androidx.compose.runtime.withFrameNanos { }
  if(autoDictate && mic) micTap() else if(autoFocus && !focusSpent) { focusSpent=true; focus.requestFocus(); keyboard?.show() }
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
 val latestSaving by rememberUpdatedState(saving)
 val latestPendingRecording by rememberUpdatedState(pendingRecording)
 val latestRecoveries by rememberUpdatedState(recoveries)
 val latestRecordingSaved by rememberUpdatedState(onRecordingSaved)
 val owner=LocalLifecycleOwner.current
 LaunchedEffect(id,draftId) { if(id!="capture") while(true) { delay(60_000); if(ready && !saved && !latestSaving && latestText!=committed) { val body=latestText;val write=model.repository.editorWrite(lease,body);if(commitEditor(write)) committed=body } } }
 DisposableEffect(editorLease) {
  onFinishReady {
   check(latestReady) {"Wait for this note to finish loading."}
   check(!startingVoice && !saving) {"Wait for the current recording or save to finish."}
   check(latestRecoveries.isEmpty() || latestPendingRecording!=null || voice.state.value.recording!=null) {"Recover or discard the unfinished recording before finishing."}
   val tappedRevision=ownerRevision
   closing=true
   saving=true
   try {
    val active=session.state.value.phase in setOf(DictationPhase.Starting,DictationPhase.Listening,DictationPhase.Stopping)
    val keepRecording=recordingActive(voice.state.value.phase) || voice.state.value.recording!=null || latestPendingRecording!=null
    voice.onForegroundChanged(false);if(voice.hasSession) voice.stop()
    val body=if(voice.hasSession) voice.textSnapshot().text else if(active) session.stopForSave() else latestText
    if(keepRecording) {
     check(ownerRevision==tappedRevision) {"The editor changed. Save the current text with this recording."}
     val clipId=recoveredId?:voice.state.value.recording?.id?:latestPendingRecording?.id?:error("Recording could not be finalized. Recover or discard it before finishing.")
     val noteId=voice.retain(clipId,model.repository.editorWrite(editorLease,body))
     retainedId=clipId;recoveredId=null;saved=true
     latestRecordingSaved(noteId)
    } else {
     if(latestReady && !saved) check(model.repository.finishEditor(model.repository.editorWrite(editorLease,body))) {"Editor is no longer active."}
     else model.repository.abandonEditor(editorLease)
     saved=true
    }
   } catch(error:Exception) {closing=false;voice.onForegroundChanged(owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED));throw error}
   finally {saving=false}
  }
  onDispose {
   onFinishReady(null)
   if(editorLease!==latestLease) model.repository.abandonEditor(editorLease)
   else if(latestReady && !saved) {
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
 DisposableEffect(owner) { val observer=LifecycleEventObserver { _,event -> if(event==Lifecycle.Event.ON_STOP) {session.stopAndKeep();voice.onForegroundChanged(false)} else if(event==Lifecycle.Event.ON_START) voice.onForegroundChanged(true) }; owner.lifecycle.addObserver(observer); onDispose { owner.lifecycle.removeObserver(observer); session.stopAndKeep();voice.onForegroundChanged(false) } }
 val listening=dictation.phase in setOf(DictationPhase.Starting,DictationPhase.Listening,DictationPhase.Stopping)
 fun ownerValue(new:TextFieldValue) {
  ownerRevision++;if(listening) session.stopAndKeep()
  voice.ownerEdit(new.text,new.selection.start,new.selection.end)
  if(saving && !voice.hasSession) {val write=model.repository.editorWrite(lease,new.text);model.container.scope.launch {model.repository.persistEditor(write)}}
  saved=false;value=new
 }
 val prefix=Regex("(?:^|\\s)#([\\p{L}\\p{N}_/-]*)$").find(value.text.take(value.selection.start))?.groupValues?.get(1)
 fun insertAtCaret(text:String,at:Int=value.selection.min,replaceTo:Int=value.selection.max) { ownerValue(TextFieldValue(value.text.replaceRange(at,replaceTo,text),TextRange(at+text.length)));focus.requestFocus() }
 // "#" lands at the caret and opens label suggestions; a checklist item starts on its own line.
 fun insertLabel() { val before=value.text.getOrNull(value.selection.min-1);insertAtCaret(if(before==null || before.isWhitespace() || before in "([") "#" else " #") }
 fun insertTask() {
  val caret=value.selection.max;val lineStart=value.text.lastIndexOf('\n',caret-1)+1;val lineEnd=value.text.indexOf('\n',caret).let {if(it<0) value.text.length else it}
  if(value.text.substring(lineStart,lineEnd).isBlank()) insertAtCaret("- [ ] ",lineStart,lineEnd) else insertAtCaret("\n- [ ] ",lineEnd,lineEnd)
 }
 suspend fun attachImage(uri:Uri) {
  val mime=context.contentResolver.getType(uri)?.takeIf {it.startsWith("image/")} ?: "image/jpeg"
  val file=withContext(Dispatchers.IO) {
   val target=java.io.File(model.container.privateDirectory("pending-attachments"),java.util.UUID.randomUUID().toString())
   try {requireNotNull(context.contentResolver.openInputStream(uri)) {"This image is no longer available."}.use {input ->target.outputStream().use {input.copyTo(it)}};target} catch(error:Exception) {target.delete();throw error}
  }
  try {
   if(file.length() !in 1..IMAGE_IMPORT_LIMIT) {file.delete();throw IllegalArgumentException("Choose an image of at most 5 MB.")}
   if(id=="capture") images=images+(file to mime)
   else {val attachment=model.repository.queueImage(id,file.absolutePath,mime);val text=value.text.trimEnd()+"\n\n[image:$attachment]";ownerValue(TextFieldValue(text,TextRange(text.length)))}
  } catch(error:Exception) {if(id=="capture") file.delete();throw error}
 }
 val pickImage=rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) {uri ->
  if(uri!=null) scope.launch {try {attachImage(uri)} catch(cancelled:CancellationException) {throw cancelled} catch(error:Exception) {model.message.value=(error as? IllegalArgumentException)?.message ?: "Couldn't add this image. Your text is kept."}}
 }
 fun saveNow() {
    if(startingVoice || saving) return
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
      if(body.isNotBlank() || canSaveEmpty || images.isNotEmpty()) {
       if(id!="capture") check(model.repository.finishEditor(model.repository.editorWrite(lease,body))) { "Editor is no longer active." }
       val dictated=dictatedText || listening && body.isNotBlank() && body!=dictationInitial
       if(id=="capture" && onPlainCaptureSave!=null) {
        val noteId=onPlainCaptureSave(body,if(dictated) "dictation" else null)
        if(noteId!=null) images.forEach { (file,mime) -> model.repository.queueImage(noteId,file.absolutePath,mime) }
        images=emptyList()
       } else onSave(body)
       if(id=="capture") model.container.scope.launch { runCatching { model.repository.clearDraft(draftId,body) } }
       if(value.text==body) { saved=true;value=TextFieldValue("");dictatedText=false }
       if(autoFocus) focus.requestFocus()
      }
     }
    } catch(cancelled:CancellationException) {throw cancelled}
    catch(error:Exception) { model.message.value=if(error is IllegalArgumentException || error is IllegalStateException) error.message.orEmpty() else "Couldn't save on this phone. Your draft is still here." }
    finally { saving=false;retaining=false }
   }
 }
 val canSave=ready && !closing && !saving && (value.text.isNotBlank() || images.isNotEmpty() || listening || recordingActive(recording.phase) || pendingRecording!=null || canSaveEmpty)
 val switchLanguage:()->Unit={scope.launch {
  val restart=listening || dictation.phase==DictationPhase.NeedsLanguage;session.stopAndKeep()
  language=when(language) {"it-IT" -> "en-US";"en-US" -> if(autoAvailable) "auto" else "it-IT";else -> "it-IT"};device.update(language=language)
  if(restart) start() else session.clearNotice()
 }}
 Column(verticalArrangement=Arrangement.spacedBy(SpuntoSpacing.s8)) {
  Surface(shape=MaterialTheme.shapes.medium,color=if(boxed) MaterialTheme.colorScheme.surfaceContainer else Color.Transparent) {
   Column(Modifier.fillMaxWidth().padding(if(boxed) SpuntoSpacing.s16 else 0.dp),verticalArrangement=Arrangement.spacedBy(SpuntoSpacing.s8)) {
    Box(Modifier.fillMaxWidth().heightIn(min=if(boxed) 96.dp else 48.dp)) {
     if(value.text.isEmpty()) Text(placeholder,color=SpuntoColors.ink3,style=SpuntoType.reading.copy(fontStyle=FontStyle.Italic))
     BasicTextField(value=value,enabled=ready && !closing && (id=="capture" || !saving),onValueChange={ownerValue(it)},textStyle=SpuntoType.reading.copy(color=MaterialTheme.colorScheme.onSurface),cursorBrush=SolidColor(MaterialTheme.colorScheme.primary),modifier=Modifier.fillMaxWidth().focusRequester(focus).onFocusChanged { focused=it.isFocused }.semantics { contentDescription="Note text" },visualTransformation=MarkdownEditorHighlight(MaterialTheme.colorScheme.primary,MaterialTheme.colorScheme.surfaceContainer,if(voice.hasSession) recording.partialSpan else dictation.partialSpan,MaterialTheme.colorScheme.onSurfaceVariant))
    }
    fun openSettings() {context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:${context.packageName}")))}
    // One message and one way forward per state, inside the card (03 A13).
    if(recordingNeedsPermission) DictationNotice(if(permanent) "Microphone access is off for spunto." else "Recording needs the microphone. Audio stays on this phone.",if(permanent) "Open settings" else "Allow") {permissionAction="recording";if(permanent) openSettings() else permission.launch(Manifest.permission.RECORD_AUDIO)}
    if(dictationEnabled && !voice.hasSession) when {
     dictation.phase==DictationPhase.NeedsPermission -> DictationNotice(if(permanent) "Microphone access is off for spunto." else "Dictation needs the microphone. Audio stays on this phone.",if(permanent) "Open settings" else "Allow") {if(permanent) openSettings() else {permissionAction="dictation";permission.launch(Manifest.permission.RECORD_AUDIO)}}
     dictation.phase==DictationPhase.NeedsLanguage -> DictationNotice(dictation.message,"Download") {scope.launch {PlatformSpeechEngine.download(context,language)}}
     listening || dictation.message.isNotEmpty() && (dictation.phase!=DictationPhase.Idle || dictation.message.startsWith("Didn't catch")) -> DictationNotice(dictation.message)
    }
   }
  }
  if(images.isNotEmpty()) Row(verticalAlignment=Alignment.CenterVertically) {
   Text(if(images.size==1) "1 image to attach" else "${images.size} images to attach",Modifier.weight(1f),style=MaterialTheme.typography.bodySmall,color=SpuntoColors.ink3)
   TextButton(onClick={images.forEach {it.first.delete()};images=emptyList()},modifier=Modifier.heightIn(min=48.dp)) {Text("Remove")}
  }
  if(allowVoiceCapture && voiceFacts.captureAllowed && android.os.Build.VERSION.SDK_INT<33) Text("Voice recording needs Android 13 or newer. You can still type, or use dictation when available.",style=MaterialTheme.typography.bodySmall)
  VoiceControls(recording,voiceFacts.transcriptionActive,pendingRecording,recoveries,ready && !closing && !saving,onStop={voice.stopCapture()},onDiscard={clip ->scope.launch {try {voice.discard(clip);if(recoveredId==clip) recoveredId=null} catch(cancelled:CancellationException) {throw cancelled} catch(error:Exception) {model.message.value="Couldn't discard this clip. It is kept on this phone."}}},onRecover={clip ->scope.launch {try {if(journal.firstOrNull {it.id==clip}?.state !in setOf("ready","recoverable")) voice.recover(clip);recoveredId=clip} catch(cancelled:CancellationException) {throw cancelled} catch(error:Exception) {model.message.value="Couldn't recover this clip. Your recording and text are kept."}}},saveAction=if(showSaveAction) "Save" else "Done")
  pendingRecording?.let {row ->AttachmentPlayer(model,row.id,"Recording ${recordingTime(row.durationMs)}",AttachmentEntity(row.id,row.noteId.orEmpty(),row.finalPath,"audio/ogg",kind="recording",durationMs=row.durationMs,byteSize=row.byteSize,sha256=row.sha256,serverUrl=row.serverUrl,status="local-only"))}
 }
 // Hiding the keyboard ends typing in the capture composer: focus goes, so its bar undocks and the list
 // stops holding the composer in place (a focused item stays composed, which kept day headers from sticking).
 val imeVisible=WindowInsets.isImeVisible;val focusManager=LocalFocusManager.current;var keyboardShown by remember { mutableStateOf(false) }
 LaunchedEffect(imeVisible) { if(keyboardShown && !imeVisible && focused && id=="capture" && !listening) focusManager.clearFocus();keyboardShown=imeVisible }
 val docked=id!="capture" || canSaveEmpty || focused || value.text.isNotEmpty() || images.isNotEmpty() || listening || recordingActive(recording.phase) || pendingRecording!=null || dictation.phase==DictationPhase.NeedsLanguage
 // A label already typed in full offers nothing to complete, so the tools stay.
 val suggestions=prefix?.let {labelCounts.filter {label -> label.key.startsWith(it,true) && !label.path.equals(it,true)}.sortedByDescending {it.count}.take(5)}.orEmpty()
 val newLabel=prefix?.takeIf {it.isNotEmpty() && labelCounts.none {label -> label.path.equals(it,true)}}
 if(docked) BottomBar {
  ComposerToolbar(tools={
   if(prefix!=null && (suggestions.isNotEmpty() || newLabel!=null)) {
    suggestions.forEach {label -> SuggestionChip(onClick={val caret=value.selection.start;insertAtCaret(label.path+" ",caret-prefix.length,caret)},label={Text("#${label.path}")})}
    if(newLabel!=null) Text("New label #$newLabel",Modifier.padding(horizontal=SpuntoSpacing.s8),style=MaterialTheme.typography.labelMedium,color=SpuntoColors.ink3)
   } else if(listening || dictation.phase==DictationPhase.NeedsLanguage) {
    IconButton(onClick={session.stopAndKeep();focus.requestFocus();keyboard?.show()}) {Icon(painterResource(R.drawable.ic_keyboard),contentDescription="Back to typing",modifier=Modifier.size(24.dp))}
    TextButton(onClick=switchLanguage,modifier=Modifier.heightIn(min=48.dp).semantics {contentDescription="Dictation language, ${languageName(language)}. Switch"}) {Text(languageName(language))}
    if(listening) ComposerDictationAction(true,true,startingVoice) {session.stopAndKeep()}
   } else if(tools) {
    IconButton(onClick={insertLabel()},enabled=ready && !closing) {Text("#",style=MaterialTheme.typography.titleLarge.copy(fontFamily=uiFont()),modifier=Modifier.semantics {contentDescription="Add a label"})}
    IconButton(onClick={insertTask()},enabled=ready && !closing) {Icon(painterResource(R.drawable.ic_checklist),contentDescription="Add a checklist item",modifier=Modifier.size(24.dp))}
    if(id!="capture" || onPlainCaptureSave!=null) IconButton(onClick={pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))},enabled=ready && !closing && !saving) {Icon(painterResource(R.drawable.ic_image),contentDescription="Add an image",modifier=Modifier.size(24.dp))}
    if(mic) ComposerDictationAction(ready && !closing && !voice.hasSession && pendingRecording==null,false,startingVoice) {micTap()}
    if(allowVoiceCapture && voiceFacts.captureAllowed && android.os.Build.VERSION.SDK_INT>=33) TextButton(onClick={recordTap()},enabled=ready && !closing && !saving && !startingVoice && !listening && !recordingActive(recording.phase) && pendingRecording==null && recoveries.isEmpty(),modifier=Modifier.heightIn(min=48.dp)) {Text("Record")}
   }
  },save={
   if(showSaveAction) ComposerSaveAction(enabled=canSave,saving=saving,startingVoice=startingVoice) {saveNow()} else trailing?.invoke()
  })
 }
}
private fun languageName(tag:String)=when(tag) {"it-IT" -> "Italian";"en-US" -> "English";else -> "Auto"}
@Composable private fun DictationNotice(message:String,action:String?=null,onAction:()->Unit={}) {
 Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(SpuntoSpacing.s8),verticalAlignment=Alignment.CenterVertically) {
  Text(message,Modifier.weight(1f),style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
  if(action!=null) TextButton(onClick=onAction,modifier=Modifier.heightIn(min=48.dp)) {Text(action)}
 }
}

/** Keep the primary action visible while optional capture tools can scroll. */
@Composable internal fun ComposerToolbar(tools:@Composable RowScope.()->Unit,save:@Composable ()->Unit) {
 Row(Modifier.fillMaxWidth().padding(start=SpuntoSpacing.s4,end=SpuntoSpacing.s8,bottom=SpuntoSpacing.s8),horizontalArrangement=Arrangement.spacedBy(SpuntoSpacing.s8),verticalAlignment=Alignment.CenterVertically) {
  Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()),verticalAlignment=Alignment.CenterVertically,content=tools)
  save()
 }
}
