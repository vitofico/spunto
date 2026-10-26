package io.theficos.spunto

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.espresso.Espresso.closeSoftKeyboard
import androidx.test.espresso.Espresso.pressBack
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.Timeout
import java.util.concurrent.TimeUnit

/** Exercise the actual toolbar and Android Back dispatcher without changing settings. */
class SettingsBackNavigationTest {
 private val compose=createAndroidComposeRule<MainActivity>()
 @get:Rule val rules=RuleChain.outerRule(Timeout(5,TimeUnit.MINUTES)).around(GuidePreferenceFixture(true)).around(compose)
 private val container get()=(compose.activity.application as SpuntoApplication).container

 private fun settingsVisible() {
  compose.onAllNodesWithText("Settings").assertCountEquals(1)
  compose.onNodeWithContentDescription("Note text").assertDoesNotExist()
 }

 private fun verifyParents(systemBack:Boolean) {
  val appContainer=container
  val authority=runBlocking {LocalFixtureAuthority.require(appContainer)}
  val original=runBlocking {appContainer.repository.draft("capture").first()?.body}
  try {
   compose.waitUntil(10_000) {compose.onAllNodesWithContentDescription("Settings").fetchSemanticsNodes().isNotEmpty()}
   compose.onNodeWithContentDescription("Settings").performClick()
   settingsVisible()
   // Devices, Profile and Export open the web app; only these two are pages on the phone.
   listOf("Appearance","Plugins").forEach {section ->
    compose.onNode(hasScrollAction()).performScrollToNode(hasText(section) and hasClickAction())
    compose.onNodeWithText(section).performClick()
    compose.onNodeWithContentDescription("Back").assertIsDisplayed()
    if(systemBack) {
     compose.activityRule.scenario.recreate()
     compose.onNodeWithContentDescription("Back").assertIsDisplayed()
     pressBack()
    } else compose.onNodeWithContentDescription("Back").performClick()
    settingsVisible()
   }
   // The final Back must target navigation, not consume a restored keyboard session.
   closeSoftKeyboard()
   if(systemBack) pressBack() else compose.onNodeWithContentDescription("Back").performClick()
   // Both approved headers identify All notes; IME/insets restoration chooses the decoration.
   compose.onNodeWithContentDescription("Choose labels and views").assertIsDisplayed().assert(
    hasText("All notes  ⌄") or hasText("All notes ▾")
   )
   compose.onAllNodesWithText("Settings").assertCountEquals(0)
   // Opening capture can materialize an empty draft row; its words must remain unchanged.
   assertEquals(original.orEmpty(),runBlocking {appContainer.repository.draft("capture").first()?.body}.orEmpty())
  } finally {authority.restoreCaptureDraft(original) {compose.activityRule.scenario.close()}}
 }

 @Test fun toolbarBackReturnsEveryCategoryToSettingsThenNotes()=verifyParents(false)
 @Test fun systemBackReturnsEveryRestoredCategoryToSettingsThenNotes()=verifyParents(true)
}
