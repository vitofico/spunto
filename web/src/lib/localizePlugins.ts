import { hasTranslation, t } from "./i18n";
import type { Plugin, Schema } from "./types";

function translatedFields<T extends object>(value: T, key: string): T {
  const fields = ["title", "description"].filter((field) =>
    hasTranslation(`${key}.${field}`),
  );
  return fields.length
    ? {
        ...value,
        ...Object.fromEntries(
          fields.map((field) => [field, t(`${key}.${field}`)]),
        ),
      }
    : value;
}
/** Only bundled IDs own these display strings. Third-party metadata and output stay intact. */
export function localizePlugin(plugin: Plugin, provenance?: Plugin): Plugin {
  const key = `plugin.${plugin.id}`;
  if (
    !hasTranslation(`${key}.name`) ||
    plugin.origin === "operator" ||
    plugin.source_kind === "folder" ||
    plugin.source_kind === "entrypoint" ||
    ((plugin.origin ?? provenance?.origin) !== "bundled" &&
      (plugin.source_kind ?? provenance?.source_kind) !== "bundled")
  )
    return plugin;
  const settings = plugin.settings as Schema | undefined;
  return {
    ...plugin,
    name: t(`${key}.name`),
    description: t(`${key}.description`),
    settings: settings?.properties
      ? {
          ...settings,
          properties: Object.fromEntries(
            Object.entries(settings.properties).map(([name, prop]) => [
              name,
              translatedFields(prop, `${key}.settings.${name}`),
            ]),
          ),
        }
      : settings,
    contributes: plugin.contributes
      ? {
          ...plugin.contributes,
          commands: plugin.contributes.commands?.map((command) =>
            translatedFields(command, `${key}.commands.${command.id}`),
          ),
          views: plugin.contributes.views?.map((view) =>
            translatedFields(view, `${key}.views.${view.id}`),
          ),
          mcp_tools: plugin.contributes.mcp_tools?.map((tool) =>
            translatedFields(tool, `${key}.mcp_tools.${tool.name}`),
          ),
        }
      : undefined,
  };
}
export function localizeCatalog<T extends { plugins: Plugin[] }>(
  catalog: T,
  provenance: Plugin[] = [],
): T {
  return {
    ...catalog,
    plugins: catalog.plugins.map((plugin) =>
      localizePlugin(
        plugin,
        provenance.find((item) => item.id === plugin.id),
      ),
    ),
  };
}
