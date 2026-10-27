#!/usr/bin/env python3
"""Upload one AAB as an INTERNAL DRAFT, without submitting a rollout.

Python 3.10+. Only upload/inspect need google-auth>=2.40,<3 and requests>=2.32,<3.
plan and the unittest suite use only the standard library. Credentials are loaded
only on explicit upload/inspect: --service-account-json PATH, otherwise ADC.

Config (UTF-8 JSON): packageName, versionCode (integer), bundlePath, releaseName,
releaseNotes [{language: "zh-CN", text: "..."}, {language: "en-US", text: "..."}].
Optional: sha256, track (only "internal"), status (only "draft"),
changesNotSentForReview (true by default; null explicitly omits that parameter).
Relative AAB
paths resolve against the config directory. No passwords or tokens belong here.

Examples:
  python scripts/play_upload.py plan --config upload.json
  python scripts/play_upload.py upload --config upload.json --receipt receipt.json
  python scripts/play_upload.py inspect --receipt receipt.json
  python scripts/play_upload.py resume --receipt receipt.json --omit-changes-not-sent-for-review
  python scripts/play_upload.py reconcile --receipt receipt.json

Every upload requires a NEW receipt. Do not delete a failed/uncertain receipt and
blindly repeat an upload. inspect only reads server state; it never repeats an
upload or commit. It cannot prove non-existence of a consumed version after an
edit expires. Resolve uncertain outcomes in Play Console before a fresh run.
resume accepts only a commit explicitly rejected with HTTP 4xx; it rechecks the
same edit and exact draft before retrying commit once. It never uploads again.
reconcile performs GETs only and records local resolution after the old edit is
confirmed absent; it does not establish that its versionCode can be reused.
Keep all receipts for this app in one directory (the app lock is scoped there).
Do not run Console edits/other API publishers concurrently: inserting an edit
invalidates another open edit owned by the same API user.

Official REST references verified 2026-09-09:
https://developers.google.com/android-publisher/edits
https://developers.google.com/android-publisher/upload
https://developers.google.com/android-publisher/api-ref/rest/v3/edits/commit
https://developers.google.com/android-publisher/api-ref/rest/v3/edits.tracks
https://developers.google.com/android-publisher/api-ref/rest/v3/applications.tracks.releases/list

The API's changesNotSentForReview flag is documented for rejected changes. The
fixed draft status is the no-rollout guard. We also set ERROR_IF_IN_REVIEW, never
fall back to a less safe commit, and never write any production track. Existing
production state is checked before commit. Console permissions/setup must be
managed separately; an unavailable preflight endpoint fails closed.
"""

from __future__ import annotations

import argparse
import copy
import hashlib
import json
import os
import re
import sys
import tempfile
import zipfile
from contextlib import contextmanager
from datetime import datetime, timezone, timedelta
from pathlib import Path
from urllib.parse import urlencode, urlsplit

API_ROOT = "https://androidpublisher.googleapis.com/androidpublisher/v3"
UPLOAD_ROOT = "https://androidpublisher.googleapis.com/upload/androidpublisher/v3"
SCOPE = "https://www.googleapis.com/auth/androidpublisher"
COMMIT_QUERY = {
    "changesNotSentForReview": "true",
    "changesInReviewBehavior": "ERROR_IF_IN_REVIEW",
}
MAX_VERSION = 2100000000
EDIT_EXPIRED_API_MESSAGE = "This edit has expired, please create a new Edit."
FGS_DECLARATION_API_MESSAGE = "You must let us know whether your app uses any Foreground Service permissions."
FGS_DECLARATION_GUIDANCE = (
    "Google Play requires a Foreground Service (FGS) declaration. "
    "The AAB may already be stored in an uncommitted edit. "
    "First use Play Console to resolve the declaration for older active bundles "
    "that still use FGS; do not blindly re-upload or commit."
)
LIFECYCLES = {
    "RELEASE_LIFECYCLE_STATE_DRAFT", "RELEASE_LIFECYCLE_STATE_NOT_SENT_FOR_REVIEW",
    "RELEASE_LIFECYCLE_STATE_IN_REVIEW", "RELEASE_LIFECYCLE_STATE_APPROVED_NOT_PUBLISHED",
    "RELEASE_LIFECYCLE_STATE_NOT_APPROVED", "RELEASE_LIFECYCLE_STATE_PUBLISHED",
}


class UploadError(Exception):
    """Messages must be locally authored; never copy remote/credential errors."""

    def __init__(self, message: str, *, uncertain=False, http_status=None, edit_expired=False):
        super().__init__(message)
        self.uncertain = uncertain
        self.http_status = http_status
        self.edit_expired = edit_expired


def now():
    return datetime.now(timezone.utc).isoformat(timespec="seconds")


def fingerprint(value):
    return hashlib.sha256(json.dumps(value, sort_keys=True, separators=(",", ":"),
                                     ensure_ascii=False).encode("utf-8")).hexdigest()


def semantic_fingerprint(value):
    """REST collection order is not contractual; content still matches exactly."""
    def normalized(item):
        if isinstance(item, dict):
            result = {key: normalized(child) for key, child in item.items()}
            if "versionCodes" in result and "status" in result:
                result.setdefault("inAppUpdatePriority", 0)
            return result
        if isinstance(item, list):
            return sorted((normalized(child) for child in item), key=fingerprint)
        return item
    return fingerprint(normalized(value))


def read_json(path):
    try:
        with Path(path).open("r", encoding="utf-8-sig") as source:
            return json.load(source)
    except (OSError, ValueError, UnicodeError):
        raise UploadError("Cannot read valid UTF-8 JSON input.") from None


def version_code(value):
    if isinstance(value, str) and re.fullmatch(r"[1-9][0-9]{0,9}", value):
        value = int(value)
    if type(value) is not int or not 1 <= value <= MAX_VERSION:
        raise UploadError("Invalid versionCode; require an integer from 1 to 2100000000.")
    return value


def package_name(value):
    if not isinstance(value, str) or not re.fullmatch(r"[A-Za-z][A-Za-z0-9_]*(?:\.[A-Za-z][A-Za-z0-9_]*)+", value):
        raise UploadError("Invalid packageName.")
    return value


def edit_id(value):
    if not isinstance(value, str) or not re.fullmatch(r"[A-Za-z0-9_-]{1,200}", value):
        raise UploadError("API returned an invalid edit identifier.", uncertain=True)
    return value


def file_hash(source):
    digest = hashlib.sha256()
    source.seek(0)
    for chunk in iter(lambda: source.read(1024 * 1024), b""):
        digest.update(chunk)
    source.seek(0)
    return digest.hexdigest()


def load_config(path):
    config_path = Path(path).resolve()
    value = read_json(config_path)
    allowed = {"packageName", "versionCode", "bundlePath", "sha256", "releaseName", "releaseNotes", "track", "status", "changesNotSentForReview"}
    if not isinstance(value, dict) or set(value) - allowed:
        raise UploadError("Config must contain only documented upload fields; keep credentials external.")
    result = copy.deepcopy(value)
    result["packageName"] = package_name(value.get("packageName"))
    if type(value.get("versionCode")) is not int:
        raise UploadError("Config versionCode must be a JSON integer.")
    result["versionCode"] = version_code(value["versionCode"])
    if value.get("track", "internal") != "internal" or value.get("status", "draft") != "draft":
        raise UploadError("This uploader only supports the internal track with draft status.")
    result.update(track="internal", status="draft")
    if value.get("changesNotSentForReview", True) is not True and value.get("changesNotSentForReview") is not None:
        raise UploadError("changesNotSentForReview accepts true or null (explicit omission) only.")
    result["changesNotSentForReview"] = value.get("changesNotSentForReview", True)
    name = value.get("releaseName")
    if not isinstance(name, str) or not name.strip() or len(name) > 50 or any(ord(c) < 32 for c in name):
        raise UploadError("releaseName must contain 1 to 50 printable characters.")
    notes = value.get("releaseNotes")
    if not isinstance(notes, list) or len(notes) != 2:
        raise UploadError("Provide exactly zh-CN and en-US release notes.")
    languages = set()
    for note in notes:
        if not isinstance(note, dict) or set(note) != {"language", "text"}:
            raise UploadError("Each release note requires language and text only.")
        language, text = note["language"], note["text"]
        if language not in ("zh-CN", "en-US") or language in languages:
            raise UploadError("Release notes must use distinct zh-CN and en-US language tags.")
        if not isinstance(text, str) or not text.strip() or len(text) > 500 or "\x00" in text:
            raise UploadError("Each release note must contain 1 to 500 characters.")
        languages.add(language)
    raw_path = value.get("bundlePath")
    if not isinstance(raw_path, str) or not raw_path:
        raise UploadError("bundlePath is required.")
    bundle = Path(raw_path)
    if not bundle.is_absolute():
        bundle = config_path.parent / bundle
    bundle = bundle.resolve()
    try:
        if bundle.suffix.lower() != ".aab" or not bundle.is_file():
            raise UploadError("bundlePath must name an existing .aab file.")
        with bundle.open("rb") as source:
            with zipfile.ZipFile(source) as archive:
                entries = set(archive.namelist())
                if not {"BundleConfig.pb", "base/manifest/AndroidManifest.xml"} <= entries:
                    raise UploadError("AAB does not contain the required bundle entries.")
            sha256 = file_hash(source)
            size = os.fstat(source.fileno()).st_size
    except (OSError, zipfile.BadZipFile, RuntimeError):
        raise UploadError("Cannot read a valid AAB archive.") from None
    expected = value.get("sha256", sha256)
    if not isinstance(expected, str) or not re.fullmatch(r"[A-Fa-f0-9]{64}", expected) or expected.lower() != sha256:
        raise UploadError("AAB SHA-256 does not match the expected artifact.")
    result.update(bundlePath=str(bundle), sha256=sha256, bundleBytes=size)
    # Play checks the package at the URL; the upload response's version and hash
    # are checked before any track write. This is not a local signature verifier.
    return result


def atomic_json(path, data):
    temporary = None
    try:
        with tempfile.NamedTemporaryFile(mode="w", encoding="utf-8", dir=path.parent,
                                         prefix=".play-receipt-", suffix=".tmp", delete=False) as target:
            temporary = target.name
            json.dump(data, target, ensure_ascii=False, indent=2)
            target.write("\n")
            target.flush()
            os.fsync(target.fileno())
        os.replace(temporary, path)
    finally:
        if temporary and os.path.exists(temporary):
            os.unlink(temporary)


class Receipt:
    def __init__(self, path, config):
        self.path = Path(path).resolve()
        self.path.parent.mkdir(parents=True, exist_ok=True)
        self.data = {"schemaVersion": 1, "createdAt": now(), "config": config,
                     "status": "running", "stage": "prepared", "events": []}
        try:
            with self.path.open("x", encoding="utf-8") as target:
                json.dump(self.data, target, ensure_ascii=False, indent=2)
                target.flush()
                os.fsync(target.fileno())
        except FileExistsError:
            raise UploadError("Receipt already exists. Use inspect; do not repeat upload or commit.") from None

    def save(self, stage, **fields):
        self.data.update(stage=stage, updatedAt=now(), **fields)
        self.data["events"].append({"stage": stage, "at": now()})
        atomic_json(self.path, self.data)

    @classmethod
    def existing(cls, path):
        result = cls.__new__(cls)
        result.path = Path(path).resolve()
        result.data = read_json(path)
        if not isinstance(result.data, dict) or result.data.get("schemaVersion") != 1 or not isinstance(result.data.get("config"), dict):
            raise UploadError("Not a supported upload receipt.")
        return result


def guard_previous_upload(receipt_path, package):
    """Read only our own index, never enumerate arbitrary JSON/credential files."""
    directory = Path(receipt_path).resolve().parent
    index = directory / (".play-upload-" + package + ".latest.json")
    if index.exists():
        previous_name = read_json(index).get("receiptFile")
        if not isinstance(previous_name, str) or Path(previous_name).name != previous_name:
            raise UploadError("Invalid upload receipt index; inspect the prior attempt before proceeding.")
        previous = Receipt.existing(directory / previous_name)
        if (previous.data.get("status") not in ("committed", "reconciled") and
                (previous.data.get("status") in ("running", "uncertain") or
                 previous.data.get("stage") not in ("prepared", "preflight"))):
            raise UploadError("A prior upload for this app is unresolved, even with a new receipt filename. Inspect and reconcile that receipt before a new upload.")
    return index


@contextmanager
def app_lock(receipt_path, package):
    directory = Path(receipt_path).resolve().parent
    directory.mkdir(parents=True, exist_ok=True)
    lock = directory / (".play-upload-" + package + ".lock")
    try:
        descriptor = os.open(lock, os.O_CREAT | os.O_EXCL | os.O_WRONLY, 0o600)
    except FileExistsError:
        raise UploadError("An app upload lock exists. Inspect the prior receipt and confirm its process stopped before removing the lock.") from None
    try:
        with os.fdopen(descriptor, "w", encoding="ascii") as target:
            target.write(str(os.getpid()))
        yield
    finally:
        lock.unlink()


def check_api_url(url):
    try:
        parsed = urlsplit(url)
        valid = (parsed.scheme == "https" and parsed.hostname in
                 {"androidpublisher.googleapis.com", "www.googleapis.com"} and
                 parsed.port in (None, 443) and not parsed.username and not parsed.password and
                 not parsed.fragment and parsed.path.startswith(("/androidpublisher/v3/", "/upload/androidpublisher/v3/")))
    except (TypeError, ValueError):
        valid = False
    if not valid:
        raise UploadError("Refusing an unexpected API or resumable upload URL.", uncertain=True)


def check_upload_location(url, package, identifier):
    check_api_url(url)
    if urlsplit(url).path != f"/upload/androidpublisher/v3/applications/{package}/edits/{identifier}/bundles":
        raise UploadError("Resumable session does not target this app, edit and bundle endpoint.", uncertain=True)


class GoogleTransport:
    """No retries, redirects, raw API errors, tokens, or session URLs in logs."""

    def __init__(self, credentials, session, auth_request):
        self.credentials, self.session, self.auth_request = credentials, session, auth_request

    @classmethod
    def create(cls, service_account_json=None):
        try:
            import google.auth
            import requests
            from google.auth.transport.requests import Request
            from google.oauth2 import service_account
        except ImportError:
            raise UploadError("Install google-auth>=2.40,<3 and requests>=2.32,<3 for upload/inspect.") from None
        try:
            if service_account_json:
                credentials = service_account.Credentials.from_service_account_file(service_account_json, scopes=[SCOPE])
            else:
                credentials, _ = google.auth.default(scopes=[SCOPE])
            session = requests.Session()
            # Do not use AuthorizedSession: its default 401 refresh can replay a
            # mutation. Refresh OAuth explicitly before a single API request.
            adapter = requests.adapters.HTTPAdapter(max_retries=0)
            session.mount("https://", adapter)
            return cls(credentials, session, Request(session=session))
        except Exception:
            raise UploadError("Cannot load credentials. Supply an external service-account JSON or configure ADC.") from None

    def call(self, method, url, *, body=None, data=None, headers=None, empty_ok=False):
        check_api_url(url)
        request_headers = {"Accept": "application/json", "User-Agent": "bluetoothsmp-internal-draft-uploader/1"}
        request_headers.update(headers or {})
        try:
            if not self.credentials.valid:
                self.credentials.refresh(self.auth_request)
            self.credentials.apply(request_headers)
        except Exception:
            raise UploadError("Authentication failed before the API request; credentials were not logged.") from None
        try:
            response = self.session.request(method, url, json=body, data=data, headers=request_headers,
                                            timeout=(15, 180), allow_redirects=False)
        except Exception:
            raise UploadError("API transport interrupted. No retry was performed; inspect the receipt.", uncertain=method != "GET") from None
        status = response.status_code
        if not 200 <= status < 300:
            # Even error.message can contain request input or secrets. Emit only
            # the HTTP status, a closed set of public status codes, and locally
            # authored guidance for an exact, previously observed policy error.
            public_status = ""
            guidance = ""
            edit_expired = False
            try:
                api_error = response.json().get("error", {})
                candidate = api_error.get("status")
                if candidate in {"INVALID_ARGUMENT", "UNAUTHENTICATED", "PERMISSION_DENIED", "NOT_FOUND",
                                 "ALREADY_EXISTS", "FAILED_PRECONDITION", "ABORTED", "RESOURCE_EXHAUSTED", "INTERNAL", "UNAVAILABLE"}:
                    public_status = " " + candidate
                if api_error.get("message") == FGS_DECLARATION_API_MESSAGE:
                    guidance = " " + FGS_DECLARATION_GUIDANCE
                # Google also reports expired edits as this exact 400 error.
                # An arbitrary FAILED_PRECONDITION must never clear a receipt.
                edit_expired = (status == 400 and candidate == "FAILED_PRECONDITION" and
                                api_error.get("message") == EDIT_EXPIRED_API_MESSAGE)
            except Exception:
                pass
            raise UploadError(f"Google API returned HTTP {status}{public_status}. No retry was performed.{guidance}",
                              uncertain=method != "GET" and (status >= 500 or status in (308, 408)),
                              http_status=status, edit_expired=edit_expired)
        if empty_ok and not response.content:
            return {}, response.headers
        try:
            result = response.json()
            if not isinstance(result, dict):
                raise ValueError()
        except Exception:
            raise UploadError("API response was not a JSON object; inspect the receipt.", uncertain=method != "GET") from None
        return result, response.headers


def array_field(value, key):
    result = value.get(key, []) if isinstance(value, dict) else None
    if not isinstance(result, list) or any(not isinstance(item, dict) for item in result):
        raise UploadError("Unexpected API collection schema; stopped without guessing.")
    return result


def summary_codes(releases):
    return {version_code(artifact.get("versionCode")) for release in releases
            for artifact in array_field(release, "activeArtifacts")}


def track_codes(tracks):
    codes = set()
    for track in tracks:
        for release in array_field(track, "releases"):
            values = release.get("versionCodes", [])
            if not isinstance(values, list):
                raise UploadError("Unexpected API versionCodes schema.")
            codes.update(version_code(code) for code in values)
    return codes


def check_version_conflict(requested, known):
    if known and requested <= max(known):
        raise UploadError(f"Requested versionCode {requested} is not above the highest observed code {max(known)}. Use a new version; nothing was uploaded.")


def release_summaries(api, package, track):
    result, _ = api.call("GET", f"{API_ROOT}/applications/{package}/tracks/{track}/releases")
    releases = array_field(result, "releases")
    if len(releases) >= 20:
        raise UploadError("Release-summary limit reached; cannot prove the full preflight state.")
    if any(release.get("releaseLifecycleState") not in LIFECYCLES for release in releases):
        raise UploadError("Unknown release lifecycle state; inspect Play Console before uploading.")
    return releases


def retained_internal_track(tracks, config):
    matching = [track for track in tracks if track.get("track") == "internal"]
    if len(matching) > 1:
        raise UploadError("API returned duplicate internal tracks.")
    existing = array_field(matching[0], "releases") if matching else []
    if any(release.get("status") != "completed" for release in existing):
        raise UploadError("Internal track already contains a draft or unfinished release. Existing releases will not be replaced.")
    return {"track": "internal", "releases": copy.deepcopy(existing) + [{
        "name": config["releaseName"], "versionCodes": [str(config["versionCode"])],
        "status": "draft", "releaseNotes": copy.deepcopy(config["releaseNotes"]),
    }]}


def upload(api, config, receipt):
    package, requested = config["packageName"], config["versionCode"]
    root = f"{API_ROOT}/applications/{package}"
    receipt.save("preflight")
    internal = release_summaries(api, package, "internal")
    production = release_summaries(api, package, "production")
    check_version_conflict(requested, summary_codes(internal + production))
    if any(release["releaseLifecycleState"] != "RELEASE_LIFECYCLE_STATE_PUBLISHED" for release in internal):
        raise UploadError("Internal track has an existing draft, pending, or reviewed release; it will not be overwritten.")
    if any(release["releaseLifecycleState"] not in {"RELEASE_LIFECYCLE_STATE_PUBLISHED", "RELEASE_LIFECYCLE_STATE_DRAFT"} for release in production):
        raise UploadError("Production has a pending/reviewed release; this uploader will not interrupt or submit it.")
    receipt.save("edit_insert_started", productionBefore=semantic_fingerprint(production), internalBefore=semantic_fingerprint(internal))
    created, _ = api.call("POST", root + "/edits", body={})
    identifier = edit_id(created.get("id"))
    edit_root = root + "/edits/" + identifier
    receipt.save("edit_created", editId=identifier)
    bundles, _ = api.call("GET", edit_root + "/bundles")
    apks, _ = api.call("GET", edit_root + "/apks")
    track_response, _ = api.call("GET", edit_root + "/tracks")
    tracks = array_field(track_response, "tracks")
    known = {version_code(item.get("versionCode")) for item in array_field(bundles, "bundles") + array_field(apks, "apks")}
    check_version_conflict(requested, known | track_codes(tracks))
    planned_track = retained_internal_track(tracks, config)
    untouched_tracks = [track for track in tracks if track.get("track") != "internal"]
    receipt.save("edit_preflight_complete", preservedTracks=semantic_fingerprint(untouched_tracks),
                 intendedInternalTrack=planned_track)
    # Keep the same descriptor for checksum and transfer; detect rebuilt files
    # before mutation and verify the server hash after transfer as well.
    with Path(config["bundlePath"]).open("rb") as bundle:
        if os.fstat(bundle.fileno()).st_size != config["bundleBytes"] or file_hash(bundle) != config["sha256"]:
            raise UploadError("AAB changed after local validation. No upload was started.")
        receipt.save("upload_session_started")
        _, headers = api.call("POST", f"{UPLOAD_ROOT}/applications/{package}/edits/{identifier}/bundles?uploadType=resumable",
                              data=b"", empty_ok=True, headers={"Content-Length": "0", "X-Upload-Content-Type": "application/octet-stream",
                                                                "X-Upload-Content-Length": str(config["bundleBytes"])})
        location = headers.get("Location") or headers.get("location")
        check_upload_location(location, package, identifier)
        # Session URLs are capability-bearing; keep them only in memory. A lost
        # response is reconciled via GET bundles/track, never a blind second PUT.
        receipt.save("bundle_upload_started")
        uploaded, _ = api.call("PUT", location, data=bundle, headers={"Content-Type": "application/octet-stream", "Content-Length": str(config["bundleBytes"])})
    if (version_code(uploaded.get("versionCode")) != requested or
            uploaded.get("sha256", "").lower() != config["sha256"]):
        raise UploadError("Uploaded bundle version or SHA-256 differs from the requested artifact. No track was changed.")
    receipt.save("bundle_uploaded", uploadedBundle={"versionCode": requested, "sha256": config["sha256"]})
    receipt.save("track_update_started")
    api.call("PUT", edit_root + "/tracks/internal", body=planned_track)
    receipt.save("track_updated")
    verification, _ = api.call("GET", edit_root + "/tracks")
    verified_tracks = array_field(verification, "tracks")
    if semantic_fingerprint([track for track in verified_tracks if track.get("track") != "internal"]) != semantic_fingerprint(untouched_tracks):
        raise UploadError("Another track changed inside the edit; commit refused.")
    current_internal = [track for track in verified_tracks if track.get("track") == "internal"]
    if len(current_internal) != 1 or semantic_fingerprint(current_internal[0]) != semantic_fingerprint(planned_track):
        raise UploadError("Internal track verification differs from the exact planned draft; commit refused.")
    receipt.save("validation_started")
    api.call("POST", edit_root + ":validate")
    receipt.save("validated")
    if semantic_fingerprint(release_summaries(api, package, "production")) != semantic_fingerprint(production):
        raise UploadError("Production state changed since preflight; commit refused.")
    if semantic_fingerprint(release_summaries(api, package, "internal")) != semantic_fingerprint(internal):
        raise UploadError("Internal state changed since preflight; commit refused.")
    return commit(api, config, receipt, edit_root, identifier)


def commit(api, config, receipt, edit_root, identifier):
    query = dict(COMMIT_QUERY)
    if config.get("changesNotSentForReview", True) is None:
        query.pop("changesNotSentForReview")
    receipt.save("commit_started", commitQuery=query, status="running", httpStatus=None)
    committed, _ = api.call("POST", edit_root + ":commit?" + urlencode(query))
    if committed.get("id") != identifier:
        raise UploadError("Commit response did not confirm this edit; inspect the receipt.", uncertain=True)
    receipt.save("committed", status="committed", committedAt=now())
    return {"status": "committed", "track": "internal", "releaseStatus": "draft",
            "versionCode": config["versionCode"], "sha256": config["sha256"], "editId": identifier}


def review_overview_evidence(path, config):
    """Human-reviewed whole-app overview, not a claim that track GETs prove no side effects."""
    if path is None:
        raise UploadError("Omitting the review flag can submit ALL app-wide pending changes. Require a fresh --review-evidence JSON after manually checking the complete Play publication overview.")
    evidence = read_json(path)
    allowed = {"packageName", "versionCode", "checkedAt", "publicationOverviewChecked",
               "pendingChanges", "acceptsReviewSubmission"}
    if not isinstance(evidence, dict) or set(evidence) != allowed:
        raise UploadError("Review evidence must contain only the documented overview-check fields.")
    if (evidence["packageName"] != config["packageName"] or
            type(evidence["versionCode"]) is not int or evidence["versionCode"] != config["versionCode"] or
            evidence["publicationOverviewChecked"] is not True or evidence["acceptsReviewSubmission"] is not True):
        raise UploadError("Review evidence must match this app/version and explicitly accept app-wide review submission.")
    changes = evidence["pendingChanges"]
    if (not isinstance(changes, list) or len(changes) > 50 or
            any(not isinstance(change, str) or not change.strip() or len(change) > 250 for change in changes)):
        raise UploadError("Record the overview's pending changes as an array of short descriptions (empty only when none exist).")
    try:
        checked = datetime.fromisoformat(evidence["checkedAt"].replace("Z", "+00:00"))
        age = datetime.now(timezone.utc) - checked
        if checked.tzinfo is None or age < timedelta(0) or age > timedelta(minutes=30):
            raise ValueError()
    except (TypeError, AttributeError, ValueError):
        raise UploadError("Overview evidence must use a timezone-aware checkedAt within the last 30 minutes.") from None
    return evidence


def execute_upload(config, receipt_path, api_factory, review_evidence=None):
    evidence = review_overview_evidence(review_evidence, config) if config.get("changesNotSentForReview", True) is None else None
    with app_lock(receipt_path, config["packageName"]):
        index = guard_previous_upload(receipt_path, config["packageName"])
        receipt = Receipt(receipt_path, config)
        if evidence is not None:
            receipt.save("manual_review_scope_accepted", reviewOverviewEvidence=evidence)
        atomic_json(index, {"receiptFile": receipt.path.name})
        return tracked_operation(receipt, lambda: upload(api_factory(), config, receipt))


def tracked_operation(receipt, operation):
    try:
        return operation()
    except BaseException as error:
        if isinstance(error, UploadError):
            failure = str(error)
            uncertain = error.uncertain
        else:
            failure = "Upload interrupted locally. Inspect the saved stage; no automatic retry was performed."
            uncertain = True
        failed_stage = receipt.data["stage"]
        try:
            receipt.save(failed_stage, status="uncertain" if uncertain else "failed", error=failure,
                         httpStatus=error.http_status if isinstance(error, UploadError) else None)
        except Exception:
            pass  # The previously fsynced *_started stage remains evidence.
        if isinstance(error, UploadError):
            raise
        raise UploadError(failure, uncertain=uncertain) from None


def resume(receipt_path, api_factory, omit_review_flag=False, review_evidence=None):
    receipt = Receipt.existing(receipt_path)
    config = receipt.data["config"]
    package = package_name(config.get("packageName"))
    requested = version_code(config.get("versionCode"))
    evidence = review_overview_evidence(review_evidence, config) if omit_review_flag or config.get("changesNotSentForReview", True) is None else None
    with app_lock(receipt_path, package):
        # Another invocation may have completed between the first read and lock.
        receipt = Receipt.existing(receipt_path)
        config = receipt.data["config"]
        if package_name(config.get("packageName")) != package or version_code(config.get("versionCode")) != requested:
            raise UploadError("Receipt identity changed before the lock was acquired.")
        # HTTP rejection is the only safe automatic boundary we accept here.
        # A lost response, a killed process or an arbitrary failed stage is not.
        status = receipt.data.get("httpStatus")
        if (receipt.data.get("status") != "failed" or receipt.data.get("stage") != "commit_started" or
                type(status) is not int or not 400 <= status < 500 or status in (408, 429)):
            raise UploadError("Resume requires a confirmed HTTP 4xx commit rejection. Uncertain outcomes must be inspected; no mutation was attempted.")
        if config.get("track") != "internal" or config.get("status") != "draft":
            raise UploadError("Receipt does not describe an internal draft.")
        identifier = edit_id(receipt.data.get("editId"))
        edit_root = f"{API_ROOT}/applications/{package}/edits/{identifier}"
        api = api_factory()
        api.call("GET", edit_root)
        bundles, _ = api.call("GET", edit_root + "/bundles")
        if not any(version_code(bundle.get("versionCode")) == requested and bundle.get("sha256") == config.get("sha256")
                   for bundle in array_field(bundles, "bundles")):
            raise UploadError("The edit does not contain the exact recorded bundle; resume refused.")
        tracks_result, _ = api.call("GET", edit_root + "/tracks")
        tracks = array_field(tracks_result, "tracks")
        actual = [track for track in tracks if track.get("track") == "internal"]
        planned = receipt.data.get("intendedInternalTrack")
        releases = array_field(planned, "releases")
        drafts = [release for release in releases if release.get("status") == "draft"]
        expected_draft = {"name": config.get("releaseName"), "versionCodes": [str(requested)],
                          "status": "draft", "releaseNotes": config.get("releaseNotes")}
        retained = [release for release in releases if release.get("status") != "draft"]
        if (len(drafts) != 1 or semantic_fingerprint(drafts[0]) != semantic_fingerprint(expected_draft) or
                any(release.get("status") != "completed" for release in retained) or
                requested in track_codes([{"releases": retained}])):
            raise UploadError("Receipt does not contain exactly the requested new draft; resume refused.")
        if (len(actual) != 1 or semantic_fingerprint(actual[0]) != semantic_fingerprint(planned) or
                semantic_fingerprint([track for track in tracks if track.get("track") != "internal"]) != receipt.data.get("preservedTracks")):
            raise UploadError("Recorded track state no longer matches this edit; resume refused.")
        for track, field in (("production", "productionBefore"), ("internal", "internalBefore")):
            if semantic_fingerprint(release_summaries(api, package, track)) != receipt.data.get(field):
                raise UploadError("Track state changed since the recorded attempt; resume refused.")
        if omit_review_flag:
            config["changesNotSentForReview"] = None
        # All checks above are read-only, so failed inspection preserves the
        # original rejection receipt. Persist the decision before validation.
        def finish():
            receipt.save("validation_started", status="running", **({"reviewOverviewEvidence": evidence} if evidence is not None else {}))
            api.call("POST", edit_root + ":validate")
            receipt.save("validated")
            return commit(api, config, receipt, edit_root, identifier)
        return tracked_operation(receipt, finish)


def inspect(api, receipt_path):
    receipt = read_json(receipt_path)
    if not isinstance(receipt, dict) or receipt.get("schemaVersion") != 1 or not isinstance(receipt.get("config"), dict):
        raise UploadError("Not a supported upload receipt.")
    config = receipt["config"]
    package, requested = package_name(config.get("packageName")), version_code(config.get("versionCode"))
    summaries = release_summaries(api, package, "internal")
    observed = [{"state": release["releaseLifecycleState"], "versionCodes": sorted(summary_codes([release]))}
                for release in summaries if requested in summary_codes([release])]
    result = {"readOnly": True, "versionCode": requested, "observedInternalReleases": observed,
              "editAccessible": False, "bundleInEditMatches": False, "draftInEdit": False,
              "guidance": "Inspection never repeats an upload or commit. Missing/expired edit does not prove the version is unused; reconcile in Play Console before a new attempt."}
    identifier = receipt.get("editId")
    if identifier:
        identifier = edit_id(identifier)
        edit_root = f"{API_ROOT}/applications/{package}/edits/{identifier}"
        try:
            api.call("GET", edit_root)
        except UploadError as error:
            if error.http_status in (404, 410) or (error.http_status == 400 and error.edit_expired):
                return result
            raise
        result["editAccessible"] = True
        bundles, _ = api.call("GET", edit_root + "/bundles")
        result["bundleInEditMatches"] = any(version_code(bundle.get("versionCode")) == requested and bundle.get("sha256") == config.get("sha256")
                                            for bundle in array_field(bundles, "bundles"))
        track, _ = api.call("GET", edit_root + "/tracks/internal")
        result["draftInEdit"] = any(release.get("status") == "draft" and requested in track_codes([{"releases": [release]}])
                                    for release in array_field(track, "releases"))
    return result


def reconcile(receipt_path, api_factory):
    receipt = Receipt.existing(receipt_path)
    package = package_name(receipt.data["config"].get("packageName"))
    with app_lock(receipt_path, package):
        receipt = Receipt.existing(receipt_path)
        if package_name(receipt.data["config"].get("packageName")) != package:
            raise UploadError("Receipt identity changed before the lock was acquired.")
        if not receipt.data.get("editId"):
            raise UploadError("No edit ID was recorded. Its creation may be uncertain; reconcile manually in Play Console before changing local state.")
        result = inspect(api_factory(), receipt_path)
        if result["editAccessible"]:
            raise UploadError("The recorded edit still exists. Resume a confirmed commit rejection or resolve it in Play Console first.")
        if receipt.data.get("status") != "committed":
            receipt.save(receipt.data["stage"], status="reconciled", reconciliation=result)
        return dict(result, localReceiptResolved=True)


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    commands = parser.add_subparsers(dest="command", required=True)
    plan = commands.add_parser("plan", help="Validate config and AAB offline; never load credentials")
    plan.add_argument("--config", required=True)
    send = commands.add_parser("upload", help="Create one internal draft; never retry uncertain mutations")
    send.add_argument("--config", required=True)
    send.add_argument("--receipt", required=True)
    send.add_argument("--service-account-json")
    send.add_argument("--review-evidence", help="Fresh whole-app publication-overview evidence when config explicitly omits the review flag")
    check = commands.add_parser("inspect", help="Read-only reconciliation of an existing receipt")
    check.add_argument("--receipt", required=True)
    check.add_argument("--service-account-json")
    retry = commands.add_parser("resume", help="Resume only a confirmed HTTP 4xx commit rejection in the same edit")
    retry.add_argument("--receipt", required=True)
    retry.add_argument("--service-account-json")
    retry.add_argument("--omit-changes-not-sent-for-review", action="store_true",
                       help="May submit ALL app-wide pending changes; requires a fresh manually checked --review-evidence JSON")
    retry.add_argument("--review-evidence", help="Whole-app publication overview inspected within 30 minutes, including pending changes and explicit review-scope acceptance")
    resolved = commands.add_parser("reconcile", help="GET-only inspection; resolve local receipt only when its edit no longer exists")
    resolved.add_argument("--receipt", required=True)
    resolved.add_argument("--service-account-json")
    args = parser.parse_args(argv)
    try:
        if args.command == "plan":
            config = load_config(args.config)
            result = {"status": "offline_validated", "packageName": config["packageName"], "versionCode": config["versionCode"],
                      "track": "internal", "releaseStatus": "draft", "sha256": config["sha256"], "bundleBytes": config["bundleBytes"],
                      "note": "Local archive/hash/input validation only. Signing and manifest identity must be verified by the build step."}
        elif args.command == "upload":
            config = load_config(args.config)
            result = execute_upload(config, args.receipt, lambda: GoogleTransport.create(args.service_account_json), args.review_evidence)
        elif args.command == "resume":
            if args.omit_changes_not_sent_for_review:
                print("Manual review scope: omitting this flag may submit Data safety, store listing, country/region and other app-wide pending changes to review.", file=sys.stderr)
            result = resume(args.receipt, lambda: GoogleTransport.create(args.service_account_json), args.omit_changes_not_sent_for_review, args.review_evidence)
        elif args.command == "reconcile":
            result = reconcile(args.receipt, lambda: GoogleTransport.create(args.service_account_json))
        else:
            # Validate the receipt JSON before loading credentials.
            read_json(args.receipt)
            result = inspect(GoogleTransport.create(args.service_account_json), args.receipt)
        print(json.dumps(result, ensure_ascii=True))
        return 0
    except UploadError as error:
        print(json.dumps({"status": "uncertain" if error.uncertain else "failed", "error": str(error)}, ensure_ascii=True), file=sys.stderr)
        return 3 if error.uncertain else 2
    except Exception:
        print('{"status":"failed","error":"Local operation failed; no credentials or raw exception details were logged. Inspect any existing receipt before retrying."}', file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
