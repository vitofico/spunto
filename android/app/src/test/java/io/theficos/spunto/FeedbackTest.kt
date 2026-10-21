package io.theficos.spunto

import androidx.compose.material3.SnackbarHostState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import org.junit.Assert.assertEquals
import org.junit.Test

class FeedbackTest {
 @Test fun aNewMessageReplacesTheOneOnScreenAndOnlyItsActionRuns() {
  val host=SnackbarHostState();val scope=CoroutineScope(Dispatchers.Unconfined);val undone=mutableListOf<String>()
  val feedback=Feedback(host,scope)
  feedback.show("Archived","Undo") {undone.add("archive")}
  assertEquals("Archived",host.currentSnackbarData?.visuals?.message)
  feedback.show("Moved to Trash","Undo") {undone.add("trash")}
  assertEquals("Moved to Trash",host.currentSnackbarData?.visuals?.message)
  host.currentSnackbarData!!.performAction()
  assertEquals(listOf("trash"),undone)
  scope.cancel()
 }
}
