"""Prepare immutable release assets and a signed static catalogue; does not publish anything."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import shutil
from plugin_tool import compact, verify, catalogue, read_package

def build(repository, tag, configuration, key, output):
    if not re.fullmatch(r'[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+', repository) or not re.fullmatch(r'plugins-[A-Za-z0-9_.-]+', tag):
        raise ValueError('Invalid repository or release tag')
    root = Path(__file__).resolve().parent.parent
    trusted = json.loads((root / 'app/src/main/assets/plugins/review-keys.json').read_text())
    if Path(str(key)+'.pub').read_text().strip() not in trusted:
        raise ValueError('Catalogue key is not trusted by the app')
    config = json.loads(Path(configuration).read_text())
    if type(config['revision']) is not int or config['revision'] < 1 or any(not re.fullmatch(r'[a-f0-9]{64}', value) for value in config['revoked']):
        raise ValueError('Invalid catalogue configuration')
    output = Path(output)
    if output.exists():
        raise ValueError('Use a new output directory')
    assets = output / 'release'
    site = output / 'site' / 'plugins'
    assets.mkdir(parents=True)
    site.mkdir(parents=True)
    entries = []
    ids = set()
    packages = sorted((root / 'app/src/main/assets/plugins/examples').glob('*.btplugin'))
    packages += sorted((root / 'plugins/approved').glob('*.btplugin'))
    for package in packages:
        manifest = verify(package, root / 'app/src/main/assets/plugins/review-keys.json')
        if manifest['id'] in ids:
            raise ValueError('Duplicate plugin ID')
        ids.add(manifest['id'])
        content = hashlib.sha256(read_package(package)[0]['plugin.json']).hexdigest()
        if content in config['revoked']:
            continue
        name = manifest['id'] + '-' + str(manifest['version']) + '.btplugin'
        shutil.copyfile(package, assets / name)
        entries.append({'id':manifest['id'], 'name':manifest['name'], 'author':manifest['author'], 'version':manifest['version'],
                        'sha256':hashlib.sha256(package.read_bytes()).hexdigest(),
                        'contentSha256':content,
                        'url':'https://github.com/'+repository+'/releases/download/'+tag+'/'+name})
    payload = {'revision':config['revision'], 'plugins':entries, 'revoked':config['revoked']}
    raw = output / 'catalogue.payload.json'
    raw.write_bytes(compact(payload))
    catalogue(raw, key, site / 'catalog.json')
    (output / 'site/index.html').write_text('<!doctype html><meta charset="utf-8"><title>Bluetooth Assistant Plugins</title><h1>Bluetooth Assistant Plugins</h1><p>Install reviewed plugins through the Bluetooth Assistant app.</p>', encoding='utf-8')

if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--repository', default='fei1025/bluetoothAssistant')
    parser.add_argument('--tag', required=True)
    parser.add_argument('--configuration', default='plugins/catalogue.source.json')
    parser.add_argument('--review-key', required=True)
    parser.add_argument('--output', required=True)
    args = parser.parse_args()
    build(args.repository,args.tag,args.configuration,args.review_key,args.output)
