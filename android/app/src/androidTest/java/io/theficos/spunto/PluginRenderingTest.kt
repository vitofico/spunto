package io.theficos.spunto

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import io.theficos.spunto.core.design.SpuntoTheme
import io.theficos.spunto.core.plugins.Vocabulary
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class PluginRenderingTest {
 @get:Rule val compose=createComposeRule()
 @get:Rule val timeout=org.junit.rules.Timeout(10,java.util.concurrent.TimeUnit.MINUTES)
 @Test fun translationFormSubmitsLanguageWithHiddenNoteContext() {
  val form=Json.parseToJsonElement("""{"type":"form","schema":{"type":"object","required":["note_id","language"],"properties":{"note_id":{"type":"string","default":"synthetic-note-a"},"language":{"type":"string","enum":["it","en"],"title":"Translate to"}}}}""").jsonObject
  var result:JsonObject?=null
  compose.setContent { SpuntoTheme { Vocabulary(form,onForm={result=it},formInitial=buildJsonObject { put("note_id","synthetic-note-a") },formHidden=setOf("note_id")) } }
  compose.onNodeWithText("synthetic-note-a").assertDoesNotExist()
  compose.onNodeWithText("Run").performClick()
  compose.onNodeWithText("Choose Translate to.").assertIsDisplayed()
  compose.onNodeWithText("it").performClick();compose.onNodeWithText("Run").performClick()
  compose.runOnIdle { assertEquals("it",result!!["language"]!!.jsonPrimitive.content);assertEquals("synthetic-note-a",result!!["note_id"]!!.jsonPrimitive.content) }
 }
 @Test fun overflowExpandsTheCurrentRowAndNamesThePlugin() {
  val proposals=(1..4).map { index -> buildJsonObject { put("id","p$index");put("plugin","tidy");put("title","Proposal $index");put("action","replace_body") } }
  val selected=mutableListOf<String>()
  compose.setContent { SpuntoTheme { ProposalLine(proposals,mapOf("tidy" to "Tidy text"),{selected+=it["id"]!!.jsonPrimitive.content},{}) } }
  compose.onNodeWithText("Proposal 4").assertDoesNotExist()
  compose.onNodeWithText("+1").performScrollTo().assertIsDisplayed().performClick()
  compose.runOnIdle { assertTrue(selected.isEmpty()) }
  compose.onNodeWithContentDescription("Proposal 4 · Tidy text · proposed wording change").assertIsDisplayed().performClick()
  compose.runOnIdle { assertEquals(listOf("p4"),selected) }
 }
 @Test fun precomputedCombinedDiffLinesRemainVisible() {
  val diff=Json.parseToJsonElement("""{"type":"diff","base_version":3,"hunks":[{"old_start":1,"new_start":1,"lines":["-Original words","+Proposed words"]}]}""").jsonObject
  compose.setContent { SpuntoTheme { Vocabulary(diff) } }
  compose.onNodeWithText("Based on version 3").assertIsDisplayed()
  compose.onNodeWithText("-Original words\n+Proposed words").assertIsDisplayed()
 }
}
