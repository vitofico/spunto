package io.theficos.spunto.core.plugins
import io.theficos.spunto.core.model.labelKey
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
class ViewParityTest {
 @get:Rule val timeout=org.junit.rules.Timeout(10,java.util.concurrent.TimeUnit.MINUTES)
 private fun filter(raw:String)=Json.parseToJsonElement(raw).jsonObject
 @Test fun labelsNormalizeAndFalseNoLabelsRequiresLabels() {
  val note=ViewNote("note","",0,labels=setOf(labelKey("café"),labelKey("Straße")))
  assertTrue(matchesView(note,filter("""{"labels":["café","STRASSE"],"no_labels":false}"""),0))
  assertFalse(matchesView(note.copy(labels=emptySet()),filter("""{"no_labels":false}"""),0))
 }
 @Test fun ownerMatchesOriginAndPluginMatchesRef() {
  val note=ViewNote("note","",0,source="share",origin="owner",sourceRef="digest")
  assertTrue(matchesView(note,filter("""{"source":"owner"}"""),0))
  assertTrue(matchesView(note,filter("""{"source":"plugin:digest"}"""),0))
  assertFalse(matchesView(note.copy(origin="external"),filter("""{"source":"owner"}"""),0))
 }
 @Test fun utcDateWindowIncludesMidnightAndBothKeys() {
  val now=java.time.Instant.parse("2026-10-14T15:30:00Z").toEpochMilli()
  val midnight=java.time.Instant.parse("2026-10-13T00:00:00Z").toEpochMilli()
  listOf("days","created_within_days").forEach { key ->
   assertTrue(matchesView(ViewNote("note","",midnight),filter("""{"$key":2}"""),now))
   assertFalse(matchesView(ViewNote("note","",midnight-1),filter("""{"$key":2}"""),now))
  }
 }
 @Test fun openTasksIgnoreInlineAndFencedCode() {
  assertFalse(matchesView(ViewNote("note","```\n- [ ] Example\n```\n\n`[ ] Literal`",0),filter("""{"open_tasks":true}"""),0))
  assertTrue(matchesView(ViewNote("note","- [ ] Real task",0),filter("""{"open_tasks":true}"""),0))
 }
}
