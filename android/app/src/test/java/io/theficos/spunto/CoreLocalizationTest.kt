package io.theficos.spunto

import android.app.Application
import android.content.Context
import android.content.res.Configuration
import androidx.test.core.app.ApplicationProvider
import io.theficos.spunto.core.model.LocalOperationException
import io.theficos.spunto.core.model.LocalOperationProblem
import io.theficos.spunto.core.model.Note
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Locale
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application=Application::class,sdk=[28])
class CoreLocalizationTest {
 private fun context(language:String):Context {
  val base=ApplicationProvider.getApplicationContext<Context>()
  val config=Configuration(base.resources.configuration)
  config.setLocale(Locale.forLanguageTag(language))
  return base.createConfigurationContext(config)
 }
 @Test fun navigationUsesResourceLocaleWithoutTranslatingLabelsOrPluginTitles() {
  for((language,title) in listOf("en" to "All notes","es" to "Todas las notas","it" to "Tutte le note","fr" to "Toutes les notes","de" to "Alle Notizen")) {
   val context=context(language)
   assertEquals(title,navigationTitle(context,"timeline",emptyList(),JsonObject(emptyMap())))
   assertEquals("#Project/Spunto",navigationTitle(context,"label:Project/Spunto",emptyList(),JsonObject(emptyMap())))
  }
  assertEquals("All notes",navigationTitle(context("pt"),"timeline",emptyList(),JsonObject(emptyMap())))
 }
 @Test fun quantitiesAndFormattedAccessibilityKeepCountsAndSavedContent() {
  val context=context("es")
  assertEquals("1 imagen para adjuntar",context.resources.getQuantityString(R.plurals.core_images_to_attach,1,1))
  assertEquals("2 imágenes para adjuntar",context.resources.getQuantityString(R.plurals.core_images_to_attach,2,2))
  val body="Owner’s original title\n\n- [x] One\n- [ ] Two\n#Project/Spunto"
  val note=Note("synthetic-localization",body,createdAt=0,updatedAt=0,pinned=true,privateNote=true)
  val description=entryDescription(context,note,"10:30")
  assertTrue(description.contains("Owner’s original title"))
  assertTrue(description.contains("1 de 2 completadas"))
  assertTrue(description.contains("Project Spunto"))
  assertTrue(description.contains("Privada"))
  assertEquals(body,note.body)
 }
 @Test fun domainGuidanceUsesTypedReasonsAndNeverDisplaysDiagnosticText() {
  val problem=LocalOperationException(LocalOperationProblem.NoteTooLarge,"technical diagnostic text")
  assertEquals("La nota es demasiado grande.",localOperationMessage(context("es").resources,problem))
  assertEquals("La nota è troppo grande.",localOperationMessage(context("it").resources,problem))
  for(language in listOf("en","es","it","fr","de")) for(reason in LocalOperationProblem.entries) {
   val text=localOperationMessage(context(language).resources,LocalOperationException(reason,"private fixture diagnostic"))
   assertTrue(text.isNotBlank());assertFalse(text.contains("private fixture diagnostic"))
  }
 }
 @Test fun datesAndRelativeTimesUseTheSelectedResourceLocale() {
  val context=context("it")
  val today=LocalDate.of(2026,10,23)
  assertEquals("Oggi",timelineGroup(context,today.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),today,ZoneOffset.UTC))
  assertEquals("Ultimi 7 giorni",dateFilterLabel(context,today.minusDays(6).toString(),today.toString(),today))
  assertEquals("2 min fa",relativeTime(context,0,120_000))
  assertEquals("nota.md",markdownFileName(context,"../\\\n"))
 }
}
