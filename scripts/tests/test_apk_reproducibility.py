import importlib.util
import shutil
import subprocess
import tempfile
import types
import unittest
from pathlib import Path
from unittest.mock import patch

SPEC = importlib.util.spec_from_file_location(
    "apk_reproducibility",
    Path(__file__).resolve().parents[1] / "check-apk-reproducibility.py",
)
REPRODUCIBILITY = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(REPRODUCIBILITY)


class ReproducibilityTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)
        self.signed = self.root / "signed.apk"
        self.unsigned = self.root / "unsigned.apk"
        self.signed.write_bytes(b"synthetic signed bytes")
        self.unsigned.write_bytes(b"synthetic unsigned bytes")
        self.module = types.SimpleNamespace(APKSigCopierError=ValueError)

    def test_signature_copy_requires_successful_verifier_and_exact_bytes(self):
        self.module.do_copy = lambda signed, unsigned, target: shutil.copyfile(
            signed, target
        )
        with (
            patch.dict("sys.modules", {"apksigcopier": self.module}),
            patch.object(
                REPRODUCIBILITY.subprocess,
                "run",
                return_value=subprocess.CompletedProcess([], 0, "verified"),
            ) as verifier,
        ):
            result = REPRODUCIBILITY.verify(
                self.signed, self.unsigned, Path("/fixture/apksigner")
            )
        self.assertIs(result["reproduced"], True)
        self.assertTrue(verifier.call_args.kwargs["check"])
        self.assertEqual(
            verifier.call_args.args[0][1:4], ["verify", "--verbose", "--print-certs"]
        )

    def test_different_bytes_or_failed_verifier_never_pass(self):
        self.module.do_copy = lambda signed, unsigned, target: shutil.copyfile(
            unsigned, target
        )
        with (
            patch.dict("sys.modules", {"apksigcopier": self.module}),
            patch.object(
                REPRODUCIBILITY.subprocess,
                "run",
                return_value=subprocess.CompletedProcess([], 0, "verified"),
            ),
            self.assertRaises(ValueError),
        ):
            REPRODUCIBILITY.verify(
                self.signed, self.unsigned, Path("/fixture/apksigner")
            )
        self.module.do_copy = lambda signed, unsigned, target: shutil.copyfile(
            signed, target
        )
        with (
            patch.dict("sys.modules", {"apksigcopier": self.module}),
            patch.object(
                REPRODUCIBILITY.subprocess,
                "run",
                side_effect=subprocess.CalledProcessError(1, "apksigner"),
            ),
            self.assertRaises(subprocess.CalledProcessError),
        ):
            REPRODUCIBILITY.verify(
                self.signed, self.unsigned, Path("/fixture/apksigner")
            )


if __name__ == "__main__":
    unittest.main()
