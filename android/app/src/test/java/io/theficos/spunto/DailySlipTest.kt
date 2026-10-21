package io.theficos.spunto

import io.theficos.spunto.core.model.Note
import io.theficos.spunto.data.local.PluginOutputEntity
import org.junit.Assert.*
import org.junit.Test

class DailySlipTest {
 private fun slip(id:String,noteId:String)=PluginOutputEntity("annotation",id,noteId,"""{"kind":"slip","plugin":"resurface"}""")
 @Test fun archivingTheNoteTakesTheSlipAwayFromTheTimelineAndTheWidgetAndUndoBringsItBack() {
  val note=Note("synthetic-note","An older synthetic note")
  val outputs=listOf(slip("slip-1","synthetic-note"))
  assertEquals(note,dailySlip(outputs,listOf(note),{true})?.second)
  assertNull(dailySlip(outputs,listOf(note.copy(archived=true)),{true}))
  assertNull(dailySlip(outputs,listOf(note.copy(trashed=true)),{true}))
  assertNull(dailySlip(outputs,listOf(note.copy(privateNote=true)),{true}))
  assertNull(dailySlip(outputs,listOf(note),{it!="resurface"}))
  assertNull(dailySlip(outputs,listOf(note),{true}) {it.id=="slip-1"})
 }
}
