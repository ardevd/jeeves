import base64, hashlib, json, tempfile, unittest
from pathlib import Path
from unittest.mock import patch
import campaign, credit
from test_campaign import CatalogAPI, SessionAPI, MODELS

class MultiSessionAPI(SessionAPI):
    def call(self,op,**params):
        if op=='project.open':return {}
        result=super().call(op,**params)
        if op=='agent.missions' and len(self.missions)%8==0:
            for mission in self.missions:mission['state']='done'
        return result

class Tests(unittest.TestCase):
    def setUp(self):
        self.tmp=tempfile.TemporaryDirectory();self.root=Path(self.tmp.name)
        self.db=campaign.catalog_db(self.root);campaign.catalog(CatalogAPI(),'t',self.db)
        h=self.root/'input.apk.workbench';h.mkdir();(h/'history.json').write_text('[]')
        self.config={'models':MODELS,'project':'fixture','priority_classes':['La;','LA;']}
    def tearDown(self):self.db.close();self.tmp.cleanup()
    def test_two_complete_eight_agent_sessions_auto_advance_then_graceful_stop(self):
        api=MultiSessionAPI();events=[]
        def barrier(api,folder,artifact):
            sid=int(folder.name.split('-')[-1]);events.append(('save',sid,len(api.missions)))
            if sid==2:(self.root/'stop-after-session').touch()
            return {'ok':True,'target':'t2'}
        def review(api,target,slots,folder,artifact):
            sid=int(folder.name.split('-')[-1]);events.append(('review',sid,len(api.missions)))
            return {'status':'complete','classes':[{'original_class':s['original_class'],'review':{'verdict':'acceptable'}} for s in slots]}
        inv=campaign.invocation_state(self.root,self.db,continuous=True)
        self.assertNotIn('deadline_at',inv);self.assertNotIn('rounds',inv)
        with patch.object(campaign,'ensure_target',return_value='t'),patch.object(campaign,'run_barrier',side_effect=barrier),patch.object(campaign,'review_session',side_effect=review):
            campaign.run_invocation(api,self.db,self.config,self.root/'input.apk',self.root,inv)
        self.assertEqual(len(api.missions),16)
        self.assertEqual(events,[('save',1,8),('review',1,8),('save',2,16),('review',2,16)])
        sessions=list(self.db.execute('SELECT * FROM sessions ORDER BY id'))
        self.assertEqual([s['phase'] for s in sessions],['complete','complete'])
        for row in sessions:
            slots=json.loads(row['slots']);self.assertEqual(len({s['original_class'] for s in slots}),8)
            self.assertEqual([s['model'] for s in slots],MODELS)
        self.assertEqual([s['original_class'] for s in json.loads(sessions[0]['slots'])][:2],['La;','LA;'])
        self.assertNotEqual([s['original_class'] for s in json.loads(sessions[1]['slots'])][:2],['La;','LA;'])
        self.assertIn('Graceful stop',inv['reason'])
        self.assertEqual(campaign.invocation_state(self.root,self.db,continuous=True),inv)
    def test_credit_refusal_drains_eight_and_retries_save_then_never_admits_ninth(self):
        class CreditAPI(MultiSessionAPI):
            def call(self,op,**params):
                result=super().call(op,**params)
                if op=='agent.missions' and len(self.missions)==8:
                    self.missions[0].update(state='blocked',stopReason='provider failure: HTTP 402: Insufficient Balance')
                return result
        api=CreditAPI();saved=[];reviewed=[]
        def barrier(*args):
            saved.append(True)
            if len(saved)==1:raise ConnectionError('save needs recovery')
            return {'ok':True,'target':'t2'}
        def review(*args):reviewed.append(True);return {'status':'complete','classes':[]}
        inv=campaign.invocation_state(self.root,self.db,continuous=True)
        with patch.object(campaign,'ensure_target',return_value='t'),patch.object(campaign,'run_barrier',side_effect=barrier),patch.object(campaign,'review_session',side_effect=review),patch.object(campaign.time,'sleep'):
            campaign.run_invocation(api,self.db,self.config,self.root/'input.apk',self.root,inv)
        self.assertEqual(len(api.missions),8);self.assertEqual(len(saved),2);self.assertEqual(len(reviewed),1)
        self.assertIn('credit exhausted (deepseek)',inv['reason'])
        self.assertEqual(self.db.execute('SELECT phase FROM sessions').fetchone()[0],'complete')
        self.assertEqual(campaign.invocation_state(self.root,self.db,continuous=True),inv)
    def test_requested_completed_class_is_selected_once_with_prior_evidence(self):
        self.db.execute("UPDATE classes SET state='done',attempts=9,result=? WHERE original='La;'",(json.dumps({'evidence':'keep earlier research'}),));self.db.commit()
        session=campaign.claim_session(self.db,MODELS,priority=['La;'])
        slot=json.loads(session['slots'])[0]
        self.assertEqual(slot['original_class'],'La;');self.assertEqual(slot['prior_result']['evidence'],'keep earlier research')
        self.assertEqual(campaign.claim_session(self.db,MODELS,priority=['La;']),session)
    def test_completed_priority_is_not_duplicated_to_fill_last_page(self):
        self.db.execute("UPDATE classes SET state='done' WHERE original!='LA;'");self.db.commit()
        current=campaign.claim_session(self.db,MODELS,priority=['La;'])
        slots=json.loads(current['slots'])
        self.assertEqual(len(slots),8);self.assertEqual(len({s['original_class'] for s in slots}),8)
    def test_credit_marker_from_previous_authorization_does_not_block_new_run(self):
        self.db.execute("INSERT INTO sessions(id,phase,slots,created) VALUES(4,'complete','[]',0)");self.db.commit()
        credit.record_exhaustion(self.root,4,[{'provider':'deepseek','source':'m-old'}])
        inv=campaign.invocation_state(self.root,self.db,continuous=True)
        self.assertIsNone(campaign.boundary_reason(self.root,self.db,inv,10**12))
        credit.record_exhaustion(self.root,5,[{'provider':'zai','source':'m-new'}])
        self.assertIn('credit exhausted (zai)',campaign.boundary_reason(self.root,self.db,inv,10**12))
    def test_provider_codes_do_not_confuse_rate_or_context_errors(self):
        self.assertTrue(credit.exhausted('deepseek',402))
        self.assertTrue(credit.exhausted('zai',429,'{"error": {"code": "1113"}}'))
        for provider,status,body in [('deepseek',429,'Rate limit'),('deepseek',400,'maximum context length'),('zai',429,'{"error":{"code":"1302"}}'),('zai',429,'{"error":{"code":"1305"}}'),('zai',400,'{"error":{"code":"1261"}}')]:
            self.assertFalse(credit.exhausted(provider,status,body))
    def test_full_error_is_read_when_first_line_has_no_business_code(self):
        class API:
            def call(self,op,**params):
                if op=='agent.turns':return [{'turn':1},{'turn':3},{'turn':2}]
                if params['turn']!=3:raise AssertionError('Did not inspect last failure')
                return {'error':'provider HTTP 429:\n{\n"error":{"code":"1113"}}','failure':{'status':429},'reasoning':'private, not in marker'}
        r=credit.mission_exhaustion(API(),{'id':'m1','state':'blocked','stopReason':'provider HTTP 429:'},'zai:glm-5.3')
        self.assertEqual(r['provider'],'zai');self.assertNotIn('private',json.dumps(r))
    def test_review_credit_rejection_requires_matching_request_and_exact_bytes(self):
        p=self.root/'quality/class-01';p.mkdir(parents=True);(p/'evidence.json').write_text('{}')
        digest=hashlib.sha256(b'{}').hexdigest();body=b'{"error":{"code":"1113"}}'
        request={'provider':'zai','evidence_sha256':digest};(p/'request.json').write_text(json.dumps(request))
        r=dict(request,status=429,body_bytes=len(body),body_sha256=hashlib.sha256(body).hexdigest(),body_base64=base64.b64encode(body).decode())
        (p/'provider-error.json').write_text(json.dumps(r))
        failures=credit.review_exhaustion(self.root/'quality');self.assertEqual(len(failures),1)
        inv=campaign.invocation_state(self.root,self.db,continuous=True)
        credit.record_exhaustion(self.root,1,failures)
        self.assertIn('credit exhausted',campaign.boundary_reason(self.root,self.db,inv,0))
        r['body_bytes']+=1;(p/'provider-error.json').write_text(json.dumps(r))
        with self.assertRaisesRegex(ValueError,'checksum'):credit.review_exhaustion(self.root/'quality')
    def test_priorities_cannot_escape_focus_or_duplicate_owner(self):
        for priority,focus in [(['missing'],None),(['LA;','LA;'],None),(['LA;'],[{'original_class':'LB;'}])]:
            with self.assertRaises(ValueError):campaign.claim_session(self.db,MODELS,focus=focus,priority=priority)
        self.assertEqual(self.db.execute('SELECT count(*) FROM sessions').fetchone()[0],0)
    def test_continuous_invocation_is_durable_not_a_new_clock_on_restart(self):
        with patch.object(campaign.time,'time',return_value=1):first=campaign.invocation_state(self.root,self.db,continuous=True)
        with patch.object(campaign.time,'time',return_value=10**12):again=campaign.invocation_state(self.root,self.db,continuous=True)
        self.assertEqual(first,again);self.assertIsNone(campaign.boundary_reason(self.root,self.db,again,10**12))

if __name__=='__main__':unittest.main()
