"""Record explicit provider credit refusals without cancelling accepted work."""
from __future__ import annotations
import base64, hashlib, json, re, time
from pathlib import Path
from checkpoint_backup import durable_json, sha256

def exhausted(provider,status=None,error=''):
    # Do not confuse an ordinary 429, context rejection, or transport error with
    # a credit refusal. These are official provider error meanings.
    message=str(error)
    if provider=='deepseek':
        return status==402 or bool(re.search(r'\b(?:provider\s+)?HTTP\s+402\b|\binsufficient balance\b',message,re.I))
    if provider=='zai':
        return bool(re.search(r'["\']code["\']\s*:\s*["\']?1113\b|\binsufficient balance\b|余额不足',message,re.I))
    return False

def mission_exhaustion(api,mission,model):
    if mission.get('state')!='blocked':return None
    provider=model.split(':',1)[0]
    reason=mission.get('stopReason') or mission.get('reason') or ''
    status=None;source='mission:'+mission['id']
    if not exhausted(provider,error=reason):
        turns=api.call('agent.turns',mission=mission['id'])
        if turns:
            turn=max(turns,key=lambda r:r['turn'])['turn']
            record=api.call('agent.turn',mission=mission['id'],turn=turn)
            # Read only failure diagnostics. Never publish model reasoning.
            status=record.get('failure',{}).get('status')
            reason=record.get('error','')
            source+=':turn:'+str(turn)
    if exhausted(provider,status,reason):
        return {'provider':provider,'source':source,'http_status':status,'reason':'Provider reported insufficient credit'}
    return None

def review_exhaustion(folder):
    failures=[]
    for path in Path(folder).rglob('provider-error.json'):
        record=json.loads(path.read_text());request=json.loads((path.parent/'request.json').read_text())
        body=base64.b64decode(record['body_base64'],validate=True)
        if (len(body)!=record['body_bytes'] or hashlib.sha256(body).hexdigest()!=record['body_sha256']
                or record.get('provider')!=request.get('provider')
                or record.get('evidence_sha256')!=request.get('evidence_sha256')
                or sha256(path.parent/'evidence.json')!=request.get('evidence_sha256')):
            raise ValueError('Provider credit diagnostic failed request/checksum verification: '+str(path))
        if exhausted(record['provider'],record['status'],body.decode('utf-8','replace')):
            failures.append({'provider':record['provider'],'source':str(path),'http_status':record['status'],
                             'reason':'Provider reported insufficient credit'})
    return failures

def record_exhaustion(root,session,failures):
    if not failures:return
    path=Path(root)/'credit-stop.json'
    previous=json.loads(path.read_text()) if path.exists() else {}
    existing=previous.get('failures',[]) if previous.get('session')==session else []
    combined={r['source']:r for r in existing+failures}
    durable_json(path,{'session':session,'at':time.time(),'failures':list(combined.values()),
                      'behavior':'Finish the active session save, verification and review outcomes; admit no next session.'})
