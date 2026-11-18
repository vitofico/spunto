package io.theficos.spunto

import io.theficos.spunto.data.sync.ApiException
import io.theficos.spunto.core.model.Note
import io.theficos.spunto.core.plugins.formArguments
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
 @Test fun decisionCaptureAndReviewRequireAnEnabledPluginAndWritableOwner() {
  val plugin=Json.parseToJsonElement("""{"id":"decision-journal","enabled":true,"origin":"bundled","contributes":{"commands":[
   {"id":"record-decision","where":["palette","note-actions"]},{"id":"review-decision","where":["note-actions"]},{"id":"journal","where":["palette"]}
  ]}}""").jsonObject
  fun manifest(row:JsonObject)=buildJsonObject {put("plugins",JsonArray(listOf(row)))}
  assertEquals(setOf("record-decision","review-decision","journal"),decisionActionIds(manifest(plugin),owner=true,online=true))
  assertTrue(decisionActionIds(manifest(plugin),owner=true,online=false).isEmpty())
  assertTrue(decisionActionIds(manifest(plugin),owner=false,online=true).isEmpty())
  assertTrue(decisionActionIds(manifest(JsonObject(plugin+("enabled" to JsonPrimitive(false)))),owner=true,online=true).isEmpty())
  assertEquals(listOf("record-decision"),noteTools(settingsTestResources(),listOf(plugin)).map {it.id})
  assertTrue(noteTools(settingsTestResources(),listOf(plugin),allowDecisionJournal=false).isEmpty())
  val ordinary=Note("note","Owner's decision words")
  val journal=ordinary.copy(sourceKind="plugin",sourceRef="decision-journal")
  assertFalse(isJournalNote(ordinary))
  assertTrue(isJournalNote(journal))
  assertFalse(isJournalNote(journal.copy(trashed=true)))
  assertFalse(isJournalNote(journal.copy(conflictOf="other-note")))
 }
 @Test fun toolsExposePrimaryGlobalCommandsAndLeaveJournalInItsOwnViews() {
  fun tool(id:String,command:String,state:String="enabled")=Json.parseToJsonElement("""{
    "id":"$id","switch":"on","state":"$state","contributes":{"commands":[
     {"id":"internal","where":[]},{"id":"$command","where":["palette"]},{"id":"$command","where":["palette"]}
    ]}
   }""").jsonObject
  val plugins=listOf(tool("ask","ask"),tool("context-packs","packs"),tool("thinking-desk","desk"),tool("label-gardener","review-now"),
   tool("digest","digest","waiting"),tool("library-stats","summary"),tool("decision-journal","record-decision"))
  val catalog=buildJsonObject {put("plugins",JsonArray(plugins))}
  val manifest=buildJsonObject {put("plugins",JsonArray(plugins.filterNot {it["id"]==JsonPrimitive("library-stats")}))}
  assertEquals(listOf("ask","packs","desk","review-now"),globalTools(catalog,manifest).map {it.second["id"]!!.jsonPrimitive.content})
 }
 @Test fun thinkingDeskConnectionLabelsTranslateTheKindFieldAndKeepWireValues() {
  val italian=settingsTestResources("it")
  val schema=Json.parseToJsonElement("""{"type":"object","properties":{
   "kind":{"type":"string","title":"Collegamento","enum":["builds-on","challenges","replaces"],"default":"builds-on"}
  }}""").jsonObject
  val choices=schema["properties"]!!.jsonObject["kind"]!!.jsonObject["enum"]!!.jsonArray
  assertEquals(listOf("Si basa su","Mette in discussione","Sostituisce"),choices.map {pluginFormChoice(italian,"thinking-desk","kind",it)})
  assertEquals(choices,choices.map {formArguments(schema,buildJsonObject {put("kind",it)})["kind"]})
  assertNull(pluginFormChoice(italian,"thinking-desk","from_note",JsonPrimitive("builds-on")))
 }
 @Test fun journalStatusAndOutcomeDatesUseTheAppLocaleWithoutRewritingOtherText() {
  val de=settingsTestResources("de")
  val due=localizedJournalLabel(de,"Reviewed · Review due 2030-02-01")
  assertTrue(due.startsWith("Geprüft · Prüfung fällig am "))
  assertTrue(due.contains("2030"))
  val outcome=localizedJournalLabel(de,"Reopened · 1 February 2030, 12:30 UTC")
  assertTrue(outcome.startsWith("Wieder geöffnet · "))
  assertTrue(outcome.endsWith("UTC"))
  assertFalse(outcome.contains("February"))
  val unknown="Reviewed since we changed the user-owned note."
  assertEquals(unknown,localizedJournalLabel(de,unknown))
  assertEquals("Open · Review due 2030-02-30",localizedJournalLabel(de,"Open · Review due 2030-02-30"))
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
