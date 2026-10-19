package io.theficos.spunto

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.*
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable internal fun PluginHeading(text:String) {Text(text,style=MaterialTheme.typography.titleSmall,modifier=Modifier.semantics {heading()})}
@Composable internal fun NativePluginRow(plugin:NativePluginFacts,online:Boolean,busy:Boolean,onToggle:(Boolean)->Unit,onSettings:()->Unit,onReview:()->Unit,onWeb:(String)->Unit) {
 Column(verticalArrangement=Arrangement.spacedBy(4.dp)) {
  Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
   Column(Modifier.weight(1f)) {
    Text(plugin.name,style=MaterialTheme.typography.titleMedium)
    if(plugin.raw.pluginText("kind")=="device") Text("Android",style=MaterialTheme.typography.labelSmall)
    if(plugin.raw.pluginText("default")=="on-with-ai") Text("Default with AI",style=MaterialTheme.typography.labelSmall)
    Text(plugin.description)
   }
   when {
    plugin.removed -> Text("Removed")
    plugin.pin==EnvironmentPin.Managed -> Text("Set in the environment · ${if(plugin.wantedOn) "On" else "Off"}",Modifier.widthIn(max=160.dp))
    plugin.pin==EnvironmentPin.Unknown || !plugin.valid -> Text("Switch unavailable")
    else -> Switch(plugin.wantedOn,onToggle,enabled=online && !busy && (plugin.canDisable || plugin.canEnable),modifier=Modifier.semantics {contentDescription="${plugin.name} wanted on"})
   }
  }
  plugin.facts.forEach {Text(it,style=MaterialTheme.typography.bodySmall)}
  if(plugin.removed && plugin.pin==EnvironmentPin.Managed) Text("Set in the environment · Off",style=MaterialTheme.typography.bodySmall)
  if(plugin.removed) plugin.raw.pluginLong("data_count")?.let {Text("$it records kept",style=MaterialTheme.typography.bodySmall)}
  if(plugin.state=="waiting") {Text("Waiting · requirements are missing",style=MaterialTheme.typography.bodySmall);TextButton(onClick={onWeb("/settings/ai")},enabled=online) {Text("AI settings in the web app")}}
  if(plugin.state=="failed") {Text("Paused",style=MaterialTheme.typography.bodySmall);TextButton(onClick={onWeb("/status")},enabled=online) {Text("Details in Status")}}
  if(plugin.permissionReviewRequired) TextButton(onClick=onReview,enabled=online && !busy && plugin.canEnable) {Text("Review updated permissions")}
  TextButton(onClick=onSettings) {Text(if(plugin.removed) "Details" else "Settings")}
  HorizontalDivider()
 }
}

@Composable internal fun NativePluginPermissionDialog(plugin:NativePluginFacts,online:Boolean,busy:Boolean,error:String,onDismiss:()->Unit,onAccept:()->Unit,onWeb:(String)->Unit) {
 AlertDialog(onDismissRequest={if(!busy) onDismiss()},title={PluginHeading(if(plugin.permissionReviewRequired) "${plugin.name} now also wants to…" else "Turn on ${plugin.name}?")},text={Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)) {
  Text(plugin.description)
  PluginHeading("It will");plugin.permissionLines.forEach {Text(it)}
  PluginHeading("AI and where note text goes");pluginDestinationFacts(plugin).forEach {Text(it)}
  if((plugin.raw["ai_route"] as? JsonObject)?.isNotEmpty()==true) {Text("Uses a route set for this plugin.");TextButton(onClick={onWeb("/settings/plugins/${plugin.id}")},enabled=online) {Text("Change route in the web app")}}
  PluginHeading("Where it shows up");Text(plugin.slots.joinToString("\n").ifEmpty {"No screen contributions are declared."})
  if(error.isNotBlank()) Text(error)
  if(!online) Text("Changes need a connection.")
 }},confirmButton={TextButton(onClick=onAccept,enabled=online && !busy && plugin.canEnable) {Text(if(busy) "Saving…" else if(plugin.permissionReviewRequired) "Accept updated permissions" else "Turn on")}},dismissButton={TextButton(onClick=onDismiss,enabled=!busy) {Text("Cancel")}})
}

@Composable internal fun NativePluginSettingsValues(plugin:NativePluginFacts,page:NativePluginPage,online:Boolean,onWeb:(String)->Unit) {
 PluginHeading("Settings")
 Text(if(online) "Server settings are read-only on this phone." else "Changes need a connection.")
 val fields=page.schema["properties"] as? JsonObject ?: JsonObject(emptyMap())
 if(fields.isEmpty()) Text("No cached server settings.")
 fields.forEach { (key,raw) -> val field=raw as? JsonObject ?: return@forEach
  val title=field.pluginText("title") ?: key.replace('_',' ').replaceFirstChar {it.uppercase()}
  if(field.pluginFlag("x-secret")==true) {
   val set=(page.secrets[key] as? JsonObject)?.pluginFlag("set")
   Text("$title · ${when(set) {true -> "Set in the environment";false -> "Not set in the environment";null -> "Environment status unavailable"}}")
   Text("SPUNTO_PLUGIN_${plugin.id.replace('-','_').uppercase()}_${key.uppercase()}",fontFamily=FontFamily.Monospace,style=MaterialTheme.typography.bodySmall)
  } else {
   val value=page.values[key]
   val display=when(value) {is JsonArray -> value.joinToString(", ") {(it as? JsonPrimitive)?.content.orEmpty()}.ifEmpty {"None"};is JsonPrimitive -> if(!value.isString && value.booleanOrNull!=null) if(value.boolean) "On" else "Off" else value.content;else -> "Unavailable"}
   Text("$title · $display")
   if(field.pluginText("x-scope")=="device") Text("This phone only",style=MaterialTheme.typography.bodySmall)
  }
 }
 TextButton(onClick={onWeb("/settings/plugins/${plugin.id}")},enabled=online && plugin.valid) {Text("Manage settings in the web app · owner sign-in")}
}

@Composable internal fun NativePluginDetailFacts(plugin:NativePluginFacts,online:Boolean,commandBusy:Boolean,onCommand:(JsonObject)->Unit,onWeb:(String)->Unit) {
 if(plugin.pin==EnvironmentPin.Managed) {Text("Set in the environment · ${if(plugin.wantedOn) "On" else "Off"}");Text("SPUNTO_PLUGINS",fontFamily=FontFamily.Monospace)}
 if(!plugin.removed) {
  PluginHeading("AI");pluginDestinationFacts(plugin).forEach {Text(it)}
  if(plugin.permissions.orEmpty().any {it.startsWith("ai.")}) TextButton(onClick={onWeb("/settings/plugins/${plugin.id}")},enabled=online) {Text("Change route in the web app · owner sign-in")}
  PluginHeading("Run")
  val commands=(plugin.contribution["commands"] as? JsonArray).orEmpty().mapNotNull {it as? JsonObject}.take(3)
  if(commands.isEmpty()) Text("No commands are declared.")
  commands.forEach {command ->
   val where=command.pluginStrings("where").orEmpty()
   val title=command.pluginText("title") ?: "Command"
   if(where.any {it in setOf("palette","global")} && Regex("[a-z][a-z0-9-]{0,127}").matches(command.pluginText("id").orEmpty())) TextButton(onClick={onCommand(command)},enabled=online && plugin.activeContribution && !commandBusy) {Text(title)}
   else Text("$title · Open a note to run this command")
  }
 }
 PluginHeading("Activity")
 val time=plugin.raw.pluginLong("last_run_at")
 Text(if(time==null) "No runs yet" else "Last run "+DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(time)))
 plugin.raw.pluginText("last_error")?.takeIf {it.isNotBlank()}?.let {Text(it)}
 Text(if(plugin.removed) "Removed" else plugin.state.replaceFirstChar {it.uppercase()})
 PluginHeading(if(plugin.removed) "Access history" else "Access")
 plugin.permissionLines.forEach {Text(it)}
 if(!plugin.removed) {
  Text("Limits: 3 commands, 1 view, 10 panel items and 3 agent tools. Up to 5 unattended notes a day; manual commands are separate.",style=MaterialTheme.typography.bodySmall)
  (plugin.contribution["mcp_tools"] as? JsonArray).orEmpty().mapNotNull {it as? JsonObject}.take(3).forEach {tool -> Text("${tool.pluginText("name").orEmpty()} · ${tool.pluginText("description").orEmpty()}",style=MaterialTheme.typography.bodySmall)}
 }
 PluginHeading("Stored data")
 plugin.raw.pluginLong("data_count")?.let {Text("$it records kept")}
 plugin.raw.pluginLong("data_size_bytes")?.let {Text("$it bytes")}
 (plugin.raw["data_counts"] as? JsonObject)?.forEach { (key,value) -> (value as? JsonPrimitive)?.takeUnless {it.isString}?.longOrNull?.takeIf {it>=0}?.let {Text("${key.replace('_',' ')} · $it",style=MaterialTheme.typography.bodySmall)} }
 Text("Its notes stay: they are notes.")
 if(plugin.canDeleteData) TextButton(onClick={onWeb("/settings/plugins/${plugin.id}")},enabled=online) {Text("Delete plugin data in the web app · owner sign-in")}
}

@Composable internal fun NativePluginUndo(receipt:PluginUndo?,online:Boolean,busy:Boolean,onUndo:()->Unit) {
 if(receipt!=null) Row {Text("Plugin switched off.",Modifier.weight(1f));TextButton(onClick=onUndo,enabled=online && !busy) {Text("Undo")}}
}
