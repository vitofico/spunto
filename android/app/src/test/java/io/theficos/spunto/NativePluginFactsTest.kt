package io.theficos.spunto

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

internal fun pluginFixture(id:String="voice-memos",on:Boolean=true,state:String="waiting",permissions:List<String> = listOf("ai.transcribe")):JsonObject=buildJsonObject {
 put("id",id);put("name",id);put("description","Synthetic plugin");put("kind","server");put("category","capture");put("api","1.0");put("version","1.0.0");put("switch",if(on) "on" else "off");put("state",state);put("pinned_by_env",0);put("permissions",JsonArray(permissions.map(::JsonPrimitive)));put("granted",JsonArray(permissions.map(::JsonPrimitive)));put("can_enable",true);put("can_delete_data",true)
}
internal fun pluginCatalogFixture(vararg plugins:JsonObject,revision:Long=3)=buildJsonObject {put("plugins_rev",revision);put("plugins",JsonArray(plugins.toList()))}
class NativePluginFactsTest {
 @Test fun savedHostWaitingAndAuthoredCatalogFixturesKeepOwnerIntentAndStrictPins() {
  val data=javaClass.getResourceAsStream("/native-plugin-catalog-fixtures.json")!!.bufferedReader().use {Json.parseToJsonElement(it.readText()).jsonObject}
  data["fixtures"]!!.jsonArray.forEach {element ->
   val fixture=element.jsonObject;val row=fixture["row"]!!.jsonObject;val expected=fixture["expected"]!!.jsonObject
   val facts=NativePluginFacts(row)
   expected["wantedOn"]?.let {assertEquals(fixture["id"].toString(),it.jsonPrimitive.boolean,facts.wantedOn)}
   expected["permissionReviewRequired"]?.let {assertEquals(it.jsonPrimitive.boolean,facts.permissionReviewRequired)}
   if(expected["lockUnknown"]?.jsonPrimitive?.boolean==true) assertEquals(EnvironmentPin.Unknown,facts.pin)
   if(facts.removed) {assertFalse(facts.canEnable);assertFalse(facts.canDisable);assertFalse(facts.activeContribution)}
   (fixture["settingsPage"] as? JsonObject)?.let {assertFalse(safePluginPage(it).json().toString().contains("SYNTHETIC_SECRET_SENTINEL"))}
  }
 }
 @Test fun wantedWaitingAndFailedCountOnWithoutActiveManifest() {
  val catalog=nativePluginCatalog(pluginCatalogFixture(pluginFixture(),pluginFixture("failed-plugin",state="failed")),pluginCatalogFixture())
  assertTrue(catalog.valid);assertEquals(2,catalog.onCount);assertTrue(catalog.rows.all {it.wantedOn && !it.activeContribution && it.canDisable})
  // The row says the missing model once, in its waiting line; the plugin page still lists every destination.
  val waiting=catalog.rows.first();assertEquals(emptyList<String>(),waiting.facts);assertEquals("Needs a transcription model",pluginWaitingLine(waiting))
  assertEquals(listOf("Transcription: no model is set up"),pluginDestinationFacts(waiting))
  val vision=NativePluginFacts(JsonObject(pluginFixture("image-text",permissions=listOf("ai.chat","ai.vision"))+("ai_destinations" to buildJsonObject {put("chat",buildJsonObject {put("model","qwen3.5");put("host","ollama.home.arpa");put("leaves_network",false)})})))
  assertEquals("Needs a vision model",pluginWaitingLine(vision));assertEquals(listOf("Chat: note text to qwen3.5 at ollama.home.arpa, on your network"),vision.facts)
 }
 @Test fun onlyExactEnvironmentPinsUnlockAndCapabilitiesFailClosed() {
  listOf("true","1").forEach {assertEquals(EnvironmentPin.Managed,environmentPin(Json.parseToJsonElement(it)))}
  listOf("false","0").forEach {assertEquals(EnvironmentPin.Unmanaged,environmentPin(Json.parseToJsonElement(it)))}
  listOf("\"1\"","\"false\"","1.0","2","null","{}").forEach {assertEquals(it,EnvironmentPin.Unknown,environmentPin(Json.parseToJsonElement(it)))}
  assertEquals(EnvironmentPin.Unknown,environmentPin(null))
  assertFalse(NativePluginFacts(JsonObject(pluginFixture(on=false)-"can_enable")).canEnable)
  assertFalse(NativePluginFacts(JsonObject(pluginFixture()+ ("pinned_by_env" to JsonPrimitive(1)))).canDisable)
 }
 @Test fun malformedContractsAndRemovedNeverActivate() {
  val duplicate=JsonObject(pluginFixture()+ ("permissions" to JsonArray(listOf(JsonPrimitive("notes.read"),JsonPrimitive("notes.read")))))
  assertFalse(NativePluginFacts(duplicate,true).valid)
  assertFalse(nativePluginCatalog(pluginCatalogFixture(pluginFixture(),pluginFixture()),pluginCatalogFixture()).valid)
  val removed=NativePluginFacts(JsonObject(pluginFixture(state="removed",on=false)+( "pinned_by_env" to JsonPrimitive(1))),true)
  assertFalse(removed.wantedOn);assertFalse(removed.activeContribution);assertFalse(removed.canEnable);assertFalse(removed.canDisable);assertTrue(removed.canDeleteData);assertTrue(removed.contribution.isEmpty())
  listOf("0.4","0.9","1.0").forEach {assertTrue(NativePluginFacts(JsonObject(pluginFixture()+ ("api" to JsonPrimitive(it)))).valid)}
 }
 @Test fun hostFlagsNameEveryDestinationAndResolveScheduleWithoutHostHeuristics() {
  val facts=NativePluginFacts(JsonObject(pluginFixture(permissions=listOf("ai.chat","ai.embed","net.fetch","net.out:api.synthetic.example","schedule"))+mapOf(
   "ai_destinations" to Json.parseToJsonElement("""{"chat":{"model":"cloud-model","host":"ollama.synthetic.lan","leaves_network":true},"embed":{"model":"embed-model","host":"ollama.synthetic.lan","leaves_network":false}}"""),
   "network_destinations" to JsonArray(listOf(JsonPrimitive("api.synthetic.example"))),
   "settings" to Json.parseToJsonElement("""{"weekday":"sunday","time":"18:00"}"""),
   "contributes" to Json.parseToJsonElement("""{"schedules":[{"weekday":"{weekday}","time":"{time}"}]}""")
  )))
  assertTrue(facts.facts.any {it.contains("cloud-model at ollama.synthetic.lan, outside your network")})
  assertTrue(facts.facts.any {it.contains("embed-model at ollama.synthetic.lan, on your network")})
  assertTrue(facts.facts.any {it.contains("Fetches")});assertTrue(facts.facts.any {it.contains("Talks to api.synthetic.example")});assertTrue(facts.facts.any {it.startsWith("Sunday 18:00 (server time)")})
 }
 @Test fun settingsRemoveSecretValuesAndScopeCacheByInstanceAndContract() {
  val raw=Json.parseToJsonElement("""{"schema":{"type":"object","properties":{"bot_token":{"type":"string","x-secret":true},"language":{"type":"string","default":"en"}}},"values":{"bot_token":"synthetic-private-placeholder","language":"it","unknown":"drop"},"secrets":{"bot_token":{"set":true,"value":"synthetic-private-placeholder"}}}""").jsonObject
  val page=safePluginPage(raw)
  assertFalse(page.json().toString().contains("synthetic-private-placeholder"));assertFalse(page.values.containsKey("bot_token"));assertFalse(page.values.containsKey("unknown"));assertEquals("it",page.values["language"]!!.jsonPrimitive.content);assertTrue(page.secrets["bot_token"]!!.jsonObject["set"]!!.jsonPrimitive.boolean)
  val plugin=NativePluginFacts(pluginFixture())
  assertNotEquals(pluginPageCacheKey("https://one.synthetic",plugin),pluginPageCacheKey("https://two.synthetic",plugin))
  assertNotEquals(pluginPageCacheKey("https://one.synthetic",plugin),pluginPageCacheKey("https://one.synthetic",NativePluginFacts(JsonObject(plugin.raw+("version" to JsonPrimitive("2.0.0"))))))
  val cachedCatalog=NativePluginFacts(JsonObject(pluginFixture()+mapOf("settings" to raw["values"]!!,"secrets" to raw["secrets"]!!)))
  assertFalse(catalogPluginPage(cachedCatalog).json().toString().contains("synthetic-private-placeholder"))
 }
 @Test fun webLinksHaveNoCredentialBridgeAndUndoNeverExpandsGrants() {
  assertEquals("https://synthetic.invalid/settings/plugins/voice-memos",pluginWebUrl("https://synthetic.invalid/",false,"/settings/plugins/voice-memos"))
  listOf("https://user:password@synthetic.invalid","https://synthetic.invalid/path","https://synthetic.invalid?token=placeholder","https://synthetic.invalid#fragment","http://synthetic.invalid").forEach {assertNull(pluginWebUrl(it,false,"/settings/plugins"))}
  assertNull(pluginWebUrl("https://synthetic.invalid",false,"/settings/plugins/../devices"));assertNull(pluginWebUrl("https://synthetic.invalid",false,"https://other.invalid"))
  val plugin=NativePluginFacts(pluginFixture(on=false,state="available",permissions=listOf("notes.read")))
  val undo=PluginUndo(plugin.id,"https://synthetic.invalid",listOf("notes.read"),setOf("notes.read","obsolete"),7000)
  assertEquals(listOf("notes.read"),undoPermissions(undo,plugin,undo.server,6000))
  assertNull(undoPermissions(undo,plugin,undo.server,7000));assertNull(undoPermissions(undo,plugin,"https://other.invalid",0))
  val updated=NativePluginFacts(pluginFixture(on=false,state="available",permissions=listOf("notes.read","notes.create")))
  assertNull(undoPermissions(undo,updated,undo.server,0))
  assertNull(undoPermissions(undo,NativePluginFacts(JsonObject(plugin.raw+("granted" to JsonArray(emptyList())))),undo.server,0))
  val waiting=NativePluginFacts(JsonObject(pluginFixture(permissions=listOf("notes.read","notes.create"))+("granted" to JsonArray(listOf(JsonPrimitive("notes.read"))))))
  assertTrue(waiting.permissionReviewRequired)
 }
 @Test fun actualGenericRemovedProducerFallbackDefaultsOnlyAbsentCategoryToData() {
  // _removed_snapshot corrupt/legacy fallback plus the actual host/API state projection.
  val row=Json.parseToJsonElement("""{"id":"legacy-removed","name":"Removed plugin","version":"0","api":"1.0","kind":"server","description":"Plugin code is no longer installed.","permissions":[],"granted":[],"switch":"off","state":"removed","pinned_by_env":0,"origin":"unknown","source_kind":"unknown","can_enable":false,"can_delete_data":true,"settings":{},"secrets":{},"ai_destinations":{},"network_destinations":[],"data_count":214}""").jsonObject
  val facts=NativePluginFacts(row);assertEquals("data",facts.category);assertTrue(facts.valid);assertTrue(facts.canDeleteData);assertFalse(facts.canEnable)
  assertEquals("data",nativePluginCatalog(pluginCatalogFixture(row),pluginCatalogFixture()).rows.single().category)
  listOf(JsonPrimitive("nonsense"),JsonPrimitive(""),JsonNull,JsonPrimitive(1)).forEach {assertFalse(NativePluginFacts(JsonObject(row+("category" to it))).valid)}
 }
 @Test fun permissionSentencesReadAfterItWill() {
  assertEquals(listOf("Read attachments","Fetch the linked page","Use the vision model","Never read private notes","Process audio","Add labels"),
   listOf("Reads attachments","Fetches the linked page","Uses the vision model","Never reads private notes","Process audio","Adds labels").map(::pluginWillSentence))
 }
}
