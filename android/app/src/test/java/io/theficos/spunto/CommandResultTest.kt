package io.theficos.spunto

import io.theficos.spunto.core.plugins.outputComponents
import io.theficos.spunto.data.local.PluginOutputEntity
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(application=android.app.Application::class,sdk=[28])
class CommandResultTest {
 @Test fun entryMetaFollowsOwnerOrderInsteadOfReplicaArrival() {
  fun annotation(plugin:String,text:String)=PluginOutputEntity("annotation",plugin,"note-a",buildJsonObject { put("plugin",plugin);put("kind","meta");put("payload",buildJsonObject { put("type","text");put("text",text) }) }.toString())
  val outputs=listOf(annotation("reading-time","3 min"),annotation("title-summary","A useful title"))
  val plugins=listOf(buildJsonObject { put("id","title-summary");put("position",0) },buildJsonObject { put("id","reading-time");put("position",1) })
  assertEquals("A useful title",entryMetaText(outputs,"note-a",plugins,settingsTestResources()))
  assertEquals("A useful title",entryMetaText(outputs.reversed(),"note-a",plugins,settingsTestResources()))
  assertEquals("3 min",entryMetaText(outputs,"note-a",plugins.map { if(it["id"]==JsonPrimitive("title-summary")) JsonObject(it+mapOf("enabled" to JsonPrimitive(false))) else it },settingsTestResources()))
 }
 @Test fun storedTasksAndPillShellFollowTheAppLocale() {
  val resources=settingsTestResources("it")
  val data="""{"plugin":"tasks","kind":"meta","payload":{"type":"text","text":"2 of 3 done"}}"""
  val tasks=PluginOutputEntity("annotation","tasks","note-a",data)
  val plugins=listOf(buildJsonObject {put("id","tasks")})
  assertEquals("2 di 3 completate",entryMetaText(listOf(tasks),"note-a",plugins,resources))
  assertEquals("Applica modifiche",pluginPillCaption(resources,"tidy","Apply changes"))
  assertEquals("Owner-authored caption",pluginPillCaption(resources,"tidy","Owner-authored caption"))
 }
 @Test fun liveArrayKeepsCommandContextAndCopiesOnlyResultWords() {
  val result=Json.parseToJsonElement("""[{"type":"markdown","text":"Traduzione sintetica."},{"type":"command","id":"save-translation","title":"Save as note","args":{"result_id":"receipt-a"}}]""")
  val envelope=commandEnvelope("translate","translate",buildJsonObject { put("note_id","note-a");put("language","it") },result)
  assertEquals(2,outputComponents(envelope).size)
  assertEquals("Traduzione sintetica.",resultCopyText(settingsTestResources(),envelope))
  assertEquals("note-a",envelope["command_args"]!!.jsonObject["note_id"]!!.jsonPrimitive.content)
  assertEquals("translate",envelope["command_id"]!!.jsonPrimitive.content)
 }
 @Test fun pinnedResultHeadingSuppressesOnlyARepeatingLeadHeadingAndCopyKeepsTheSource() {
  val markdown=Json.parseToJsonElement("""{"type":"markdown","text":"# Statistiche\n\nUna nota.\n\n## Statistiche"}""").jsonObject
  assertEquals("Una nota.\n\n## Statistiche",withoutRepeatedResultHeading(markdown,"Statistiche")["text"]?.jsonPrimitive?.content)
  assertEquals(markdown,withoutRepeatedResultHeading(markdown,"Risultato"))
  assertEquals("# Statistiche\n\nUna nota.\n\n## Statistiche",resultCopyText(settingsTestResources(),markdown))
 }
 @Test fun copiedFocusedDiffClearlyNamesBothSides() {
  val diff=Json.parseToJsonElement("""{"type":"diff","base_version":3,"hunks":[{"old_start":4,"new_start":4,"lines":[" context","-Original words","+Proposed words"]}]}""")
  assertEquals("Before:\ncontext\nOriginal words\n\nAfter:\ncontext\nProposed words",resultCopyText(settingsTestResources(),diff))
 }
}
