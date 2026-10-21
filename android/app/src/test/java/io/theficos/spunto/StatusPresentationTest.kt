package io.theficos.spunto

import io.theficos.spunto.data.sync.SyncStatus
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class StatusPresentationTest {
 @Test fun syncSummariesExplainRecoveryAndPreserveOfflineWork() {
  assertTrue(syncSummary(SyncStatus.SignedOut,3,false,null).contains("keep writing offline"))
  assertEquals("Up to date",syncSummary(SyncStatus.Complete(0),0,true,null))
  assertEquals("1 change waiting",syncSummary(SyncStatus.Idle,1,true,null))
  assertTrue(syncSummary(SyncStatus.Complete(2),2,true,0L).startsWith("2 changes waiting · last synced "))
  assertTrue(syncSummary(SyncStatus.Waiting("different_identity"),2,true,null).contains("original account"))
  assertTrue(syncSummary(SyncStatus.Waiting("connection_failed"),2,false,null).contains("saved here"))
  assertFalse(syncSummary(SyncStatus.Waiting("sensitive_unknown_code"),2,true,null).contains("sensitive_unknown_code"))
 }
 @Test fun signingOutSaysWhereThePhoneStaysListed() {
  assertTrue(signOutText(0).endsWith("This phone stays listed under Devices in the web app until you remove it there."))
  assertTrue(signOutText(1).startsWith("1 change hasn't synced yet. It stays on this phone"))
  assertTrue(signOutText(3).startsWith("3 changes haven't synced yet. They stay"))
 }
 @Test fun healthForPreviousModelNeverClaimsCurrentModelPassed() {
  val ai=Json.parseToJsonElement("""{"profiles":[{"name":"local","chat_model":"new-model","base_url":"http://model.test/v1","leaves_network":{"chat":false}}],"bindings":{"chat":"local"},"health":[{"capability":"chat","profile":"local","model":"old-model","ok":true}]}""").jsonObject
  val chat=aiCapabilityCards(ai).first()
  assertEquals("new-model",chat.model);assertEquals("Not checked yet",chat.state);assertNull(chat.healthy)
  assertEquals("model.test · on your network",chat.destination)
  assertEquals("Not configured",aiCapabilityCards(ai).last().state)
  assertEquals("new-model · model.test · on your network",aiCardSummary(chat))
  assertEquals("No model selected",aiCardSummary(aiCapabilityCards(ai).last()))
 }
 @Test fun indexingShowsProgressWithoutRawPayload() {
  val ai=Json.parseToJsonElement("""{"indexing":{"enabled":true,"available":true,"routes":[{"indexed":4,"total":9,"target_key":"internal-key"}]}}""").jsonObject
  val text=aiIndexDescription(ai)
  assertTrue(text.startsWith("4 of 9"));assertFalse(text.contains("internal-key"))
 }
}
