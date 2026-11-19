package io.theficos.spunto.data.sync
import kotlinx.serialization.json.*
import org.junit.Test
import org.junit.Assert.*
import java.io.File
internal val hostedSyncGolden by lazy {
    Json.parseToJsonElement(
        File(System.getProperty("spunto.fixtures"), "sync-api-hosted.json").readText(),
    ).jsonObject
}

class GoldenContractTest {
 @Test fun sharedHostedResponsesDecode() {
  val editor = hostedSyncGolden.getValue("editor_meta").jsonObject
  val viewer = hostedSyncGolden.getValue("viewer_meta").jsonObject
  val wait = hostedSyncGolden.getValue("viewer_wait").jsonObject
  val reconcile = Json.decodeFromJsonElement<ReconcileResponse>(hostedSyncGolden.getValue("viewer_reconcile"))
  val rejected = hostedSyncGolden.getValue("read_only_push").jsonObject

  assertEquals("editor", editor.getValue("workspace_role").jsonPrimitive.content)
  assertEquals("viewer", viewer.getValue("workspace_role").jsonPrimitive.content)
  assertEquals(viewer.getValue("sync_epoch").jsonPrimitive.content + ".0", wait.getValue("cursor").jsonPrimitive.content)
  assertFalse(wait.getValue("changed").jsonPrimitive.boolean)
  assertEquals(viewer["workspace_role"], wait["workspace_role"])
  assertEquals("viewer", reconcile.workspaceRole)
  assertEquals(viewer.getValue("sync_epoch").jsonPrimitive.content, reconcile.epoch)
  assertTrue(reconcile.restoreIds.isEmpty())
  assertEquals("read_only_space", rejected.getValue("code").jsonPrimitive.content)
 }

 @get:org.junit.Rule val timeout = org.junit.rules.Timeout(10, java.util.concurrent.TimeUnit.MINUTES)
 @Test fun allSharedRealServerDtosDeserialize() {
  val golden=Json.parseToJsonElement(File(System.getProperty("spunto.fixtures"),"sync-api.json").readText()).jsonObject
  val json=Json{ignoreUnknownKeys=true};val statuses=mutableSetOf<String>();val kinds=mutableSetOf<String>()
  for((name,value) in golden) when {
   name.startsWith("push") && name.endsWith("response") -> {val response=json.decodeFromJsonElement<PushResponse>(value);assertTrue(response.epoch.isNotEmpty());response.results.forEach {result->statuses+=result.status;result.note?.let {assertTrue(noteFromWire(it).bodyHash.isNotEmpty())}}}
   name.startsWith("pull") && name.endsWith("response") -> {val response=json.decodeFromJsonElement<PullResponse>(value);response.items.forEach {item->kinds+=item.kind;assertNotNull(item.data["id"]);if(item.kind=="note") assertTrue(noteFromWire(item.data).bodyHash.isNotEmpty())}}
   name=="reconcile_response" -> assertTrue(json.decodeFromJsonElement<ReconcileResponse>(value).epoch.isNotEmpty())
  }
  assertEquals(setOf("applied","duplicate","merged","conflict","rejected"),statuses)
  assertEquals(setOf("note","suggestion","annotation","tombstone"),kinds)
 }
}
