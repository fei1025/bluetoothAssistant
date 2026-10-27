#!/usr/bin/env python3
"""Record a device-test APK and JUnit evidence, including incomplete or failed runs."""

import argparse
import hashlib
import json
import subprocess
import sys
import xml.etree.ElementTree as ET
from datetime import datetime, timezone
from pathlib import Path


RUN_STATUSES = ("success", "failure", "cancelled", "skipped", "unknown")
COUNT_KEYS = ("tests", "failures", "errors", "skipped")


def sha256(path):
    digest = hashlib.sha256()
    with Path(path).open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def git_sha(repository):
    try:
        value = subprocess.check_output(
            ["git", "-C", str(repository), "rev-parse", "HEAD"],
            stderr=subprocess.DEVNULL,
        ).decode("ascii").strip()
        if not value or any(character not in "0123456789abcdefABCDEF" for character in value):
            raise ValueError("Invalid Git revision")
        return value, None
    except (OSError, ValueError, UnicodeError, subprocess.CalledProcessError):
        return None, "source_git_sha_unavailable"


def relative_path(path, build_directory):
    return path.relative_to(build_directory).as_posix()


def apk_evidence(build_directory, variant):
    directory = build_directory / "outputs" / "apk" / variant
    candidates = sorted(path for path in directory.rglob("*.apk")
                        if path.is_file() and "androidtest" not in path.name.lower()
                        and not any(part.lower() == "androidtest" for part in path.relative_to(directory).parts))
    result = {"status": "missing", "file": None, "sha256": None, "bytes": None,
              "candidates": [relative_path(path, build_directory) for path in candidates]}
    if not candidates:
        return result, "app_apk_missing"
    if len(candidates) != 1:
        result["status"] = "ambiguous"
        return result, "app_apk_ambiguous"
    path = candidates[0]
    result["file"] = relative_path(path, build_directory)
    try:
        result.update(status="available", sha256=sha256(path), bytes=path.stat().st_size)
        return result, None
    except OSError:
        result["status"] = "unreadable"
        return result, "app_apk_unreadable"


def tag_name(element):
    return element.tag.rsplit("}", 1)[-1]


def suite_counts(suite):
    cases = [child for child in suite if tag_name(child) == "testcase"]
    observed = {"tests": len(cases)}
    for key, tag in (("failures", "failure"), ("errors", "error"), ("skipped", "skipped")):
        observed[key] = sum(any(tag_name(child) == tag for child in case) for case in cases)
    counts = {}
    for key in COUNT_KEYS:
        advertised = int(suite.get(key, observed[key]))
        if advertised < 0:
            raise ValueError("Negative JUnit count")
        # A failing testcase must remain visible even if its suite header is incorrect.
        counts[key] = max(advertised, observed[key])
    if counts["failures"] + counts["errors"] + counts["skipped"] > counts["tests"]:
        raise ValueError("Inconsistent JUnit counts")
    return counts


def report_counts(path):
    root = ET.parse(path).getroot()
    if tag_name(root) not in {"testsuite", "testsuites"}:
        raise ValueError("Not a JUnit report")
    # Wrapper/parent suite counts aggregate their children; count only the leaf suites.
    suites = [element for element in root.iter() if tag_name(element) == "testsuite"
              and not any(tag_name(child) == "testsuite" for child in element.iter() if child is not element)]
    if not suites:
        raise ValueError("JUnit report has no test suites")
    counts = dict.fromkeys(COUNT_KEYS, 0)
    for suite in suites:
        for key, value in suite_counts(suite).items():
            counts[key] += value
    return counts


def junit_evidence(build_directory, variant):
    directory = build_directory / "outputs" / "androidTest-results" / "connected" / variant
    reports = sorted(path for path in directory.rglob("TEST-*.xml") if path.is_file())
    result = {"status": "available" if reports else "missing", "reportCount": len(reports),
              "reports": [relative_path(path, build_directory) for path in reports],
              "counts": dict.fromkeys(COUNT_KEYS, 0)}
    errors = [] if reports else ["junit_reports_missing"]
    for path in reports:
        try:
            counts = report_counts(path)
            for key, value in counts.items():
                result["counts"][key] += value
        except (OSError, ValueError, ET.ParseError):
            result["status"] = "invalid"
            errors.append("junit_report_invalid:" + relative_path(path, build_directory))
    counts = result["counts"]
    counts["failed"] = counts["failures"] + counts["errors"]
    counts["passed"] = max(0, counts["tests"] - counts["failed"] - counts["skipped"])
    return result, errors


def device_test_evidence(repository, build_directory, variant, api, run_status="unknown"):
    if variant not in {"debug", "release"} or api <= 0 or run_status not in RUN_STATUSES:
        raise ValueError("Invalid variant, API or run status")
    repository = Path(repository).resolve()
    build_directory = Path(build_directory).resolve()
    revision, source_error = git_sha(repository)
    artifact, artifact_error = apk_evidence(build_directory, variant)
    instrumentation, report_errors = junit_evidence(build_directory, variant)
    errors = [error for error in (source_error, artifact_error) if error] + report_errors
    counts = instrumentation["counts"]
    reasons = list(errors)
    if run_status != "success":
        reasons.append("run_" + run_status)
    if counts["failed"]:
        reasons.append("tests_failed")
    if not counts["tests"] and instrumentation["status"] == "available":
        reasons.append("no_tests")
    if counts["skipped"]:
        reasons.append("tests_skipped")
    if run_status == "failure" or counts["failed"]:
        result = "failed"
    elif run_status in {"cancelled", "skipped"}:
        result = run_status
    elif errors or run_status == "unknown":
        result = "unknown"
    elif not counts["tests"] or counts["skipped"]:
        result = "incomplete"
    else:
        result = "passed"
    return {"schemaVersion": 1, "capturedAt": datetime.now(timezone.utc).isoformat(),
            "gitSha": revision, "buildDirectory": str(build_directory), "variant": variant,
            "api": api, "runStatus": run_status, "artifact": artifact,
            "instrumentation": instrumentation, "result": result,
            "fullPassed": result == "passed", "resultReasons": reasons, "errors": errors}


def positive_integer(value):
    number = int(value)
    if number <= 0:
        raise argparse.ArgumentTypeError("API must be a positive integer")
    return number


def main(arguments=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repository", type=Path, required=True)
    parser.add_argument("--build-directory", type=Path, required=True)
    parser.add_argument("--variant", choices=("debug", "release"), required=True)
    parser.add_argument("--api", type=positive_integer, required=True)
    parser.add_argument("--run-status", choices=RUN_STATUSES, default="unknown")
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args(arguments)
    try:
        result = device_test_evidence(args.repository, args.build_directory, args.variant, args.api, args.run_status)
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        print(json.dumps({"status": "saved", "result": result["result"], "output": str(args.output)}, ensure_ascii=True))
        # An always() evidence step must preserve failed/incomplete evidence without hiding the test step outcome.
        return 0
    except (OSError, ValueError):
        print("Device-test evidence could not be written", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
