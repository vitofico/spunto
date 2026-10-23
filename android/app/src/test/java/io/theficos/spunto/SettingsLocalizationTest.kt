package io.theficos.spunto

import android.content.res.Configuration
import android.content.res.Resources
import androidx.test.core.app.ApplicationProvider
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale

internal fun settingsTestResources(tag:String="en"):Resources {
 val context=ApplicationProvider.getApplicationContext<android.app.Application>()
 val config=Configuration(context.resources.configuration).apply {setLocale(Locale.forLanguageTag(tag))}
 return context.createConfigurationContext(config).resources
}

@RunWith(RobolectricTestRunner::class)
@Config(application=android.app.Application::class,sdk=[28])
class SettingsLocalizationTest {
 @Test fun knownBundledMetadataUsesIdsAndCustomMetadataStaysAuthored() {
  val plugin=buildJsonObject {put("id","tidy");put("name","Author name");put("description","Author description")}
  val command=buildJsonObject {put("id","tidy");put("title","Author command")}
  val resources=settingsTestResources("de")
  assertEquals("Author name",localizedPluginName(resources,plugin))
  assertEquals("Author description",localizedPluginDescription(resources,plugin))
  assertEquals("Author command",localizedPluginCommand(resources,plugin,command))
  val catalog=buildJsonObject {put("plugins",JsonArray(listOf(JsonObject(plugin+("origin" to JsonPrimitive("bundled"))))))}
  assertEquals("Text bereinigen",localizedPluginName(resources,plugin,catalog))
  assertEquals("Text bereinigen",localizedPluginCommand(resources,plugin,command,catalog))
 }
 @Test fun builtinSettingTitlesUseStableIdsAndPreserveCustomTitles() {
  val field=buildJsonObject {put("title","Owner field title")}
  val custom=buildJsonObject {put("id","digest");put("origin","operator")}
  assertEquals("Owner field title",localizedPluginSettingTitle(settingsTestResources("fr"),custom,"weekday",field))
  val bundled=JsonObject(custom+("origin" to JsonPrimitive("bundled")))
  assertEquals("Jour",localizedPluginSettingTitle(settingsTestResources("fr"),bundled,"weekday",field))
 }
 @Test fun everyBundledPermissionHasLocalizedGuidanceInsteadOfProtocolIds() {
  val permissions=listOf("notes.update_own","search.text","routes","mic")
  val expected=mapOf(
   "en" to listOf("Updates its own notes while they remain unedited","Attaches searchable text","Adds plugin routes","Uses your device microphone"),
   "es" to listOf("Actualiza sus propias notas mientras no se hayan editado","Añade texto que se puede buscar","Añade rutas del complemento","Usa el micrófono de tu dispositivo"),
   "it" to listOf("Aggiorna le proprie note finché non vengono modificate","Aggiunge testo ricercabile","Aggiunge percorsi del plugin","Usa il microfono del dispositivo"),
   "fr" to listOf("Met à jour ses propres notes tant qu’elles restent non modifiées","Ajoute du texte recherchable","Ajoute des routes d’extension","Utilise le microphone de votre appareil"),
   "de" to listOf("Aktualisiert eigene Notizen, solange sie unbearbeitet bleiben","Fügt durchsuchbaren Text hinzu","Fügt Erweiterungsrouten hinzu","Verwendet das Mikrofon deines Geräts"),
  )
  val bundled=NativePluginFacts(buildJsonObject {
   put("id","fixture-plugin");put("origin","bundled")
   put("permissions",JsonArray(permissions.map(::JsonPrimitive)))
  })
  expected.forEach {(locale,sentences) ->
   assertEquals(locale,sentences,bundled.permissionLines(settingsTestResources(locale)))
   permissions.zip(sentences).forEach {(permission,sentence) -> assertNotEquals(permission,sentence)}
  }
 }
 @Test fun allSupportedLocalesResolveNativeLabelsAndWholePluralSentences() {
  val languages=mapOf("en" to "Language","es" to "Idioma","it" to "Lingua","fr" to "Langue","de" to "Sprache")
  languages.forEach {(tag,label) ->
   val resources=settingsTestResources(tag)
   assertEquals(label,resources.getString(R.string.settings_language))
   val one=resources.getQuantityString(R.plurals.settings_waiting_sync,1,1)
   val many=resources.getQuantityString(R.plurals.settings_waiting_sync,3,3)
   assertTrue(one.contains("1"));assertTrue(many.contains("3"));assertNotEquals(one,many)
   assertFalse(signOutText(resources,2,false).contains("%"))
   assertEquals("Synthetic server diagnostic.",apiErrorMessage(resources,io.theficos.spunto.data.sync.ApiException(503,"synthetic","Synthetic server diagnostic.")))
  }
 }
}
