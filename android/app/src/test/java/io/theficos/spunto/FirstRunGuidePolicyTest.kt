package io.theficos.spunto

import org.junit.Assert.*
import org.junit.Test

class FirstRunGuidePolicyTest {
 @Test fun onlyAnOrdinaryMainLaunchIsEligible() {
  assertTrue(firstRunGuideEligible("android.intent.action.MAIN",false,null))
  assertFalse(firstRunGuideEligible("android.intent.action.MAIN",false,"synthetic-note"))
  assertFalse(firstRunGuideEligible("android.intent.action.MAIN",true,null))
  for(action in listOf(null,"android.intent.action.VIEW","android.intent.action.SEND","android.intent.action.PROCESS_TEXT","dictate")) {
   assertFalse(firstRunGuideEligible(action,false,null))
  }
 }
}
