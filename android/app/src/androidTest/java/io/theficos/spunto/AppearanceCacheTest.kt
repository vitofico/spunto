package io.theficos.spunto

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.sp
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import io.theficos.spunto.core.design.*
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class AppearanceCacheTest {
 @get:Rule val compose=createComposeRule()
 private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
 private val appearance get()=context.getSharedPreferences("appearance",Context.MODE_PRIVATE)
 private val manifestPreferences get()=context.getSharedPreferences("plugin-manifest",Context.MODE_PRIVATE)
 private lateinit var originalAppearance:Map<String,*>
 private lateinit var originalManifest:Map<String,*>
 private val fixtureNames=mutableListOf<String>()
 private val showContent=mutableStateOf(true)
 private var containsContent=false
 @Before fun retainExactOwnerPreferences() { originalAppearance=appearance.all.toMap();originalManifest=manifestPreferences.all.toMap() }
 @After fun restoreExactOwnerPreferences() {
  if(containsContent) { compose.runOnIdle { showContent.value=false };compose.waitForIdle() }
  restore(appearance,originalAppearance);restore(manifestPreferences,originalManifest)
  fixtureNames.forEach { context.deleteSharedPreferences(it) }
  assertEquals(originalAppearance,appearance.all);assertEquals(originalManifest,manifestPreferences.all)
 }
 private fun restore(preferences:SharedPreferences,original:Map<String,*>) {
  preferences.edit().clear().apply {
   original.forEach { (key,value) -> when(value) {
    is String -> putString(key,value);is Boolean -> putBoolean(key,value);is Int -> putInt(key,value);is Long -> putLong(key,value);is Float -> putFloat(key,value)
    is Set<*> -> putStringSet(key,value.filterIsInstance<String>().toSet())
   } }
  }.commit()
 }
 private fun fixturePreferences():SharedPreferences { val name="appearance-fixture-${UUID.randomUUID()}";fixtureNames.add(name);return context.getSharedPreferences(name,Context.MODE_PRIVATE) }
 private fun pack():ThemeCard {
  val paper=builtInTokens.getValue("carta");val slate=builtInTokens.getValue("ardesia")
  return ThemeCard("fixture-pack","Fixture paper",light=paper.getValue("light"),dark=paper.getValue("dark"),accents=listOf(ThemeAccent("blue-accent","Blue",slate.getValue("light"),slate.getValue("dark"))))
 }
 private fun manifest(card:ThemeCard):String {
  val accents=JSONArray(card.accents.map { JSONObject().put("id",it.id).put("name",it.name).put("light",JSONObject(it.light)).put("dark",JSONObject(it.dark)) })
  val json=JSONObject().put("id",card.id).put("name",card.name).put("status",card.status).put("reason",card.reason ?: JSONObject.NULL).put("tokens",card.tokens).put("light",JSONObject(card.light)).put("dark",JSONObject(card.dark)).put("accents",accents)
  return JSONObject().put("theme_catalog",JSONArray().put(json)).put("theme",JSONObject().put("id","carta").put("tokens",1).put("light",JSONObject(builtInTokens.getValue("carta").getValue("light"))).put("dark",JSONObject(builtInTokens.getValue("carta").getValue("dark")))).toString()
 }
 @Test fun offlineSnapshotResetAndInvalidationPreserveUnrelatedDevicePreferences() {
  val preferences=fixturePreferences()
  preferences.edit().putString("mode","dark").putBoolean("black",true).putBoolean("wallpaper",true).putString("reading-face","sans").putString("reading-size","L").putString("retained-draft-sentinel","Synthetic fixture draft").putStringSet("device-tools",setOf("fixture-tool")).commit()
  val preserved=preferences.all.toMap()
  val raw=manifest(pack());val fingerprint=themeManifestFingerprint(raw);val catalog=decodeThemeManifest(raw).catalog
  val selected=resolveThemeSelection("fixture-pack","blue-accent",catalog)
  applyDeviceTheme(preferences,"fixture-pack","blue-accent",selected,fingerprint)
  assertEquals(selected,readSelectedThemeSnapshot(preferences,fingerprint))
  val reopened=context.getSharedPreferences(fixtureNames.last(),Context.MODE_PRIVATE)
  assertEquals(selected,readSelectedThemeSnapshot(reopened,fingerprint))
  val changed=manifest(pack().copy(status="failed",reason="dark contrast failed"))
  assertNull(readSelectedThemeSnapshot(reopened,themeManifestFingerprint(changed)))
  val fallback=resolveThemeSelection("fixture-pack","blue-accent",decodeThemeManifest(changed).catalog,selected)
  assertEquals("carta",fallback.id);assertEquals("fixture-pack",preferences.getString("theme",null));assertEquals("blue-accent",preferences.getString("theme-accent",null))
  val default=resolveThemeSelection("fixture-pack",null,catalog)
  applyDeviceTheme(preferences,"fixture-pack",null,default,fingerprint)
  assertFalse(preferences.contains("theme-accent"));assertEquals(default,readSelectedThemeSnapshot(preferences,fingerprint))
  val instance=resolveThemeSelection("corallo",null)
  applyDeviceTheme(preferences,null,null,instance,fingerprint)
  assertFalse(preferences.contains("theme"));assertFalse(preferences.contains("theme-accent"));assertEquals(instance,readSelectedThemeSnapshot(preferences,fingerprint))
  preserved.forEach { (key,value) -> assertEquals(key,value,preferences.all[key]) }
 }
 @Test fun malformedRuntimeCatalogsAndTamperedSnapshotsNeverSupplyColours() {
  val documents=listOf("not json","{\"theme_catalog\":{}}","{\"theme_catalog\":[null,12,\"pack\"]}","{\"theme_catalog\":[{\"id\":\"fixture-pack\",\"name\":\"Fixture\",\"status\":\"available\",\"tokens\":\"1\"}]}")
  documents.forEach { raw -> val parsed=decodeThemeManifest(raw);assertEquals("carta",parsed.instance.id);assertTrue(parsed.catalog.cards.any { !it.available });assertEquals(parsed.catalog.cards.size,parsed.catalog.cards.map { it.id }.distinct().size) }
  val preferences=fixturePreferences();val raw=manifest(pack());val fingerprint=themeManifestFingerprint(raw)
  applyDeviceTheme(preferences,"fixture-pack",null,resolveThemeSelection("fixture-pack",null,decodeThemeManifest(raw).catalog),fingerprint)
  val snapshot=JSONObject(preferences.getString("theme-snapshot",null)!!)
  snapshot.getJSONObject("palette").getJSONObject("dark").put("ink-3","#000000")
  preferences.edit().putString("theme-snapshot",snapshot.toString()).commit()
  assertNull(readSelectedThemeSnapshot(preferences,fingerprint))
 }
 @Test fun resetToCustomInstanceAccentImmediatelyCachesInstanceAndPreservesOtherChoices() {
  val preferences=fixturePreferences()
  preferences.edit().putString("mode","light").putBoolean("black",true).putBoolean("wallpaper",true).putString("reading-face","sans").putString("reading-size","S").putString("retained-draft-sentinel","Synthetic fixture draft").putStringSet("device-tools",setOf("fixture-tool")).commit()
  val preserved=preferences.all.toMap()
  val selected=resolveThemeSelection("fixture-pack","blue-accent",validatedThemeCatalog(listOf(pack())))
  val document=JSONObject(manifest(pack())).put("theme",JSONObject().put("id",selected.id).put("accent",selected.accent).put("tokens",selected.tokens).put("light",JSONObject(selected.light)).put("dark",JSONObject(selected.dark)))
  val raw=document.toString();val fingerprint=themeManifestFingerprint(raw);val instance=decodeThemeManifest(raw).instance
  assertEquals(selected,instance)
  val default=resolveThemeSelection("fixture-pack","removed-accent",decodeThemeManifest(raw).catalog)
  applyDeviceTheme(preferences,"fixture-pack","removed-accent",default,fingerprint)
  assertEquals("removed-accent",preferences.getString("theme-accent",null));assertEquals(default,readSelectedThemeSnapshot(preferences,fingerprint))
  applyDeviceTheme(preferences,null,null,instance,fingerprint)
  assertFalse(preferences.contains("theme"));assertFalse(preferences.contains("theme-accent"))
  assertEquals("blue-accent",JSONObject(preferences.getString("theme-snapshot",null)!!).getString("requestedAccent"))
  assertEquals(instance,readSelectedThemeSnapshot(preferences,fingerprint))
  assertEquals(instance,readSelectedThemeSnapshot(context.getSharedPreferences(fixtureNames.last(),Context.MODE_PRIVATE),fingerprint))
  preserved.forEach { (key,value) -> assertEquals(key,value,preferences.all[key]) }
  val reset=preferences.all.toMap()
  applyDeviceTheme(preferences,null,null,instance,fingerprint)
  assertEquals(reset,preferences.all)
  storeSelectedThemeSnapshot(preferences,SelectedThemeSnapshot(false,instance.id,null,fingerprint,instance))
  assertEquals(instance,readSelectedThemeSnapshot(preferences,fingerprint));assertEquals(reset,preferences.all)
 }
 @Test fun defaultCaptureThemeUsesMatchingCacheInFirstFrameAndFreshFailureWins() {
  val raw=manifest(pack());val fingerprint=themeManifestFingerprint(raw)
  val selected=resolveThemeSelection("fixture-pack","blue-accent",decodeThemeManifest(raw).catalog)
  appearance.edit().clear().putString("mode","dark").putString("reading-face","sans").putString("reading-size","L").commit()
  manifestPreferences.edit().putString("manifest",raw).commit()
  applyDeviceTheme(appearance,"fixture-pack","blue-accent",selected,fingerprint)
  val seen=mutableListOf<Color>();val prosePixels=mutableListOf<Float>()
  containsContent=true
  compose.setContent {
   if(showContent.value) CompositionLocalProvider(LocalDensity provides Density(1f,1.6f)) {
    SpuntoTheme {
     val color=MaterialTheme.colorScheme.primary;val typography=MaterialTheme.typography;val reading=SpuntoType.reading
     val pixels=with(LocalDensity.current) { reading.fontSize.toPx() }
     SideEffect { seen.add(color);prosePixels.add(pixels);assertEquals(18f*16.5f/17,reading.fontSize.value,.001f);assertEquals(uiFont(),reading.fontFamily);assertEquals(14f,typography.bodyMedium.fontSize.value,0f) }
    }
   }
  }
  // Android applies a non-linear font-scale curve; keep the actual sans L reading size explicit.
  compose.runOnIdle { assertEquals(tokenColor(selected.dark.getValue("accent")),seen.first());assertEquals(with(Density(1f,1.6f)) { (18f*16.5f/17).sp.toPx() },prosePixels.first(),.01f) }
  var start=0
  compose.runOnIdle { start=seen.size;manifestPreferences.edit().putString("manifest",manifest(pack().copy(status="failed",reason="dark contrast failed"))).commit() }
  val carta=tokenColor(builtInTokens.getValue("carta").getValue("dark").getValue("accent"))
  compose.waitUntil(5000) { seen.size>start && seen.last()==carta }
  compose.runOnIdle { assertTrue(seen.drop(start).all { it==carta });assertEquals("fixture-pack",appearance.getString("theme",null));assertEquals("blue-accent",appearance.getString("theme-accent",null)) }
 }
}
