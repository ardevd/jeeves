"""Configured sessions: persisted eight-slot recovery and sixteen-slot admission."""
import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch
import campaign
import deploy_after_session
import focus
import status_export
import test_deploy_after_session
from test_campaign import SessionAPI, MODELS

SIXTEEN = ['deepseek:deepseek-v4-pro'] * 8 + ['zai:glm-5.3'] * 8

class Tests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.root = Path(self.tmp.name)
        self.db = campaign.catalog_db(self.root)
        with self.db:
            self.db.executemany('INSERT INTO classes(original,name) VALUES(?,?)',
                               [(f'LC{i:02d};',f'C{i}') for i in range(40)])
        self.artifact = self.root / 'input.apk'
        side = Path(str(self.artifact)+'.workbench'); side.mkdir()
        (side/'history.json').write_text('[]')
        self.focus = [dict(original_class=f'LC{i:02d};',current_name=f'C{i}',role='FMessage reference site',connections=[]) for i in range(32)]

    def tearDown(self):
        self.db.close(); self.tmp.cleanup()

    def assert_slots(self, session, count=16):
        slots = json.loads(session['slots'])
        self.assertEqual(len(slots), count)
        self.assertEqual(len({s['original_class'] for s in slots}), count)
        self.assertEqual([s['model'] for s in slots], SIXTEEN if count==16 else MODELS)
        return slots

    def test_focus_priority_and_persisted_eight_to_sixteen_transition(self):
        first = campaign.claim_session(self.db, MODELS, self.focus)
        self.assertEqual(campaign.claim_session(self.db, SIXTEEN, []), first)
        self.assert_slots(first, 8)
        with self.db:
            self.db.execute("UPDATE sessions SET phase='complete'")
            self.db.execute("UPDATE classes SET state='done' WHERE state='claimed'")
        next_session = campaign.claim_session(self.db, SIXTEEN, self.focus, priority=['LC31;','LC30;'])
        slots = self.assert_slots(next_session)
        self.assertEqual([s['original_class'] for s in slots[:2]], ['LC31;','LC30;'])
        self.assertTrue(all('research_focus' in s for s in slots))
        self.assertEqual(campaign.claim_session(self.db, SIXTEEN), next_session)

    def test_repair_tail_never_admits_untouched_classes(self):
        with self.db:
            self.db.execute("UPDATE classes SET state='done' WHERE original<'LC16;'")
            self.db.execute("UPDATE classes SET state='needs_review' WHERE original IN ('LC00;','LC01;')")
        slots = self.assert_slots(campaign.claim_session(self.db, SIXTEEN, review_debt_first=True))
        self.assertEqual(sum(s['assignment_kind']=='repair' for s in slots), 2)
        self.assertEqual(sum(s['assignment_kind']=='verification' for s in slots), 14)
        self.assertTrue(all(s['original_class']<'LC16;' for s in slots))
        self.assertEqual(self.db.execute("SELECT count(*) FROM classes WHERE state='pending'").fetchone()[0], 24)

    def test_completed_focus_repeats_sixteen_and_small_scope_rejects(self):
        with self.db:self.db.execute("UPDATE classes SET state='done'")
        self.assert_slots(campaign.claim_session(self.db,SIXTEEN,self.focus,revisit_completed=True))
        path=self.root/'focus.json'
        path.write_text(json.dumps(dict(schema='fmessage-focus-v1',apk_sha256='fixture',classes=self.focus[:8])))
        self.assertEqual(len(focus.load(path,self.db,'fixture',required_slots=8)),8)
        with self.assertRaisesRegex(RuntimeError,'16 distinct'):
            focus.load(path,self.db,'fixture',required_slots=16)

    def test_resume_eight_does_not_require_new_sixteen_focus(self):
        first=campaign.claim_session(self.db,MODELS)
        seen=[]
        def finish(api,db,session,*args):
            seen.append(self.assert_slots(session,8))
            with db:db.execute("UPDATE sessions SET phase='complete'")
        class API:
            def call(self,*args,**kwargs):return {}
        inv=campaign.invocation_state(self.root,self.db,rounds=1)
        with patch.object(campaign,'run_session',side_effect=finish),patch.object(focus,'load',side_effect=AssertionError('must use persisted slots')):
            campaign.run_invocation(API(),self.db,dict(project='fixture',models=SIXTEEN,focus_manifest='absent'),self.artifact,self.root,inv)
        self.assertEqual(len(seen),1)
        self.assertEqual(json.loads(first['slots']),seen[0])

    def test_sixteen_lost_ack_save_recovery_and_complete_review_order(self):
        api=SessionAPI(count=16);api.lost=True
        session=campaign.claim_session(self.db,SIXTEEN)
        with patch.object(campaign,'ensure_target',return_value='t1'):
            with self.assertRaises(ConnectionResetError):campaign.run_session(api,self.db,session,self.artifact,self.root)
            session=campaign.claim_session(self.db,MODELS)
            with patch.object(campaign,'run_barrier',side_effect=RuntimeError('save unavailable')):
                with self.assertRaisesRegex(RuntimeError,'save unavailable'):
                    campaign.run_session(api,self.db,session,self.artifact,self.root)
            self.assertEqual(len(api.missions),16)
            session=campaign.claim_session(self.db,MODELS);self.assertEqual(session['phase'],'barrier')
            slots=self.assert_slots(session)
            def barrier(*args):
                self.assertTrue(all(campaign.settled(m) for m in api.missions))
                coverage=json.loads((self.root/'rounds/cloud-000001/coverage-before.json').read_text())
                self.assertEqual(len(coverage['classes']),16)
                return {'ok':True,'target':'t2'}
            def reviews(*args):
                self.assertTrue((self.root/'rounds/cloud-000001/coverage-after.json').exists())
                self.assertEqual(self.db.execute('SELECT phase FROM sessions').fetchone()[0],'quality')
                return {'status':'complete','classes':[dict(original_class=s['original_class'],review={'verdict':'acceptable'}) for s in slots]}
            with patch.object(campaign,'run_barrier',side_effect=barrier),patch.object(campaign,'review_session',side_effect=reviews):
                campaign.run_session(api,self.db,session,self.artifact,self.root)
        self.assertEqual(api.calls.count('agent.spawn'),16)
        self.assertEqual(self.db.execute("SELECT count(*) FROM classes WHERE state='done'").fetchone()[0],16)

    def test_credit_stop_drains_sixteen_without_admitting_another_session(self):
        inv=campaign.invocation_state(self.root,self.db,continuous=True);seen=[]
        def finish(api,db,session,*args):
            seen.append(self.assert_slots(session))
            (self.root/'credit-stop.json').write_text(json.dumps(dict(session=session['id'],failures=[{'provider':'zai'}])))
            self.assertIsNone(campaign.boundary_reason(self.root,db,inv,0))
            with db:db.execute("UPDATE sessions SET phase='complete'")
        class API:
            def call(self,*args,**kwargs):return {}
        with patch.object(campaign,'run_session',side_effect=finish):
            campaign.run_invocation(API(),self.db,dict(project='fixture',models=SIXTEEN),self.artifact,self.root,inv)
        self.assertEqual(len(seen),1);self.assertIn('credit exhausted',inv['reason'])

    def test_deployment_requires_exact_sixteen_review_identities(self):
        fixture=test_deploy_after_session.Tests('test_boundary_requires_drained_agents_saved_verified_reviewed_session')
        fixture.setUp()
        try:
            slots=[{'original_class':f'LC{i};'} for i in range(16)]
            import sqlite3
            with sqlite3.connect(fixture.root/'catalog.sqlite3') as db:
                db.execute('UPDATE sessions SET slots=?',(json.dumps(slots),))
            for reviews,allowed in [(slots[:8],False),(slots[:-1]+slots[:1],False),
                                    (slots[:-1]+[{'original_class':'LWrong;'}],False),(list(reversed(slots)),True)]:
                fixture.write(fixture.folder/'quality/receipt.json',dict(fixture.quality,classes=reviews))
                self.assertEqual(deploy_after_session.boundary(fixture.root,1,fixture.api) is not None,allowed)
        finally:fixture.tearDown()

    def test_dashboard_preserves_historical_eight_and_current_sixteen(self):
        first=campaign.claim_session(self.db,MODELS)
        with self.db:
            self.db.execute("UPDATE sessions SET phase='complete'")
            self.db.execute("UPDATE classes SET state='done' WHERE state='claimed'")
        second=campaign.claim_session(self.db,SIXTEEN)
        for sid in (first['id'],second['id']):
            directory=self.root/'rounds'/f'cloud-{sid:06d}'/'quality';directory.mkdir(parents=True)
            (directory/'receipt.json').write_text(json.dumps(dict(status='complete',classes=[dict(original_class='LExample;')])))
        class API:
            def call(self,op):return {'calls':[]} if op=='ops.active' else []
        snapshot=status_export.snapshot(dict(root=str(self.root),artifact=str(self.artifact),models=SIXTEEN),API())
        self.assertEqual([s['agent_count'] for s in snapshot['sessions']],[8,16])
        self.assertEqual([s['agent_count'] for s in snapshot['quality_history']],[8,16])
        self.assertEqual(len(snapshot['workers']),16);self.assertEqual(snapshot['configured_agents'],16)

if __name__=='__main__':unittest.main()
