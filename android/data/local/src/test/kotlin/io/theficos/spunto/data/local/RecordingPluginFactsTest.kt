package io.theficos.spunto.data.local

import kotlinx.serialization.json.*
import org.junit.Test
import org.junit.Assert.*
import java.io.File

class RecordingPluginFactsTest {
 private val catalog=File(System.getProperty("spunto.fixtures")?:"/spec/fixtures","recording-waiting-catalog.json").readText()
 private val empty="{\"plugins\":[]}"
 private fun changed(key:String,value:JsonElement):String {
  val root=Json.parseToJsonElement(catalog).jsonObject
  val row=root.getValue("plugins").jsonArray.single().jsonObject
  return JsonObject(root+ ("plugins" to JsonArray(listOf(JsonObject(row+(key to value)))))).toString()
 }
 @Test fun ownerEnabledWaitingCatalogAllowsCaptureWithoutAnActiveTranscriptionContribution() {
  val facts=recordingPluginFacts(empty,catalog)
  assertTrue(facts.captureAllowed);assertFalse(facts.transcriptionActive)
  val row=Json.parseToJsonElement(catalog).jsonObject.getValue("plugins").jsonArray.single()
  val active=JsonObject(mapOf("plugins" to JsonArray(listOf(row)))).toString()
  assertTrue(recordingPluginFacts(active,changed("state",JsonPrimitive("enabled"))).transcriptionActive)
 }
 @Test fun missingDisabledFailedUnknownOrUngrantedFactsCannotOpenTheMicrophone() {
  assertFalse(recordingPluginFacts(empty,empty).captureAllowed)
  for(value in listOf("disabled","failed","available","missing","unknown")) assertFalse(value,recordingPluginFacts(empty,changed("state",JsonPrimitive(value))).captureAllowed)
  assertFalse(recordingPluginFacts(empty,changed("switch",JsonPrimitive("off"))).captureAllowed)
  assertFalse(recordingPluginFacts(empty,changed("granted",JsonArray(listOf(JsonPrimitive("attachments.write"))))).captureAllowed)
  assertFalse(recordingPluginFacts(empty,changed("permissions",JsonArray(emptyList()))).captureAllowed)
 }
 @Test fun malformedWireTypesDuplicatesAndInvalidCacheFailClosed() {
  for(value in listOf("not JSON","{}","{\"plugins\":true}","{\"plugins\":[null]}")) assertFalse(recordingPluginFacts(empty,value).captureAllowed)
  assertFalse(recordingPluginFacts("bad",catalog).captureAllowed)
  assertFalse(recordingPluginFacts(empty,changed("granted",JsonArray(listOf(JsonPrimitive(true))))).captureAllowed)
  assertFalse(recordingPluginFacts(empty,changed("switch",JsonPrimitive(true))).captureAllowed)
  val row=Json.parseToJsonElement(catalog).jsonObject.getValue("plugins").jsonArray.single()
  assertFalse(recordingPluginFacts(empty,JsonObject(mapOf("plugins" to JsonArray(listOf(row,row)))).toString()).captureAllowed)
 }
}
