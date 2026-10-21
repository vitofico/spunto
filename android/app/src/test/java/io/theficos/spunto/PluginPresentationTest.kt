package io.theficos.spunto

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class PluginPresentationTest {
 @Test fun toolsListEachActionOnceWithoutAppendingThePluginName() {
  val plugins=Json.parseToJsonElement("""[
   {"id":"label-suggestions","name":"Label suggestions","state":"on","position":2,"contributes":{"commands":[{"id":"suggest-now","title":"Suggest labels","where":["note-actions","slash"]},{"id":"suggest-now","title":"Suggest labels","where":["note-actions"]}]}},
   {"id":"tidy","name":"Tidy text","state":"on","position":1,"contributes":{"commands":[{"id":"tidy","title":"Tidy text","where":["note-actions"]}]}},
   {"id":"off","enabled":false,"contributes":{"commands":[{"id":"off","where":["note-actions"]}]}},
   {"id":"waiting","state":"waiting","contributes":{"commands":[{"id":"waiting","where":["note-actions"]}]}}
  ]""").jsonArray.map {it.jsonObject}
  val tools=noteTools(plugins)
  assertEquals(listOf("Tidy text","Suggest labels"),tools.map {it.title})
  assertEquals(2,tools.size)
 }
 @Test fun actionableFailuresNameTheNextStepWithoutProviderOutput() {
  assertTrue(pluginCommandErrorMessage(503,"ai_capability_unbound").contains("Settings → AI"))
  assertTrue(pluginCommandErrorMessage(502,"ai_provider_timeout").contains("faster model"))
  assertTrue(pluginCommandErrorMessage(409,"stale_note").contains("Sync"))
  assertTrue(pluginCommandErrorMessage(401,"session_expired").contains("Sign in"))
 }
 @Test fun privateNotesKeepLocalToolsAndExcludePluginsWithEgress() {
  val plugins=Json.parseToJsonElement("""[
   {"id":"links","permissions":["notes.read","suggest"],"contributes":{"commands":[{"id":"link","title":"Link to note","where":["note-actions"]}]}},
   {"id":"tidy","permissions":["notes.read","ai.chat"],"contributes":{"commands":[{"id":"tidy","title":"Tidy text","where":["note-actions"]}]}},
   {"id":"network","permissions":["notes.read","net.fetch"],"contributes":{"commands":[{"id":"fetch","title":"Fetch","where":["note-actions"]}]}},
   {"id":"unknown","contributes":{"commands":[{"id":"unknown","title":"Unknown","where":["note-actions"]}]}}
  ]""").jsonArray.map {it.jsonObject}
  assertEquals(listOf("Link to note"),noteTools(plugins,privateNote=true).map {it.title})
 }
}
