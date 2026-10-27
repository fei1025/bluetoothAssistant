"""Evidence regression: source drift, credentials exclusion and real test summaries."""
import importlib.util
import json
import subprocess
import tempfile
import unittest
from pathlib import Path

SPEC = importlib.util.spec_from_file_location("build_info", Path(__file__).resolve().parents[1] / "build_info.py")
info = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(info)


class BuildInfoTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.directory = Path(self.temporary.name)
        def git(*arguments):
            subprocess.run(["git", "-C", str(self.directory), *arguments], check=True, capture_output=True)
        git("init")
        git("config", "user.name", "Offline Test")
        git("config", "user.email", "offline@example.invalid")
        (self.directory / "app.java").write_text("class Test {}", encoding="utf-8")
        git("add", "app.java")
        git("commit", "-m", "离线测试夹具")

    def test_source_change_has_distinct_fingerprint_and_dirty_state(self):
        before = info.source_snapshot(self.directory)
        (self.directory / "app.java").write_text("class Test { int changed; }", encoding="utf-8")
        after = info.source_snapshot(self.directory)
        self.assertFalse(before["dirty"])
        self.assertTrue(after["dirty"])
        self.assertNotEqual(before["sourceFingerprint"], after["sourceFingerprint"])

    def test_credential_files_are_excluded_from_source_reader(self):
        (self.directory / "google-services.json").write_text("never read", encoding="utf-8")
        (self.directory / "play-service-account.json").write_text("never read", encoding="utf-8")
        self.assertEqual(["app.java"], [entry["path"] for entry in info.source_snapshot(self.directory)["sourceFiles"]])
    def test_plugin_resources_and_signed_packages_participate_in_source_fingerprint(self):
        before=info.source_snapshot(self.directory)
        names=['sdk.js','index.html','style.css','sdk.d.ts','reviewed.btplugin','author.pub']
        for name in names:(self.directory/name).write_bytes(b'public plugin input')
        (self.directory/'author.pk8').write_bytes(b'private key must never be read')
        after=info.source_snapshot(self.directory)
        self.assertNotEqual(before['sourceFingerprint'],after['sourceFingerprint'])
        self.assertEqual(set(names+['app.java']),{entry['path'] for entry in after['sourceFiles']})

    def test_junit_summary_keeps_failures_and_skips(self):
        report = self.directory / "TEST-fixture.xml"
        report.write_text('<testsuite tests="5" failures="1" errors="0" skipped="2"/>', encoding="utf-8")
        self.assertEqual({"tests": 5, "failures": 1, "errors": 0, "skipped": 2}, info.junit_summary(self.directory))

    def test_write_rejects_sources_changed_after_capture(self):
        before = self.directory / "source.json"
        captured = info.source_snapshot(self.directory)
        # Keep the evidence fixture outside the repository snapshot.
        external = self.directory.parent / (self.directory.name + "-evidence.json")
        self.addCleanup(lambda: external.unlink(missing_ok=True))
        info.write_json(external, captured)
        (self.directory / "app.java").write_text("changed", encoding="utf-8")
        with self.assertRaisesRegex(ValueError, "Source changed"):
            info.build_info(self.directory, external, self.directory, self.directory / "missing.aab")

    def evidence_fixture(self):
        external = tempfile.TemporaryDirectory()
        self.addCleanup(external.cleanup)
        build = Path(external.name)
        before = build / "source-before.json"
        info.write_json(before, info.source_snapshot(self.directory))
        info.write_json(build / "outputs/release-pipeline/metadata.json", {"versionCode": 1})
        unit = build / "test-results/testDebugUnitTest/TEST-fixture.xml"
        unit.parent.mkdir(parents=True)
        unit.write_text('<testsuite tests="3" failures="0" errors="0" skipped="0"/>', encoding="utf-8")
        lint = build / "reports/lint-results-release.xml"
        lint.parent.mkdir(parents=True)
        lint.write_text('<issues><issue severity="Warning"/></issues>', encoding="utf-8")
        artifact = build / "unsigned.aab"
        artifact.write_bytes(b"unsigned fixture")
        return before, build, artifact

    def test_standalone_write_does_not_claim_signature_or_scripts_were_verified(self):
        before, build, artifact = self.evidence_fixture()
        evidence = info.build_info(self.directory, before, build, artifact)
        self.assertEqual({"status": "unknown"}, evidence["checks"]["signature"])
        self.assertEqual({"status": "unknown"}, evidence["checks"]["offlineScripts"])
        self.assertEqual(3, evidence["checks"]["jvm"]["tests"])

    def test_explicit_success_requires_the_actual_command_logs(self):
        before, build, artifact = self.evidence_fixture()
        with self.assertRaisesRegex(ValueError, "requires.*evidence log"):
            info.build_info(self.directory, before, build, artifact, signature_status="verified")
        with self.assertRaisesRegex(ValueError, "requires.*evidence log"):
            info.build_info(self.directory, before, build, artifact, offline_scripts_status="passed")

    def test_explicit_completed_checks_include_evidence_fingerprints(self):
        before, build, artifact = self.evidence_fixture()
        signature, scripts = build / "verify.log", build / "script-tests.log"
        signature.write_text("Actual verifier exit 0\n", encoding="utf-8")
        scripts.write_text("Actual unittest exit 0\n", encoding="utf-8")
        evidence = info.build_info(self.directory, before, build, artifact,
                                   signature_status="verified", signature_evidence=signature,
                                   offline_scripts_status="passed", offline_scripts_evidence=scripts)
        self.assertEqual("verified", evidence["checks"]["signature"]["status"])
        self.assertEqual(info.sha256(signature), evidence["checks"]["signature"]["evidence"]["sha256"])
        self.assertEqual("passed", evidence["checks"]["offlineScripts"]["status"])
        self.assertEqual(info.sha256(scripts), evidence["checks"]["offlineScripts"]["evidence"]["sha256"])

    def test_failed_or_empty_check_evidence_cannot_become_verified(self):
        before, build, artifact = self.evidence_fixture()
        evidence = info.build_info(self.directory, before, build, artifact,
                                   signature_status="failed", offline_scripts_status="failed")
        self.assertEqual("failed", evidence["checks"]["signature"]["status"])
        self.assertEqual("failed", evidence["checks"]["offlineScripts"]["status"])
        empty = build / "empty.log"
        empty.touch()
        with self.assertRaisesRegex(ValueError, "missing or empty"):
            info.build_info(self.directory, before, build, artifact,
                            signature_status="verified", signature_evidence=empty)
