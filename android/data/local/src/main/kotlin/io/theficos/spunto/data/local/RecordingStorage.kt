package io.theficos.spunto.data.local

import java.io.File
import java.io.BufferedInputStream
import java.security.MessageDigest

const val LEGACY_UPLOAD_LIMIT=5_000_000L
const val APPROVED_UPLOAD_LIMIT=20_000_000L
const val IMAGE_IMPORT_LIMIT=5_000_000L

data class RecordingFile(val durationMs:Long,val byteSize:Long,val sha256:String)
/** Bounded container validation; it proves packet structure without claiming decoded audio quality. */
fun inspectRecording(file:File,maximumBytes:Long):RecordingFile {
 val parsed=scanRecording(file,maximumBytes,false)
 require(parsed.eos && parsed.audioPackets>0 && !parsed.pendingPacket) {"Recording is incomplete."}
 val duration=(parsed.lastGranule-parsed.preskip)*1000/48_000
 require(duration in 1..3_600_000L) {"Recording duration is unsupported."}
 return RecordingFile(duration,file.length(),attachmentDigest(file,maximumBytes).second)
}
private data class RecordingScan(val eos:Boolean,val audioPackets:Int,val pendingPacket:Boolean,val preskip:Long,val lastGranule:Long,val sealOffset:Long,val sealEnd:Long,val sealPage:ByteArray?)
private fun oggLittle(bytes:ByteArray,offset:Int,count:Int):Long {var n=0L;repeat(count) {n=n or ((bytes[offset+it].toLong() and 255) shl (8*it))};return n}
private fun oggCrc(bytes:ByteArray):Int {var n=0;for(byte in bytes) {n=n xor ((byte.toInt() and 255) shl 24);repeat(8) {n=if(n<0) (n shl 1) xor 0x04c11db7 else n shl 1}};return n}
private fun validateTags(packet:ByteArray) {
 require(packet.size>=16 && packet.copyOfRange(0,8).contentEquals("OpusTags".toByteArray())) {"Recording has no Opus comment header."}
 val vendor=oggLittle(packet,8,4);require(vendor<=packet.size-16)
 var offset=12+vendor.toInt();val comments=oggLittle(packet,offset,4);offset+=4
 require(comments<=(packet.size-offset)/4)
 repeat(comments.toInt()) {require(offset+4<=packet.size);val length=oggLittle(packet,offset,4);offset+=4;require(length<=packet.size-offset);offset+=length.toInt()}
}
private fun scanRecording(file:File,maximumBytes:Long,allowIncompleteTail:Boolean):RecordingScan {
 require(file.isFile && file.length() in 1..maximumBytes) {"Recording exceeds the upload limit or is unavailable."}
 var sequence=0L;var serial:Long?=null;var preskip=0L;var lastGranule=0L;var eos=false
 var packetIndex=0;var packetBytes=0;var continuation=false;var audioPackets=0
 var headerPacket=java.io.ByteArrayOutputStream();var sealOffset=-1L;var sealEnd=0L;var sealPage:ByteArray?=null
 java.io.RandomAccessFile(file,"r").use {stream ->
  while(stream.filePointer<stream.length()) {
   require(!eos) {"Recording contains trailing data."}
   val offset=stream.filePointer
   if(stream.length()-offset<27) {require(allowIncompleteTail) {"Recording is incomplete."};break}
   val header=ByteArray(27);stream.readFully(header)
   require(header.copyOfRange(0,4).contentEquals("OggS".toByteArray()) && header[4].toInt()==0) {"Recording must be Ogg Opus."}
   val count=header[26].toInt() and 255
   if(stream.length()-stream.filePointer<count) {require(allowIncompleteTail);break}
   val lacing=ByteArray(count);stream.readFully(lacing)
   val payloadSize=lacing.sumOf {it.toInt() and 255}
   if(stream.length()-stream.filePointer<payloadSize) {require(allowIncompleteTail);break}
   val payload=ByteArray(payloadSize);stream.readFully(payload)
   val page=header+lacing+payload;val checksum=oggLittle(page,22,4);repeat(4) {page[22+it]=0}
   require((oggCrc(page).toLong() and 0xffffffffL)==checksum) {"Recording checksum failed."}
   val flags=header[5].toInt() and 255
   require((flags and 0xf8)==0 && ((flags and 1)!=0)==continuation) {"Recording packet continuation is invalid."}
   require(oggLittle(header,18,4)==sequence)
   val currentSerial=oggLittle(header,14,4)
   if(serial==null) {serial=currentSerial;require((flags and 2)!=0)} else require(currentSerial==serial && (flags and 2)==0)
   var used=0;var pageAudio=0
   for(raw in lacing) {
    val size=raw.toInt() and 255
    packetBytes+=size;require(packetBytes<=65_536) {"Recording packet is too large."}
    if(packetIndex<2) headerPacket.write(payload,used,size)
    used+=size;continuation=size==255
    if(!continuation) {
     when(packetIndex) {
      0 -> {val packet=headerPacket.toByteArray();require(sequence==0L && packet.size==19 && packet.copyOfRange(0,8).contentEquals("OpusHead".toByteArray()) && packet[8].toInt()==1 && packet[9].toInt()==1 && packet[18].toInt()==0) {"Recording must be mono Opus."};preskip=oggLittle(packet,10,2)}
      1 -> validateTags(headerPacket.toByteArray())
      else -> {require(packetBytes>0) {"Recording contains an empty audio packet."};audioPackets++;pageAudio++}
     }
     packetIndex++;packetBytes=0;headerPacket=java.io.ByteArrayOutputStream()
    }
   }
   if(sequence==0L) require(packetIndex==1 && !continuation) {"Opus identification needs its own complete page."}
   val granule=oggLittle(header,6,8)
   require(granule>=0 || granule==-1L)
   if(packetIndex<=2) require(granule<=0) {"Headers cannot claim audio duration."}
   if(pageAudio==0 && packetIndex>2 && continuation) require(granule==-1L) {"An unfinished packet cannot claim a completed granule."}
   if(granule>=0) {require(granule>=lastGranule);lastGranule=granule}
   require(lastGranule-preskip<=3_600L*48_000) {"Recording duration is unsupported."}
   eos=(flags and 4)!=0
   if(eos) require(packetIndex>=3 && audioPackets>0 && !continuation && granule>preskip) {"Recording has no completed audio."}
   if(pageAudio>0 && !continuation && granule>preskip) {sealOffset=offset;sealEnd=stream.filePointer;sealPage=page}
   sequence++
  }
 }
 return RecordingScan(eos,audioPackets,continuation,preskip,lastGranule,sealOffset,sealEnd,sealPage)
}
fun attachmentDigest(file:File,maximumBytes:Long):Pair<Long,String> {
 require(file.isFile && file.length() in 1..maximumBytes) {"Attachment exceeds the upload limit or is unavailable."}
 val digest=MessageDigest.getInstance("SHA-256");var size=0L
 file.inputStream().use {input -> val buffer=ByteArray(32*1024);while(true) {val count=input.read(buffer);if(count<0) break;size+=count;require(size<=maximumBytes);digest.update(buffer,0,count)} }
 return size to digest.digest().joinToString("") {"%02x".format(it)}
}

fun syncRecordingDirectory(directory:File) {
 java.nio.channels.FileChannel.open(directory.toPath(),java.nio.file.StandardOpenOption.READ).use {it.force(true)}
}

/** Crash recovery only: seal a proven complete audio packet page, never mutate retained blobs. */
internal fun recoverInterruptedRecording(file:File,maximumBytes:Long):RecordingFile {
 try {return inspectRecording(file,maximumBytes)} catch(_:IllegalArgumentException) { }
 val parsed=scanRecording(file,maximumBytes,true)
 require(!parsed.eos && parsed.audioPackets>0 && parsed.sealOffset>=0) {"Recording has no completed audio to recover."}
 val sealed=requireNotNull(parsed.sealPage)
 sealed[5]=(sealed[5].toInt() or 4).toByte()
 val checksum=oggCrc(sealed);repeat(4) {sealed[22+it]=(checksum ushr (8*it)).toByte()}
 java.io.RandomAccessFile(file,"rw").use {stream ->stream.seek(parsed.sealOffset);stream.write(sealed);stream.setLength(parsed.sealEnd);stream.fd.sync()}
 return inspectRecording(file,maximumBytes)
}
