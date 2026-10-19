package io.theficos.spunto.plugins.quickcapture

import android.app.PendingIntent
import android.content.*
import android.content.pm.PackageManager
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.appwidget.*
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.layout.*
import androidx.glance.text.Text
import androidx.glance.Button
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.*

object CaptureSurfaces {
 fun lockedPreviews(context:Context)=context.getSharedPreferences("device-surfaces",Context.MODE_PRIVATE).getBoolean("app-lock-enabled",false)
 fun refreshWidgets(context:Context) {CoroutineScope(Dispatchers.IO).launch {CaptureWidget().updateAll(context)}}
 fun enabled(context:Context,id:String)=context.getSharedPreferences("device-surfaces",Context.MODE_PRIVATE).getBoolean(id,true)
 fun intent(context:Context,dictate:Boolean=false)=Intent().setClassName(context.packageName,"io.theficos.spunto.CaptureActivity").putExtra("dictate",dictate).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
 fun cacheSlip(context:Context,id:String?,text:String?) {
  val prefs=context.getSharedPreferences("device-surfaces",Context.MODE_PRIVATE)
  val safeId=if(lockedPreviews(context)) null else id;val safeText=if(lockedPreviews(context)) null else text
  if(prefs.getString("slip-id",null)==safeId && prefs.getString("slip-text",null)==safeText) return
  prefs.edit().putString("slip-id",safeId).putString("slip-text",safeText).apply()
  CoroutineScope(Dispatchers.IO).launch { CaptureWidget().updateAll(context) }
 }
 fun apply(context:Context,quick:Boolean,dictation:Boolean) {
  context.getSharedPreferences("device-surfaces",Context.MODE_PRIVATE).edit().putBoolean("quick-capture",quick).putBoolean("dictation",dictation).apply()
  listOf(NewNoteTile::class.java to quick,DictateTile::class.java to (quick && dictation)).forEach { (type,on) -> context.packageManager.setComponentEnabledSetting(ComponentName(context,type),if(on) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,PackageManager.DONT_KILL_APP) }
  CoroutineScope(Dispatchers.IO).launch { CaptureWidget().updateAll(context) }
 }
}
class CaptureWidget : GlanceAppWidget() {
 override suspend fun provideGlance(context:Context,id:GlanceId) {
  provideContent {
   Column(GlanceModifier.fillMaxSize().padding(16.dp)) {
    Text("spunto.")
    if(CaptureSurfaces.enabled(context,"quick-capture")) {
     val slip=context.getSharedPreferences("device-surfaces",Context.MODE_PRIVATE)
     val slipId=if(CaptureSurfaces.lockedPreviews(context)) null else slip.getString("slip-id",null)
     if(slipId!=null) { Text("From your notes"); Text(slip.getString("slip-text","").orEmpty()); Button("Open",onClick=actionStartActivity(Intent().setClassName(context.packageName,"io.theficos.spunto.MainActivity").putExtra("noteId",slipId))) }
     Spacer(GlanceModifier.height(12.dp))
     Button("New note",onClick=actionStartActivity(CaptureSurfaces.intent(context)))
     if(CaptureSurfaces.enabled(context,"dictation")) Button("Dictate",onClick=actionStartActivity(CaptureSurfaces.intent(context,true)))
    } else Text("Quick capture is off. Turn it on in Settings → Plugins.")
   }
  }
 }
}
class CaptureWidgetReceiver : GlanceAppWidgetReceiver() { override val glanceAppWidget = CaptureWidget() }
open class NewNoteTile : TileService() {
 protected open val dictate=false
 override fun onStartListening() { qsTile?.apply { state=if(CaptureSurfaces.enabled(this@NewNoteTile,"quick-capture") && (!dictate || CaptureSurfaces.enabled(this@NewNoteTile,"dictation"))) Tile.STATE_ACTIVE else Tile.STATE_UNAVAILABLE; updateTile() } }
 override fun onClick() {
  if(!CaptureSurfaces.enabled(this,"quick-capture") || dictate && !CaptureSurfaces.enabled(this,"dictation")) return
  val intent=CaptureSurfaces.intent(this,dictate)
  if(Build.VERSION.SDK_INT >= 34) startActivityAndCollapse(PendingIntent.getActivity(this,if(dictate) 2 else 1,intent,PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
  else { @Suppress("DEPRECATION") startActivityAndCollapse(intent) }
 }
}
class DictateTile : NewNoteTile() { override val dictate=true }
