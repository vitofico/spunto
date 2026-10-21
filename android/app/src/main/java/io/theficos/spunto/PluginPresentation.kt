package io.theficos.spunto

import io.theficos.spunto.core.plugins.*
import io.theficos.spunto.data.sync.ApiException
import kotlinx.serialization.json.*

internal data class NoteTool(val pluginId:String,val pluginName:String,val id:String,val title:String,val description:String)

/** A command is listed once, even if the manifest repeats a surface contribution. */
internal fun noteTools(plugins:List<JsonObject>,privateNote:Boolean=false):List<NoteTool> = plugins.filter {
 it.bool("enabled",true) && it.string("state") !in setOf("disabled","off","failed","waiting","removed")
 && (!privateNote || (it["permissions"] as? JsonArray)?.all {permission ->
  val name=(permission as? JsonPrimitive)?.takeIf {value ->value.isString}?.content
  name!=null && !name.startsWith("ai.") && !name.startsWith("net.")
 }==true)
}.sortedBy {(it["position"] as? JsonPrimitive)?.intOrNull ?: Int.MAX_VALUE}.flatMap {plugin ->
 (plugin["contributes"] as? JsonObject)?.objects("commands").orEmpty().take(3).filter {command ->
  (command["where"] as? JsonArray)?.any {(it as? JsonPrimitive)?.content=="note-actions"}==true
 }.map {NoteTool(plugin.string("id"),plugin.string("name"),it.string("id"),it.string("title").ifBlank {plugin.string("name")},plugin.string("description"))}
}.distinctBy {it.pluginId to it.id}

/** The server's own words for a refused request: its problem detail is safe owner guidance (docs/api.md), and it knows
 * what the web app and this phone can each do. A reply without one, from a proxy in front of it say, gets a line from its status. */
internal fun apiErrorMessage(error:ApiException):String = error.detail.ifBlank { when {
 error.status==401 -> "Your session expired. Sign in again, then retry."
 error.status==504 -> "The server took too long. Try again."
 error.status==429 -> "The server is busy. Wait a moment, then retry."
 else -> "The server couldn't finish this. Try again."
} }
