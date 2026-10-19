package io.theficos.spunto

import org.junit.Assert.*
import org.junit.Test

class PairingInputTest {
 private val server="https://synthetic-pairing.invalid"
 private val code="synthetic-existing-code"
 @Test fun objectOrArrayFieldsAreRejectedBeforeEitherFieldCanChange() {
  for(lock in listOf(null,server)) for(shape in listOf("[]","{}","null","true","123")) {
   assertEquals(PairingInput.Invalid,pairingInput("""{"server":$shape,"code":"synthetic-new-code"}""",server,code,lock))
   assertEquals(PairingInput.Invalid,pairingInput("""{"server":"https://other-synthetic.invalid","code":$shape}""",server,code,lock))
  }
 }
 @Test fun malformedAndWrongRootPayloadsFailWithoutExposingAnyPartialFields() {
  for(lock in listOf(null,server)) for(payload in listOf("{","[]","[{}]","{\"server\":\"https://other-synthetic.invalid\",\"code\":")) {
   assertEquals(PairingInput.Invalid,pairingInput(payload,server,code,lock))
  }
 }
 @Test fun validPayloadUpdatesBothFieldsTogetherAndPreservesOptionalFields() {
  assertEquals(PairingInput.Accepted("https://other-synthetic.invalid","synthetic-new-code"),pairingInput("""{"server":"https://other-synthetic.invalid","code":"synthetic-new-code"}""",server,code))
  assertEquals(PairingInput.Accepted(server,"synthetic-new-code"),pairingInput("""{"code":"synthetic-new-code"}""",server,code))
  assertEquals(PairingInput.Accepted(server,code),pairingInput("{}",server,code))
 }
 @Test fun lockedServerKeepsSameServerAndPlainCodeRules() {
  assertEquals(PairingInput.Accepted(server+"/","synthetic-new-code"),pairingInput("""{"server":"$server/","code":"synthetic-new-code"}""",server,code,server))
  assertEquals(PairingInput.DifferentServer,pairingInput("""{"server":"https://other-synthetic.invalid","code":"synthetic-new-code"}""",server,code,server))
  assertEquals(PairingInput.DifferentServer,pairingInput("""{"code":"synthetic-new-code"}""",server,code,server))
  assertEquals(PairingInput.Accepted(server,"synthetic-manual-code"),pairingInput("synthetic-manual-code",server,code,server))
 }
}
