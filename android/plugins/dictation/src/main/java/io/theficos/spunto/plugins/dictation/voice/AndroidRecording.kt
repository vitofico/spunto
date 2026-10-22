package io.theficos.spunto.plugins.dictation.voice

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.*
import android.os.Build
import androidx.annotation.RequiresApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteOrder

/** The sole microphone owner. Constructed only by an explicit recording start. */
class AndroidPcmSource(private val context:Context):PcmSource {
 @Volatile private var audio:AudioRecord?=null
 override fun start() {
  if(Build.VERSION.SDK_INT<33) throw RecordingFailure("android_13_required")
  if(context.checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED) throw RecordingFailure("permission_required")
  val minimum=AudioRecord.getMinBufferSize(RECORDING_RATE,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT)
  if(minimum<=0) throw RecordingFailure("microphone_unavailable")
  // The explicit permission guard above precedes every allocation; revocation still throws.
  @SuppressLint("MissingPermission")
  val local=AudioRecord.Builder().setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
   .setAudioFormat(AudioFormat.Builder().setSampleRate(RECORDING_RATE).setChannelMask(AudioFormat.CHANNEL_IN_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
   .setBufferSizeInBytes(maxOf(minimum,6400)).build()
  audio=local
  if(local.state!=AudioRecord.STATE_INITIALIZED) {close();throw RecordingFailure("microphone_unavailable")}
  try {local.startRecording()} catch(_:Exception) {close();throw RecordingFailure("microphone_failed")}
 }
 override suspend fun read(buffer:ShortArray):Int=withContext(Dispatchers.IO) {
  val local=audio?:return@withContext -1
  if(Build.VERSION.SDK_INT>=29 && local.activeRecordingConfiguration?.isClientSilenced==true) throw RecordingFailure("microphone_silenced")
  local.read(buffer,0,buffer.size,AudioRecord.READ_BLOCKING)
 }
 override fun stop() {runCatching {audio?.stop()}}
 override fun close() {stop();audio?.release();audio=null}
}

/** Native Opus encoding with checked Ogg packaging; no bundled model or encoder library. */
@RequiresApi(29)
class AndroidOggEncoder(private val beforeFinalize:((File)->Unit)?=null):RecordingEncoder {
 private var codec:MediaCodec?=null
 private var muxer:OpusOggWriter?=null
 private var started=false
 private var finished=false
 private var codecEof=false
 private var muxerStopped=false
 private var inputFrames=0L
 private var output:GeneratedOggOutput?=null
 private var outputFile:File?=null
 private var lastEndUs=0L
 private var endFrames:Long?=null
 override fun start(file:File) {
  check(output==null)
  output=GeneratedOggOutput.reserve(file);outputFile=file
  try {
   val format=MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_OPUS,RECORDING_RATE,1)
   format.setInteger(MediaFormat.KEY_BIT_RATE,RECORDING_BITRATE)
   format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE,6400)
   val local=MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_OPUS);codec=local;local.configure(format,null,null,MediaCodec.CONFIGURE_FLAG_ENCODE);local.start()
  } catch(_:Exception) {close();throw RecordingFailure("opus_encoder_unavailable")}
 }
 override suspend fun write(samples:ShortArray,count:Int,timeUs:Long)=withContext(Dispatchers.IO) {
  require(count in 1..samples.size)
  val local=codec?:throw RecordingFailure("encoder_unavailable")
  queuePcm(local,samples,count,timeUs)
  inputFrames+=count
  drain(local,false)
 }
 private fun queuePcm(local:MediaCodec,samples:ShortArray,count:Int,timeUs:Long) {
  val index=inputIndex(local)
  val input=local.getInputBuffer(index)?:throw RecordingFailure("encoder_failed")
  if(input.capacity()<count*2) throw RecordingFailure("encoder_buffer_small")
  input.clear();input.order(ByteOrder.LITTLE_ENDIAN)
  repeat(count) {input.putShort(samples[it])}
  local.queueInputBuffer(index,0,count*2,timeUs,0)
  lastEndUs=timeUs+count*1_000_000L/RECORDING_RATE
 }
 private fun inputIndex(local:MediaCodec):Int {
  repeat(100) {val index=local.dequeueInputBuffer(10_000);if(index>=0) return index;drain(local,false)}
  throw RecordingFailure("encoder_stalled")
 }
 private fun drain(local:MediaCodec,end:Boolean) {
  val info=MediaCodec.BufferInfo();val deadline=System.nanoTime()+2_000_000_000L
  do {
   when(val index=local.dequeueOutputBuffer(info,if(end) 10_000 else 0)) {
    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {if(started) throw RecordingFailure("encoder_format_changed");val format=local.outputFormat;val header=format.getByteBuffer("csd-0")?:throw RecordingFailure("encoder_no_header");muxer=OpusOggWriter(requireNotNull(outputFile),header,format.getByteBuffer("csd-1"));endFrames?.let {muxer!!.endInput(it)};started=true}
    MediaCodec.INFO_TRY_AGAIN_LATER -> if(!end) return
    else -> if(index>=0) {
     val output=local.getOutputBuffer(index)?:throw RecordingFailure("encoder_failed")
     if((info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG)!=0) info.size=0
     if(info.size>0) {if(!started) throw RecordingFailure("encoder_no_format");output.position(info.offset);output.limit(info.offset+info.size);val packet=ByteArray(info.size);output.get(packet);muxer!!.write(packet)}
     val done=(info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM)!=0
     local.releaseOutputBuffer(index,false)
     if(done) {codecEof=true;return}
    }
   }
  } while(!end || System.nanoTime()<deadline)
  throw RecordingFailure("encoder_stalled")
 }
 override suspend fun finish()=withContext(Dispatchers.IO) {
  if(finished) return@withContext
  val local=codec?:throw RecordingFailure("encoder_unavailable")
  if(inputFrames==0L) throw RecordingFailure("empty_recording")
  endFrames=inputFrames;muxer?.endInput(inputFrames)
  // Some Opus codecs flush only one partial frame at EOF, omitting lookahead for a short tail.
  // Feed bounded silence until every captured sample is encoded; the Ogg end trims all padding.
  val padding=ShortArray(RECORDING_RATE/50)
  val maximumPadding=65_535L*RECORDING_RATE/48_000+2*padding.size
  var padded=0
  while(muxer?.hasEndAudio!=true && padded<maximumPadding) {
   queuePcm(local,padding,padding.size,lastEndUs);padded+=padding.size;drain(local,false)
  }
  local.queueInputBuffer(inputIndex(local),0,0,lastEndUs,MediaCodec.BUFFER_FLAG_END_OF_STREAM)
  drain(local,true)
  if(!started) throw RecordingFailure("empty_recording")
  muxer!!.finish(inputFrames);muxerStopped=true
  // Only this new, completed output is validated and synced before the journal retains it.
  beforeFinalize?.invoke(requireNotNull(outputFile))
  requireNotNull(output).finish(codecEof,muxerStopped,inputFrames)
  finished=true
 }
 override fun close() {
  runCatching {codec?.stop()};runCatching {codec?.release()};codec=null
  runCatching {muxer?.close()};muxer=null
 }
}
