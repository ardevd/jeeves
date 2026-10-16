import tempfile,unittest,json
from pathlib import Path
from unittest.mock import patch
import naming_coverage,barrier

class Tests(unittest.TestCase):
    def test_exact_methods_actor_isolation_and_collection_errors(self):
        slots=[{'original_class':'LFMessage;','coverage_actor':'llm:new-agent','current_name':'FMessage'}]
        class API:
            def call(self,op,**p):
                if op=='code.outline':return {'symbol':{'origSig':'LFMessage;'},'methods':[{'origSig':'LFMessage;->a()V'},{'origSig':'LFMessage;->b()V'}]}
                self_outer.assertEqual(p['recordedBy'],'llm:new-agent')
                if p['symbol'].endswith('b()V'):raise RuntimeError('decompiler failure')
                return {'originalMethod':p['symbol'],'recordedBy':'llm:old-kimi','status':'complete','counts':{'total':2,'retained':2},'inventoryFingerprint':'x','inventoryErrors':[]}
        self_outer=self
        with tempfile.TemporaryDirectory() as root:
            result=naming_coverage.audit_session(API(),'t1',slots,Path(root),'before')
            self.assertEqual(result['status'],'needs_review')
            self.assertEqual(result['classes'][0]['methods_complete'],0)
            self.assertEqual(result['classes'][0]['methods_total'],2)
            self.assertEqual(len(result['classes'][0]['errors']),1)
            self.assertEqual(naming_coverage.native_expectations(result),[{'original_method':'LFMessage;->a()V','inventory_fingerprint':'x'}])
            self.assertNotIn('methods',naming_coverage.summary(result)['classes'][0])
    def test_missing_actor_does_not_accept_other_session_receipts(self):
        class API:
            def call(self,op,**p):return {'symbol':{'origSig':'LA;'},'methods':[]}
        with tempfile.TemporaryDirectory() as root:
            result=naming_coverage.audit_session(API(),'t1',[{'original_class':'LA;'}],Path(root),'after')
            self.assertEqual(result['status'],'needs_review')
    def test_unavailable_inventory_is_never_claimed_as_native_verified(self):
        result={'classes':[{'methods':[{'originalMethod':'LA;->m()V','inventoryFingerprint':'bad','inventoryErrors':['cannot collect']}]}]}
        self.assertEqual(naming_coverage.native_expectations(result),[])
    def test_complete_inventory_change_fails_live_reopen_verification(self):
        class API:
            def call(self,op,**p):
                if op=='symbol.resolve':return {'origSig':'LA;','name':'FMessage'}
                if op=='naming.coverage':return {'inventoryFingerprint':'different','inventoryErrors':[]}
                raise AssertionError(op)
        result=barrier.verify_live(API(),'t1',{'symbols':[{'original_symbol':'LA;','name':'FMessage'}],
                    'coverage_methods':[{'original_method':'LA;->m()V','inventory_fingerprint':'before'}]})
        self.assertFalse(result['ok']);self.assertEqual(result['checked'],2)

if __name__=='__main__':unittest.main()
