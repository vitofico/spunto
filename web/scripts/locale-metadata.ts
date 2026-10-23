import { readFileSync } from "node:fs";
import type { Plugin } from "vite";

/** Keep automatic language names in the entry bundle and catalog bodies in lazy chunks. */
export function localeMetadata(): Plugin {
  const query = "?locale-metadata";
  const prefix = "\0spunto-locale-metadata:";
  return {
    name: "spunto-locale-metadata",
    enforce: "pre",
    async resolveId(source, importer) {
      if (!source.endsWith(query)) return;
      const resolved = await this.resolve(
        source.slice(0, -query.length),
        importer,
        { skipSelf: true },
      );
      if (!resolved) throw new Error(`Missing locale catalog: ${source}`);
      return prefix + resolved.id + ".js";
    },
    load(id) {
      if (!id.startsWith(prefix)) return;
      const file = id.slice(prefix.length, -3);
      this.addWatchFile(file);
      const { nativeName } = JSON.parse(readFileSync(file, "utf8"));
      if (typeof nativeName !== "string" || !nativeName.trim())
        throw new Error(`Missing nativeName in ${file}`);
      return `export default ${JSON.stringify(nativeName)};`;
    },
  };
}
