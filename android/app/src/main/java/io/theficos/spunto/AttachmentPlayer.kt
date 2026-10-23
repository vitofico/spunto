package io.theficos.spunto

import io.theficos.spunto.core.model.LocalOperationException

import android.content.Context
import android.media.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.lifecycle.*
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.theficos.spunto.plugins.dictation.AudioFocus
import io.theficos.spunto.data.local.*
import io.theficos.spunto.data.sync.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.security.MessageDigest
import io.theficos.spunto.core.design.SpuntoSpacing

private const val RECORDING_CACHE_LIMIT=40_000_000L
private val recordingCacheMutex=Mutex()
private fun pruneRecordingCache(directory:File,current:File) {
 val files=directory.listFiles().orEmpty().filter {it.isDirectory && it.name.matches(Regex("[a-f0-9]{64}"))}.flatMap {folder ->folder.listFiles().orEmpty().filter {file ->file.isFile && file.name.endsWith(".ogg") && runCatching {java.util.UUID.fromString(file.name.removeSuffix(".ogg")).toString()==file.name.removeSuffix(".ogg")}.getOrDefault(false)}}
 var size=files.sumOf {it.length()}
 files.filter {it!=current}.sortedBy {it.lastModified()}.forEach {file ->if(size>RECORDING_CACHE_LIMIT) {val bytes=file.length();if(file.delete()) size-=bytes}}
}

private class PlaybackAudioFocus(context:Context,attributes:AudioAttributes,lost:()->Unit):AudioFocus {
 private val manager=context.getSystemService(AudioManager::class.java)
 private val request=AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT).setAudioAttributes(attributes).setOnAudioFocusChangeListener {if(it<0) lost()}.build()
 override fun acquire()=manager.requestAudioFocus(request)==AudioManager.AUDIOFOCUS_REQUEST_GRANTED
 override fun release() {manager.abandonAudioFocusRequest(request)}
}
internal class RecordingPlayback(context:Context,focusFactory:((()->Unit)->AudioFocus)?=null,private val changed:(Boolean,Long)->Unit) {
 private val attributes=AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
 private val focus=focusFactory?.invoke {release()}?:PlaybackAudioFocus(context,attributes) {release()}
 private var player:MediaPlayer?=null
 private var prepared=false
 fun play(file:File,onError:()->Unit) {
  player?.let {
   if(!prepared) {release();onError();return}
   if(!focus.acquire()) {onError();return}
   try {it.start();changed(true,it.currentPosition.toLong())} catch(_:Exception) {release();onError()}
   return
  }
  if(!focus.acquire()) {onError();return}
  val local=MediaPlayer();player=local
  try {
   local.setAudioAttributes(attributes)
   local.setOnPreparedListener {if(player===it) {prepared=true;it.start();changed(true,0)}}
   local.setOnCompletionListener {if(player===it) {changed(false,it.duration.toLong());focus.release()}}
   local.setOnErrorListener {failed,_,_ ->if(player===failed) {release();onError()};true}
   local.setDataSource(file.absolutePath);local.prepareAsync()
  } catch(_:Exception) {release();onError()}
 }
 fun pause() {if(prepared) player?.let {if(it.isPlaying) it.pause();changed(false,it.currentPosition.toLong())};focus.release()}
 fun position():Long=runCatching {player?.currentPosition?.toLong()?:0}.getOrDefault(0)
 fun release() {runCatching {player?.release()};player=null;prepared=false;focus.release();changed(false,0)}
}

@Composable internal fun RecordingPlaybackControls(playing:Boolean,loading:Boolean,elapsed:Long,duration:Long,onToggle:()->Unit,onStop:()->Unit) {
 val context=LocalContext.current
 Row {
  TextButton(onClick=onToggle,enabled=!loading,modifier=Modifier.semantics {contentDescription=if(playing) context.getString(R.string.core_pause_recording) else context.getString(R.string.core_play_recording)}) {Text(if(loading) context.getString(R.string.core_loading) else if(playing) context.getString(R.string.core_pause) else context.getString(R.string.core_play))}
  TextButton(onClick=onStop,modifier=Modifier.semantics {contentDescription=context.getString(R.string.core_stop_playback)}) {Text(context.getString(R.string.core_stop))}
  Text("${recordingTime(elapsed)} / ${recordingTime(duration)}",Modifier.padding(SpuntoSpacing.s12).semantics {contentDescription=context.getString(R.string.core_playback_elapsed,recordingTime(elapsed),recordingTime(duration))})
 }
}

@Composable internal fun RecordingPlaybackLifecycle(playback:RecordingPlayback,onStop:()->Unit={}) {
 val owner=LocalLifecycleOwner.current
 val latestStop by rememberUpdatedState(onStop)
 DisposableEffect(playback,owner) {
  val observer=LifecycleEventObserver {_,event ->if(event==Lifecycle.Event.ON_STOP) {latestStop();playback.release()}}
  owner.lifecycle.addObserver(observer)
  onDispose {owner.lifecycle.removeObserver(observer);latestStop();playback.release()}
 }
}

/** Validated local bytes first. Remote bytes are fetched only on Play with header authentication. */
@Composable fun AttachmentPlayer(model:SpuntoViewModel,attachmentId:String,label:String?=null,attachment:AttachmentEntity?=null) {
 val context=LocalContext.current
 val auth by model.auth.collectAsState()
 val latestAuth by rememberUpdatedState(auth)
 val scope=rememberCoroutineScope()
 var playing by remember(attachmentId) {mutableStateOf(false)}
 var preparing by remember(attachmentId) {mutableStateOf(false)}
 var elapsed by remember(attachmentId) {mutableStateOf(0L)}
 var duration by remember(attachmentId) {mutableStateOf(attachment?.durationMs?:0L)}
 var playbackError by remember(attachmentId) {mutableStateOf("")}
 val playback=remember(attachmentId) {RecordingPlayback(context) {active,position ->playing=active;elapsed=position;preparing=false}}
 val loadRecording:suspend ()->RecordingPlaybackFile = {
  val config=latestAuth
  val state=model.repository.dao.state()
  val maximum=maxOf(state?.maxUploadBytes?:LEGACY_UPLOAD_LIMIT,attachment?.byteSize?:0)
  val root=config?.serverUrl?.trimEnd('/')?:state?.serverUrl?.trimEnd('/').orEmpty()
  require(attachment==null || attachment.serverUrl.isEmpty() || attachment.serverUrl.trimEnd('/')==root) {context.getString(R.string.core_recording_reconnect)}
  val file=withContext(Dispatchers.IO) {
   val local=attachment?.localPath?.takeIf {it.isNotEmpty()}?.let(::File)?.canonicalFile
   val owned=model.container.recordingDirectory.canonicalFile
   if(local?.isFile==true && local.parentFile==owned) local
   else {
    val selected=requireNotNull(config) {context.getString(R.string.core_recording_connect)}
    val id=attachment?.remoteId?:attachmentId
    require(java.util.UUID.fromString(id).toString()==id) {context.getString(R.string.core_recording_link_missing)}
    val instance=io.theficos.spunto.data.sync.replicaHash(listOf(selected.serverUrl.trimEnd('/'),selected.replicaId))
    recordingCacheMutex.withLock {
     val directory=File(context.cacheDir,"recordings")
     val folder=File(directory,instance).apply {mkdirs()}
     val cached=File(folder,"$id.ogg")
     val bounded=minOf(maximum,RECORDING_CACHE_LIMIT)
     if(cached.isFile && runCatching {inspectRecording(cached,bounded)}.isFailure) cached.delete()
     if(!cached.isFile) ApiClient(selected).downloadAttachment(id,cached,bounded)
     try {inspectRecording(cached,bounded)} catch(error:Exception) {cached.delete();throw error}
     cached.setLastModified(System.currentTimeMillis());pruneRecordingCache(directory,cached)
     cached
    }
   }
  }
  val metadata=withContext(Dispatchers.IO) {inspectRecording(file,maximum)}
  require(attachment==null || attachment.sha256.isEmpty() || attachment.sha256==metadata.sha256) {context.getString(R.string.core_recording_bytes_changed)}
  require(config==null || latestAuth==config) {context.getString(R.string.core_recording_sign_in)}
  RecordingPlaybackFile(file,metadata.durationMs)
 }
 val latestLoad by rememberUpdatedState(loadRecording)
 val loader=remember(attachmentId) {RecordingLoadCoordinator(scope,{latestLoad()},ready={recording ->
  duration=recording.durationMs;preparing=true
  playback.play(recording.file) {preparing=false;playbackError=context.getString(R.string.core_recording_play_failed)}
 },errorMessage={failure ->if(failure is LocalOperationException) localOperationMessage(context.resources,failure) else if(failure is IllegalArgumentException) failure.message.orEmpty() else context.getString(R.string.core_recording_load_failed)},release={playbackError="";preparing=false;playback.release()})}
 val load by loader.state.collectAsState()
 RecordingPlaybackLifecycle(playback) {loader.cancel();playbackError="";preparing=false}
 DisposableEffect(loader,auth?.serverUrl) {onDispose {loader.stop()}}
 LaunchedEffect(playing) {while(playing) {elapsed=playback.position();delay(250)}}
 fun play() {
  if(load.loading || preparing) return
  if(playing) {playback.pause();return}
  playbackError="";loader.start()
 }
 val error=load.error.ifEmpty {playbackError}
 Surface(shape=MaterialTheme.shapes.medium,color=MaterialTheme.colorScheme.surfaceContainer) {
  Column(Modifier.fillMaxWidth().padding(SpuntoSpacing.s12),verticalArrangement=Arrangement.spacedBy(SpuntoSpacing.s4)) {
   Text(label ?: context.getString(R.string.core_recording),style=MaterialTheme.typography.labelLarge)
   RecordingPlaybackControls(playing,load.loading || preparing,elapsed,duration,{play()},{loader.stop()})
   attachment?.let {item ->Text(when(item.status) {"local-only"->context.getString(R.string.core_recording_kept);"uploaded"->context.getString(R.string.core_recording_saved);"paused"->context.getString(R.string.core_recording_paused);"failed"->context.getString(R.string.core_recording_upload_failed);"uploading"->context.getString(R.string.core_recording_uploading);else->context.getString(R.string.core_recording_waiting)},style=MaterialTheme.typography.bodySmall)}
   if(error.isNotEmpty()) Text(error,style=MaterialTheme.typography.bodySmall)
  }
 }
}
