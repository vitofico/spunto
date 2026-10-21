package io.theficos.spunto

import io.theficos.spunto.core.plugins.validSchema
import kotlinx.serialization.json.*
import java.net.URI
import java.security.MessageDigest

internal fun JsonObject.pluginText(key:String):String?=(this[key] as? JsonPrimitive)?.takeIf {it.isString}?.content
internal fun JsonObject.pluginStrings(key:String):List<String>? {
 val array=this[key] as? JsonArray ?: return null
 return array.map { (it as? JsonPrimitive)?.takeIf {value -> value.isString}?.content ?: return null }
}
internal fun JsonObject.pluginFlag(key:String):Boolean?=(this[key] as? JsonPrimitive)?.takeUnless {it.isString}?.booleanOrNull
internal fun JsonObject.pluginLong(key:String):Long?=(this[key] as? JsonPrimitive)?.takeUnless {it.isString || !Regex("[0-9]+").matches(it.content)}?.longOrNull
internal val pluginIdPattern=Regex("[a-z][a-z0-9-]{1,23}")
internal val pluginGroups=listOf("capture","organize","recall","develop","agents","data")
internal enum class EnvironmentPin { Managed, Unmanaged, Unknown }
internal fun environmentPin(value:JsonElement?):EnvironmentPin {
 val primitive=value as? JsonPrimitive ?: return EnvironmentPin.Unknown
 if(primitive.isString) return EnvironmentPin.Unknown
 return when(primitive.content) { "true","1" -> EnvironmentPin.Managed; "false","0" -> EnvironmentPin.Unmanaged; else -> EnvironmentPin.Unknown }
}
internal data class NativePluginFacts(val raw:JsonObject,private val manifestActive:Boolean=false) {
 val id=raw.pluginText("id").orEmpty()
 val name=raw.pluginText("name") ?: id
 val description=raw.pluginText("description").orEmpty()
 val category=if("category" !in raw) "data" else raw.pluginText("category").orEmpty()
 val state=raw.pluginText("state").orEmpty()
 val removed=state=="removed"
 val wantedOn=!removed && raw.pluginText("switch")=="on"
 val permissions=raw.pluginStrings("permissions")
 val granted=raw.pluginStrings("granted")
 val pin=environmentPin(raw["pinned_by_env"])
 val valid=pluginIdPattern.matches(id) && raw.pluginText("api") in setOf("0.4","0.9","1.0") && category in pluginGroups && raw.pluginText("kind") in setOf("server","device") && raw.pluginText("switch") in setOf("on","off") && state in setOf("available","enabled","waiting","failed","removed","disabled") && permissions!=null && permissions.distinct().size==permissions.size && granted!=null && granted.distinct().size==granted.size
 val activeContribution get()=valid && wantedOn && state=="enabled" && manifestActive
 val canEnable=valid && !removed && pin==EnvironmentPin.Unmanaged && raw.pluginFlag("can_enable")==true
 val canDisable=valid && !removed && wantedOn && pin==EnvironmentPin.Unmanaged
 val canDeleteData=valid && raw.pluginFlag("can_delete_data")==true
 val permissionReviewRequired=wantedOn && permissions!=null && granted!=null && !granted.toSet().containsAll(permissions)
 val origin=when(raw.pluginText("origin")) { "bundled" -> "Built in"; "operator" -> "Third party"; else -> "Origin unavailable" }
 val contribution=if(removed) JsonObject(emptyMap()) else raw["contributes"] as? JsonObject ?: JsonObject(emptyMap())
 val facts:List<String> get()=pluginDestinationFacts(this)+pluginScheduleFacts(this)
 val permissionLines:List<String> get()=raw.pluginStrings("permission_sentences")?.takeIf {it.isNotEmpty()} ?: permissions.orEmpty().map(::pluginPermissionSentence)
 val slots:List<String> get()=contribution.keys.mapNotNull { when(it) {
  "suggestions" -> "Suggestions under notes and in Review"; "review" -> "Review"; "panel" -> "About this note"; "meta" -> "Note details"; "slip" -> "Daily slip"; "commands" -> "Commands"; "views" -> "Views"; "ranking" -> "Search ranking"; "mcp_tools" -> "Agent tools"; "schedules" -> "Scheduled runs"; else -> null
 } }.distinct()
}
internal data class NativePluginCatalog(val valid:Boolean,val revision:Long,val rows:List<NativePluginFacts>) {
 val onCount get()=rows.count {it.wantedOn}
}
internal fun nativePluginCatalog(catalog:JsonObject,manifest:JsonObject):NativePluginCatalog {
 val elements=catalog["plugins"] as? JsonArray ?: return NativePluginCatalog(false,-1,emptyList())
 val revision=catalog.pluginLong("plugins_rev") ?: return NativePluginCatalog(false,-1,emptyList())
 val active=(manifest["plugins"] as? JsonArray).orEmpty().mapNotNull {it as? JsonObject}.filter {it.pluginFlag("enabled")!=false && it.pluginText("state") !in setOf("waiting","failed","removed","disabled","off")}.mapNotNull {it.pluginText("id")}.toSet()
 val rows=elements.map {element -> val row=element as? JsonObject ?: return NativePluginCatalog(false,revision,emptyList());NativePluginFacts(row,row.pluginText("id") in active)}.filter {it.raw.pluginText("kind")!="theme"}
 if(rows.map {it.id}.distinct().size!=rows.size) return NativePluginCatalog(false,revision,emptyList())
 return NativePluginCatalog(true,revision,rows)
}
internal fun pluginPermissionSentence(permission:String):String=when(permission) {
 "notes.read" -> "Reads non-private notes"; "notes.create" -> "Creates notes"; "notes.private" -> "Reads private notes"; "attachments.read" -> "Reads attachments"; "attachments.write" -> "Writes attachments"; "suggest" -> "Proposes edits for your review"; "annotate" -> "Adds note details"; "net.fetch" -> "Fetches the pages you link to"; "schedule" -> "Runs on its declared schedule when enabled"; "mcp" -> "Exposes its declared agent tools"; else -> when {
 permission.startsWith("ai.") -> "Uses ${permission.removePrefix("ai.")} AI"; permission.startsWith("net.out:") -> "Talks to ${permission.removePrefix("net.out:")}"; else -> permission
 }
}
/** Permission sentences read as what the plugin "will" do: "Reads your notes" becomes "Read your notes", "Never reads" becomes "Never read". */
internal fun pluginWillSentence(sentence:String):String {
 if(sentence.startsWith("Never ")) return "Never "+pluginWillSentence(sentence.removePrefix("Never "))
 val verb=sentence.substringBefore(' ');val rest=sentence.substring(verb.length)
 val base=when {
  verb.length<4 || !verb.endsWith("s") || verb.endsWith("ss") -> verb
  Regex("(ch|sh|x|ss)es$").containsMatchIn(verb) -> verb.dropLast(2)
  else -> verb.dropLast(1)
 }
 return base+rest
}
/** The AI capability a waiting plugin is missing a model for, from the host's destinations; null when nothing is missing. */
internal fun pluginMissingModel(plugin:NativePluginFacts):String? {
 val destinations=plugin.raw["ai_destinations"] as? JsonObject
 return plugin.permissions.orEmpty().filter {it.startsWith("ai.")}.map {it.removePrefix("ai.")}.firstOrNull {(destinations?.get(it) as? JsonObject)?.pluginText("model").isNullOrBlank()}
}
internal fun aiModelName(capability:String)=when(capability) { "chat" -> "a chat model"; "embed" -> "an embedding model"; "vision" -> "a vision model"; "transcribe" -> "a transcription model"; else -> "an AI model" }
/** The waiting line in the switch's place (03 §6.4): "Needs a chat model". */
internal fun pluginWaitingLine(plugin:NativePluginFacts):String = pluginMissingModel(plugin)?.let {"Needs ${aiModelName(it)}"} ?: "Waiting for a requirement"
internal fun pluginDestinationFacts(plugin:NativePluginFacts):List<String> {
 if(plugin.removed) return listOf("Removed · retained access history only")
 val result=mutableListOf<String>()
 val destinations=plugin.raw["ai_destinations"] as? JsonObject ?: JsonObject(emptyMap())
 val capabilities=mapOf("chat" to "Chat","embed" to "Embeddings","vision" to "Vision","transcribe" to "Transcription")
 plugin.permissions.orEmpty().filter {it.startsWith("ai.")}.forEach {permission ->
  val capability=permission.removePrefix("ai.");val label=capabilities[capability] ?: return@forEach
  val destination=destinations[capability] as? JsonObject
  val model=destination?.pluginText("model");val host=destination?.pluginText("host");val outside=destination?.pluginFlag("leaves_network")
  result+=if(!model.isNullOrBlank() && !host.isNullOrBlank() && outside!=null) "$label: note text to $model at $host, ${if(outside) "outside your network" else "on your network"}" else "$label: no model is set up"
 }
 if("net.fetch" in plugin.permissions.orEmpty()) result+="Fetches the linked page from your server"
 val network=plugin.raw.pluginStrings("network_destinations").orEmpty().filter {it.isNotBlank()}.distinct()
 network.forEach {result+="Talks to $it"}
 if(plugin.permissions.orEmpty().any {it.startsWith("net.out:")} && network.isEmpty()) result+="Outbound network destinations are unavailable"
 if(result.isEmpty()) result+=if(plugin.id=="dictation" && plugin.raw.pluginText("kind")=="device") "Recognition uses this phone or its speech provider, according to this phone’s setting" else if(plugin.raw.pluginText("kind")=="device") "Stays on the phone" else "Stays on your server"
 return result
}
internal fun pluginScheduleFacts(plugin:NativePluginFacts):List<String> {
 val settings=plugin.raw["settings"] as? JsonObject ?: JsonObject(emptyMap())
 fun resolve(value:String?):String?=value?.let { if(Regex("\\{[a-z][a-z0-9_]*\\}").matches(it)) settings.pluginText(it.substring(1,it.length-1)) else it }
 return (plugin.contribution["schedules"] as? JsonArray).orEmpty().mapNotNull {it as? JsonObject}.map {schedule ->
  val day=resolve(schedule.pluginText("weekday"));val time=resolve(schedule.pluginText("time"))
  if(day in setOf("monday","tuesday","wednesday","thursday","friday","saturday","sunday") && time!=null && Regex("(?:[01][0-9]|2[0-3]):[0-5][0-9]").matches(time)) "${day!!.replaceFirstChar {it.uppercase()}} $time (server time) · pauses while off or waiting" else "Schedule details unavailable"
 }
}
internal data class NativePluginPage(val schema:JsonObject=JsonObject(emptyMap()),val values:JsonObject=JsonObject(emptyMap()),val secrets:JsonObject=JsonObject(emptyMap())) {
 fun json():JsonObject=buildJsonObject {put("schema",schema);put("values",values);put("secrets",secrets)}
}
internal fun safePluginPage(raw:JsonObject):NativePluginPage {
 val schema=raw["schema"] as? JsonObject ?: return NativePluginPage()
 if(!validSchema(schema)) return NativePluginPage()
 val fields=schema["properties"] as? JsonObject ?: JsonObject(emptyMap())
 val values=raw["values"] as? JsonObject ?: JsonObject(emptyMap())
 val secrets=raw["secrets"] as? JsonObject ?: JsonObject(emptyMap())
 val publicValues=fields.mapNotNull { (key,value) -> val field=value as? JsonObject ?: return@mapNotNull null
  if(field.pluginFlag("x-secret")==true) return@mapNotNull null
  val item=values[key] ?: field["default"] ?: return@mapNotNull null
  val valid=when(field.pluginText("type")) { "string" -> item is JsonPrimitive && item.isString; "boolean" -> item is JsonPrimitive && !item.isString && item.booleanOrNull!=null; "integer" -> item is JsonPrimitive && !item.isString && Regex("-?[0-9]+").matches(item.content); "number" -> item is JsonPrimitive && !item.isString && item.doubleOrNull?.isFinite()==true; "array" -> item is JsonArray && item.all {it is JsonPrimitive && it.isString};else -> false }
  if(valid) key to item else null
 }.toMap()
 val statuses=fields.filter {(_,value) -> (value as? JsonObject)?.pluginFlag("x-secret")==true}.mapValues { (key,_) -> buildJsonObject { (secrets[key] as? JsonObject)?.pluginFlag("set")?.let {put("set",it)};put("source","environment") } }
 return NativePluginPage(schema,JsonObject(publicValues),JsonObject(statuses))
}
internal fun catalogPluginPage(plugin:NativePluginFacts):NativePluginPage {
 if(plugin.removed) return NativePluginPage()
 val settings=plugin.raw["settings"] as? JsonObject ?: JsonObject(emptyMap())
 val statuses=plugin.raw["secrets"] as? JsonObject ?: JsonObject(emptyMap())
 val fields=buildJsonObject {
  settings.forEach { (key,value) ->
   if(key in statuses || !Regex("[a-z][a-z0-9_]{0,63}").matches(key)) return@forEach
   val type=when {value is JsonPrimitive && value.isString -> "string";value is JsonPrimitive && value.booleanOrNull!=null -> "boolean";value is JsonPrimitive && value.longOrNull!=null -> "integer";value is JsonPrimitive && value.doubleOrNull?.isFinite()==true -> "number";value is JsonArray && value.all {it is JsonPrimitive && it.isString} -> "array";else -> return@forEach}
   put(key,buildJsonObject {put("type",type);if(type=="array") put("items",buildJsonObject {put("type","string")})})
  }
  statuses.keys.filter {Regex("[a-z][a-z0-9_]{0,63}").matches(it)}.forEach {key -> put(key,buildJsonObject {put("type","string");put("x-secret",true)})}
 }
 return safePluginPage(buildJsonObject {put("schema",buildJsonObject {put("type","object");put("properties",fields)});put("values",settings);put("secrets",statuses)})
}
internal fun pluginPageCacheKey(server:String,plugin:NativePluginFacts):String {
 val identity="$server\n${plugin.id}\n${plugin.raw.pluginText("api")}\n${plugin.raw.pluginText("version")}"
 return "native-plugin-page-"+MessageDigest.getInstance("SHA-256").digest(identity.toByteArray()).joinToString("") {"%02x".format(it)}
}
internal fun pluginWebUrl(server:String,httpConsent:Boolean,path:String):String?=runCatching {
 val root=URI(server);require(root.scheme in setOf("http","https") && root.host!=null && root.rawUserInfo==null && root.rawQuery==null && root.rawFragment==null && root.rawPath in setOf("","/"))
 require(root.scheme=="https" || httpConsent)
 require(path in setOf("/settings/plugins","/settings/ai","/settings/devices","/status") || path.startsWith("/settings/plugins/") && pluginIdPattern.matches(path.removePrefix("/settings/plugins/")))
 server.trimEnd('/')+path
}.getOrNull()
internal data class PluginUndo(val pluginId:String,val server:String,val permissions:List<String>,val grants:Set<String>,val expiresAt:Long)
internal fun undoPermissions(undo:PluginUndo,current:NativePluginFacts,server:String,now:Long):List<String>? {
 if(now>=undo.expiresAt || undo.server!=server || current.id!=undo.pluginId || current.wantedOn || !current.canEnable) return null
 val permissions=current.permissions ?: return null
 return permissions.takeIf {it.toSet()==undo.permissions.toSet() && undo.grants.containsAll(it) && current.granted?.toSet()?.containsAll(it)==true}
}
internal fun pluginAiSummary(ai:JsonObject):List<String> {
 val profiles=(ai["profiles"] as? JsonArray).orEmpty().mapNotNull {it as? JsonObject}
 val bindings=ai["bindings"] as? JsonObject ?: JsonObject(emptyMap())
 return listOf("chat" to "Chat","embed" to "Embeddings").map { (cap,label) ->
  val profile=profiles.firstOrNull {it.pluginText("name")==bindings.pluginText(cap)}
  val model=profile?.pluginText("${cap}_model");val host=profile?.pluginText("base_url")?.let {runCatching {URI(it).host}.getOrNull()}
  val outside=(profile?.get("leaves_network") as? JsonObject)?.pluginFlag(cap)
  if(!model.isNullOrBlank() && !host.isNullOrBlank() && outside!=null) "$label: $model at $host, ${if(outside) "outside your network" else "on your network"}" else "$label: no model is set up"
 }
}
