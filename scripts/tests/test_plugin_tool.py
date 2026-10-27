import base64
import hashlib
import json
import os
from pathlib import Path
import shutil
import sys
import tempfile
import unittest
from unittest.mock import patch
import zipfile

ROOT=Path(__file__).resolve().parents[2]
sys.path.insert(0,str(ROOT/'scripts'))
import plugin_tool
import build_plugin_catalog

@unittest.skipUnless(shutil.which('java'),'JDK 17 required')
class PluginToolTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.workspace=tempfile.TemporaryDirectory()
        cls.root=Path(cls.workspace.name)
        cls.author=cls.root/'author.pk8';cls.review=cls.root/'review.pk8'
        plugin_tool.crypto('keygen',cls.author);plugin_tool.crypto('keygen',cls.review)
        cls.keys=cls.root/'review-keys.json'
        cls.keys.write_text(json.dumps([Path(str(cls.review)+'.pub').read_text()]))
    @classmethod
    def tearDownClass(cls):cls.workspace.cleanup()
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory();self.addCleanup(self.temp.cleanup)
        self.source=Path(self.temp.name)/'source';self.source.mkdir()
        (self.source/'index.html').write_text('<!doctype html><h1>Test</h1>')
        (self.source/'plugin.json').write_text(json.dumps({'id':'example.test','name':'Test','author':'Test',
            'version':1,'sdk':1,'entry':'index.html','capabilities':['ble.scan']}))
    def signed(self):
        package=Path(self.temp.name)/'author.btplugin';reviewed=Path(self.temp.name)/'reviewed.btplugin'
        plugin_tool.pack(self.source,self.author,package);plugin_tool.review(package,self.review,reviewed)
        return reviewed
    def test_author_and_review_signatures_match_raw_manifest(self):
        manifest=plugin_tool.verify(self.signed(),self.keys)
        self.assertEqual('example.test',manifest['id'])
    def test_changed_resource_cannot_be_reviewed_with_stale_hash(self):
        package=self.signed();out=Path(self.temp.name)/'changed.btplugin'
        with zipfile.ZipFile(package) as z:resources={n:z.read(n) for n in z.namelist()}
        resources['index.html']=b'tampered';plugin_tool.write_zip(resources,out)
        with self.assertRaisesRegex(ValueError,'hash mismatch'):plugin_tool.review(out,self.review,Path(self.temp.name)/'re-review.btplugin')
    def test_an_author_key_cannot_approve_itself(self):
        package=Path(self.temp.name)/'author.btplugin';fake=Path(self.temp.name)/'fake.btplugin'
        plugin_tool.pack(self.source,self.author,package);plugin_tool.review(package,self.author,fake)
        with self.assertRaisesRegex(ValueError,'not trusted'):plugin_tool.verify(fake,self.keys)
    def test_output_is_immutable_and_keys_cannot_be_bundled(self):
        package=self.signed()
        with self.assertRaisesRegex(ValueError,'overwrite'):plugin_tool.pack(self.source,self.author,package)
        with self.assertRaisesRegex(ValueError,'outside'):plugin_tool.pack(self.source,self.source/'key.pk8',Path(self.temp.name)/'bad.btplugin')
        with zipfile.ZipFile(package) as archive:self.assertFalse(any(n.endswith('.pk8') for n in archive.namelist()))
    def test_dangerous_resource_and_duplicate_paths_are_rejected(self):
        for names in [{'../escape.js':b'a'},{'index.html':b'a','INDEX.html':b'b'},{'payload.dex':b'x'}]:
            with self.assertRaises(ValueError):plugin_tool.write_zip(names,Path(self.temp.name)/'bad.btplugin')
    def test_catalogue_signature_is_bound_to_exact_payload(self):
        raw=Path(self.temp.name)/'catalogue.json';out=Path(self.temp.name)/'signed.json'
        raw.write_text(json.dumps({'revision':1,'plugins':[],'revoked':[]}));plugin_tool.catalogue(raw,self.review,out)
        signed=json.loads(out.read_text());payload=base64.b64decode(signed['payload'])
        plugin_tool.crypto('verify',signed['key'],signed['signature'],message=payload)
        with self.assertRaises(Exception):plugin_tool.crypto('verify',signed['key'],signed['signature'],message=payload+b' ')
    def test_wrong_private_key_cannot_publish_with_a_trusted_public_sidecar(self):
        key=Path(self.temp.name)/'mismatched.pk8';shutil.copyfile(self.author,key)
        Path(str(key)+'.pub').write_text(Path(str(self.review)+'.pub').read_text())
        raw=Path(self.temp.name)/'catalogue.json';out=Path(self.temp.name)/'signed.json'
        raw.write_text(json.dumps({'revision':1,'plugins':[],'revoked':[]}))
        with self.assertRaises(Exception):plugin_tool.catalogue(raw,key,out)
        self.assertFalse(out.exists())
    def test_shipped_examples_match_sources_and_trusted_review_keys(self):
        keys=ROOT/'app/src/main/assets/plugins/review-keys.json'
        for name in ('classic','ble'):
            package=ROOT/'app/src/main/assets/plugins/examples'/(name+'.btplugin')
            manifest=plugin_tool.verify(package,keys)
            source=ROOT/'samples/plugins'/name
            for filename,digest in manifest['files'].items():self.assertEqual(digest,hashlib.sha256((source/filename).read_bytes()).hexdigest())
            self.assertEqual(json.loads((source/'plugin.json').read_text(encoding='utf-8'))['version'],manifest['version'])
    def test_withdrawal_only_catalogue_removes_bad_entries_and_needs_no_assets(self):
        revoked=[]
        for package in (ROOT/'app/src/main/assets/plugins/examples').glob('*.btplugin'):
            with zipfile.ZipFile(package) as z:revoked.append(hashlib.sha256(z.read('plugin.json')).hexdigest())
        config=Path(self.temp.name)/'configuration.json';config.write_text(json.dumps({'revision':2,'revoked':revoked}))
        key=Path(self.temp.name)/'catalogue-key.pk8'
        Path(str(key)+'.pub').write_text(json.loads((ROOT/'app/src/main/assets/plugins/review-keys.json').read_text())[0])
        output=Path(self.temp.name)/'published'
        with patch.object(build_plugin_catalog,'catalogue') as signing:
            build_plugin_catalog.build('fei1025/bluetoothAssistant','plugins-withdrawal',config,key,output)
            signing.assert_called_once()
        payload=json.loads((output/'catalogue.payload.json').read_text())
        self.assertEqual([],payload['plugins']);self.assertEqual(revoked,payload['revoked'])
        self.assertEqual([],list((output/'release').glob('*.btplugin')))

if __name__=='__main__':unittest.main()
