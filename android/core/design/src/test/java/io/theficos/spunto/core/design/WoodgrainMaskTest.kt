package io.theficos.spunto.core.design

import org.junit.Assert.assertEquals
import org.junit.Test

class WoodgrainMaskTest {
 @Test fun transparentBackgroundNeverBecomesSolidInk() {assertEquals(0,woodgrainAlpha(0x00000000));assertEquals(0,woodgrainAlpha(0x00FFFFFF))}
 @Test fun printedToneAndOriginalCoverageBothRemainVisible() {
  assertEquals(255,woodgrainAlpha(0xFF000000.toInt()))
  assertEquals(128,woodgrainAlpha(0x80000000.toInt()))
  assertEquals(127,woodgrainAlpha(0xFF808080.toInt()))
  assertEquals(64,woodgrainAlpha(0x80808080.toInt()))
  assertEquals(0,woodgrainAlpha(0xFFFFFFFF.toInt()))
 }
}
