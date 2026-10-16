import json, tempfile, unittest
from pathlib import Path
from unittest.mock import patch
import campaign, focus
from checkpoint_backup import sha256

MODELS=['deepseek:deepseek-v4-pro']*4+['zai:glm-5.3']*4

class API:
    def __init__(self):self.calls=[]
    def call(self,op,**p):
        self.calls.append((op,p))
        if op=='symbol.search':return [dict(addr='LSeed;',name='FMessage'),dict(addr='LText;',name='FMessageText'),dict(addr='LFalse;',name='WireFutureProofMessageProto')]
        if op=='symbol.resolve':
            s=p['symbol'].replace('LCurrentUse;','LUse0;')
            return {'origSig':s,'name':{'LSeed;':'FMessage','LText;':'FMessageText'}.get(s,s)}
        if op=='code.outline':return {'fields':[{'origSig':p['class']+'->A00:I'}],'methods':[{'origSig':p['class']+'->A01()V'}]}
        if op=='xref.to':
            if p['symbol']=='LSeed;':
                return [{'from':('LCurrentUse;' if i==0 else f'LUse{i};')+'->A01()V+0x2','type':'INVOKE'} for i in range(6)]
            if p['symbol']=='LText;->A00:I':return [{'from':'LCurrentUse;->A01()V+0x4','type':'GET'}]
            return []
        raise AssertionError('Unexpected or mutating call: '+op)

class Tests(unittest.TestCase):
    def setUp(self):
        self.tmp=tempfile.TemporaryDirectory();self.root=Path(self.tmp.name)
        self.artifact=self.root/'input.apk';self.artifact.write_text('fixture apk')
        h=Path(str(self.artifact)+'.workbench');h.mkdir();(h/'history.json').write_text('[]')
        self.db=campaign.catalog_db(self.root)
        names=['LSeed;','LText;','LFalse;','L000;']+[f'LUse{i};' for i in range(6)]
        with self.db:self.db.executemany('INSERT INTO classes(original,name) VALUES(?,?)',[(s,s) for s in names])
        self.api=API();self.folder=self.root/'focus'
    def tearDown(self):self.db.close();self.tmp.cleanup()
    def build(self):
        with patch.object(focus,'APK_SHA',sha256(self.artifact)):
            return focus.build(self.api,'t1',self.db,self.folder,self.artifact,['LSeed;','LText;'])
    def test_complete_xref_queue_uses_original_ids_and_reuses_read_evidence(self):
        result=self.build();self.assertEqual(len(result['classes']),8);self.assertEqual(result['reference_sites'],7)
        self.assertEqual(result['seed_classes'],['LSeed;','LText;'])
        self.assertNotIn('LFalse;',[r['original_class'] for r in result['classes']])
        use=next(r for r in result['classes'] if r['original_class']=='LUse0;')
        self.assertEqual(use['connections'][0]['site'],'LUse0;->A01()V+0x2')
        self.assertEqual(len(focus.context(use)['entry_symbols']),1)
        calls=len(self.api.calls);self.build();self.assertEqual(len(self.api.calls),calls)
        queried={p['symbol'] for op,p in self.api.calls if op=='xref.to'}
        self.assertIn('LText;->A00:I',queried);self.assertIn('LSeed;->A01()V',queried)
    def test_eight_assignments_exclude_unrelated_classes_and_keep_context(self):
        self.build();rows=focus.load(self.folder/'manifest.json',self.db,sha256(self.artifact))
        session=campaign.claim_session(self.db,MODELS,rows);slots=json.loads(session['slots'])
        self.assertEqual(len(slots),8);self.assertEqual(slots[0]['original_class'],'LSeed;')
        self.assertNotIn('L000;',[s['original_class'] for s in slots])
        self.assertTrue(all('research_focus' in s for s in slots))
        text=campaign.prompt(slots[2],'t1')
        self.assertIn('LUse0;->A01()V',text);self.assertIn('FMessage assignment context:',text)
        self.assertNotIn('{research_focus}',text)
        self.assertEqual(campaign.claim_session(self.db,MODELS,[]),session)
    def test_focused_tail_fills_only_from_completed_focus_classes(self):
        result=self.build()
        with self.db:self.db.execute("UPDATE classes SET state='done' WHERE original!='LSeed;'")
        slots=json.loads(campaign.claim_session(self.db,MODELS,result['classes'])['slots'])
        self.assertEqual({s['original_class'] for s in slots},{r['original_class'] for r in result['classes']})
    def test_invalid_scope_never_falls_back_to_generic_catalog(self):
        result=self.build()
        with self.assertRaisesRegex(RuntimeError,'different executable'):focus.load(self.folder/'manifest.json',self.db,'wrong apk')
        result['classes']=result['classes'][:7];(self.folder/'manifest.json').write_text(json.dumps(result))
        with self.assertRaisesRegex(RuntimeError,'eight distinct'):focus.load(self.folder/'manifest.json',self.db,sha256(self.artifact))
        self.assertIsNone(campaign.claim_session(self.db,MODELS,[]))
        self.assertEqual(self.db.execute('SELECT count(*) FROM sessions').fetchone()[0],0)
    def test_truncated_references_are_not_accepted(self):
        with self.assertRaisesRegex(RuntimeError,'truncated'):focus.all_rows({'rows':[],'truncated':True,'total':9})
        with self.assertRaisesRegex(RuntimeError,'Incomplete'):focus.all_rows({'rows':[],'total':9})
    def test_unknown_reference_owner_does_not_disappear_from_evidence(self):
        with self.db:self.db.execute("DELETE FROM classes WHERE original='LUse0;'")
        with self.assertRaisesRegex(RuntimeError,'absent from the full class catalog'):self.build()

if __name__=='__main__':unittest.main()
