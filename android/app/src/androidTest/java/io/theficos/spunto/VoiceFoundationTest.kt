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
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Build
import kotlin.math.sin

/** Synthetic generated PCM only. No microphone, recognizer, owner DB, token or network is used. */
@RunWith(AndroidJUnit4::class)
@androidx.test.filters.SdkSuppress(minSdkVersion=33)
class VoiceFoundationTest {
 @get:Rule val timeout=org.junit.rules.Timeout(10,java.util.concurrent.TimeUnit.MINUTES)
 private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
 private suspend fun encode(file:File,exportGeneratedEvidence:Boolean=false) {
  check(Build.VERSION.SDK_INT>=33)
  val export=exportGeneratedEvidence && InstrumentationRegistry.getArguments().getString("spunto.exportGeneratedOpus")=="true"
  val evidence=if(export) File(context.cacheDir,"voice-codec-evidence").apply {check(isDirectory || mkdirs())} else null
  val encoder=AndroidOggEncoder(beforeFinalize=evidence?.let {folder ->{generated:File ->generated.copyTo(File(folder,"generated-before.ogg"),overwrite=true);Unit}})
  try {
   encoder.start(file)
   repeat(20) {frame ->
    val samples=ShortArray(1600) {index ->(sin(2*Math.PI*440*(frame*1600+index)/RECORDING_RATE)*4000).toInt().toShort()}
    encoder.write(samples,samples.size,frame*100_000L)
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
