package io.theficos.spunto

import org.junit.Assert.*
import org.junit.Test

class CaretAfterSyncTest {
 @Test fun caretFollowsItsWordsWhenASyncAddsTextBeforeIt() {
  val old="first\nlast PHONE";val new="first WEB\nlast PHONE"
  assertEquals(new.length,caretAfterSync(old,new,old.length))
  assertEquals(new.indexOf("PHONE"),caretAfterSync(old,new,old.indexOf("PHONE")))
 }
 @Test fun caretStaysWhenASyncChangesTextAfterIt() = assertEquals(5,caretAfterSync("first\nlast","first\nlast WEB",5))
 @Test fun caretInsideReplacedWordsMovesToTheirEnd() = assertEquals("one WEB two".indexOf(" two"),caretAfterSync("one old two","one WEB two",6))
}
