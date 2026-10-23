package io.theficos.spunto

import io.theficos.spunto.data.sync.SyncStatus
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(application=android.app.Application::class,sdk=[28])
class StatusPresentationTest {
 @Test fun syncSummariesExplainRecoveryAndPreserveOfflineWork() {
  assertTrue(syncSummary(settingsTestResources(),SyncStatus.SignedOut,3,false,null).contains("keep writing offline"))
  assertEquals("Up to date",syncSummary(settingsTestResources(),SyncStatus.Complete(0),0,true,null))
  assertEquals("1 change waiting",syncSummary(settingsTestResources(),SyncStatus.Idle,1,true,null))
  assertTrue(syncSummary(settingsTestResources(),SyncStatus.Complete(2),2,true,0L).startsWith("2 changes waiting · last synced "))
  assertTrue(syncSummary(settingsTestResources(),SyncStatus.Waiting("different_identity"),2,true,null).contains("original account"))
  assertTrue(syncSummary(settingsTestResources(),SyncStatus.Waiting("connection_failed"),2,false,null).contains("saved here"))
  assertFalse(syncSummary(settingsTestResources(),SyncStatus.Waiting("sensitive_unknown_code"),2,true,null).contains("sensitive_unknown_code"))
 }
 @Test fun signingOutSaysWhereThePhoneStaysListed() {
  assertTrue(signOutText(settingsTestResources(),0,online=false).endsWith("This phone stays listed under Devices in the web app until you remove it there."))
  assertEquals("Your notes stay on this phone. This phone also leaves Devices in the web app.",signOutText(settingsTestResources(),0,online=true))
  assertTrue(signOutText(settingsTestResources(),1,online=true).startsWith("1 change hasn't synced yet. It stays on this phone"))
  assertTrue(signOutText(settingsTestResources(),3,online=false).startsWith("3 changes haven't synced yet. They stay"))
 }
 @Test fun healthForPreviousModelNeverClaimsCurrentModelPassed() {
  val ai=Json.parseToJsonElement("""{"profiles":[{"name":"local","chat_model":"new-model","base_url":"http://model.test/v1","leaves_network":{"chat":false}}],"bindings":{"chat":"local"},"health":[{"capability":"chat","profile":"local","model":"old-model","ok":true}]}""").jsonObject
  val chat=aiCapabilityCards(settingsTestResources(),ai).first()
  assertEquals("new-model",chat.model);assertEquals("Not checked yet",chat.state);assertNull(chat.healthy)
  assertEquals("model.test · on your network",chat.destination)
  assertEquals("Not configured",aiCapabilityCards(settingsTestResources(),ai).last().state)
  assertEquals("new-model · model.test · on your network",aiCardSummary(settingsTestResources(),chat))
  assertEquals("No model selected",aiCardSummary(settingsTestResources(),aiCapabilityCards(settingsTestResources(),ai).last()))
 }
 @Test fun indexingShowsProgressWithoutRawPayload() {
  val ai=Json.parseToJsonElement("""{"indexing":{"enabled":true,"available":true,"routes":[{"indexed":4,"total":9,"target_key":"internal-key"}]}}""").jsonObject
  val text=aiIndexDescription(settingsTestResources(),ai)
  assertTrue(text.startsWith("4 of 9"));assertFalse(text.contains("internal-key"))
 }
}
