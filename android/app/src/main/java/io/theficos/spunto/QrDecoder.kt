package io.theficos.spunto

import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer

/** Decode only a bounded luminance plane locally; never persist or log a frame or payload. */
internal fun decodeQrLuma(bytes:ByteArray,width:Int,height:Int):String? {
 val pixels=width.toLong()*height
 if(width<=0 || height<=0 || pixels>2_073_600 || bytes.size<pixels) return null
 val reader=MultiFormatReader()
 return try {
  val source=PlanarYUVLuminanceSource(bytes,width,height,0,0,width,height,false)
  reader.decode(BinaryBitmap(HybridBinarizer(source)),mapOf(DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),DecodeHintType.TRY_HARDER to true,DecodeHintType.ALSO_INVERTED to true)).text.takeIf {it.length<=8192}
 } catch(_:Exception) {null}
 finally {reader.reset()}
}
