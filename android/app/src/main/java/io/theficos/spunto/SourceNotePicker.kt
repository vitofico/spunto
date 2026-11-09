package io.theficos.spunto

import androidx.compose.foundation.layout.height
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.theficos.spunto.core.plugins.string
import kotlinx.serialization.json.*

private val sourceRecordFields=mapOf("thinking-desk" to "desk_id","context-packs" to "pack_id")

internal fun opensSourcePicker(plugin:String,command:String,args:JsonObject):Boolean =
 plugin in sourceRecordFields && command=="sources" && args.string("action") in setOf("","choose")

internal data class SourcePickerState(val selectedIds:Set<String>,val args:JsonObject,val maxSources:Int?) {
 val atLimit get()=maxSources?.let {selectedIds.size>=it}==true
 fun update(values:JsonObject)=JsonObject(args+values.filterKeys {it in setOf("remove","refresh")})
}

internal fun sourcePickerState(plugin:String,command:String,args:JsonObject,components:List<JsonObject>):SourcePickerState? {
 if(!opensSourcePicker(plugin,command,args) || args["picker"]!=JsonPrimitive(true)) return null
 val field=sourceRecordFields.getValue(plugin)
 val recordId=args.string(field).takeIf(String::isNotBlank) ?: return null
 val schema=components.firstOrNull {it.string("type")=="form"}?.get("schema") as? JsonObject ?: return null
 val properties=schema["properties"] as? JsonObject ?: return null
 fun default(name:String)=(properties[name] as? JsonObject)?.get("default")
 val ids=(default("selected_ids") as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content } ?: return null
 val revision=(default("expected_revision") as? JsonPrimitive)?.takeUnless(JsonPrimitive::isString)?.longOrNull?.takeIf {it>0} ?: return null
 val limit=(default("max_sources") as? JsonPrimitive)?.intOrNull?.takeIf {it>0}
 return SourcePickerState(ids.toSet(),buildJsonObject {
  put(field,recordId)
  put("expected_revision",revision)
  put("picker",true)
 },limit)
}

@Composable internal fun SourceNotePicker(
 model:SpuntoViewModel,state:SourcePickerState,session:NotePickerSession,online:Boolean,running:Boolean,
 refresh:Int,onQueryChanged:()->Unit,onAdd:(JsonObject)->Unit
) {
 if(state.atLimit) {
  Text(stringResource(R.string.core_picker_source_limit))
  return
 }
 val api=remember(model,session) {session.api(model)}
 NotePickerPanel(Modifier.height(390.dp),state.selectedIds,online,!running,state.args to refresh,
  search={query,cursor -> searchPickerNotes(api,query,cursor)},
  reportError={resources,error -> session.reportError(model,resources,error)},onQueryChanged=onQueryChanged) {selected ->
  Button(enabled=online && !running && selected!=null,onClick={
   selected?.let {note -> onAdd(JsonObject(state.args+mapOf("add_id" to JsonPrimitive(note.id),"add_version" to JsonPrimitive(note.serverRev))))}
  }) {Text(stringResource(R.string.core_picker_add_source))}
 }
}
