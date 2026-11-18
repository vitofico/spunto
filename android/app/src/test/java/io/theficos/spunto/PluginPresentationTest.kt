package io.theficos.spunto

import io.theficos.spunto.data.sync.ApiException
import io.theficos.spunto.core.model.Note
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(application=android.app.Application::class,sdk=[28])
class PluginPresentationTest {
 @Test fun toolsListEachActionOnceWithoutAppendingThePluginName() {
  val plugins=Json.parseToJsonElement("""[
   {"id":"label-suggestions","name":"Label suggestions","state":"on","position":2,"contributes":{"commands":[{"id":"suggest-now","title":"Suggest labels","where":["note-actions","slash"]},{"id":"suggest-now","title":"Suggest labels","where":["note-actions"]}]}},
   {"id":"tidy","name":"Tidy text","state":"on","position":1,"contributes":{"commands":[{"id":"tidy","title":"Tidy text","where":["note-actions"]}]}},
   {"id":"links","state":"on","position":10,"contributes":{"commands":[{"id":"link","title":"Link to note","where":["note-actions"]}]}},
   {"id":"off","enabled":false,"contributes":{"commands":[{"id":"off","where":["note-actions"]}]}},
   {"id":"waiting","state":"waiting","contributes":{"commands":[{"id":"waiting","where":["note-actions"]}]}}
  ]""").jsonArray.map {it.jsonObject}
  val tools=noteTools(settingsTestResources(),plugins)
  assertEquals(listOf("Link to note","Tidy text","Suggest labels"),tools.map {it.title})
 }
 @Test fun tidyCommandsRequireTheDisplayedSuggestionAndExactRevisionWithAndWithoutInlineDiff() {
  val proposal=Json.parseToJsonElement("""{"id":"suggestion","plugin":"tidy","status":"pending","action":"replace_body","note_id":"note","note_version":7}""").jsonObject
  val pill=Json.parseToJsonElement("""{"type":"pill","action":"accept","label":"Apply changes","suggestion_id":"suggestion"}""").jsonObject
  val args=buildJsonObject {put("note_id","note")}
  val diff=Json.parseToJsonElement("""{"type":"diff","base_version":7,"hunks":[{"old_start":1,"new_start":1,"old_lines":["Before"],"new_lines":["After"]}]}""").jsonObject
  val ordinary=commandEnvelope("tidy","tidy",args,buildJsonObject {put("ui",1);put("items",JsonArray(listOf(diff,pill)))})
  val fallback=commandEnvelope("tidy","tidy",args,pill)
  val note=Note("note","Before",serverRev=7)
  assertTrue(commandReviewMatches(ordinary,proposal,note,diff))
  assertTrue(commandReviewMatches(fallback,proposal,note.copy(body="a".repeat(4001)),null))
  assertFalse(commandReviewMatches(ordinary,proposal,note,JsonObject(diff+("base_version" to JsonPrimitive(8)))))
  assertFalse(commandReviewMatches(fallback,proposal,note.copy(serverRev=8),null))
  assertFalse(commandReviewMatches(fallback,JsonObject(proposal+("plugin" to JsonPrimitive("other"))),note,null))
  assertFalse(commandReviewMatches(fallback,proposal,note.copy(id="other"),null))
 }
 @Test fun failuresSayWhatTheServerSaysAndOnlyAProxysBareReplyGetsAStatusLine() {
  val said="Choose a model for this capability in Settings → AI."
  assertEquals(said,apiErrorMessage(settingsTestResources(),ApiException(503,"ai_capability_unbound",said)))
  assertEquals("This plugin call is not permitted.",apiErrorMessage(settingsTestResources(),ApiException(403,"plugin_call_denied","This plugin call is not permitted.")))
  assertEquals("The server took too long. Try again.",apiErrorMessage(settingsTestResources(),ApiException(504,"request_failed")))
  assertEquals("Your session expired. Sign in again, then retry.",apiErrorMessage(settingsTestResources(),ApiException(401,"request_failed")))
  assertEquals("The server couldn't finish this. Try again.",apiErrorMessage(settingsTestResources(),ApiException(502,"request_failed")))
 }
 @Test fun privateNotesKeepLocalToolsAndExcludePluginsWithEgress() {
  val plugins=Json.parseToJsonElement("""[
   {"id":"links","permissions":["notes.read","suggest"],"contributes":{"commands":[{"id":"link","title":"Link to note","where":["note-actions"]}]}},
   {"id":"tidy","permissions":["notes.read","ai.chat"],"contributes":{"commands":[{"id":"tidy","title":"Tidy text","where":["note-actions"]}]}},
   {"id":"network","permissions":["notes.read","net.fetch"],"contributes":{"commands":[{"id":"fetch","title":"Fetch","where":["note-actions"]}]}},
   {"id":"unknown","contributes":{"commands":[{"id":"unknown","title":"Unknown","where":["note-actions"]}]}}
  ]""").jsonArray.map {it.jsonObject}
  assertEquals(listOf("Link to note"),noteTools(settingsTestResources(),plugins,privateNote=true).map {it.title})
 }
}
