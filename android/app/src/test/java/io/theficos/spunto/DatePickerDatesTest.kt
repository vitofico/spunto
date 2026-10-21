package io.theficos.spunto

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.util.TimeZone

class DatePickerDatesTest {
 @Test fun pickerCalendarDaysStayStableAcrossDeviceZonesAndDaylightSaving() {
  val previous=TimeZone.getDefault()
  try {
   for(zone in listOf("Pacific/Honolulu","Europe/Madrid","Pacific/Kiritimati")) {
    TimeZone.setDefault(TimeZone.getTimeZone(zone))
    for(day in listOf("2026-03-29","2026-10-25","2026-12-31")) {
     val millis=pickerDateMillis(day)!!
     assertEquals(Instant.parse(day+"T00:00:00Z").toEpochMilli(),millis)
     assertEquals(day,pickerDate(millis))
    }
   }
   assertNull(pickerDateMillis(""))
  } finally {TimeZone.setDefault(previous)}
 }
}
