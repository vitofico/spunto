package io.theficos.spunto

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import io.theficos.spunto.core.design.builtInTokens
import io.theficos.spunto.core.design.tokenColor
import io.theficos.spunto.core.design.useDarkSystemBarIcons
import org.junit.Assert.*
import org.junit.Test

class HeaderWaveContrastTest {
 @Test fun builtInPalettesKeepTheirVisibleWave() {
  for((name,modes) in builtInTokens) for((mode,tokens) in modes) {
   assertEquals("$name/$mode",.65f,headerWaveOpacity(tokenColor(tokens.getValue("paper")),tokenColor(tokens.getValue("accent"))),.0001f)
  }
 }
 @Test fun acceptedNearThresholdPapersReduceOnlyTheUnsafeWave() {
  for((paper,accent) in listOf(Color(0xff747474) to Color.White,Color(0xff767676) to Color.Black)) {
   val alpha=headerWaveOpacity(paper,accent)
   assertTrue(alpha>=0f && alpha<.65f)
   verifyAllBlends(paper,accent,alpha)
  }
 }
 @Test fun opposingColoursNeverHideAnUnsafeIntermediateBlend() {
  val colours=listOf(Color.Black,Color.White,Color.Red,Color.Green,Color.Blue,Color.Cyan,Color.Magenta,Color.Yellow,Color(0xff747474),Color(0xff767676))
  for(paper in colours) for(accent in colours) verifyAllBlends(paper,accent,headerWaveOpacity(paper,accent))
 }
 private fun verifyAllBlends(paper:Color,accent:Color,alpha:Float) {
  for(step in 0..512) {
   val t=alpha*.88f*step/512f
   // Independently render each sRGB source-over mixture, not the channelwise bound used by production.
   fun channel(base:Float,ink:Float):Float {
    val nearest=kotlin.math.round((base*(1-t)+ink*t)*255)
    val roundingAllowance=if(alpha==0f) 0 else if(useDarkSystemBarIcons(paper)) -2 else 2
    return ((nearest+roundingAllowance)/255).coerceIn(0f,1f)
   }
   val sample=Color(channel(paper.red,accent.red),channel(paper.green,accent.green),channel(paper.blue,accent.blue))
   val luminance=sample.luminance()
   val contrast=if(useDarkSystemBarIcons(paper)) (luminance+.05f)/.05f else 1.05f/(luminance+.05f)
   assertTrue("$paper/$accent at $t: $contrast",contrast>=4.4999f)
  }
 }
}
