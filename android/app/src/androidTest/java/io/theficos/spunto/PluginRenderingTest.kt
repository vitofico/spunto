package io.theficos.spunto

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import io.theficos.spunto.core.design.SpuntoTheme
import io.theficos.spunto.core.plugins.Vocabulary
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class PluginRenderingTest {
 @get:Rule val compose=createComposeRule()
 @get:Rule val timeout=org.junit.rules.Timeout(10,java.util.concurrent.TimeUnit.MINUTES)
 @Test fun longResultsScrollWithoutMovingTheResultHeadingOrActions() {
  val app=InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as SpuntoApplication
  lateinit var model:SpuntoViewModel
  compose.runOnIdle {model=SpuntoViewModel(app)}
  val mounted=mutableStateOf(true)
  val end="End of the synthetic result."
  val markdown=(1..30).joinToString("\n\n") {"Synthetic paragraph $it. It should scroll without moving the controls."}+"\n\n"+end
  val result=commandEnvelope("library-stats","summary",JsonObject(emptyMap()),buildJsonObject {put("type","markdown");put("text",markdown)})
  try {
   runBlocking {LocalFixtureAuthority.require(model.container)}
   compose.setContent {SpuntoTheme {
    val scope=rememberCoroutineScope()
    val feedback=remember {Feedback(SnackbarHostState(),scope)}
    CompositionLocalProvider(LocalFeedback provides feedback) {
     if(mounted.value) CommandResultSheet(model,result,{}, {}, {})
    }
   }}
   val heading=compose.onNodeWithText(app.getString(R.string.settings_tool_result)).assertIsDisplayed()
   val close=compose.onNodeWithText(app.getString(R.string.settings_close)).assertIsDisplayed()
   val copy=compose.onNodeWithText(app.getString(R.string.settings_copy)).assertIsDisplayed()
   compose.onNodeWithText(end).performScrollTo().assertIsDisplayed()
   heading.assertIsDisplayed()
   close.assertIsDisplayed()
   copy.assertIsDisplayed()
  } finally {
   compose.runOnIdle {mounted.value=false;model.retire()}
  }
 }
 @Test fun translationFormSubmitsLanguageWithHiddenNoteContext() {
  val form=Json.parseToJsonElement("""{"type":"form","schema":{"type":"object","required":["note_id","language"],"properties":{"note_id":{"type":"string","default":"synthetic-note-a"},"language":{"type":"string","enum":["it","en"],"title":"Translate to"}}}}""").jsonObject
  var result:JsonObject?=null
  compose.setContent { SpuntoTheme { Vocabulary(form,onForm={result=it},formInitial=buildJsonObject { put("note_id","synthetic-note-a") },formHidden=setOf("note_id")) } }
  compose.onNodeWithText("synthetic-note-a").assertDoesNotExist()
  compose.onNodeWithText("Run").performClick()
  compose.onNodeWithText("Choose Translate to.").assertIsDisplayed()
  compose.onNodeWithText("Italian").performClick();compose.onNodeWithText("Run").performClick()
  compose.runOnIdle { assertEquals("it",result!!["language"]!!.jsonPrimitive.content);assertEquals("synthetic-note-a",result!!["note_id"]!!.jsonPrimitive.content) }
 }
 @Test fun overflowExpandsTheCurrentRowAndNamesThePlugin() {
  val proposals=(1..4).map { index -> buildJsonObject { put("id","p$index");put("plugin","tidy");put("title","Proposal $index");put("action","replace_body") } }
  val selected=mutableListOf<String>()
  compose.setContent { SpuntoTheme { ProposalLine(proposals,mapOf("tidy" to "Tidy text"),{selected+=it["id"]!!.jsonPrimitive.content},{}) } }
  compose.onNodeWithText("Proposal 4").assertDoesNotExist()
  compose.onNodeWithText("+1").assertIsDisplayed().performClick()
  compose.runOnIdle { assertTrue(selected.isEmpty()) }
  compose.onNodeWithContentDescription("Proposal 4 · Tidy text · proposed wording change").assertIsDisplayed().performClick()
  compose.runOnIdle { assertEquals(listOf("p4"),selected) }
 }
 @Test fun dismissEndsTheRowAndDismissesEverySuggestionInIt() {
  val proposals=(1..4).map { index -> buildJsonObject { put("id","p$index");put("plugin","label-suggestions");put("title","Add #idea$index");put("action","add_label") } }
  val dismissed=mutableListOf<String>()
  compose.setContent { SpuntoTheme { ProposalLine(proposals,mapOf("label-suggestions" to "Label suggestions"),{},{dismissed+=it}) } }
  compose.onNodeWithContentDescription("Add #idea2 · Label suggestions · proposed addition").performTouchInput { longClick() }
  compose.runOnIdle { assertEquals(listOf("p2"),dismissed);dismissed.clear() }
  compose.onNodeWithContentDescription("Dismiss 4 suggestions").assertIsDisplayed().assertHasClickAction().performClick()
  compose.runOnIdle { assertEquals(listOf("p1","p2","p3","p4"),dismissed) }
 }
 @Test fun precomputedCombinedDiffLinesHighlightBothSidesWithoutRawMarkers() {
  val diff=Json.parseToJsonElement("""{"type":"diff","base_version":3,"hunks":[{"old_start":1,"new_start":1,"lines":["-Original words","+Proposed words"]}]}""").jsonObject
  compose.setContent { SpuntoTheme { Vocabulary(diff) } }
  compose.onNodeWithText("Based on version 3").assertIsDisplayed()
  compose.onNodeWithText("− Removed").assertIsDisplayed()
  compose.onNodeWithText("+ Added").assertIsDisplayed()
  compose.onNodeWithText("OriginalProposed words").assertIsDisplayed()
 }
 @Test fun longChoicesAreSearchableAndTechnicalContextStaysHidden() {
  val form=Json.parseToJsonElement("""{"type":"form","schema":{"type":"object","required":["note_id","target"],"description":"Search your notes, then choose a destination.","properties":{"note_id":{"type":"string","default":"hidden-note-id"},"source_choices":{"type":"array","items":{"type":"string"},"default":["hidden-choice-id"]},"target":{"type":"string","title":"Link to","enum":["1. Weekend shopping","2. Project planning","3. Garden ideas","4. Winter holiday","5. Travel checklist","6. Reading list"]}}}}""").jsonObject
  var result:JsonObject?=null
  compose.setContent {SpuntoTheme {Vocabulary(form,onForm={result=it})}}
  compose.onNodeWithText("hidden-note-id").assertDoesNotExist()
  compose.onNodeWithText("hidden-choice-id").assertDoesNotExist()
  compose.onNodeWithText("Search your notes, then choose a destination.").assertIsDisplayed()
  compose.onNodeWithContentDescription("Choose Link to").performClick()
  compose.onNodeWithText("Find an option").performTextInput("Garden")
  compose.onNodeWithText("1. Weekend shopping").assertDoesNotExist()
  compose.onNodeWithText("3. Garden ideas").performClick()
  compose.onNodeWithText("Run").performClick()
  compose.runOnIdle {assertEquals("3. Garden ideas",result!!["target"]!!.jsonPrimitive.content);assertEquals("hidden-note-id",result!!["note_id"]!!.jsonPrimitive.content)}
 }
 @Test fun emptyNoteResultsExplainTheEmptyPanel() {
  val result=Json.parseToJsonElement("""{"type":"notes","ids":[]}""").jsonObject
  compose.setContent {SpuntoTheme {Vocabulary(result)}}
  compose.onNodeWithText("No notes to show yet.").assertIsDisplayed()
 }
 @Test fun paragraphOnlyEditsHaveAReadableDescription() {
  val diff=Json.parseToJsonElement("""{"type":"diff","base_version":3,"hunks":[{"old_lines":["Paragraph"],"new_lines":["Paragraph",""]}]}""").jsonObject
  compose.setContent {SpuntoTheme {Vocabulary(diff)}}
  compose.onNodeWithText("Added 1 line break").assertIsDisplayed()
 }
}
