package io.theficos.spunto

import androidx.compose.runtime.*
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
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
 @Test fun followupInputLabelsItsActionAndKeepsThePendingQuestion() {
  var question by mutableStateOf("What about tomorrow?")
  var asking by mutableStateOf(false)
  var submitted:String?=null
  compose.setContent {SpuntoTheme("carta","light") {AskQuestionInput(question,true,true,asking,{question=it},{submitted=question;asking=true},followup=true)}}
  compose.onNodeWithText("Ask a follow-up").assertIsDisplayed()
  compose.onNodeWithText("Send question").assertIsEnabled().performClick()
  compose.runOnIdle {assertEquals("What about tomorrow?",submitted)}
  compose.onNodeWithText("Reading your notes…").assertIsNotEnabled()
  compose.runOnIdle {asking=false}
  compose.onNodeWithContentDescription("Question for your notes").assertTextContains("What about tomorrow?")
  compose.onNodeWithText("Send question").assertIsEnabled()
 }
 @Test fun authoringStartersPrefillAndFocusWithoutSendingAndNeverReplaceTyping() {
  var question by mutableStateOf("");var sent=0
  compose.setContent {SpuntoTheme("carta","light") {Column(Modifier.verticalScroll(rememberScrollState())) {
   AskQuestionInput(question,true,true,false,{question=it},{sent++},noteActions=true)
  }}}
  compose.onNodeWithText("Create a note").performClick()
  compose.runOnIdle {assertEquals(0,sent);assertEquals("Create a note: Weekend shopping — buy pasta and tomatoes.",question)}
  compose.onNodeWithContentDescription("Message for your notes").assertIsFocused()
  compose.onNodeWithText("Create a note").assertDoesNotExist()
  compose.onNodeWithText("Edit a note").assertDoesNotExist()
  compose.onNodeWithContentDescription("Message for your notes").performTextReplacement("My unfinished request")
  compose.runOnIdle {assertEquals("My unfinished request",question);assertEquals(0,sent);question=""}
  compose.onNodeWithText("Edit a note").performScrollTo().performClick()
  compose.runOnIdle {assertEquals("In my note “Weekend shopping”, add olive oil.",question);assertEquals(0,sent)}
  compose.onNodeWithText("Send").performScrollTo().performClick()
  compose.runOnIdle {assertEquals(1,sent)}
 }
 @Test fun oldServerInputDoesNotAdvertiseNoteAuthoring() {
  compose.setContent {SpuntoTheme("carta","light") {AskQuestionInput("",true,true,false,{},{},noteActions=false)}}
  compose.onNodeWithText("Create a note").assertDoesNotExist()
  compose.onNodeWithText("Edit a note").assertDoesNotExist()
  compose.onNodeWithText("Ask your notes").assertIsDisplayed()
 }

}
