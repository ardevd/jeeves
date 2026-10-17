import base64, hashlib, io, json, tarfile, tempfile, time, unittest, threading
from concurrent.futures import ThreadPoolExecutor
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
        for name in ('v','v1','s','s2','z','z12','p','arg0','local3'):
            with self.subTest(name=name):self.assertTrue(quality.generic(name,'local'))
        for name in ('A00','A0a','A1G','B1m','C2z'):
            with self.subTest(name=name):self.assertTrue(quality.generic(name,'field'))
        for name in ('addMessage','origin','timestamp','payloadRendererId','A0LongerName'):
            with self.subTest(name=name):self.assertFalse(quality.generic(name,'local'))
        self.assertTrue(quality.generic('s','class'))  # existing short-class heuristic
        self.assertFalse(quality.generic('this','local'))
        self.assertFalse(quality.generic('message','local'))
        self.assertFalse(quality.generic('<init>','method'))

    def test_propagated_family_change_is_reviewed_on_affected_class(self):
        row={'originalSymbol':'LA;->a()I','renameTargets':[{'originalSymbol':'LB;->a()I'}]}
        self.assertTrue(quality.affects_class(row,'LB;'))
        self.assertFalse(quality.affects_class(row,'LC;'))

    def test_checkpoint_rationales_match_artifact_author_and_applied_history(self):
        with tempfile.TemporaryDirectory() as temporary:
            root=Path(temporary);folder=root/'round';(folder/'barrier').mkdir(parents=True)
            checkpoint=root/'checkpoint';checkpoint.mkdir();archive=checkpoint/'checkpoint.tar';artifact='/input/actual.apk'
            proposal=dict(id='pp1',historyId='h1',kind='rename',agentId='writer',project='Actual Project',
                          targetKey='actual-key',state='accepted',symbol='LA;->a:I',newName='rowId',rationale='Caller binds the row identifier.')
            proposals=[proposal,dict(proposal,id='pending',state='pending'),dict(proposal,id='wrong-key',targetKey='other-key'),
                       dict(proposal,id='wrong-project',project='Other Project'),dict(proposal,id='unchanged',state='unchanged'),
                       dict(proposal,id='no-rationale',rationale=None)]
            files={'project-state/project/.project-name':b'Actual Project',
                   'project-state/project/targets.json':json.dumps({'targets':[{'key':'actual-key','storePath':artifact}]}).encode(),
                   'project-state/project/proposals.json':json.dumps(proposals).encode(),
                   'project-state/other/targets.json':json.dumps({'targets':[{'key':'actual-key','storePath':'/input/other.apk'}]}).encode(),
                   'project-state/other/proposals.json':json.dumps([proposal]).encode()}
            entries=[]
            with tarfile.open(archive,'w') as tar:
                for name,data in files.items():
                    member=tarfile.TarInfo(name);member.size=len(data);tar.addfile(member,io.BytesIO(data))
                    entries.append(dict(path=name,bytes=len(data),sha256=hashlib.sha256(data).hexdigest()))
            receipt=dict(local_verified=True,sha256=quality.sha256(archive),archive=str(archive),entries=entries)
            (checkpoint/'receipt.json').write_text(json.dumps(receipt))
            (folder/'barrier/receipt.json').write_text(json.dumps(dict(ok=True,checkpoint=str(checkpoint),backup_sha256=receipt['sha256'])))
            snapshot=quality.saved_proposals(folder,Path(artifact))
            self.assertEqual(snapshot['gaps'],[]);self.assertEqual(len(snapshot['proposals']),1)
            history=[dict(id='h1',op='rename',actor='agent:writer auto-applied',originalSymbol='LA;->a:I',newName='rowId')]
            before=json.dumps(history)
            evidence=quality.proposal_rationales(snapshot,history,'LA;')
            self.assertEqual(evidence[0]['rationale'],proposal['rationale']);self.assertEqual(evidence[0]['originalSymbol'],'LA;->a:I')
            self.assertEqual(json.dumps(history),before);self.assertEqual(quality.proposal_rationales(snapshot,history,'LB;'),[])
            for change in ({'undone':True},{'discarded':True},{'actor':'agent:writer-extra auto-applied'},
                           {'newName':'differentName'},{'op':'comment'},{'id':'different-id'}):
                with self.subTest(change=change):self.assertEqual(quality.proposal_rationales(snapshot,[dict(history[0],**change)],'LA;'),[])
            receipt['entries'][2]['sha256']='0'*64;(checkpoint/'receipt.json').write_text(json.dumps(receipt))
            failed=quality.saved_proposals(folder,artifact)
            self.assertEqual(failed['proposals'],[]);self.assertIn('checksum mismatch',failed['gaps'][0]['error'])

    def test_checkpoint_proposal_evidence_reports_missing_verification(self):
        with tempfile.TemporaryDirectory() as root:
            snapshot=quality.saved_proposals(Path(root),Path('/input/actual.apk'))
            self.assertEqual(snapshot['proposals'],[]);self.assertIn('Verified checkpoint',snapshot['gaps'][0]['error'])

    def test_checkpoint_rationale_follows_native_family_and_human_review_attribution(self):
        proposal=dict(id='pp1',historyId='h1',kind='rename',agentId='writer',newName='readValue',rationale='Shared getter contract.')
        history=[dict(id='h1',op='rename',actor='agent:writer accepted by human',originalSymbol='LA;->a()I',
                      newName='readValue',renameTargets=[{'originalSymbol':'LB;->a()I'}])]
        self.assertEqual(len(quality.proposal_rationales({'proposals':[proposal]},history,'LB;')),1)
        comment=dict(proposal,id='pp2',historyId='h2',kind='comment',newText='Verified caller.')
        history.append(dict(id='h2',op='comment',actor='agent:writer auto-applied',originalSymbol='LB;->a()I',newText='Verified caller.'))
        self.assertEqual(len(quality.proposal_rationales({'proposals':[proposal,comment]},history,'LB;')),2)

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

    def test_http_rejection_bytes_survive_result_recovery_without_replay(self):
        requests=[];body=b'provider diagnostic '+b'\xff\x00'+('full context diagnosis '*5000).encode()
        class Handler(BaseHTTPRequestHandler):
            def do_POST(self):
                requests.append(self.rfile.read(int(self.headers['Content-Length'])))
                self.send_response(400);self.send_header('Content-Type','application/octet-stream')
                self.send_header('Content-Length',str(len(body)));self.end_headers();self.wfile.write(body)
            def log_message(self,*args):pass
        server=ThreadingHTTPServer(('127.0.0.1',0),Handler);thread=threading.Thread(target=server.serve_forever,daemon=True);thread.start()
        try:
            with tempfile.TemporaryDirectory() as temporary:
                folder=Path(temporary);evidence={'code':'fixture source'}
                (folder/'evidence.json').write_text(json.dumps(evidence))
                (folder/'llm.json').write_text(json.dumps({'providers':{'deepseek':{'model':'deepseek-v4-pro',
                    'baseUrl':'http://127.0.0.1:'+str(server.server_port),'maxOutputTokens':393216}}}))
                (folder/'llm-keys.json').write_text(json.dumps({'deepseek':'private-fixture-secret'}))
                result=quality.provider_review(folder,evidence,'deepseek',folder)
                path=folder/'provider-error.json'
                self.assertTrue(path.exists(),'the actual provider rejection must be retained')
                saved=json.loads(path.read_text());self.assertEqual(saved['status'],400)
                self.assertEqual(base64.b64decode(saved['body_base64']),body)
                self.assertEqual(saved['body_bytes'],len(body));self.assertEqual(saved['body_sha256'],hashlib.sha256(body).hexdigest())
                self.assertEqual(saved['content_type'],'application/octet-stream');self.assertNotIn('private-fixture-secret',path.read_text())
                self.assertEqual(result['status'],'needs_manual_review');self.assertEqual(result['http_status'],400)
                self.assertEqual(result['provider_error_file'],'provider-error.json');self.assertNotIn('full context diagnosis',json.dumps(result))
                self.assertEqual(path.stat().st_mode&0o777,0o600)
                quality.provider_review(folder,evidence,'deepseek',folder)
                self.assertEqual(len(requests),1)
                (folder/'model-review.json').unlink()  # interrupted after saving rejection, before final result
                restored=quality.provider_review(folder,evidence,'deepseek',folder)
                self.assertEqual(restored['http_status'],400);self.assertEqual(restored['provider_error_file'],'provider-error.json')
                self.assertEqual(len(requests),1);self.assertFalse((folder/'response.sse').exists())
        finally:server.shutdown();server.server_close();thread.join()

    def test_context_rejection_uses_exact_remaining_window_and_recovers_without_replay(self):
        requests=[]
        rejection={'error':{'message':"This model's maximum context length is 1048576 tokens. However, you requested 1183244 tokens (790028 in the messages, 393216 in the completion). Please reduce the length of the messages or completion."}}
        class Handler(BaseHTTPRequestHandler):
            def do_POST(self):
                payload=json.loads(self.rfile.read(int(self.headers['Content-Length'])));requests.append(payload)
                if payload['max_tokens']==393216:
                    body=json.dumps(rejection).encode();status=400;kind='application/json'
                else:
                    result={'verdict':'acceptable','summary':'Full evidence reviewed.','findings':[],'examples':[]}
                    body=('data: '+json.dumps({'choices':[{'delta':{'content':json.dumps(result)}}]})+'\n\ndata: [DONE]\n\n').encode()
                    status=200;kind='text/event-stream'
                self.send_response(status);self.send_header('Content-Type',kind);self.send_header('Content-Length',str(len(body)))
                self.end_headers();self.wfile.write(body)
            def log_message(self,*args):pass
        server=ThreadingHTTPServer(('127.0.0.1',0),Handler);thread=threading.Thread(target=server.serve_forever,daemon=True);thread.start()
        try:
            with tempfile.TemporaryDirectory() as temporary:
                folder=Path(temporary);evidence={'code':'complete source and evidence '*1000}
                quality.durable_json(folder/'evidence.json',evidence)
                quality.durable_json(folder/'llm.json',{'providers':{'deepseek':{'model':'deepseek-v4-pro',
                    'baseUrl':'http://127.0.0.1:'+str(server.server_port),'maxOutputTokens':393216,
                    'extraParams':{'thinking':{'type':'enabled'},'reasoning_effort':'max'}}}})
                quality.durable_json(folder/'llm-keys.json',{'deepseek':'synthetic-key'})
                result=quality.provider_review(folder,evidence,'deepseek',folder)
                self.assertEqual(result['status'],'complete');self.assertEqual(len(requests),2)
                self.assertEqual(requests[1]['max_tokens'],258548)
                self.assertEqual(requests[0]['messages'],requests[1]['messages'])
                self.assertEqual(requests[0]['reasoning_effort'],requests[1]['reasoning_effort']);self.assertEqual(requests[1]['reasoning_effort'],'max')
                self.assertEqual(json.loads(requests[1]['messages'][1]['content']),evidence)
                correction=folder/result['review_attempt'];self.assertTrue((folder/'provider-error.json').exists())
                self.assertEqual(result['context_adjustments'][0]['input_tokens'],790028)
                self.assertEqual(json.loads((folder/'llm.json').read_text())['providers']['deepseek']['maxOutputTokens'],393216)
                self.assertEqual(json.loads((correction/'request.json').read_text())['max_output_tokens'],258548)
                quality.provider_review(folder,evidence,'deepseek',folder);self.assertEqual(len(requests),2)
                (folder/'model-review.json').unlink();(correction/'model-review.json').unlink()
                restored=quality.provider_review(folder,evidence,'deepseek',folder)
                self.assertEqual(restored['status'],'complete');self.assertEqual(len(requests),2)
                # A crash with only the corrected intent is ambiguous, even though
                # its parent was explicitly rejected. Never repeat that attempt.
                for path in [folder/'model-review.json',correction/'model-review.json',correction/'response.sse']:path.unlink()
                lost=quality.provider_review(folder,evidence,'deepseek',folder)
                self.assertEqual(lost['status'],'needs_manual_review');self.assertIn('unknown',lost['error'])
                self.assertEqual(len(requests),2)
        finally:server.shutdown();server.server_close();thread.join()

    def test_context_adjustment_requires_consistent_checksummed_explicit_counts(self):
        with tempfile.TemporaryDirectory() as temporary:
            folder=Path(temporary);quality.durable_json(folder/'evidence.json',{'code':'all input'})
            digest=quality.sha256(folder/'evidence.json')
            quality.durable_json(folder/'request.json',{'provider':'deepseek','evidence_sha256':digest,'max_output_tokens':393216})
            cases=[(1048576,1183244,790028,393216,258548),
                   (1048576,1183245,790028,393216,None),
                   (1048576,1183243,790028,393215,None),
                   (1048576,1441792,1048576,393216,None),
                   (1048576,393226,10,393216,None)]
            for limit,total,prompt,completion,expected in cases:
                with self.subTest(counts=(limit,total,prompt,completion)):
                    body=json.dumps({'error':{'message':f"maximum context length is {limit} tokens. However, you requested {total} tokens ({prompt} in the messages, {completion} in the completion)"}}).encode()
                    record=dict(provider='deepseek',status=400,body_complete=True,evidence_sha256=digest,
                                body_base64=base64.b64encode(body).decode(),body_bytes=len(body),body_sha256=hashlib.sha256(body).hexdigest())
                    quality.durable_json(folder/'provider-error.json',record)
                    result=quality.context_adjustment(folder,'deepseek')
                    self.assertEqual(result and result['max_output_tokens'],expected)
                    self.assertIsNone(quality.context_adjustment(folder,'zai'))
                    for change in [{'body_complete':False},{'status':429},{'body_sha256':'wrong'},{'evidence_sha256':'wrong'}]:
                        quality.durable_json(folder/'provider-error.json',dict(record,**change))
                        self.assertIsNone(quality.context_adjustment(folder,'deepseek'))

    def test_eight_reviews_overlap_publish_partial_results_and_resume_without_recharge(self):
        requests=[];lock=threading.Lock();arrived=threading.Event();release=[threading.Event() for _ in range(8)]
        class Handler(BaseHTTPRequestHandler):
            def do_POST(self):
                payload=json.loads(self.rfile.read(int(self.headers['Content-Length'])))
                evidence=json.loads(payload['messages'][1]['content']);index=int(evidence['original_class'][2:-1])
                with lock:
                    requests.append(payload)
                    if len(requests)==8:arrived.set()
                release[index].wait(15)
                result={'verdict':'acceptable','summary':'Fixture code agrees with the name.','findings':[],
                        'examples':[{'symbol':evidence['original_class'],'old':'a','new':'count','assessment':'supported','evidence':'Returns the fixture count.'}]}
                body=('data: '+json.dumps({'choices':[{'delta':{'content':json.dumps(result)}}],
                      'usage':{'prompt_tokens':20,'completion_tokens':10}})+'\n\n'+('' if index==7 else 'data: [DONE]\n\n')).encode()
                self.send_response(200);self.send_header('Content-Length',str(len(body)));self.end_headers();self.wfile.write(body)
            def log_message(self,*args):pass
        server=ThreadingHTTPServer(('127.0.0.1',0),Handler);thread=threading.Thread(target=server.serve_forever,daemon=True);thread.start()
        try:
            with tempfile.TemporaryDirectory() as temporary,ThreadPoolExecutor(max_workers=1) as coordinator:
                root=Path(temporary);folder=root/'round';folder.mkdir();artifact=root/'input.apk'
                sidecar=Path(str(artifact)+'.workbench');sidecar.mkdir()
                history=[dict(id='h'+str(i),op='rename',actor='agent:writer'+str(i)+' auto-applied',
                              originalSymbol='LC'+str(i)+';->a:I',newName='count') for i in range(8)]
                (sidecar/'history.json').write_text(json.dumps(history));before=(sidecar/'history.json').read_bytes()
                (folder/'baseline-history.json').write_text('[]')
                slots=[dict(original_class='LC'+str(i)+';',mission='m'+str(i),
                            model='deepseek:fixture' if i<4 else 'zai:fixture',result={'status':'complete'}) for i in range(8)]
                settings={'providers':{provider:{'model':provider+'-fixture','baseUrl':'http://127.0.0.1:'+str(server.server_port),
                          'maxOutputTokens':393216 if provider=='deepseek' else 131072,'extraParams':{'reasoning_effort':'max'}} for provider in ('deepseek','zai')}}
                (root/'llm.json').write_text(json.dumps(settings));(root/'llm-keys.json').write_text(json.dumps({'deepseek':'synthetic','zai':'synthetic'}))
                class API:
                    def call(self,op,**args):
                        symbol=args.get('symbol',args.get('class',''))
                        if op=='code.outline':return {'symbol':{'origSig':symbol,'addr':symbol,'name':'Fixture'},'fields':[],
                                                    'methods':[{'origSig':symbol+'->getCount(I)I','name':'getCount'}]}
                        if op=='code.decompile':return {'text':'complete fixture source '*1000,'symbol':{'origSig':symbol}}
                        if op=='code.params':return {'parameters':[{'name':'count','index':0}]}
                        if op=='locals.list':return [{'name':'count','originalSymbol':symbol+'#local:1'}]
                        if op=='agent.turns':return []
                        raise AssertionError(op)
                with patch.object(quality,'saved_proposals',return_value={'proposals':[],'gaps':[]}):
                    future=coordinator.submit(quality.review_session,API(),'t1',slots,folder,artifact,root)
                    try:
                        self.assertTrue(arrived.wait(10),'all eight requests must arrive before any response is released')
                        self.assertEqual(len(requests),8)
                        self.assertEqual(sum(p['model']=='zai-fixture' for p in requests),4)
                        self.assertEqual(sum(p['model']=='deepseek-fixture' for p in requests),4)
                        for payload in requests:
                            evidence=json.loads(payload['messages'][1]['content'])
                            self.assertEqual(evidence['code']['text'],'complete fixture source '*1000)
                            self.assertEqual(len(evidence['changes']),1);self.assertEqual(len(evidence['methods']),1)
                            self.assertNotIn('tools',payload);self.assertEqual(payload['reasoning_effort'],'max')
                            self.assertEqual(payload['max_tokens'],393216 if payload['model']=='deepseek-fixture' else 131072)
                        release[5].set();partial=folder/'quality/partial.json';deadline=time.monotonic()+10
                        while not partial.exists() and time.monotonic()<deadline:time.sleep(.01)
                        self.assertTrue(partial.exists());rows=json.loads(partial.read_text())
                        self.assertEqual([row['original_class'] for row in rows],['LC5;'])
                        self.assertFalse(future.done(),'unfinished paid requests continue while progress is published')
                    finally:
                        for gate in release:gate.set()
                    result=future.result(timeout=15)
                    self.assertEqual([row['original_class'] for row in result['classes']],[slot['original_class'] for slot in slots])
                    self.assertTrue(result['read_only'] and result['history_unchanged']);self.assertEqual(result['status'],'complete')
                    self.assertEqual(result['classes'][7]['review']['status'],'needs_manual_review')
                    self.assertIn('completion marker',result['classes'][7]['review']['error'])
                    self.assertEqual((sidecar/'history.json').read_bytes(),before)
                    (folder/'quality/receipt.json').unlink()  # coordinator died before publishing this file
                    resumed=quality.review_session(API(),'t1',slots,folder,artifact,root)
                    self.assertEqual(len(requests),8);self.assertEqual(len(resumed['classes']),8)
                    self.assertEqual(resumed['classes'][7]['review']['status'],'needs_manual_review')
                    self.assertTrue((folder/'quality/class-08/response.sse').exists())
        finally:
            for gate in release:gate.set()
            server.shutdown();server.server_close();thread.join()

if __name__=='__main__':unittest.main()
