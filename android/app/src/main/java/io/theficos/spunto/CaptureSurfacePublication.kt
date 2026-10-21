package io.theficos.spunto

import io.theficos.spunto.core.model.Note
import io.theficos.spunto.core.plugins.ManifestCache
import io.theficos.spunto.core.plugins.string
import io.theficos.spunto.data.local.PluginOutputEntity
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

/** The day's slip and its note, for the timeline and the widget alike: a slip from an enabled plugin on a note that is
 * not private, archived or trashed, as the server's /slip chooses, so archiving the note takes the slip away and Undo brings it back. */
internal fun dailySlip(outputs:List<PluginOutputEntity>,notes:List<Note>,enabled:(String)->Boolean,hidden:(PluginOutputEntity)->Boolean={false}):Pair<PluginOutputEntity,Note>? =
 outputs.filter { output -> val annotation=runCatching { Json.parseToJsonElement(output.payload).jsonObject }.getOrNull();output.kind=="annotation" && annotation?.string("kind")=="slip" && enabled(annotation.string("plugin")) && !hidden(output) }
  .firstNotNullOfOrNull { output -> notes.firstOrNull { it.id==output.noteId && !it.privateNote && !it.archived && !it.trashed }?.let { output to it } }
/** Selection and the synchronous widget write must share the current manifest's publication lock. */
internal fun ManifestCache.withCaptureSurfacePublication(notes:List<Note>,outputs:List<PluginOutputEntity>,updateManifest:()->Unit,cacheSlip:(String?,String?,Long?)->Unit) {
 withCurrentPublication {
  updateManifest()
  val note=dailySlip(outputs,notes,::enabled)?.second
  cacheSlip(note?.id,note?.body?.take(280),note?.createdAt)
 }
}
