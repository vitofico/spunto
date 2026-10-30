package io.theficos.spunto

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class AskConversationTest {
 private fun output(vararg components:JsonObject)=buildJsonObject {put("ui",1);put("items",JsonArray(components.toList()))}
 private fun command(turn:Int)=buildJsonObject {put("type","command");put("id","ask");put("title","Ask a follow-up");put("args",buildJsonObject {put("conversation_id","opaque");put("after_turn",turn)})}
 @Test fun continuationSurvivesTheGenericCommandEnvelope() {
  val response=commandEnvelope("ask","ask",buildJsonObject {put("question","First question?")},output(command(2)))
  val next=askContinuation(response)!!
  assertEquals("opaque",next["conversation_id"]!!.jsonPrimitive.content)
  assertEquals(2,next["after_turn"]!!.jsonPrimitive.int)
 }
 @Test fun malformedContinuationIsNotAdopted() {
  assertNull(askContinuation(output(command(-1))))
  assertNull(askContinuation(output(JsonObject(command(1)+("unexpected" to JsonPrimitive(true))))))
  assertNull(askContinuation(output(buildJsonObject {put("type","text");put("text","Legacy one-shot answer")})))
 }
 @Test fun textOnlySaveRefusalIsNotAConfirmation() {
  val refusal=commandEnvelope("ask","save-answer",buildJsonObject {put("answer_id","expired")},output(buildJsonObject {put("type","text");put("text","This answer expired. Ask again before saving.")}))
  assertTrue("A save succeeds only with a returned note reference",askSources(refusal).isEmpty())
  val saved=commandEnvelope("ask","save-answer",buildJsonObject {put("answer_id","valid")},output(buildJsonObject {put("type","notes");put("ids",JsonArray(listOf(JsonPrimitive("saved-note"))))}))
  assertEquals(listOf("saved-note"),askSources(saved))
 }
 @Test fun sourceOrderPreservesCitationNumbers() {
  val response=output(buildJsonObject {put("type","notes");put("ids",JsonArray(listOf(JsonPrimitive("first"),buildJsonObject {put("id","second");put("reason","Supporting evidence")})))})
  assertEquals(listOf("first","second"),askSources(response))
 }

 private fun manifest(plugin:String="ask",conversation:Boolean=false,version:String="1.0.0")=buildJsonObject {
  put("ui",1);put("plugins",JsonArray(listOf(buildJsonObject {
   put("id",plugin);put("version",version);put("contributes",buildJsonObject {put("commands",JsonArray((listOf("ask","save-answer")+if(conversation) listOf("clear-conversation") else emptyList()).map {id ->buildJsonObject {put("id",id)}}))})
  })))
 }
 @Test fun commandAdvertisementDistinguishesOldAndNewServersWithTheSameVersion() {
  assertFalse(supportsAskConversation(manifest()))
  assertTrue(supportsAskConversation(manifest(conversation=true)))
  assertFalse("A version string does not advertise a protocol",supportsAskConversation(manifest(version="99.0.0")))
 }
 @Test fun anotherPluginsClearCommandAndAMissingManifestDoNotAdvertiseAskHistory() {
  assertFalse(supportsAskConversation(manifest(plugin="another-plugin",conversation=true)))
  assertFalse(supportsAskConversation(JsonObject(emptyMap())))
 }
}
