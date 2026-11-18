import { getLanguage, t } from "../lib/i18n";
import { isBundledPlugin, translatedFields } from "../lib/localizePlugins";
import type { Plugin, Schema } from "../lib/types";

const prerequisites: Record<string, string> = {
  "Needs a list of allowed senders": "needs_senders",
  "Needs an imaps:// mailbox server": "needs_mailbox",
  "Needs a mailbox username": "needs_username",
  "Set the mailbox password in the environment": "needs_mailbox_password",
  "Set the bot token in the environment": "needs_bot_token",
  "Needs attachment storage.": "needs_attachments",
  "Plugin secrets are unavailable; check the environment.": "needs_secrets",
  "Needs the note search index. Check indexing in Settings → AI.":
    "needs_search_index",
};

const modelNeeds = {
  "a chat model": "a_chat_model",
  "an embedding model": "an_embedding_model",
  "a transcription model": "a_transcription_model",
  "a vision model": "a_vision_model",
} as const;

// Older servers supply English prerequisite prose. Match only bundled host messages.
export function pluginDiagnostic(plugin: Plugin | undefined, value: string) {
  if (!plugin || !isBundledPlugin(plugin) || getLanguage() === "en")
    return value;
  if (Object.hasOwn(prerequisites, value))
    return t(`pluginui.${prerequisites[value]}`);
  const model =
    /^Needs (a chat model|an embedding model|a transcription model|a vision model)\. (AI is off\.|Choose one in Settings → AI\.)$/.exec(
      value,
    );
  if (model) {
    const key = modelNeeds[model[1] as keyof typeof modelNeeds];
    return `${t("settingsplugins.needs_value1", { value1: t(`settingsplugins.${key}`) })}. ${t(model[2] === "AI is off." ? "aisettings.ai_is_off" : "pluginui.choose_model")}`;
  }
  if (
    /^The (chat|embed|transcribe|vision) model failed its connection check\./.test(
      value,
    )
  )
    return t("pluginui.model_connection_failed");
  if (value === "plugin_job_failed") return t("pluginui.plugin_job_failed");
  if (value === "plugin_removed") return t("settingsstatus.removed");
  if (value === "plugin_disabled" || value === "plugin_waiting")
    return t(`pluginui.${value}`);
  return value;
}

export function localizePluginSchema(plugin: Plugin, schema: Schema): Schema {
  if (!isBundledPlugin(plugin) || !schema.properties) return schema;
  return {
    ...schema,
    properties: Object.fromEntries(
      Object.entries(schema.properties).map(([name, prop]) => [
        name,
        translatedFields(prop, `plugin.${plugin.id}.settings.${name}`),
      ]),
    ),
  };
}
