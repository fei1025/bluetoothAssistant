#!/usr/bin/env python3
"""Capture reproducible source/build evidence without reading credentials or contacting a service."""

import argparse
import hashlib
import json
import platform
import subprocess
import sys
import xml.etree.ElementTree as ET
from datetime import datetime, timezone
from pathlib import Path


SOURCE_EXTENSIONS = {".java", ".kt", ".kts", ".gradle", ".properties", ".xml", ".pro",
                     ".md", ".py", ".ps1", ".cmd", ".bat", ".sh", ".json", ".yml", ".yaml", ".txt",
                     ".png", ".webp", ".jpg", ".jpeg", ".gif", ".svg", ".csv", ".toml",
                     ".html", ".css", ".js", ".ts", ".btplugin", ".pub", ".woff", ".woff2", ".ttf", ".ico"}


def sha256(path):
    digest = hashlib.sha256()
    with Path(path).open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def git(repository, *arguments):
    return subprocess.check_output(["git", "-C", str(repository), *arguments], stderr=subprocess.DEVNULL)


def source_snapshot(repository):
    repository = Path(repository).resolve()
    paths = sorted(set(p.decode("utf-8") for p in
                       git(repository, "ls-files", "--cached", "--others", "--exclude-standard", "-z").split(b"\0") if p))
    files = []
    for relative in paths:
        path = Path(relative)
        lowered = path.name.lower()
        # Credential/signing files never enter this reader, even if mistakenly tracked.
        if ((path.suffix.lower() not in SOURCE_EXTENSIONS and relative not in {"gradlew", "gradle/wrapper/gradle-wrapper.jar"})
                or lowered in {"google-services.json", "local.properties", "keystore.properties", "key.properties", "signing.properties", "application_default_credentials.json"}
                or any(fragment in lowered for fragment in ("service-account", "service_account", "credential", "private-key", "private_key", "secret"))
                or any(part in {".claude", ".codex"} for part in path.parts)):
            continue
        absolute = repository / path
        files.append({"path": relative, "sha256": sha256(absolute) if absolute.is_file() else "deleted"})
    encoded = json.dumps(files, sort_keys=True, separators=(",", ":")).encode("utf-8")
    return {"gitSha": git(repository, "rev-parse", "HEAD").decode().strip(),
            "dirty": bool(git(repository, "status", "--porcelain", "--untracked-files=normal")),
            "sourceFingerprint": hashlib.sha256(encoded).hexdigest(), "sourceFiles": files}


def write_json(path, value):
    Path(path).parent.mkdir(parents=True, exist_ok=True)
    Path(path).write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def junit_summary(directory):
    counts = {"tests": 0, "failures": 0, "errors": 0, "skipped": 0}
    reports = sorted(Path(directory).glob("TEST-*.xml"))
    if not reports:
        raise ValueError("JVM test reports missing")
    for report in reports:
        root = ET.parse(report).getroot()
        for key in counts:
            counts[key] += int(root.get(key, 0))
    return counts


def check_evidence(status, allowed, success_status, evidence):
    if status not in allowed:
        raise ValueError("Invalid check status")
    recorded = {"status": status}
    if evidence is not None:
        evidence = Path(evidence)
        if not evidence.is_file() or evidence.stat().st_size == 0:
            raise ValueError("Check evidence log is missing or empty")
        recorded["evidence"] = {"file": evidence.name, "sha256": sha256(evidence),
                                "bytes": evidence.stat().st_size}
    elif status == success_status:
        raise ValueError("Successful check requires its command evidence log")
    return recorded


def build_info(repository, before_path, build_directory, artifact, *, signature_status="unknown",
               offline_scripts_status="unknown", signature_evidence=None, offline_scripts_evidence=None):
    before = json.loads(Path(before_path).read_text(encoding="utf-8"))
    after = source_snapshot(repository)
    if before != after:
        raise ValueError("Source changed during the build; rebuild to obtain matching evidence")
    build_directory = Path(build_directory)
    metadata = json.loads((build_directory / "outputs/release-pipeline/metadata.json").read_text(encoding="utf-8"))
    unit = junit_summary(build_directory / "test-results/testDebugUnitTest")
    lint = ET.parse(build_directory / "reports/lint-results-release.xml").getroot()
    severities = [issue.get("severity") for issue in lint.findall("issue")]
    signature = check_evidence(signature_status, {"unknown", "verified", "failed"}, "verified", signature_evidence)
    scripts = check_evidence(offline_scripts_status, {"unknown", "passed", "failed"}, "passed", offline_scripts_evidence)
    return {"schemaVersion": 2, "builtAt": datetime.now(timezone.utc).isoformat(),
            "source": after, "build": metadata,
            "host": {"python": platform.python_version(), "os": platform.system()},
            "artifact": {"file": Path(artifact).name, "sha256": sha256(artifact), "bytes": Path(artifact).stat().st_size},
            "checks": {"jvm": unit, "lint": {"errors": severities.count("Error") + severities.count("Fatal"),
                       "warnings": severities.count("Warning")}, "signature": signature,
                       "offlineScripts": scripts,
                       "deviceTests": "not run by the local release pipeline",
                       "playBilling": "requires real Play test-track acceptance"}}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=("capture", "write"))
    parser.add_argument("--repository", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--before", type=Path)
    parser.add_argument("--build-directory", type=Path)
    parser.add_argument("--artifact", type=Path)
    parser.add_argument("--signature-status", choices=("unknown", "verified", "failed"), default="unknown")
    parser.add_argument("--signature-evidence", type=Path)
    parser.add_argument("--offline-scripts-status", choices=("unknown", "passed", "failed"), default="unknown")
    parser.add_argument("--offline-scripts-evidence", type=Path)
    args = parser.parse_args()
    try:
        if args.command == "capture":
            result = source_snapshot(args.repository)
        else:
            if not all((args.before, args.build_directory, args.artifact)):
                raise ValueError("write requires --before, --build-directory and --artifact")
            result = build_info(args.repository, args.before, args.build_directory, args.artifact,
                                signature_status=args.signature_status, signature_evidence=args.signature_evidence,
                                offline_scripts_status=args.offline_scripts_status,
                                offline_scripts_evidence=args.offline_scripts_evidence)
        write_json(args.output, result)
        print(json.dumps({"status": "saved", "output": str(args.output)}, ensure_ascii=True))
        return 0
    except (OSError, ValueError, ET.ParseError, subprocess.CalledProcessError):
        print("Build evidence failed: missing reports or source changed during build", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
