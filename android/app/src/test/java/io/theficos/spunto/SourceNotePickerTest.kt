package io.theficos.spunto

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class SourceNotePickerTest {
 @Test fun connectionWorkflowStaysOnItsExistingForm() {
  val connect=buildJsonObject {put("action","connect")}
  assertFalse(opensSourcePicker("thinking-desk","sources",connect))
  assertTrue(opensSourcePicker("thinking-desk","sources",buildJsonObject {}))
  assertTrue(opensSourcePicker("context-packs","sources",buildJsonObject {}))
  assertFalse(opensSourcePicker("links","sources",buildJsonObject {}))
 }

 @Test fun chooserUsesTheReturnedRevisionAndDoesNotEchoPickerMetadata() {
  val initial=buildJsonObject {put("pack_id","record");put("expected_revision",1);put("picker",true)}
  val components=listOf(Json.parseToJsonElement("""
    {"type":"form","schema":{"properties":{"selected_ids":{"default":["a","b"]},
    "expected_revision":{"default":4},"max_sources":{"default":2}}}}
  """).jsonObject)
  val state=checkNotNull(sourcePickerState("context-packs","sources",initial,components))
  assertTrue(state.atLimit)
  assertEquals(setOf("a","b"),state.selectedIds)
  assertEquals(JsonPrimitive(4),state.args["expected_revision"])
  val remove=state.update(buildJsonObject {
   put("remove","a")
   put("selected_ids",JsonArray(emptyList()))
   put("max_sources",30)
   put("expected_revision",1)
  })
  assertEquals(setOf("pack_id","expected_revision","picker","remove"),remove.keys)
  assertEquals(JsonPrimitive(4),remove["expected_revision"])
  assertNull(sourcePickerState("context-packs","sources",JsonObject(initial-"picker"),components))
 }
}
