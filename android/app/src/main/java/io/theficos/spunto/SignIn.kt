package io.theficos.spunto

import android.content.res.Resources

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
internal fun serverUrlProblem(resources:Resources,input:String):SignInProblem? = when {
 normalizedServerUrl(input)!=null -> null
 addressPart(input).isBlank() -> SignInProblem(SignInField.Server,resources.getString(R.string.settings_enter_your_server_s_address))
 else -> SignInProblem(SignInField.Server,resources.getString(R.string.settings_enter_the_server_s_own_address_like))
}
internal fun defaultDeviceName(context:Context):String = Settings.Global.getString(context.contentResolver,Settings.Global.DEVICE_NAME)?.trim()?.takeIf {it.isNotEmpty()} ?: Build.MODEL

/** Known problems get the 03 §8 copy, anything else the server's own sentence; never a status code or exception text. */
internal fun signInProblem(resources:Resources,error:Throwable,pairing:Boolean):SignInProblem {
 val secret=if(pairing) SignInField.Code else SignInField.Password
 return when(error) {
  is ApiException -> when {
   error.code=="wrong_password" -> SignInProblem(SignInField.Password,resources.getString(R.string.settings_that_password_didn_t_work_try_again))
   error.code=="rate_limited" -> SignInProblem(secret,error.retryAfter?.let {resources.getQuantityString(R.plurals.settings_retry_seconds,it.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),it)} ?: resources.getString(R.string.settings_retry_minute))
   error.code=="invalid_pairing_code" -> SignInProblem(SignInField.Code,resources.getString(R.string.settings_this_pairing_code_expired_or_was_already))
   error.code=="setup_required" -> SignInProblem(SignInField.Server,resources.getString(R.string.settings_this_server_isn_t_set_up_yet))
   error.status in 300..399 -> SignInProblem(SignInField.Server,resources.getString(R.string.settings_that_address_forwards_somewhere_else_enter_the))
   error.code=="request_failed" && error.status<500 -> SignInProblem(SignInField.Server,resources.getString(R.string.settings_no_spunto_server_answered_at_that_address))
   error.status>=500 -> SignInProblem(SignInField.Server,resources.getString(R.string.settings_the_server_couldn_t_sign_this_phone))
   else -> SignInProblem(SignInField.Form,error.detail.ifBlank {resources.getString(R.string.settings_couldn_t_sign_in_try_again)})
  }
  // A certificate this phone rejects, or no TLS at all: an address typed without http:// meets a plain HTTP server as https://.
  is SSLException -> if(error is SSLPeerUnverifiedException || generateSequence(error as Throwable) {it.cause}.any {it is CertificateException}) SignInProblem(SignInField.Server,resources.getString(R.string.settings_this_phone_doesn_t_trust_the_server))
   else SignInProblem(SignInField.Server,resources.getString(R.string.settings_this_address_doesn_t_answer_over_https))
  is IOException -> SignInProblem(SignInField.Server,resources.getString(R.string.settings_no_server_answered_at_that_address_check))
  is SerializationException -> SignInProblem(SignInField.Server,resources.getString(R.string.settings_no_spunto_server_answered_at_that_address))
  // The account checks in SpuntoViewModel.login state their reason as a sentence.
  is io.theficos.spunto.core.model.LocalOperationException -> SignInProblem(SignInField.Form,localOperationMessage(resources,error))
  is IllegalArgumentException,is IllegalStateException -> SignInProblem(SignInField.Form,error.message?.takeIf {it.endsWith('.')} ?: resources.getString(R.string.settings_couldn_t_sign_in_try_again))
  else -> SignInProblem(SignInField.Form,resources.getString(R.string.settings_couldn_t_sign_in_try_again))
 }
}
