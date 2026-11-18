import assert from "node:assert/strict";
import { execFileSync } from "node:child_process";
import { createHash } from "node:crypto";
import { readFileSync } from "node:fs";
import { createRequire } from "node:module";
import { join } from "node:path";

const web = new URL("../", import.meta.url);
const version = "4.0.7";
const patch = readFileSync(new URL(`patches/picomatch@${version}.patch`, web));
const hash = createHash("sha256").update(patch).digest("hex");
const locked = `${version}(patch_hash=${hash})`;
const lock = readFileSync(new URL("pnpm-lock.yaml", web), "utf8");
const copies = lock.split(/^---$/m).flatMap((document) => {
    const snapshots = document.split("\nsnapshots:\n")[1] ?? "";
    return [...snapshots.matchAll(/^ {2}['"]?picomatch@([^:'"\n]+)/gm)];
});
assert.deepEqual(
    copies.map((copy) => copy[1]),
    [locked],
    "Every locked picomatch snapshot must be patched.",
);

const graph = JSON.parse(
    execFileSync(
        "pnpm",
        ["list", "picomatch", "--depth", "Infinity", "--json"],
        {
            cwd: web,
            encoding: "utf8",
        },
    ),
);
const verified = new Set();
function inspect(parent) {
    for (const scope of [
        "dependencies",
        "devDependencies",
        "optionalDependencies",
    ]) {
        for (const [name, dependency] of Object.entries(parent[scope] ?? {})) {
            if (name === "picomatch" || dependency.from === "picomatch") {
                const requireDependency = createRequire(
                    join(parent.path, "package.json"),
                );
                const requirePackage = createRequire(
                    join(dependency.path, "package.json"),
                );
                assert.equal(
                    requireDependency.resolve(name),
                    requirePackage.resolve("./"),
                );
                assert.equal(requirePackage("./package.json").version, version);
                const modulePath = requireDependency.resolve(name);
                if (!verified.has(modulePath)) {
                    verifyPatterns(requireDependency(name));
                    verified.add(modulePath);
                }
            }
            inspect(dependency);
        }
    }
}

function verifyPatterns(picomatch) {
    assert(picomatch("src/**/*.{js,ts}")("src/nested/file.ts"));
    assert(!picomatch("src/**/*.{js,ts}")("src/nested/file.css"));
    assert(picomatch("@(draft|final).txt")("final.txt"));
    assert(picomatch("?(draft-)report.txt")("draft-report.txt"));
    assert(picomatch(String.raw`\+\(draft\).txt`)("+(draft).txt"));
    const disabled = {
        name: "SyntaxError",
        message: "Repeated extglobs are disabled",
    };
    for (const pattern of ["src/+(draft).txt", "src/*(draft).txt"]) {
        assert.throws(() => picomatch(pattern), disabled);
        assert.throws(() => picomatch.parse(pattern), disabled);
    }
}

graph.forEach(inspect);
assert(verified.size > 0, "Check picomatch after the frozen pnpm install.");
console.log(
    `Verified the picomatch mitigation on ${verified.size} referenced installation(s).`,
);
