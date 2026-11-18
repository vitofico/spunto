import { hasTranslation, t } from "./i18n";
import type { Plugin } from "./types";

export function translatedFields<T extends object>(value: T, key: string): T {
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
/** Operator installations retain control of their UI copy. */
export function isBundledPlugin(plugin: Plugin, provenance?: Plugin) {
  return (
    plugin.origin !== "operator" &&
    plugin.source_kind !== "folder" &&
    plugin.source_kind !== "entrypoint" &&
    ((plugin.origin ?? provenance?.origin) === "bundled" ||
      (plugin.source_kind ?? provenance?.source_kind) === "bundled")
  );
}

export function localizePlugin(plugin: Plugin, provenance?: Plugin): Plugin {
  const key = `plugin.${plugin.id}`;
  if (!hasTranslation(`${key}.name`) || !isBundledPlugin(plugin, provenance))
    return plugin;
  const panel = plugin.contributes?.panel;
  return {
    ...plugin,
    origin: "bundled",
    name: t(`${key}.name`),
    description: t(`${key}.description`),
    contributes: plugin.contributes
      ? {
          ...plugin.contributes,
          panel:
            panel && typeof panel === "object"
              ? translatedFields(panel, `${key}.panel`)
              : panel,
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
