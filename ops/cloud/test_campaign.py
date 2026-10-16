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

    def test_requested_repair_gets_one_followup_with_prior_review_and_eight_distinct_agents(self):
        self.db.execute("INSERT INTO classes(original,name) VALUES('LNew;','New')")
        prior={'quality_review':{'findings':[{'symbol':'LK;->a()V','reason':'bitwise OR, not replacement'}]}}
        self.db.execute("UPDATE classes SET state='needs_review',attempts=5,session=1,result=? WHERE original='LK;'",(json.dumps(prior),))
        self.db.execute("INSERT INTO sessions(id,phase,slots,created) VALUES(1,'complete','[]',0)");self.db.commit()
        repairs=[{'original_class':'LK;','after_session':1,'goal':'Check the OR operation and stale comment {target}.'}]
        first=campaign.claim_session(self.db,MODELS,repairs=repairs);slots=json.loads(first['slots'])
        self.assertEqual(slots[0]['original_class'],'LK;');self.assertEqual(slots[0]['prior_result'],prior)
        self.assertEqual(len({s['original_class'] for s in slots}),8);self.assertEqual([s['model'] for s in slots],MODELS)
        goal=campaign.prompt(slots[0],'t2');self.assertIn('bitwise OR, not replacement',goal)
        self.assertIn('stale comment {target}',goal);self.assertIn('Work against target t2.',goal)
        self.assertEqual(campaign.claim_session(self.db,MODELS,repairs=repairs),first)
        self.db.execute("UPDATE sessions SET phase='complete'")
        self.db.execute("UPDATE classes SET state='pending' WHERE state='claimed' AND original!='LK;'")
        self.db.execute("UPDATE classes SET state='needs_review' WHERE original='LK;'");self.db.commit()
        next_slots=json.loads(campaign.claim_session(self.db,MODELS,repairs=repairs)['slots'])
        self.assertNotIn('LK;',[s['original_class'] for s in next_slots])

    def test_repair_request_cannot_escape_focus_or_hide_duplicate_work(self):
        bad={'original_class':'LA;','after_session':0,'goal':'inspect caller'}
        with self.assertRaises(ValueError):campaign.claim_session(self.db,MODELS,repairs=[bad,bad])
        with self.assertRaises(ValueError):campaign.claim_session(self.db,MODELS,focus=[{'original_class':'LB;'}],repairs=[bad])
        self.assertEqual(self.db.execute('SELECT count(*) FROM sessions').fetchone()[0],0)
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

    def test_window_deadline_is_durable_and_never_restarts_on_process_recovery(self):
        with patch.object(campaign.time,'time',return_value=1000):
            first=campaign.invocation_state(self.root,self.db,hours=8)
        with patch.object(campaign.time,'time',return_value=20000):
            again=campaign.invocation_state(self.root,self.db,hours=8)
        self.assertEqual(first,again)
        self.assertEqual(first['deadline_at'],29800)

    def test_eight_hour_loop_claims_eight_each_time_and_finishes_session_past_deadline(self):
        now=[1000.0];seen=[]
        class API:
            def call(self,op,**params):self.assert_op=op
        def session(api,db,current,artifact,root):
            slots=json.loads(current['slots']);seen.append(current['id'])
            self.assertEqual(len(slots),8)
            self.assertEqual([s['model'] for s in slots],MODELS)
            self.assertEqual(len({s['original_class'] for s in slots}),8)
            self.assertEqual(db.execute("SELECT count(*) FROM sessions WHERE phase!='complete'").fetchone()[0],1)
            now[0]+=3*3600  # Session includes its save, reopen verification and review.
            with db:
                db.execute("UPDATE classes SET state='needs_review'")
                db.execute("UPDATE sessions SET phase='complete' WHERE id=?",(current['id'],))
        with patch.object(campaign.time,'time',side_effect=lambda:now[0]),patch.object(campaign,'run_session',side_effect=session):
            inv=campaign.invocation_state(self.root,self.db,hours=8)
            campaign.run_invocation(API(),self.db,{'project':'fixture','models':MODELS},self.root/'input.apk',self.root,inv)
        self.assertEqual(seen,[1,2,3])
        self.assertEqual(inv['phase'],'complete')
        self.assertEqual(inv['completed_at'],1000+9*3600)
        self.assertEqual(inv['deadline_at'],1000+8*3600)
        self.assertEqual(self.db.execute('SELECT count(*) FROM sessions').fetchone()[0],3)

    def test_recovery_after_deadline_finishes_pending_verification_before_pausing(self):
        first=campaign.claim_session(self.db,MODELS)
        with self.db:self.db.execute("UPDATE sessions SET phase='barrier'")
        inv={'phase':'running','mode':'window','start_completed':0,'deadline_at':1}
        self.assertIsNone(campaign.boundary_reason(self.root,self.db,inv,2))
        (self.root/'stop-after-session').touch()
        self.assertIsNone(campaign.boundary_reason(self.root,self.db,inv,2))
        calls=[]
        def session(api,db,current,*unused):
            calls.append((current['id'],current['phase']))
            if len(calls)==1:raise ConnectionError('verification temporarily unavailable')
            with db:db.execute("UPDATE sessions SET phase='complete' WHERE id=?",(current['id'],))
        class API:
            def call(self,*args,**kwargs):pass
        with patch.object(campaign,'run_session',side_effect=session),patch.object(campaign.time,'sleep'):
            campaign.run_invocation(API(),self.db,{'project':'fixture','models':MODELS},self.root/'input.apk',self.root,inv)
        self.assertEqual(calls,[(first['id'],'barrier')]*2)
        self.assertEqual(self.db.execute('SELECT count(*) FROM sessions').fetchone()[0],1)
        self.assertIn('Graceful stop',inv['reason'])

    def test_expired_window_never_claims_a_session(self):
        inv={'phase':'running','mode':'window','start_completed':0,'deadline_at':1}
        with patch.object(campaign,'claim_session',side_effect=AssertionError('new work after deadline')):
            campaign.run_invocation(None,self.db,{'project':'fixture','models':MODELS},self.root/'input.apk',self.root,inv)
        self.assertEqual(inv['phase'],'complete')

    def test_invalid_window_cannot_create_an_invocation(self):
        for hours in [0,-1,float('inf'),float('nan')]:
            with self.assertRaises(ValueError):campaign.invocation_state(self.root,self.db,hours=hours)
        self.assertFalse((self.root/'invocation.json').exists())

if __name__=='__main__':unittest.main()
