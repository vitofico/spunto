package io.theficos.spunto

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.theficos.spunto.core.design.SpuntoSpacing
import io.theficos.spunto.core.design.SpuntoColors
import io.theficos.spunto.core.design.monoFont
import kotlinx.serialization.json.*
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable internal fun PluginHeading(text:String) {Text(text,style=MaterialTheme.typography.titleSmall,color=MaterialTheme.colorScheme.onSurface,modifier=Modifier.semantics {heading()})}
/** One compact row (03 §6.4): name and tags, one sentence, one grey facts line; "Settings" and the switch on the right; a state line only when there is one. */
@Composable internal fun NativePluginRow(plugin:NativePluginFacts,online:Boolean,busy:Boolean,onToggle:(Boolean)->Unit,onSettings:()->Unit,onReview:()->Unit,onWeb:(String)->Unit) {
 val quiet=MaterialTheme.colorScheme.onSurfaceVariant
 Column(Modifier.fillMaxWidth().padding(vertical=SpuntoSpacing.s8)) {
  Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(SpuntoSpacing.s8)) {
   Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(SpuntoSpacing.s2)) {
    val tags=listOfNotNull("Android".takeIf {plugin.raw.pluginText("kind")=="device"},"Default with AI".takeIf {plugin.raw.pluginText("default")=="on-with-ai"})
    Text(plugin.name+tags.joinToString("") {" · $it"},style=MaterialTheme.typography.titleSmall)
    if(plugin.description.isNotBlank()) Text(plugin.description,style=MaterialTheme.typography.bodyMedium,color=quiet,maxLines=2,overflow=TextOverflow.Ellipsis)
    if(plugin.facts.isNotEmpty()) Text(plugin.facts.joinToString(" · "),style=MaterialTheme.typography.bodySmall,color=SpuntoColors.ink3,maxLines=2,overflow=TextOverflow.Ellipsis)
   }
   when {
    plugin.removed -> TextButton(onClick=onSettings,modifier=Modifier.heightIn(min=48.dp)) {Text("Details")}
    plugin.pin==EnvironmentPin.Managed -> Text("Set in the environment · ${if(plugin.wantedOn) "On" else "Off"}",Modifier.widthIn(max=128.dp),style=MaterialTheme.typography.bodySmall,color=quiet)
    plugin.pin==EnvironmentPin.Unknown || !plugin.valid -> Text("Switch unavailable",style=MaterialTheme.typography.bodySmall,color=quiet)
    else -> {
     if(plugin.wantedOn) TextButton(onClick=onSettings,modifier=Modifier.heightIn(min=48.dp)) {Text("Settings")}
     PluginSwitch(plugin,online,busy,onToggle)
    }
   }
  }
  val state=when {
   plugin.removed -> listOfNotNull("Removed",plugin.raw.pluginLong("data_count")?.let {"${plural(it,"record")} kept"}).joinToString(" · ")
   plugin.state=="waiting" -> pluginWaitingLine(plugin)
   plugin.state=="failed" -> "Paused after failed runs"
   else -> null
  }
  if(state!=null || plugin.permissionReviewRequired) Row(verticalAlignment=Alignment.CenterVertically) {
   state?.let {Text(it,style=MaterialTheme.typography.bodySmall,color=quiet)}
   if(plugin.state=="waiting" && !plugin.removed) TextButton(onClick={onWeb("/settings/ai")},enabled=online,modifier=Modifier.heightIn(min=48.dp)) {Text("AI settings")}
   if(plugin.state=="failed" && !plugin.removed) TextButton(onClick={onWeb("/status")},enabled=online,modifier=Modifier.heightIn(min=48.dp)) {Text("Details")}
   if(plugin.permissionReviewRequired) TextButton(onClick=onReview,enabled=online && !busy && plugin.canEnable,modifier=Modifier.heightIn(min=48.dp)) {Text("Review updated permissions")}
  }
 }
 HorizontalDivider(color=MaterialTheme.colorScheme.outlineVariant)
}
/** Switches are ink, not accent: the accent is kept for meaning (03 principle 5), as on the web. */
@Composable internal fun spuntoSwitchColors()=SwitchDefaults.colors(
 checkedThumbColor=MaterialTheme.colorScheme.surface,checkedTrackColor=MaterialTheme.colorScheme.onSurface,checkedBorderColor=MaterialTheme.colorScheme.onSurface,checkedIconColor=MaterialTheme.colorScheme.onSurface,
 uncheckedThumbColor=MaterialTheme.colorScheme.onSurfaceVariant,uncheckedTrackColor=MaterialTheme.colorScheme.surface,uncheckedBorderColor=MaterialTheme.colorScheme.outline,
)
/** Announced as "Dictation, on" (05 §10). */
@Composable internal fun PluginSwitch(plugin:NativePluginFacts,online:Boolean,busy:Boolean,onToggle:(Boolean)->Unit) {
 Switch(plugin.wantedOn,onToggle,enabled=online && !busy && (plugin.canDisable || plugin.canEnable),colors=spuntoSwitchColors(),modifier=Modifier.semantics {contentDescription=plugin.name;stateDescription=if(plugin.wantedOn) "on" else "off"})
}
internal fun plural(count:Long,noun:String)="$count $noun${if(count==1L) "" else "s"}"

@Composable internal fun NativePluginPermissionDialog(plugin:NativePluginFacts,online:Boolean,busy:Boolean,error:String,onDismiss:()->Unit,onAccept:()->Unit,onWeb:(String)->Unit) {
 AlertDialog(onDismissRequest={if(!busy) onDismiss()},title={PluginHeading(if(plugin.permissionReviewRequired) "${plugin.name} now also wants to…" else "Turn on ${plugin.name}?")},text={Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(SpuntoSpacing.s12)) {
  Text(plugin.description)
  PluginHeading("It will");plugin.permissionLines.forEach {Text(pluginWillSentence(it))}
  PluginHeading("AI and where note text goes");pluginDestinationFacts(plugin).forEach {Text(it)}
  pluginMissingModel(plugin)?.let {Text("It waits until ${aiModelName(it)} is set up in AI settings, then starts on its own.",color=MaterialTheme.colorScheme.onSurfaceVariant)}
  if((plugin.raw["ai_route"] as? JsonObject)?.isNotEmpty()==true) {Text("Uses a route set for this plugin.");TextButton(onClick={onWeb("/settings/plugins/${plugin.id}")},enabled=online) {Text("Change route in the web app")}}
  PluginHeading("Where it shows up");Text(plugin.slots.joinToString("\n").ifEmpty {"No screen contributions are declared."})
  if(error.isNotBlank()) Text(error,color=MaterialTheme.colorScheme.error)
  if(!online) Text("Changes need a connection.")
 }},confirmButton={TextButton(onClick=onAccept,enabled=online && !busy && plugin.canEnable) {Text(if(busy) "Saving…" else if(plugin.permissionReviewRequired) "Accept updated permissions" else "Turn on")}},dismissButton={TextButton(onClick=onDismiss,enabled=!busy) {Text("Cancel")}})
}

@Composable internal fun NativePluginSettingsValues(plugin:NativePluginFacts,page:NativePluginPage,online:Boolean,onWeb:(String)->Unit) {
 val fields=page.schema["properties"] as? JsonObject ?: JsonObject(emptyMap())
 if(fields.isEmpty()) return
 PluginHeading("Settings")
 Text(if(online) "Server settings are read-only on this phone." else "Changes need a connection.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
 fields.forEach { (key,raw) -> val field=raw as? JsonObject ?: return@forEach
  val title=field.pluginText("title") ?: key.replace('_',' ').replaceFirstChar {it.uppercase()}
  if(field.pluginFlag("x-secret")==true) {
   val set=(page.secrets[key] as? JsonObject)?.pluginFlag("set")
   Text("$title · ${when(set) {true -> "Set in the environment";false -> "Not set in the environment";null -> "Environment status unavailable"}}")
   Text("SPUNTO_PLUGIN_${plugin.id.replace('-','_').uppercase()}_${key.uppercase()}",fontFamily=monoFont(),style=MaterialTheme.typography.bodySmall)
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
 if(plugin.pin==EnvironmentPin.Managed) {Text("Set in the environment · ${if(plugin.wantedOn) "On" else "Off"}");Text("SPUNTO_PLUGINS",fontFamily=monoFont())}
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
 // On and off show in the switch above; only the states it can't show get a line.
 when {plugin.removed -> "Removed";plugin.state=="waiting" -> pluginWaitingLine(plugin);plugin.state=="failed" -> "Paused after failed runs";else -> null}?.let {Text(it)}
 PluginHeading(if(plugin.removed) "Access history" else "Access")
 plugin.permissionLines.forEach {Text(it)}
 if(!plugin.removed) {
  Text("Limits: 3 commands, 1 view, 10 panel items and 3 agent tools. Up to 5 unattended notes a day; manual commands are separate.",style=MaterialTheme.typography.bodySmall)
  (plugin.contribution["mcp_tools"] as? JsonArray).orEmpty().mapNotNull {it as? JsonObject}.take(3).forEach {tool -> Text("${tool.pluginText("name").orEmpty()} · ${tool.pluginText("description").orEmpty()}",style=MaterialTheme.typography.bodySmall)}
 }
 PluginHeading("Stored data")
 Text(listOfNotNull(plugin.raw.pluginLong("data_count")?.takeIf {it>0}?.let {"${plural(it,"record")} kept"},plugin.raw.pluginLong("data_size_bytes")?.takeIf {it>0}?.let(::byteSize)).joinToString(" · ").ifEmpty {"Nothing stored"})
 Text("Its notes stay: they are notes.",color=MaterialTheme.colorScheme.onSurfaceVariant)
 if(plugin.canDeleteData) TextButton(onClick={onWeb("/settings/plugins/${plugin.id}")},enabled=online) {Text("Delete plugin data in the web app · owner sign-in")}
}

@Composable internal fun NativePluginUndo(receipt:PluginUndo?,name:String,online:Boolean,busy:Boolean,onUndo:()->Unit) {
 if(receipt!=null) Row(verticalAlignment=Alignment.CenterVertically) {Text("$name is off",Modifier.weight(1f).semantics {liveRegion=LiveRegionMode.Polite});TextButton(onClick=onUndo,enabled=online && !busy,modifier=Modifier.heightIn(min=48.dp)) {Text("Undo")}}
}
internal fun byteSize(bytes:Long)=when { bytes<1024 -> "$bytes bytes"; bytes<1024*1024 -> "${bytes/1024} KB"; else -> "%.1f MB".format(bytes/1048576.0) }
