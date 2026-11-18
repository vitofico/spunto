"""One-time scoped cleanup evidence for the disposable CI test database."""

import subprocess
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

out = Path(sys.argv[1])
out.mkdir()
fixture = Path("tests/conftest.py")
original = fixture.read_bytes()
anchor = b"    async def remove(self, conn):\n"
assert original.count(anchor) == 1
no_op = anchor + b"        if self.admin is None:\n            return\n"
selector = (
    "tests/test_hosted.py::"
    "test_account_closure_requires_choice_revokes_credentials_and_purges_only_owned"
)

for label, expected in (("control", 0), ("scoped-cleanup-no-op", 1)):
    xml = out / f"{label}.xml"
    if expected:
        # Only the rollback probe uses an unbound cleanup scope. Real fixture
        # teardown still runs, so the mutant leaves no synthetic rows behind.
        fixture.write_bytes(original.replace(anchor, no_op, 1))
    try:
        with (out / f"{label}.log").open("wb") as log:
            result = subprocess.run(
                [sys.executable, "-B", "-m", "pytest", "-q", f"--junitxml={xml}", selector],
                stdout=log,
                stderr=subprocess.STDOUT,
                timeout=120,
            )
    finally:
        fixture.write_bytes(original)
    suite, = ET.parse(xml).getroot().findall("testsuite")
    counts = tuple(int(suite.attrib[key]) for key in ("tests", "failures", "errors", "skipped"))
    print(label, counts, suite.attrib["time"])
    assert result.returncode == expected and counts == (1, expected, 0, 0)
    if expected:
        failure, = suite.findall("testcase/failure")
        assert "cleanup must remove only the selected synthetic workspace owner" in failure.attrib[
            "message"
        ]
