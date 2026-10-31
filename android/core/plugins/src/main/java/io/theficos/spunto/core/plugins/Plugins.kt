package io.theficos.spunto.core.plugins

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.theficos.spunto.core.design.Markdown
import kotlinx.serialization.json.*
import io.theficos.spunto.core.model.*

fun JsonObject.string(key:String)=(this[key] as? JsonPrimitive)?.contentOrNull.orEmpty()
fun JsonObject.bool(key:String,default:Boolean=false)=(this[key] as? JsonPrimitive)?.booleanOrNull ?: default
fun JsonObject.objects(key:String)=(this[key] as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()
class ManifestCache(context:Context,private val publications:PluginPublicationFence=processPluginPublications,namespace:String="",private val beforeStore:(JsonObject)->Unit={}) {
 private val preferences=context.getSharedPreferences(if(namespace.isEmpty()) "plugin-manifest" else "plugin-manifest-$namespace",Context.MODE_PRIVATE)
 @Volatile var value:JsonObject = runCatching { Json.parseToJsonElement(preferences.getString("manifest","{}")!!).jsonObject }.getOrDefault(JsonObject(emptyMap())); private set
 private var cacheOwner:PluginPublicationTicket?=null
 private fun storeLocked(json:JsonObject) {beforeStore(json);value=json;preferences.edit().putString("manifest",json.toString()).apply()}
 fun store(json:JsonObject)=publications.withCurrent {owner,current ->
  cacheOwner=owner;storeLocked(current?.manifest ?: json)
 }
 fun adopt(owner:PluginPublicationTicket,onStored:(PluginPublication)->Unit={}):PluginPublication?=publications.withCurrent {currentOwner,publication ->
  if(owner!=currentOwner || publication==null) null else {cacheOwner=owner;storeLocked(publication.manifest);onStored(publication);publication}
 }
 /** The complete synchronous surface callback shares the cache's committed-publication lock. */
 fun withCurrentPublication(block:()->Unit):Unit=publications.withCurrent {owner,publication ->
  if(publication!=null) {cacheOwner=owner;storeLocked(publication.manifest)}
  else if(cacheOwner!=owner) {cacheOwner=owner;storeLocked(JsonObject(emptyMap()))}
  block()
 }
 fun enabled(id:String):Boolean = plugins().firstOrNull { it.string("id")==id }?.let { it.bool("enabled",true) && it.string("state") !in setOf("disabled","off","failed","waiting","removed") } ?: (value["plugins_rev"]==null && id in setOf("dictation","quick-capture"))
 fun plugins():List<JsonObject> = value.objects("plugins")
 fun views():List<Pair<String,JsonObject>> = plugins().filter { enabled(it.string("id")) }.flatMap { plugin -> ((plugin["contributes"] as? JsonObject) ?: plugin).objects("views").map { plugin.string("id") to it } }.filter { (plugin,view) -> (value["hidden_views"] as? JsonArray)?.none { it.jsonPrimitive.content==plugin+"/"+view.string("id") } ?: true }
}
/** Closed vocabulary validation and depth bounded fallbacks match the shared web contract. */
private fun bounded(value:JsonElement?,maximum:Int):Boolean = value is JsonPrimitive && value.isString && value.content.codePointCount(0,value.content.length)<=maximum
private val vocabularyFields=mapOf("text" to setOf("text"),"markdown" to setOf("text","markdown","html"),"notes" to setOf("ids","items"),"pill" to setOf("suggestion_id","label","action"),"command" to setOf("id","args","title"),"form" to setOf("schema"),"diff" to setOf("hunks","base_version","text"))
fun safeComponent(value:JsonObject, depth:Int=0):JsonObject? {
 if(depth>3) return null
 val type=value.string("type")
 val known=vocabularyFields[type]
 val shape=known!=null && value.keys.all { it in known || it in setOf("type","fallback") }
 val valid=shape && when(type) {
  "text" -> bounded(value["text"],280)
  "markdown" -> bounded(value["text"] ?: value["markdown"],4000) && (value["html"]==null || bounded(value["html"],64000))
  "notes" -> ((value["ids"] ?: value["items"]) as? JsonArray)?.let { items -> items.size<=10 && items.all { item -> bounded(item,128) || item is JsonObject && item.keys.all { it in setOf("id","reason") } && bounded(item["id"],128) && (item["reason"]==null || bounded(item["reason"],280)) } } ?: false
  "pill" -> bounded(value["label"],40) && bounded(value["suggestion_id"],128) && bounded(value["action"],40)
  "command" -> bounded(value["title"],24) && bounded(value["id"],128) && (value["args"]==null || value["args"] is JsonObject)
  "form" -> validSchema(value["schema"])
  "diff" -> (value["base_version"] as? JsonPrimitive)?.intOrNull?.let { it>0 } == true && (value["hunks"] as? JsonArray)?.let { hunks -> hunks.size<=100 && hunks.all { hunk -> hunk is JsonObject && hunk.keys.all { it in setOf("old_start","new_start","old_lines","new_lines","lines") } && hunk.all { (key,item) -> if(key.endsWith("start")) (item as? JsonPrimitive)?.intOrNull!=null else item is JsonArray && item.size<=4000 && item.all { bounded(it,4000) } } } } == true
  else -> false
 }
 if(valid) return value
 val fallback=value["fallback"]
 return when { fallback is JsonObject -> safeComponent(fallback,depth+1); fallback is JsonPrimitive && fallback.content=="Open on the web" -> buildJsonObject { put("type","text"); put("text","Open on the web") }; else -> null }
}
fun validSchema(value:JsonElement?):Boolean {
 val schema=value as? JsonObject ?: return false
 if(schema.string("type")!="object") return false
 val allowed=setOf("type","properties","required","additionalProperties","title","description","enum","default","minimum","maximum","minLength","maxLength","items","format","x-secret","x-scope")
 if(schema.keys.any { it !in allowed }) return false
 val fields=schema["properties"] as? JsonObject ?: return schema["properties"]==null
 if(fields.size>50) return false
 val required=schema["required"]
 if(required!=null && (required !is JsonArray || required.any { it !is JsonPrimitive || !it.isString || it.content !in fields })) return false
 return fields.values.all { element ->
  val field=element as? JsonObject ?: return@all false
  val type=field.string("type")
  field.keys.all { it in allowed } && type in setOf("string","boolean","integer","number","array") &&
   (field["enum"]==null || field["enum"] is JsonArray && (field["enum"] as JsonArray).all { it is JsonPrimitive }) &&
   (type!="array" || (field["items"] as? JsonObject)?.let { it.string("type")=="string" && it.keys.all { key -> key in allowed } } == true) &&
   (field["format"]==null || field.string("format") in setOf("time","label")) &&
   (field["x-scope"]==null || field.string("x-scope")=="device") &&
   (field["x-secret"]==null || (field["x-secret"] as? JsonPrimitive)?.let { !it.isString && it.booleanOrNull!=null } == true) &&
   (!field.bool("x-secret") || type=="string" && field["default"]==null)
 }
}
@Composable fun Vocabulary(value:JsonObject,onNote:(String)->Unit={},onSuggestion:(String)->Unit={},onCommand:(String,JsonObject)->Unit={_,_->},online:Boolean=true,onForm:((JsonObject)->Unit)?=null,formInitial:JsonObject=JsonObject(emptyMap()),formHidden:Set<String> = emptySet(),noteReference:(@Composable (String,String)->Unit)?=null,proposalPill:(@Composable (JsonObject)->Unit)?=null) {
 val component=safeComponent(value) ?: return
 when(component.string("type")) {
  "text" -> Text(component.string("text"))
  "markdown" -> Markdown(component.string("text").ifEmpty { component.string("markdown") },machine=true,onLink={ if(it.startsWith("/n/")) onNote(it.substringAfter("/n/")) })
  "notes" -> Column {
   val entries=((component["ids"] ?: component["items"]) as? JsonArray).orEmpty()
   if(entries.isEmpty()) Text("No notes to show yet.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
   entries.forEach { item -> val id=if(item is JsonObject) item.string("id") else item.jsonPrimitive.content; val reason=(item as? JsonObject)?.string("reason").orEmpty();if(noteReference!=null) noteReference(id,reason) else TextButton(onClick={onNote(id)}) { Text(reason.ifEmpty { "Open note" }) } }
  }
  "pill" -> if(proposalPill!=null) proposalPill(component) else SuggestionChip(onClick={onSuggestion(component.string("suggestion_id"))},label={Text(component.string("label"))})
  "command" -> if(online) TextButton(onClick={onCommand(component.string("id"),component["args"] as? JsonObject ?: JsonObject(emptyMap()))}) { Text(component.string("title")) }
  "form" -> SchemaForm(component["schema"]!!.jsonObject,initial=formInitial,preferDefaults=true,readOnly=!online || onForm==null,readOnlyMessage=if(!online) "Changes need a connection." else "Open on the web",saveLabel="Run",hiddenFields=formHidden+commandFormHiddenFields(component["schema"]!!.jsonObject),onSave={onForm?.invoke(it)})
  "diff" -> Column(verticalArrangement=Arrangement.spacedBy(12.dp)) {
   Text("Based on version ${component["base_version"]}",style=MaterialTheme.typography.bodySmall)
   component.objects("hunks").forEachIndexed { index,hunk ->
    if(index>0) HorizontalDivider()
    val (before,after)=diffHunkTexts(hunk)
    TextChangePreview(before,after,allowFullText=false)
   }
  }
 }
}
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable fun SchemaForm(schema:JsonObject,initial:JsonObject=JsonObject(emptyMap()),readOnly:Boolean=false,pluginId:String="",readOnlyMessage:String="Changes need a connection.",saveLabel:String="Save settings",hiddenFields:Set<String> = emptySet(),preferDefaults:Boolean=false,onSave:(JsonObject)->Unit={}) {
 val fields=(schema["properties"] as? JsonObject).orEmpty()
 val values=remember(schema,initial,preferDefaults) { mutableStateMapOf<String,JsonElement>().apply { putAll(formInitialValues(schema,initial,preferDefaults)) } }
 var error by remember(schema,initial) { mutableStateOf("") }
 Column(verticalArrangement=Arrangement.spacedBy(12.dp)) {
  if(readOnly) Text(readOnlyMessage)
  schema.string("description").takeIf(String::isNotBlank)?.let {Text(it,style=MaterialTheme.typography.bodySmall)}
  fields.forEach { (key,raw) ->
   val field=raw as? JsonObject ?: return@forEach
   if(key in hiddenFields) return@forEach
   val label=field.string("title").ifEmpty { key.replace('_',' ').replaceFirstChar { it.uppercase() } }
   val value=values[key] ?: field["default"] ?: JsonPrimitive("")
   if(field.bool("x-secret")) { Text("$label · Set in the environment"); Text("SPUNTO_PLUGIN_${pluginId.replace('-','_').uppercase()}_${key.uppercase()}"); return@forEach }
   if(field.string("x-scope")=="device") Text("This phone only",style=MaterialTheme.typography.labelSmall)
   val choices=field["enum"] as? JsonArray
   when {
    choices!=null -> {FormChoiceField(label,choices,value,!readOnly,language=key=="language") {values[key]=it};field.string("description").takeIf(String::isNotBlank)?.let {Text(it,style=MaterialTheme.typography.bodySmall)}}
    field.string("type")=="boolean" -> Row(Modifier.fillMaxWidth().heightIn(min=48.dp),horizontalArrangement=Arrangement.spacedBy(12.dp),verticalAlignment=Alignment.CenterVertically) {
     Text(label,modifier=Modifier.weight(1f))
     Switch(checked=value.jsonPrimitive.booleanOrNull ?: false,onCheckedChange={values[key]=JsonPrimitive(it)},enabled=!readOnly,modifier=Modifier.sizeIn(minWidth=48.dp,minHeight=48.dp).semantics {contentDescription=label})
    }
    else -> OutlinedTextField(value=if(value is JsonArray) value.joinToString(", ") { it.jsonPrimitive.content } else value.jsonPrimitive.content,onValueChange={text -> values[key]=JsonPrimitive(text)},label={Text(label)},enabled=!readOnly,minLines=if(field.string("type")=="string" && ((field["maxLength"] as? JsonPrimitive)?.intOrNull ?: 0)>280) 3 else 1,modifier=Modifier.fillMaxWidth(),supportingText={Text(field.string("description"))})
   }
  }
  if(error.isNotEmpty()) Text(error,color=MaterialTheme.colorScheme.error)
  if(!readOnly && fields.isNotEmpty()) Button(onClick={runCatching { formArguments(schema,JsonObject(values.toMap())) }.onSuccess { error="";onSave(it) }.onFailure { error=it.message ?: "Check the form fields." }}) { Text(saveLabel) }
 }
}
data class ViewNote(val id:String,val body:String,val createdAt:Long,val pinned:Boolean=false,val archived:Boolean=false,val trashed:Boolean=false,val source:String="owner",val latestAgent:Boolean=false,val sourceRef:String="",val members:Set<String> = emptySet(),val labels:Set<String> = emptySet(),val origin:String="owner")
fun matchesView(note:ViewNote,filter:JsonObject,now:Long):Boolean = runCatching {
 if(note.trashed) return false
 val accepted=setOf("labels","no_labels","open_tasks","pinned","archived","source","member","not_member","created_within_days","days")
 if(filter.keys.any { it !in accepted }) return false
 filter.all { (key,value) -> when(key) {
  "labels" -> (value as? JsonArray)?.all { target -> val path=io.theficos.spunto.core.model.labelKey(target.jsonPrimitive.content);note.labels.any { it==path || it.startsWith("$path/") } } ?: false
  "no_labels" -> note.labels.isEmpty()==value.jsonPrimitive.boolean
  "open_tasks" -> io.theficos.spunto.core.design.hasOpenTasks(note.body)==value.jsonPrimitive.boolean
  "pinned" -> note.pinned==value.jsonPrimitive.boolean
  "archived" -> note.archived==value.jsonPrimitive.boolean
  "source" -> when(val source=value.jsonPrimitive.content) { "owner" -> note.origin=="owner";"agent" -> note.source=="agent" || note.latestAgent; else -> if(source.startsWith("plugin:")) note.sourceRef==source.substringAfter(':') else note.source==source }
  "member" -> value.jsonPrimitive.content in note.members
  "not_member" -> value.jsonPrimitive.content !in note.members
  "days","created_within_days" -> { val days=value.jsonPrimitive.long;days in 1L..36500L && note.createdAt>=java.time.Instant.ofEpochMilli(now).atZone(java.time.ZoneOffset.UTC).toLocalDate().atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli()-(days-1)*86400000 }
  else -> false
 } }
}.getOrDefault(false)
