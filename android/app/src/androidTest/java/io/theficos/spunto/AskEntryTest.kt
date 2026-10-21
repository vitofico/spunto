package io.theficos.spunto

import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import io.theficos.spunto.core.design.SpuntoTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class AskEntryTest {
 @get:Rule val compose=createComposeRule()
 @Test fun directAskExplainsOfflineStateAndAcceptsQuestionsWithoutSearchWordThreshold() {
  var question by mutableStateOf("");var online by mutableStateOf(false);var enabled by mutableStateOf(true);var asking by mutableStateOf(false)
  var submitted:String?=null
  compose.setContent {SpuntoTheme("carta","light") {AskQuestionInput(question,enabled,online,asking,{question=it},{submitted=question})}}
  compose.onNodeWithText("Connect to your server to ask your notes.").assertIsDisplayed()
  compose.onNodeWithText("Ask your notes").assertIsNotEnabled()
  compose.runOnIdle {online=true}
  compose.onNodeWithText("Ask your notes").assertIsNotEnabled()
  compose.onNodeWithContentDescription("Question for your notes").performTextInput("Deadlines?")
  compose.onNodeWithText("Ask your notes").assertIsEnabled().performClick()
  compose.runOnIdle {assertEquals("Deadlines?",submitted);asking=true}
  compose.onNodeWithText("Reading your notes…").assertIsNotEnabled()
  compose.runOnIdle {asking=false;question="x".repeat(501)}
  compose.onNodeWithText("Ask your notes").assertIsNotEnabled()
  compose.runOnIdle {question="💡".repeat(500)}
  compose.onNodeWithText("Ask your notes").assertIsEnabled()
  compose.runOnIdle {enabled=false;question="Another question"}
  compose.onNodeWithText("Ask is switched off. Turn on Ask in Settings → Plugins.").assertIsDisplayed()
  compose.onNodeWithContentDescription("Question for your notes").assertIsNotEnabled()
 }
}
