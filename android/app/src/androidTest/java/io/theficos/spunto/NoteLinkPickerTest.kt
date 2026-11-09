package io.theficos.spunto

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.theficos.spunto.core.design.SpuntoTheme
import kotlinx.coroutines.awaitCancellation
import kotlinx.serialization.json.*
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class NoteLinkPickerTest {
 @get:Rule val compose=createComposeRule()

 @Test fun queryReplacesSelectionAndOneInsertUsesTheChosenTarget() {
  val staleSearchStarted=AtomicBoolean()
  val staleSearchCancelled=AtomicBoolean()
  val writes=mutableListOf<String>()
  val receipts=mutableListOf<Int>()
  compose.setContent {
   SpuntoTheme("carta","light") {
    NoteLinkPicker("source",true,search={query,_ ->
     when(query) {
      "" -> hits(note("source","Source note",""),note("west","Duplicate title","West wing"),note("east","Duplicate title","East wing"))
      "stale" -> {
       staleSearchStarted.set(true)
       try {awaitCancellation()} finally {staleSearchCancelled.set(true)}
      }
      else -> hits(note("new","Another title","New destination"))
     }
    },insert={writes+=it.id;R.string.core_link_inserted},reportError={_,_->"Unavailable"},
     onClose={},onInserted={receipts+=it})
   }
  }

  compose.onNodeWithText("Recent notes").assertIsDisplayed()
  compose.onNodeWithText("Source note").assertDoesNotExist()
  compose.onAllNodesWithText("Duplicate title").assertCountEquals(2)
  compose.onNode(hasText("East wing") and hasClickAction()).performClick().assertIsSelected()
  compose.onNodeWithText("Insert link").assertIsEnabled()
  compose.onNode(hasSetTextAction()).performTextReplacement("stale")
  compose.waitUntil(5_000) {staleSearchStarted.get()}
  compose.onNodeWithText("Insert link").assertIsNotEnabled()
  compose.onNode(hasSetTextAction()).performTextReplacement("new")
  compose.waitUntil(5_000) {staleSearchCancelled.get() && compose.onAllNodesWithText("Another title").fetchSemanticsNodes().isNotEmpty()}
  compose.onNodeWithText("Duplicate title").assertDoesNotExist()
  compose.onNodeWithText("Insert link").assertIsNotEnabled()
  compose.onNode(hasText("New destination") and hasClickAction()).performClick()
  compose.onNodeWithText("Insert link").performClick()
  compose.runOnIdle {
   assertEquals(listOf("new"),writes)
   assertEquals(listOf(R.string.core_link_inserted),receipts)
  }
 }

 @Test fun failedSearchCanRetryAndShowTheEmptyState() {
  val attempts=AtomicInteger()
  val closes=AtomicInteger()
  compose.setContent {
   SpuntoTheme("carta","light") {
    NoteLinkPicker("source",true,search={_,_ ->
     if(attempts.incrementAndGet()==1) throw IOException()
     hits(note("source","Source note",""))
    },insert={error("Insert requires a selected note")},reportError={_,_->"Search failed"},
     onClose={closes.incrementAndGet()},onInserted={})
   }
  }

  compose.onNodeWithText("Search failed").assertIsDisplayed()
  compose.onNodeWithText("Try again").performClick()
  compose.onNodeWithText("No other notes found.").assertIsDisplayed()
  compose.onNodeWithText("Insert link").assertIsNotEnabled()
  compose.onNodeWithText("Cancel").performClick()
  compose.runOnIdle {assertEquals(2,attempts.get());assertEquals(1,closes.get())}
 }

 private fun hits(vararg notes:JsonObject)=buildJsonObject {put("hits",JsonArray(notes.toList()))}

 private fun note(id:String,title:String,excerpt:String)=buildJsonObject {
  put("id",id)
  put("title",title)
  put("body","$title\n\n$excerpt")
  put("created_at","2026-10-10T12:30:00Z")
  put("version",1)
 }
}
