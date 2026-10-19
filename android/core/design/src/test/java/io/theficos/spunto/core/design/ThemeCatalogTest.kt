package io.theficos.spunto.core.design

import java.security.MessageDigest
import org.junit.Assert.*
import org.junit.Test

class ThemeCatalogTest {
 private fun pack()=ThemeCard("fixture-pack","Fixture paper",light=builtInTokens.getValue("carta").getValue("light"),dark=builtInTokens.getValue("carta").getValue("dark"),accents=listOf(ThemeAccent("blue-accent","Blue",builtInTokens.getValue("ardesia").getValue("light"),builtInTokens.getValue("ardesia").getValue("dark"))))
 @Test fun allFiveOriginalSixteenRoleMapsRemainByteExact() {
  val frozen=mapOf("carta" to "b4c8fd201f493038eccc8ecf08a6f285f14a2b4d4cc843418f156c0f15a09623","ardesia" to "0985b936e120bf0398ba0f10aeef8a4c2adb0f1d0af036671974e7933736feee","salvia" to "00ce22ddfeab421b71576d84de533f320da842c671440aa4b9876243096e7495","corallo" to "7a5e85efa73f4efe5f6d677242d95767c8f09368fcc3f4f19a4f5bb1ea8292d7","elettrica" to "cd0667012e6bfaafe1cb3933566eb3eacc57500734cd1ac439f10427cc57b856")
  frozen.forEach { (id,expected) ->
   val exact=listOf("light","dark").flatMap { mode -> themeColorRoles.sorted().map { role -> "$mode:$role=${builtInTokens.getValue(id).getValue(mode).getValue(role)}" } }.joinToString("\n")
   assertEquals(id,expected,MessageDigest.getInstance("SHA-256").digest(exact.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) })
  }
  assertEquals(frozen.keys,builtInThemeCards.map { it.id }.toSet())
 }
 @Test fun explicitAccentAndDefaultResolveCompleteLightAndDarkMaps() {
  val catalog=validatedThemeCatalog(listOf(pack()))
  val default=resolveThemeSelection("fixture-pack",null,catalog)
  val accent=resolveThemeSelection("fixture-pack","blue-accent",catalog)
  assertNull(default.accent);assertEquals("blue-accent",accent.accent)
  for(dark in listOf(false,true)) {
   val mode=if(dark) "dark" else "light"
   assertEquals(builtInTokens.getValue("carta").getValue(mode).filterKeys { it in themeColorRoles },default.mode(dark))
   assertEquals(builtInTokens.getValue("ardesia").getValue(mode).filterKeys { it in themeColorRoles },accent.mode(dark))
   assertEquals(themeColorRoles,accent.mode(dark).keys)
  }
  assertEquals(default,resolveThemeSelection("fixture-pack","removed-accent",catalog))
 }
 @Test fun failingModeFieldVersionAndAccentsDisableTheWholePack() {
  val good=pack()
  val badDark=good.copy(dark=good.dark+mapOf("ink-3" to good.dark.getValue("sunken")))
  val broken=validateThemeCard(badDark)
  assertFalse(broken.available);assertTrue(broken.reason!!.contains("dark: ink-3 on"));assertTrue(broken.light.isEmpty())
  val cases=listOf(good.copy(tokens=2),good.copy(light=good.light-"warn"),good.copy(light=good.light+mapOf("paper" to "url(https://example.invalid/font)")),good.copy(accents=good.accents+good.accents),good.copy(accents=listOf(good.accents.single().copy(dark=mapOf("accent" to "#FFFFFF")))),good.copy(status="unknown"))
  cases.forEach { assertFalse(validateThemeCard(it).available) }
  val failed=validateThemeCard(good.copy(status="failed",reason="dark: ink-3 on sunken"))
  assertEquals("dark: ink-3 on sunken",failed.reason);assertTrue(failed.accents.isEmpty())
 }
 @Test fun failedMissingAndDuplicateIdsCannotResurrectAnOlderValidPalette() {
  val good=pack();val saved=resolveThemeSelection(good.id,"blue-accent",validatedThemeCatalog(listOf(good)))
  val requestedId=good.id;val requestedAccent="blue-accent"
  assertEquals(saved,resolveThemeSelection(requestedId,requestedAccent,null,saved))
  for(catalog in listOf(validatedThemeCatalog(emptyList()),validatedThemeCatalog(listOf(good.copy(status="failed",reason="dark contrast failed"))),validatedThemeCatalog(listOf(good,good)))) {
   assertEquals("carta",resolveThemeSelection(requestedId,requestedAccent,catalog,saved).id)
   assertEquals(good.id,requestedId);assertEquals("blue-accent",requestedAccent)
   assertEquals(catalog.cards.size,catalog.cards.map { it.id }.distinct().size)
  }
  assertEquals("carta",resolveThemeSelection(good.id,null,null,saved).id)
 }
 @Test fun catalogCannotReplaceBuiltinColoursOrLoadUnknownFields() {
  val disguised=pack().copy(id="carta",name="Replacement",light=mapOf("paper" to "#000000"))
  val catalog=validatedThemeCatalog(listOf(disguised,pack().copy(light=pack().light+mapOf("font" to "https://example.invalid/font"))))
  assertEquals(builtInTokens.getValue("carta").getValue("light").filterKeys { it in themeColorRoles },resolveThemeSelection("carta",null,catalog).light)
  assertEquals(themeColorRoles,catalog.cards.first { it.id=="fixture-pack" }.light.keys)
  assertTrue(validateThemeCard(pack().copy(status="failed",reason="x".repeat(500)+"\u0000")).reason!!.length<=256)
 }
 @Test fun forgedUnvalidatedCatalogCannotSupplyBrokenMaterialRoles() {
  val bad=pack().copy(light=mapOf("paper" to "#FFFFFF"))
  assertEquals("carta",resolveThemeSelection(bad.id,null,ThemeCatalog(listOf(bad))).id)
 }
}
