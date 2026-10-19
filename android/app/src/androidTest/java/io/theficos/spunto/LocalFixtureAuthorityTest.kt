package io.theficos.spunto

import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

/** Pure synthetic authorities only; these cases never open the application or write a body. */
class LocalFixtureAuthorityTest {
 @Test fun blankAndCanonicalLocalAuthoritiesAreAccepted() {
  listOf(null to null,null to "",null to "http://127.0.0.1:18081", "http://localhost:80/" to "http://LOCALHOST", "https://10.0.2.2:443" to "https://10.0.2.2/", "http://127.0.0.1:18081" to "http://127.0.0.1:18081/").forEach {(auth,replica) ->requireLocalFixtureRoots(auth,replica)}
 }
 @Test fun remoteMalformedUnboundAndMismatchedAuthoritiesFailBeforeTheWrite() {
  val cases=listOf(null to "https://synthetic.invalid", "https://synthetic.invalid" to "https://synthetic.invalid", "http://127.0.0.1:18081" to "http://127.0.0.1:18082", "http://localhost" to "http://127.0.0.1", "http://localhost" to null, "http://localhost" to "https://localhost", "http://localhost.synthetic.invalid" to "http://localhost.synthetic.invalid", "http://fixture@localhost" to "http://localhost", "http://localhost/?fixture=true" to "http://localhost", "http://localhost/#fixture" to "http://localhost", "http://localhost/subpath" to "http://localhost", "http://localhost:0" to "http://localhost")
  var bodyWrites=0
  cases.forEach {(auth,replica) ->
   try {requireLocalFixtureRoots(auth,replica);bodyWrites++;fail("Unsafe fixture authority was accepted.")}
   catch(error:IllegalStateException) {assertEquals("Effectful fixtures require the same local replica authority.",error.message)}
  }
  assertEquals(0,bodyWrites)
 }
}
