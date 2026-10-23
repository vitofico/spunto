package io.theficos.spunto

import android.content.res.Resources

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

internal sealed interface PairingInput {
 data class Accepted(val server:String,val code:String):PairingInput
 data object Invalid:PairingInput
 data object DifferentServer:PairingInput
}

/** Validate the whole payload before either form field can change. Plain pairing codes stay plain. */
internal fun pairingInput(text:String,currentServer:String,currentCode:String,lockedServer:String?=null):PairingInput {
 val start=text.trimStart()
 if(!start.startsWith("{") && !start.startsWith("[")) return PairingInput.Accepted(currentServer,text)
 val payload=runCatching {Json.parseToJsonElement(text)}.getOrNull() as? JsonObject ?: return PairingInput.Invalid
 if(listOf("server","code").any {key ->payload[key]?.let {it !is JsonPrimitive || !it.isString}==true}) return PairingInput.Invalid
 val server=(payload["server"] as? JsonPrimitive)?.content
 val code=(payload["code"] as? JsonPrimitive)?.content
 if(lockedServer!=null && server?.trimEnd('/')!=lockedServer.trimEnd('/')) return PairingInput.DifferentServer
 return PairingInput.Accepted(server ?: currentServer,code ?: currentCode)
}
