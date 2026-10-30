package io.theficos.spunto

import org.junit.Assert.*
import org.junit.Test

class AskSearchTest {
 @Test fun questionsUseWhitespaceWordsAndTheServerCharacterLimit() {
  assertFalse(canAskSearch(""));assertFalse(canAskSearch("two words"))
  assertTrue(canAskSearch("  What did I decide?  "))
  assertTrue(canAskSearch("What\tdid\nI decide?"))
  assertFalse(canAskSearch("what\\ssdid\\ssI"))
  assertTrue(canAskSearch("😀 "+"x".repeat(496)+" z"))
  assertFalse(canAskSearch("😀 "+"x".repeat(497)+" z"))
 }
}
