package io.theficos.spunto.data.sync
import kotlinx.serialization.json.*
import org.junit.Test
import org.junit.Assert.*
import java.io.File
class GoldenContractTest {
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
