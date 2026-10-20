package io.theficos.spunto

import io.theficos.spunto.core.plugins.*
import kotlinx.serialization.json.*

internal data class NoteTool(val pluginId:String,val pluginName:String,val id:String,val title:String,val description:String)

/** A command is listed once, even if the manifest repeats a surface contribution. */
internal fun noteTools(plugins:List<JsonObject>):List<NoteTool> = plugins.filter {
 it.bool("enabled",true) && it.string("state") !in setOf("disabled","off","failed","waiting","removed")
}.sortedBy {(it["position"] as? JsonPrimitive)?.intOrNull ?: Int.MAX_VALUE}.flatMap {plugin ->
 (plugin["contributes"] as? JsonObject)?.objects("commands").orEmpty().take(3).filter {command ->
  (command["where"] as? JsonArray)?.any {(it as? JsonPrimitive)?.content=="note-actions"}==true
 }.map {NoteTool(plugin.string("id"),plugin.string("name"),it.string("id"),it.string("title").ifBlank {plugin.string("name")},plugin.string("description"))}
}.distinctBy {it.pluginId to it.id}

/** Safe guidance from stable error codes; provider messages and note data never reach the UI. */
internal fun pluginCommandErrorMessage(status:Int,code:String):String = when {
 status==401 -> "Your session expired. Sign in again, then retry."
 code=="vectors_unavailable" -> "AI search is still preparing. Check indexing in Settings → AI, then retry."
 code in setOf("ai_capability_unbound","ai_configuration_changed","ai_provider_key_missing","ai_model_invalid","ai_profile_endpoint_changed") -> "AI needs attention in Settings → AI. Check the selected model and provider, then retry."
 code=="ai_selection_changed" -> "The AI model changed during this request. Run the tool again."
 code in setOf("ai_provider_timeout","plugin_timeout") || status==504 -> "The AI model took too long. Try again, or choose a faster model in Settings → AI."
 code in setOf("ai_provider_unreachable","ai_provider_http_error","ai_inventory_unavailable") -> "The AI provider is unavailable. Check its connection and model in Settings → AI."
 code.startsWith("ai_invalid_") || code=="plugin_output_invalid" -> "The AI model returned an unusable answer. Try again, or choose another model in Settings → AI."
 code in setOf("ai_note_refused","ai_external_refused","ai_image_refused","ai_audio_refused","ai_answer_refused") -> "This content could not be sent to the selected AI model. Check privacy and AI settings."
 status==429 -> "This tool is busy or has reached its limit. Wait a moment, then retry."
 status==409 -> "The note or plugin changed. Sync, then run the tool again."
 status==404 -> "This tool is no longer available. Sync to refresh your plugins."
 status==403 -> "This tool needs owner access or an updated permission review in Settings → Plugins."
 status==422 -> "The tool could not use these inputs. Check your choices and try again."
 status>=500 -> "The tool could not finish on the server. Try again; details are available in Status."
 else -> "The tool could not finish ($code). Try again or check Status."
}
