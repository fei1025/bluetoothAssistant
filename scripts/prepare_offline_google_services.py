"""Synthetic Firebase config: preserve local files; replace only in an explicit GitHub Actions audit."""
import argparse
import json
import os
from pathlib import Path

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--ci", action="store_true", help="Replace the disposable CI checkout's existing configuration")
args = parser.parse_args()
if args.ci and os.environ.get("GITHUB_ACTIONS") != "true":
    parser.error("--ci replacement is allowed only inside GitHub Actions")
destination = Path(__file__).resolve().parents[1] / "app/google-services.json"
if args.ci or not destination.exists():
    destination.write_text(json.dumps({
        "project_info": {"project_number": "1234567890", "project_id": "bluetooth-offline-audit"},
        "client": [{"client_info": {"mobilesdk_app_id": "1:1234567890:android:0000000000000000",
                    "android_client_info": {"package_name": "com.zzf.bluetoothsmp"}},
                    "api_key": [{"current_key": "AIzaSyOfflineAuditDummyKey000000000000000"}]}],
        "configuration_version": "1"}, indent=2) + "\n", encoding="utf-8")
    print("Synthetic offline audit config created")
else:
    print("Existing config preserved")
