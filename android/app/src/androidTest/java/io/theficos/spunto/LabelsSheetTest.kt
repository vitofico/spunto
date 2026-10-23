package io.theficos.spunto

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import io.theficos.spunto.core.design.SpuntoTheme
import io.theficos.spunto.core.model.LabelCount
import kotlinx.serialization.json.*
import org.junit.*
import org.junit.Assert.*

/** Isolated UI fixtures: no owner replica, bearer, preferences, transport or capture session. */
class LabelsSheetTest {
 @get:Rule val compose=createComposeRule()
 @get:Rule val timeout=org.junit.rules.Timeout(10,java.util.concurrent.TimeUnit.MINUTES)
 private val labels=listOf(LabelCount("project","Project",3),LabelCount("project/spunto","Project/Spunto",2))
 private fun manifest(active:Boolean=true)=Json.parseToJsonElement("""{"plugins_rev":17,"hidden_views":["label-suggestions/unlabeled"],"plugins":[{"id":"tasks","state":"${if(active) "enabled" else "disabled"}","contributes":{"views":[{"id":"open","title":"Open tasks","filter":{"open_tasks":true}}]}},{"id":"label-suggestions","contributes":{"views":[{"id":"unlabeled","title":"Unlabeled","filter":{"no_labels":true}}]}}]}""").jsonObject
 @Test fun searchAndDismissNeverNavigateOrMutateAnUnrelatedDraft() {
  val open=mutableStateOf(true);val routes=mutableListOf<String>();var management=0
  val draft=mutableStateOf("Synthetic draft remains exactly here")
  compose.setContent {SpuntoTheme("carta","light") {if(open.value) LabelsSheet(LabelsNavigationSnapshot("timeline",labels,manifest()),{routes+=it},{routes+="settings"},{management++},{management++},{open.value=false})}}
  compose.onNodeWithText("Find a label").performTextInput("SPUNTO")
  compose.onNode(hasSetTextAction()).assertTextEquals("SPUNTO")
  androidx.test.espresso.Espresso.closeSoftKeyboard()
  // The IME changes the lazy viewport; each row must be scrolled into view before checking it.
  for(tag in listOf("label:project","label:project/spunto")) {
   compose.onNode(hasScrollAction()).performScrollToNode(hasTestTag(tag))
   compose.onNodeWithTag(tag).assertIsDisplayed()
  }
  compose.onNodeWithContentDescription("Close sheet").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
  compose.runOnIdle {assertTrue(routes.isEmpty());assertEquals(0,management);assertEquals("Synthetic draft remains exactly here",draft.value);assertFalse(open.value)}
 }
 @Test fun allNotesCarriesItsCountAndReviewOnlyShowsWhenItemsWait() {
  val waiting=mutableStateOf(0)
  compose.setContent {SpuntoTheme("carta","light") {LabelsSheet(LabelsNavigationSnapshot("timeline",labels,manifest()),{},{},{},{},{},reviewWaiting=waiting.value,allNotes=24)}}
  compose.onNode(hasText("All notes") and hasText("24")).assertIsDisplayed().assertIsSelected().assert(hasContentDescription("All notes, 24 notes"))
  compose.onNodeWithText("Archive").assertIsDisplayed();compose.onNodeWithText("Trash").assertIsDisplayed();compose.onNodeWithText("Review").assertDoesNotExist()
  compose.runOnIdle {waiting.value=6}
  compose.onNode(hasText("Review") and hasText("6")).assertIsDisplayed()
 }
 @Test fun exactLabelSelectionNavigatesOnceWithCanonicalKey() {
  val open=mutableStateOf(true);val routes=mutableListOf<String>()
  compose.setContent {SpuntoTheme("carta","dark") {if(open.value) LabelsSheet(LabelsNavigationSnapshot("label:project/spunto",labels,manifest()),{routes+=it;open.value=false},{},{},{},{open.value=false})}}
  compose.onNode(hasScrollAction()).performScrollToNode(hasTestTag("label:project/spunto"))
  compose.onNodeWithTag("label:project/spunto").assertIsSelected();compose.onNodeWithTag("label:project").assertIsNotSelected()
  compose.onNodeWithTag("label:project/spunto").performClick()
  compose.runOnIdle {assertEquals(listOf("label:project/spunto"),routes);assertFalse(open.value)}
 }
 @Test fun hiddenViewsAreTemporarilyBrowsableOfflineWithoutManagementWrites() {
  val cached=manifest();val before=cached.toString();val routes=mutableListOf<String>();var management=0
  compose.setContent {SpuntoTheme("carta","light") {LabelsSheet(LabelsNavigationSnapshot("timeline",labels,cached),{routes+=it},{},{management++},{management++},{},managementAvailable=false,managementMessage="Offline. Shared management needs your server.")}}
  compose.onNodeWithText("Unlabeled").assertDoesNotExist()
  compose.onNode(hasScrollAction()).performScrollToNode(hasText("Show more views"));compose.onNodeWithText("Show more views").performClick()
  compose.onNode(hasScrollAction()).performScrollToNode(hasText("Unlabeled"));compose.onNodeWithText("Unlabeled").performClick()
  compose.onNode(hasScrollAction()).performScrollToNode(hasText("Offline. Shared management needs your server."))
  compose.onNodeWithText("Offline. Shared management needs your server.").assertIsDisplayed()
  compose.runOnIdle {assertEquals(listOf("view:label-suggestions:unlabeled"),routes);assertEquals(0,management);assertEquals(before,cached.toString())}
 }
 @Test fun cachedPublicationChangesRemoveInactiveViewsFromAnOpenSheet() {
  val cached=mutableStateOf(manifest());val routes=mutableListOf<String>()
  compose.setContent {SpuntoTheme("carta","light") {LabelsSheet(LabelsNavigationSnapshot("view:tasks:open",labels,cached.value),{routes+=it},{},{},{},{})}}
  compose.onNode(hasScrollAction()).performScrollToNode(hasText("Open tasks"));compose.onNodeWithText("Open tasks").assertIsSelected()
  compose.runOnIdle {cached.value=manifest(false)}
  compose.onNodeWithText("Open tasks").assertDoesNotExist()
  compose.runOnIdle {assertEquals("View unavailable",navigationTitle(androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext,"view:tasks:open",labels,cached.value));assertTrue(routes.isEmpty())}
 }
}
