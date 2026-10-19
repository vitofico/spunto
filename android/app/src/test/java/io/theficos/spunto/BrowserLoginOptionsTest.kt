package io.theficos.spunto

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Test

class BrowserLoginOptionsTest {
 private fun options(text:String,known:Boolean=false)=browserLoginOptions(Json.parseToJsonElement(text).jsonObject,known)

 @Test fun hostedAccountsAlwaysChooseTheirLoginMethodInTheBrowser() {
  for(body in listOf(
   """{"mode":"hosted","password_enabled":true,"oidc_enabled":false}""",
   """{"mode":"hosted","password_enabled":true,"oidc_enabled":true,"login_url":"/api/v1/auth/oidc/start"}""",
  )) {
   assertEquals(BrowserLoginOptions(true,"/auth"),options(body))
  }
 }
 @Test fun olderOidcServersRetainTheirSameOriginEntryPoint() {
  assertEquals(BrowserLoginOptions(true,"/api/v1/auth/oidc/start"),options("""{"mode":"oidc"}"""))
  assertEquals(BrowserLoginOptions(true,"/custom/signin"),options("""{"mode":"oidc","login_url":"/custom/signin"}"""))
  for(path in listOf("https://other.invalid","//other.invalid","/\\other.invalid")) {
   val body=kotlinx.serialization.json.buildJsonObject {put("mode",Json.parseToJsonElement("\"oidc\""));put("login_url",kotlinx.serialization.json.JsonPrimitive(path))}
   assertEquals("/api/v1/auth/oidc/start",browserLoginOptions(body,false).path)
  }
 }
 @Test fun legacyLocalAndUnavailableHostedServersKeepTheCorrectConnectionFlow() {
  assertFalse(options("""{"mode":"local"}""").hosted)
  assertEquals(BrowserLoginOptions(true,"/auth"),browserLoginOptions(null,true))
  assertFalse(browserLoginOptions(null,false).hosted)
  assertEquals(BrowserLoginOptions(true,"/auth"),options("""{"mode":{}}""",true))
 }
}
