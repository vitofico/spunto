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
    `Initial JavaScript: ${bytes} bytes gzip / 160000 (target 150000); fonts: ${fonts} / 320000`,
);
// Keep the target visible without rejecting tiny compression/toolchain changes.
// The hard ceiling still catches a meaningful increase in the initial download.
if (bytes > 150000) {
    console.warn(
        "Initial JavaScript exceeds the 150000-byte target; keep growth below the 160000-byte ceiling.",
    );
}
if (bytes > 160000 || fonts > 320000) process.exit(1);
