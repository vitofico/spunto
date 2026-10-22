package io.theficos.spunto.plugins.dictation.voice

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.*
import org.junit.Assert.*

class OpusOggWriterTest {
 private lateinit var folder:File
 @Before fun setup() {folder=kotlin.io.path.createTempDirectory("opus-ogg").toFile()}
 @After fun close() {folder.deleteRecursively()}
 private fun header(skip:Int=312)=ByteBuffer.allocate(19).order(ByteOrder.LITTLE_ENDIAN)
  .put("OpusHead".toByteArray()).put(1).put(1).putShort(skip.toShort()).putInt(16_000).putShort(0).put(0).array()
 private fun long(value:Long)=ByteBuffer.allocate(8).order(ByteOrder.nativeOrder()).putLong(value).array()
 private fun unified(head:ByteArray,delay:Long=6_500_000L):ByteArray=
  "AOPUSHDR".toByteArray()+long(head.size.toLong())+head+"AOPUSDLY".toByteArray()+long(8)+long(delay)+"AOPUSPRL".toByteArray()+long(8)+long(80_000_000)
 private fun packet(size:Int=20)=ByteArray(size).also {it[0]=0xf8.toByte()}
 private data class Page(val flags:Int,val granule:Long,val serial:Int,val sequence:Int,val laces:List<Int>,val payload:ByteArray,val bytes:ByteArray)
 private fun pages(file:File):List<Page> {
  val bytes=file.readBytes();val result=mutableListOf<Page>();var offset=0
  while(offset<bytes.size) {
   val buffer=ByteBuffer.wrap(bytes,offset,bytes.size-offset).slice().order(ByteOrder.LITTLE_ENDIAN)
   val marker=ByteArray(4);buffer.get(marker);assertEquals("OggS",String(marker));assertEquals(0,buffer.get().toInt())
   val flags=buffer.get().toInt();val granule=buffer.long;val serial=buffer.int;val sequence=buffer.int;buffer.int
   val laces=List(buffer.get().toInt() and 255) {buffer.get().toInt() and 255}
   val payload=ByteArray(laces.sum());buffer.get(payload)
   val length=27+laces.size+payload.size
   result+=Page(flags,granule,serial,sequence,laces,payload,bytes.copyOfRange(offset,offset+length));offset+=length
  }
  return result
 }
 private fun assertCrc(page:ByteArray) {
  val bytes=page.copyOf();val expected=ByteBuffer.wrap(bytes,22,4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xffffffffL
  bytes.fill(0,22,26)
  var crc=0L
  for(byte in bytes) {crc=crc xor ((byte.toLong() and 255) shl 24);repeat(8) {crc=((crc shl 1) xor if(crc and 0x80000000L!=0L) 0x04c11db7L else 0L) and 0xffffffffL}}
  assertEquals(expected,crc)
 }
 @Test fun completePacketsHaveIndependentSequenceCrcAndExact255LacingTerminator() {
  for(size in listOf(1,255,510,65_024)) {
   val file=File(folder,"packet-$size.ogg");val output=GeneratedOggOutput.reserve(file)
   val writer=OpusOggWriter(file,ByteBuffer.wrap(header()))
   writer.write(packet(size));writer.finish(216);writer.close()
   val pages=pages(file);assertEquals(3,pages.size)
   assertEquals(listOf(2,0,4),pages.map {it.flags});assertEquals(listOf(0,1,2),pages.map {it.sequence})
   assertEquals(1,pages.map {it.serial}.distinct().size);assertEquals(listOf(0L,0L,960L),pages.map {it.granule})
   assertArrayEquals(header(),pages[0].payload);assertArrayEquals(packet(size),pages.last().payload)
   assertEquals(size/255+1,pages.last().laces.size);assertEquals(size%255,pages.last().laces.last())
   pages.forEach {assertCrc(it.bytes)}
   val original=file.readBytes();output.finish(true,true,216);assertArrayEquals(original,file.readBytes())
  }
 }
 @Test fun finalPacketTrimsToActualNonAlignedPcmAndCodecPreskip() {
  val file=File(folder,"trim.ogg");val output=GeneratedOggOutput.reserve(file)
  OpusOggWriter(file,ByteBuffer.wrap(header())).use {writer ->writer.write(packet());writer.write(packet());writer.finish(321)}
  assertEquals(listOf(0L,0L,960L,1275L),pages(file).map {it.granule})
  output.finish(true,true,321)
 }
 @Test fun completedPagesAreVisibleBeforeCloseAndWholeFlushPaddingPacketsAreDiscarded() {
  val file=File(folder,"live.ogg");val output=GeneratedOggOutput.reserve(file)
  val writer=OpusOggWriter(file,ByteBuffer.wrap(header()))
  writer.write(packet());writer.write(packet())
  assertEquals(3,pages(file).size);assertEquals(960L,pages(file).last().granule)
  writer.endInput(321);assertTrue(writer.hasEndAudio)
  repeat(4) {writer.write(packet())}
  writer.finish(321);writer.close();output.finish(true,true,321)
  assertEquals(listOf(0L,0L,960L,1275L),pages(file).map {it.granule})
  val exact=File(folder,"exact.ogg");GeneratedOggOutput.reserve(exact)
  OpusOggWriter(exact,ByteBuffer.wrap(header())).use {
   it.endInput(216);it.write(packet());repeat(3) {_ ->it.write(packet())};it.finish(216)
  }
  assertEquals(3,pages(exact).size);assertEquals(4,pages(exact).last().flags)
 }
 @Test fun unifiedAndSeparateCodecDelayNormalizeThe48khzPreskip() {
  val head=header(104)
  assertArrayEquals(header(),opusIdentificationHeader(ByteBuffer.wrap(unified(head))))
  assertArrayEquals(header(),opusIdentificationHeader(ByteBuffer.wrap(head),ByteBuffer.wrap(long(6_500_000))))
  val padded=ByteBuffer.wrap(byteArrayOf(0)+head+byteArrayOf(0));padded.position(1);padded.limit(20)
  assertArrayEquals(head,opusIdentificationHeader(padded));assertEquals(1,padded.position())
  assertTrue(runCatching {opusIdentificationHeader(ByteBuffer.wrap(unified(head)),ByteBuffer.wrap(long(7_000_000)))}.isFailure)
  for(bytes in listOf(ByteArray(0),header().copyOf(18),unified(head).copyOf(30),header().also {it[9]=2})) {
   assertTrue(runCatching {opusIdentificationHeader(ByteBuffer.wrap(bytes))}.isFailure)
  }
  for(delay in listOf(-1L,Long.MAX_VALUE)) assertTrue(runCatching {opusIdentificationHeader(ByteBuffer.wrap(head),ByteBuffer.wrap(long(delay)))}.isFailure)
 }
 @Test fun opusModesAndFrameCodesUseThe48khzClockAndRejectInvalidCounts() {
  assertEquals(120,opusPacketSamples(byteArrayOf(0x80.toByte())))
  assertEquals(240,opusPacketSamples(byteArrayOf(0x81.toByte())))
  assertEquals(240,opusPacketSamples(byteArrayOf(0x82.toByte())))
  assertEquals(960,opusPacketSamples(byteArrayOf(0xf8.toByte())))
  assertEquals(480,opusPacketSamples(byteArrayOf(0x60)))
  assertEquals(2880,opusPacketSamples(byteArrayOf(0x18)))
  assertEquals(5760,opusPacketSamples(byteArrayOf(0x83.toByte(),48)))
  for(bytes in listOf(ByteArray(0),byteArrayOf(0x83.toByte()),byteArrayOf(0x83.toByte(),0),byteArrayOf(0x83.toByte(),49),byteArrayOf(0x1b,3))) {
   assertTrue(runCatching {opusPacketSamples(bytes)}.isFailure)
  }
 }
 @Test fun incompleteOrImpossibleSampleCountsCannotClaimFinalEosAndExistingFilesArePreserved() {
  val original=byteArrayOf(9,8,7);val existing=File(folder,"existing").apply {writeBytes(original)}
  assertTrue(runCatching {OpusOggWriter(existing,ByteBuffer.wrap(header()))}.isFailure);assertArrayEquals(original,existing.readBytes())
  for(frames in listOf(0L,1L,10_000L)) {
   val file=File(folder,"bad-$frames");GeneratedOggOutput.reserve(file)
   OpusOggWriter(file,ByteBuffer.wrap(header())).use {writer ->
    if(frames>0) {writer.write(packet());writer.write(packet())}
    assertTrue(runCatching {writer.finish(frames)}.isFailure)
   }
   assertTrue(pages(file).none {it.flags and 4!=0})
  }
  val file=File(folder,"closed");GeneratedOggOutput.reserve(file)
  val writer=OpusOggWriter(file,ByteBuffer.wrap(header()));writer.write(packet());writer.finish(216)
  assertTrue(runCatching {writer.write(packet())}.isFailure);assertTrue(runCatching {writer.finish(216)}.isFailure);writer.close()
  val interrupted=File(folder,"interrupted");GeneratedOggOutput.reserve(interrupted)
  val closed=OpusOggWriter(interrupted,ByteBuffer.wrap(header()));closed.close()
  assertTrue(runCatching {closed.write(packet())}.isFailure);assertTrue(runCatching {closed.finish(216)}.isFailure)
 }
}
