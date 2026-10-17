import json, tempfile, unittest
from pathlib import Path
from unittest.mock import patch
import campaign, review_debt, quality
from test_campaign import MODELS
from test_continuous import MultiSessionAPI

FINDING={'symbol':'LA;->a()V','severity':'incorrect','name':'replaceFlags','reason':'The body ORs new flags into existing flags.'}

class Tests(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory();self.root=Path(self.temp.name)
        self.db=campaign.catalog_db(self.root)
        h=self.root/'input.apk.workbench';h.mkdir();(h/'history.json').write_text('[]')
    def tearDown(self):self.db.close();self.temp.cleanup()
    def add(self,count,state,offset=0,session=1):
        with self.db:
            for n in range(offset,count+offset):
                original=f'LC{n:03d};'
                result={'status':'needs_review','quality_review':dict(status='complete',findings=[dict(FINDING,symbol=original+'->a()V')])} if state=='needs_review' else {}
                self.db.execute('INSERT INTO classes(original,name,state,attempts,session,result) VALUES(?,?,?,?,?,?)',
                                (original,original,state,1 if state!='pending' else 0,session if state!='pending' else None,json.dumps(result)))
    def settle(self,session,state):
        with self.db:
            self.db.execute("UPDATE sessions SET phase='complete' WHERE id=?",(session['id'],))
            self.db.execute("UPDATE classes SET state=? WHERE state='claimed'",(state,))
    def test_all_75_debts_rotate_before_retries_and_untouched_classes_stay_held(self):
        self.add(75,'needs_review');self.add(8,'done',75);self.add(100,'pending',83)
        with self.db:self.db.execute("INSERT INTO sessions(id,phase,slots,created) VALUES(1,'complete','[]',0)")
        focus=[{'original_class':f'LC{n:03d};'} for n in range(83,183)]
        seen=[]
        for _ in range(10):
            session=campaign.claim_session(self.db,MODELS,focus=focus,priority=['LC083;'],review_debt_first=True)
            slots=json.loads(session['slots']);self.assertEqual(len(slots),8)
            self.assertEqual([s['model'] for s in slots],MODELS)
            self.assertTrue(all(s['assignment_kind']=='repair' for s in slots))
            self.assertEqual(campaign.claim_session(self.db,MODELS,focus=focus,review_debt_first=True),session)
            seen.extend(s['original_class'] for s in slots)
            self.settle(session,'needs_review')
        self.assertEqual(len(set(seen[:75])),75)
        self.assertEqual(self.db.execute("SELECT count(*) FROM classes WHERE state='pending'").fetchone()[0],100)
    def test_last_three_repairs_use_five_done_classes_never_new_ones(self):
        self.add(3,'needs_review');self.add(8,'done',3);self.add(8,'pending',11)
        session=campaign.claim_session(self.db,MODELS,review_debt_first=True)
        slots=json.loads(session['slots'])
        self.assertEqual([s['assignment_kind'] for s in slots],['repair']*3+['verification']*5)
        self.assertEqual(len({s['original_class'] for s in slots}),8)
        self.assertEqual(self.db.execute("SELECT count(*) FROM classes WHERE state='pending'").fetchone()[0],8)
        self.settle(session,'done')
        next_session=campaign.claim_session(self.db,MODELS,review_debt_first=True)
        self.assertTrue(all(s['assignment_kind']=='discovery' for s in json.loads(next_session['slots'])))
    def test_not_enough_existing_owners_cannot_fill_repair_batch_with_pending(self):
        self.add(2,'needs_review');self.add(30,'pending',2)
        with self.assertRaises(RuntimeError):campaign.claim_session(self.db,MODELS,review_debt_first=True)
        self.assertEqual(self.db.execute('SELECT count(*) FROM sessions').fetchone()[0],0)
    def test_failed_review_does_not_erase_last_complete_review_on_seed(self):
        self.add(1,'needs_review')
        with self.db:
            self.db.execute("UPDATE classes SET result=?,session=2",(json.dumps({'quality_review':{'status':'needs_manual_review','findings':[]},'unresolved':['provider failure']}),))
            for sid in (1,2):self.db.execute("INSERT INTO sessions(id,phase,slots,created) VALUES(?,'complete','[]',0)",(sid,))
        for sid,status,findings in [(1,'complete',[FINDING]),(2,'needs_manual_review',[])]:
            folder=self.root/'rounds'/f'cloud-{sid:06d}'/'quality';folder.mkdir(parents=True)
            (folder/'receipt.json').write_text(json.dumps({'classes':[{'original_class':'LC000;','review':{'status':status,'findings':findings}}]}))
        review_debt.seed_backlog(self.db,self.root)
        result=json.loads(self.db.execute('SELECT result FROM classes').fetchone()[0])
        self.assertEqual(result['review_debt_source_session'],1);self.assertEqual(result['review_debt'][0]['reason'],FINDING['reason'])
        self.assertEqual(result['unresolved'],['provider failure'])
        review_debt.seed_backlog(self.db,self.root)
        self.assertEqual(json.loads(self.db.execute('SELECT result FROM classes').fetchone()[0]),result)
    def test_every_prior_finding_needs_explicit_independent_assessment(self):
        findings=review_debt.identified([FINDING]);evidence={'repair_context':{'findings':findings}}
        for assessments in ([],[{'id':'wrong','status':'resolved','evidence':'code'}],
                            [{'id':findings[0]['id'],'status':'resolved','evidence':''}]):
            with self.assertRaises(ValueError):review_debt.validate_assessments({'verdict':'acceptable','repair_assessments':assessments},evidence)
        item={'id':findings[0]['id'],'status':'unresolved','evidence':'Caller contract absent.'}
        self.assertEqual(review_debt.validate_assessments({'verdict':'acceptable','repair_assessments':[item]},evidence)['verdict'],'needs_review')
        item['status']='resolved';item['evidence']='Current addFlags name matches bitwise OR in a().'
        r={'verdict':'acceptable','repair_assessments':[item]}
        self.assertEqual(review_debt.validate_assessments(r,evidence)['verdict'],'acceptable')
        self.assertEqual(review_debt.remaining_findings({'repair_request':{'findings':findings}},dict(r,status='complete')),[])
    def test_only_successful_assessments_clear_findings_and_new_findings_persist(self):
        findings=review_debt.identified([FINDING]);slot={'repair_request':{'findings':findings}}
        old={'id':findings[0]['id'],'status':'resolved','evidence':'Changed to addFlags.'}
        self.assertEqual(review_debt.remaining_findings(slot,{'status':'needs_manual_review','repair_assessments':[old]}),findings)
        self.assertEqual(review_debt.remaining_findings(slot,{'status':'complete','findings':[FINDING]}),findings)
        new=dict(FINDING,symbol='LA;->b()V',reason='Remaining argument lacks caller evidence.')
        result=review_debt.remaining_findings(slot,{'status':'complete','repair_assessments':[old],'findings':[new]})
        self.assertEqual(len(result),1);self.assertEqual(result[0]['symbol'],new['symbol'])
    def test_inflight_repairs_remain_in_dashboard_debt_count(self):
        self.add(9,'needs_review')
        session=campaign.claim_session(self.db,MODELS,review_debt_first=True)
        status=review_debt.progress({'needs_review':1,'claimed':8},[session],True)
        self.assertEqual(status,dict(enabled=True,waiting=1,in_flight=8,outstanding=9,discovery_held=True))
        self.assertFalse(review_debt.progress({'done':9},[],True)['discovery_held'])
    def test_real_controller_sequence_repairs_then_save_review_then_new_work(self):
        self.add(8,'needs_review');self.add(8,'pending',8)
        api=MultiSessionAPI();events=[]
        def barrier(api,folder,artifact):
            sid=int(folder.name.split('-')[-1]);events.append(('save',sid,len(api.missions)))
            if sid==2:(self.root/'stop-after-session').touch()
            return {'ok':True,'target':'t2'}
        def review(api,target,slots,folder,artifact):
            sid=int(folder.name.split('-')[-1]);events.append(('review',sid,len(api.missions)))
            if sid==1:self.assertTrue(all(s['assignment_kind']=='repair' for s in slots))
            else:self.assertTrue(all(s['assignment_kind']=='discovery' for s in slots))
            return {'status':'complete','classes':[{'original_class':s['original_class'],'review':{'status':'complete','verdict':'acceptable','findings':[],
                'repair_assessments':[dict(id=f['id'],status='resolved',evidence='Current body and caller verify the corrected name.') for f in s.get('repair_request',{}).get('findings',[])]}} for s in slots]}
        inv=campaign.invocation_state(self.root,self.db,continuous=True)
        config={'models':MODELS,'project':'fixture','review_debt_first':True}
        with patch.object(campaign,'ensure_target',return_value='t'),patch.object(campaign,'run_barrier',side_effect=barrier),patch.object(campaign,'review_session',side_effect=review):
            campaign.run_invocation(api,self.db,config,self.root/'input.apk',self.root,inv)
        self.assertEqual(events,[('save',1,8),('review',1,8),('save',2,16),('review',2,16)])
        self.assertEqual(self.db.execute("SELECT count(*) FROM classes WHERE state='done'").fetchone()[0],16)
    def test_completed_provider_response_cannot_skip_old_findings(self):
        findings=review_debt.identified([FINDING]);evidence={'repair_context':{'findings':findings}}
        folder=self.root/'provider';folder.mkdir()
        (folder/'request.json').write_text('{}')
        response={'verdict':'acceptable','summary':'Looks fine','findings':[],'examples':[]}
        stream='data: '+json.dumps({'choices':[{'delta':{'content':json.dumps(response)}}]})+'\n\ndata: [DONE]\n'
        (folder/'response.sse').write_text(stream)
        with patch.object(quality.urllib.request,'build_opener',side_effect=AssertionError('must reuse saved response')):
            result=quality.provider_review(folder,evidence,'zai')
            self.assertEqual(quality.provider_review(folder,evidence,'zai'),result)
        self.assertEqual(result['status'],'needs_manual_review')
        self.assertIn('omitted prior repair findings',result['error'])
        self.assertEqual(review_debt.remaining_findings({'repair_request':{'findings':findings}},result),findings)
    def test_prompt_carries_original_finding_and_unresolved_variable_evidence(self):
        self.add(8,'needs_review')
        session=campaign.claim_session(self.db,MODELS,review_debt_first=True)
        slot=json.loads(session['slots'])[0];prompt=campaign.prompt(slot,'t9')
        self.assertIn(FINDING['reason'],prompt);self.assertIn(slot['repair_request']['findings'][0]['id'],prompt)
        self.assertIn('review_resolutions',prompt);self.assertIn('No tools or mutations',quality.REVIEW_PROMPT)

if __name__=='__main__':unittest.main()
