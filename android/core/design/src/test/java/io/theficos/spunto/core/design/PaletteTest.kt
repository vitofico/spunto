package io.theficos.spunto.core.design

import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout

class PaletteTest {
 @get:Rule val timeout=Timeout(10,TimeUnit.MINUTES)
 @Test fun fiveCatalogChoicesHaveBothAccessibleModes() {
  assertEquals(listOf("carta","ardesia","salvia","corallo","elettrica"),builtInThemes.map { it.id })
  assertEquals(builtInTokens.keys,builtInThemes.map { it.id }.toSet())
  builtInTokens.forEach { (id,modes) -> assertEquals(setOf("light","dark"),modes.keys);modes.forEach { (mode,palette) -> assertEquals("$id $mode",emptyList<String>(),paletteErrors(palette)) } }
 }
 @Test fun invalidCachedPackFallsBackWithoutChangingDeviceChoice() {
  val valid=builtInTokens.getValue("corallo").getValue("dark")
  assertEquals(valid.filterKeys { it in themeColorRoles },resolveThemePalette("unknown-pack",true,valid))
  val broken=valid+mapOf("ink-3" to valid.getValue("paper"))
  assertTrue(paletteErrors(broken).any { it.startsWith("ink-3 on paper") })
  assertEquals(builtInTokens.getValue("elettrica").getValue("dark"),resolveThemePalette("elettrica",true,broken))
  val invisibleSecondary=valid+mapOf("ink-2" to valid.getValue("paper"))
  assertTrue(paletteErrors(invisibleSecondary).any { it.startsWith("ink-2 on paper") })
  assertEquals(builtInTokens.getValue("corallo").getValue("dark"),resolveThemePalette("corallo",true,invisibleSecondary))
  assertEquals(builtInTokens.getValue("carta").getValue("light"),resolveThemePalette("unknown",false,mapOf("paper" to "url(https://example.invalid/font)")))
 }
 @Test fun contrastUsesUnroundedSrgbRatios() { assertEquals(21.0,contrastRatio("#FFFFFF","#000000"),0.000001);assertEquals(1.0,contrastRatio("#5317AB","#5317AB"),0.000001) }
}
