import json,tempfile,threading,unittest
from concurrent.futures import ThreadPoolExecutor
from http.server import BaseHTTPRequestHandler,ThreadingHTTPServer
from pathlib import Path
import quality
from review_evidence import compact,pack,unpack,encode_evidence

class Tests(unittest.TestCase):
 def test_complete_values_whitespace_unicode_and_reserved_keys_round_trip(self):
  repeated={'original':'Lcom/fixture/LongClass;->method(II)Ljava/lang/String;',
            'code':'  first line\n\t第二行 → value\n','evidence':'supported by exact call-site data '*20}
  value={'methods':[dict(repeated) for _ in range(30)],'literal':{'$ref':0},
         'nested':{'$literal':{'$ref':1}},'array':[None,True,False,1,1.0,'$ref',{'$literal':42}]}
  before=compact(value);content,encoding=encode_evidence(value)
  self.assertEqual(encoding,'shared-json-values-v1');self.assertLess(len(content.encode()),len(before.encode()))
  self.assertEqual(compact(unpack(json.loads(content))),before)
  self.assertEqual(compact(value),before);self.assertEqual(encode_evidence(value),(content,encoding))
  self.assertEqual(unpack(pack(value))['methods'][0]['code'],repeated['code'])

 def test_small_or_unique_evidence_keeps_plain_compact_json(self):
  for value in [{},{'code':'x'*10000},{'a':1,'b':['one','two']}]:
   content,encoding=encode_evidence(value)
   self.assertEqual(encoding,'compact-json-v1');self.assertEqual(json.loads(content),value)

 def test_invalid_or_cyclic_references_are_explicitly_rejected(self):
  for index in [True,-1,1,'0']:
   with self.assertRaises(ValueError):unpack({'shared_values':['value'],'evidence':{'$ref':index}})
  with self.assertRaises(ValueError):unpack({'shared_values':[{'$ref':0}],'evidence':{'$ref':0}})
  with self.assertRaises(ValueError):unpack({'shared_values':[{'$ref':1},{'$ref':0}],'evidence':{'$ref':0}})

 def test_eight_actual_reviews_receive_complete_evidence_and_resume_without_replay(self):
  requests=[];problems=[];lock=threading.Lock()
  evidence=[{'slot':i,'code':'class Fixture {\n  void method() {}\n}',
             'rows':[{'identity':'Lfixture/Named;->method()V#local:123456789012345678',
                      'name':'messageStore','evidence':'verified against original source and the current caller '*10} for _ in range(30)]} for i in range(8)]
  class Handler(BaseHTTPRequestHandler):
   def do_POST(self):
    try:
     payload=json.loads(self.rfile.read(int(self.headers['Content-Length'])))
     encoded=json.loads(payload['messages'][1]['content'])
     if encoded.get('encoding')!='shared-json-values-v1':raise ValueError('oversized unshared fixture')
     restored=unpack(encoded)
     if restored!=evidence[restored['slot']]:raise ValueError('evidence changed')
     if 'Expand references conceptually' not in payload['messages'][0]['content']:raise ValueError('missing reference contract')
     if payload['reasoning_effort']!='max' or 'tools' in payload:raise ValueError('review contract changed')
     with lock:requests.append(restored['slot'])
     result={'verdict':'acceptable','summary':'Complete fixture evidence received.','findings':[],'examples':[]}
     body=('data: '+json.dumps({'choices':[{'delta':{'content':json.dumps(result)}}]})+'\n\ndata: [DONE]\n\n').encode();status=200
    except Exception as error:
     with lock:problems.append(str(error))
     body=json.dumps({'error':str(error)}).encode();status=400
    self.send_response(status);self.send_header('Content-Length',str(len(body)));self.end_headers();self.wfile.write(body)
   def log_message(self,*args):pass
  server=ThreadingHTTPServer(('127.0.0.1',0),Handler);thread=threading.Thread(target=server.serve_forever,daemon=True);thread.start()
  try:
   with tempfile.TemporaryDirectory() as tmp:
    root=Path(tmp);settings={'providers':{p:{'model':'fixture','baseUrl':'http://127.0.0.1:'+str(server.server_port),
     'maxOutputTokens':393216 if p=='deepseek' else 131072,'extraParams':{'reasoning_effort':'max','thinking':{'type':'enabled'}}} for p in ['deepseek','zai']}}
    quality.durable_json(root/'llm.json',settings);quality.durable_json(root/'llm-keys.json',{'deepseek':'fixture','zai':'fixture'})
    def run(i):
     folder=root/str(i);folder.mkdir();quality.durable_json(folder/'evidence.json',evidence[i]);before=(folder/'evidence.json').read_bytes()
     provider='deepseek' if i<4 else 'zai';result=quality.provider_review(folder,evidence[i],provider,root)
     self.assertEqual(result['status'],'complete');self.assertEqual((folder/'evidence.json').read_bytes(),before)
     self.assertEqual(json.loads((folder/'request.json').read_text())['evidence_encoding'],'shared-json-values-v1')
     (folder/'model-review.json').unlink()
     self.assertEqual(quality.provider_review(folder,evidence[i],provider,root)['status'],'complete')
    with ThreadPoolExecutor(max_workers=8) as pool:list(pool.map(run,range(8)))
    self.assertEqual(sorted(requests),list(range(8)));self.assertFalse(problems)
  finally:server.shutdown();server.server_close();thread.join()

if __name__=='__main__':unittest.main()
