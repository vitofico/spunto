package io.theficos.spunto.core.design

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.*
import org.junit.Test

class MaterialPaletteTest {
 @Test fun controlsAndContainersNeverLeakMaterialDefaultColours() {
  for((_,modes) in builtInTokens) for((mode,values) in modes) {
   val scheme=paletteColorScheme(values,mode=="dark")
   val allowed=values.filterKeys { it in themeColorRoles }.values.map(::tokenColor).toSet()
   val controls=listOf(scheme.primary,scheme.onPrimary,scheme.primaryContainer,scheme.onPrimaryContainer,scheme.secondary,scheme.onSecondary,scheme.secondaryContainer,scheme.onSecondaryContainer,scheme.tertiary,scheme.onTertiary,scheme.tertiaryContainer,scheme.onTertiaryContainer,scheme.surfaceContainerLow,scheme.surfaceContainerHigh,scheme.surfaceContainerHighest,scheme.error,scheme.onError,scheme.errorContainer,scheme.onErrorContainer)
   assertTrue("$mode contains an unthemed Material colour",controls.all { it in allowed })
   for((foreground,background) in listOf(scheme.onPrimary to scheme.primary,scheme.onPrimaryContainer to scheme.primaryContainer,scheme.onSecondaryContainer to scheme.secondaryContainer,scheme.onError to scheme.error,scheme.onErrorContainer to scheme.errorContainer)) {
    val a=foreground.luminance().toDouble();val b=background.luminance().toDouble()
    assertTrue("$mode control text contrast",(maxOf(a,b)+.05)/(minOf(a,b)+.05)>=4.5)
   }
   val icons=if(useDarkSystemBarIcons(scheme.background)) Color.Black else Color.White
   val a=icons.luminance().toDouble();val b=scheme.background.luminance().toDouble()
   assertTrue("$mode system icons contrast",(maxOf(a,b)+.05)/(minOf(a,b)+.05)>=4.5)
  }
 }
 @Test fun pureBlackRetainsPaletteControlsAndBrightSystemIcons() {
  for((id,modes) in builtInTokens) {
   val values=modes.getValue("dark");val base=paletteColorScheme(values,true);val black=paletteColorScheme(values,true,true,id)
   assertEquals(Color.Black,black.background);assertEquals(Color.Black,black.surface)
   assertEquals(base.primary,black.primary);assertEquals(Color.Black,black.surfaceVariant)
   val raised=blackPalette(id,values).getValue("raised")
   assertEquals(tokenColor(raised),black.surfaceContainerLowest);assertEquals(Color.Black,black.surfaceContainerHigh)
   assertEquals(emptyList<String>(),paletteErrors(blackPalette(id,values)))
   assertFalse(useDarkSystemBarIcons(black.background))
  }
  assertEquals("#110F0D",blackPalette("carta",builtInTokens.getValue("carta").getValue("dark"))["raised"])
  assertEquals("#0F1114",blackPalette("ardesia",builtInTokens.getValue("ardesia").getValue("dark"))["raised"])
  assertEquals("#0E110F",blackPalette("salvia",builtInTokens.getValue("salvia").getValue("dark"))["raised"])
 }
}
