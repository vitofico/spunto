package io.theficos.spunto

import android.os.Build
import androidx.test.platform.app.InstrumentationRegistry
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.*
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.theficos.spunto.core.design.SpuntoTheme
import io.theficos.spunto.plugins.dictation.AudioFocus
import io.theficos.spunto.plugins.dictation.voice.*
import io.theficos.spunto.data.local.inspectRecording
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import java.io.File
import kotlin.math.sin

/** Generated PCM file only; no microphone, owner database, token or network. */
@androidx.test.filters.SdkSuppress(minSdkVersion=33)
class RecordingPlaybackTest {
 @get:Rule val timeout=org.junit.rules.Timeout(10,java.util.concurrent.TimeUnit.MINUTES)
 @get:Rule val compose=createComposeRule()
 private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
 private class Focus:AudioFocus {
  var allowed=true;var releases=0;var lost:()->Unit={}
  override fun acquire()=allowed
  override fun release() {releases++}
 }
 private class Owner:LifecycleOwner {val registry=LifecycleRegistry(this);override val lifecycle:Lifecycle get()=registry}
 private suspend fun fixture():File {
  val file=File(context.cacheDir,"playback-controls-synthetic-${System.nanoTime()}.ogg")
  val encoder=AndroidOggEncoder()
  try {
   encoder.start(file)
   repeat(100) {frame ->val samples=ShortArray(1600) {index ->(sin(2*Math.PI*440*(frame*1600+index)/RECORDING_RATE)*4000).toInt().toShort()};encoder.write(samples,samples.size,frame*100_000L)}
   encoder.finish();inspectRecording(file,5_000_000);return file
  } catch(error:Exception) {file.delete();throw error}
  finally {encoder.close()}
 }
 @Test fun nativePlayerStartsValidatedSyntheticOggAndReleasesOnLeave()=runBlocking {
  if(Build.VERSION.SDK_INT<33) {Assume.assumeTrue(false);return@runBlocking}
  val context=InstrumentationRegistry.getInstrumentation().targetContext
  val file=File(context.cacheDir,"playback-synthetic-${System.nanoTime()}.ogg")
  val encoder=AndroidOggEncoder()
  val started=CompletableDeferred<Unit>()
  var active=false
  val player=withContext(Dispatchers.Main) {RecordingPlayback(context) {playing,_ ->active=playing;if(playing) started.complete(Unit)}}
  try {
   encoder.start(file)
   repeat(20) {frame ->val samples=ShortArray(1600) {index ->(sin(2*Math.PI*440*(frame*1600+index)/RECORDING_RATE)*4000).toInt().toShort()};encoder.write(samples,samples.size,frame*100_000L)}
   encoder.finish();encoder.close()
   inspectRecording(file,5_000_000)
   withContext(Dispatchers.Main) {player.play(file) {started.completeExceptionally(IllegalStateException("Synthetic playback failed"))}}
   withTimeout(10_000) {started.await()}
   withContext(Dispatchers.Main) {assertTrue(active);player.release();assertFalse(active);assertEquals(0L,player.position())}
  } finally {withContext(Dispatchers.Main) {player.release()};encoder.close();file.delete()}
 }
 @Test fun renderedStopLifecycleAndFocusLossResetPlayerWithoutDeletingBytesOrResuming()=runBlocking {
  if(Build.VERSION.SDK_INT<33) {Assume.assumeTrue(false);return@runBlocking}
  val file=fixture();val bytes=file.length();val focus=Focus()
  var playing by mutableStateOf(false);var loading by mutableStateOf(false);var elapsed by mutableStateOf(0L)
  val owner=withContext(Dispatchers.Main) {Owner().also {it.registry.currentState=Lifecycle.State.RESUMED}}
  val player=withContext(Dispatchers.Main) {RecordingPlayback(context,focusFactory={lost ->focus.lost=lost;focus}) {active,position ->playing=active;loading=false;elapsed=position}}
  try {
   compose.setContent {SpuntoTheme {CompositionLocalProvider(LocalLifecycleOwner provides owner) {
    RecordingPlaybackLifecycle(player) {loading=false}
    LaunchedEffect(playing) {while(playing) {elapsed=player.position();delay(50)}}
    RecordingPlaybackControls(playing,loading,elapsed,10_000,{loading=true;player.play(file) {loading=false}},{loading=false;player.release()})
   }}}
   compose.onNodeWithContentDescription("Play recording").performClick()
   compose.waitUntil(10_000) {playing}
   compose.onNodeWithContentDescription("Stop playback").performClick()
   compose.onNodeWithContentDescription("Play recording").assertIsEnabled()
   compose.onNodeWithContentDescription("Playback elapsed 0:00, duration 0:10").assertExists()
   assertEquals(bytes,file.length())
   compose.onNodeWithContentDescription("Play recording").performClick()
   compose.waitUntil(10_000) {playing}
   compose.runOnIdle {owner.registry.currentState=Lifecycle.State.CREATED}
   compose.onNodeWithContentDescription("Play recording").assertIsEnabled()
   compose.runOnIdle {owner.registry.currentState=Lifecycle.State.RESUMED;assertFalse(playing);assertEquals(0L,player.position())}
   compose.onNodeWithContentDescription("Play recording").performClick()
   compose.waitUntil(10_000) {playing}
   compose.runOnIdle {focus.lost();assertFalse(playing);assertEquals(0L,player.position())}
   compose.onNodeWithContentDescription("Play recording").assertIsEnabled()
   assertTrue(focus.releases>=3);assertTrue(file.exists());assertEquals(bytes,file.length())
  } finally {withContext(Dispatchers.Main) {owner.registry.currentState=Lifecycle.State.DESTROYED;player.release()};file.delete()}
 }
 @Test fun deniedFocusAfterRenderedPauseCompletesErrorAndReenablesPlay()=runBlocking {
  if(Build.VERSION.SDK_INT<33) {Assume.assumeTrue(false);return@runBlocking}
  val file=fixture();val focus=Focus()
  var playing by mutableStateOf(false);var loading by mutableStateOf(false);var elapsed by mutableStateOf(0L);var error by mutableStateOf("")
  val player=withContext(Dispatchers.Main) {RecordingPlayback(context,focusFactory={lost ->focus.lost=lost;focus}) {active,position ->playing=active;loading=false;elapsed=position}}
  try {
   compose.setContent {SpuntoTheme {Column {
    RecordingPlaybackLifecycle(player) {loading=false}
    RecordingPlaybackControls(playing,loading,elapsed,10_000,{if(playing) player.pause() else {loading=true;player.play(file) {loading=false;error="Audio focus unavailable"}}},{loading=false;player.release()})
    if(error.isNotEmpty()) Text(error)
   }}}
   compose.onNodeWithContentDescription("Play recording").performClick()
   compose.waitUntil(10_000) {playing}
   compose.onNodeWithContentDescription("Pause recording").performClick()
   compose.runOnIdle {focus.allowed=false}
   compose.onNodeWithContentDescription("Play recording").performClick()
   compose.onNodeWithText("Audio focus unavailable").assertExists()
   compose.onNodeWithContentDescription("Play recording").assertIsEnabled()
   compose.runOnIdle {assertFalse(playing);assertFalse(loading)}
   assertTrue(file.exists())
  } finally {withContext(Dispatchers.Main) {player.release()};file.delete()}
 }
}
