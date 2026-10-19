package io.theficos.spunto.core.plugins

import kotlinx.serialization.json.*

fun commandFormHiddenFields(schema:JsonObject):Set<String> = (schema["properties"] as? JsonObject).orEmpty().filter { (key,field) ->
 key in setOf("request_id","note_id","pack_id","decision_id","expected_revision","base_version","revision","source_choices","removal_choices") && (field as? JsonObject)?.get("default")!=null
}.keys

/** Server-returned command forms carry fresh defaults; settings keep saved values first. */
fun formInitialValues(schema:JsonObject,initial:JsonObject,preferDefaults:Boolean=false):JsonObject=buildJsonObject {
 (schema["properties"] as? JsonObject).orEmpty().forEach { (key,raw) ->
  val fallback=(raw as? JsonObject)?.get("default")
  (if(preferDefaults) fallback ?: initial[key] else initial[key] ?: fallback)?.let { put(key,it) }
 }
}

/** Stored annotations, live results and bare component lists use one bounded renderer. */
fun outputComponents(value:JsonElement?,depth:Int=0):List<JsonObject> {
 if(depth>3) return emptyList()
 return when(value) {
  is JsonArray -> if(value.size<=10) value.mapNotNull { (it as? JsonObject)?.let(::safeComponent) } else emptyList()
  is JsonObject -> when {
   value["type"]!=null -> listOfNotNull(safeComponent(value))
   value["payload"]!=null -> outputComponents(value["payload"],depth+1)
   value["ui"] is JsonObject || value["ui"] is JsonArray -> outputComponents(value["ui"],depth+1)
   value["ui"]!=null && value["ui"]!=JsonPrimitive(1) -> emptyList()
   else -> outputComponents(value["items"] ?: value["components"],depth+1)
  }
  else -> emptyList()
 }
}

/** Parse only on submission so intermediate number input never silently becomes zero. */
fun formArguments(schema:JsonObject,values:JsonObject):JsonObject {
 require(validSchema(schema)) { "This form needs a newer app." }
 val fields=(schema["properties"] as? JsonObject).orEmpty()
 require(values.keys.all { it in fields }) { "Check the form fields." }
 val required=(schema["required"] as? JsonArray)?.map { it.jsonPrimitive.content }?.toSet().orEmpty()
 return buildJsonObject {
  fields.forEach { (key,raw) ->
   val field=raw.jsonObject
   if(field.bool("x-secret")) return@forEach
   val title=field.string("title").ifEmpty { key.replace('_',' ') }
   val candidate=values[key] ?: field["default"]
   require(candidate!=null || key !in required) { "Choose $title." }
   if(candidate==null) return@forEach
   val result:JsonElement=when(field.string("type")) {
    "string" -> { require(candidate is JsonPrimitive && candidate.isString) { "Check $title." };candidate }
    "boolean" -> { require(candidate is JsonPrimitive && !candidate.isString && candidate.booleanOrNull!=null) { "Check $title." };candidate }
    "integer" -> { val number=(candidate as? JsonPrimitive)?.content?.toLongOrNull();require(number!=null) { "Enter a whole number for $title." };JsonPrimitive(number) }
    "number" -> { val number=(candidate as? JsonPrimitive)?.content?.toDoubleOrNull();require(number!=null && number.isFinite()) { "Enter a number for $title." };JsonPrimitive(number) }
    "array" -> { val list=if(candidate is JsonArray) candidate.map { require(it is JsonPrimitive && it.isString) { "Check $title." };it.content } else { require(candidate is JsonPrimitive && candidate.isString) { "Check $title." };candidate.content.split(',') };JsonArray(list.map(String::trim).filter(String::isNotEmpty).distinct().map(::JsonPrimitive)) }
    else -> error("Unsupported form field.")
   }
   val choices=field["enum"] as? JsonArray
   require(choices==null || result in choices) { "Choose $title." }
   if(result is JsonPrimitive && field.string("type") in setOf("integer","number")) {
    val number=result.double
    require(number >= ((field["minimum"] as? JsonPrimitive)?.doubleOrNull ?: Double.NEGATIVE_INFINITY) && number <= ((field["maximum"] as? JsonPrimitive)?.doubleOrNull ?: Double.POSITIVE_INFINITY)) { "$title is outside its range." }
   }
   if(field.string("type")=="string") {
    val text=result.jsonPrimitive.content
    val length=text.codePointCount(0,text.length)
    require(length>=((field["minLength"] as? JsonPrimitive)?.intOrNull ?: 0) && length<=((field["maxLength"] as? JsonPrimitive)?.intOrNull ?: 100000)) { "Check the length of $title." }
    require(field.string("format")!="time" || Regex("(?:[01]\\d|2[0-3]):[0-5]\\d").matches(text)) { "Use HH:MM for $title." }
   }
   put(key,result)
  }
 }
}
