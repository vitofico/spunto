package io.theficos.spunto.core.plugins

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ComponentResultTest {
 private fun json(raw:String)=Json.parseToJsonElement(raw)
 @Test fun returnedCommandDefaultsReplaceOldRevisionAndBodyButSettingsKeepSavedValues() {
  val schema=json("""{"type":"object","properties":{"revision":{"type":"integer","default":3},"body":{"type":"string","default":"Fresh server text"},"note_id":{"type":"string"}}}""").jsonObject
  val initial=buildJsonObject { put("revision",2);put("body","Previous form text");put("note_id","note-a") }
  val command=formInitialValues(schema,initial,preferDefaults=true)
  assertEquals(JsonPrimitive(3),command["revision"]);assertEquals("Fresh server text",command.string("body"));assertEquals("note-a",command.string("note_id"))
  assertEquals(setOf("revision"),commandFormHiddenFields(schema))
  assertEquals(initial,formInitialValues(schema,initial))
 }
 @Test fun bareAndStoredArraysRenderWithoutLosingItems() {
  val result=json("""[{"type":"text","text":"Source"},{"type":"notes","ids":["note-a"]}]""")
  assertEquals(2,outputComponents(result).size)
  assertEquals(2,outputComponents(buildJsonObject { put("payload",result);put("plugin","link-enrichment") }).size)
  assertEquals(2,outputComponents(buildJsonObject { put("ui",1);put("items",result) }).size)
 }
 @Test fun invalidWrapperAndOverBudgetOutputFailClosed() {
  assertTrue(outputComponents(json("""{"ui":2,"items":[{"type":"text","text":"Hidden"}]}""")).isEmpty())
  assertTrue(outputComponents(JsonArray(List(11) { json("""{"type":"text","text":"Hidden"}""") })).isEmpty())
  assertEquals("Safe",outputComponents(json("""[{"type":"future","fallback":{"type":"text","text":"Safe"}}]""")).single().string("text"))
 }
 @Test fun translationFormKeepsNoteContextAndRequiresLanguage() {
  val schema=json("""{"type":"object","required":["note_id","language"],"properties":{"note_id":{"type":"string","default":"note-a"},"language":{"type":"string","enum":["it","en"]}}}""").jsonObject
  assertTrue(runCatching { formArguments(schema,JsonObject(emptyMap())) }.isFailure)
  val result=formArguments(schema,buildJsonObject { put("language","it") })
  assertEquals("note-a",result.string("note_id"));assertEquals("it",result.string("language"))
 }
 @Test fun numericInputDoesNotSilentlyChangeInvalidValues() {
  val schema=json("""{"type":"object","properties":{"count":{"type":"integer","minimum":1,"maximum":3},"ratio":{"type":"number"}}}""").jsonObject
  listOf("-","2.5","0","4").forEach { input -> assertTrue(runCatching { formArguments(schema,buildJsonObject { put("count",input) }) }.isFailure) }
  assertTrue(runCatching { formArguments(schema,buildJsonObject { put("ratio","NaN") }) }.isFailure)
  assertEquals(JsonPrimitive(2),formArguments(schema,buildJsonObject { put("count","2") })["count"])
 }
 @Test fun listsNormalizeOnlyStringItemsAndSecretsNeverLeaveForm() {
  val schema=json("""{"type":"object","properties":{"labels":{"type":"array","items":{"type":"string"}},"token":{"type":"string","x-secret":true}}}""").jsonObject
  val result=formArguments(schema,buildJsonObject { put("labels"," Ideas, , work , Ideas ");put("token","hidden") })
  assertEquals(json("""["Ideas","work"]"""),result["labels"]);assertFalse(result.containsKey("token"))
  assertTrue(runCatching { formArguments(schema,buildJsonObject { put("labels",json("[1]")) }) }.isFailure)
 }
 @Test fun booleansTimesAndLengthsMatchTheWireTypes() {
  val schema=json("""{"type":"object","properties":{"on":{"type":"boolean"},"at":{"type":"string","format":"time"},"name":{"type":"string","maxLength":1}}}""").jsonObject
  assertTrue(runCatching { formArguments(schema,buildJsonObject { put("on","true") }) }.isFailure)
  assertTrue(runCatching { formArguments(schema,buildJsonObject { put("at","24:00") }) }.isFailure)
  assertEquals("😀",formArguments(schema,buildJsonObject { put("name","😀") }).string("name"))
 }
}
