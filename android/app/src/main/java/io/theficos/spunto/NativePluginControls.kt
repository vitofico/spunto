package io.theficos.spunto

import android.content.res.Resources
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration

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

@Composable internal fun PluginHeading(text:String) {
 Text(text,style=MaterialTheme.typography.titleSmall,color=MaterialTheme.colorScheme.onSurface,modifier=Modifier.semantics {heading()})}
/** One compact row (03 §6.4): name and tags, one sentence, one grey facts line; "Settings" and the switch on the right; a state line only when there is one. */
@Composable internal fun NativePluginRow(plugin:NativePluginFacts,online:Boolean,busy:Boolean,onToggle:(Boolean)->Unit,onSettings:()->Unit,onReview:()->Unit,onWeb:(String)->Unit) {
 val resources=LocalContext.current.resources
 val quiet=MaterialTheme.colorScheme.onSurfaceVariant
 Column(Modifier.fillMaxWidth().padding(vertical=SpuntoSpacing.s8)) {
  Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(SpuntoSpacing.s8)) {
   Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(SpuntoSpacing.s2)) {
    val tags=listOfNotNull("Android".takeIf {plugin.raw.pluginText("kind")=="device"},resources.getString(R.string.settings_default_with_ai).takeIf {plugin.raw.pluginText("default")=="on-with-ai"})
    Text(localizedPluginName(resources,plugin.raw)+tags.joinToString("") {" · $it"},style=MaterialTheme.typography.titleSmall,color=if(!plugin.wantedOn && !plugin.canEnable) quiet else MaterialTheme.colorScheme.onSurface)
    if(localizedPluginDescription(resources,plugin.raw).isNotBlank()) Text(localizedPluginDescription(resources,plugin.raw),style=MaterialTheme.typography.bodyMedium,color=quiet,maxLines=2,overflow=TextOverflow.Ellipsis)
    if(plugin.facts(resources).isNotEmpty()) Text(plugin.facts(resources).joinToString(" · "),style=MaterialTheme.typography.bodySmall,color=SpuntoColors.ink3,maxLines=2,overflow=TextOverflow.Ellipsis)
   }
   when {
    plugin.removed -> TextButton(onClick=onSettings,modifier=Modifier.heightIn(min=48.dp)) {Text(resources.getString(R.string.settings_details))}
    plugin.pin==EnvironmentPin.Managed -> Text(resources.getString(R.string.settings_set_in_the_environment ,if(plugin.wantedOn) resources.getString(R.string.settings_on_e0049) else resources.getString(R.string.settings_off)),Modifier.widthIn(max=128.dp),style=MaterialTheme.typography.bodySmall,color=quiet)
    plugin.pin==EnvironmentPin.Unknown || !plugin.valid -> Text(resources.getString(R.string.settings_switch_unavailable),style=MaterialTheme.typography.bodySmall,color=quiet)
    else -> {
     if(plugin.wantedOn || !plugin.canEnable) TextButton(onClick=onSettings,modifier=Modifier.heightIn(min=48.dp)) {Text(resources.getString(R.string.settings_settings))}
     PluginSwitch(plugin,online,busy,onToggle)
    }
   }
  }
  val state=when {
   plugin.removed -> listOfNotNull(resources.getString(R.string.settings_removed),plugin.raw.pluginLong("data_count")?.let {"${resources.getQuantityString(R.plurals.settings_records_kept,it.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),it)}"}).joinToString(" · ")
   plugin.availabilityReason!=null -> plugin.availabilityReason
   plugin.state=="waiting" -> pluginWaitingLine(resources,plugin)
   plugin.state=="failed" -> resources.getString(R.string.settings_paused_after_failed_runs)
   else -> null
  }
  if(state!=null || plugin.permissionReviewRequired) Row(verticalAlignment=Alignment.CenterVertically) {
   state?.let {Text(it,style=MaterialTheme.typography.bodySmall,color=quiet)}
   if(plugin.state=="waiting" && !plugin.removed) TextButton(onClick={onWeb("/settings/ai")},enabled=online,modifier=Modifier.heightIn(min=48.dp)) {Text(resources.getString(R.string.settings_ai_settings))}
   if(plugin.state=="failed" && !plugin.removed) TextButton(onClick={onWeb("/status")},enabled=online,modifier=Modifier.heightIn(min=48.dp)) {Text(resources.getString(R.string.settings_details))}
   if(plugin.permissionReviewRequired) TextButton(onClick=onReview,enabled=online && !busy && plugin.canEnable,modifier=Modifier.heightIn(min=48.dp)) {Text(resources.getString(R.string.settings_review_updated_permissions))}
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
 val resources=LocalContext.current.resources
 Switch(plugin.wantedOn,onToggle,enabled=online && !busy && (plugin.canDisable || plugin.canEnable),colors=spuntoSwitchColors(),modifier=Modifier.semantics {contentDescription=localizedPluginName(resources,plugin.raw);stateDescription=if(plugin.wantedOn) resources.getString(R.string.settings_on_e0049) else resources.getString(R.string.settings_off)})
}


@Composable internal fun NativePluginPermissionDialog(plugin:NativePluginFacts,online:Boolean,busy:Boolean,error:String,onDismiss:()->Unit,onAccept:()->Unit,onWeb:(String)->Unit) {
 val resources=LocalContext.current.resources
 AlertDialog(onDismissRequest={if(!busy) onDismiss()},title={PluginHeading(if(plugin.permissionReviewRequired) resources.getString(R.string.settings_now_also_wants_to ,localizedPluginName(resources,plugin.raw)) else resources.getString(R.string.settings_turn_on ,localizedPluginName(resources,plugin.raw)))},text={Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(SpuntoSpacing.s12)) {
  Text(localizedPluginDescription(resources,plugin.raw))
  PluginHeading(resources.getString(R.string.settings_it_will));plugin.permissionLines(resources).forEach {Text(it)}
  PluginHeading(resources.getString(R.string.settings_ai_and_where_note_text_goes));pluginDestinationFacts(resources,plugin).forEach {Text(it)}
  pluginMissingModel(plugin)?.let {Text(resources.getString(R.string.settings_it_waits_until_is_set_up_in ,aiModelName(resources,it)),color=MaterialTheme.colorScheme.onSurfaceVariant)}
  if((plugin.raw["ai_route"] as? JsonObject)?.isNotEmpty()==true) {Text(resources.getString(R.string.settings_uses_a_route_set_for_this_plugin));TextButton(onClick={onWeb("/settings/plugins/${plugin.id}")},enabled=online) {Text(resources.getString(R.string.settings_change_route_in_the_web_app))}}
  PluginHeading(resources.getString(R.string.settings_where_it_shows_up));Text(plugin.slots(resources).joinToString("\n").ifEmpty {resources.getString(R.string.settings_no_screen_contributions_are_declared)})
  if(error.isNotBlank()) Text(error,color=MaterialTheme.colorScheme.error)
  if(!online) Text(resources.getString(R.string.settings_changes_need_a_connection))
 }},confirmButton={TextButton(onClick=onAccept,enabled=online && !busy && plugin.canEnable) {Text(if(busy) resources.getString(R.string.settings_saving) else if(plugin.permissionReviewRequired) resources.getString(R.string.settings_accept_updated_permissions) else resources.getString(R.string.settings_turn_on_26563))}},dismissButton={TextButton(onClick=onDismiss,enabled=!busy) {Text(resources.getString(R.string.settings_cancel))}})
}

@Composable internal fun NativePluginSettingsValues(plugin:NativePluginFacts,page:NativePluginPage,online:Boolean,onWeb:(String)->Unit) {
 val resources=LocalContext.current.resources
 val fields=page.schema["properties"] as? JsonObject ?: JsonObject(emptyMap())
 if(fields.isEmpty()) return
 PluginHeading(resources.getString(R.string.settings_settings))
 Text(if(online) resources.getString(R.string.settings_server_settings_are_read_only_on_this) else resources.getString(R.string.settings_changes_need_a_connection),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
 fields.forEach { (key,raw) -> val field=raw as? JsonObject ?: return@forEach
  val title=localizedPluginSettingTitle(resources,plugin.raw,key,field)
  if(field.pluginFlag("x-secret")==true) {
   val set=(page.secrets[key] as? JsonObject)?.pluginFlag("set")
   Text("$title · ${when(set) {true -> resources.getString(R.string.settings_set_in_the_environment_81d85);false -> resources.getString(R.string.settings_not_set_in_the_environment);null -> resources.getString(R.string.settings_environment_status_unavailable)}}")
   Text("SPUNTO_PLUGIN_${plugin.id.replace('-','_').uppercase()}_${key.uppercase()}",fontFamily=monoFont(),style=MaterialTheme.typography.bodySmall)
  } else {
   val value=page.values[key]
   val display=when(value) {is JsonArray -> value.joinToString(", ") {(it as? JsonPrimitive)?.content.orEmpty()}.ifEmpty {resources.getString(R.string.settings_none)};is JsonPrimitive -> if(!value.isString && value.booleanOrNull!=null) if(value.boolean) resources.getString(R.string.settings_on_e0049) else resources.getString(R.string.settings_off) else value.content;else -> resources.getString(R.string.settings_unavailable_2c9c1)}
   Text("$title · $display")
   if(field.pluginText("x-scope")=="device") Text(resources.getString(R.string.settings_this_phone_only),style=MaterialTheme.typography.bodySmall)
  }
 }
 TextButton(onClick={onWeb("/settings/plugins/${plugin.id}")},enabled=online && plugin.valid) {Text(resources.getString(R.string.settings_manage_settings_in_the_web_app_owner))}
}

@Composable internal fun NativePluginDetailFacts(plugin:NativePluginFacts,online:Boolean,commandBusy:Boolean,onCommand:(JsonObject)->Unit,onWeb:(String)->Unit) {
 val locale=LocalConfiguration.current.locales[0]
 val resources=LocalContext.current.resources
 if(plugin.pin==EnvironmentPin.Managed) {Text(resources.getString(R.string.settings_set_in_the_environment ,if(plugin.wantedOn) resources.getString(R.string.settings_on_e0049) else resources.getString(R.string.settings_off)));Text("SPUNTO_PLUGINS",fontFamily=monoFont())}
 if(!plugin.removed) {
  PluginHeading(resources.getString(R.string.settings_ai));pluginDestinationFacts(resources,plugin).forEach {Text(it)}
  if(plugin.permissions.orEmpty().any {it.startsWith("ai.")}) TextButton(onClick={onWeb("/settings/plugins/${plugin.id}")},enabled=online) {Text(resources.getString(R.string.settings_change_route_in_the_web_app_owner))}
  PluginHeading(resources.getString(R.string.settings_run))
  val commands=(plugin.contribution["commands"] as? JsonArray).orEmpty().mapNotNull {it as? JsonObject}.take(3)
  if(commands.isEmpty()) Text(resources.getString(R.string.settings_no_commands_are_declared))
  commands.forEach {command ->
   val where=command.pluginStrings("where").orEmpty()
   val title=localizedPluginCommand(resources,plugin.raw,command)
   if(where.any {it in setOf("palette","global")} && Regex("[a-z][a-z0-9-]{0,127}").matches(command.pluginText("id").orEmpty())) TextButton(onClick={onCommand(command)},enabled=online && plugin.activeContribution && !commandBusy) {Text(title)}
   else Text(resources.getString(R.string.settings_open_a_note_to_run_this_command ,title))
  }
 }
 PluginHeading(resources.getString(R.string.settings_activity))
 val time=plugin.raw.pluginLong("last_run_at")
 Text(if(time==null) resources.getString(R.string.settings_no_runs_yet) else resources.getString(R.string.settings_last_run,java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM,java.text.DateFormat.SHORT,locale).format(java.util.Date(time))))
 plugin.raw.pluginText("last_error")?.takeIf {it.isNotBlank()}?.let {Text(it)}
 // On and off show in the switch above; only the states it can't show get a line.
 when {plugin.removed -> resources.getString(R.string.settings_removed);plugin.availabilityReason!=null -> plugin.availabilityReason;plugin.state=="waiting" -> pluginWaitingLine(resources,plugin);plugin.state=="failed" -> resources.getString(R.string.settings_paused_after_failed_runs);else -> null}?.let {Text(it)}
 PluginHeading(if(plugin.removed) resources.getString(R.string.settings_access_history) else resources.getString(R.string.settings_access))
 plugin.permissionLines(resources).forEach {Text(it)}
 if(!plugin.removed) {
  Text(resources.getString(R.string.settings_limits_3_commands_1_view_10_panel),style=MaterialTheme.typography.bodySmall)
  (plugin.contribution["mcp_tools"] as? JsonArray).orEmpty().mapNotNull {it as? JsonObject}.take(3).forEach {tool -> Text("${tool.pluginText("name").orEmpty()} · ${localizedPluginToolDescription(resources,plugin.raw,tool.pluginText("name").orEmpty(),tool)}",style=MaterialTheme.typography.bodySmall)}
 }
 PluginHeading(resources.getString(R.string.settings_stored_data))
 Text(listOfNotNull(plugin.raw.pluginLong("data_count")?.takeIf {it>0}?.let {"${resources.getQuantityString(R.plurals.settings_records_kept,it.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),it)}"},plugin.raw.pluginLong("data_size_bytes")?.takeIf {it>0}?.let {byteSize(resources,it)}).joinToString(" · ").ifEmpty {resources.getString(R.string.settings_nothing_stored)})
 Text(resources.getString(R.string.settings_its_notes_stay_they_are_notes),color=MaterialTheme.colorScheme.onSurfaceVariant)
 if(plugin.canDeleteData) TextButton(onClick={onWeb("/settings/plugins/${plugin.id}")},enabled=online) {Text(resources.getString(R.string.settings_delete_plugin_data_in_the_web_app))}
}

@Composable internal fun NativePluginUndo(receipt:PluginUndo?,name:String,online:Boolean,busy:Boolean,onUndo:()->Unit) {
 val resources=LocalContext.current.resources
 if(receipt!=null) Row(verticalAlignment=Alignment.CenterVertically) {Text(resources.getString(R.string.settings_is_off ,name),Modifier.weight(1f).semantics {liveRegion=LiveRegionMode.Polite});TextButton(onClick=onUndo,enabled=online && !busy,modifier=Modifier.heightIn(min=48.dp)) {Text(resources.getString(R.string.settings_undo))}}
}
internal fun byteSize(resources:Resources,bytes:Long)=when { bytes<1024 -> resources.getQuantityString(R.plurals.settings_bytes,bytes.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),bytes); bytes<1024*1024 -> resources.getString(R.string.settings_kilobytes,bytes/1024); else -> resources.getString(R.string.settings_megabytes,bytes/1048576.0) }
