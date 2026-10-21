package io.theficos.spunto

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.theficos.spunto.core.model.labelKey
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Actual root navigation and replica, guarded local synthetic fixtures; no microphone. */
@RunWith(AndroidJUnit4::class)
class NativeNavigationTest {
 private val timeout=org.junit.rules.Timeout(10,java.util.concurrent.TimeUnit.MINUTES)
 private val compose=createAndroidComposeRule<MainActivity>()
 @get:Rule val rules=org.junit.rules.RuleChain.outerRule(timeout).around(GuidePreferenceFixture(true)).around(compose)
 private val container get()=(compose.activity.application as SpuntoApplication).container
 private fun waitForCapture(text:String?=null) {
  val editable=hasContentDescription("Note text") and isEnabled()
  compose.waitUntil(10_000) {compose.onAllNodes(if(text==null) editable else editable and hasText(text)).fetchSemanticsNodes(atLeastOneRootRequired=false).isNotEmpty()}
 }
 private fun openSheet() {
  compose.onNodeWithContentDescription("Choose labels and views").performClick()
  compose.waitUntil(10_000) {compose.onAllNodesWithText("Labels and views").fetchSemanticsNodes(atLeastOneRootRequired=false).isNotEmpty()}
  compose.onNodeWithText("Labels and views").assertIsDisplayed()
 }
 private fun labelFilterMatcher()=hasSetTextAction() and hasText("Find a label") and hasAnyAncestor(isDialog())
 private fun filterLabels(query:String) {
  compose.onNode(labelFilterMatcher()).performClick()
  compose.waitUntil(10_000) {compose.onAllNodes(labelFilterMatcher() and isFocused()).fetchSemanticsNodes().size==1}
  compose.onNode(labelFilterMatcher() and isFocused()).performTextInput(query)
 }
 private fun sheetList()=compose.onNode(hasScrollAction() and hasAnyAncestor(isDialog()))
 private fun assertDraft(body:String) {runBlocking {assertEquals(body,container.repository.draft("capture").first()?.body)}}

 @Test fun dateFiltersStayOffTheTimelineAndApplyOnlyAfterConfirmation() {
  waitForCapture()
  compose.onNodeWithText("Dates").assertDoesNotExist()
  compose.onNodeWithText("Date filter").assertDoesNotExist()
  compose.onNodeWithContentDescription("Filter notes").performClick()
  compose.onNodeWithText("Filter notes").assertIsDisplayed()
  compose.onNodeWithText("Last 7 days").performClick()
  compose.onNodeWithText("Cancel").performScrollTo().performClick()
  compose.onNodeWithContentDescription("Filter notes").assertIsDisplayed().performClick()
  compose.onNodeWithText("Today").performClick()
  compose.onNodeWithText("Apply filters").performScrollTo().performClick()
  compose.onNodeWithContentDescription("Filters applied").assertIsDisplayed()
  compose.onNodeWithText("Filter notes").assertDoesNotExist()
  compose.onNodeWithText("Clear dates").assertDoesNotExist()
  compose.onNodeWithContentDescription("Filters applied").performClick()
  compose.onNodeWithText("Clear dates").performScrollTo().performClick()
  compose.onNodeWithText("Apply filters").performScrollTo().performClick()
  compose.onNodeWithContentDescription("Filter notes").assertIsDisplayed()
 }

 @Test fun timelineTitleSearchAndDismissKeepActualRouteAndCaptureDraft() {
  val appContainer=container
  val authority=runBlocking {LocalFixtureAuthority.require(appContainer)}
  val repository=appContainer.repository
  val original=runBlocking {repository.draft("capture").first()?.body}
  val body="Synthetic root navigation draft ${System.nanoTime()}"
  try {
   waitForCapture()
   compose.onNodeWithContentDescription("Choose labels and views").assertTextEquals("All notes ▾")
   runBlocking {authority.requireCurrent()}
   compose.onNodeWithContentDescription("Note text").performTextReplacement(body)
   compose.mainClock.advanceTimeBy(400);compose.waitForIdle()
   runBlocking {withTimeout(10_000) {repository.draft("capture").first {it?.body==body}}}
   openSheet()
   compose.onNodeWithText("All notes").assertIsSelected()
   filterLabels("Synthetic_no_matching_label_${System.nanoTime()}")
   compose.onNodeWithText("All notes").assertIsSelected()
   assertDraft(body)
   compose.onNodeWithText("Close").performClick()
   compose.onNodeWithText("Labels and views").assertDoesNotExist()
   compose.onNodeWithContentDescription("Choose labels and views").assertTextEquals("All notes ▾")
   waitForCapture(body);compose.onNodeWithContentDescription("Note text").assertTextEquals(body)
   assertDraft(body)
  } finally {authority.restoreCaptureDraft(original) {compose.activityRule.scenario.close()}}
 }

 @Test fun explicitNestedLabelReopenDismissAndAllNotesRestoreActualCapture() {
  val appContainer=container
  val authority=runBlocking {LocalFixtureAuthority.require(appContainer)}
  val repository=appContainer.repository
  val original=runBlocking {repository.draft("capture").first()?.body}
  val unique=java.lang.Long.toUnsignedString(System.nanoTime(),36)
  val path="Navigation_$unique/Child";val key=labelKey(path)
  val noteBody="Synthetic nested root navigation $unique #$path"
  val draftBody="Synthetic capture retained across nested navigation $unique"
  try {
   waitForCapture()
   runBlocking {authority.requireCurrent();repository.create(noteBody);withTimeout(10_000) {repository.labels().first {rows ->rows.any {it.key==key}}}}
   runBlocking {authority.requireCurrent()}
   compose.onNodeWithContentDescription("Note text").performTextReplacement(draftBody)
   compose.mainClock.advanceTimeBy(400);compose.waitForIdle()
   runBlocking {withTimeout(10_000) {repository.draft("capture").first {it?.body==draftBody}}}
   openSheet()
   filterLabels(unique)
   sheetList().performScrollToNode(hasTestTag("label:$key"))
   compose.onNodeWithTag("label:$key").assertIsNotSelected()
   compose.waitForIdle()
   compose.onNodeWithTag("label:$key").performClick()
   compose.onNodeWithText("Labels and views").assertDoesNotExist()
   compose.onNodeWithContentDescription("Choose labels and views").assertTextEquals("#$path ▾")
   compose.onNodeWithContentDescription("Note text").assertDoesNotExist()
   compose.waitUntil(10_000) {compose.onAllNodesWithText(noteBody,substring=true).fetchSemanticsNodes().isNotEmpty()}
   assertDraft(draftBody)
   openSheet()
   filterLabels(unique)
   sheetList().performScrollToNode(hasTestTag("label:$key"))
   compose.onNodeWithTag("label:$key").assertIsSelected()
   compose.onNodeWithText("Close").performClick()
   compose.onNodeWithContentDescription("Choose labels and views").assertTextEquals("#$path ▾")
   compose.onNodeWithContentDescription("Note text").assertDoesNotExist();assertDraft(draftBody)
   openSheet()
   compose.onNodeWithText("All notes").performClick()
   compose.onNodeWithContentDescription("Choose labels and views").assertTextEquals("All notes ▾")
   waitForCapture(draftBody);compose.onNodeWithContentDescription("Note text").assertTextEquals(draftBody)
   assertDraft(draftBody)
  } finally {authority.restoreCaptureDraft(original) {compose.activityRule.scenario.close()}}
 }
}
