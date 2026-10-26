package io.theficos.spunto.core.design

import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.background
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntSize
import kotlin.math.roundToInt

fun normalizedBackgroundStyle(value:String?):String = if(value=="plain") "plain" else "artwork"
val LocalSpuntoBackground = compositionLocalOf { "artwork" }

/** Preserve original coverage and printed tonal variation before applying any theme tint. */
internal fun woodgrainAlpha(pixel:Int):Int {
 val luminance=(.2126f*((pixel ushr 16) and 255)+.7152f*((pixel ushr 8) and 255)+.0722f*(pixel and 255))/255f
 return (((pixel ushr 24) and 255)*(1f-luminance)).roundToInt().coerceIn(0,255)
}
private var cachedWoodgrain:ImageBitmap?=null
@Synchronized private fun woodgrainMask(resources:Resources):ImageBitmap {
 cachedWoodgrain?.let {return it}
 val source=checkNotNull(BitmapFactory.decodeResource(resources,R.drawable.spunto_woodgrain))
 val pixels=IntArray(source.width*source.height)
 source.getPixels(pixels,0,source.width,0,0,source.width,source.height)
 for(index in pixels.indices) pixels[index]=(woodgrainAlpha(pixels[index]) shl 24) or 0x00FFFFFF
 val mask=Bitmap.createBitmap(pixels,source.width,source.height,Bitmap.Config.ARGB_8888).asImageBitmap()
 source.recycle()
 return mask.also {cachedWoodgrain=it}
}

/** Authored, irregular engraved ink and pigment wash. Plain keeps the smooth paper only. */
@Composable fun Modifier.spuntoBackground(enabled:Boolean=true,style:String=LocalSpuntoBackground.current,paper:Color=MaterialTheme.colorScheme.background,accent:Color=MaterialTheme.colorScheme.primary):Modifier {
 val base=background(paper)
 if(!enabled || normalizedBackgroundStyle(style)=="plain") return base
 val ink=woodgrainMask(LocalContext.current.resources)
 return base.drawWithCache {
  val target=IntSize(size.width.roundToInt().coerceAtLeast(1),size.height.roundToInt().coerceAtLeast(1))
  val tint=ColorFilter.tint(accent)
  onDrawBehind {drawImage(ink,dstSize=target,colorFilter=tint,alpha=.32f)}
 }
}
