"""Offline canonical recorder/import checks; only explicitly owned synthetic workers."""
import copy
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import time
import unittest
import uuid

HERE=Path(__file__).resolve().parent
sys.path.insert(0,str(HERE))
import bundle

FIXTURE=bundle.ROOT/'fixtures/local-run/v1'


class RecorderTests(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory(prefix='jeeves local recorder ');self.addCleanup(self.temp.cleanup)
        self.root=Path(self.temp.name)
    def recorder(self,budget=100):
        return bundle.Recorder(FIXTURE,self.root/'bundle',str(uuid.uuid4()),budget)
    def events(self):
        return [json.loads(line) for line in (FIXTURE/'events/000001.jsonl').read_text().splitlines()]
    def test_complete_case_sensitive_capture_and_selected_export(self):
        recorder=self.recorder()
        for row in self.events(): recorder.append(row)
        result=recorder.finish('deadline',{'state':'not-applicable','basis':'fixture','resources':[]})
        self.assertEqual(result['events'],4);self.assertEqual(result['completion'],'complete')
        (self.root/'bundle'/'private-unselected.txt').write_text('never exported')
        copied=bundle.selected_copy(self.root/'bundle',self.root/'export')
        self.assertEqual(copied['manifest_sha256'],result['manifest_sha256'])
        self.assertFalse((self.root/'export'/'private-unselected.txt').exists())
        self.assertEqual(bundle.manifest(self.root/'export')['coverage']['observed_methods'],2)
    def test_budget_is_partial_and_losses_explicit(self):
        recorder=self.recorder(2)
        for row in self.events(): recorder.append(row)
        result=recorder.finish('event-budget',{'state':'not-applicable','basis':'fixture','resources':[]})
        self.assertEqual(result['completion'],'partial')
        self.assertEqual(bundle.manifest(self.root/'bundle')['capture']['loss']['known_dropped'],2)
    def test_owner_crash_tail_recovery_preserves_complete_lines(self):
        recorder=self.recorder()
        for row in self.events(): recorder.append(row)
        recorder.stream.write(b'{"incomplete":');recorder.stream.flush();recorder.stream.close();recorder.stream=None
        result=bundle.recover_bundle(self.root)
        self.assertEqual(result['events'],4);self.assertEqual(result['completion'],'partial');self.assertEqual(result['cleanup'],'unknown')
        self.assertTrue(bundle.manifest(self.root/'bundle')['capture']['loss']['unknown_loss'])
        self.assertEqual(bundle.recover_bundle(self.root),result)
    def test_import_external_preserves_declared_provenance(self):
        source=self.root/'external';bundle.selected_copy(FIXTURE,source)
        doc=bundle.manifest(source)
        def external(item):
            if isinstance(item,dict):
                for key,value in item.items():
                    if key in ('basis','provenance','identity_basis','state_basis','split_provenance'): item[key]='external-declared'
                    else: external(value)
            elif isinstance(item,list):
                for value in item: external(value)
        external(doc);doc['producer']['mode']='external';doc['device']['platform']='android'
        doc['target']['artifact']['kind']='apk';doc['session']['ownership']='external-declared'
        doc['privacy'].update(projection='selected',sensitive_content_review='not-reviewed')
        doc['capture'].update(completion='partial',stop_reason='owner-crash')
        doc['capture']['loss']['unknown_loss']=True;doc['cleanup'].update(state='unknown')
        bundle.atomic(source/'manifest.json',doc)
        before=(source/'manifest.json').read_bytes()
        result=bundle.selected_copy(source,self.root/'imported')
        self.assertEqual(result['producer_mode'],'external');self.assertEqual(result['completion'],'partial')
        self.assertEqual((self.root/'imported'/'manifest.json').read_bytes(),before)
    def test_existing_destination_and_mutated_input_refused(self):
        target=self.root/'destination';target.mkdir();(target/'keep.txt').write_text('keep')
        with self.assertRaises(ValueError): bundle.selected_copy(FIXTURE,target)
        source=self.root/'source';bundle.selected_copy(FIXTURE,source)
        (source/'evidence/fixture-input.txt').write_text('tampered')
        with self.assertRaises(ValueError): bundle.selected_copy(source,self.root/'bad')
        self.assertEqual((target/'keep.txt').read_text(),'keep')
    def test_symlink_source_refused(self):
        source=self.root/'source';source.symlink_to(FIXTURE,target_is_directory=True)
        with self.assertRaises(OSError): bundle.selected_copy(source,self.root/'copy')

    def test_recovery_refuses_symlink_or_hardlinked_event_without_touching_it(self):
        recorder=self.recorder()
        recorder.append(self.events()[0]);recorder.close_segment()
        path=self.root/'bundle/events/000001.jsonl'
        outside=self.root/'outside';outside.write_text('private bytes')
        path.unlink();path.symlink_to(outside)
        with self.assertRaises(OSError): bundle.recover_bundle(self.root)
        self.assertEqual(outside.read_text(),'private bytes')
        path.unlink();os.link(outside,path)
        with self.assertRaises(ValueError): bundle.recover_bundle(self.root)
        self.assertEqual(outside.read_text(),'private bytes')

    def test_recovery_ignores_empty_crash_segment_and_preserves_loss(self):
        recorder=self.recorder();recorder.loss('Earlier fixture loss',known=1)
        (self.root/'bundle/events').mkdir(exist_ok=True)
        (self.root/'bundle/events/000001.jsonl').write_bytes(b'')
        result=bundle.recover_bundle(self.root)
        self.assertEqual(result['events'],0)
        self.assertEqual(result['completion'],'partial')
        self.assertIn('Earlier fixture loss',bundle.manifest(self.root/'bundle')['capture']['loss']['details'])

    def test_startup_failure_recovers_as_empty_partial_without_replay(self):
        import local_run
        root=self.root/'never-ready';root.mkdir()
        bundle.selected_copy(FIXTURE,root/'template')
        bundle.atomic(root/'state.json',{'state':'starting','run_id':str(uuid.uuid4()),'event_budget':20,'fixture':True})
        result=local_run.recover(root)
        self.assertEqual(result['completion'],'partial');self.assertEqual(result['events'],0)
        self.assertEqual(result['cleanup'],'unknown')

    def test_normal_real_mode_cleanup_does_not_invent_capture_loss(self):
        import local_run
        from types import SimpleNamespace
        from unittest.mock import patch
        root=self.root/'fake-controller';root.mkdir();(root/'commands').mkdir()
        bundle.selected_copy(bundle.ROOT/'fixtures/local-run/external-partial',root/'template')
        doc=bundle.manifest(root/'template')
        state={'state':'starting','run_id':str(uuid.uuid4()),'event_budget':20,'fixture':False,
               'device':'fixture-selected','server_path':'/fixture/server','ownership_confirmed':True,
               'seconds':1,'token':'fixture-control','template_manifest_sha256':bundle.validator.validate(root/'template')['manifest_sha256']}
        bundle.atomic(root/'state.json',state)
        def shell(command):
            if command.startswith('pm path '):return 'package:/fixture/input.apk'
            if command.startswith('sha256sum '):return doc['target']['artifact']['sha256']+'  /fixture/input.apk'
            if command.startswith('stat '):return str(doc['target']['artifact']['size'])
            raise AssertionError(command)
        fake=SimpleNamespace(_lifecycle=SimpleNamespace(verify=lambda row:SimpleNamespace(shell=shell)))
        row={'controller_id':str(uuid.uuid4()),'client_version':'17.18.0','server':{'version':'17.18.0','sha256':'a'*64},'observations':{'selinux':'Enforcing'}}
        fake.op_provision=lambda params:{'ownership':row}
        fake.op_appversion=lambda params:{'versionName':doc['target']['version'],'versionCode':doc['target']['version']}
        def attach(params):
            return {'session':'fixture-session','pid':4321,'processIdentity':{'boot_id':'fixture-boot','start_ticks':'123','executable':'/fixture/app'},'hooks':[{'hookId':h['hookId'],'ok':True} for h in params['hooks']]}
        fake.op_attach=attach
        def cleanup(params):
            fake.emit('session',state='dead',cleanup='complete')
            return {'cleanup':{'cleanup':'complete','cleanup_results':[{'resource':'fixture-server','state':'released'}]}}
        fake.op_cleanup=cleanup
        with patch.dict(sys.modules,{'bridge':fake}),patch.dict(os.environ),patch.object(local_run.signal,'signal'):
            result=local_run.worker(root)
        self.assertEqual(result['state'],'closed');self.assertEqual(result['completion'],'complete')
        final=bundle.manifest(root/'bundle')
        self.assertEqual(final['cleanup']['state'],'complete')
        self.assertFalse(final['capture']['loss']['unknown_loss'])
        self.assertEqual(final['producer']['mode'],'local')



class BackgroundTests(unittest.TestCase):
    def test_background_start_mark_stop_offline(self):
        with tempfile.TemporaryDirectory(prefix='jeeves background fixture ') as temp:
            root=Path(temp)/'owned run'
            def cli(*args):
                process=subprocess.run([sys.executable,'-I','-B',str(HERE/'local_run.py'),*map(str,args)],text=True,capture_output=True,timeout=110)
                self.assertEqual(process.returncode,0,process.stdout+process.stderr)
                return json.loads(process.stdout)
            started=cli('start','--template',FIXTURE,'--fixture','--seconds','5','--run',root)
            try:
                self.assertEqual(started['state'],'ready');self.assertNotIn('token',started)
                self.assertTrue(cli('mark','--run',root,'--text','Human fixture phase')['accepted'])
                cli('stop','--run',root)
                deadline=time.monotonic()+10
                while time.monotonic()<deadline:
                    state=cli('status','--run',root)
                    if state['state']=='closed': break
                    time.sleep(0.1)
                self.assertEqual(state['state'],'closed')
                result=bundle.validator.validate(root/'bundle')
                self.assertEqual(result['events'],5);self.assertEqual(result['cleanup'],'not-applicable')
                again=cli('stop','--run',root)
                self.assertEqual(again['manifest_sha256'],state['manifest_sha256'])
            finally:
                # The worker's five-second deadline is an additional bound; never signal another process.
                deadline=time.monotonic()+8
                while time.monotonic()<deadline:
                    if json.loads((root/'state.json').read_text())['state'] in ('closed','failed'):break
                    time.sleep(0.1)


if __name__=='__main__': unittest.main(verbosity=2)
