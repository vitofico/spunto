package io.theficos.spunto

import io.theficos.spunto.data.sync.SyncStatus
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class StatusPresentationTest {
 @Test fun syncSummariesExplainRecoveryAndPreserveOfflineWork() {
  assertEquals("Saved on this phone",syncStatusTitle(SyncStatus.SignedOut,3))
  assertEquals("Everything is up to date",syncStatusTitle(SyncStatus.Complete(0),0))
  assertTrue(syncStatusDescription(SyncStatus.Waiting("different_identity"),2,true).contains("original account"))
  assertTrue(syncStatusDescription(SyncStatus.Waiting("connection_failed"),2,false).contains("saved here"))
  assertFalse(syncStatusDescription(SyncStatus.Waiting("sensitive_unknown_code"),2,true).contains("sensitive_unknown_code"))
 }
 @Test fun healthForPreviousModelNeverClaimsCurrentModelPassed() {
  val ai=Json.parseToJsonElement("""{"profiles":[{"name":"local","chat_model":"new-model","base_url":"http://model.test/v1","leaves_network":{"chat":false}}],"bindings":{"chat":"local"},"health":[{"capability":"chat","profile":"local","model":"old-model","ok":true}]}""").jsonObject
  val chat=aiCapabilityCards(ai).first()
  assertEquals("new-model",chat.model);assertEquals("Not checked yet",chat.state);assertNull(chat.healthy)
  assertEquals("model.test · on your network",chat.destination)
  assertEquals("Not configured",aiCapabilityCards(ai).last().state)
 }
 @Test fun indexingShowsProgressWithoutRawPayload() {
  val ai=Json.parseToJsonElement("""{"indexing":{"enabled":true,"available":true,"routes":[{"indexed":4,"total":9,"target_key":"internal-key"}]}}""").jsonObject
  val text=aiIndexDescription(ai)
  assertTrue(text.startsWith("4 of 9"));assertFalse(text.contains("internal-key"))
 }
}
