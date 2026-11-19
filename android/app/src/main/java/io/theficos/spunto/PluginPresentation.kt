package io.theficos.spunto

import android.content.res.Resources

import io.theficos.spunto.core.plugins.*
import io.theficos.spunto.data.sync.ApiException
import io.theficos.spunto.data.sync.ApiClient
import io.theficos.spunto.data.sync.AuthConfig
import java.util.Locale
import kotlinx.serialization.json.*

internal data class NoteTool(val pluginId:String,val id:String,val title:String) {
 val icon get()=when(pluginId) {
  "links", "link-enrichment" -> R.drawable.ic_link
  "image-text" -> R.drawable.ic_image
  "voice-memos" -> R.drawable.ic_mic
  "label-suggestions" -> R.drawable.ic_label
  "translate" -> R.drawable.ic_language
  "title-summary", "tidy", "develop" -> R.drawable.ic_notes
  "context-packs", "thinking-desk" -> R.drawable.ic_inbox
  "agent-inbox" -> R.drawable.ic_checklist
  else -> R.drawable.ic_settings_outline
 }
}

/** A command is listed once, with the internal note-link action kept in reach. */
internal fun noteTools(
 resources:Resources,plugins:List<JsonObject>,privateNote:Boolean=false,catalog:JsonObject=JsonObject(emptyMap()),
 versionedAgentReview:Boolean=false,legacyAgentReview:Boolean=true,inputs:NoteToolInputs=NoteToolInputs(),waitingOnly:Boolean=false,
):List<NoteTool> = plugins.filter {
 (if(waitingOnly) it.string("state")=="waiting" else it.bool("enabled",true) && it.string("state") !in setOf("disabled","off","failed","waiting","removed"))
 && (!privateNote || (it["permissions"] as? JsonArray)?.all {permission ->
  val name=(permission as? JsonPrimitive)?.takeIf {value ->value.isString}?.content
  name!=null && !name.startsWith("ai.") && !name.startsWith("net.")
 }==true)
}.sortedBy {(it["position"] as? JsonPrimitive)?.intOrNull ?: Int.MAX_VALUE}.flatMap {plugin ->
 val id=plugin.string("id")
 val origin=plugin.pluginText("origin") ?: catalog.objects("plugins").firstOrNull {it.string("id")==id}?.pluginText("origin")
 val stock=origin==null || origin=="bundled"
 (plugin["contributes"] as? JsonObject)?.objects("commands").orEmpty().filter {command ->
  (command["where"] as? JsonArray)?.any {(it as? JsonPrimitive)?.content=="note-actions"}==true &&
  (!stock || when(id to command.string("id")) {
   DecisionJournalId to "record-decision", DecisionJournalId to "review-decision" -> false
   AgentInboxId to AgentReviewCommand -> !versionedAgentReview && legacyAgentReview
   "image-text" to "read" -> inputs.image
   "voice-memos" to "transcribe" -> inputs.audio
   "link-enrichment" to "fetch" -> inputs.link
   "title-summary" to "summarize" -> inputs.titleSummary
   "label-suggestions" to "suggest-now" -> inputs.labels
   "tidy" to "tidy", "translate" to "translate", "develop" to "develop" -> inputs.shortText
   else -> true
  })
 }.map {NoteTool(id,it.string("id"),localizedPluginCommand(resources,plugin,it,catalog))}
}.distinctBy {it.pluginId to it.id}.sortedByDescending {it.pluginId=="links" && it.id=="link"}

internal fun pluginApi(config:AuthConfig,resources:Resources)=ApiClient(config,language={resources.configuration.locales[0].toLanguageTag()})

internal fun pluginApiError(resources:Resources,error:ApiException,command:String=""):String {
 if(!error.contentLanguage.isNullOrBlank() && Locale.forLanguageTag(error.contentLanguage).language==resources.configuration.locales[0].language && error.detail.isNotBlank()) return error.detail
 val message=when(error.code) {
  "decision_conflict" -> if(command=="record-decision") R.string.plugin_capture_conflict else R.string.decision_conflict
  "not_found" -> R.string.settings_this_command_is_unavailable
  "validation_failed" -> R.string.settings_check_the_tool_inputs
  "plugin_call_failed" -> R.string.settings_the_server_couldn_t_finish_this_try
  "plugin_review_changed", "permissions_changed" -> R.string.plugin_review_details
  "pinned_by_environment" -> R.string.settings_set_in_the_environment_81d85
  "read_only_space" -> R.string.workspace_read_only
  "owner_required" -> R.string.plugin_owner_required
  "plugin_call_denied", "permission_denied" -> R.string.plugin_command_denied
  else -> null
 }
 return message?.let(resources::getString) ?: apiErrorMessage(resources,error)
}

internal fun pluginPillCaption(resources:Resources,plugin:String,caption:String):String {
 val label=when(plugin to caption) {
  "title-summary" to "Use title", "link-enrichment" to "Use page title" -> R.string.plugin_accept_title
  "tidy" to "Apply changes" -> R.string.settings_apply_changes
  "links" to "Link to note" -> R.string.settings_bundled_title_11
  else -> null
 }
 return label?.let(resources::getString) ?: caption
}

/** These presentation labels leave the submitted enum value and note captions intact. */
internal fun pluginFormChoice(resources:Resources,plugin:String,field:String,value:JsonElement):String? {
 val option=(value as? JsonPrimitive)?.takeIf {it.isString}?.content ?: return null
 val label=when {
  plugin in setOf("context-packs","thinking-desk") && field=="action" -> when(option) {
   "save" -> R.string.core_save;"delete" -> R.string.plugin_delete;else -> null
  }
  plugin in setOf("context-packs","thinking-desk") && field in setOf("add","remove") && option=="None" -> R.string.settings_none
  plugin=="thinking-desk" && field=="mode" -> when(option) {"history" -> R.string.core_history;"collision" -> R.string.plugin_collision;else -> null}
  plugin=="thinking-desk" && field=="kind" -> when(option) {"builds-on" -> R.string.plugin_builds_on;"challenges" -> R.string.plugin_challenges;"replaces" -> R.string.plugin_replaces;else -> null}
  plugin=="links" && field=="target" && option=="Choose a note" -> R.string.plugin_choose_note
  else -> null
 }
 return label?.let(resources::getString)
}

/** Older servers send these fixed field labels as English. Field values remain server-owned. */
internal fun pluginFormComponent(resources:Resources,plugin:String,component:JsonObject):JsonObject {
 if(component.string("type")!="form") return component
 val schema=component["schema"] as? JsonObject ?: return component
 val fields=schema["properties"] as? JsonObject ?: return component
 val localized=fields.mapValues { (key,value) ->
  val spec=value as? JsonObject ?: return@mapValues value
  val label=when {
   plugin=="translate" && key=="language" && spec.string("title")=="Translate to" -> R.string.plugin_translate_to
   plugin in setOf("context-packs","thinking-desk") && key=="action" && spec.string("title")=="Action" -> R.string.plugin_action
   else -> null
  }
  label?.let {JsonObject(spec+("title" to JsonPrimitive(resources.getString(it))))} ?: value
 }
 return JsonObject(component+("schema" to JsonObject(schema+("properties" to JsonObject(localized)))))
}

/** The server's own words for a refused request: its problem detail is safe owner guidance (docs/api.md), and it knows
 * what the web app and this phone can each do. A reply without one, from a proxy in front of it say, gets a line from its status. */
internal fun apiErrorMessage(resources:Resources,error:ApiException):String = error.detail.ifBlank { when {
 error.status==401 -> resources.getString(R.string.settings_your_session_expired_sign_in_again_then)
 error.status==504 -> resources.getString(R.string.settings_the_server_took_too_long_try_again)
 error.status==429 -> resources.getString(R.string.settings_the_server_is_busy_wait_a_moment)
 else -> resources.getString(R.string.settings_the_server_couldn_t_finish_this_try)
} }

/** The catalog supplies trusted provenance; custom manifest prose stays author-provided. */
private fun bundledMetadata(plugin:JsonObject,catalog:JsonObject):Boolean = plugin.pluginText("origin")=="bundled" ||
 catalog.objects("plugins").any {it.pluginText("id")==plugin.pluginText("id") && it.pluginText("origin")=="bundled"}
internal fun localizedPluginName(resources:Resources,plugin:JsonObject,catalog:JsonObject=JsonObject(emptyMap())):String {
 val title=if(bundledMetadata(plugin,catalog)) when(plugin.pluginText("id")) {
  "library-stats" -> R.string.settings_plugin_library_stats_name
  "thinking-desk" -> R.string.settings_plugin_thinking_desk_name
  "context-packs" -> R.string.settings_plugin_context_packs_name
  "tasks" -> R.string.settings_plugin_tasks_name
  "develop" -> R.string.settings_plugin_develop_name
  "ask" -> R.string.settings_plugin_ask_name
  "translate" -> R.string.settings_plugin_translate_name
  "email-in" -> R.string.settings_plugin_email_in_name
  "agent-inbox" -> R.string.settings_plugin_agent_inbox_name
  "digest" -> R.string.settings_plugin_digest_name
  "title-summary" -> R.string.settings_plugin_title_summary_name
  "related" -> R.string.settings_plugin_related_name
  "quick-capture" -> R.string.settings_plugin_quick_capture_name
  "resurface" -> R.string.settings_plugin_resurface_name
  "tidy" -> R.string.settings_plugin_tidy_name
  "dictation" -> R.string.settings_plugin_dictation_name
  "links" -> R.string.settings_plugin_links_name
  "telegram" -> R.string.settings_plugin_telegram_name
  "decision-journal" -> R.string.settings_plugin_decision_journal_name
  "label-gardener" -> R.string.settings_plugin_label_gardener_name
  "voice-memos" -> R.string.settings_plugin_voice_memos_name
  "image-text" -> R.string.settings_plugin_image_text_name
  "markdown-import" -> R.string.settings_plugin_markdown_import_name
  "semantic-search" -> R.string.settings_plugin_semantic_search_name
  "webhooks" -> R.string.settings_plugin_webhooks_name
  "duplicates" -> R.string.settings_plugin_duplicates_name
  "label-suggestions" -> R.string.settings_plugin_label_suggestions_name
  "link-enrichment" -> R.string.settings_plugin_link_enrichment_name
  else -> null
 } else null
 return title?.let {resources.getString(it)} ?: plugin.string("name").ifBlank {plugin.string("id")}
}
internal fun localizedPluginDescription(resources:Resources,plugin:JsonObject,catalog:JsonObject=JsonObject(emptyMap())):String {
 val text=if(bundledMetadata(plugin,catalog)) when(plugin.pluginText("id")) {
  "library-stats" -> R.string.settings_plugin_library_stats_description
  "thinking-desk" -> R.string.settings_plugin_thinking_desk_description
  "context-packs" -> R.string.settings_plugin_context_packs_description
  "tasks" -> R.string.settings_plugin_tasks_description
  "develop" -> R.string.settings_plugin_develop_description
  "ask" -> R.string.settings_plugin_ask_description
  "translate" -> R.string.settings_plugin_translate_description
  "email-in" -> R.string.settings_plugin_email_in_description
  "agent-inbox" -> R.string.settings_plugin_agent_inbox_description
  "digest" -> R.string.settings_plugin_digest_description
  "title-summary" -> R.string.settings_plugin_title_summary_description
  "related" -> R.string.settings_plugin_related_description
  "quick-capture" -> R.string.settings_plugin_quick_capture_description
  "resurface" -> R.string.settings_plugin_resurface_description
  "tidy" -> R.string.settings_plugin_tidy_description
  "dictation" -> R.string.settings_plugin_dictation_description
  "links" -> R.string.settings_plugin_links_description
  "telegram" -> R.string.settings_plugin_telegram_description
  "decision-journal" -> R.string.settings_plugin_decision_journal_description
  "label-gardener" -> R.string.settings_plugin_label_gardener_description
  "voice-memos" -> R.string.settings_plugin_voice_memos_description
  "image-text" -> R.string.settings_plugin_image_text_description
  "markdown-import" -> R.string.settings_plugin_markdown_import_description
  "semantic-search" -> R.string.settings_plugin_semantic_search_description
  "webhooks" -> R.string.settings_plugin_webhooks_description
  "duplicates" -> R.string.settings_plugin_duplicates_description
  "label-suggestions" -> R.string.settings_plugin_label_suggestions_description
  "link-enrichment" -> R.string.settings_plugin_link_enrichment_description
  else -> null
 } else null
 return text?.let {resources.getString(it)} ?: plugin.string("description")
}
internal fun localizedPluginCommand(resources:Resources,plugin:JsonObject,item:JsonObject,catalog:JsonObject=JsonObject(emptyMap())):String {
 val title=if(bundledMetadata(plugin,catalog)) when(plugin.string("id") to item.string("id")) {
  "library-stats" to "summary" -> R.string.settings_plugin_library_stats_name
  "thinking-desk" to "desk" -> R.string.settings_thinking_desk_command
  "thinking-desk" to "edit" -> R.string.settings_thinking_desk_edit
  "thinking-desk" to "sources" -> R.string.settings_thinking_desk_sources
  "context-packs" to "packs" -> R.string.settings_bundled_title_0
  "context-packs" to "edit" -> R.string.settings_bundled_title_1
  "context-packs" to "sources" -> R.string.settings_bundled_title_2
  "develop" to "develop" -> R.string.settings_bundled_title_3
  "ask" to "ask" -> R.string.settings_bundled_title_4
  "ask" to "save-answer" -> R.string.settings_bundled_title_5
  "translate" to "save-translation" -> R.string.settings_bundled_title_5
  "translate" to "translate" -> R.string.settings_bundled_title_6
  "agent-inbox" to "mark-reviewed" -> R.string.settings_bundled_title_7
  "digest" to "digest" -> R.string.settings_bundled_title_8
  "title-summary" to "summarize" -> R.string.settings_bundled_title_9
  "tidy" to "tidy" -> R.string.settings_bundled_title_10
  "links" to "link" -> R.string.settings_bundled_title_11
  "decision-journal" to "record-decision" -> R.string.settings_bundled_title_12
  "decision-journal" to "review-decision" -> R.string.settings_bundled_title_13
  "decision-journal" to "journal" -> R.string.settings_bundled_title_14
  "label-gardener" to "review-now" -> R.string.settings_bundled_title_15
  "voice-memos" to "transcribe" -> R.string.settings_bundled_title_16
  "image-text" to "read" -> R.string.settings_bundled_title_17
  "label-suggestions" to "suggest-now" -> R.string.settings_bundled_title_18
  "link-enrichment" to "fetch" -> R.string.settings_bundled_title_19
  else -> null
 } else null
 return title?.let {resources.getString(it)} ?: item.string("title").ifBlank {localizedPluginName(resources,plugin,catalog)}
}
internal fun localizedPluginView(resources:Resources,plugin:JsonObject,item:JsonObject,catalog:JsonObject=JsonObject(emptyMap())):String {
 val title=if(bundledMetadata(plugin,catalog)) when(plugin.string("id") to item.string("id")) {
  "tasks" to "tasks" -> R.string.settings_bundled_title_20
  "agent-inbox" to "reviewed" -> R.string.settings_bundled_title_21
  "digest" to "digests" -> R.string.settings_bundled_title_22
  "decision-journal" to "journal" -> R.string.settings_bundled_title_23
  "label-suggestions" to "unlabeled" -> R.string.settings_bundled_title_24
  else -> null
 } else null
 return title?.let {resources.getString(it)} ?: item.string("title").ifBlank {localizedPluginName(resources,plugin,catalog)}
}
internal fun localizedPluginPanel(resources:Resources,plugin:JsonObject,item:JsonObject,catalog:JsonObject=JsonObject(emptyMap())):String {
 val title=if(bundledMetadata(plugin,catalog)) when(plugin.string("id") to item.string("id")) {
  "decision-journal" to "" -> R.string.settings_bundled_title_14
  "title-summary" to "" -> R.string.settings_bundled_title_25
  "related" to "" -> R.string.settings_bundled_title_26
  "links" to "" -> R.string.settings_bundled_title_27
  "voice-memos" to "" -> R.string.settings_bundled_title_28
  "image-text" to "" -> R.string.settings_bundled_title_29
  "link-enrichment" to "" -> R.string.settings_bundled_title_30
  else -> null
 } else null
 return title?.let {resources.getString(it)} ?: item.string("title").ifBlank {localizedPluginName(resources,plugin,catalog)}
}

internal fun localizedPluginSettingTitle(resources:Resources,plugin:JsonObject,key:String,item:JsonObject):String {
 val text=if(bundledMetadata(plugin,JsonObject(emptyMap()))) when(plugin.string("id") to key) {
  "digest" to "weekday" -> R.string.settings_metadata_digest_settings_weekday_title
  "digest" to "time" -> R.string.settings_metadata_digest_settings_time_title
  "digest" to "min_notes" -> R.string.settings_metadata_digest_settings_min_notes_title
  "digest" to "leave_out" -> R.string.settings_metadata_digest_settings_leave_out_title
  "duplicates" to "threshold" -> R.string.settings_metadata_duplicates_settings_threshold_title
  "email-in" to "url" -> R.string.settings_metadata_email_in_settings_url_title
  "email-in" to "username" -> R.string.settings_metadata_email_in_settings_username_title
  "email-in" to "password" -> R.string.settings_metadata_email_in_settings_password_title
  "email-in" to "folder" -> R.string.settings_metadata_email_in_settings_folder_title
  "email-in" to "allow_senders" -> R.string.settings_metadata_email_in_settings_allow_senders_title
  "label-gardener" to "weekday" -> R.string.settings_metadata_label_gardener_settings_weekday_title
  "label-gardener" to "time" -> R.string.settings_metadata_label_gardener_settings_time_title
  "label-suggestions" to "change_mode" -> R.string.plugin_change_behavior
  "title-summary" to "change_mode" -> R.string.plugin_change_behavior
  "voice-memos" to "change_mode" -> R.string.plugin_change_behavior
  "label-suggestions" to "auto_accept_existing" -> R.string.settings_metadata_label_suggestions_settings_auto_accept_existing_title
  "label-suggestions" to "confidence_threshold" -> R.string.settings_metadata_label_suggestions_settings_confidence_threshold_title
  "resurface" to "time" -> R.string.settings_metadata_resurface_settings_time_title
  "resurface" to "min_age_days" -> R.string.settings_metadata_resurface_settings_min_age_days_title
  "semantic-search" to "model_family" -> R.string.settings_metadata_semantic_search_settings_model_family_title
  "semantic-search" to "similarity_floor" -> R.string.settings_metadata_semantic_search_settings_similarity_floor_title
  "telegram" to "bot_token" -> R.string.settings_metadata_telegram_settings_bot_token_title
  "telegram" to "allow_accounts" -> R.string.settings_metadata_telegram_settings_allow_accounts_title
  "voice-memos" to "language" -> R.string.settings_metadata_voice_memos_settings_language_title
  "voice-memos" to "keep_link" -> R.string.settings_metadata_voice_memos_settings_keep_link_title
  "webhooks" to "urls" -> R.string.settings_metadata_webhooks_settings_urls_title
  "webhooks" to "token" -> R.string.settings_metadata_webhooks_settings_token_title
  else -> null
 } else null
 return text?.let {resources.getString(it)} ?: item.pluginText("title") ?: key.replace('_',' ').replaceFirstChar {it.uppercase()}
}

internal fun localizedPluginToolDescription(resources:Resources,plugin:JsonObject,key:String,item:JsonObject):String {
 val text=if(bundledMetadata(plugin,JsonObject(emptyMap()))) when(plugin.string("id") to key) {
  "context-packs" to "list" -> R.string.settings_metadata_context_packs_mcp_tools_list_description
  "context-packs" to "get" -> R.string.settings_metadata_context_packs_mcp_tools_get_description
  "digest" to "latest" -> R.string.settings_metadata_digest_mcp_tools_latest_description
  "related" to "notes" -> R.string.settings_metadata_related_mcp_tools_notes_description
  else -> null
 } else null
 return text?.let {resources.getString(it)} ?: item.pluginText("description").orEmpty()
}
