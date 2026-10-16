import json, tempfile, unittest, sys
from pathlib import Path
from unittest.mock import patch
import campaign

MODELS=['deepseek:deepseek-v4-pro']*4+['zai:glm-5.3']*4
class CatalogAPI:
    def call(self,op,**p):
        if op=='target.info':return {'classes':8}
        if op=='symbol.tree':
            if p['prefix']=='':return {'items':[{'kind':'package','prefix':'LX/'}],'truncated':False}
            names=['LA;','La;','LK;','LK;','LB;','LC;','LD;','LE;']
            return {'items':[{'kind':'class','origSig':n,'name':n} for n in names],'truncated':False}
        raise AssertionError(op)
class SessionAPI:
    def __init__(self):self.missions=[];self.calls=[];self.lost=False;self.coverage_status='complete'
    def call(self,op,**p):
        self.calls.append(op)
        if op=='agent.missions':
            if len(self.missions)==8:
                for m in self.missions:m['state']='done'
            return list(self.missions)
        if op=='agent.spawn':
            m=dict(id='m'+str(len(self.missions)+1),agentId='agent-'+str(len(self.missions)+1),state='running',goal=p['goal'],turnsUnlimited=True,budgetUnlimited=True)
            self.missions.append(m)
            if self.lost:self.lost=False;raise ConnectionResetError('lost spawn acknowledgement')
            return m
        if op=='agent.turns':return [{'turn':1}]
        if op=='code.outline':return {'symbol':{'origSig':p['class']},'methods':[{'origSig':p['class']+'->a()V'}]}
        if op=='naming.coverage':return {'originalMethod':p['symbol'],'recordedBy':p['recordedBy'],'status':self.coverage_status,
                'counts':{'total':3,'retained':3,'renamed':0,'missing':0,'unresolved':0},'inventoryFingerprint':'fixture','inventoryErrors':[],'variables':[]}
        if op=='agent.turn':
            m=next(x for x in self.missions if x['id']==p['mission'])
            original=m['goal'].split('You own exactly one class: ',1)[1].split('.',1)[0]
            receipt=dict(original_class=original,status='complete',fields_seen=1,methods_seen=1,arguments_seen=2,locals_seen=3,renamed=1,retained=6,unresolved=[])
            return {'text':'CLASS_RESULT: '+json.dumps(receipt)}
        raise AssertionError(op)

class Tests(unittest.TestCase):
    def setUp(self):
        self.tmp=tempfile.TemporaryDirectory();self.root=Path(self.tmp.name);self.db=campaign.catalog_db(self.root)
        history=self.root/'input.apk.workbench';history.mkdir();(history/'history.json').write_text('[]')
        campaign.catalog(CatalogAPI(),'t1',self.db)
    def tearDown(self):self.db.close();self.tmp.cleanup()
    def test_case_sensitive_complete_catalog_and_atomic_eight(self):
        self.assertEqual(self.db.execute('SELECT count(*) FROM classes').fetchone()[0],8)
        first=campaign.claim_session(self.db,MODELS);again=campaign.claim_session(self.db,MODELS)
        self.assertEqual(first,again)
        slots=json.loads(first['slots']);self.assertEqual(len({s['original_class'] for s in slots}),8)
        self.assertEqual([s['model'] for s in slots],MODELS)
    def test_truncated_catalog_is_rejected(self):
        api=CatalogAPI();old=api.call
        def truncated(op,**p):
            result=old(op,**p)
            if op=='symbol.tree':result['truncated']=True
            return result
        api.call=truncated
        with self.assertRaisesRegex(RuntimeError,'truncated'):campaign.catalog(api,'t1',self.db)
    def test_lost_spawn_ack_recovers_without_duplicate_and_barrier_orders_next(self):
        api=SessionAPI();api.lost=True;session=campaign.claim_session(self.db,MODELS)
        with patch.object(campaign,'ensure_target',return_value='t1'),patch.object(campaign.time,'sleep'):
            with self.assertRaises(ConnectionResetError):campaign.run_session(api,self.db,session,self.root/'input.apk',self.root)
            session=dict(self.db.execute('SELECT * FROM sessions').fetchone())
            with patch.object(campaign,'run_barrier',side_effect=RuntimeError('save verification failed')):
                with self.assertRaisesRegex(RuntimeError,'verification failed'):campaign.run_session(api,self.db,session,self.root/'input.apk',self.root)
            self.assertEqual(len(api.missions),8)
            session=campaign.claim_session(self.db,MODELS)
            self.assertEqual(session['phase'],'barrier')
            self.assertEqual(self.db.execute("SELECT count(*) FROM classes WHERE state='done'").fetchone()[0],0)
            quality={'status':'complete','classes':[{'original_class':s['original_class'],'review':{'verdict':'acceptable'}} for s in json.loads(session['slots'])]}
            with patch.object(campaign,'run_barrier',return_value={'ok':True,'target':'t2'}),patch.object(campaign,'review_session',return_value=quality):
                campaign.run_session(api,self.db,session,self.root/'input.apk',self.root)
            self.assertEqual(len(api.missions),8)
            self.assertEqual(self.db.execute("SELECT count(*) FROM classes WHERE state='done'").fetchone()[0],8)
    def test_no_completion_claim_without_receipt(self):
        class API:
            def call(self,*a,**k):return []
        self.assertEqual(campaign.completion(API(),'m1','LA;')['status'],'needs_review')

    def test_optimistic_class_claim_and_quality_cannot_hide_missing_variable_coverage(self):
        api=SessionAPI();api.coverage_status='needs_review';session=campaign.claim_session(self.db,MODELS)
        quality={'status':'complete','classes':[{'original_class':s['original_class'],'review':{'verdict':'acceptable'}} for s in json.loads(session['slots'])]}
        def barrier(*a):
            before=json.loads((self.root/'rounds/cloud-000001/coverage-before.json').read_text())
            self.assertEqual(len(before['classes']),8)
            self.assertTrue(all(c['actor'].startswith('llm:agent-') for c in before['classes']))
            return {'ok':True,'target':'t2'}
        with patch.object(campaign,'ensure_target',return_value='t1'),patch.object(campaign,'run_barrier',side_effect=barrier),patch.object(campaign,'review_session',return_value=quality):
            campaign.run_session(api,self.db,session,self.root/'input.apk',self.root)
        self.assertEqual(self.db.execute("SELECT count(*) FROM classes WHERE state='needs_review'").fetchone()[0],8)
        self.assertTrue((self.root/'rounds/cloud-000001/coverage-after.json').exists())

    def test_terminal_label_does_not_acknowledge_inflight_work(self):
        self.assertFalse(campaign.settled({'state':'stopped','loopActive':True}))
        self.assertFalse(campaign.settled({'state':'stopped','cancellationUnconfirmed':True}))
        self.assertFalse(campaign.settled({'state':'running'}))
        self.assertTrue(campaign.settled({'state':'stopped','loopActive':False}))
        self.assertTrue(campaign.settled({'state':'done'}))

    def test_completed_invocation_never_starts_another_session_after_restart(self):
        config=self.root/'config.json';config.write_text(json.dumps({'root':str(self.root),'url':'http://127.0.0.1:7800','artifact':str(self.root/'input.apk'),'project':'fixture','models':MODELS}))
        (self.root/'invocation.json').write_text(json.dumps({'phase':'complete','start_completed':0,'rounds':1}))
        class API:
            def call(self,op,**params):
                if op!='project.open':raise AssertionError('Completed request dispatched '+op)
        with patch.object(sys,'argv',['campaign.py','run','--config',str(config),'--rounds','1']),patch.object(campaign,'ROOT',self.root.resolve()),patch.object(campaign,'sha256',return_value=campaign.APK_SHA),patch.object(campaign,'API',return_value=API()):
            campaign.main()
        self.assertEqual(self.db.execute('SELECT count(*) FROM sessions').fetchone()[0],0)

if __name__=='__main__':unittest.main()
