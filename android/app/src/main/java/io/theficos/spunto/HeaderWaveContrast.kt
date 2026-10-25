package io.theficos.spunto

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.colorspace.ColorSpaces
import androidx.compose.ui.graphics.luminance
import io.theficos.spunto.core.design.useDarkSystemBarIcons
import kotlin.math.ceil
import kotlin.math.floor

internal const val HEADER_WAVE_ALPHA=.65f
internal const val HEADER_SURFACE_FADE_START=.12f

/** Bound every intermediate strand blend, including opposing saturated colours, under OS text and icons. */
internal fun headerWaveOpacity(surface:Color,accent:Color):Float {
 val paper=surface.convert(ColorSpaces.Srgb)
 val ink=accent.convert(ColorSpaces.Srgb)
 val darkIcons=useDarkSystemBarIcons(paper)
 fun safe(alpha:Float):Boolean {
  // A strand intersection may be opaque. The whole wave is one offscreen alpha group.
  if(alpha==0f) return true // No artwork preserves the existing system-bar surface exactly.
  val coverage=alpha*(1f-HEADER_SURFACE_FADE_START)
  fun channel(base:Float,accent:Float):Float {
   val end=base+(accent-base)*coverage
   // Bound all intermediate mixtures without quantizing the alpha first. Round away from
   // contrast, with two extra channel codes for offscreen, fade and framebuffer rounding.
   return if(darkIcons) ((floor(minOf(base,end)*255)-2)/255).coerceAtLeast(0f)
    else ((ceil(maxOf(base,end)*255)+2)/255).coerceAtMost(1f)
  }
  val bound=Color(channel(paper.red,ink.red),channel(paper.green,ink.green),channel(paper.blue,ink.blue))
  val luminance=bound.luminance()
  val contrast=if(darkIcons) (luminance+.05f)/.05f else 1.05f/(luminance+.05f)
  return contrast>=4.5f
 }
 if(safe(HEADER_WAVE_ALPHA)) return HEADER_WAVE_ALPHA
 var low=0f;var high=HEADER_WAVE_ALPHA
 repeat(18) {val middle=(low+high)/2;if(safe(middle)) low=middle else high=middle}
 return low*.999f
}
