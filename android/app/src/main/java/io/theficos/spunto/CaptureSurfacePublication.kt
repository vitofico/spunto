package io.theficos.spunto

import io.theficos.spunto.core.model.Note
import io.theficos.spunto.core.plugins.ManifestCache
import io.theficos.spunto.core.plugins.string
import io.theficos.spunto.data.local.PluginOutputEntity
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

/** Selection and the synchronous widget write must share the current manifest's publication lock. */
internal fun ManifestCache.withCaptureSurfacePublication(notes:List<Note>,outputs:List<PluginOutputEntity>,updateManifest:()->Unit,cacheSlip:(String?,String?)->Unit) {
 withCurrentPublication {
  updateManifest()
  val slip=outputs.firstOrNull { output -> val annotation=runCatching { Json.parseToJsonElement(output.payload).jsonObject }.getOrNull();output.kind=="annotation" && annotation?.string("kind")=="slip" && enabled(annotation.string("plugin")) && notes.any { it.id==output.noteId && !it.privateNote && !it.trashed } }
  val note=notes.firstOrNull { it.id==slip?.noteId }
  cacheSlip(note?.id,note?.body?.take(280))
 }
}
