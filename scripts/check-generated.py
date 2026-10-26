#!/usr/bin/env python3
"""Check generated contracts against the working tree without retaining rewrites."""

from pathlib import Path
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]
OUTPUTS = (
    "web/src/lib/openapi.json",
    "web/src/lib/api-types.ts",
    "web/src/lib/theme-catalog.ts",
    "web/src/styles/tokens.css",
    "web/src/styles/theme-previews.css",
    "server/src/spunto/host/theme_tokens.json",
    "server/src/spunto/host/theme_catalog.json",
    "android/core/design/src/main/java/io/theficos/spunto/core/design/Tokens.kt",
)


def main() -> int:
    original = {
        name: (ROOT / name).read_bytes() if (ROOT / name).exists() else None
        for name in OUTPUTS
    }
    try:
        for command in (
            ["pnpm", "generate:api"],
            ["pnpm", "generate:tokens"],
            [sys.executable, "../android/core/design/generate-tokens.py"],
        ):
            subprocess.run(command, cwd=ROOT / "web", check=True)
        changed = [
            name for name, content in original.items()
            if (ROOT / name).read_bytes() != content
        ]
        if changed:
            print("Generated files are stale:\n" + "\n".join(changed), file=sys.stderr)
            print(
                "Run pnpm generate:api && pnpm generate:tokens from web/, then "
                "python3 android/core/design/generate-tokens.py from the root.",
                file=sys.stderr,
            )
            return 1
        return 0
    finally:
        # Keep preflight read-only even when generation fails midway. Comparing
        # with these bytes also permits unrelated, uncommitted feature work.
        for name, content in original.items():
            path = ROOT / name
            if content is None:
                path.unlink(missing_ok=True)
            elif not path.exists() or path.read_bytes() != content:
                path.write_bytes(content)


if __name__ == "__main__":
    sys.exit(main())
