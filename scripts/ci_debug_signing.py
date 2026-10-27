"""Restore and verify the pinned CI test identity; never generate a replacement key."""
import argparse
import base64
import hashlib
import os
from pathlib import Path
import re
import subprocess
import tempfile


ROOT = Path(__file__).resolve().parents[1]
PIN = ROOT / '.github/ci-debug-signing.sha256'


def expected_certificate(path=PIN):
    value = Path(path).read_text(encoding='ascii').strip().lower()
    if not re.fullmatch(r'[0-9a-f]{64}', value):
        raise ValueError('Invalid pinned test certificate SHA-256')
    return value


def restore(value, destination, expected):
    if not value:
        raise ValueError('CI_DEBUG_KEYSTORE_BASE64 is required; refusing a temporary signing key')
    if len(value) > 128 * 1024:
        raise ValueError('Test keystore secret is too large')
    try:
        content = base64.b64decode(value, validate=True)
    except ValueError:
        raise ValueError('Test keystore secret is not valid Base64') from None
    destination = Path(destination)
    destination.parent.mkdir(parents=True, exist_ok=True)
    staged = None
    try:
        with tempfile.NamedTemporaryFile(dir=destination.parent, suffix='.keystore', delete=False) as output:
            staged = Path(output.name)
            output.write(content)
        staged.chmod(0o600)
        result = subprocess.run(['keytool', '-exportcert', '-keystore', str(staged),
                                 '-storepass', 'android', '-alias', 'androiddebugkey'],
                                capture_output=True)
        if result.returncode:
            raise ValueError('Cannot read the Android debug signing certificate')
        actual = hashlib.sha256(result.stdout).hexdigest()
        if actual != expected:
            raise ValueError('Test certificate differs from the pinned identity; refusing key rotation')
        os.replace(staged, destination)
    finally:
        if staged is not None and staged.exists():
            staged.unlink()
    return actual


def verify_apk(apk, apksigner, expected):
    result = subprocess.run([str(apksigner), 'verify', '--print-certs', str(apk)],
                            capture_output=True, text=True)
    if result.returncode:
        raise ValueError('APK signature verification failed')
    certificates = re.findall(r'^Signer #\d+ certificate SHA-256 digest: ([0-9a-fA-F]{64})\s*$',
                              result.stdout, re.MULTILINE)
    if [item.lower() for item in certificates] != [expected]:
        raise ValueError('Built APK does not have the pinned test signing certificate; actual SHA-256: '
                         + ', '.join(certificates))
    return expected


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--certificate', type=Path, default=PIN)
    commands = parser.add_subparsers(dest='command', required=True)
    restore_command = commands.add_parser('restore')
    restore_command.add_argument('--destination', type=Path, default=Path.home() / '.android/debug.keystore')
    verify_command = commands.add_parser('verify-apk')
    verify_command.add_argument('--apk', type=Path, required=True)
    verify_command.add_argument('--apksigner', type=Path, required=True)
    args = parser.parse_args()
    expected = expected_certificate(args.certificate)
    try:
        if args.command == 'restore':
            actual = restore(os.environ.get('CI_DEBUG_KEYSTORE_BASE64', ''), args.destination, expected)
        else:
            actual = verify_apk(args.apk, args.apksigner, expected)
    except ValueError as error:
        parser.exit(1, str(error) + '\n')
    print('Fixed test signing certificate SHA-256: ' + actual)


if __name__ == '__main__':
    main()
