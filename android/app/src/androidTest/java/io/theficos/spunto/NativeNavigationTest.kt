package io.theficos.spunto

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.espresso.Espresso.closeSoftKeyboard
import io.theficos.spunto.core.model.Note
import io.theficos.spunto.core.model.labelKey
import io.theficos.spunto.core.model.uuidV7
import io.theficos.spunto.data.local.NoteEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.time.ZoneId

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
  compose.waitUntil(10_000) {compose.onAllNodes(labelFilterMatcher()).fetchSemanticsNodes(atLeastOneRootRequired=false).isNotEmpty()}
  compose.onNode(labelFilterMatcher()).assertIsDisplayed()
 }
 private fun labelFilterMatcher()=hasSetTextAction() and hasText("Find a label") and hasAnyAncestor(isDialog())
 private fun filterLabels(query:String) {
  compose.onNode(labelFilterMatcher()).performClick()
  compose.waitUntil(10_000) {compose.onAllNodes(labelFilterMatcher() and isFocused()).fetchSemanticsNodes().size==1}
  compose.onNode(labelFilterMatcher() and isFocused()).performTextInput(query)
 }
 private fun sheetList()=compose.onNode(hasScrollAction() and hasAnyAncestor(isDialog()))
 private fun assertDraft(body:String) {runBlocking {assertEquals(body,container.repository.draft("capture").first()?.body)}}
 private fun assertNavigationTitle(title:String) {
  compose.onNodeWithContentDescription("Choose labels and views").assertTextContains(title,substring=true)
 }
 private fun homeTitle() {
  // The approved idle home and focused app bar have different decorative chevrons.
  closeSoftKeyboard()
  compose.waitUntil(5000) {compose.onAllNodesWithText("spunto.").fetchSemanticsNodes().isNotEmpty()}
  assertNavigationTitle("All notes")
 }
 private fun waitForFilterSheet() {
  val title=hasText("Filter notes") and hasAnyAncestor(isDialog())
  // The dialog can enter semantics before its sheet is positioned on screen.
  compose.waitUntil(10_000) {compose.onAllNodes(title).fetchSemanticsNodes(atLeastOneRootRequired=false).size==1 && compose.onNode(title).isDisplayed()}
 }
 private fun openFilters() {
  homeTitle()
  val filter=hasContentDescription(container.context.getString(R.string.core_filter_notes)) or
   hasContentDescription(container.context.getString(R.string.core_filters_applied))
  compose.onNode(filter).assertIsDisplayed().performClick()
  waitForFilterSheet()
 }
 private fun todayFilter()=compose.onNodeWithContentDescription(container.context.getString(R.string.core_clear_date_filter,container.context.getString(R.string.core_today)))

 @Test fun notebookScrollMinimizesCaptureAndExpansionReturnsTheSameDraft() {
  val authority=runBlocking {LocalFixtureAuthority.require(container)}
  val repository=container.repository
  val original=runBlocking {repository.draft("capture").first()?.body}
  val fixture="Synthetic dock fixture ${System.nanoTime()}"
  val draft="$fixture draft"
  val revised="$draft\n\nExpanded edit"
  val yesterday=LocalDate.now().minusDays(1).atTime(12,0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
  val now=System.currentTimeMillis()
  val notes=List(20) {index ->
   Note(uuidV7(),"# $fixture $index\n\nNotebook excerpt",createdAt=(if(index<10) now else yesterday)-index)
  }
  val inserted=mutableListOf<String>()
  val feed=compose.onNodeWithTag("notebook-feed")
  val dock=compose.onNodeWithTag("quick-capture-dock")
  val pencil=compose.onNodeWithTag("quick-capture-pencil")
  val editor=compose.onNodeWithContentDescription("Note text")
  try {
   runBlocking {
    authority.requireCurrent()
    for(note in notes) {
     check(repository.dao.get(note.id)==null)
     repository.dao.put(NoteEntity.from(note))
     inserted+=note.id
    }
   }
   waitForCapture()
   homeTitle()
   compose.waitUntil(10_000) {compose.onAllNodesWithText("Notebook excerpt",substring=true).fetchSemanticsNodes().isNotEmpty()}
   val feedBounds=feed.assertIsDisplayed().fetchSemanticsNode().boundsInRoot
   val dockBounds=dock.assertIsDisplayed().fetchSemanticsNode().boundsInRoot
   assertTrue("The feed ends before the capture dock",feedBounds.bottom<=dockBounds.top)
   editor.performTextReplacement(draft)
   closeSoftKeyboard()
   feed.performScrollToIndex(4)
   pencil.assertIsDisplayed().assert(
    SemanticsMatcher.expectValue(SemanticsProperties.StateDescription,"Draft available"),
   )
   dock.assertDoesNotExist()
   val visibleDate=compose.onNode(hasTestTag("notebook-date-header") and hasContentDescription("Today"))
    .assertIsDisplayed().fetchSemanticsNode().boundsInRoot
   val entry=compose.onAllNodesWithContentDescription(fixture,substring=true).fetchSemanticsNodes().first()
   assertEquals(entry.boundsInRoot.width,visibleDate.width,1f)
   val beforeOpen=feed.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
   pencil.performClick()
   waitForCapture(draft)
   val afterOpen=feed.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
   assertEquals("Opening the pencil preserves the list position",beforeOpen,afterOpen,.01f)
   val editingFeed=feed.assertExists().fetchSemanticsNode().boundsInRoot
   val editingDock=dock.assertIsDisplayed().fetchSemanticsNode().boundsInRoot
   assertTrue("Typing keeps the dock separate from the feed",editingFeed.bottom<=editingDock.top)
   compose.onNodeWithText("Save",useUnmergedTree=true).assertIsDisplayed().assertIsEnabled()
   compose.onNodeWithContentDescription("Expand composer").performClick()
   compose.onNodeWithText("New note").assertIsDisplayed()
   waitForCapture(draft)
   editor.assertTextEquals(draft).performTextReplacement(revised)
   compose.onNodeWithContentDescription("Back").performClick()
   waitForCapture(revised)
   editor.assertTextEquals(revised)
   dock.assertIsDisplayed()
   closeSoftKeyboard()
   feed.performScrollToIndex(0)
   compose.onNodeWithContentDescription("Search").assertIsDisplayed().performClick()
   compose.onNodeWithContentDescription("Back").performClick()
   waitForCapture(revised)
   editor.assertTextEquals(revised)
   assertDraft(revised)
   feed.performScrollToIndex(19)
   pencil.assertIsDisplayed()
   val yesterdayDate=compose.onNode(hasTestTag("notebook-date-header") and hasContentDescription("Yesterday"))
    .assertIsDisplayed().fetchSemanticsNode().boundsInRoot
   assertEquals("The next date takes the same pinned position",visibleDate.top,yesterdayDate.top,1f)
  } finally {
   try {authority.restoreCaptureDraft(original) {compose.activityRule.scenario.close()}}
   finally {
    runBlocking {
     authority.requireCurrent()
     for(id in inserted) repository.dao.remove(id)
    }
   }
  }
 }

 @Test fun alwaysCompactIsDeviceLocalAndShowsThePencilAtTheTop() {
  val preferences=container.preferences
  val key="quick-capture-compact"
  val original=preferences.all[key] as? Boolean
  val dock=compose.onNodeWithTag("quick-capture-dock")
  val pencil=compose.onNodeWithTag("quick-capture-pencil")
  fun openAppearance() {
   compose.onNodeWithContentDescription("Settings").performClick()
   compose.onNode(hasText("Appearance") and hasClickAction()).performClick()
  }
  fun choose(label:String) {
   val option=hasText(label) and hasClickAction()
   compose.onNode(hasScrollAction()).performScrollToNode(option)
   compose.onNode(option).performClick().assertIsSelected()
   repeat(2) {compose.onNodeWithContentDescription("Back").performClick()}
  }
  try {
   openAppearance()
   choose("Always compact")
   pencil.assertIsDisplayed()
   dock.assertDoesNotExist()
   compose.activityRule.scenario.recreate()
   pencil.assertIsDisplayed()
   pencil.performClick()
   waitForCapture()
   dock.assertIsDisplayed()
   compose.onNodeWithContentDescription("Minimize Quick Capture").performClick()
   pencil.assertIsDisplayed()
   openAppearance()
   choose("Collapses while scrolling")
   waitForCapture()
   dock.assertIsDisplayed()
   pencil.assertDoesNotExist()
  } finally {
   preferences.edit().apply { if(original==null) remove(key) else putBoolean(key,original) }.commit()
  }
 }

 @Test fun dateFiltersStayOffTheTimelineAndApplyOnlyAfterConfirmation() {
  waitForCapture()
  compose.onNodeWithText("Dates").assertDoesNotExist()
  compose.onNodeWithText("Date filter").assertDoesNotExist()
  todayFilter().assertDoesNotExist()
  openFilters()
  compose.onNodeWithText("Filter notes").assertIsDisplayed()
  compose.onNodeWithText("Last 7 days").performClick()
  compose.onNodeWithText("Cancel").performScrollTo().performClick()
  todayFilter().assertDoesNotExist()
  compose.onNodeWithContentDescription(container.context.getString(R.string.core_clear_date_filter,container.context.getString(R.string.core_last_week))).assertDoesNotExist()
  openFilters()
  compose.onNodeWithText("Last 7 days").assertIsNotSelected()
  compose.onNodeWithText("Today").performClick()
  compose.onNodeWithText("Apply filters").performScrollTo().performClick()
  todayFilter().assertIsDisplayed().assertIsSelected()
  compose.onNodeWithText("Filter notes").assertDoesNotExist()
  compose.onNodeWithText("Clear dates").assertDoesNotExist()
  openFilters()
  compose.onNodeWithText("Today").assertIsSelected()
  compose.onNodeWithText("Clear dates").performScrollTo().performClick()
  compose.onNodeWithText("Apply filters").performScrollTo().performClick()
  todayFilter().assertDoesNotExist()
  homeTitle()
 }

 @Test fun timelineTitleSearchAndDismissKeepActualRouteAndCaptureDraft() {
  val appContainer=container
  val authority=runBlocking {LocalFixtureAuthority.require(appContainer)}
  val repository=appContainer.repository
  val original=runBlocking {repository.draft("capture").first()?.body}
  val body="Synthetic root navigation draft ${System.nanoTime()}"
  try {
   waitForCapture()
   homeTitle()
   runBlocking {authority.requireCurrent()}
   compose.onNodeWithContentDescription("Note text").performTextReplacement(body)
   compose.mainClock.advanceTimeBy(400);compose.waitForIdle()
   runBlocking {withTimeout(10_000) {repository.draft("capture").first {it?.body==body}}}
   openSheet()
   compose.onNode(hasText("All notes") and hasAnyAncestor(isDialog())).assertIsSelected()
   filterLabels("Synthetic_no_matching_label_${System.nanoTime()}")
   compose.onNode(hasText("All notes") and hasAnyAncestor(isDialog())).assertIsSelected()
   assertDraft(body)
   compose.onNodeWithContentDescription("Close sheet").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
   compose.onNode(labelFilterMatcher()).assertDoesNotExist()
   homeTitle()
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
  val passage="Synthetic nested root navigation $unique"
  val noteBody="$passage #$path"
  val draftBody="Synthetic capture retained across nested navigation $unique"
  try {
   waitForCapture()
   val noteId=runBlocking {
    authority.requireCurrent()
    val created=repository.create(noteBody)
    withTimeout(10_000) {repository.labels().first {rows ->rows.any {it.key==key}}}
    created
   }
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
   compose.onNode(labelFilterMatcher()).assertDoesNotExist()
   assertNavigationTitle("#$path")
   compose.onNodeWithContentDescription("Note text").assertDoesNotExist()
   compose.waitUntil(10_000) {compose.onAllNodesWithText(passage).fetchSemanticsNodes().isNotEmpty()}
   compose.onNodeWithText(passage).assertIsDisplayed()
   compose.onNodeWithText(path).assertIsDisplayed()
   runBlocking {assertEquals(noteBody,repository.dao.get(noteId)?.body)}
   assertDraft(draftBody)
   openSheet()
   filterLabels(unique)
   sheetList().performScrollToNode(hasTestTag("label:$key"))
   compose.onNodeWithTag("label:$key").assertIsSelected()
   compose.onNodeWithContentDescription("Close sheet").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
   assertNavigationTitle("#$path")
   compose.onNodeWithContentDescription("Note text").assertDoesNotExist();assertDraft(draftBody)
   openSheet()
   compose.onNode(hasText("All notes") and hasAnyAncestor(isDialog())).performClick()
   homeTitle()
   waitForCapture(draftBody);compose.onNodeWithContentDescription("Note text").assertTextEquals(draftBody)
   assertDraft(draftBody)
  } finally {authority.restoreCaptureDraft(original) {compose.activityRule.scenario.close()}}
 }
}
