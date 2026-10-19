package io.theficos.spunto

import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import org.junit.Assert.*
import org.junit.Test

class QrDecoderTest {
 private val payload="synthetic-pairing-fixture-not-a-credential"
 private val size=240
 private fun image():ByteArray {
  val matrix=QRCodeWriter().encode(payload,BarcodeFormat.QR_CODE,size,size)
  return ByteArray(size*size) {index ->if(matrix[index%size,index/size]) 0 else 255.toByte()}
 }
 @Test fun syntheticQrDecodesLocallyInBothOrientationsAndInverted() {
  val image=image()
  assertEquals(payload,decodeQrLuma(image,size,size))
  val rotated=ByteArray(image.size) {index ->image[(size-1-index%size)*size+index/size]}
  assertEquals(payload,decodeQrLuma(rotated,size,size))
  assertEquals(payload,decodeQrLuma(image.map {(255-(it.toInt() and 255)).toByte()}.toByteArray(),size,size))
 }
 @Test fun malformedBlankOrOversizeFramesAreRejectedWithoutThrowing() {
  assertNull(decodeQrLuma(byteArrayOf(),0,0))
  assertNull(decodeQrLuma(byteArrayOf(0),size,size))
  assertNull(decodeQrLuma(ByteArray(size*size) {255.toByte()},size,size))
  assertNull(decodeQrLuma(byteArrayOf(),4000,3000))
 }
}
