package io.theficos.spunto.core.design

import androidx.compose.ui.unit.sp
import org.junit.Assert.assertEquals
import org.junit.Test

class MarkdownTypographyTest {
 @Test fun machineProseKeepsItsExistingMetricsAcrossOwnerReadingPreferences() {
  for(face in listOf("serif","sans")) for(size in listOf("S","M","L")) {
   val owner=spuntoType(face,size).reading
   assertEquals(owner,markdownBodyStyle(false,owner))
   val machine=markdownBodyStyle(true,owner)
   assertEquals(uiFont(),machine.fontFamily)
   assertEquals(17.sp,machine.fontSize)
   assertEquals(26.sp,machine.lineHeight)
  }
 }
}
