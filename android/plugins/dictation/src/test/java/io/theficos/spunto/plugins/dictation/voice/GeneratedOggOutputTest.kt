package io.theficos.spunto.plugins.dictation.voice

import java.io.File
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.rules.Timeout
import java.util.concurrent.TimeUnit

/** Independent structural fixtures, not decoded Opus or microphone/quality claims. */
class GeneratedOggOutputTest {
 @get:org.junit.Rule val timeout=Timeout(10,TimeUnit.MINUTES)
 private lateinit var folder:File
 @Before fun setup() {folder=kotlin.io.path.createTempDirectory("generated-ogg").toFile()}
 @After fun cleanup() {folder.listFiles()?.forEach {it.delete()};folder.delete()}
 private fun put(bytes:ByteArray,offset:Int,value:Long,count:Int) {repeat(count) {bytes[offset+it]=(value ushr (8*it)).toByte()}}
 private fun checksum(bytes:ByteArray):Long {
  var value=0L
  for(byte in bytes) {
   value=value xor ((byte.toLong() and 255) shl 24)
   repeat(8) {value=((value shl 1) xor (if(value and 0x80000000L!=0L) 0x04c11db7L else 0L)) and 0xffffffffL}
  }
  return value
 }
 private fun page(sequence:Int,flags:Int,granule:Long,payload:ByteArray,lacing:ByteArray=byteArrayOf(payload.size.toByte()),serial:Long=17):ByteArray {
  val header=ByteArray(27)
  "OggS".toByteArray().copyInto(header);header[5]=flags.toByte();put(header,6,granule,8);put(header,14,serial,4);put(header,18,sequence.toLong(),4);header[26]=lacing.size.toByte()
  return (header+lacing+payload).also {put(it,22,checksum(it),4)}
 }
 private fun head(channels:Int=1):ByteArray=ByteArray(19).also {
  "OpusHead".toByteArray().copyInto(it);it[8]=1;it[9]=channels.toByte();put(it,10,312,2);put(it,12,16_000,4)
 }
 private fun tags():ByteArray="OpusTags".toByteArray()+ByteArray(8)
 private fun headers():ByteArray=page(0,2,0,head())+page(1,0,0,tags())
 private fun valid(eos:Boolean=false):ByteArray=headers()+page(2,if(eos) 4 else 0,96_312,byteArrayOf(0x78,1,2,3))
 private fun prepare(bytes:ByteArray):Pair<File,GeneratedOggOutput> {
  val file=File(folder,"fixture-${System.nanoTime()}.partial");val output=GeneratedOggOutput.reserve(file);file.writeBytes(bytes);return file to output
 }
 private fun refuses(bytes:ByteArray) {
  val (file,output)=prepare(bytes)
  assertTrue(runCatching {output.finish(true,true,32_000)}.isFailure)
  assertArrayEquals("Refusal must preserve every original byte",bytes,file.readBytes())
 }
 @Test fun reserveRefusesBothEmptyAndNonemptyExistingFilesWithoutOverwriting() {
  for(bytes in listOf(ByteArray(0),valid())) {
   val file=File(folder,"existing-${bytes.size}").apply {writeBytes(bytes)}
   assertTrue(runCatching {GeneratedOggOutput.reserve(file)}.isFailure);assertArrayEquals(bytes,file.readBytes())
  }
 }
 @Test fun finalizationRequiresActualCodecEofMuxerStopAndNonemptyPcm() {
  for(proof in listOf(Triple(false,true,32_000L),Triple(true,false,32_000L),Triple(true,true,0L),Triple(true,true,57_600_001L))) {
   val bytes=valid();val (file,output)=prepare(bytes)
   assertTrue(runCatching {output.finish(proof.first,proof.second,proof.third)}.isFailure);assertArrayEquals(bytes,file.readBytes())
  }
 }
 @Test fun absentEosChangesOnlyFinalFlagAndCrcAndLeavesAllPacketsIntact() {
  val before=valid();val (file,output)=prepare(before);output.finish(true,true,32_000)
  val after=file.readBytes();val offset=headers().size
  assertArrayEquals(valid(true),after);assertEquals(before.size,after.size)
  before.indices.filter {it!=offset+5 && it !in offset+22..offset+25}.forEach {assertEquals(before[it],after[it])}
  val final=after.copyOfRange(offset,after.size);val expected=(22..25).foldIndexed(0L) {index,acc,position ->acc or ((final[position].toLong() and 255) shl (index*8))}
  repeat(4) {final[22+it]=0};assertEquals(expected,checksum(final))
 }
 @Test fun existingValidEosRemainsByteIdentical() {
  val before=valid(true);val (file,output)=prepare(before);output.finish(true,true,32_000);assertArrayEquals(before,file.readBytes())
 }
 @Test fun headerOnlyPositiveEosAndEmptyAudioCannotBecomeRecordings() {
  refuses(page(0,2,0,head())+page(1,4,96_312,tags()))
  refuses(headers()+page(2,4,96_312,ByteArray(0)))
  refuses(headers())
 }
 @Test fun malformedHeadOrTagsNeverMutates() {
  refuses(page(0,2,0,head(2))+page(1,0,0,tags())+page(2,0,96_312,byteArrayOf(1)))
  refuses(page(0,2,0,head())+page(1,0,0,"not tags".toByteArray())+page(2,0,96_312,byteArrayOf(1)))
  val truncatedVendor=tags().also {put(it,8,1,4)}
  refuses(page(0,2,0,head())+page(1,0,0,truncatedVendor)+page(2,0,96_312,byteArrayOf(1)))
 }
 @Test fun crcVersionSequenceAndSerialFailuresNeverMutate() {
  refuses(valid().also {it[it.lastIndex]=(it.last().toInt() xor 1).toByte()})
  refuses(headers()+page(3,0,96_312,byteArrayOf(1)))
  refuses(headers()+page(2,0,96_312,byteArrayOf(1),serial=18))
  val version=page(2,0,96_312,byteArrayOf(1)).also {it[4]=1;repeat(4) {index ->it[22+index]=0};put(it,22,checksum(it),4)}
  refuses(headers()+version)
  refuses(headers()+page(2,2,96_312,byteArrayOf(1)))
  refuses(page(0,0,0,head())+page(1,0,0,tags())+page(2,0,96_312,byteArrayOf(1)))
 }
 @Test fun continuationMustMatchLacingAndFinalAudioMustComplete() {
  refuses(headers()+page(2,1,96_312,byteArrayOf(1)))
  refuses(headers()+page(2,0,-1,ByteArray(255) {1},byteArrayOf(255.toByte()))+page(3,0,96_312,byteArrayOf(2)))
  refuses(headers()+page(2,0,-1,ByteArray(255) {1},byteArrayOf(255.toByte())))
  refuses(headers()+page(2,0,96_312,ByteArray(255) {1},byteArrayOf(255.toByte())))
 }
 @Test fun validContinuedAudioSealsOnlyItsCompleteFinalPage() {
  val first=page(2,0,-1,ByteArray(255) {1},byteArrayOf(255.toByte()))
  val before=headers()+first+page(3,1,96_312,byteArrayOf(2))
  val (file,output)=prepare(before);output.finish(true,true,32_000)
  assertArrayEquals(headers()+first+page(3,5,96_312,byteArrayOf(2)),file.readBytes())
 }
 @Test fun trailingIncompleteBytesOrAnEosFollowedByPagesNeverGetsTrimmed() {
  refuses(valid()+byteArrayOf(1));refuses(valid(true)+page(3,0,96_312,byteArrayOf(1)))
  refuses(valid()+page(3,0,96_312,ByteArray(0),ByteArray(0)))
 }
 @Test fun unknownRegressingZeroAndExcessiveFinalGranulesNeverGetInvented() {
  for(granule in listOf(-1L,-2L,0L,312L,172_800_313L)) refuses(headers()+page(2,0,granule,byteArrayOf(1)))
  refuses(valid()+page(3,0,48_312,byteArrayOf(1)))
 }
 @Test fun oversizedContinuedAudioPacketNeverMutates() {
  val body=ByteArray(65_025) {1};val lacing=ByteArray(255) {255.toByte()}
  refuses(headers()+page(2,0,-1,body,lacing)+page(3,1,96_312,ByteArray(512) {1},byteArrayOf(255.toByte(),255.toByte(),2)))
 }
}
