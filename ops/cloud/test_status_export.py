import json,tempfile,unittest
from pathlib import Path
import campaign,status_export

class Tests(unittest.TestCase):
    def test_eight_workers_count_native_locals_and_accepted_proposals_from_session_history(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory);artifact=root/'input.apk';sidecar=Path(str(artifact)+'.workbench');sidecar.mkdir()
            slots=[dict(mission='m'+str(i),coverage_actor='llm:agent-'+str(i),original_class='LC'+str(i)+';',current_name='C'+str(i),model='fixture') for i in range(1,9)]
            db=campaign.catalog_db(root)
            try:
                db.execute("INSERT INTO sessions(id,phase,slots,created) VALUES(1,'agents',?,0)",(json.dumps(slots),));db.commit()
            finally:db.close()
            old=dict(id='old',actor='llm:agent-1',op='rename',kind='local',originalSymbol='LC1;->a()V#local:1')
            folder=root/'rounds/cloud-000001';folder.mkdir(parents=True);(folder/'baseline-history.json').write_text(json.dumps([old]))
            edits=[old,dict(old,id='local',originalSymbol='LC1;->a()V#local:2'),
                dict(old,id='proposal',actor='agent:agent-2 auto-applied',kind='method',originalSymbol='LC2;->a()V'),
                dict(old,id='prefix',actor='llm:agent-10'),dict(old,id='undone',undone=True),dict(old,id='discarded',discarded=True)]
            (sidecar/'history.json').write_text(json.dumps(edits))
            class API:
                def call(self,op):
                    if op=='ops.active':return {'calls':[]}
                    if op=='agent.missions':return [dict(id='m'+str(i),agentId='agent-'+str(i),state='done',writes=0) for i in range(1,9)]
                    raise AssertionError(op)
            result=status_export.snapshot(dict(root=str(root),artifact=str(artifact)),API())
            workers=result['workers'];self.assertEqual(len(workers),8)
            self.assertEqual([w['writes'] for w in workers],[1,1,0,0,0,0,0,0])
            self.assertEqual(workers[0]['localWrites'],1);self.assertEqual(workers[0]['apiWrites'],0)
            self.assertEqual(workers[1]['localWrites'],0)
    def test_actor_normalization_keeps_exact_identity(self):
        for actor in ('llm:agent-1','agent:agent-1 auto-applied','agent:agent-1 accepted by human','agent:agent-1 auto-applied (follows class rename)'):
            self.assertEqual(status_export.agent_owner(actor),'agent-1')
        self.assertNotEqual(status_export.agent_owner('llm:agent-10'),'agent-1')
        self.assertIsNone(status_export.agent_owner('human:agent-1'))

if __name__=='__main__':unittest.main()
