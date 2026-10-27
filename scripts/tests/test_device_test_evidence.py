"""Device evidence must identify the APK and never turn incomplete/failed runs into a pass."""

import contextlib
import hashlib
import importlib.util
import io
import json
import subprocess
import tempfile
import unittest
from pathlib import Path


SPEC = importlib.util.spec_from_file_location(
    "device_test_evidence", Path(__file__).resolve().parents[1] / "device_test_evidence.py")
evidence = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(evidence)


class DeviceTestEvidenceTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.repository = Path(self.temporary.name)
        self.build = self.repository / "app" / "build"
        for arguments in (("init",), ("config", "user.name", "Offline Test"),
                          ("config", "user.email", "offline@example.invalid")):
            subprocess.run(["git", "-C", str(self.repository), *arguments], check=True, capture_output=True)
        (self.repository / "fixture.txt").write_text("Offline fixture", encoding="utf-8")
        subprocess.run(["git", "-C", str(self.repository), "add", "fixture.txt"], check=True, capture_output=True)
        subprocess.run(["git", "-C", str(self.repository), "commit", "-m", "离线测试夹具"],
                       check=True, capture_output=True)
        self.revision = subprocess.check_output(["git", "-C", str(self.repository), "rev-parse", "HEAD"]).decode().strip()

    def apk(self, name="app-debug.apk", variant="debug", content=b"actual tested APK\x00\xff"):
        path = self.build / "outputs" / "apk" / variant / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(content)
        return path

    def report(self, xml='<testsuite tests="3" failures="0" errors="0" skipped="0"/>',
               name="TEST-fixture.xml", variant="debug"):
        path = self.build / "outputs" / "androidTest-results" / "connected" / variant / "flavors" / "device" / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(xml, encoding="utf-8")
        return path

    def collect(self, run_status="success", variant="debug"):
        return evidence.device_test_evidence(self.repository, self.build, variant, 36, run_status)

    def test_exact_apk_hash_and_nested_junit_aggregation(self):
        apk = self.apk(variant="release", name="app-release.apk")
        self.apk(variant="release", name="app-release-androidTest.apk", content=b"test APK")
        self.report(variant="release")
        self.report('<testsuites tests="5"><testsuite tests="5"><testsuite tests="2" failures="0" '
                    'errors="0" skipped="0"/><testsuite tests="3" failures="0" errors="0" skipped="0"/>'
                    '</testsuite></testsuites>', name="TEST-second.xml", variant="release")
        result = self.collect(variant="release")
        self.assertEqual(self.revision, result["gitSha"])
        self.assertEqual("outputs/apk/release/app-release.apk", result["artifact"]["file"])
        self.assertEqual(hashlib.sha256(apk.read_bytes()).hexdigest(), result["artifact"]["sha256"])
        self.assertEqual(apk.stat().st_size, result["artifact"]["bytes"])
        self.assertEqual({"tests": 8, "failures": 0, "errors": 0, "skipped": 0, "failed": 0, "passed": 8},
                         result["instrumentation"]["counts"])
        self.assertEqual(2, result["instrumentation"]["reportCount"])
        self.assertEqual("passed", result["result"])
        self.assertTrue(result["fullPassed"])

    def test_missing_reports_cannot_pass(self):
        self.apk()
        result = self.collect()
        self.assertEqual("missing", result["instrumentation"]["status"])
        self.assertIn("junit_reports_missing", result["errors"])
        self.assertEqual("unknown", result["result"])
        self.assertFalse(result["fullPassed"])

    def test_missing_apk_cannot_pass(self):
        self.report()
        result = self.collect()
        self.assertEqual("missing", result["artifact"]["status"])
        self.assertIsNone(result["artifact"]["sha256"])
        self.assertIn("app_apk_missing", result["errors"])
        self.assertFalse(result["fullPassed"])

    def test_multiple_app_apks_are_ambiguous_instead_of_selecting_one(self):
        self.apk()
        self.apk(name="app-debug-split.apk", content=b"split")
        self.report()
        result = self.collect()
        self.assertEqual("ambiguous", result["artifact"]["status"])
        self.assertEqual(2, len(result["artifact"]["candidates"]))
        self.assertIsNone(result["artifact"]["file"])
        self.assertIsNone(result["artifact"]["sha256"])
        self.assertEqual("unknown", result["result"])

    def test_failed_run_cannot_be_overridden_by_successful_reports(self):
        self.apk()
        self.report()
        result = self.collect(run_status="failure")
        self.assertEqual("failed", result["result"])
        self.assertIn("run_failure", result["resultReasons"])
        self.assertFalse(result["fullPassed"])

    def test_junit_failures_errors_and_skips_remain_visible(self):
        self.apk()
        self.report('<testsuite tests="5" failures="1" errors="1" skipped="2"/>')
        result = self.collect()
        self.assertEqual({"tests": 5, "failures": 1, "errors": 1, "skipped": 2, "failed": 2, "passed": 1},
                         result["instrumentation"]["counts"])
        self.assertEqual("failed", result["result"])

    def test_skipped_or_zero_tests_are_incomplete(self):
        self.apk()
        for xml, reason in (('<testsuite tests="3" failures="0" errors="0" skipped="1"/>', "tests_skipped"),
                            ('<testsuite tests="0" failures="0" errors="0" skipped="0"/>', "no_tests")):
            with self.subTest(reason=reason):
                self.report(xml)
                result = self.collect()
                self.assertEqual("incomplete", result["result"])
                self.assertIn(reason, result["resultReasons"])
                self.assertFalse(result["fullPassed"])

    def test_invalid_xml_keeps_valid_partial_counts_but_cannot_pass(self):
        self.apk()
        self.report()
        self.report("<testsuite", name="TEST-broken.xml")
        result = self.collect()
        self.assertEqual("invalid", result["instrumentation"]["status"])
        self.assertEqual(3, result["instrumentation"]["counts"]["tests"])
        self.assertEqual("unknown", result["result"])

    def test_failing_testcase_is_not_hidden_by_a_success_suite_header(self):
        self.apk()
        self.report('<testsuite tests="1" failures="0" errors="0" skipped="0">'
                    '<testcase name="failed"><failure/></testcase></testsuite>')
        result = self.collect()
        self.assertEqual(1, result["instrumentation"]["counts"]["failed"])
        self.assertEqual("failed", result["result"])

    def test_unknown_cancelled_and_skipped_outcomes_cannot_pass(self):
        self.apk()
        self.report()
        for status in ("unknown", "cancelled", "skipped"):
            with self.subTest(status=status):
                result = self.collect(run_status=status)
                self.assertEqual(status, result["result"])
                self.assertFalse(result["fullPassed"])

    def test_cli_writes_missing_evidence_without_overriding_run_failure(self):
        output = self.repository / "evidence" / "device-test-evidence.json"
        with contextlib.redirect_stdout(io.StringIO()):
            exit_code = evidence.main(["--repository", str(self.repository), "--build-directory", str(self.build),
                                       "--variant", "debug", "--api", "23", "--run-status", "failure",
                                       "--output", str(output)])
        result = json.loads(output.read_text(encoding="utf-8"))
        self.assertEqual(0, exit_code)
        self.assertEqual(23, result["api"])
        self.assertEqual("failure", result["runStatus"])
        self.assertEqual("failed", result["result"])
        self.assertIn("app_apk_missing", result["errors"])
        self.assertIn("junit_reports_missing", result["errors"])

    def test_cli_defaults_to_unknown_even_with_complete_reports(self):
        self.apk()
        self.report()
        output = self.repository / "device-test-evidence.json"
        with contextlib.redirect_stdout(io.StringIO()):
            exit_code = evidence.main(["--repository", str(self.repository), "--build-directory", str(self.build),
                                       "--variant", "debug", "--api", "36", "--output", str(output)])
        result = json.loads(output.read_text(encoding="utf-8"))
        self.assertEqual(0, exit_code)
        self.assertEqual("unknown", result["runStatus"])
        self.assertFalse(result["fullPassed"])


if __name__ == "__main__":
    unittest.main()
