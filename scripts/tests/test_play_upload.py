"""Offline only: python -m unittest discover -s scripts/tests -v

All publishing HTTP and credential objects are fakes. No SDK, Google account,
network, keystore, or installed google-auth/requests dependency is needed.
"""

import copy
import hashlib
import importlib.util
import io
import json
import sys
import tempfile
import unittest
import zipfile
from contextlib import redirect_stderr, redirect_stdout
from datetime import datetime, timezone, timedelta
from pathlib import Path
from unittest.mock import patch
from urllib.parse import urlsplit

SCRIPT = Path(__file__).resolve().parents[1] / "play_upload.py"
SPEC = importlib.util.spec_from_file_location("play_upload", SCRIPT)
play = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(play)


class FakeAPI:
    def __init__(self, config):
        self.config = config
        self.calls = []
        self.internal_summary = [{"releaseName": "2.7", "track": "internal",
                                  "activeArtifacts": [{"versionCode": 27}],
                                  "releaseLifecycleState": "RELEASE_LIFECYCLE_STATE_PUBLISHED"}]
        self.production_summary = [{"releaseName": "preserve production draft", "track": "production",
                                    "activeArtifacts": [{"versionCode": 26}],
                                    "releaseLifecycleState": "RELEASE_LIFECYCLE_STATE_DRAFT"}]
        self.tracks = [{"track": "production", "releases": [{"name": "untouched", "status": "draft", "versionCodes": ["26"]}]},
                       {"track": "internal", "releases": [{"name": "2.7", "status": "completed", "versionCodes": ["27"],
                                                            "releaseNotes": [{"language": "en-US", "text": "Existing notes"}],
                                                            "inAppUpdatePriority": 2}]}]
        self.bundles = [{"versionCode": 27, "sha256": "0" * 64}]
        self.apks = []
        self.fail = None
        self.upload_override = None
        self.edit_expired = False
        self.mutate_production_in_edit = False
        self.mutate_production_outside_edit = False
        self.normalize_internal_order = False
        self.upload_location = (f"{play.UPLOAD_ROOT}/applications/{config['packageName']}/edits/edit123/bundles"
                                "?uploadType=resumable&upload_id=secret-session-capability")

    def call(self, method, url, **kwargs):
        path = urlsplit(url).path
        record = {"method": method, "url": url, "body": copy.deepcopy(kwargs.get("body"))}
        self.calls.append(record)
        if self.fail:
            self.fail(method, url, kwargs)
        if "/edits/" not in path and path.endswith("/tracks/internal/releases"):
            return {"releases": copy.deepcopy(self.internal_summary)}, {}
        if "/edits/" not in path and path.endswith("/tracks/production/releases"):
            result = copy.deepcopy(self.production_summary)
            if self.mutate_production_outside_edit and sum(call["url"].endswith("/tracks/production/releases") for call in self.calls) > 1:
                result[0]["releaseName"] = "changed by Console"
            return {"releases": result}, {}
        if method == "POST" and path.endswith("/edits"):
            return {"id": "edit123"}, {}
        if method == "GET" and path.endswith("/edits/edit123"):
            if self.edit_expired:
                raise play.UploadError("Google API returned HTTP 404.", http_status=404)
            return {"id": "edit123"}, {}
        if method == "POST" and "uploadType=resumable" in url:
            return {}, {"Location": self.upload_location}
        if method == "PUT" and "upload_id=" in url:
            content = kwargs["data"].read()
            record["uploadedBytes"] = len(content)
            result = self.upload_override or {"versionCode": self.config["versionCode"], "sha256": hashlib.sha256(content).hexdigest()}
            self.bundles.append(copy.deepcopy(result))
            return result, {}
        if method == "GET" and path.endswith("/bundles"):
            return {"bundles": copy.deepcopy(self.bundles)}, {}
        if method == "GET" and path.endswith("/apks"):
            return {"apks": copy.deepcopy(self.apks)}, {}
        if method == "GET" and path.endswith("/tracks"):
            return {"tracks": copy.deepcopy(self.tracks)}, {}
        if method == "GET" and path.endswith("/tracks/internal"):
            return copy.deepcopy(next(track for track in self.tracks if track["track"] == "internal")), {}
        if method == "PUT" and path.endswith("/tracks/internal"):
            self.tracks[1] = copy.deepcopy(kwargs["body"])
            if self.normalize_internal_order:
                self.tracks[1]["releases"][-1]["releaseNotes"].reverse()
                self.tracks[1]["releases"][-1]["inAppUpdatePriority"] = 0
                self.tracks[1]["releases"].reverse()
            if self.mutate_production_in_edit:
                self.tracks[0]["releases"][0]["status"] = "completed"
            return copy.deepcopy(kwargs["body"]), {}
        if method == "POST" and path.endswith(":validate"):
            return {"id": "edit123"}, {}
        if method == "POST" and path.endswith(":commit"):
            return {"id": "edit123"}, {}
        raise AssertionError("Unexpected fake API request")


class UploadTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.directory = Path(self.temporary.name)
        self.bundle = self.directory / "release.aab"
        with zipfile.ZipFile(self.bundle, "w") as archive:
            archive.writestr("BundleConfig.pb", b"fixture")
            archive.writestr("base/manifest/AndroidManifest.xml", b"offline protobuf placeholder")
            archive.writestr("base/dex/classes.dex", bytes(range(256)) * 8)
        self.config_path = self.directory / "upload.json"
        self.raw_config = {"packageName": "com.zzf.bluetoothsmp", "versionCode": 28, "bundlePath": "release.aab",
                           "releaseName": "2.8", "releaseNotes": [{"language": "zh-CN", "text": "修复传输\n支持中文"},
                                                                    {"language": "en-US", "text": "Fix transfer\nKeep English"}]}
        self.write_config()
        self.config = play.load_config(self.config_path)
        self.api = FakeAPI(self.config)
        self.receipt_path = self.directory / "receipt.json"

    def write_config(self):
        self.config_path.write_text(json.dumps(self.raw_config, ensure_ascii=False), encoding="utf-8-sig")

    def run_upload(self, review_evidence=None):
        return play.execute_upload(self.config, self.receipt_path, lambda: self.api, review_evidence)

    def review_evidence(self, **changes):
        evidence = {"packageName": self.config["packageName"], "versionCode": self.config["versionCode"],
                    "checkedAt": datetime.now(timezone.utc).isoformat(), "publicationOverviewChecked": True,
                    "pendingChanges": ["Data safety", "Open-test country availability"], "acceptsReviewSubmission": True}
        evidence.update(changes)
        path = self.directory / ("review-evidence-%s.json" % len(list(self.directory.glob("review-evidence*.json"))))
        path.write_text(json.dumps(evidence), encoding="utf-8")
        return path

    def receipt(self):
        return json.loads(self.receipt_path.read_text(encoding="utf-8"))

    def count_calls(self, method, suffix):
        return sum(call["method"] == method and urlsplit(call["url"]).path.endswith(suffix) for call in self.api.calls)

    def test_success_preserves_completed_and_production_draft_and_bilingual_notes(self):
        original_production = copy.deepcopy(self.api.tracks[0])
        original_completed = copy.deepcopy(self.api.tracks[1]["releases"][0])
        result = self.run_upload()
        self.assertEqual("committed", result["status"])
        self.assertEqual(original_production, self.api.tracks[0])
        self.assertEqual(original_completed, self.api.tracks[1]["releases"][0])
        new = self.api.tracks[1]["releases"][1]
        self.assertEqual("draft", new["status"])
        self.assertEqual(["28"], new["versionCodes"])
        self.assertEqual(self.raw_config["releaseNotes"], new["releaseNotes"])
        self.assertEqual(0, self.count_calls("PUT", "/tracks/production"))
        self.assertEqual(1, self.count_calls("POST", ":commit"))
        commit = next(call for call in self.api.calls if ":commit?" in call["url"])
        self.assertIn("changesNotSentForReview=true", commit["url"])
        self.assertIn("changesInReviewBehavior=ERROR_IF_IN_REVIEW", commit["url"])
        self.assertIsNone(commit["body"])
        operations = [urlsplit(call["url"]).path for call in self.api.calls]
        self.assertLess(operations.index(operations[-4]), len(operations) - 1)
        self.assertTrue(operations[-4].endswith(":validate"))
        receipt = self.receipt()
        self.assertEqual("committed", receipt["status"])
        self.assertEqual(self.config["sha256"], receipt["uploadedBundle"]["sha256"])
        self.assertNotIn("upload_id", self.receipt_path.read_text(encoding="utf-8"))
        self.assertNotIn("secret-session-capability", self.receipt_path.read_text(encoding="utf-8"))

    def test_config_defaults_are_internal_draft_and_plan_never_loads_credentials(self):
        with patch.object(play.GoogleTransport, "create", side_effect=AssertionError("Auth forbidden")):
            output = io.StringIO()
            with redirect_stdout(output):
                self.assertEqual(0, play.main(["plan", "--config", str(self.config_path)]))
        self.assertEqual("offline_validated", json.loads(output.getvalue())["status"])
        self.assertEqual("internal", self.config["track"])
        self.assertEqual("draft", self.config["status"])

    def test_invalid_inputs_cannot_enable_rollout_or_inject_credentials(self):
        changes = [{"track": "production"}, {"status": "completed"}, {"packageName": "x/../../production"},
                   {"versionCode": "28"}, {"versionCode": True}, {"versionCode": 0}, {"versionCode": 2100000001},
                   {"private_key": "never allowed"}, {"releaseNotes": [{"language": "zh_CN", "text": "x"}]},
                   {"releaseName": "bad\nname"}, {"sha256": "0" * 64}]
        base = copy.deepcopy(self.raw_config)
        for change in changes:
            with self.subTest(change=change):
                self.raw_config = dict(base, **change)
                self.write_config()
                with self.assertRaises(play.UploadError):
                    play.load_config(self.config_path)

    def test_duplicate_language_and_overlong_notes_fail_locally(self):
        for notes in ([{"language": "zh-CN", "text": "x"}, {"language": "zh-CN", "text": "y"}],
                      [{"language": "zh-CN", "text": "x" * 501}, {"language": "en-US", "text": "y"}]):
            self.raw_config["releaseNotes"] = notes
            self.write_config()
            with self.assertRaises(play.UploadError):
                play.load_config(self.config_path)

    def test_summary_version_conflict_fails_before_creating_edit(self):
        self.api.internal_summary[0]["activeArtifacts"][0]["versionCode"] = 28
        with self.assertRaisesRegex(play.UploadError, "highest observed code 28"):
            self.run_upload()
        self.assertEqual(0, self.count_calls("POST", "/edits"))
        self.assertEqual("failed", self.receipt()["status"])

    def test_unassigned_bundle_or_apk_conflict_fails_before_upload(self):
        for field in ("bundles", "apks"):
            with self.subTest(field=field):
                self.api = FakeAPI(self.config)
                setattr(self.api, field, [{"versionCode": 29}])
                self.receipt_path = self.directory / (field + ".json")
                self.receipt_path.parent.joinpath(".play-upload-com.zzf.bluetoothsmp.latest.json").unlink(missing_ok=True)
                with self.assertRaisesRegex(play.UploadError, "highest observed code 29"):
                    self.run_upload()
                self.assertEqual(1, self.count_calls("POST", "/edits"))
                self.assertEqual(0, self.count_calls("POST", "/bundles"))

    def test_existing_internal_draft_in_summary_fails_before_edit(self):
        self.api.internal_summary[0]["releaseLifecycleState"] = "RELEASE_LIFECYCLE_STATE_DRAFT"
        with self.assertRaisesRegex(play.UploadError, "existing draft"):
            self.run_upload()
        self.assertEqual(0, self.count_calls("POST", "/edits"))

    def test_existing_internal_draft_in_edit_is_not_overwritten(self):
        self.api.tracks[1]["releases"][0]["status"] = "draft"
        with self.assertRaisesRegex(play.UploadError, "unfinished release"):
            self.run_upload()
        self.assertEqual(0, self.count_calls("PUT", "/tracks/internal"))
        self.assertEqual(0, self.count_calls("POST", "/bundles"))

    def test_production_in_review_stops_before_edit(self):
        self.api.production_summary[0]["releaseLifecycleState"] = "RELEASE_LIFECYCLE_STATE_IN_REVIEW"
        with self.assertRaisesRegex(play.UploadError, "will not interrupt"):
            self.run_upload()
        self.assertEqual(0, self.count_calls("POST", "/edits"))

    def test_changed_production_prevents_commit(self):
        for attribute in ("mutate_production_in_edit", "mutate_production_outside_edit"):
            with self.subTest(attribute=attribute):
                self.api = FakeAPI(self.config)
                setattr(self.api, attribute, True)
                self.receipt_path = self.directory / (attribute + ".json")
                self.receipt_path.parent.joinpath(".play-upload-com.zzf.bluetoothsmp.latest.json").unlink(missing_ok=True)
                with self.assertRaisesRegex(play.UploadError, "commit refused"):
                    self.run_upload()
                self.assertEqual(0, self.count_calls("POST", ":commit"))

    def test_bundle_hash_or_version_mismatch_never_changes_track(self):
        for returned in ({"versionCode": 29, "sha256": self.config["sha256"]}, {"versionCode": 28, "sha256": "0" * 64}):
            with self.subTest(returned=returned):
                self.api = FakeAPI(self.config)
                self.api.upload_override = returned
                self.receipt_path = self.directory / (str(returned["versionCode"]) + ".json")
                self.receipt_path.parent.joinpath(".play-upload-com.zzf.bluetoothsmp.latest.json").unlink(missing_ok=True)
                with self.assertRaisesRegex(play.UploadError, "differs"):
                    self.run_upload()
                self.assertEqual(0, self.count_calls("PUT", "/tracks/internal"))

    def test_bundle_changed_after_plan_is_not_sent(self):
        self.bundle.write_bytes(b"replaced during build")
        with self.assertRaisesRegex(play.UploadError, "changed after local validation"):
            self.run_upload()
        self.assertEqual(0, self.count_calls("POST", "/bundles"))

    def test_upload_interruption_records_uncertainty_and_does_not_retry(self):
        def fail(method, url, kwargs):
            if method == "PUT" and "upload_id=" in url:
                raise play.UploadError("Transport interrupted; inspect receipt.", uncertain=True)
        self.api.fail = fail
        with self.assertRaises(play.UploadError):
            self.run_upload()
        self.assertEqual(1, sum(call["method"] == "PUT" for call in self.api.calls))
        self.assertEqual("uncertain", self.receipt()["status"])
        self.assertEqual("bundle_upload_started", self.receipt()["stage"])
        self.assertEqual(0, self.count_calls("POST", ":commit"))

    def test_lost_commit_response_never_retries_and_receipt_is_inspectable(self):
        def fail(method, url, kwargs):
            if ":commit?" in url:
                raise play.UploadError("Transport interrupted; inspect receipt.", uncertain=True)
        self.api.fail = fail
        with self.assertRaises(play.UploadError):
            self.run_upload()
        self.assertEqual(1, self.count_calls("POST", ":commit"))
        self.assertEqual("commit_started", self.receipt()["stage"])
        self.assertEqual("uncertain", self.receipt()["status"])
        self.api.fail = None
        original = self.receipt_path.read_bytes()
        self.api.calls.clear()
        result = play.inspect(self.api, self.receipt_path)
        self.assertTrue(result["bundleInEditMatches"])
        self.assertTrue(result["draftInEdit"])
        self.assertTrue(all(call["method"] == "GET" for call in self.api.calls))
        self.assertEqual(original, self.receipt_path.read_bytes())

    def test_commit_400_never_falls_back_to_sending_changes_for_review(self):
        def fail(method, url, kwargs):
            if ":commit?" in url:
                raise play.UploadError("Google API returned HTTP 400 INVALID_ARGUMENT.", http_status=400)
        self.api.fail = fail
        with self.assertRaises(play.UploadError):
            self.run_upload()
        self.assertEqual(1, self.count_calls("POST", ":commit"))
        self.assertEqual("failed", self.receipt()["status"])
        self.assertEqual("commit_started", self.receipt()["stage"])

    def test_existing_receipt_blocks_before_credentials_loaded(self):
        self.run_upload()
        with self.assertRaisesRegex(play.UploadError, "Receipt already exists"):
            play.execute_upload(self.config, self.receipt_path, lambda: self.fail("Must not load credentials"))

    def test_app_lock_blocks_second_process_and_releases_after_failure(self):
        with play.app_lock(self.receipt_path, self.config["packageName"]):
            with self.assertRaisesRegex(play.UploadError, "upload lock exists"):
                self.run_upload()
        self.run_upload()
        self.assertFalse(list(self.directory.glob("*.lock")))

    def test_expired_edit_inspection_does_not_claim_version_is_unused(self):
        self.run_upload()
        self.api.edit_expired = True
        self.api.calls.clear()
        result = play.inspect(self.api, self.receipt_path)
        self.assertFalse(result["editAccessible"])
        self.assertIn("does not prove the version is unused", result["guidance"])
        self.assertTrue(all(call["method"] == "GET" for call in self.api.calls))

    def test_exact_expired_400_can_be_inspected_and_reconciled_using_only_get(self):
        self.rejected_commit()
        response = FakeResponse(400, {"error": {"status": "FAILED_PRECONDITION",
                                               "message": play.EDIT_EXPIRED_API_MESSAGE}})
        session = FakeSession(response)
        transport = play.GoogleTransport(FakeCredentials(), session, None)
        def fail(method, url, kwargs):
            if method == "GET" and url.endswith("/edits/edit123"):
                transport.call(method, url)
        self.api.fail = fail
        self.api.calls.clear()
        original = self.receipt_path.read_bytes()
        result = play.inspect(self.api, self.receipt_path)
        self.assertFalse(result["editAccessible"])
        self.assertIn("does not prove the version is unused", result["guidance"])
        self.assertEqual(original, self.receipt_path.read_bytes())
        result = play.reconcile(self.receipt_path, lambda: self.api)
        self.assertTrue(result["localReceiptResolved"])
        self.assertEqual("reconciled", self.receipt()["status"])
        self.assertTrue(all(call["method"] == "GET" for call in self.api.calls))
        self.assertEqual(2, len(session.calls))
        play.guard_previous_upload(self.directory / "next.json", self.config["packageName"])

    def test_unknown_400_cannot_be_inspected_as_absent_or_reconciled(self):
        self.rejected_commit()
        response = FakeResponse(400, {"error": {"status": "FAILED_PRECONDITION",
                                               "message": "Other error private-token-never-log"}})
        transport = play.GoogleTransport(FakeCredentials(), FakeSession(response), None)
        def fail(method, url, kwargs):
            if method == "GET" and url.endswith("/edits/edit123"):
                transport.call(method, url)
        self.api.fail = fail
        self.api.calls.clear()
        original = self.receipt_path.read_bytes()
        with self.assertRaises(play.UploadError) as caught:
            play.inspect(self.api, self.receipt_path)
        self.assertFalse(caught.exception.edit_expired)
        with self.assertRaises(play.UploadError):
            play.reconcile(self.receipt_path, lambda: self.api)
        self.assertEqual(original, self.receipt_path.read_bytes())
        self.assertTrue(all(call["method"] == "GET" for call in self.api.calls))

    def test_unknown_summary_state_or_limit_fails_closed(self):
        for summaries in ([dict(self.api.internal_summary[0], releaseLifecycleState="FUTURE_UNKNOWN")],
                          self.api.internal_summary * 20):
            self.api.internal_summary = summaries
            with self.assertRaises(play.UploadError):
                play.release_summaries(self.api, self.config["packageName"], "internal")

    def test_untrusted_resumable_url_never_receives_bundle(self):
        self.api.upload_location = "https://example.invalid/upload?token=secret"
        with self.assertRaisesRegex(play.UploadError, "unexpected API"):
            self.run_upload()
        self.assertEqual(0, sum(call["method"] == "PUT" for call in self.api.calls))

    def test_unexpected_local_exception_is_redacted_in_receipt_and_cli(self):
        secret = "PRIVATE_KEY_CONTENT_do_not_log"
        def fail(method, url, kwargs):
            if method == "POST":
                raise RuntimeError(secret)
        self.api.fail = fail
        output = io.StringIO()
        with patch.object(play.GoogleTransport, "create", return_value=self.api), redirect_stderr(output):
            result = play.main(["upload", "--config", str(self.config_path), "--receipt", str(self.receipt_path)])
        self.assertEqual(3, result)
        self.assertNotIn(secret, output.getvalue())
        self.assertNotIn(secret, self.receipt_path.read_text(encoding="utf-8"))

    def test_production_all_pending_states_block_but_empty_draft_is_preserved(self):
        for state in ("RELEASE_LIFECYCLE_STATE_NOT_SENT_FOR_REVIEW", "RELEASE_LIFECYCLE_STATE_IN_REVIEW",
                      "RELEASE_LIFECYCLE_STATE_APPROVED_NOT_PUBLISHED", "RELEASE_LIFECYCLE_STATE_NOT_APPROVED"):
            with self.subTest(state=state):
                self.api = FakeAPI(self.config)
                self.api.production_summary[0]["releaseLifecycleState"] = state
                self.receipt_path = self.directory / (state + ".json")
                with self.assertRaisesRegex(play.UploadError, "will not interrupt or submit"):
                    self.run_upload()
                self.assertEqual(0, self.count_calls("POST", "/edits"))
        self.api = FakeAPI(self.config)
        self.api.production_summary[0]["activeArtifacts"] = []
        self.receipt_path = self.directory / "empty-production-draft.json"
        self.assertEqual("committed", self.run_upload()["status"])

    def test_collection_order_and_default_priority_are_semantically_equal(self):
        self.api.normalize_internal_order = True
        self.assertEqual("committed", self.run_upload()["status"])
        self.assertEqual(1, self.count_calls("POST", ":commit"))

    def test_new_receipt_does_not_bypass_unresolved_upload(self):
        def fail(method, url, kwargs):
            if method == "PUT":
                raise play.UploadError("Lost upload response", uncertain=True)
        self.api.fail = fail
        with self.assertRaises(play.UploadError):
            self.run_upload()
        new_path = self.directory / "different-name.json"
        with self.assertRaisesRegex(play.UploadError, "prior upload.*unresolved"):
            play.execute_upload(self.config, new_path, lambda: self.fail("Credentials must not be read"))
        self.assertFalse(new_path.exists())

    def rejected_commit(self):
        def fail(method, url, kwargs):
            if ":commit?" in url:
                raise play.UploadError("Google API returned HTTP 400 INVALID_ARGUMENT.", http_status=400)
        self.api.fail = fail
        with self.assertRaises(play.UploadError):
            self.run_upload()
        self.api.fail = None

    def test_confirmed_commit_rejection_resumes_same_edit_without_reupload(self):
        self.rejected_commit()
        result = play.resume(self.receipt_path, lambda: self.api, omit_review_flag=True,
                             review_evidence=self.review_evidence())
        self.assertEqual("committed", result["status"])
        self.assertEqual(1, self.count_calls("POST", "/edits"))
        self.assertEqual(1, self.count_calls("PUT", "/bundles"))
        self.assertEqual(2, self.count_calls("POST", ":commit"))
        commit_url = self.api.calls[-1]["url"]
        self.assertNotIn("changesNotSentForReview", commit_url)
        self.assertIn("ERROR_IF_IN_REVIEW", commit_url)
        self.assertEqual("draft", self.api.tracks[1]["releases"][-1]["status"])
        self.assertEqual(["Data safety", "Open-test country availability"], self.receipt()["reviewOverviewEvidence"]["pendingChanges"])

    def test_known_failed_edit_also_blocks_new_upload(self):
        self.rejected_commit()
        with self.assertRaisesRegex(play.UploadError, "prior upload.*unresolved"):
            play.execute_upload(self.config, self.directory / "new-run.json", lambda: self.fail("No auth"))

    def test_uncertain_commit_cannot_resume_or_reconcile_live_edit(self):
        self.rejected_commit()
        data = self.receipt()
        data.update(status="uncertain", httpStatus=None)
        self.receipt_path.write_text(json.dumps(data), encoding="utf-8")
        with self.assertRaisesRegex(play.UploadError, "confirmed HTTP 4xx"):
            play.resume(self.receipt_path, lambda: self.fail("No auth"))
        with self.assertRaisesRegex(play.UploadError, "edit still exists"):
            play.reconcile(self.receipt_path, lambda: self.api)
        self.assertEqual("uncertain", self.receipt()["status"])

    def test_reconciliation_of_expired_edit_is_get_only_and_unblocks_new_run(self):
        self.rejected_commit()
        self.api.edit_expired = True
        self.api.calls.clear()
        result = play.reconcile(self.receipt_path, lambda: self.api)
        self.assertTrue(result["localReceiptResolved"])
        self.assertTrue(all(call["method"] == "GET" for call in self.api.calls))
        self.assertEqual("reconciled", self.receipt()["status"])
        play.guard_previous_upload(self.directory / "next-run.json", self.config["packageName"])

    def test_resume_rechecks_bundle_and_production_before_any_mutation(self):
        self.rejected_commit()
        self.api.production_summary[0]["releaseName"] = "changed"
        self.api.calls.clear()
        with self.assertRaisesRegex(play.UploadError, "Track state changed"):
            play.resume(self.receipt_path, lambda: self.api)
        self.assertTrue(all(call["method"] == "GET" for call in self.api.calls))
        self.assertEqual("failed", self.receipt()["status"])

    def test_omit_review_flag_is_explicit_and_false_is_rejected(self):
        self.raw_config["changesNotSentForReview"] = None
        self.write_config()
        self.config = play.load_config(self.config_path)
        self.run_upload(self.review_evidence())
        self.assertNotIn("changesNotSentForReview", self.api.calls[-1]["url"])
        self.raw_config["changesNotSentForReview"] = False
        self.write_config()
        with self.assertRaises(play.UploadError):
            play.load_config(self.config_path)

    def test_review_omission_requires_fresh_app_wide_acceptance_before_credentials(self):
        self.rejected_commit()
        for evidence in (None,
                         self.review_evidence(acceptsReviewSubmission=False),
                         self.review_evidence(checkedAt=(datetime.now(timezone.utc) - timedelta(hours=1)).isoformat()),
                         self.review_evidence(versionCode=999)):
            with self.assertRaises(play.UploadError):
                play.resume(self.receipt_path, lambda: self.fail("Credentials must not be read"),
                            omit_review_flag=True, review_evidence=evidence)

    def test_direct_config_omission_cannot_bypass_overview_evidence(self):
        self.config["changesNotSentForReview"] = None
        with self.assertRaisesRegex(play.UploadError, "ALL app-wide"):
            play.execute_upload(self.config, self.receipt_path, lambda: self.fail("No auth"))
        self.assertFalse(self.receipt_path.exists())

    def test_upload_location_cannot_switch_package_or_endpoint_on_google_host(self):
        self.api.upload_location = play.UPLOAD_ROOT + "/applications/other.app/edits/edit123/bundles?upload_id=x"
        with self.assertRaisesRegex(play.UploadError, "does not target this app"):
            self.run_upload()
        self.assertEqual(0, sum(call["method"] == "PUT" for call in self.api.calls))

    def test_fgs_validation_error_keeps_uploaded_edit_and_records_safe_guidance(self):
        session = FakeSession(FakeResponse(403, {"error": {"status": "PERMISSION_DENIED",
                                                           "message": play.FGS_DECLARATION_API_MESSAGE}}))
        transport = play.GoogleTransport(FakeCredentials(), session, None)
        def fail(method, url, kwargs):
            if url.endswith(":validate"):
                transport.call(method, url)
        self.api.fail = fail
        with self.assertRaises(play.UploadError):
            self.run_upload()
        receipt = self.receipt()
        self.assertEqual("validation_started", receipt["stage"])
        self.assertEqual("failed", receipt["status"])
        self.assertEqual("edit123", receipt["editId"])
        self.assertEqual(self.config["sha256"], receipt["uploadedBundle"]["sha256"])
        self.assertIn(play.FGS_DECLARATION_GUIDANCE, receipt["error"])
        self.assertNotIn(play.FGS_DECLARATION_API_MESSAGE, receipt["error"])
        self.assertEqual(1, len(session.calls))
        self.assertEqual(1, self.count_calls("PUT", "/bundles"))
        self.assertEqual(0, self.count_calls("POST", ":commit"))


class FakeCredentials:
    valid = True
    def apply(self, headers):
        headers["Authorization"] = "Bearer private-token-never-log"
    def refresh(self, request):
        raise RuntimeError("private-key-never-log")


class FakeResponse:
    def __init__(self, status=200, value=None):
        self.status_code = status
        self.value = value if value is not None else {"id": "edit123"}
        self.content = b"json response"
        self.headers = {}
    def json(self):
        return self.value


class FakeSession:
    def __init__(self, response=None, error=None):
        self.response = response or FakeResponse()
        self.error = error
        self.calls = []
    def request(self, *args, **kwargs):
        self.calls.append((args, kwargs))
        if self.error:
            raise self.error
        return self.response


class TransportTest(unittest.TestCase):
    URL = play.API_ROOT + "/applications/com.zzf.bluetoothsmp/edits"

    def test_401_is_not_refreshed_and_replayed(self):
        session = FakeSession(FakeResponse(401, {"error": {"status": "UNAUTHENTICATED", "message": "private-token-never-log"}}))
        api = play.GoogleTransport(FakeCredentials(), session, None)
        with self.assertRaises(play.UploadError) as caught:
            api.call("POST", self.URL, body={})
        self.assertEqual(1, len(session.calls))
        self.assertNotIn("private-token", str(caught.exception))
        self.assertFalse(session.calls[0][1]["allow_redirects"])
        self.assertEqual((15, 180), session.calls[0][1]["timeout"])

    def test_only_exact_observed_400_sets_structured_edit_expired_marker(self):
        cases = [(400, "FAILED_PRECONDITION", play.EDIT_EXPIRED_API_MESSAGE, True),
                 (400, "FAILED_PRECONDITION", play.EDIT_EXPIRED_API_MESSAGE + " secret-token", False),
                 (400, "FAILED_PRECONDITION", "Other failure secret-token", False),
                 (400, "INVALID_ARGUMENT", play.EDIT_EXPIRED_API_MESSAGE, False),
                 (403, "FAILED_PRECONDITION", play.EDIT_EXPIRED_API_MESSAGE, False)]
        for status, public_status, message, expected in cases:
            with self.subTest(status=status, public_status=public_status, message=message):
                session = FakeSession(FakeResponse(status, {"error": {"status": public_status, "message": message}}))
                with self.assertRaises(play.UploadError) as caught:
                    play.GoogleTransport(FakeCredentials(), session, None).call("GET", self.URL + "/edit123")
                self.assertEqual(expected, caught.exception.edit_expired)
                self.assertEqual(status, caught.exception.http_status)
                self.assertNotIn(message, str(caught.exception))
                self.assertNotIn("secret-token", str(caught.exception))
                self.assertEqual(1, len(session.calls))

    def test_exact_fgs_error_gets_only_local_guidance_without_retry(self):
        session = FakeSession(FakeResponse(403, {"error": {"status": "PERMISSION_DENIED",
                                                           "message": play.FGS_DECLARATION_API_MESSAGE,
                                                           "details": [{"private_key": "must-not-leak"}]}}))
        with self.assertRaises(play.UploadError) as caught:
            play.GoogleTransport(FakeCredentials(), session, None).call("POST", self.URL + "/edit123:validate")
        self.assertEqual(403, caught.exception.http_status)
        self.assertFalse(caught.exception.uncertain)
        self.assertIn(play.FGS_DECLARATION_GUIDANCE, str(caught.exception))
        self.assertNotIn(play.FGS_DECLARATION_API_MESSAGE, str(caught.exception))
        self.assertNotIn("must-not-leak", str(caught.exception))
        self.assertEqual(1, len(session.calls))

    def test_unknown_or_near_match_error_message_is_not_echoed(self):
        for message in ("private-key-never-log", play.FGS_DECLARATION_API_MESSAGE + " private-token-never-log",
                        " " + play.FGS_DECLARATION_API_MESSAGE):
            with self.subTest(message=message):
                session = FakeSession(FakeResponse(403, {"error": {"status": "PERMISSION_DENIED", "message": message}}))
                with self.assertRaises(play.UploadError) as caught:
                    play.GoogleTransport(FakeCredentials(), session, None).call("POST", self.URL + "/edit123:validate")
                self.assertEqual("Google API returned HTTP 403 PERMISSION_DENIED. No retry was performed.", str(caught.exception))
                self.assertNotIn(message, str(caught.exception))
                self.assertEqual(1, len(session.calls))

    def test_network_and_server_errors_are_redacted_without_retry(self):
        for session in (FakeSession(error=RuntimeError("Authorization: Bearer private-token-never-log")),
                        FakeSession(FakeResponse(503, {"error": {"message": "private-key-never-log", "status": "UNAVAILABLE"}}))):
            with self.subTest(session=session):
                api = play.GoogleTransport(FakeCredentials(), session, None)
                with self.assertRaises(play.UploadError) as caught:
                    api.call("POST", self.URL, body={})
                self.assertTrue(caught.exception.uncertain)
                self.assertEqual(1, len(session.calls))
                self.assertNotIn("private-", str(caught.exception))

    def test_auth_failure_is_redacted_before_any_api_call(self):
        credentials = FakeCredentials()
        credentials.valid = False
        session = FakeSession()
        with self.assertRaises(play.UploadError) as caught:
            play.GoogleTransport(credentials, session, None).call("POST", self.URL, body={})
        self.assertNotIn("private-key", str(caught.exception))
        self.assertEqual([], session.calls)

    def test_redirects_and_bad_origins_do_not_leak_tokens(self):
        for url in ("http://androidpublisher.googleapis.com/androidpublisher/v3/", "https://evil.invalid/androidpublisher/v3/",
                    "https://androidpublisher.googleapis.com.evil.invalid/androidpublisher/v3/", "https://user:pass@androidpublisher.googleapis.com/androidpublisher/v3/",
                    "https://www.googleapis.com:444/androidpublisher/v3/", "https://www.googleapis.com/other-service"):
            with self.subTest(url=url):
                session = FakeSession()
                with self.assertRaises(play.UploadError):
                    play.GoogleTransport(FakeCredentials(), session, None).call("PUT", url, data=b"binary")
                self.assertEqual([], session.calls)

    def test_successful_http_with_malformed_body_is_uncertain(self):
        session = FakeSession(FakeResponse(200, ["wrong shape"]))
        with self.assertRaises(play.UploadError) as caught:
            play.GoogleTransport(FakeCredentials(), session, None).call("POST", self.URL, body={})
        self.assertTrue(caught.exception.uncertain)
        self.assertEqual(1, len(session.calls))


if __name__ == "__main__":
    unittest.main()
