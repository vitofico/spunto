package io.theficos.spunto.plugins.quickcapture

import java.time.ZoneOffset
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test

class SlipOverlineTest {
 @Test fun widgetSlipNamesTheDayItsNoteWasWritten() {
  assertEquals("FROM YOUR NOTES · 19 MARCH 2025",slipOverline(1_742_385_600_000L,"From your notes",ZoneOffset.UTC,Locale.ENGLISH))
  assertEquals("FROM YOUR NOTES",slipOverline(0L,"From your notes",ZoneOffset.UTC,Locale.ENGLISH))
 }
}
