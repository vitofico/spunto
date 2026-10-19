package io.theficos.spunto.core.design

import androidx.compose.ui.unit.sp
import org.junit.Assert.*
import org.junit.Test

class ReadingTypographyTest {
 @Test fun readingFaceAndSizeOnlyChangeTheOwnerBodyRole() {
  val baseline=readingTypography("serif","M")
  assertEquals(readingFont(),baseline.bodyLarge.fontFamily)
  for((size,expected) in listOf("S" to 16,"M" to 17,"L" to 19)) {
   val selected=readingTypography("sans",size)
   assertEquals(uiFont(),selected.bodyLarge.fontFamily);assertEquals(expected.sp,selected.bodyLarge.fontSize)
   val ratio=selected.bodyLarge.lineHeight.value/selected.bodyLarge.fontSize.value
   assertTrue(ratio in 1.55f..1.6f)
   assertEquals(baseline.bodyMedium,selected.bodyMedium);assertEquals(baseline.bodySmall,selected.bodySmall)
   assertEquals(baseline.titleLarge,selected.titleLarge);assertEquals(baseline.labelLarge,selected.labelLarge)
  }
  assertEquals(baseline,readingTypography("not-a-font","not-a-size"))
 }
}
