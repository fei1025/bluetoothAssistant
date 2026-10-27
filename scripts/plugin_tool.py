"""Build, sign and review .btplugin packages using Python 3 + JDK 17, with no pip dependencies."""
import argparse
import base64
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile
import zipfile

MAX_PACKAGE = 10 * 1024 * 1024
MAX_EXPANDED = 32 * 1024 * 1024
CAPABILITIES = {'classic.scan', 'classic.connect', 'classic.io', 'ble.scan', 'ble.connect', 'ble.gatt'}
CRYPTO = Path(__file__).with_name('PluginCrypto.java')

def crypto(operation, *args, message=None):
    return subprocess.run(['java', str(CRYPTO), operation, *map(str, args)], input=message,
                          stdout=subprocess.PIPE, stderr=subprocess.PIPE, check=True).stdout.decode().strip()

def compact(value):
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(',', ':')).encode('utf-8')

def safe_path(name):
    return len(name) <= 240 and re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9._/-]*', name) and not any(p in ('', '.', '..') for p in name.split('/'))

def validate_names(resources):
    if len(resources) > 512 or len({n.lower() for n in resources}) != len(resources):
        raise ValueError('Duplicate paths or file count limit exceeded')
    for name in resources:
        if not safe_path(name) or not re.search(r'\.(html|js|css|json|svg|png|jpg|jpeg|webp|gif|ico|woff2?|ttf|txt)$', name):
            raise ValueError('Invalid resource path: ' + name)
    if sum(map(len, resources.values())) > MAX_EXPANDED:
        raise ValueError('Expanded package exceeds 32 MiB')

def validate_manifest(manifest):
    if not isinstance(manifest.get('id'),str) or not re.fullmatch(r'[a-z][a-z0-9]*(?:[.-][a-z0-9]+)+', manifest['id']) or len(manifest['id']) > 100:
        raise ValueError('Invalid plugin ID')
    if any(not isinstance(manifest.get(k),str) or not 1 <= len(manifest[k]) <= 100 for k in ('name','author')):
        raise ValueError('Invalid name/author')
    if type(manifest.get('version')) is not int or not 1 <= manifest['version'] <= 2147483647 or type(manifest.get('sdk')) is not int or manifest['sdk'] != 1:
        raise ValueError('Invalid SDK/version')
    caps=manifest.get('capabilities')
    if not isinstance(caps,list) or any(not isinstance(c,str) for c in caps) or len(set(caps)) != len(caps) or not set(caps) <= CAPABILITIES:
        raise ValueError('Invalid capabilities')
    if not isinstance(manifest.get('entry'),str) or not safe_path(manifest['entry']) or not manifest['entry'].endswith('.html'):
        raise ValueError('Invalid entry')

def write_zip(resources, output):
    validate_names(resources)
    output = Path(output)
    if output.exists():
        raise ValueError('Refusing to overwrite output; choose a new version or output path')
    with tempfile.NamedTemporaryFile(dir=output.parent, suffix='.tmp', delete=False) as f:
        temporary = Path(f.name)
    try:
        with zipfile.ZipFile(temporary, 'w', compression=zipfile.ZIP_DEFLATED) as archive:
            for name, data in sorted(resources.items()):
                info = zipfile.ZipInfo(name, date_time=(2026, 1, 1, 0, 0, 0))
                info.create_system = 3
                info.external_attr = 0o100644 << 16
                info.compress_type = zipfile.ZIP_DEFLATED
                archive.writestr(info, data)
        if temporary.stat().st_size > MAX_PACKAGE:
            raise ValueError('Package exceeds 10 MiB')
        os.replace(temporary, output)
    finally:
        temporary.unlink(missing_ok=True)

def pack(source, author_key, output):
    source, author_key = Path(source).resolve(), Path(author_key).resolve()
    if author_key.is_relative_to(source):
        raise ValueError('The signing key must be outside the plugin source directory')
    manifest = json.loads((source / 'plugin.json').read_text(encoding='utf-8'))
    validate_manifest(manifest)
    if not re.fullmatch(r'[a-z][a-z0-9]*(?:[.-][a-z0-9]+)+', manifest['id']) or len(manifest['id']) > 100:
        raise ValueError('Invalid plugin ID')
    if type(manifest['version']) is not int or manifest['version'] < 1 or manifest['sdk'] != 1:
        raise ValueError('Invalid version/SDK')
    if len(set(manifest['capabilities'])) != len(manifest['capabilities']) or not set(manifest['capabilities']) <= CAPABILITIES:
        raise ValueError('Invalid capabilities')
    resources = {}
    for path in source.rglob('*'):
        if path.is_symlink():
            raise ValueError('Symlinks are not permitted')
        if path.is_file() and path.name not in ('plugin.json', 'signature.json'):
            resources[path.relative_to(source).as_posix()] = path.read_bytes()
    if manifest['entry'] not in resources or not manifest['entry'].endswith('.html'):
        raise ValueError('Missing HTML entry')
    manifest['files'] = {name: hashlib.sha256(data).hexdigest() for name, data in resources.items()}
    manifest['authorKey'] = Path(str(author_key) + '.pub').read_text().strip()
    metadata = compact(manifest)
    resources['plugin.json'] = metadata
    resources['signature.json'] = compact({'author': crypto('sign', author_key, message=metadata)})
    write_zip(resources, output)

def read_package(package):
    package = Path(package)
    if package.stat().st_size > MAX_PACKAGE:
        raise ValueError('Package exceeds 10 MiB')
    with zipfile.ZipFile(package) as archive:
        if len(archive.infolist()) > 512 or sum(i.file_size for i in archive.infolist()) > MAX_EXPANDED:
            raise ValueError('Expanded package limit exceeded')
        names = [i.filename for i in archive.infolist()]
        if len(set(n.lower() for n in names)) != len(names):
            raise ValueError('Duplicate paths')
        for info in archive.infolist():
            if info.flag_bits & 1 or (info.create_system == 3 and ((info.external_attr >> 16) & 0o170000) not in (0, 0o100000)):
                raise ValueError('Encrypted or non-regular resource')
        resources = {name: archive.read(name) for name in names}
    validate_names(resources)
    metadata = resources['plugin.json']
    manifest = json.loads(metadata)
    validate_manifest(manifest)
    signatures = json.loads(resources['signature.json'])
    if manifest.get('sdk') != 1 or type(manifest.get('version')) is not int or manifest['version'] < 1:
        raise ValueError('Invalid SDK/version')
    if not re.fullmatch(r'[a-z][a-z0-9]*(?:[.-][a-z0-9]+)+', manifest['id']) or len(manifest['id']) > 100:
        raise ValueError('Invalid plugin ID')
    if not isinstance(manifest['capabilities'], list) or len(set(manifest['capabilities'])) != len(manifest['capabilities']) or not set(manifest['capabilities']) <= CAPABILITIES:
        raise ValueError('Invalid capabilities')
    if manifest['entry'] not in resources or not manifest['entry'].endswith('.html'):
        raise ValueError('Invalid entry')
    actual = {n: hashlib.sha256(data).hexdigest() for n, data in resources.items() if n not in ('plugin.json', 'signature.json')}
    if manifest['files'] != actual:
        raise ValueError('Resource hash mismatch')
    crypto('verify', manifest['authorKey'], signatures['author'], message=metadata)
    return resources, manifest, signatures

def verify(package, review_keys):
    resources, manifest, signatures = read_package(package)
    keys = json.loads(Path(review_keys).read_text(encoding='utf-8'))
    if signatures.get('reviewKey') not in keys:
        raise ValueError('Review signature is not trusted')
    crypto('verify', signatures['reviewKey'], signatures['review'], message=resources['plugin.json'])
    return manifest

def review(package, review_key, output):
    resources, manifest, signatures = read_package(package)
    signatures['reviewKey'] = Path(str(review_key) + '.pub').read_text().strip()
    signatures['review'] = crypto('sign', review_key, message=resources['plugin.json'])
    resources['signature.json'] = compact(signatures)
    write_zip(resources, output)

def catalogue(source, key, output):
    payload = compact(json.loads(Path(source).read_text(encoding='utf-8')))
    signed = {'payload': base64.b64encode(payload).decode(), 'key': Path(str(key)+'.pub').read_text().strip(),
              'signature': crypto('sign', key, message=payload)}
    crypto('verify', signed['key'], signed['signature'], message=payload)
    destination = Path(output)
    with destination.open('xb') as f:
        f.write(compact(signed))

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest='action', required=True)
    gen = sub.add_parser('keygen'); gen.add_argument('private_key')
    packer = sub.add_parser('pack'); packer.add_argument('source'); packer.add_argument('--author-key', required=True); packer.add_argument('--output', required=True)
    reviewer = sub.add_parser('review'); reviewer.add_argument('package'); reviewer.add_argument('--review-key', required=True); reviewer.add_argument('--output', required=True)
    checker = sub.add_parser('verify'); checker.add_argument('package'); checker.add_argument('--review-keys', required=True)
    catalog = sub.add_parser('catalogue'); catalog.add_argument('source'); catalog.add_argument('--review-key', required=True); catalog.add_argument('--output', required=True)
    args = parser.parse_args()
    if args.action == 'keygen': crypto('keygen', args.private_key)
    elif args.action == 'pack': pack(args.source, args.author_key, args.output)
    elif args.action == 'review': review(args.package, args.review_key, args.output)
    elif args.action == 'verify':
        manifest = verify(args.package, args.review_keys)
        print(manifest['id'] + ' v' + str(manifest['version']) + ': verified')
    else: catalogue(args.source, args.review_key, args.output)

if __name__ == '__main__':
    main()
