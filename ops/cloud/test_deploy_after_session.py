import json, os, sqlite3, tarfile, tempfile, unittest
from contextlib import ExitStack
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch
import barrier
import checkpoint_backup
import deploy_after_session as deploy
from checkpoint_backup import sha256

class API:
    def __init__(self,request):
        self.request=request;self.calls=[];self.missions=[dict(id=f'm{i}',state='done',loopActive=False) for i in range(8)]
        self.closed=False;self.changes={'dirty':False,'symbols':0,'decompiled':0};self.active=[]
    def call(self,op,**params):
        self.calls.append(op)
        if op=='agent.missions':return self.missions
        if op=='ops.active':return self.active
        if op=='target.changes':return self.changes
        if op=='target.list':return [] if self.closed else [{'id':'t1','path':self.request['artifact'],'state':'ready'}]
        if op=='target.close':self.closed=True;return {}
        if op=='target.open':self.closed=False;return {'id':'t1'}
        if op=='target.info':return {'state':'ready','jdb2':str(Path(self.request['artifact']).with_suffix('.jdb2'))}
        if op=='target.saveStatus':return {'saving':False,'last':{'at':1,'ok':True}}
        if op=='target.save':
            Path(self.request['artifact']).with_suffix('.jdb2').write_text('saved native names and review analysis')
            self.changes['decompiled']=0
            return {'startedAt':1}
        if op=='symbol.resolve':return {'origSig':params['symbol'],'name':'Fixture'}
        if op=='workspace.state':return {'runtime':{'build_sha':self.request['revision'],'build_dirty':False}}
        if op=='project.open':return {}
        raise AssertionError('Unexpected call: '+op)

class Tests(unittest.TestCase):
    def setUp(self):
        self.tmp=tempfile.TemporaryDirectory();self.root=Path(self.tmp.name).resolve()
        self.folder=self.root/'rounds/cloud-000001';(self.folder/'barrier').mkdir(parents=True);(self.folder/'quality').mkdir()
        self.source=self.root/'source';self.source.mkdir();(self.source/'original').write_text('old release')
        self.release=self.root/'releases/new';self.release.mkdir(parents=True);(self.release/'new').write_text('new release')
        (self.root/'inputs').mkdir()
        self.artifact=self.root/'inputs/input.apk';self.artifact.write_text('apk')
        self.db=self.root/'inputs/input.jdb2';self.db.write_text('saved native names')
        Path(str(self.db)+'.key').write_text('fixture key')
        self.history=Path(str(self.artifact)+'.workbench/history.json');self.history.parent.mkdir();self.history.write_text('[]')
        self.request={'root':str(self.root),'release':str(self.release),'source':str(self.source),'revision':'abc123','session':1,'artifact':str(self.artifact),'project':'fixture'}
        self.api=API(self.request);self.control=[]
        self.write(self.root/'invocation.json',{'phase':'complete','rounds':1})
        self.write(self.root/'deployment.json',{'revision':'old'})
        self.receipt={'ok':True,'phase':'complete','target':'t1','db':str(self.db),'database_sha256':sha256(self.db)}
        self.write(self.folder/'barrier/receipt.json',self.receipt);self.write(self.folder/'barrier/expected.json',{'symbols':[]})
        self.quality={'status':'complete','read_only':True,'history_unchanged':True,'history_sha256':sha256(self.history),'classes':[{}]*8}
        self.write(self.folder/'quality/receipt.json',self.quality)
        with sqlite3.connect(self.root/'catalog.sqlite3') as db:
            db.execute('CREATE TABLE sessions(id INTEGER,phase TEXT)');db.execute("INSERT INTO sessions VALUES(1,'complete')")
    def tearDown(self):self.tmp.cleanup()
    def write(self,path,value):path.write_text(json.dumps(value))
    def run_deploy(self):
        with patch.object(deploy,'ROOT',self.root),patch.object(deploy,'verify_release'),patch.object(deploy,'ensure_target',return_value='t1'),patch.object(deploy,'verify_live',return_value={'ok':True,'checked':9}):
            return deploy.deploy(self.request,self.api,lambda *args:self.control.append(args))
    def test_boundary_requires_drained_agents_saved_verified_reviewed_session(self):
        self.assertIsNotNone(deploy.boundary(self.root,1,self.api))
        for flag in ('loopActive','cancellationUnconfirmed'):
            self.api.missions[0][flag]=True;self.assertIsNone(deploy.boundary(self.root,1,self.api));self.api.missions[0][flag]=False
        self.api.active=[{'actor':'llm:finishing','op':'rename'}];self.assertIsNone(deploy.boundary(self.root,1,self.api));self.api.active=[]
        self.quality['status']='stale';self.write(self.folder/'quality/receipt.json',self.quality)
        self.assertIsNone(deploy.boundary(self.root,1,self.api))
    def test_success_preserves_old_release_verifies_and_never_launches_agents(self):
        result=self.run_deploy();self.assertEqual(result['phase'],'complete');self.assertEqual(result['live_checks'],9)
        self.assertEqual(self.source.resolve(),self.release.resolve())
        self.assertEqual((self.root/'releases/before-abc123/original').read_text(),'old release')
        self.assertEqual(self.control,[('stop','jeeves.service'),('start','jeeves.service')])
        self.assertNotIn('agent.spawn',self.api.calls);self.assertNotIn('target.save',self.api.calls)
        self.assertEqual(self.db.read_text(),'saved native names')
        self.control.clear();self.run_deploy();self.assertEqual(self.control,[])
    def test_unsaved_changes_never_stop_the_server(self):
        self.api.changes['dirty']=True
        with self.assertRaisesRegex(RuntimeError,'unsaved edits'):self.run_deploy()
        self.assertEqual(self.control,[]);self.assertFalse(self.api.closed)
    def test_changed_history_never_stops_the_server(self):
        self.history.write_text('[{"newName":"unexpected"}]')
        with self.assertRaisesRegex(RuntimeError,'changed after quality'):self.run_deploy()
        self.assertEqual(self.control,[])
    def test_live_session_waits_without_stopping_or_spawning(self):
        self.write(self.root/'invocation.json',{'phase':'running'})
        with patch.object(deploy.time,'sleep',side_effect=RuntimeError('fixture ends waiting')):
            with self.assertRaisesRegex(RuntimeError,'fixture ends'):self.run_deploy()
        self.assertEqual(self.control,[]);self.assertNotIn('agent.spawn',self.api.calls)
    def test_restart_after_directory_moved_finishes_activation_without_deletion(self):
        previous=self.root/'releases/previous';self.source.rename(previous)
        deploy.activate(self.source,self.release,previous);deploy.activate(self.source,self.release,previous)
        self.assertEqual(self.source.resolve(),self.release);self.assertTrue((previous/'original').exists())
    def test_bad_release_hash_is_rejected_before_switch(self):
        self.write(self.release/'release-manifest.json',{'revision':'abc123','files':{'new':'wrong'}})
        with self.assertRaisesRegex(RuntimeError,'mismatch'):deploy.verify_release(self.release,'abc123')
        self.assertTrue((self.source/'original').exists())
    def test_review_decompilations_pass_another_barrier_before_close(self):
        self.api.changes['decompiled']=1
        def barrier(api,folder,artifact):
            self.assertFalse(self.api.closed);self.assertEqual(self.control,[])
            (folder/'barrier').mkdir(parents=True);self.write(folder/'barrier/expected.json',{'symbols':[]})
            self.api.changes['decompiled']=0
            return self.receipt
        with patch.object(deploy,'presave',side_effect=barrier) as saved:
            self.assertEqual(self.run_deploy()['phase'],'complete');self.assertEqual(saved.call_count,1)
    def real_barrier_fixture(self,stack,native):
        self.write(self.history,[{'op':'rename','kind':'class','addr':'LFixture;','originalSymbol':'LFixture;'}])
        self.quality['history_sha256']=sha256(self.history);self.write(self.folder/'quality/receipt.json',self.quality)
        (self.root/'home/projects').mkdir(parents=True)
        for module,name,value in [(barrier,'ROOT',self.root),(checkpoint_backup,'ROOT',self.root),
                                  (barrier,'APK_SHA',sha256(self.artifact)),(barrier,'LIVE_HOME',self.root/'home')]:
            stack.enter_context(patch.object(module,name,value))
        stack.enter_context(patch.object(barrier,'native_verify',side_effect=native))
        stack.enter_context(patch.object(deploy,'presave',side_effect=barrier.run_barrier))
    def test_real_barrier_creates_missing_maintenance_round_and_checks_new_save(self):
        self.api.changes['decompiled']=1
        def native(db,expected,folder,checkpoint):
            self.assertTrue(self.api.closed);self.assertEqual(self.control,[])
            self.assertEqual(json.loads(expected.read_text())['symbols'][0]['name'],'Fixture')
            with tarfile.open(checkpoint/'checkpoint.tar') as archive:
                self.assertEqual(archive.extractfile('target/input.jdb2').read(),db.read_bytes())
            return {'checked':1,'ok':True}
        with ExitStack() as stack:
            self.real_barrier_fixture(stack,native)
            result=self.run_deploy()
        self.assertEqual(result['phase'],'complete')
        self.assertEqual(result['database_sha256'],sha256(self.db))
        self.assertNotEqual(result['database_sha256'],self.receipt['database_sha256'])
        self.assertEqual(result['barrier']['native_checks'],1)
        self.assertEqual(self.api.calls.count('target.save'),1)
        self.assertNotIn('agent.spawn',self.api.calls)
    def test_interrupted_native_presave_resumes_with_no_decompiled_changes(self):
        self.api.changes['decompiled']=1
        with ExitStack() as stack:
            self.real_barrier_fixture(stack,[RuntimeError('interrupted native verifier'),{'checked':1,'ok':True}])
            with self.assertRaisesRegex(RuntimeError,'interrupted native'):self.run_deploy()
            self.assertTrue(self.api.closed);self.assertEqual(self.control,[])
            self.assertEqual(self.api.changes['decompiled'],0)
            # The final deployment reopen is legitimate; reopening before the
            # resumed native stage would consume production heap unnecessarily.
            def ensure(api,artifact):
                self.assertIn(('start','jeeves.service'),self.control)
                return barrier.ensure_target(api,artifact)
            with patch.object(deploy,'ROOT',self.root),patch.object(deploy,'verify_release'),patch.object(deploy,'verify_live',return_value={'ok':True,'checked':1}),patch.object(deploy,'ensure_target',side_effect=ensure):
                result=deploy.deploy(self.request,self.api,lambda *args:self.control.append(args))
        self.assertEqual(result['phase'],'complete');self.assertEqual(result['database_sha256'],sha256(self.db))
        self.assertEqual(self.api.calls.count('target.save'),1)
        self.assertNotIn('agent.spawn',self.api.calls)
    def test_presave_child_uses_service_account_and_pinned_script(self):
        self.api.url='http://127.0.0.1:7800'
        account=SimpleNamespace(pw_uid=os.getuid(),pw_gid=os.getgid(),pw_name='jeeves',pw_dir=str(self.root/'home'))
        folder=self.root/'new-presave'
        def child(command,**kwargs):
            self.assertEqual(command[1],str(Path(deploy.__file__).resolve()))
            self.assertEqual(kwargs['user'],account.pw_uid);self.assertEqual(kwargs['group'],account.pw_gid)
            self.assertEqual(kwargs['env']['HOME'],account.pw_dir)
            self.assertEqual(kwargs['extra_groups'],[account.pw_gid]);self.assertTrue(kwargs['check'])
            self.assertEqual(folder.stat().st_uid,account.pw_uid)
            (folder/'barrier').mkdir();self.write(folder/'barrier/receipt.json',self.receipt)
        with patch.object(deploy.pwd,'getpwnam',return_value=account),patch.object(deploy.os,'geteuid',return_value=0),patch.object(deploy.os,'getgrouplist',return_value=[account.pw_gid]),patch.object(deploy.subprocess,'run',side_effect=child) as run:
            self.assertEqual(deploy.presave(self.api,folder,self.artifact),self.receipt)
            self.assertEqual(run.call_count,1)
    def test_restart_in_verifying_reuses_release_and_does_not_repeat_stop(self):
        # A crash after activation must retry only reopen/verification, never
        # launch a campaign or overwrite the prior release directory.
        with patch.object(deploy,'ROOT',self.root),patch.object(deploy,'verify_release'),patch.object(deploy,'ensure_target',return_value='t1'),patch.object(deploy,'verify_live',side_effect=RuntimeError('interrupted verification')):
            with self.assertRaisesRegex(RuntimeError,'interrupted verification'):
                deploy.deploy(self.request,self.api,lambda *args:self.control.append(args))
        self.assertEqual(self.source.resolve(),self.release)
        self.control.clear();self.assertEqual(self.run_deploy()['phase'],'complete')
        self.assertEqual(self.control,[('start','jeeves.service')]);self.assertNotIn('agent.spawn',self.api.calls)
    def test_corrupt_database_never_stops_service(self):
        self.db.write_text('not the saved database')
        with self.assertRaisesRegex(RuntimeError,'Database changed'):self.run_deploy()
        self.assertEqual(self.control,[]);self.assertTrue(self.source.is_dir());self.assertFalse(self.source.is_symlink())

if __name__=='__main__':unittest.main()
