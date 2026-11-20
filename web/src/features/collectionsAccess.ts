import { isBundledPlugin } from "../lib/localizePlugins";
import type { Plugin } from "../lib/types";

export function supportsCollections(plugin: Plugin) {
  const [major, minor] = (plugin.version ?? "").split(".").map(Number);
  return (
    plugin.id === "context-packs" &&
    isBundledPlugin(plugin) &&
    (plugin.state === undefined || plugin.state === "enabled") &&
    (major > 1 || (major === 1 && minor >= 2))
  );
}
