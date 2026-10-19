package io.theficos.spunto

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import io.theficos.spunto.core.design.SpuntoTheme
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.io.IOException

/** Real load coordinator and rendered controls, held fake IO, synthetic bytes; no app DB or network. */
class RecordingLoadCoordinatorTest {
 @get:Rule val timeout=org.junit.rules.Timeout(10,java.util.concurrent.TimeUnit.MINUTES)
 @get:Rule val compose=createComposeRule()
 private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
 private val retainedBytes=byteArrayOf(1,2,3,4)
 private val cachedBytes=byteArrayOf(5,6,7,8)

 @Test fun lateCancelledLoadCannotClearNewLoadingOrEscapeAnotherStop()=runBlocking {
  val folder=File(context.cacheDir,"load-generation-${System.nanoTime()}").apply {mkdirs()}
  val retained=File(folder,"retained.ogg").apply {writeBytes(retainedBytes)}
  val cached=File(folder,"cached.ogg").apply {writeBytes(cachedBytes)}
  val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
  val gates=Array(3) {CompletableDeferred<Unit>()}
  val entered=Array(3) {CompletableDeferred<Job>()}
  gates[2].complete(Unit)
  var attempts=0;var plays=0;var releases=0
  var playing by mutableStateOf(false);var elapsed by mutableStateOf(0L)
  val owner=withContext(Dispatchers.Main) {RecordingLoadCoordinator(scope,load={
   val attempt=attempts++
   entered[attempt].complete(currentCoroutineContext()[Job]!!)
   // Models an inspect/download adapter that cannot stop until its current blocking read returns.
   withContext(NonCancellable) {gates[attempt].await()}
   RecordingPlaybackFile(cached,60_000)
  },ready={plays++;playing=true;elapsed=1000},errorMessage={it.message.orEmpty()},release={releases++;playing=false;elapsed=0})}
  try {
   compose.setContent {SpuntoTheme {val state by owner.state.collectAsState();Column {
    RecordingPlaybackControls(playing,state.loading,elapsed,60_000,{if(playing) playing=false else owner.start()},{owner.stop()})
    if(state.error.isNotEmpty()) Text(state.error)
   }}}
   compose.onNodeWithContentDescription("Play recording").performClick()
   val old=withTimeout(10_000) {entered[0].await()}
   compose.onNodeWithContentDescription("Stop playback").performClick()
   compose.onNodeWithContentDescription("Play recording").performClick()
   val current=withTimeout(10_000) {entered[1].await()}
   gates[0].complete(Unit);old.join()
   compose.onNodeWithContentDescription("Play recording").assertIsNotEnabled()
   compose.runOnIdle {assertTrue(owner.state.value.loading);assertFalse(owner.start());assertEquals(2,attempts);assertEquals(0,plays)}
   compose.onNodeWithContentDescription("Stop playback").performClick()
   gates[1].complete(Unit);current.join()
   compose.onNodeWithContentDescription("Play recording").assertIsEnabled()
   compose.runOnIdle {assertFalse(playing);assertFalse(owner.state.value.loading);assertEquals(0,plays);assertTrue(releases>=2)}
   // A later explicit gesture remains usable after both cancelled attempts have ended.
   compose.onNodeWithContentDescription("Play recording").performClick()
   val fresh=withTimeout(10_000) {entered[2].await()};fresh.join()
   compose.onNodeWithContentDescription("Pause recording").assertIsEnabled()
   compose.onNodeWithContentDescription("Stop playback").performClick()
   compose.runOnIdle {assertEquals(1,plays);assertFalse(playing);assertEquals(0L,elapsed)}
   assertArrayEquals(retainedBytes,retained.readBytes());assertArrayEquals(cachedBytes,cached.readBytes())
  } finally {withContext(NonCancellable) {withContext(Dispatchers.Main) {owner.stop();gates.forEach {it.complete(Unit)};scope.cancel()};scope.coroutineContext[Job]!!.join();folder.deleteRecursively()}}
 }
 @Test fun lateOldFailureCannotReplaceCurrentLoadingOrItsErrorAndEnabledState()=runBlocking {
  val folder=File(context.cacheDir,"load-errors-${System.nanoTime()}").apply {mkdirs()}
  val retained=File(folder,"retained.ogg").apply {writeBytes(retainedBytes)}
  val cached=File(folder,"cached.ogg").apply {writeBytes(cachedBytes)}
  val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
  val gates=Array(2) {CompletableDeferred<Unit>()}
  val entered=Array(2) {CompletableDeferred<Job>()}
  var attempts=0;var plays=0;var releases=0
  val owner=withContext(Dispatchers.Main) {RecordingLoadCoordinator(scope,load={
   val attempt=attempts++
   entered[attempt].complete(currentCoroutineContext()[Job]!!)
   withContext(NonCancellable) {
    gates[attempt].await()
    if(attempt==0) throw IOException("Old download failed")
    throw IllegalArgumentException("Current recording unavailable")
   }
  },ready={plays++},errorMessage={it.message.orEmpty()},release={releases++})}
  try {
   compose.setContent {SpuntoTheme {val state by owner.state.collectAsState();Column {
    RecordingPlaybackControls(false,state.loading,0,60_000,{owner.start()},{owner.stop()})
    if(state.error.isNotEmpty()) Text(state.error)
   }}}
   compose.onNodeWithContentDescription("Play recording").performClick()
   val old=withTimeout(10_000) {entered[0].await()}
   compose.onNodeWithContentDescription("Stop playback").performClick()
   compose.onNodeWithContentDescription("Play recording").performClick()
   val current=withTimeout(10_000) {entered[1].await()}
   gates[0].complete(Unit);old.join()
   compose.onNodeWithText("Old download failed").assertDoesNotExist()
   compose.onNodeWithContentDescription("Play recording").assertIsNotEnabled()
   compose.runOnIdle {assertTrue(owner.state.value.loading);assertEquals("",owner.state.value.error)}
   gates[1].complete(Unit);current.join()
   compose.onNodeWithText("Current recording unavailable").assertExists()
   compose.onNodeWithContentDescription("Play recording").assertIsEnabled()
   compose.runOnIdle {assertFalse(owner.state.value.loading);assertEquals(0,plays);assertEquals(2,releases)}
   assertArrayEquals(retainedBytes,retained.readBytes());assertArrayEquals(cachedBytes,cached.readBytes())
  } finally {withContext(NonCancellable) {withContext(Dispatchers.Main) {owner.stop();gates.forEach {it.complete(Unit)};scope.cancel()};scope.coroutineContext[Job]!!.join();folder.deleteRecursively()}}
 }
}
