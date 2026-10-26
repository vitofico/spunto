#!/usr/bin/env python3
"""Copy the canonical ink exports without changing their pixels."""

import argparse
import base64
import re
import shutil
from pathlib import Path

root = Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--check", action="store_true")
check = parser.parse_args().check
encoded = root / "branding/spunto-woodgrain.webp"
wrapper = root / "branding/spunto-woodgrain-mask.svg"
embedded = re.search(
    r'href="data:image/webp;base64,([^"]+)"', wrapper.read_text()
).group(1)
if base64.b64decode(embedded) != encoded.read_bytes():
    raise SystemExit("The SVG mask must embed the canonical compressed ink export.")
exports = {
    root / "web/public/spunto-woodgrain.webp": encoded,
    root / "web/public/spunto-woodgrain-mask.svg": wrapper,
    root
    / "android/core/design/src/main/res/drawable-nodpi/spunto_woodgrain.webp": encoded,
}
for target, source in exports.items():
    if check:
        if not target.exists() or target.read_bytes() != source.read_bytes():
            raise SystemExit(f"Ink artwork export differs: {target.relative_to(root)}")
    else:
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(source, target)
print(
    f"Ink artwork exports {'match' if check else 'synchronized'} ({len(exports)} files)"
)
