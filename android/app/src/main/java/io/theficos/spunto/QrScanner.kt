@file:Suppress("DEPRECATION")
@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package io.theficos.spunto

import android.content.Context
import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.ImageFormat
import android.graphics.Rect
import android.hardware.Camera
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.Surface as CameraSurface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.widget.FrameLayout
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.core.content.ContextCompat
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import io.theficos.spunto.core.design.SpuntoSpacing

private data class QrCameraUi(
 val ready:Boolean=false,val focus:Boolean=false,val torch:Boolean=false,val light:Boolean=false,
 val error:Boolean=false,val selected:Int=0,val cameras:List<String> = emptyList(),val message:String="Starting camera…",
)

@Composable fun QrScanner(onValue:(String)->Unit) {
 val context=LocalContext.current
 val lifecycle=LocalLifecycleOwner.current.lifecycle
 val latest by rememberUpdatedState(onValue)
 var state by remember {mutableStateOf(QrCameraUi())}
 var cameraMenu by remember {mutableStateOf(false)}
 val preview=remember(context) {QrCameraPreview(context,{state=it},{latest(it)})}
 DisposableEffect(preview,lifecycle) {
  val observer=LifecycleEventObserver {_,event ->
   when(event) {Lifecycle.Event.ON_RESUME -> preview.resume();Lifecycle.Event.ON_PAUSE, Lifecycle.Event.ON_STOP -> preview.pause();else -> Unit}
  }
  lifecycle.addObserver(observer)
  if(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) preview.resume()
  onDispose {lifecycle.removeObserver(observer);preview.close()}
 }
 Column(verticalArrangement=Arrangement.spacedBy(SpuntoSpacing.s8)) {
  Surface(shape=MaterialTheme.shapes.medium,color=MaterialTheme.colorScheme.scrim) {
   AndroidView(factory={preview},modifier=Modifier.fillMaxWidth().height(300.dp))
  }
  Text(state.message,style=MaterialTheme.typography.bodySmall,color=if(state.error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,modifier=Modifier.semantics {liveRegion=LiveRegionMode.Polite})
  FlowRow(horizontalArrangement=Arrangement.spacedBy(SpuntoSpacing.s4)) {
   if(state.focus) TextButton(enabled=state.ready,onClick=preview::focusCenter) {Text("Focus")}
   if(state.torch) TextButton(enabled=state.ready,onClick=preview::toggleLight) {Text(if(state.light) "Light off" else "Light on")}
   if(state.cameras.size>1) Box {
    TextButton(onClick={cameraMenu=true}) {Text(state.cameras.getOrElse(state.selected) {"Camera"}+" ▾")}
    DropdownMenu(expanded=cameraMenu,onDismissRequest={cameraMenu=false}) {
     state.cameras.forEachIndexed {index,label -> DropdownMenuItem(text={Text(label)},onClick={cameraMenu=false;preview.select(index)})}
    }
   }
   if(state.error) TextButton(onClick=preview::retry) {Text("Retry camera")}
  }
 }
}

/** Camera1 only exposes the devices made public by the phone; hidden physical lenses stay hidden. */
private class QrCameraPreview(context:Context,private val changed:(QrCameraUi)->Unit,private val found:(String)->Unit):FrameLayout(context),SurfaceHolder.Callback {
 private val surface=SurfaceView(context)
 private val handler=Handler(Looper.getMainLooper())
 private val executor=Executors.newSingleThreadExecutor()
 private val gate=QrDecodeGate()
 private val cameras=runCatching {qrCameraOrder((0 until Camera.getNumberOfCameras()).map {id ->
  val info=Camera.CameraInfo();Camera.getCameraInfo(id,info);QrCameraOption(id,info.facing==Camera.CameraInfo.CAMERA_FACING_BACK,info.orientation)
 })}.getOrDefault(emptyList())
 private val names=cameras.mapIndexed {index,camera ->
  val same=cameras.take(index+1).count {it.rear==camera.rear}
  (if(camera.rear) "Back camera" else "Front camera")+if(same>1) " $same" else ""
 }
 private var ui=QrCameraUi(cameras=names)
 private var camera:Camera?=null
 private var selected=0
 private var resumed=false
 private var closed=false
 private var delivered=false
 private var previewSize:QrPreviewSize?=null
 private var orientation=0
 private var defaultFocus:String?=null
 private var focusEpoch=0L
 private var tappedFocus:Pair<Float,Float>?=null
 private val focusResume=Runnable {resumeFocus()}

 init {
  setBackgroundColor(Color.BLACK);clipChildren=true;clipToPadding=true
  addView(surface,LayoutParams(LayoutParams.MATCH_PARENT,LayoutParams.MATCH_PARENT,Gravity.CENTER))
  surface.holder.addCallback(this)
  surface.contentDescription="Pairing QR camera preview. Tap to focus."
  surface.setOnTouchListener {_,event ->
   if(event.action==MotionEvent.ACTION_UP) {tappedFocus=event.x to event.y;surface.performClick();true} else true
  }
  surface.setOnClickListener {val point=tappedFocus;tappedFocus=null;if(point!=null) focusAt(point.first,point.second) else focusCenter()}
 }
 private fun publish(message:String=ui.message,error:Boolean=false) {
  ui=ui.copy(selected=selected,message=message,error=error);changed(ui)
 }
 fun resume() {if(!closed) {resumed=true;open()}}
 fun pause() {resumed=false;releaseCamera();ui=ui.copy(ready=false,light=false);publish("Camera paused.")}
 fun close() {if(closed) return;closed=true;resumed=false;releaseCamera();gate.close();surface.holder.removeCallback(this);executor.shutdownNow();handler.removeCallbacksAndMessages(null)}
 fun retry() {if(closed) return;delivered=false;releaseCamera();open()}
 fun select(index:Int) {if(index !in cameras.indices || index==selected) return;selected=index;delivered=false;releaseCamera();open()}
 override fun surfaceCreated(holder:SurfaceHolder) {open()}
 override fun surfaceDestroyed(holder:SurfaceHolder) {releaseCamera();ui=ui.copy(ready=false,light=false);if(!closed) publish("Camera paused.")}
 override fun surfaceChanged(holder:SurfaceHolder,format:Int,width:Int,height:Int) {if(camera==null) open() else updatePreview()}
 override fun onSizeChanged(width:Int,height:Int,oldWidth:Int,oldHeight:Int) {super.onSizeChanged(width,height,oldWidth,oldHeight);layoutPreview()}

 private fun open() {
  if(closed || !resumed || delivered || camera!=null || !surface.holder.surface.isValid) return
  if(cameras.isEmpty()) {publish("No camera is available. Paste the pairing code above.",true);return}
  if(ContextCompat.checkSelfPermission(context,Manifest.permission.CAMERA)!=PackageManager.PERMISSION_GRANTED) {
   ui=ui.copy(ready=false,focus=false,torch=false,light=false);publish("Camera permission is unavailable. Cancel scanning to allow it again, or paste the pairing code above.",true);return
  }
  ui=ui.copy(ready=false,focus=false,torch=false,light=false);publish("Starting camera…")
  try {
   val active=Camera.open(cameras[selected].id);camera=active
   active.setErrorCallback {_,owner ->if(camera===owner) fail()}
   val parameters=active.parameters
   val size=qrPreviewSize(parameters.supportedPreviewSizes.orEmpty().map {QrPreviewSize(it.width,it.height)}) ?: error("No bounded preview")
   require(ImageFormat.NV21 in parameters.supportedPreviewFormats.orEmpty())
   parameters.setPreviewSize(size.width,size.height);parameters.previewFormat=ImageFormat.NV21
   defaultFocus=qrFocusMode(parameters.supportedFocusModes.orEmpty())
   defaultFocus?.let {parameters.focusMode=it}
   val torch=Camera.Parameters.FLASH_MODE_TORCH in parameters.supportedFlashModes.orEmpty() && Camera.Parameters.FLASH_MODE_OFF in parameters.supportedFlashModes.orEmpty()
   if(Camera.Parameters.FLASH_MODE_OFF in parameters.supportedFlashModes.orEmpty()) parameters.flashMode=Camera.Parameters.FLASH_MODE_OFF
   try {active.parameters=parameters} catch(error:RuntimeException) {
    if(Camera.Parameters.FOCUS_MODE_AUTO !in parameters.supportedFocusModes.orEmpty()) throw error
    parameters.focusMode=Camera.Parameters.FOCUS_MODE_AUTO;active.parameters=parameters;defaultFocus=Camera.Parameters.FOCUS_MODE_AUTO
   }
   previewSize=size;updatePreview();active.setPreviewDisplay(surface.holder)
   active.setPreviewCallbackWithBuffer {bytes,owner ->
    if(owner!==camera || !resumed || closed || delivered) return@setPreviewCallbackWithBuffer
    if(bytes==null) {fail();return@setPreviewCallbackWithBuffer}
    val ticket=gate.begin(SystemClock.elapsedRealtime())
    if(ticket==null) {runCatching {owner.addCallbackBuffer(bytes)};return@setPreviewCallbackWithBuffer}
    val pixels=size.width*size.height
    val plane=if(bytes.size>=pixels) bytes.copyOf(pixels) else null
    runCatching {owner.addCallbackBuffer(bytes)}
    if(plane==null) {gate.complete(ticket,false);fail();return@setPreviewCallbackWithBuffer}
    try {executor.execute {
     val result=decodeQrLuma(plane,size.width,size.height)
     handler.post {
      if(gate.complete(ticket,result!=null) && !closed && resumed && !delivered) {delivered=true;releaseCamera();found(result!!)}
     }
    }} catch(_:RejectedExecutionException) {gate.complete(ticket,false)}
   }
   repeat(2) {active.addCallbackBuffer(ByteArray(size.width*size.height*3/2))}
   active.startPreview()
   ui=ui.copy(ready=true,focus=defaultFocus!=null,torch=torch,light=false)
   publish(if(defaultFocus!=null) "Point at the pairing QR. Tap the preview to focus; move farther away if it looks blurry." else "This camera has fixed focus. Move farther away until the QR is sharp, or choose another camera.")
   if(defaultFocus in setOf("auto","macro")) focusCenter()
  } catch(_:SecurityException) {releaseCamera();ui=ui.copy(ready=false,focus=false,torch=false,light=false);publish("Camera permission is unavailable. Cancel scanning to allow it again, or paste the pairing code above.",true)}
  catch(_:Exception) {fail()}
 }
 private fun fail() {releaseCamera();ui=ui.copy(ready=false,focus=false,torch=false,light=false);publish("Camera unavailable. Close other camera apps and retry, or paste the pairing code above.",true)}
 private fun updatePreview() {
  val option=cameras.getOrNull(selected) ?: return
  val rotation=when(display?.rotation) {CameraSurface.ROTATION_90 ->90;CameraSurface.ROTATION_180 ->180;CameraSurface.ROTATION_270 ->270;else ->0}
  orientation=qrDisplayOrientation(option.orientation,rotation,!option.rear)
  runCatching {camera?.setDisplayOrientation(orientation)}
  layoutPreview()
 }
 private fun layoutPreview() {
  val size=previewSize ?: return
  if(width<=0 || height<=0) return
  val bounds=qrPreviewBounds(size,orientation,width,height)
  val params=surface.layoutParams as LayoutParams
  if(params.width!=bounds.width || params.height!=bounds.height) {params.width=bounds.width;params.height=bounds.height;params.gravity=Gravity.CENTER;surface.layoutParams=params}
 }
 private fun releaseCamera() {
  gate.invalidate();focusEpoch++;handler.removeCallbacks(focusResume)
  val active=camera;camera=null
  if(active!=null) {
   runCatching {active.setPreviewCallbackWithBuffer(null)}
   runCatching {active.cancelAutoFocus()}
   runCatching {val parameters=active.parameters;if(Camera.Parameters.FLASH_MODE_OFF in parameters.supportedFlashModes.orEmpty()) {parameters.flashMode=Camera.Parameters.FLASH_MODE_OFF;active.parameters=parameters}}
   runCatching {active.stopPreview()};runCatching {active.release()}
  }
 }
 fun toggleLight() {
  val active=camera ?: return
  if(!ui.torch || !ui.ready) return
  try {val parameters=active.parameters;parameters.flashMode=if(ui.light) Camera.Parameters.FLASH_MODE_OFF else Camera.Parameters.FLASH_MODE_TORCH;active.parameters=parameters;ui=ui.copy(light=!ui.light);publish()}
  catch(_:Exception) {publish("The light could not be changed. You can keep scanning or paste the pairing code above.")}
 }
 fun focusCenter() {focusAt(surface.width/2f,surface.height/2f)}
 private fun focusAt(x:Float,y:Float) {
  val active=camera ?: return
  if(!ui.ready || defaultFocus==null) return
  handler.removeCallbacks(focusResume)
  val epoch=++focusEpoch
  try {
   active.cancelAutoFocus()
   val parameters=active.parameters
   if(Camera.Parameters.FOCUS_MODE_AUTO in parameters.supportedFocusModes.orEmpty()) parameters.focusMode=Camera.Parameters.FOCUS_MODE_AUTO
   val area=qrFocusRect(x,y,surface.width,surface.height,orientation,!cameras[selected].rear)
   val regions=listOf(Camera.Area(Rect(area.left,area.top,area.right,area.bottom),1000))
   if(parameters.maxNumFocusAreas>0) parameters.focusAreas=regions
   if(parameters.maxNumMeteringAreas>0) parameters.meteringAreas=regions
   active.parameters=parameters
   publish("Focusing… Hold the phone steady.")
   active.autoFocus {success,owner ->if(camera===owner && resumed && !closed && focusEpoch==epoch) publish(if(success) "Focused. Keep the pairing QR in view." else "Move a little farther away, then tap to focus again.")}
  } catch(_:Exception) {publish("Focus could not be confirmed. Move farther away or try another camera.")}
  if(camera===active && resumed && !closed) handler.postDelayed(focusResume,2000)
 }
 private fun resumeFocus() {
  val active=camera ?: return
  if(!resumed || closed || !ui.ready) return
  if(defaultFocus in setOf("auto","macro")) {focusCenter();return}
  try {
   active.cancelAutoFocus();focusEpoch++
   val parameters=active.parameters;defaultFocus?.let {parameters.focusMode=it}
   if(parameters.maxNumFocusAreas>0) parameters.focusAreas=null
   if(parameters.maxNumMeteringAreas>0) parameters.meteringAreas=null
   active.parameters=parameters
  } catch(_:Exception) { /* A focus adjustment must not discard the live preview. */ }
 }
}
