package io.theficos.spunto.core.plugins
import java.io.File
import kotlinx.serialization.json.*
import org.junit.Test
import org.junit.Assert.*
class PluginContractTest {
 @get:org.junit.Rule val perTestTimeout=org.junit.rules.Timeout(10,java.util.concurrent.TimeUnit.MINUTES)
 private fun fixture(path:String):JsonObject { if(File("/spec/fixtures/$path").exists()) return Json.parseToJsonElement(File("/spec/fixtures/$path").readText()).jsonObject; var dir=File(System.getProperty("user.dir")); while(!File(dir,"spec/fixtures").exists()) { dir=dir.parentFile ?: error("fixtures missing") }; return Json.parseToJsonElement(File(dir,"spec/fixtures/$path").readText()).jsonObject }
 @Test fun vocabularyGoldensAndFallbacks() { listOf("text","markdown","notes","pill","command","form","diff").forEach { assertNotNull(safeComponent(fixture("vocabulary/$it.json"))) }; assertEquals("Kept",safeComponent(Json.parseToJsonElement("""{"type":"future","fallback":{"type":"text","text":"Kept"}}""").jsonObject)?.string("text")) }
 @Test fun malformedKnownTypesRenderFallbackWithoutThrowing() {
  val invalid=listOf("""{"type":"text","text":{},"fallback":{"type":"text","text":"Safe"}}""", """{"type":"notes","items":[{}],"fallback":{"type":"text","text":"Safe"}}""", """{"type":"text","text":"Ignored","unexpected":true,"fallback":{"type":"text","text":"Safe"}}""")
  invalid.forEach { assertEquals("Safe",safeComponent(Json.parseToJsonElement(it).jsonObject)?.string("text")) }
 }
 @Test fun viewGoldens() {
  val fixture=fixture("views.json"); val now=fixture["now"]!!.jsonPrimitive.long
  val notes=fixture.objects("notes").map { n -> ViewNote(n.string("key"),n.string("body"),now-(n["age_days"]!!.jsonPrimitive.long*86400000),n.bool("pinned"),n.bool("archived"),source=n.string("source").ifEmpty { "owner" },latestAgent=n.bool("latest_agent"),sourceRef=n.string("source_ref"),members=(n["members"] as? JsonArray)?.map { it.jsonPrimitive.content }?.toSet().orEmpty(),labels=Regex("#([\\p{L}][\\p{L}\\p{N}_/-]*)").findAll(n.string("body")).map { it.groupValues[1] }.toSet()) }
  fixture.objects("cases").forEach { case -> assertEquals((case["expected"] as JsonArray).map { it.jsonPrimitive.content }.sorted(),notes.filter { matchesView(it,case["filter"]!!.jsonObject,now) }.map { it.id }.sorted()) }
 }
}
