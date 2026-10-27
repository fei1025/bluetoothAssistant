import base64
import hashlib
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import ci_debug_signing as signing


class CiDebugSigningTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.fixture = tempfile.TemporaryDirectory()
        cls.keystore = Path(cls.fixture.name) / 'fixture.keystore'
        subprocess.run(['keytool', '-genkeypair', '-keystore', str(cls.keystore),
                        '-storepass', 'android', '-keypass', 'android', '-alias', 'androiddebugkey',
                        '-keyalg', 'RSA', '-keysize', '2048', '-validity', '30',
                        '-dname', 'CN=CI Test Fixture', '-noprompt'], capture_output=True, check=True)
        certificate = subprocess.run(['keytool', '-exportcert', '-keystore', str(cls.keystore),
                                      '-storepass', 'android', '-alias', 'androiddebugkey'],
                                     capture_output=True, check=True).stdout
        cls.digest = hashlib.sha256(certificate).hexdigest()
        cls.secret = base64.b64encode(cls.keystore.read_bytes()).decode('ascii')

    @classmethod
    def tearDownClass(cls):
        cls.fixture.cleanup()

    def setUp(self):
        self.folder = tempfile.TemporaryDirectory()
        self.addCleanup(self.folder.cleanup)
        self.destination = Path(self.folder.name) / 'debug.keystore'
        self.destination.write_bytes(b'original identity must survive invalid input')

    def test_missing_or_invalid_secret_preserves_existing_identity(self):
        original = self.destination.read_bytes()
        for value in ('', 'not base64!', base64.b64encode(b'not a keystore').decode(), 'a' * (128 * 1024 + 1)):
            with self.subTest(value_length=len(value)):
                with self.assertRaises(ValueError):
                    signing.restore(value, self.destination, self.digest)
                self.assertEqual(original, self.destination.read_bytes())
                self.assertEqual([self.destination], list(Path(self.folder.name).iterdir()))

    def test_wrong_certificate_cannot_rotate_identity(self):
        original = self.destination.read_bytes()
        with self.assertRaisesRegex(ValueError, 'refusing key rotation'):
            signing.restore(self.secret, self.destination, '0' * 64)
        self.assertEqual(original, self.destination.read_bytes())

    def test_valid_secret_restores_identical_key_on_repeated_runs(self):
        for _ in range(2):
            self.assertEqual(self.digest, signing.restore(self.secret, self.destination, self.digest))
            self.assertEqual(self.keystore.read_bytes(), self.destination.read_bytes())

    def test_only_the_pinned_successfully_verified_apk_can_publish(self):
        for status, output in ((1, ''), (0, ''), (0, 'Signer #1 certificate SHA-256 digest: ' + '0' * 64),
                               (0, ('Signer #1 certificate SHA-256 digest: ' + self.digest + '\n') * 2)):
            with patch.object(signing.subprocess, 'run', return_value=subprocess.CompletedProcess([], status, output)):
                with self.assertRaises(ValueError):
                    signing.verify_apk('test.apk', 'apksigner', self.digest)
        with patch.object(signing.subprocess, 'run', return_value=subprocess.CompletedProcess(
                [], 0, 'Signer #1 certificate SHA-256 digest: ' + self.digest + '\n')):
            self.assertEqual(self.digest, signing.verify_apk('test.apk', 'apksigner', self.digest))


if __name__ == '__main__':
    unittest.main()
