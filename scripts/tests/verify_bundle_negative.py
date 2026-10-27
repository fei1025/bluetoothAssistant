"""Exercise VerifyBundle.java against real AAB copies without any private keys.

Run explicitly; this is not part of the fake-network uploader unit tests:
  python scripts/tests/verify_bundle_negative.py --signed path.aab \
    --unsigned unsigned.aab --certificate-sha256 HEX [--java path/to/java.exe]

Writes evidence and modified copies only beneath a unique .scratch directory.
"""

import argparse
import hashlib
import json
import shutil
import subprocess
import uuid
import zipfile
from datetime import datetime, timezone
from pathlib import Path


def sha256(path):
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--signed", type=Path, required=True)
    parser.add_argument("--unsigned", type=Path, required=True)
    parser.add_argument("--certificate-sha256", required=True)
    parser.add_argument("--java", default="java")
    args = parser.parse_args()
    repository = Path(__file__).resolve().parents[2]
    directory = repository / ".scratch" / ("verify-bundle-negative-" + uuid.uuid4().hex)
    directory.mkdir(parents=True)
    signed, unsigned = args.signed.resolve(), args.unsigned.resolve()
    before = {"signed": sha256(signed), "unsigned": sha256(unsigned)}

    appended = directory / "unsigned-entry-added.aab"
    shutil.copyfile(signed, appended)
    with zipfile.ZipFile(appended, "a") as archive:
        archive.writestr("base/assets/unsigned-regression-probe.txt", b"untrusted appended payload\n")

    changed = directory / "signed-payload-modified.aab"
    target_entry = "BundleConfig.pb"
    with zipfile.ZipFile(signed, "r") as source, zipfile.ZipFile(changed, "w") as target:
        if target_entry not in source.namelist():
            raise RuntimeError("Signed AAB fixture lacks BundleConfig.pb")
        for entry in source.infolist():
            payload = source.read(entry)
            if entry.filename == target_entry:
                payload += b"\x00regression-tamper"
            target.writestr(entry, payload)

    expected = args.certificate_sha256.replace(":", "").lower()
    wrong = ("0" if expected[0] != "0" else "1") + expected[1:]
    cases = [("original_signed", signed, expected, True),
             ("original_unsigned", unsigned, expected, False),
             ("unsigned_payload_appended", appended, expected, False),
             ("signed_payload_modified", changed, expected, False),
             ("wrong_certificate", signed, wrong, False)]
    results = []
    for name, bundle, certificate, should_pass in cases:
        outcome = subprocess.run([args.java, str(repository / "scripts" / "VerifyBundle.java"),
                                  str(bundle), certificate], capture_output=True, text=True,
                                 encoding="utf-8", errors="replace", timeout=30, check=False)
        results.append({"case": name, "bundle": str(bundle), "bundleSha256": sha256(bundle),
                        "expectedAccepted": should_pass, "returnCode": outcome.returncode,
                        "passed": (outcome.returncode == 0) == should_pass,
                        "stdout": outcome.stdout.strip(), "stderr": outcome.stderr.strip()})
    after = {"signed": sha256(signed), "unsigned": sha256(unsigned)}
    evidence = {"at": datetime.now(timezone.utc).isoformat(), "expectedCertificateSha256": expected,
                "originalHashesBefore": before, "originalHashesAfter": after,
                "originalsUnchanged": before == after, "cases": results,
                "allPassed": before == after and all(item["passed"] for item in results)}
    evidence_path = directory / "evidence.json"
    evidence_path.write_text(json.dumps(evidence, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"allPassed": evidence["allPassed"], "cases": len(results),
                      "evidence": str(evidence_path)}, ensure_ascii=True))
    return 0 if evidence["allPassed"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
