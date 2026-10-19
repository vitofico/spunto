import { readFileSync, readdirSync, statSync } from "node:fs";
import { gzipSync } from "node:zlib";
const manifest = JSON.parse(readFileSync("dist/.vite/manifest.json", "utf8"));
const files = new Set();
const visited = new Set();
function visit(key) {
    if (visited.has(key)) return;
    visited.add(key);
    const chunk = manifest[key];
    if (!chunk) throw new Error(`Missing chunk ${key}`);
    files.add(chunk.file);
    for (const dependency of chunk.imports ?? []) visit(dependency);
}
for (const [key, chunk] of Object.entries(manifest))
    if (chunk.isEntry) visit(key);
const bytes = [...files]
    .filter((f) => f.endsWith(".js"))
    .reduce((sum, f) => sum + gzipSync(readFileSync(`dist/${f}`)).length, 0);
const fonts = readdirSync("public/fonts")
    .filter((f) => f.endsWith(".woff2"))
    .reduce((sum, f) => sum + statSync(`public/fonts/${f}`).size, 0);
console.log(
    `Initial JavaScript: ${bytes} bytes gzip / 150000; fonts: ${fonts} / 320000`,
);
if (bytes > 150000 || fonts > 320000) process.exit(1);
