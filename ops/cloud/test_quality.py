import json, tempfile, unittest, threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from unittest.mock import patch
import quality

class Tests(unittest.TestCase):
    def test_stream_preserves_usage_and_requires_terminal_marker(self):
        lines=['data: '+json.dumps({'choices':[{'delta':{'content':'{"verdict":"acceptable"}','reasoning_content':'evidence'}}], 'usage':{'completion_tokens':7}}),'data: [DONE]']
        result=quality.decode_stream(lines)
        self.assertEqual(result['usage']['completion_tokens'],7)
        self.assertEqual(result['reasoning'],'evidence')
        with self.assertRaisesRegex(RuntimeError,'completion marker'):quality.decode_stream(lines[:1])
    def test_findings_override_optimistic_verdict(self):
        result=quality.validate_review(json.dumps({'verdict':'acceptable','summary':'test','findings':[{'symbol':'LA;','severity':'incorrect','name':'foo','reason':'contradicts code'}],'examples':[]}))
        self.assertEqual(result['verdict'],'needs_review')
    def test_lost_request_is_not_blindly_recharged(self):
        with tempfile.TemporaryDirectory() as root:
            folder=Path(root);(folder/'request.json').write_text('{}')
            with patch.object(quality.urllib.request,'build_opener',side_effect=AssertionError('must not call provider')):
                result=quality.provider_review(folder,{},'zai')
            self.assertEqual(result['status'],'needs_manual_review')
    def test_incomplete_stream_is_retained_without_replay(self):
        with tempfile.TemporaryDirectory() as root:
            folder=Path(root);(folder/'request.json').write_text('{}');(folder/'response.sse').write_text('data: {"choices":[]}\n')
            result=quality.provider_review(folder,{},'deepseek')
            self.assertEqual(result['verdict'],'needs_review')
            self.assertTrue((folder/'response.sse').exists())
    def test_obfuscation_heuristics_are_narrow(self):
        self.assertTrue(quality.generic('arg12','local'))
        self.assertFalse(quality.generic('this','local'))
        self.assertFalse(quality.generic('message','local'))
        self.assertFalse(quality.generic('<init>','method'))

    def test_propagated_family_change_is_reviewed_on_affected_class(self):
        row={'originalSymbol':'LA;->a()I','renameTargets':[{'originalSymbol':'LB;->a()I'}]}
        self.assertTrue(quality.affects_class(row,'LB;'))
        self.assertFalse(quality.affects_class(row,'LC;'))

    def test_review_reuses_callers_and_prior_names_without_model_reasoning(self):
        class API:
            def call(self,op,**args):
                if op=='agent.turns':return [{'turn':1},{'turn':2}]
                self_test.assertEqual(op,'agent.turn')
                return {'reasoning':'private model reasoning must not enter the evidence', 'calls':[
                    {'tool':'code','ok':True,'args':json.dumps({'symbol':'LB;->a()J','view':'java'}),
                     'result':json.dumps({'symbol':{'origSig':'LB;->a()J'},'text':'(TEXT block)'}),
                     'resultText':'return 1000L * timestamp;' if args['turn']==2 else 'earlier name'},
                    {'tool':'code','ok':True,'args':json.dumps({'symbol':'LCurrent;','view':'java'}),'result':'{"text":"duplicate class"}'},
                    {'tool':'annotate','ok':True,'args':'{}','result':'mutation receipt'}]}
        self_test=self
        prior={'id':'old','originalSymbol':'LA;->a:J','newName':'timestamp','actor':'llm:previous'}
        evidence=quality.research_evidence(API(),{'mission':'m1','original_class':'LA;'},
            {'outline':{'symbol':{'addr':'LCurrent;'}}},[prior,dict(prior,id='new'),dict(prior,id='undone',undone=True)],{'old','undone'})
        self.assertEqual(evidence['prior_changes'],[prior]);self.assertEqual(len(evidence['supporting_reads']),1)
        self.assertEqual(evidence['supporting_reads'][0]['result']['text'],'return 1000L * timestamp;')
        self.assertFalse(evidence['supporting_reads'][0]['transcript_truncated'])
        self.assertNotIn('private model reasoning',json.dumps(evidence))
        self.assertEqual(evidence['supporting_read_gaps'],[])

    def test_partial_transcripts_are_visible_not_presented_as_complete_code(self):
        class API:
            def call(self,op,**args):
                if op=='agent.turns':return [{'turn':3}]
                return {'calls':[{'tool':'code','ok':True,'args':'{"symbol":"LB;","view":"java"}',
                                 'result':'{"broken…[+200 chars]','resultText':'prefix…[+900 chars]'},
                                {'tool':'code','ok':True,'args':'{"broken…[+20 chars]'}]}
        evidence=quality.research_evidence(API(),{'mission':'m1','original_class':'LA;'}, {},[],set())
        self.assertTrue(evidence['supporting_reads'][0]['transcript_truncated'])
        self.assertEqual(evidence['supporting_reads'][0]['result']['text'],'prefix…[+900 chars]')
        self.assertEqual(evidence['supporting_read_gaps'][-1]['turns'],[1,2])
        self.assertEqual(len(evidence['supporting_read_gaps']),2)

    def test_actual_http_stream_retains_full_evidence_and_native_max(self):
        requests=[]
        class Handler(BaseHTTPRequestHandler):
            def do_POST(self):
                requests.append(json.loads(self.rfile.read(int(self.headers['Content-Length']))))
                result={'verdict':'acceptable','summary':'Fixture arithmetic name matches code.','findings':[],
                        'examples':[{'symbol':'LA;->a(I)I','old':'a','new':'doubleValue','assessment':'supported','evidence':'returns input * 2'}]}
                body=('data: '+json.dumps({'choices':[{'delta':{'content':json.dumps(result)}}],'usage':{'prompt_tokens':41,'completion_tokens':13}})+'\n\ndata: [DONE]\n\n').encode()
                self.send_response(200);self.send_header('Content-Length',str(len(body)));self.send_header('Content-Type','text/event-stream');self.end_headers();self.wfile.write(body)
            def log_message(self,*args):pass
        server=ThreadingHTTPServer(('127.0.0.1',0),Handler);thread=threading.Thread(target=server.serve_forever,daemon=True);thread.start()
        try:
            with tempfile.TemporaryDirectory() as root:
                folder=Path(root);evidence={'code':'complete evidence '*1000}
                (folder/'evidence.json').write_text(json.dumps(evidence))
                (folder/'llm.json').write_text(json.dumps({'providers':{'zai':{'model':'glm-5.3','baseUrl':'http://127.0.0.1:'+str(server.server_port),'maxOutputTokens':131072,'extraParams':{'thinking':{'type':'enabled','clear_thinking':False},'reasoning_effort':'max'}}}}))
                (folder/'llm-keys.json').write_text(json.dumps({'zai':'synthetic-test-key'}))
                result=quality.provider_review(folder,evidence,'zai',folder)
                self.assertEqual(result['status'],'complete');self.assertEqual(result['usage']['completion_tokens'],13)
                self.assertEqual(requests[0]['reasoning_effort'],'max');self.assertNotIn('tools',requests[0])
                self.assertEqual(json.loads(requests[0]['messages'][1]['content']),evidence)
                quality.provider_review(folder,evidence,'zai',folder)
                self.assertEqual(len(requests),1)
        finally:server.shutdown();server.server_close();thread.join()

if __name__=='__main__':unittest.main()
