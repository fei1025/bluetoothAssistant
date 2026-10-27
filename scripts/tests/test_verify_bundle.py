"""Real JAR-signature regression with a disposable local test key; no production credentials."""
import hashlib
import shutil
import subprocess
import tempfile
import unittest
import zipfile
from pathlib import Path


def require_jdk_tools():
    missing = [tool for tool in ("java", "keytool", "jarsigner") if not shutil.which(tool)]
    if missing:
        raise RuntimeError("Required signature regression tools missing: " + ", ".join(missing))


class VerifyBundleTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        require_jdk_tools()
        cls.temporary = tempfile.TemporaryDirectory()
        cls.directory = Path(cls.temporary.name)
        cls.verifier = Path(__file__).resolve().parents[1] / "VerifyBundle.java"
        cls.unsigned = cls.directory / "unsigned.aab"
        with zipfile.ZipFile(cls.unsigned, "w") as archive:
            archive.writestr("BundleConfig.pb", b"test bundle config")
            archive.writestr("base/manifest/AndroidManifest.xml", b"test manifest")
        key = cls.directory / "offline-test.p12"
        # This password protects an ephemeral fixture only, never an upload key.
        cls.run_tool("keytool", "-genkeypair", "-alias", "offline", "-keyalg", "RSA", "-keystore", str(key),
                     "-storepass", "offline-fixture-password", "-keypass", "offline-fixture-password",
                     "-dname", "CN=Offline Test", "-validity", "1", "-storetype", "PKCS12")
        cls.signed = cls.directory / "signed.aab"
        shutil.copyfile(cls.unsigned, cls.signed)
        cls.run_tool("jarsigner", "-keystore", str(key), "-storepass", "offline-fixture-password",
                     str(cls.signed), "offline")
        certificate = cls.directory / "certificate.der"
        cls.run_tool("keytool", "-exportcert", "-alias", "offline", "-keystore", str(key),
                     "-storepass", "offline-fixture-password", "-file", str(certificate))
        cls.certificate = hashlib.sha256(certificate.read_bytes()).hexdigest()

    @classmethod
    def tearDownClass(cls):
        cls.temporary.cleanup()

    @staticmethod
    def run_tool(*args):
        return subprocess.run(args, check=True, capture_output=True, timeout=60)

    def accepted(self, bundle, certificate=None):
        return subprocess.run(["java", str(self.verifier), str(bundle), certificate or self.certificate],
                              capture_output=True, timeout=60).returncode == 0

    def test_signed_payload_is_accepted(self):
        self.assertTrue(self.accepted(self.signed))

    def test_unsigned_is_rejected(self):
        self.assertFalse(self.accepted(self.unsigned))

    def test_unsigned_appended_payload_is_rejected(self):
        changed = self.directory / "appended.aab"
        shutil.copyfile(self.signed, changed)
        with zipfile.ZipFile(changed, "a") as archive:
            archive.writestr("base/assets/untrusted.txt", b"untrusted")
        self.assertFalse(self.accepted(changed))

    def test_tampered_signed_payload_is_rejected(self):
        changed = self.directory / "tampered.aab"
        with zipfile.ZipFile(self.signed) as source, zipfile.ZipFile(changed, "w") as target:
            for entry in source.infolist():
                content = source.read(entry)
                if entry.filename == "BundleConfig.pb":
                    content += b"changed"
                target.writestr(entry, content)
        self.assertFalse(self.accepted(changed))

    def test_wrong_certificate_is_rejected(self):
        self.assertFalse(self.accepted(self.signed, "0" * 64))


class RequiredSignatureToolsTest(unittest.TestCase):
    def test_missing_jdk_tool_is_an_error_instead_of_skipping_signature_regressions(self):
        from unittest.mock import patch
        with patch.object(shutil, "which", side_effect=lambda tool: None if tool == "jarsigner" else tool):
            with self.assertRaisesRegex(RuntimeError, "jarsigner"):
                require_jdk_tools()
