from pathlib import Path
import shutil
import subprocess
import unittest

@unittest.skipUnless(shutil.which('node'), 'Node.js required for SDK runtime checks')
class PluginSdkTest(unittest.TestCase):
    def test_binary_promises_limits_and_events(self):
        root=Path(__file__).resolve().parents[2]
        subprocess.run(['node','scripts/tests/plugin_sdk_test.js'],cwd=root,check=True,timeout=15)
