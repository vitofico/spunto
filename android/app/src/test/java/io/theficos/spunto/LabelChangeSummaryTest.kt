package io.theficos.spunto

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Test

class LabelChangeSummaryTest {
 @Test fun removalDoesNotMasqueradeAsRenameToNull() {
  assertEquals("Remove #Old from affected notes, including nested labels.",labelChangeSummary(Json.parseToJsonElement("""{"old":"Old","new":null}""").jsonObject))
 }
 @Test fun renameAndClusterKeepTheirExistingMeaning() {
  assertEquals("Rename or merge #Old into #New.",labelChangeSummary(Json.parseToJsonElement("""{"old":"Old","new":"New"}""").jsonObject))
  assertEquals("Add #New to 2 selected unlabeled notes.",labelChangeSummary(Json.parseToJsonElement("""{"new":"New","note_ids":["a","b"]}""").jsonObject))
 }
}
