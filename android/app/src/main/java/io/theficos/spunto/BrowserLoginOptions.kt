package io.theficos.spunto

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

internal data class BrowserLoginOptions(val hosted:Boolean,val path:String)

internal fun browserLoginOptions(options:JsonObject?,knownHosted:Boolean):BrowserLoginOptions {
 val mode=(options?.get("mode") as? JsonPrimitive)?.content
 val hosted=mode in setOf("hosted","oidc") || knownHosted
 // Hosted accounts choose password or optional SSO in the server's browser UI.
 val path=if(mode=="oidc") (options?.get("login_url") as? JsonPrimitive)?.content else null
 val safePath=path?.takeIf {it.startsWith("/") && !it.startsWith("//") && !it.contains("\\")}
 return BrowserLoginOptions(hosted,safePath ?: if(mode=="oidc") "/api/v1/auth/oidc/start" else "/auth")
}
