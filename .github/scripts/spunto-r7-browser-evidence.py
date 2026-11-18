"""Replay R7 presentation regressions against the existing disposable image."""

import os
from pathlib import Path
import subprocess
from xml.etree import ElementTree as ET

WORKSPACE = Path.cwd()
SPEC = WORKSPACE / "web/e2e/new-features.spec.ts"
ARTIFACTS = Path(os.environ["RUNNER_TEMP"]) / "spunto-r7"
PROJECTS = ("chromium", "firefox", "webkit")
CASES = {
    "decision": ("decision journal creates", "toHaveText", "Outcome saved"),
    "context": ("context packs curate sources", "toBeVisible", "baseline 2"),
    "history": ("thinking history exposes", "toBeVisible", "Notes in creation order"),
}

HOOK = """
test.beforeEach(async ({ page }, testInfo) => {
  const variant = "__VARIANT__";
  const plugin = variant === "decision" ? "decision-journal"
    : variant === "context" ? "context-packs" : "thinking-desk";
  await page.route(`**/api/v1/plugins/${plugin}/commands/*`, async (route) => {
    if (route.request().method() !== "POST") return route.fallback();
    const response = await route.fetch();
    if (response.status() !== 200) return route.fulfill({ response });
    const original = await response.text();
    let display: string;
    if (variant === "decision") {
      const input = route.request().postDataJSON();
      if (!input || input.outcome !== "A new storage constraint needs another review.")
        return route.fulfill({ response });
      display = JSON.stringify({ type: "text", text: "Synthetic missing outcome presentation" });
    } else {
      const expected = variant === "context" ? "baseline 2 · current 2 · current" : "Notes in creation order";
      display = original.replaceAll(expected, "Synthetic changed presentation");
      if (display === original) return route.fulfill({ response });
    }
    const headers = response.headers();
    delete headers["content-length"];
    delete headers["content-encoding"];
    console.log("SPUNTO_R7_APPLIED:" + testInfo.project.name);
    return route.fulfill({ status: response.status(), headers, body: display });
  });
});
"""


def check(label, candidates, variant=None):
    output = ARTIFACTS / f"{label}.xml"
    output.unlink(missing_ok=True)
    command = [
        "docker", "run", "--rm", "--init", "--network", "host", "--shm-size=1g",
        "--env", "CI=true", "--env", "SPUNTO_E2E_URL=http://127.0.0.1:18081",
        "--env", f"PLAYWRIGHT_JUNIT_OUTPUT_FILE=/artifacts/{label}.xml",
        "--volume", f"{WORKSPACE}:{WORKSPACE}:ro", "--volume", f"{ARTIFACTS}:/artifacts",
    ]
    if variant:
        probe = ARTIFACTS / f"probe-{variant}.ts"
        source = SPEC.read_text()
        anchor = "type Output = {"
        assert source.count(anchor) == 1
        probe.write_text(source.replace(anchor, HOOK.replace("__VARIANT__", variant) + anchor))
        command += ["--volume", f"{probe}:{SPEC}:ro"]
    command += [
        "--workdir", str(WORKSPACE / "web"), os.environ["PLAYWRIGHT_IMAGE"],
        "node", "node_modules/@playwright/test/cli.js", "test", "e2e/new-features.spec.ts",
        "--reporter=line,junit", f"--output=/artifacts/results-{label}",
        "--global-timeout=180000", "--workers=1", "--retries=0",
        "--grep", "|".join(CASES[candidate][0] for candidate in candidates),
        *(f"--project={project}" for project in PROJECTS),
    ]
    log = ARTIFACTS / f"{label}.log"
    with log.open("w") as stream:
        result = subprocess.run(command, stdout=stream, stderr=subprocess.STDOUT, timeout=210)
    suite = ET.parse(output).getroot()
    cases = list(suite.iter("testcase"))
    assert len(cases) == len(PROJECTS) * len(candidates), (label, len(cases))
    for case in cases:
        assert sum(CASES[candidate][0] in case.attrib["name"] for candidate in candidates) == 1, case.attrib
        assert case.find("error") is None and case.find("skipped") is None, case.attrib
        failure = case.find("failure")
        if variant:
            assert failure is not None, case.attrib
            message = failure.get("message", "") + (failure.text or "")
            _, assertion, expected = CASES[variant]
            assert assertion in message and expected in message, message
        else:
            assert failure is None, case.attrib
    assert result.returncode == (1 if variant else 0), (label, result.returncode)
    if variant:
        text = log.read_text()
        assert all(f"SPUNTO_R7_APPLIED:{project}" in text for project in PROJECTS), label
    print(f"{label}: {len(cases)} named cases, {len(cases) if variant else 0} intended failures", flush=True)


if __name__ == "__main__":
    ARTIFACTS.mkdir(parents=True, exist_ok=True)
    check("control", tuple(CASES))
    for variant in CASES:
        check(variant, (variant,), variant=variant)
    check("restored", tuple(CASES))
