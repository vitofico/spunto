package io.theficos.spunto

import io.theficos.spunto.data.sync.ApiException
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
   {"id":"off","enabled":false,"contributes":{"commands":[{"id":"off","where":["note-actions"]}]}},
   {"id":"waiting","state":"waiting","contributes":{"commands":[{"id":"waiting","where":["note-actions"]}]}}
  ]""").jsonArray.map {it.jsonObject}
  val tools=noteTools(settingsTestResources(),plugins)
  assertEquals(listOf("Tidy text","Suggest labels"),tools.map {it.title})
  assertEquals(2,tools.size)
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
