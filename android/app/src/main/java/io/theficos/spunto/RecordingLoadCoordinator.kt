package io.theficos.spunto

import androidx.annotation.MainThread
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.File

data class RecordingPlaybackFile(val file:File,val durationMs:Long)
data class RecordingLoadState(val loading:Boolean=false,val error:String="")

/** Main owns each attempt; cancelled IO may finish but cannot publish into a later attempt. */
class RecordingLoadCoordinator(
 private val scope:CoroutineScope,
 private val load:suspend ()->RecordingPlaybackFile,
 private val ready:(RecordingPlaybackFile)->Unit,
 private val errorMessage:(Exception)->String,
 private val release:()->Unit,
) {
 private val mutable=MutableStateFlow(RecordingLoadState())
 val state:StateFlow<RecordingLoadState> = mutable.asStateFlow()
 private var generation=0L
 private var active:Job?=null
 private fun owns(token:Long,job:Job)=generation==token && active===job

 @MainThread fun start():Boolean {
  if(mutable.value.loading) return false
  val token=++generation
  lateinit var work:Job
  work=scope.launch(Dispatchers.Main.immediate,start=CoroutineStart.LAZY) {
   try {
    val recording=load()
    currentCoroutineContext().ensureActive()
    if(owns(token,work)) ready(recording)
   } catch(cancelled:CancellationException) {throw cancelled}
   catch(error:Exception) {
    if(owns(token,work)) {release();mutable.value=mutable.value.copy(error=errorMessage(error))}
   } finally {
    if(owns(token,work)) {active=null;mutable.value=mutable.value.copy(loading=false)}
   }
  }
  active=work
  mutable.value=RecordingLoadState(loading=true)
  if(!work.start()) {if(owns(token,work)) {active=null;mutable.value=RecordingLoadState()};return false}
  return true
 }
 @MainThread fun cancel() {
  generation++
  val work=active;active=null
  mutable.value=RecordingLoadState()
  work?.cancel()
 }
 @MainThread fun stop() {cancel();release()}
}
