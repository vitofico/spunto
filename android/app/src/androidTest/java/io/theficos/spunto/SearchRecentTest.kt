package io.theficos.spunto

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.theficos.spunto.core.design.SpuntoTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** S2 on the real search screen; the phone's own recent searches are put back afterwards. */
@RunWith(AndroidJUnit4::class)
class SearchRecentTest {
 @get:Rule val compose=createComposeRule()
 @get:Rule val timeout=org.junit.rules.Timeout(5,java.util.concurrent.TimeUnit.MINUTES)
 @Test fun aSearchIsRememberedRerunsOnTapAndClears() {
  val app=InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as SpuntoApplication
  val model=SpuntoViewModel(app);val preferences=model.container.preferences
  val before=preferences.getString("recent-searches",null);val word="synthetic${System.nanoTime()}"
  preferences.edit().putString("recent-searches","""["older search"]""").commit()
  try {
   compose.setContent {SpuntoTheme("carta","light") {SearchScreen(model,onNote={})}}
   compose.onNodeWithContentDescription("Recent searches").assertIsDisplayed();compose.onNodeWithText("older search").assertIsDisplayed()
   compose.onNode(hasSetTextAction()).performTextInput(word)
   compose.onNodeWithContentDescription("Recent searches").assertDoesNotExist()
   compose.onNode(hasSetTextAction()).performImeAction()
   compose.onNodeWithContentDescription("Clear search").performClick()
   compose.onAllNodes(hasClickAction() and (hasText(word) or hasText("older search"))).assertCountEquals(2)
   compose.runOnIdle {assertEquals(listOf(word,"older search"),recentSearches(preferences.getString("recent-searches",null)))}
   compose.onNodeWithText("older search").performClick()
   compose.onNode(hasSetTextAction()).assert(hasText("older search"))
   compose.runOnIdle {assertEquals(listOf("older search",word),recentSearches(preferences.getString("recent-searches",null)))}
   compose.onNodeWithContentDescription("Clear search").performClick()
   compose.onNodeWithContentDescription("Clear recent searches").performClick()
   compose.onNodeWithText(word).assertDoesNotExist();compose.onNodeWithContentDescription("Recent searches").assertDoesNotExist()
   compose.runOnIdle {assertNull(preferences.getString("recent-searches",null))}
  } finally {
   preferences.edit().apply {if(before==null) remove("recent-searches") else putString("recent-searches",before)}.commit()
   model.retire()
  }
 }
}
