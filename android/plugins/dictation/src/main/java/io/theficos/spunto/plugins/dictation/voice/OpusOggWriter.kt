package io.theficos.spunto.plugins.dictation.voice

import java.io.FileOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.random.Random

/** Android's OggWriter has an uninitialized packet counter on affected releases; keep muxing here. */
internal class OpusOggWriter(file:File,codecHeader:ByteBuffer,codecDelay:ByteBuffer?=null):AutoCloseable {
 private val head=opusIdentificationHeader(codecHeader,codecDelay)
 private val preskip=(head[10].toInt() and 255) or ((head[11].toInt() and 255) shl 8)
 private val serial=Random.nextInt()
 private var sequence=0
 private var decodedSamples=0L
 private var writtenSamples=0L
 private var pending:ByteArray?=null
 private var finished=false
 private var closed=false
 private var endSamples:Long?=null
 private val stream:FileOutputStream
 init {
  require(file.isFile && file.length()==0L) {"Use a new reserved recording file."}
  stream=file.outputStream()
  try {
   page(head,0,2)
   val vendor="Spunto".toByteArray(Charsets.UTF_8)
   val tags=ByteBuffer.allocate(16+vendor.size).order(ByteOrder.LITTLE_ENDIAN)
   tags.put("OpusTags".toByteArray(Charsets.US_ASCII)).putInt(vendor.size).put(vendor).putInt(0)
   page(tags.array(),0,0)
  } catch(error:Exception) {stream.close();throw error}
 }
 fun write(packet:ByteArray) {
  check(!finished && !closed)
  require(packet.size in 1..65_024) {"Recording packet is too large."}
  val samples=opusPacketSamples(packet)
  if(hasEndAudio) return // Codec flush padding after the captured end never becomes another packet.
  pending?.let {page(it,decodedSamples,0);writtenSamples=decodedSamples}
  decodedSamples+=samples
  pending=packet
 }
 val hasEndAudio:Boolean get()=endSamples?.let {pending!=null && decodedSamples>=it}==true
 fun endInput(inputFrames:Long) {
  check(!closed)
  require(inputFrames in 1..RECORDING_RATE*RECORDING_MAX_SECONDS) {"Recording duration is unsupported."}
  val target=inputFrames*48_000/RECORDING_RATE+preskip
  require(endSamples==null || endSamples==target)
  endSamples=target
 }
 fun finish(inputFrames:Long) {
  check(!finished && !closed)
  endInput(inputFrames)
  val last=requireNotNull(pending) {"Recording has no audio."}
  val finalSamples=requireNotNull(endSamples)
  // Only the final packet can be trimmed, and a granule cannot claim samples the codec never produced.
  require(finalSamples>writtenSamples && finalSamples<=decodedSamples) {"Recording final sample count is invalid."}
  page(last,finalSamples,4)
  close();pending=null;finished=true
 }
 override fun close() {if(!closed) {closed=true;stream.close()}}
 private fun page(packet:ByteArray,granule:Long,flags:Int) {
  val segments=packet.size/255+1
  val bytes=ByteArray(27+segments+packet.size)
  val header=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
  header.put("OggS".toByteArray(Charsets.US_ASCII)).put(0).put(flags.toByte()).putLong(granule)
   .putInt(serial).putInt(sequence++).putInt(0).put(segments.toByte())
  repeat(segments) {header.put(if(it<segments-1) 255.toByte() else (packet.size%255).toByte())}
  header.put(packet)
  var crc=0
  for(byte in bytes) {crc=crc xor ((byte.toInt() and 255) shl 24);repeat(8) {crc=if(crc<0) (crc shl 1) xor 0x04c11db7 else crc shl 1}}
  header.putInt(22,crc)
  stream.write(bytes)
 }
}

/** MediaCodec returns either OpusHead itself or Android's length-prefixed unified codec data. */
internal fun opusIdentificationHeader(codecHeader:ByteBuffer,codecDelay:ByteBuffer?=null):ByteArray {
 var delay=codecDelay?.duplicate()?.order(ByteOrder.nativeOrder())?.let {require(it.remaining()==8);it.long}
 val buffer=codecHeader.duplicate().order(ByteOrder.nativeOrder())
 require(buffer.remaining() in 19..256) {"Opus codec header is invalid."}
 val bytes=ByteArray(buffer.remaining());buffer.get(bytes)
 fun marker(offset:Int)=String(bytes,offset,8,Charsets.US_ASCII)
 val head=if(marker(0)=="OpusHead") bytes else {
  var offset=0;var found:ByteArray?=null
  while(offset+16<=bytes.size) {
   val kind=marker(offset)
   require(kind in setOf("AOPUSHDR","AOPUSDLY","AOPUSPRL")) {"Opus codec header is invalid."}
   val size=ByteBuffer.wrap(bytes,offset+8,8).order(ByteOrder.nativeOrder()).long
   require(size>=0 && size<=bytes.size-offset-16) {"Opus codec header is incomplete."}
   if(kind=="AOPUSHDR") {require(found==null);found=bytes.copyOfRange(offset+16,offset+16+size.toInt())}
   else {
    require(size==8L)
    if(kind=="AOPUSDLY") {
     val unifiedDelay=ByteBuffer.wrap(bytes,offset+16,8).order(ByteOrder.nativeOrder()).long
     require(delay==null || delay==unifiedDelay) {"Opus codec delays disagree."}
     delay=unifiedDelay
    }
   }
   offset+=16+size.toInt()
  }
  require(offset==bytes.size)
  requireNotNull(found) {"Opus identification header is missing."}
 }
 require(head.size==19 && String(head,0,8,Charsets.US_ASCII)=="OpusHead" && head[8].toInt()==1 && head[9].toInt()==1 && head[18].toInt()==0) {"Recording must be mono Opus."}
 // Android's separate/unified codec delay overrides OpusHead and is expressed in nanoseconds.
 delay?.let {
  require(it in 0..1_365_312_500L) {"Opus codec delay is invalid."}
  val samples=(it*48_000+500_000_000)/1_000_000_000
  require(samples<=65_535)
  head[10]=samples.toByte();head[11]=(samples ushr 8).toByte()
 }
 return head
}

/** RFC 6716 section 3: packet duration in the Ogg Opus clock, always 48 kHz. */
internal fun opusPacketSamples(packet:ByteArray):Int {
 require(packet.isNotEmpty()) {"Opus packet is empty."}
 val toc=packet[0].toInt() and 255
 val config=toc ushr 3
 val frameSamples=when {
  config>=16 -> 120 shl (config and 3)
  config>=12 -> 480 shl (config and 1)
  config and 3==3 -> 2880
  else -> 480 shl (config and 3)
 }
 val frames=when(toc and 3) {0 -> 1;1,2 -> 2;else -> {require(packet.size>=2);packet[1].toInt() and 63}}
 require(frames>0 && frameSamples*frames<=5760) {"Opus packet duration is invalid."}
 return frameSamples*frames
}
