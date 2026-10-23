package io.theficos.spunto.plugins.quickcapture

import android.app.PendingIntent
import android.content.*
import android.content.pm.PackageManager
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.*
import androidx.glance.action.clickable
import androidx.glance.appwidget.*
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.color.ColorProvider
import androidx.glance.layout.*
import androidx.glance.text.*
import androidx.glance.unit.ColorProvider
import kotlinx.coroutines.*
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

// Carta, until the app hands over its theme.
private val cartaLight=mapOf("paper" to "#F8F5F0","ink" to "#221D18","ink-2" to "#5B544C","ink-3" to "#6E6860","accent" to "#9F4C2A")
private val cartaDark=mapOf("paper" to "#171412","ink" to "#EAE5DD","ink-2" to "#BDB6AE","ink-3" to "#9D978F","accent" to "#E7986F")

object CaptureSurfaces {
 fun lockedPreviews(context:Context)=context.getSharedPreferences("device-surfaces",Context.MODE_PRIVATE).getBoolean("app-lock-enabled",false)
 fun refreshWidgets(context:Context) {CoroutineScope(Dispatchers.IO).launch {CaptureWidget().updateAll(context)}}
 fun enabled(context:Context,id:String)=context.getSharedPreferences("device-surfaces",Context.MODE_PRIVATE).getBoolean(id,true)
 fun intent(context:Context,dictate:Boolean=false)=Intent().setClassName(context.packageName,"io.theficos.spunto.CaptureActivity").putExtra("dictate",dictate).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
 /** The day's slip for the taller widget, with the day its note was written for the overline. */
 fun cacheSlip(context:Context,id:String?,text:String?,writtenAt:Long?=null) {
  val prefs=context.getSharedPreferences("device-surfaces",Context.MODE_PRIVATE)
  val safeId=if(lockedPreviews(context)) null else id;val safeText=if(lockedPreviews(context)) null else text;val safeAt=if(safeId==null) 0L else writtenAt ?: 0L
  if(prefs.getString("slip-id",null)==safeId && prefs.getString("slip-text",null)==safeText && prefs.getLong("slip-at",0L)==safeAt) return
  prefs.edit().putString("slip-id",safeId).putString("slip-text",safeText).putLong("slip-at",safeAt).apply()
  CoroutineScope(Dispatchers.IO).launch { CaptureWidget().updateAll(context) }
 }
 /** The app's theme as the widget draws it; the widget then follows the system's day or night (03 A11). */
 fun palette(context:Context,light:Map<String,String>,dark:Map<String,String>) {
  fun encode(values:Map<String,String>)=cartaLight.keys.mapNotNull { role -> values[role]?.let {"$role=$it"} }.joinToString(";")
  val prefs=context.getSharedPreferences("device-surfaces",Context.MODE_PRIVATE)
  if(prefs.getString("palette-light",null)==encode(light) && prefs.getString("palette-dark",null)==encode(dark)) return
  prefs.edit().putString("palette-light",encode(light)).putString("palette-dark",encode(dark)).apply()
  refreshWidgets(context)
 }
 internal fun colors(context:Context):(String)->ColorProvider {
  val prefs=context.getSharedPreferences("device-surfaces",Context.MODE_PRIVATE)
  fun decode(key:String,fallback:Map<String,String>)=fallback+prefs.getString(key,null).orEmpty().split(';').mapNotNull { it.split('=').takeIf { pair -> pair.size==2 && pair[0] in fallback && Regex("#[0-9a-fA-F]{6}").matches(pair[1]) }?.let { (role,hex) -> role to hex } }
  val light=decode("palette-light",cartaLight);val dark=decode("palette-dark",cartaDark)
  fun color(hex:String)=Color(android.graphics.Color.parseColor(hex))
  return { role -> ColorProvider(day=color(light.getValue(role)),night=color(dark.getValue(role))) }
 }
 fun apply(context:Context,quick:Boolean,dictation:Boolean) {
  context.getSharedPreferences("device-surfaces",Context.MODE_PRIVATE).edit().putBoolean("quick-capture",quick).putBoolean("dictation",dictation).apply()
  listOf(NewNoteTile::class.java to quick,DictateTile::class.java to (quick && dictation)).forEach { (type,on) -> context.packageManager.setComponentEnabledSetting(ComponentName(context,type),if(on) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,PackageManager.DONT_KILL_APP) }
  CoroutineScope(Dispatchers.IO).launch { CaptureWidget().updateAll(context) }
 }
}
/** "FROM YOUR NOTES · 19 MARCH 2025", as the timeline's slip writes it; a slip saved before its date was kept shows no date. */
internal fun slipOverline(writtenAt:Long,label:String,zone:ZoneId=ZoneId.systemDefault(),locale:Locale=Locale.getDefault())=
 (label+if(writtenAt>0) " · "+DateTimeFormatter.ofPattern("d MMMM yyyy",locale).format(Instant.ofEpochMilli(writtenAt).atZone(zone)) else "").uppercase(locale)
/** 03 A11: a paper "Write a note" pill with a mic when dictation is on; a taller widget adds the day's slip. */
class CaptureWidget : GlanceAppWidget() {
 override val sizeMode=SizeMode.Exact
 override suspend fun provideGlance(context:Context,id:GlanceId) { provideContent { CaptureWidgetContent(context) } }
}
@Composable private fun CaptureWidgetContent(context:Context) {
 val color=CaptureSurfaces.colors(context)
 val pill=GlanceModifier.fillMaxWidth().height(48.dp).cornerRadius(24.dp).background(color("paper"))
 Column(GlanceModifier.fillMaxSize().padding(4.dp)) {
  if(!CaptureSurfaces.enabled(context,"quick-capture")) {
   Box(pill.padding(horizontal=20.dp),contentAlignment=Alignment.CenterStart) { Text(context.getString(R.string.capture_disabled),style=TextStyle(color=color("ink-2"),fontSize=13.sp),maxLines=2) }
   return@Column
  }
  Row(pill,verticalAlignment=Alignment.CenterVertically) {
   Row(GlanceModifier.defaultWeight().fillMaxHeight().padding(start=20.dp).clickable(actionStartActivity(CaptureSurfaces.intent(context))),verticalAlignment=Alignment.CenterVertically) {
    Image(ImageProvider(R.drawable.ic_pencil),null,GlanceModifier.size(20.dp),colorFilter=ColorFilter.tint(color("ink-2")))
    Spacer(GlanceModifier.width(12.dp))
    Text(context.getString(R.string.capture_widget_write),style=TextStyle(color=color("ink-2"),fontSize=16.sp),maxLines=1)
   }
   if(CaptureSurfaces.enabled(context,"dictation")) Box(GlanceModifier.size(48.dp).clickable(actionStartActivity(CaptureSurfaces.intent(context,true))),contentAlignment=Alignment.Center) {
    Image(ImageProvider(R.drawable.ic_mic),context.getString(R.string.capture_dictate_note),GlanceModifier.size(22.dp),colorFilter=ColorFilter.tint(color("accent")))
   }
  }
  val slip=context.getSharedPreferences("device-surfaces",Context.MODE_PRIVATE)
  val slipId=if(CaptureSurfaces.lockedPreviews(context)) null else slip.getString("slip-id",null)
  if(slipId!=null && LocalSize.current.height>=110.dp) {
   Spacer(GlanceModifier.height(8.dp))
   Column(GlanceModifier.fillMaxWidth().defaultWeight().cornerRadius(16.dp).background(color("paper")).padding(horizontal=20.dp,vertical=10.dp).clickable(actionStartActivity(Intent().setClassName(context.packageName,"io.theficos.spunto.MainActivity").putExtra("noteId",slipId)))) {
    Text(slipOverline(slip.getLong("slip-at",0L),context.getString(R.string.capture_from_notes),locale=context.resources.configuration.locales[0]),style=TextStyle(color=color("ink-3"),fontSize=11.sp,fontWeight=FontWeight.Medium))
    Spacer(GlanceModifier.height(4.dp))
    Text(slip.getString("slip-text","").orEmpty(),style=TextStyle(color=color("ink"),fontSize=15.sp,fontFamily=FontFamily.Serif),maxLines=2)
   }
  }
 }
}
class CaptureWidgetReceiver : GlanceAppWidgetReceiver() { override val glanceAppWidget = CaptureWidget() }
open class NewNoteTile : TileService() {
 protected open val dictate=false
 override fun onStartListening() { qsTile?.apply { label=getString(if(dictate) R.string.capture_dictate else R.string.capture_new_note);state=if(CaptureSurfaces.enabled(this@NewNoteTile,"quick-capture") && (!dictate || CaptureSurfaces.enabled(this@NewNoteTile,"dictation"))) Tile.STATE_ACTIVE else Tile.STATE_UNAVAILABLE; updateTile() } }
 override fun onClick() {
  if(!CaptureSurfaces.enabled(this,"quick-capture") || dictate && !CaptureSurfaces.enabled(this,"dictation")) return
  val intent=CaptureSurfaces.intent(this,dictate)
  if(Build.VERSION.SDK_INT >= 34) startActivityAndCollapse(PendingIntent.getActivity(this,if(dictate) 2 else 1,intent,PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
  else { @Suppress("DEPRECATION") startActivityAndCollapse(intent) }
 }
}
class DictateTile : NewNoteTile() { override val dictate=true }
