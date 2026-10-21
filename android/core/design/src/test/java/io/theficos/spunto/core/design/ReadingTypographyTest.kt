package io.theficos.spunto.core.design

import androidx.compose.ui.unit.sp
import org.junit.Assert.*
import org.junit.Test

class ReadingTypographyTest {
 @Test fun interfaceRolesStayInInterWhateverTheReadingFace() {
  with(spuntoMaterialTypography) { for(style in listOf(bodyLarge,bodyMedium,bodySmall,labelLarge,labelMedium,labelSmall,titleMedium,titleSmall)) assertEquals(uiFont(),style.fontFamily) }
  assertEquals(16.sp,spuntoMaterialTypography.bodyLarge.fontSize);assertEquals(14.sp,spuntoMaterialTypography.bodyMedium.fontSize)
 }
 @Test fun readingFaceAndSizeOnlyChangeTheNoteTextStyles() {
  val baseline=spuntoType("serif","M")
  assertEquals(readingFont(),baseline.reading.fontFamily);assertEquals(16.sp,baseline.reading.fontSize);assertEquals(25.sp,baseline.reading.lineHeight)
  assertEquals(15.sp,baseline.readingSmall.fontSize);assertEquals(18.sp,baseline.heading.fontSize);assertEquals(monoFont(),baseline.mono.fontFamily)
  for((size,expected) in listOf("S" to 15f,"M" to 16f,"L" to 18f)) {
   assertEquals(expected,spuntoType("serif",size).reading.fontSize.value,.001f)
   val sans=spuntoType("sans",size)
   assertEquals(uiFont(),sans.reading.fontFamily);assertEquals(expected*16.5f/17,sans.reading.fontSize.value,.001f)
   assertTrue(sans.reading.lineHeight.value/sans.reading.fontSize.value in 1.55f..1.65f)
   assertEquals(baseline.overline,sans.overline);assertEquals(baseline.mono,sans.mono)
  }
  assertEquals(baseline,spuntoType("not-a-font","not-a-size"))
 }
}
