package io.theficos.spunto

import io.theficos.spunto.data.sync.ApiException
import kotlinx.serialization.SerializationException
import org.junit.Assert.*
import org.junit.Test
import java.net.ConnectException
import java.net.UnknownHostException
import java.security.cert.CertificateException
import javax.net.ssl.SSLException
import javax.net.ssl.SSLHandshakeException

@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(application=android.app.Application::class,sdk=[28])
class SignInTest {
 @Test fun addressesGetASchemeAndLoseTrailingSlashes() {
  assertEquals("https://spunto.example.org",normalizedServerUrl("spunto.example.org"))
  assertEquals("https://spunto.example.org",normalizedServerUrl(" https://Spunto.Example.org/ "))
  assertEquals("http://192.0.2.10:8080",normalizedServerUrl("http://192.0.2.10:8080//"))
  assertEquals("https://localhost:8443",normalizedServerUrl("localhost:8443"))
  for(bad in listOf("","https://","http://","https://spunto.example.org/notes","https://user:pass@spunto.example.org","ftp://spunto.example.org")) assertNull(bad,normalizedServerUrl(bad))
 }
 @Test fun addressProblemsAreOneSentenceForTheServerField() {
  assertNull(serverUrlProblem(settingsTestResources(),"spunto.example.org"))
  assertEquals(SignInProblem(SignInField.Server,"Enter your server's address."),serverUrlProblem(settingsTestResources(),"https://"))
  assertEquals(SignInField.Server,serverUrlProblem(settingsTestResources(),"https://spunto.example.org/notes")?.field)
 }
 @Test fun aWrongPasswordStaysUnderThePasswordField() {
  val problem=signInProblem(settingsTestResources(),ApiException(401,"wrong_password","That password is not right."),pairing=false)
  assertEquals(SignInProblem(SignInField.Password,"That password didn't work. Try again."),problem)
  assertEquals(SignInProblem(SignInField.Password,"Too many tries. Try again in 4 seconds."),signInProblem(settingsTestResources(),ApiException(429,"rate_limited","",4),pairing=false))
  assertEquals(SignInProblem(SignInField.Code,"Too many tries. Try again in 1 second."),signInProblem(settingsTestResources(),ApiException(429,"rate_limited","",1),pairing=true))
  assertEquals(SignInField.Code,signInProblem(settingsTestResources(),ApiException(401,"invalid_pairing_code"),pairing=true).field)
 }
 @Test fun unreachableOrForeignServersPointAtTheAddress() {
  val unreachable=SignInProblem(SignInField.Server,"No server answered at that address. Check the URL, including the port.")
  assertEquals(unreachable,signInProblem(settingsTestResources(),UnknownHostException("spunto.example.org"),pairing=false))
  assertEquals(unreachable,signInProblem(settingsTestResources(),ConnectException("Failed to connect to /192.0.2.10:443"),pairing=false))
  val untrusted=SSLHandshakeException("PKIX path building failed").apply {initCause(CertificateException("Trust anchor for certification path not found."))}
  assertEquals(SignInProblem(SignInField.Server,"This phone doesn't trust the server's HTTPS certificate."),signInProblem(settingsTestResources(),untrusted,pairing=false))
  assertEquals(SignInProblem(SignInField.Server,"This address doesn't answer over HTTPS. If your server uses plain HTTP, start the address with http://."),signInProblem(settingsTestResources(),SSLException("Unable to parse TLS packet header"),pairing=false))
  assertEquals(SignInField.Server,signInProblem(settingsTestResources(),SerializationException("Unexpected JSON token"),pairing=false).field)
  assertEquals(SignInField.Server,signInProblem(settingsTestResources(),ApiException(404,"request_failed"),pairing=false).field)
  assertEquals(SignInField.Server,signInProblem(settingsTestResources(),ApiException(301,"request_failed"),pairing=false).field)
 }
 @Test fun unknownProblemsShowTheServerSentenceAndNeverACode() {
  assertEquals(SignInProblem(SignInField.Form,"Writes must come from https://spunto.example.org."),signInProblem(settingsTestResources(),ApiException(403,"origin_mismatch","Writes must come from https://spunto.example.org."),pairing=false))
  val bare=signInProblem(settingsTestResources(),ApiException(403,"some_new_code"),pairing=false)
  assertFalse(bare.message.contains("some_new_code") || bare.message.contains("403"))
  assertEquals("The account changed. Continue from the current account.",signInProblem(settingsTestResources(),IllegalArgumentException("The account changed. Continue from the current account."),pairing=false).message)
  assertEquals("Couldn't sign in. Try again.",signInProblem(settingsTestResources(),IllegalArgumentException("Expected URL scheme 'http' or 'https' but no scheme was found for spunto…"),pairing=false).message)
 }
}
