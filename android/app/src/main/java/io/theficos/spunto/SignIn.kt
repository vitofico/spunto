package io.theficos.spunto

import android.content.Context
import android.os.Build
import android.provider.Settings
import io.theficos.spunto.data.sync.ApiException
import kotlinx.serialization.SerializationException
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.IOException
import java.security.cert.CertificateException
import javax.net.ssl.SSLException
import javax.net.ssl.SSLPeerUnverifiedException

internal enum class SignInField { Server, Password, Name, Code, Form }
/** One sentence under the field that caused it (03 A10); [SignInField.Form] sits above the button. */
internal data class SignInProblem(val field:SignInField,val message:String)

private fun addressPart(input:String)=input.trim().replace(Regex("^[a-zA-Z][a-zA-Z0-9+.-]*:/*"),"").trimEnd('/')
/** The server's root address: https:// is added when no scheme is typed and trailing slashes go; null when it is not a root http(s) address. */
internal fun normalizedServerUrl(input:String):String? {
 val text=input.trim().trimEnd('/')
 if(addressPart(input).isBlank()) return null
 val url=(if(text.contains("://")) text else "https://$text").toHttpUrlOrNull() ?: return null
 if(url.encodedPath!="/" || url.username.isNotEmpty() || url.password.isNotEmpty() || url.query!=null || url.fragment!=null) return null
 return url.toString().trimEnd('/')
}
internal fun serverUrlProblem(input:String):SignInProblem? = when {
 normalizedServerUrl(input)!=null -> null
 addressPart(input).isBlank() -> SignInProblem(SignInField.Server,"Enter your server's address.")
 else -> SignInProblem(SignInField.Server,"Enter the server's own address, like https://spunto.example.org.")
}
internal fun defaultDeviceName(context:Context):String = Settings.Global.getString(context.contentResolver,Settings.Global.DEVICE_NAME)?.trim()?.takeIf {it.isNotEmpty()} ?: Build.MODEL

/** Known problems get the 03 §8 copy, anything else the server's own sentence; never a status code or exception text. */
internal fun signInProblem(error:Throwable,pairing:Boolean):SignInProblem {
 val secret=if(pairing) SignInField.Code else SignInField.Password
 return when(error) {
  is ApiException -> when {
   error.code=="wrong_password" -> SignInProblem(SignInField.Password,"That password didn't work. Try again.")
   error.code=="rate_limited" -> SignInProblem(secret,"Too many tries. Try again in ${error.retryAfter?.let {if(it==1L) "1 second" else "$it seconds"} ?: "a minute"}.")
   error.code=="invalid_pairing_code" -> SignInProblem(SignInField.Code,"This pairing code expired or was already used. Create a new one in the web app, under Settings → Devices.")
   error.code=="setup_required" -> SignInProblem(SignInField.Server,"This server isn't set up yet. Open it in a browser to finish setup.")
   error.status in 300..399 -> SignInProblem(SignInField.Server,"That address forwards somewhere else. Enter the server's own address, starting with https://.")
   error.code=="request_failed" && error.status<500 -> SignInProblem(SignInField.Server,"No Spunto server answered at that address. Check the URL.")
   error.status>=500 -> SignInProblem(SignInField.Server,"The server couldn't sign this phone in. Try again in a moment.")
   else -> SignInProblem(SignInField.Form,error.detail.ifBlank {"Couldn't sign in. Try again."})
  }
  // A certificate this phone rejects, or no TLS at all: an address typed without http:// meets a plain HTTP server as https://.
  is SSLException -> if(error is SSLPeerUnverifiedException || generateSequence(error as Throwable) {it.cause}.any {it is CertificateException}) SignInProblem(SignInField.Server,"This phone doesn't trust the server's HTTPS certificate.")
   else SignInProblem(SignInField.Server,"This address doesn't answer over HTTPS. If your server uses plain HTTP, start the address with http://.")
  is IOException -> SignInProblem(SignInField.Server,"No server answered at that address. Check the URL, including the port.")
  is SerializationException -> SignInProblem(SignInField.Server,"No Spunto server answered at that address. Check the URL.")
  // The account checks in SpuntoViewModel.login state their reason as a sentence.
  is IllegalArgumentException,is IllegalStateException -> SignInProblem(SignInField.Form,error.message?.takeIf {it.endsWith('.')} ?: "Couldn't sign in. Try again.")
  else -> SignInProblem(SignInField.Form,"Couldn't sign in. Try again.")
 }
}
