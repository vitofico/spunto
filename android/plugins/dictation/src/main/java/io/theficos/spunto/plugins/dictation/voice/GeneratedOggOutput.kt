package io.theficos.spunto.plugins.dictation.voice

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile

/** Only a newly reserved encoder output can be sealed; retained/recovered clips use other APIs. */
internal class GeneratedOggOutput private constructor(private val file:File) {
 companion object {
  fun reserve(file:File):GeneratedOggOutput {
   file.parentFile?.let {require(it.isDirectory || it.mkdirs()) {"Recording folder is unavailable."}}
   require(file.createNewFile()) {"Use a new recording file."}
   return GeneratedOggOutput(file)
  }
 }

 /** MediaMuxer OGG may omit EOS. Successful codec EOF and stopped writer are prerequisites. */
 fun finish(codecEof:Boolean,muxerStopped:Boolean,inputFrames:Long) {
  require(codecEof && muxerStopped && inputFrames in 1..RECORDING_RATE*RECORDING_MAX_SECONDS) {"Encoder did not finish."}
  RandomAccessFile(file,"rw").use {stream ->
   val length=stream.length()
   require(length>0) {"Recording is empty."}
   val final=validate(stream)
   require(stream.filePointer==length && stream.length()==length) {"Recording changed during finalization."}
   if(!final.eos) {
    val page=final.page
    page[5]=(page[5].toInt() or 4).toByte()
    putCrc(page)
    stream.seek(final.offset)
    stream.write(page,0,27)
   }
   stream.fd.sync()
  }
 }

 private data class LastPage(val offset:Long,val page:ByteArray,val eos:Boolean)
 private fun validate(stream:RandomAccessFile):LastPage {
  var sequence=0L;var serial:Long?=null;var preskip=0L;var lastGranule=0L
  var packetIndex=0;var packetBytes=0;var pending=false;var audioPackets=0
  var headerPacket=ByteArrayOutputStream();var last:LastPage?=null
  while(stream.filePointer<stream.length()) {
   require(last?.eos!=true) {"Recording contains trailing data."}
   val offset=stream.filePointer
   require(stream.length()-offset>=27) {"Recording page is incomplete."}
   val header=ByteArray(27);stream.readFully(header)
   require(header.copyOfRange(0,4).contentEquals("OggS".toByteArray(Charsets.US_ASCII)) && header[4].toInt()==0) {"Recording must be Ogg Opus."}
   val lacing=ByteArray(header[26].toInt() and 255)
   require(stream.length()-stream.filePointer>=lacing.size);stream.readFully(lacing)
   val payload=ByteArray(lacing.sumOf {it.toInt() and 255})
   require(stream.length()-stream.filePointer>=payload.size) {"Recording page is incomplete."};stream.readFully(payload)
   val page=header+lacing+payload;val checksum=little(page,22,4)
   repeat(4) {page[22+it]=0}
   require((crc(page).toLong() and 0xffffffffL)==checksum) {"Recording checksum failed."}
   val flags=header[5].toInt() and 255
   require(flags and 0xf8==0 && ((flags and 1)!=0)==pending) {"Recording packet continuation is invalid."}
   require(little(header,18,4)==sequence) {"Recording page sequence is invalid."}
   val currentSerial=little(header,14,4)
   if(serial==null) {serial=currentSerial;require(flags and 2!=0)} else require(serial==currentSerial && flags and 2==0)
   var used=0;var pageAudio=0
   for(raw in lacing) {
    val size=raw.toInt() and 255
    packetBytes+=size;require(packetBytes<=65_536) {"Recording packet is too large."}
    if(packetIndex<2) headerPacket.write(payload,used,size)
    used+=size;pending=size==255
    if(!pending) {
     when(packetIndex) {
      0 -> {
       val packet=headerPacket.toByteArray()
       require(sequence==0L && packet.size==19 && packet.copyOfRange(0,8).contentEquals("OpusHead".toByteArray(Charsets.US_ASCII)) && packet[8].toInt()==1 && packet[9].toInt()==1 && packet[18].toInt()==0) {"Recording must be mono Opus."}
       preskip=little(packet,10,2)
      }
      1 -> validateTags(headerPacket.toByteArray())
      else -> {require(packetBytes>0) {"Recording contains an empty audio packet."};audioPackets++;pageAudio++}
     }
     packetIndex++;packetBytes=0;headerPacket=ByteArrayOutputStream()
    }
   }
   if(sequence==0L) require(packetIndex==1 && !pending) {"Opus identification needs its own page."}
   val granule=little(header,6,8)
   require(granule>=0 || granule==-1L) {"Recording granule is invalid."}
   if(packetIndex<=2) require(granule<=0) {"Headers cannot claim audio duration."}
   if(pageAudio==0 && packetIndex>2 && pending) require(granule==-1L) {"Unfinished audio cannot claim a granule."}
   if(granule>=0) {require(granule>=lastGranule) {"Recording granules moved backwards."};lastGranule=granule}
   require(lastGranule-preskip<=RECORDING_MAX_SECONDS*48_000) {"Recording duration is unsupported."}
   val eos=flags and 4!=0
   if(eos) require(audioPackets>0 && !pending && granule>preskip) {"Recording has no complete audio."}
   if(stream.filePointer==stream.length()) {
    // No trimming or guessing: the physical final page must complete audio at a known end.
    require(pageAudio>0 && audioPackets>0 && !pending && granule>preskip && (granule-preskip)*1000/48_000>=1) {"Recording final audio page is incomplete."}
   }
   last=LastPage(offset,page,eos);sequence++
  }
  return requireNotNull(last) {"Recording is empty."}
 }

 private fun validateTags(packet:ByteArray) {
  require(packet.size>=16 && packet.copyOfRange(0,8).contentEquals("OpusTags".toByteArray(Charsets.US_ASCII))) {"Recording has no Opus comment header."}
  val vendor=little(packet,8,4);require(vendor<=packet.size-16)
  var offset=12+vendor.toInt();val comments=little(packet,offset,4);offset+=4
  require(comments<=(packet.size-offset)/4)
  repeat(comments.toInt()) {require(offset+4<=packet.size);val size=little(packet,offset,4);offset+=4;require(size<=packet.size-offset);offset+=size.toInt()}
 }
 private fun little(bytes:ByteArray,offset:Int,count:Int):Long {var value=0L;repeat(count) {value=value or ((bytes[offset+it].toLong() and 255) shl (8*it))};return value}
 private fun crc(bytes:ByteArray):Int {
  var value=0
  for(byte in bytes) {value=value xor ((byte.toInt() and 255) shl 24);repeat(8) {value=if(value<0) (value shl 1) xor 0x04c11db7 else value shl 1}}
  return value
 }
 private fun putCrc(page:ByteArray) {repeat(4) {page[22+it]=0};val checksum=crc(page);repeat(4) {page[22+it]=(checksum ushr (8*it)).toByte()}}
}
