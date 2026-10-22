package io.theficos.spunto

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.theficos.spunto.data.local.*
import io.theficos.spunto.plugins.dictation.voice.*
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import android.media.MediaCodec
import android.media.AudioFormat
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Build
import kotlin.math.sin
import kotlin.math.cos
import kotlin.math.sqrt

/** Synthetic generated PCM only. No microphone, recognizer, owner DB, token or network is used. */
@RunWith(AndroidJUnit4::class)
@androidx.test.filters.SdkSuppress(minSdkVersion=33)
class VoiceFoundationTest {
 @get:Rule val timeout=org.junit.rules.Timeout(10,java.util.concurrent.TimeUnit.MINUTES)
 private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
 private suspend fun encode(file:File,exportGeneratedEvidence:Boolean=false,inputFrames:Int=32_000) {
  check(Build.VERSION.SDK_INT>=33)
  val export=exportGeneratedEvidence && InstrumentationRegistry.getArguments().getString("spunto.exportGeneratedOpus")=="true"
  val evidence=if(export) File(context.cacheDir,"voice-codec-evidence").apply {check(isDirectory || mkdirs())} else null
  val encoder=AndroidOggEncoder(beforeFinalize=evidence?.let {folder ->{generated:File ->generated.copyTo(File(folder,"generated-before.ogg"),overwrite=true);Unit}})
  try {
   encoder.start(file)
   var offset=0
   while(offset<inputFrames) {
    val samples=ShortArray(minOf(1600,inputFrames-offset)) {index ->(sin(2*Math.PI*440*(offset+index)/RECORDING_RATE)*4000).toInt().toShort()}
    encoder.write(samples,samples.size,offset*1_000_000L/RECORDING_RATE)
    offset+=samples.size
   }
   encoder.finish()
   if(evidence!=null) {
    file.copyTo(File(evidence,"generated-after.ogg"),overwrite=true)
    fun metadata(name:String):String {
     val generated=File(evidence,name)
     val hashing=java.security.MessageDigest.getInstance("SHA-256")
     generated.inputStream().use {input ->val buffer=ByteArray(32*1024);while(true) {val count=input.read(buffer);if(count<0) break;hashing.update(buffer,0,count)}}
     val digest=hashing.digest().joinToString("") {"%02x".format(it)}
     return "{\"name\":\"$name\",\"bytes\":${generated.length()},\"sha256\":\"$digest\"}"
    }
    File(evidence,"generated-evidence.json").writeText("{\"synthetic_pcm\":true,\"sample_rate\":16000,\"channels\":1,\"input_frames\":32000,\"files\":[${metadata("generated-before.ogg")},${metadata("generated-after.ogg")}]}")
   }
  } finally {encoder.close()}
 }
 @Test fun nativeCodecProducesValidatedMonoOpusOggWithExpectedDurationFromSyntheticPcm()=runBlocking {
  if(Build.VERSION.SDK_INT<33) {Assume.assumeTrue(false);return@runBlocking}
  val folder=File(context.cacheDir,"codec-${System.nanoTime()}").apply {mkdirs()};val file=File(folder,"generated-sine.ogg")
  try {
   encode(file,exportGeneratedEvidence=true);val metadata=inspectRecording(file,20_000_000)
   assertTrue(metadata.durationMs in 1900..2100);assertTrue(metadata.byteSize in 1..100_000);assertEquals(64,metadata.sha256.length)
   val extractor=MediaExtractor()
   try {extractor.setDataSource(file.absolutePath);assertEquals(1,extractor.trackCount);val format=extractor.getTrackFormat(0);assertEquals(MediaFormat.MIMETYPE_AUDIO_OPUS,format.getString(MediaFormat.KEY_MIME));assertEquals(1,format.getInteger(MediaFormat.KEY_CHANNEL_COUNT))}
   finally {extractor.release()}
  } finally {file.delete();folder.delete()}
 }
 private fun decode(file:File):ShortArray {
  val extractor=MediaExtractor();var codec:MediaCodec?=null
  try {
   extractor.setDataSource(file.absolutePath);extractor.selectTrack(0)
   val format=extractor.getTrackFormat(0)
   format.setInteger(MediaFormat.KEY_SAMPLE_RATE,48_000)
   format.setInteger(MediaFormat.KEY_PCM_ENCODING,AudioFormat.ENCODING_PCM_16BIT)
   val decoder=MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_OPUS);codec=decoder
   decoder.configure(format,null,null,0);decoder.start()
   val bytes=ByteArrayOutputStream();val info=MediaCodec.BufferInfo()
   var inputDone=false;var outputDone=false;val deadline=System.nanoTime()+10_000_000_000L
   while(!outputDone && System.nanoTime()<deadline) {
    if(!inputDone) {
     val index=decoder.dequeueInputBuffer(10_000)
     if(index>=0) {
      val input=requireNotNull(decoder.getInputBuffer(index));input.clear()
      val count=extractor.readSampleData(input,0)
      if(count<0) {decoder.queueInputBuffer(index,0,0,0,MediaCodec.BUFFER_FLAG_END_OF_STREAM);inputDone=true}
      else {decoder.queueInputBuffer(index,0,count,extractor.sampleTime,0);extractor.advance()}
     }
    }
    when(val index=decoder.dequeueOutputBuffer(info,10_000)) {
     MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
      assertEquals(48_000,decoder.outputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE))
      assertEquals(1,decoder.outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT))
     }
     else -> if(index>=0) {
      val output=requireNotNull(decoder.getOutputBuffer(index));output.position(info.offset);output.limit(info.offset+info.size)
      val block=ByteArray(info.size);output.get(block);bytes.write(block)
      outputDone=info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM!=0
      decoder.releaseOutputBuffer(index,false)
     }
    }
   }
   assertTrue("Decoder must reach EOF",outputDone)
   val samples=ByteBuffer.wrap(bytes.toByteArray()).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
   return ShortArray(samples.remaining()).also {samples.get(it)}
  } finally {runCatching {codec?.stop()};codec?.release();extractor.release()}
 }
 @Test fun shortAndPartialTailRecordingsKeepTheirPcmAndDecodeTheGeneratedTone()=runBlocking {
  val folder=File(context.cacheDir,"codec-tail-${System.nanoTime()}").apply {mkdirs()}
  try {
   for(frames in listOf(216,300,321,32_300)) {
    val file=File(folder,"generated-$frames.ogg")
    encode(file,inputFrames=frames)
    val metadata=inspectRecording(file,20_000_000)
    assertEquals(frames*1000L/RECORDING_RATE,metadata.durationMs)
    val decoded=decode(file);val expected=frames*3
    println("synthetic_opus_pcm_frames=$frames decoded_48khz_samples=${decoded.size} duration_ms=${metadata.durationMs}")
    assertTrue("All captured PCM must remain",decoded.size>=expected)
    assertTrue("Padding is bounded to one Opus frame",decoded.size<=expected+960)
    var sine=0.0;var cosine=0.0;var energy=0.0
    for(index in 0 until expected.coerceAtMost(decoded.size)) {
     val sample=decoded[index].toDouble();val angle=2*Math.PI*440*index/48_000
     sine+=sample*sin(angle);cosine+=sample*cos(angle);energy+=sample*sample
    }
    assertTrue("Decoded output contains the generated 440Hz tone",2*(sine*sine+cosine*cosine)/(expected*energy)>0.8)
    val tail=decoded.sliceArray((expected-48) until expected)
    assertTrue("Final captured tone must not become padding",sqrt(tail.sumOf {it.toDouble()*it}/tail.size)>500)
   }
  } finally {folder.listFiles()?.forEach {it.delete()};folder.delete()}
 }
 @Test fun finalizedInterruptedJournalRecoversLocallyAndAtomicallyRetainsPrivateClip()=runBlocking {
  if(Build.VERSION.SDK_INT<33) {Assume.assumeTrue(false);return@runBlocking}
  val db=Room.inMemoryDatabaseBuilder(context,SpuntoDatabase::class.java).build()
  val folder=File(context.cacheDir,"journal-${System.nanoTime()}").apply {mkdirs()};val notes=NotesRepository(db);val recordings=RecordingRepository(notes,folder)
  try {
   notes.dao.putState(SyncStateEntity(serverUrl="https://synthetic.invalid",maxUploadBytes=20_000_000,manifest="{\"plugins\":[]}",catalog="""{"plugins":[{"id":"voice-memos","switch":"on","state":"waiting","permissions":["attachments.read","attachments.write","ai.transcribe","suggest"],"granted":["attachments.read","attachments.write","ai.transcribe","suggest"],"ai_destinations":{}}]}"""))
   val row=recordings.begin();encode(File(row.localPath))
   val recovered=RecordingRepository(notes,folder).recover().single();assertEquals("recoverable",recovered.state)
   assertTrue(notes.dao.all().isEmpty());assertTrue(notes.dao.pendingAttachments().isEmpty());assertTrue(notes.dao.outbox().isEmpty())
   val id=recordings.retain(row.id,"Synthetic private recording comment",privateNote=true)
   assertTrue(notes.dao.get(id)!!.privateNote);val attachment=notes.dao.attachment(row.id)!!;assertEquals("queued",attachment.status);assertTrue(File(attachment.localPath).exists())
   assertTrue(attachment.durationMs in 1900..2100);assertTrue(notes.dao.get(id)!!.body.contains(attachment.marker))
   assertTrue(runCatching {recordings.discard(row.id)}.isFailure);assertTrue(File(attachment.localPath).exists())
  } finally {db.close();folder.listFiles()?.forEach {it.delete()};folder.delete()}
 }
}
