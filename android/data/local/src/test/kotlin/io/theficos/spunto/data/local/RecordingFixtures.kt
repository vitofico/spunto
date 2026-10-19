package io.theficos.spunto.data.local

/** Tiny generated Ogg container; only container tests, never real encoder/recognition quality. */
fun syntheticOgg(seconds:Int=2):ByteArray {
 fun little(value:Long,count:Int)=ByteArray(count) {(value ushr (it*8)).toByte()}
 fun page(number:Int,flags:Int,granule:Long,payload:ByteArray,lacing:ByteArray=byteArrayOf(payload.size.toByte())):ByteArray {
  val header="OggS".toByteArray()+byteArrayOf(0,flags.toByte())+little(granule,8)+little(42,4)+little(number.toLong(),4)+ByteArray(4)+byteArrayOf(lacing.size.toByte())+lacing
  val bytes=header+payload;var crc=0
  for(b in bytes) {crc=crc xor ((b.toInt() and 255) shl 24);repeat(8) {crc=if(crc<0) (crc shl 1) xor 0x04c11db7 else crc shl 1}}
  little(crc.toLong(),4).copyInto(bytes,22);return bytes
 }
 val head="OpusHead".toByteArray()+byteArrayOf(1,1)+little(0,2)+little(16_000,4)+byteArrayOf(0,0,0)
 val packets=minOf(seconds*50,200);val silence=ByteArray(packets*3) { when(it%3) {0->0xf8.toByte();1->0xff.toByte();else->0xfe.toByte()} }
 return page(0,2,0,head)+page(1,0,0,"OpusTags".toByteArray()+ByteArray(8))+page(2,4,seconds*48_000L,silence,ByteArray(packets) {3})
}

fun syntheticRewritePage(bytes:ByteArray,pageNumber:Int,flags:Int?=null,granule:Long?=null):ByteArray {
 val result=bytes.copyOf();var offset=0
 repeat(pageNumber) {val segments=result[offset+26].toInt() and 255;var size=0;repeat(segments) {size+=result[offset+27+it].toInt() and 255};offset+=27+segments+size}
 val segments=result[offset+26].toInt() and 255;var size=0;repeat(segments) {size+=result[offset+27+it].toInt() and 255};val end=offset+27+segments+size
 flags?.let {result[offset+5]=it.toByte()};granule?.let {value ->repeat(8) {result[offset+6+it]=(value ushr (8*it)).toByte()}}
 repeat(4) {result[offset+22+it]=0};var crc=0
 for(index in offset until end) {crc=crc xor ((result[index].toInt() and 255) shl 24);repeat(8) {crc=if(crc<0) (crc shl 1) xor 0x04c11db7 else crc shl 1}}
 repeat(4) {result[offset+22+it]=(crc ushr (8*it)).toByte()};return result
}
