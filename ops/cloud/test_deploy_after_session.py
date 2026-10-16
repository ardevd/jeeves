import json, sqlite3, tempfile, unittest
from pathlib import Path
from unittest.mock import patch
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
        if op=='workspace.state':return {'runtime':{'build_sha':self.request['revision'],'build_dirty':False}}
        if op=='project.open':return {}
        raise AssertionError('Unexpected call: '+op)

class Tests(unittest.TestCase):
    def setUp(self):
        self.tmp=tempfile.TemporaryDirectory();self.root=Path(self.tmp.name).resolve()
        self.folder=self.root/'rounds/cloud-000001';(self.folder/'barrier').mkdir(parents=True);(self.folder/'quality').mkdir()
        self.source=self.root/'source';self.source.mkdir();(self.source/'original').write_text('old release')
        self.release=self.root/'releases/new';self.release.mkdir(parents=True);(self.release/'new').write_text('new release')
        self.artifact=self.root/'input.apk';self.artifact.write_text('apk')
        self.db=self.root/'input.jdb2';self.db.write_text('saved native names')
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
        with patch.object(deploy,'run_barrier',side_effect=barrier) as saved:
            self.assertEqual(self.run_deploy()['phase'],'complete');self.assertEqual(saved.call_count,1)
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
