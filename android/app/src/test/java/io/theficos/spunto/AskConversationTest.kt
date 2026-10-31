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

 private fun manifest(plugin:String="ask",conversation:Boolean=false,version:String="1.0.0",state:String="enabled")=buildJsonObject {
  put("ui",1);put("plugins",JsonArray(listOf(buildJsonObject {
   put("id",plugin);put("version",version);put("state",state);put("contributes",buildJsonObject {put("commands",JsonArray((listOf("ask","save-answer")+if(conversation) listOf("clear-conversation") else emptyList()).map {id ->buildJsonObject {put("id",id)}}))})
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

 private fun writeCommand(stage:String,id:String="action")=buildJsonObject {put("type","command");put("id","save-answer");put("title",if(stage=="preview") "Apply change" else if(stage=="undo") "Undo" else "Undo complete");put("args",buildJsonObject {put("action_id",id);put("stage",stage)})}
 private fun preview()=output(buildJsonObject {put("type","markdown");put("text","Target: Plan\nBefore: Old\nAfter: New")},writeCommand("preview"))
 @Test fun reviewedActionsNeedAVisiblePreviewAndNeverUseTheLegacySavePath() {
  assertEquals("preview",askWriteAction(preview())!!.stage)
  assertNull(askWriteAction(output(writeCommand("preview"))))
  assertNull(askAnswerSave(preview()))
  assertEquals("legacy",askAnswerSave(output(buildJsonObject {put("type","command");put("id","save-answer");put("title","Save as note");put("args",buildJsonObject {put("answer_id","legacy")})}))!!.get("answer_id")!!.jsonPrimitive.content)
 }
 @Test fun notesAloneAndUnchangedOrForeignStagesAreNotWriteAcknowledgements() {
  val requested=askWriteAction(preview())!!
  assertNull(askWriteAcknowledgement(requested,output(buildJsonObject {put("type","notes");put("ids",JsonArray(listOf(JsonPrimitive("target"))))})))
  assertNull(askWriteAcknowledgement(requested,preview()))
  assertNull(askWriteAcknowledgement(requested,output(writeCommand("undo","foreign"))))
  val applied=askWriteAcknowledgement(requested,output(writeCommand("undo")))!!
  assertEquals("undo",applied.stage)
  assertNull(askWriteAcknowledgement(applied,output(writeCommand("undo"))))
  assertEquals("done",askWriteAcknowledgement(applied,output(writeCommand("done")))!!.stage)
  assertEquals("done",askWriteAcknowledgement(requested,output(writeCommand("done")))!!.stage)
 }
 @Test fun actionArgumentsCannotSmuggleBodiesOrMixedLegacyReceipts() {
  val markdown=buildJsonObject {put("type","markdown");put("text","Preview")}
  for(extra in listOf("body","answer_id","target_id","version")) {
   val command=writeCommand("preview")
   val args=command["args"]!!.jsonObject
   assertNull(askWriteAction(output(markdown,JsonObject(command+("args" to JsonObject(args+(extra to JsonPrimitive("untrusted"))))))))
  }
  assertNull(askWriteAction(output(markdown,writeCommand("unknown"))))
 }
 @Test fun reviewedActionsOptInOnlyForEnabledReleasedCapableManifests() {
  assertFalse(supportsAskNoteActions(manifest(conversation=true)))
  assertTrue(supportsAskNoteActions(manifest(conversation=true,version="1.1.0")))
  assertFalse(supportsAskNoteActions(manifest(conversation=true,version="1.1.0",state="waiting")))
  assertFalse(supportsAskNoteActions(manifest(conversation=true,version="1.1.0-alpha")))
  assertFalse(supportsAskNoteActions(manifest(conversation=false,version="1.1.0")))
 }

 @Test fun retainedReceiptStateDoesNotResetOrDowngradeAfterCompositionLeaves() {
  val state=AskWriteState(askWriteAction(preview())!!)
  assertTrue(state.accept(state.action,output(writeCommand("undo"))))
  assertEquals("undo",state.action.stage)
  assertFalse(state.accept(state.action,preview()))
  assertEquals("undo",state.action.stage)
  assertTrue(state.accept(state.action,output(writeCommand("done"))))
  assertEquals("done",state.action.stage)
  assertFalse(state.accept(state.action,output(writeCommand("undo"))))
  assertEquals("done",state.action.stage)
 }
 @Test fun reviewedPreviewKeepsAuthoritativeTargetAndAllSplitMarkdownInOrder() {
  fun item(type:String,text:String)=buildJsonObject {put("type",type);put("text",text)}
  val target=item("text","Target note: Meeting")
  val before=item("markdown","Before:\n\n```\nOriginal text\n```")
  val after=item("markdown","After:\n\n```\n"+"First section ".repeat(250)+"\n```")
  val tail=item("markdown","After:\n\n```\nFinal section must stay visible\n```")
  val additional=item("text","Review the exact change below.")
  val data=output(item("text","Preview only. Nothing changes until you confirm."),target,before,after,tail,additional,writeCommand("preview"))
  assertEquals(listOf(target,before,after,tail,additional),askWritePreview(data)["items"]!!.jsonArray.toList())
 }
 @Test fun createdOperationSurvivesUndoReceiptTitles() {
  val initial=AskWriteAction("Create note",buildJsonObject {put("action_id","action");put("stage","preview")})
  val state=AskWriteState(initial)
  assertTrue(state.created)
  assertTrue(state.accept(initial,output(writeCommand("undo"))))
  assertTrue(state.created)
  assertTrue(state.accept(state.action,output(writeCommand("done"))))
  assertTrue(state.created)
 }

 @Test fun reviewedWritesAreAdvertisedOnlyForWritableWorkspaceRolesOrLegacyLocalMode() {
  val capable=manifest(conversation=true,version="1.1.0")
  for(role in listOf(null,"owner","editor")) assertTrue("Writable role $role",supportsAskNoteActions(capable,role))
  for(role in listOf("viewer","unknown")) assertFalse("Read-only or unknown role $role",supportsAskNoteActions(capable,role))
  for(role in listOf(null,"owner","editor","viewer")) assertFalse("Old server never supports reviewed writes",supportsAskNoteActions(manifest(conversation=true),role))
 }

}
