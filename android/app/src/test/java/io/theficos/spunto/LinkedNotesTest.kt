package io.theficos.spunto
import io.theficos.spunto.data.local.PluginOutputEntity
import org.junit.Assert.assertEquals
import org.junit.Test
class LinkedNotesTest {
 private fun panel(id:String,note:String,plugin:String,payload:String)=PluginOutputEntity("annotation",id,note,"""{"plugin_id":"$plugin","kind":"panel","payload":$payload}""")
 @Test fun backlinksComeOnlyFromTheLinksPanelOfThisNote() {
  val outputs=listOf(
   panel("1","here","links","""{"type":"notes","ids":["a","b","a"]}"""),
   panel("2","here","related","""{"type":"notes","ids":["c"]}"""),
   panel("3","elsewhere","links","""{"type":"notes","ids":["d"]}"""),
   PluginOutputEntity("suggestion","4","here","""{"plugin_id":"links","kind":"panel","payload":{"type":"notes","ids":["e"]}}"""))
  assertEquals(listOf("a","b"),backlinkIds(outputs,"here"))
  assertEquals(emptyList<String>(),backlinkIds(outputs,"nowhere"))
 }
 @Test fun excerptIsTheLineAfterTheTitleWithoutMarkup() {
  assertEquals("Flights booked for March.",noteExcerpt("# Trip to Lisbon\n\n**Flights** booked for [March](https://example.org).\n\nMore"))
  assertEquals("",noteExcerpt("only a title"))
 }
}
