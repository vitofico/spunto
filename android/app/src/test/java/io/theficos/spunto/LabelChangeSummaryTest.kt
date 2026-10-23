package io.theficos.spunto

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Test

@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(application=android.app.Application::class,sdk=[28])
class LabelChangeSummaryTest {
 @Test fun removalDoesNotMasqueradeAsRenameToNull() {
  assertEquals("Remove #Old from affected notes, including nested labels.",labelChangeSummary(settingsTestResources(),Json.parseToJsonElement("""{"old":"Old","new":null}""").jsonObject))
 }
 @Test fun renameAndClusterKeepTheirExistingMeaning() {
  assertEquals("Rename or merge #Old into #New.",labelChangeSummary(settingsTestResources(),Json.parseToJsonElement("""{"old":"Old","new":"New"}""").jsonObject))
  assertEquals("Add #New to 2 selected unlabeled notes.",labelChangeSummary(settingsTestResources(),Json.parseToJsonElement("""{"new":"New","note_ids":["a","b"]}""").jsonObject))
 }
}
