package io.theficos.spunto.plugins.dictation.voice

import io.theficos.spunto.plugins.dictation.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

class RecordingSessionTest {
 @get:Rule val timeout=org.junit.rules.Timeout(10,java.util.concurrent.TimeUnit.MINUTES)
 private val folder=kotlin.io.path.createTempDirectory("synthetic-session").toFile()
 private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
 @After fun close() {scope.cancel();folder.listFiles()?.forEach {it.delete()};folder.delete()}
 private class Focus:AudioFocus {var releases=0;override fun acquire()=true;override fun release() {releases++}}
 private class Source(private val chunks:Int=2,private val delayMs:Long=0):PcmSource {
  var reads=0;var closed=false;var afterChunk:()->Unit={};private val stopped=CompletableDeferred<Unit>()
  override fun start()=Unit
  override suspend fun read(buffer:ShortArray):Int {
   if(reads>=chunks) {stopped.await();return -1}
   if(delayMs>0) delay(delayMs)
   repeat(buffer.size) {buffer[it]=((it%100)*30).toShort()};reads++;if(reads==chunks) afterChunk();return buffer.size
  }
  override fun stop() {stopped.complete(Unit)}
  override fun close() {closed=true;stop()}
 }
 private class Encoder:RecordingEncoder {
  lateinit var file:File;val samples=mutableListOf<List<Short>>();val times=mutableListOf<Long>();var closed=false;var finished=false;var fail=false
  override fun start(file:File) {this.file=file;file.writeBytes("fake-codec".toByteArray())}
  override suspend fun write(samples:ShortArray,count:Int,timeUs:Long) {if(fail) throw RecordingFailure("encoder_failed");this.samples+=samples.take(count);times+=timeUs;file.appendBytes(ByteArray(count*2))}
  override suspend fun finish() {finished=true}
  override fun close() {closed=true}
 }
 @Test fun sameSamplesReachCodecAndInjectedRecognizerWithMonotonicFrameTimes()=runBlocking {
  val source=Source();val encoder=Encoder();val focus=Focus();val received=mutableListOf<List<Short>>();var outcomes=0
  val recognizer=object:PcmRecognition {override fun offer(samples:ShortArray,count:Int):Boolean {received+=samples.take(count);return true};override suspend fun finish()=Unit;override fun close()=Unit}
  lateinit var session:RecordingSession
  session=RecordingSession(scope,{source},{encoder},focus,PcmRecognitionFactory {_,events ->events(SpeechEvent.Final("ciao mondo"));recognizer},{outcomes++},isEnabled={true})
  source.afterChunk={session.onForegroundChanged(false)}
  session.start("synthetic",File(folder,"synthetic.ogg.partial"),language="it-IT")
  val state=withTimeout(3000) {session.state.first {it.phase==RecordingPhase.Ready}}
  assertEquals(encoder.samples,received);assertEquals(listOf(0L,100_000L),encoder.times);assertEquals(200L,state.recording!!.durationMs)
  assertTrue(state.recording.capturedText);assertEquals("Ciao mondo",state.text);assertFalse(state.recordingOnly)
  assertTrue(source.closed);assertTrue(encoder.finished && encoder.closed);assertEquals(1,focus.releases);assertEquals(1,outcomes)
 }
 @Test fun unsupportedRecognitionKeepsRecordingOnlyWithoutCallingAnotherSpeechEngine()=runBlocking {
  val source=Source();val encoder=Encoder();lateinit var session:RecordingSession
  session=RecordingSession(scope,{source},{encoder},Focus(),PcmRecognitionFactory {_,_->null},isEnabled={true})
  source.afterChunk={session.onForegroundChanged(false)}
  session.start("only",File(folder,"only.ogg.partial"),initialText="Typed owner comment")
  val state=withTimeout(3000) {session.state.first {it.phase==RecordingPhase.Ready}}
  assertTrue(state.recordingOnly);assertFalse(state.recording!!.capturedText);assertEquals("Typed owner comment",state.text);assertTrue(File(state.recording.path).exists())
 }
 @Test fun stalledRecognitionIsDetachedWhileEveryAudioChunkIsKept()=runBlocking {
  val source=Source(3);val encoder=Encoder();var closed=0;lateinit var session:RecordingSession
  val recognizer=object:PcmRecognition {override fun offer(samples:ShortArray,count:Int)=false;override suspend fun finish()=Unit;override fun close() {closed++}}
  session=RecordingSession(scope,{source},{encoder},Focus(),PcmRecognitionFactory {_,_->recognizer},isEnabled={true});source.afterChunk={session.onForegroundChanged(false)}
  session.start("stalled",File(folder,"stalled.ogg.partial"))
  val ready=withTimeout(3000) {session.state.first {it.phase==RecordingPhase.Ready}}
  assertTrue(ready.recordingOnly);assertEquals(3,encoder.samples.size);assertEquals(1,closed)
 }
 @Test fun phoneInterruptionStopsAndFinalizesWithoutBackgroundCapture()=runBlocking {
  val source=Source(1);val encoder=Encoder();val focus=Focus();val read=CompletableDeferred<Unit>()
  val session=RecordingSession(scope,{source},{encoder},focus,isEnabled={true});source.afterChunk={read.complete(Unit)}
  session.start("phone",File(folder,"phone.ogg.partial"));withTimeout(3000) {read.await()};session.onAudioFocusLost()
  val state=withTimeout(3000) {session.state.first {it.phase==RecordingPhase.Ready}}
  assertEquals(100L,state.recording!!.durationMs);assertTrue(source.closed);assertEquals(1,focus.releases)
 }
 @Test fun discardJoinsClosesDeletesAndRejectsLateWords()=runBlocking {
  val source=Source(1);val encoder=Encoder();val read=CompletableDeferred<Unit>();var events:((SpeechEvent)->Unit)?=null;var discarded=0
  val recognition=object:PcmRecognition {override fun offer(samples:ShortArray,count:Int)=true;override suspend fun finish()=Unit;override fun close()=Unit}
  val session=RecordingSession(scope,{source},{encoder},Focus(),PcmRecognitionFactory {_,callback ->events=callback;recognition},{if(it is RecordingOutcome.Discarded) discarded++},isEnabled={true})
  source.afterChunk={read.complete(Unit)};session.start("discard",File(folder,"discard.ogg.partial"),initialText="Keep typed words")
  withTimeout(3000) {read.await()};withTimeout(3000) {session.discard()};events?.invoke(SpeechEvent.Final("must never return"))
  assertEquals(RecordingPhase.Discarded,session.state.value.phase);assertEquals("Keep typed words",session.state.value.text);assertEquals(1,discarded);assertTrue(source.closed);assertTrue(folder.listFiles().isNullOrEmpty())
 }
 @Test fun workerFinishingDuringDiscardSignalPublishesDiscardOnlyOnce()=runBlocking {
  val read=CompletableDeferred<Unit>();val stopped=CompletableDeferred<Unit>();val encoder=Encoder();val focus=Focus();var discarded=0;var closed=false
  val caller=Thread.currentThread();lateinit var recordingJob:Job
  val source=object:PcmSource {
   override fun start()=Unit
   override suspend fun read(buffer:ShortArray):Int {read.complete(Unit);stopped.await();return -1}
   override fun stop() {
    stopped.complete(Unit)
    // Force the worker to finish after the discard signal but before its caller resumes.
    if(Thread.currentThread()==caller) runBlocking {recordingJob.join()}
   }
   override fun close() {closed=true;stop()}
  }
  val session=RecordingSession(scope,{source},{encoder},focus,onOutcome={if(it is RecordingOutcome.Discarded) discarded++},isEnabled={true})
  session.start("discard-race",File(folder,"discard-race.ogg.partial"),initialText="Keep typed words")
  withTimeout(3000) {read.await()};recordingJob=scope.coroutineContext[Job]!!.children.single()
  withTimeout(3000) {session.discard()}
  assertTrue(recordingJob.isCompleted);assertEquals(1,discarded);assertEquals(RecordingPhase.Discarded,session.state.value.phase)
  assertEquals("Keep typed words",session.state.value.text);assertTrue(closed && encoder.closed);assertEquals(1,focus.releases);assertTrue(folder.listFiles().isNullOrEmpty())
 }
 @Test fun completedRecordingConcurrentAndRepeatedDiscardsPublishOnlyOnce()=runBlocking {
  val source=Source(1);val encoder=Encoder();val read=CompletableDeferred<Unit>();val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>();val discarded=AtomicInteger()
  val session=RecordingSession(scope,{source},{encoder},Focus(),onOutcome={if(it is RecordingOutcome.Discarded && discarded.incrementAndGet()==1) {entered.complete(Unit);release.await()}},isEnabled={true})
  source.afterChunk={read.complete(Unit)};session.start("completed",File(folder,"completed.ogg.partial"),initialText="Keep typed words")
  withTimeout(3000) {read.await()};assertNotNull(session.stop());assertEquals(RecordingPhase.Ready,session.state.value.phase)
  val first=async(Dispatchers.Default) {session.discard()};withTimeout(3000) {entered.await()}
  val second=async(start=CoroutineStart.UNDISPATCHED) {session.discard()};val waitedForFirst=!second.isCompleted
  release.complete(Unit);withTimeout(3000) {awaitAll(first,second)};session.discard()
  assertTrue(waitedForFirst);assertEquals(1,discarded.get());assertEquals(RecordingPhase.Discarded,session.state.value.phase)
  assertNull(session.state.value.recording);assertEquals("Keep typed words",session.state.value.text);assertTrue(source.closed && encoder.closed);assertTrue(folder.listFiles().isNullOrEmpty())
 }
 @OptIn(ExperimentalCoroutinesApi::class)
 @Test fun newStartWaitsUntilPreviousDiscardCannotTouchItsSession()=runBlocking {
  val read=CompletableDeferred<Unit>();val finishing=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>();val newRead=CompletableDeferred<Unit>();val notices=java.util.concurrent.ConcurrentLinkedQueue<String>()
  val oldSource=Source(1).also {it.afterChunk={read.complete(Unit)}};val oldEncoder=Encoder()
  val gated=object:RecordingEncoder by oldEncoder {override suspend fun finish() {finishing.complete(Unit);release.await();oldEncoder.finish()}}
  val newSource=Source(1).also {it.afterChunk={newRead.complete(Unit)}}
  val sources=java.util.concurrent.ConcurrentLinkedQueue(listOf(oldSource,newSource))
  val encoders=java.util.concurrent.ConcurrentLinkedQueue(listOf(gated,Encoder()))
  val session=RecordingSession(scope,{sources.remove()},{encoders.remove()},Focus(),onOutcome={if(it is RecordingOutcome.Discarded) notices+=it.id},isEnabled={true})
  session.start("old",File(folder,"old.ogg.partial"))
  withTimeout(3000) {read.await()};val recordingJob=scope.coroutineContext[Job]!!.children.single()
  val dispatcher=StandardTestDispatcher();val discarding=scope.async(dispatcher) {session.discard()}
  dispatcher.scheduler.runCurrent();withTimeout(3000) {finishing.await()};release.complete(Unit);withTimeout(3000) {recordingJob.join()}
  // Leave the old discard continuation queued after its job has completed.
  val newStart=scope.async(start=CoroutineStart.UNDISPATCHED) {session.start("new",File(folder,"new.ogg.partial"),initialText="New typed words")}
  assertFalse("New recording must wait for the previous discard.",newStart.isCompleted)
  withTimeout(3000) {
   // The real IO worker may enqueue its join continuation after one runCurrent returns.
   while(!discarding.isCompleted) {dispatcher.scheduler.runCurrent();yield()}
   discarding.await();newStart.await();newRead.await()
  }
  try {
   assertEquals(listOf("old"),notices.toList());assertEquals("new",session.state.value.id);assertEquals(RecordingPhase.Recording,session.state.value.phase)
   assertEquals("New typed words",session.state.value.text);assertTrue(File(folder,"new.ogg.partial").exists());assertFalse(newSource.closed);assertFalse(File(folder,"old.ogg.partial").exists())
  } finally {session.discard()}
 }
 @Test fun failedWorkerDiscardOutcomeIsRetainedUntilAnExplicitRetry()=runBlocking {
  val source=Source(1);val encoder=Encoder();val focus=Focus();val read=CompletableDeferred<Unit>();val attempts=AtomicInteger();val file=File(folder,"retry.ogg.partial")
  val session=RecordingSession(scope,{source},{encoder},focus,onOutcome={if(it is RecordingOutcome.Discarded && attempts.incrementAndGet()==1) throw java.io.IOException("synthetic deletion failure")},isEnabled={true})
  source.afterChunk={read.complete(Unit)};session.start("retry",file,initialText="Keep typed words")
  withTimeout(3000) {read.await()};withTimeout(3000) {session.discard()}
  assertEquals(1,attempts.get());assertEquals(RecordingPhase.Failed,session.state.value.phase);assertTrue(file.exists())
  assertTrue(source.closed && encoder.closed);assertEquals(1,focus.releases);assertEquals("Keep typed words",session.state.value.text)
  withTimeout(3000) {session.discard()};session.discard()
  assertEquals(2,attempts.get());assertEquals(RecordingPhase.Discarded,session.state.value.phase);assertFalse(file.exists())
 }
 @Test fun failedLocalDeletionCanBeRetriedWithoutAnotherDiscardNotification()=runBlocking {
  val source=Source(1);val encoder=Encoder();val read=CompletableDeferred<Unit>();var discarded=0
  val file=object:File(folder,"local-delete.ogg.partial") {var allowDelete=false;override fun delete()=allowDelete && super.delete()}
  val session=RecordingSession(scope,{source},{encoder},Focus(),onOutcome={if(it is RecordingOutcome.Discarded) discarded++},isEnabled={true})
  source.afterChunk={read.complete(Unit)};session.start("local-delete",file)
  withTimeout(3000) {read.await()};withTimeout(3000) {session.discard()}
  assertEquals(1,discarded);assertEquals(RecordingPhase.Discarded,session.state.value.phase);assertTrue(file.exists())
  file.allowDelete=true;session.discard();assertFalse(file.exists());assertEquals(1,discarded)
 }
 @Test fun discardWaitsForCancelledWorkerCleanupBeforePublishing()=runBlocking {
  val source=Source(1);val encoder=Encoder();val focus=Focus();val read=CompletableDeferred<Unit>();val finishing=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>();var discarded=0
  val gated=object:RecordingEncoder by encoder {override suspend fun finish() {finishing.complete(Unit);release.await();encoder.finish()}}
  val session=RecordingSession(scope,{source},{gated},focus,onOutcome={if(it is RecordingOutcome.Discarded) discarded++},isEnabled={true})
  source.afterChunk={read.complete(Unit)};session.start("cancelled",File(folder,"cancelled.ogg.partial"),initialText="Keep typed words")
  withTimeout(3000) {read.await()};val recordingJob=scope.coroutineContext[Job]!!.children.single();recordingJob.cancel();withTimeout(3000) {finishing.await()}
  val discarding=async(start=CoroutineStart.UNDISPATCHED) {session.discard()};val waitedForCleanup=!discarding.isCompleted;val unpublished=discarded==0
  release.complete(Unit);withTimeout(3000) {discarding.await()}
  assertTrue(waitedForCleanup);assertTrue(unpublished);assertTrue(recordingJob.isCompleted);assertEquals(1,discarded)
  assertEquals(RecordingPhase.Discarded,session.state.value.phase);assertEquals("Keep typed words",session.state.value.text);assertTrue(source.closed && encoder.closed);assertEquals(1,focus.releases);assertTrue(folder.listFiles().isNullOrEmpty())
 }
 @Test fun encoderFailureReleasesResourcesAndLeavesAnActionableInterruptedOutcome()=runBlocking {
  val source=Source();val encoder=Encoder().also {it.fail=true};val focus=Focus();val outcome=CompletableDeferred<RecordingOutcome>()
  val session=RecordingSession(scope,{source},{encoder},focus,onOutcome={outcome.complete(it)},isEnabled={true})
  session.start("failure",File(folder,"failure.ogg.partial"),initialText="Preserved words")
  assertTrue(withTimeout(3000) {outcome.await()} is RecordingOutcome.Interrupted);assertEquals(RecordingPhase.Failed,session.state.value.phase)
  assertEquals("Preserved words",session.state.value.text);assertTrue(source.closed && encoder.closed);assertEquals(1,focus.releases)
 }
 @Test fun syntheticSilenceClockStopsWithinTenSecondsWithoutWallClockSleep()=runBlocking {
  var now=0L;val source=object:PcmSource {override fun start()=Unit;override suspend fun read(buffer:ShortArray):Int {now+=11_000_000_000L;buffer.fill(0);return buffer.size};override fun stop()=Unit;override fun close()=Unit}
  val encoder=Encoder();val session=RecordingSession(scope,{source},{encoder},Focus(),nanoTime={now},isEnabled={true})
  session.start("silence",File(folder,"silence.ogg.partial"))
  val state=withTimeout(3000) {session.state.first {it.phase==RecordingPhase.Ready}};assertEquals(1,encoder.samples.size);assertEquals(100L,state.recording!!.durationMs)
 }
 @Test fun encodedByteBudgetStopsCaptureBeforeItCanGrowWithoutBound()=runBlocking {
  val source=Source(100,delayMs=10);val encoder=Encoder();val outcome=CompletableDeferred<RecordingOutcome>();val session=RecordingSession(scope,{source},{encoder},Focus(),onOutcome={outcome.complete(it)},isEnabled={true})
  session.start("limit",File(folder,"limit.ogg.partial"),maximumBytes=100_000)
  val state=withTimeout(3000) {session.state.first {it.phase==RecordingPhase.Ready}}
  assertEquals("recording_limit",(outcome.await() as RecordingOutcome.Ready).reason);assertEquals(11,encoder.samples.size);assertTrue(state.recording!!.byteSize<=100_000);assertTrue(state.message.contains("clip stays"));assertTrue(source.closed)
 }

 @Test fun visiblePartialsAreJournaledAtMostOncePerSecondAndFinalWordsAlwaysPersist()=runBlocking {
  val source=Source();val encoder=Encoder();val words=mutableListOf<String>();lateinit var session:RecordingSession
  val recognizer=object:PcmRecognition {override fun offer(samples:ShortArray,count:Int)=true;override suspend fun finish()=Unit;override fun close()=Unit}
  session=RecordingSession(scope,{source},{encoder},Focus(),PcmRecognitionFactory {_,events ->events(SpeechEvent.Partial("hello"));events(SpeechEvent.Partial("hello there"));events(SpeechEvent.Final("Hello there."));recognizer},nanoTime={0},persistText={words+=it.text},isEnabled={true})
  source.afterChunk={session.onForegroundChanged(false)};session.start("text",File(folder,"text.ogg.partial"))
  withTimeout(3000) {session.state.first {it.phase==RecordingPhase.Ready}}
  assertEquals(listOf("Hello","Hello there.","Hello there."),words)
 }

 @Test fun missingOptInAllocatesNoMicrophoneEncoderOrFile()=runBlocking {
  var factories=0;val session=RecordingSession(scope,{factories++;Source()},{factories++;Encoder()},Focus())
  assertTrue(runCatching {session.start("off",File(folder,"off.ogg.partial"))}.isFailure)
  assertEquals(0,factories);assertTrue(folder.listFiles().isNullOrEmpty());assertEquals(RecordingPhase.Idle,session.state.value.phase)
 }

 @Test fun laterTypingAndDraftCasSurviveAPartialPersistenceAwaitAndChangedFinalResult()=runBlocking {
  val source=Source(1);val encoder=Encoder();val audioCaptured=CompletableDeferred<Unit>();val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>();val persisted=mutableListOf<String>()
  var events:((SpeechEvent)->Unit)?=null;lateinit var session:RecordingSession
  val recognition=object:PcmRecognition {override fun offer(samples:ShortArray,count:Int)=true;override suspend fun finish()=Unit;override fun close()=Unit}
  session=RecordingSession(scope,{source},{encoder},Focus(),PcmRecognitionFactory {_,callback ->events=callback;recognition},persistText={snapshot ->
   if(snapshot.text=="Hello Owner") {entered.complete(Unit);release.await()}
   if(session.isCurrent(snapshot)) persisted+=snapshot.text
  },isEnabled={true})
  source.afterChunk={audioCaptured.complete(Unit)}
  session.start("typing",File(folder,"typing.ogg.partial"),initialText="Owner",selectionStart=0)
  withTimeout(3000) {session.state.first {it.phase==RecordingPhase.Recording}}
  withTimeout(3000) {audioCaptured.await()}
  events!!.invoke(SpeechEvent.Partial("hello"))
  // The captured chunk is drained before stopping; the consumer polls speech independently.
  withTimeout(3000) {entered.await()}
  val prior=session.textSnapshot();session.updateTypedText("Prefix Hello Owner typed",0,0)
  assertFalse(session.isCurrent(prior));events!!.invoke(SpeechEvent.Final("Hello there."));release.complete(Unit)
  session.onForegroundChanged(false)
  val ready=withTimeout(3000) {session.state.first {it.phase==RecordingPhase.Ready}}
  assertEquals("Prefix Hello there. Owner typed",ready.text)
  assertFalse(persisted.contains("Hello Owner"));assertTrue(persisted.contains(ready.text));assertTrue(source.closed)
 }
 @Test fun zeroSampleStallUsesBoundedClockAndReleasesMicrophoneWithoutKeepingItActive()=runBlocking {
  var now=0L;var closed=false
  val source=object:PcmSource {override fun start()=Unit;override suspend fun read(buffer:ShortArray):Int {now+=3_000_000_000L;return 0};override fun stop()=Unit;override fun close() {closed=true}}
  val session=RecordingSession(scope,{source},{Encoder()},Focus(),nanoTime={now},isEnabled={true})
  session.start("zero",File(folder,"zero.ogg.partial"));val failed=withTimeout(3000) {session.state.first {it.phase==RecordingPhase.Failed}}
  assertTrue(closed);assertNull(failed.recording)
 }

}
