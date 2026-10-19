package io.theficos.spunto

import org.junit.Assert.*
import org.junit.Test

class QrScanPolicyTest {
 @Test fun rearCamerasComeFirstAndAutofocusUsesOnlySupportedModes() {
  val cameras=listOf(QrCameraOption(0,false,270),QrCameraOption(1,true,90),QrCameraOption(2,true,90))
  assertEquals(listOf(1,2,0),qrCameraOrder(cameras).map {it.id})
  assertEquals("continuous-picture",qrFocusMode(listOf("fixed","auto","continuous-picture")))
  assertEquals("auto",qrFocusMode(listOf("fixed","auto")))
  assertNull(qrFocusMode(listOf("fixed")))
 }
 @Test fun previewIsBoundedSupportedAndNeverStretched() {
  val chosen=qrPreviewSize(listOf(QrPreviewSize(4000,3000),QrPreviewSize(1920,1080),QrPreviewSize(1280,960),QrPreviewSize(640,480)))
  assertEquals(QrPreviewSize(1280,960),chosen)
  assertNull(qrPreviewSize(listOf(QrPreviewSize(4000,3000),QrPreviewSize(0,0))))
  assertEquals(QrPreviewSize(400,534),qrPreviewBounds(QrPreviewSize(640,480),90,400,300))
  assertEquals(QrPreviewSize(400,300),qrPreviewBounds(QrPreviewSize(640,480),0,400,300))
 }
 @Test fun sensorAndDisplayRotationIncludeFrontMirrorCompensation() {
  assertEquals(90,qrDisplayOrientation(90,0,false))
  assertEquals(0,qrDisplayOrientation(90,90,false))
  assertEquals(270,qrDisplayOrientation(90,180,false))
  assertEquals(90,qrDisplayOrientation(270,0,true))
  assertEquals(0,qrDisplayOrientation(270,90,true))
 }
 @Test fun tappedFocusMapsRotationMirrorAndEdgesIntoValidCameraAreas() {
  assertEquals(QrFocusRect(-100,-100,100,100),qrFocusRect(100f,150f,200,300,90,false))
  assertEquals(QrFocusRect(-1000,800,-800,1000),qrFocusRect(0f,0f,200,300,90,false))
  assertEquals(QrFocusRect(800,800,1000,1000),qrFocusRect(0f,0f,200,300,90,true))
  assertEquals(QrFocusRect(-100,-100,100,100),qrFocusRect(Float.NaN,Float.NaN,0,0,0,false))
 }
 @Test fun decoderIsSingleFlightThrottledAndDeliversOnlyOnce() {
  val gate=QrDecodeGate();val first=gate.begin(0)!!
  assertNull(gate.begin(1000));assertFalse(gate.complete(first,false))
  assertNull(gate.begin(199));val next=gate.begin(200)!!
  assertTrue(gate.complete(next,true));assertNull(gate.begin(1000))
  assertFalse(gate.complete(next,true))
 }
 @Test fun pausedOrSwitchedCameraCannotDeliverItsOldResultOrQueueMoreWork() {
  val gate=QrDecodeGate();val old=gate.begin(0)!!
  gate.invalidate();assertNull(gate.begin(1000));assertFalse(gate.complete(old,true))
  val current=gate.begin(1001)!!;assertTrue(gate.complete(current,true))
  gate.invalidate();val closing=gate.begin(2000)!!;gate.close()
  assertFalse(gate.complete(closing,true));assertNull(gate.begin(3000))
 }
 @Test fun switchingOrRetryingDoesNotResetTheDecodeRateLimit() {
  val gate=QrDecodeGate();val first=gate.begin(1000)!!
  assertFalse(gate.complete(first,false));gate.invalidate()
  assertNull(gate.begin(1001));gate.invalidate();assertNull(gate.begin(1199))
  val next=gate.begin(1200)!!;assertFalse(gate.complete(next,false))
 }
}
