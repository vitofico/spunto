package io.theficos.spunto

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt

internal data class QrCameraOption(val id:Int,val rear:Boolean,val orientation:Int)
internal data class QrPreviewSize(val width:Int,val height:Int) {val pixels:Long get()=width.toLong()*height}
internal data class QrFocusRect(val left:Int,val top:Int,val right:Int,val bottom:Int)

/** Only expose cameras reported by Android; prefer the rear camera for printed codes. */
internal fun qrCameraOrder(cameras:List<QrCameraOption>)=cameras.sortedBy {!it.rear}
internal fun qrFocusMode(supported:List<String>)=listOf("continuous-picture","continuous-video","auto","macro").firstOrNull {it in supported}
internal fun qrDisplayOrientation(sensor:Int,display:Int,front:Boolean):Int {
 val rotation=((display%360)+360)%360
 return if(front) (360-(sensor+rotation)%360)%360 else (sensor-rotation+360)%360
}
internal fun qrPreviewSize(sizes:List<QrPreviewSize>):QrPreviewSize? = sizes
 .filter {it.width>0 && it.height>0 && it.pixels<=2_073_600}
 .minWithOrNull(compareBy<QrPreviewSize> {abs(it.width.toDouble()/it.height-4.0/3)}.thenBy {abs(it.pixels-921_600)})

/** Center-crop a correctly rotated preview without stretching the camera image. */
internal fun qrPreviewBounds(size:QrPreviewSize,orientation:Int,width:Int,height:Int):QrPreviewSize {
 val rotated=orientation%180!=0
 val sourceWidth=if(rotated) size.height else size.width
 val sourceHeight=if(rotated) size.width else size.height
 val scale=max(width.coerceAtLeast(1).toDouble()/sourceWidth,height.coerceAtLeast(1).toDouble()/sourceHeight)
 return QrPreviewSize(ceil(sourceWidth*scale).toInt(),ceil(sourceHeight*scale).toInt())
}

/** Invert display rotation/mirroring into Camera1's sensor-relative -1000..1000 area. */
internal fun qrFocusRect(x:Float,y:Float,width:Int,height:Int,orientation:Int,front:Boolean):QrFocusRect {
 val displayX=if(x.isFinite()) (x/width.coerceAtLeast(1)*2000-1000).coerceIn(-1000f,1000f) else 0f
 val displayY=if(y.isFinite()) (y/height.coerceAtLeast(1)*2000-1000).coerceIn(-1000f,1000f) else 0f
 val (sensorX,sensorY)=when(orientation) {
  90 -> displayY to -displayX
  180 -> -displayX to -displayY
  270 -> -displayY to displayX
  else -> displayX to displayY
 }
 val centerX=(if(front) -sensorX else sensorX).roundToInt().coerceIn(-900,900)
 val centerY=sensorY.roundToInt().coerceIn(-900,900)
 return QrFocusRect(centerX-100,centerY-100,centerX+100,centerY+100)
}

internal data class QrDecodeTicket(val generation:Long,val sequence:Long)
/** One bounded decoder job; obsolete camera/surface results can never pair a device. */
internal class QrDecodeGate(private val intervalMs:Long=200) {
 private var generation=0L
 private var sequence=0L
 private var busy:QrDecodeTicket?=null
 private var lastAttempt:Long?=null
 private var delivered=false
 private var closed=false
 @Synchronized fun begin(nowMs:Long):QrDecodeTicket? {
  val last=lastAttempt
  if(closed || delivered || busy!=null || last!=null && nowMs>=last && nowMs-last<intervalMs) return null
  lastAttempt=nowMs
  return QrDecodeTicket(generation,++sequence).also {busy=it}
 }
 @Synchronized fun complete(ticket:QrDecodeTicket,found:Boolean):Boolean {
  if(busy!=ticket) return false
  busy=null
  if(closed || delivered || ticket.generation!=generation || !found) return false
  delivered=true
  return true
 }
 @Synchronized fun invalidate() {generation++;delivered=false}
 @Synchronized fun close() {closed=true;generation++;delivered=false}
}
