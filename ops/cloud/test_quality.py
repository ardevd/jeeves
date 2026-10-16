import json, tempfile, unittest
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

if __name__=='__main__':unittest.main()
