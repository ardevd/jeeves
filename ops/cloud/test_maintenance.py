import fcntl,json,sys,tempfile,unittest
from pathlib import Path
from unittest.mock import patch
import campaign,maintenance
from checkpoint_backup import durable_json

class Tests(unittest.TestCase):
    def setUp(self):
        self.tmp=tempfile.TemporaryDirectory();self.root=Path(self.tmp.name).resolve()
        self.db=campaign.catalog_db(self.root)
        self.db.execute("INSERT INTO sessions(id,phase,slots,created) VALUES(1,'complete','[]',0)");self.db.commit()
        self.invocation={'phase':'running','started_at':100,'mode':'window','deadline_at':28900}
        self.request={'revision':'abcdef0123','session':1,'invocation_started_at':100}
        durable_json(self.root/'invocation.json',self.invocation)
        durable_json(self.root/'maintenance-request.json',self.request)
        self.lock=(self.root/'controller.lock').open('a');fcntl.flock(self.lock,fcntl.LOCK_EX)
    def tearDown(self):self.lock.close();self.db.close();self.tmp.cleanup()
    def receipt(self,**values):
        result=dict(phase='complete',revision=self.request['revision'],session=1,live_checks=8,
                    invocation_started_at=100,
                    runtime={'build_sha':self.request['revision'],'build_dirty':False})
        result.update(values)
        durable_json(self.root/'deployment.json',{'maintenance':result})
    def test_handoff_releases_real_lock_and_preserves_deadline(self):
        def deploy_wait(seconds):
            with (self.root/'controller.lock').open('a') as deployment_lock:
                fcntl.flock(deployment_lock,fcntl.LOCK_EX|fcntl.LOCK_NB)
                self.assertTrue(maintenance.admission_held(self.root,1,self.request['revision'],self.invocation))
                self.receipt()
        with patch.object(maintenance.time,'sleep',side_effect=deploy_wait):
            maintenance.wait_at_boundary(self.root,self.db,self.invocation,self.lock)
        with (self.root/'controller.lock').open('a') as other:
            with self.assertRaises(BlockingIOError):fcntl.flock(other,fcntl.LOCK_EX|fcntl.LOCK_NB)
        self.assertEqual(json.loads((self.root/'invocation.json').read_text()),self.invocation)
        self.assertEqual(self.db.execute('SELECT count(*) FROM sessions').fetchone()[0],1)
    def test_active_session_is_not_held_or_interrupted(self):
        self.db.execute("UPDATE sessions SET phase='agents'");self.db.commit()
        maintenance.wait_at_boundary(self.root,self.db,self.invocation,self.lock)
        self.assertFalse((self.root/'maintenance-ready.json').exists())
    def test_unverified_receipt_cannot_resume(self):
        for fields in ({'phase':'verifying'},{'live_checks':0},{'session':2},{'invocation_started_at':99},{'runtime':{'build_sha':'wrong','build_dirty':False}}):
            self.receipt(**fields);self.assertFalse(maintenance.completed(self.root,self.request))
        self.receipt();self.assertTrue(maintenance.completed(self.root,self.request))
    def test_stale_request_or_readiness_cannot_authorize_deployment(self):
        for key,value in (('revision','wrong'),('session',2),('invocation_started_at',99)):
            ready=dict(self.request,phase='ready');ready[key]=value
            durable_json(self.root/'maintenance-ready.json',ready)
            self.assertFalse(maintenance.admission_held(self.root,1,self.request['revision'],self.invocation))
        self.db.execute('UPDATE sessions SET id=2');self.db.commit()
        with self.assertRaisesRegex(RuntimeError,'does not match'):maintenance.wait_at_boundary(self.root,self.db,self.invocation,self.lock)
    def test_restarted_controller_waits_for_receipt_before_any_api_operation(self):
        self.lock.close()
        config=self.root/'config.json'
        durable_json(config,dict(root=str(self.root),url='http://127.0.0.1:7800',artifact=str(self.root/'input.apk'),project='fixture',models=['fixture']*8))
        class API:
            def call(self,*a,**k):raise AssertionError('API operation during maintenance')
        def finish(seconds):
            self.receipt();(self.root/'stop-after-session').touch()
        with patch.object(sys,'argv',['campaign.py','run','--config',str(config),'--hours','8']),patch.object(campaign,'ROOT',self.root),patch.object(campaign,'sha256',return_value=campaign.APK_SHA),patch.object(campaign,'API',return_value=API()),patch.object(maintenance.time,'sleep',side_effect=finish):
            campaign.main()
        inv=json.loads((self.root/'invocation.json').read_text())
        self.assertEqual(inv['deadline_at'],self.invocation['deadline_at']);self.assertEqual(inv['phase'],'complete')
        self.assertEqual(self.db.execute('SELECT count(*) FROM sessions').fetchone()[0],1)

if __name__=='__main__':unittest.main()
