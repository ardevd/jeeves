"""Durable hand-off between an overnight coordinator and a verified deployment."""
from __future__ import annotations
import fcntl, json, time
from pathlib import Path
from checkpoint_backup import durable_json

def read_optional(path):
    try:return json.loads(Path(path).read_text())
    except FileNotFoundError:return None

def identity(request):
    revision=request['revision']
    if not isinstance(revision,str) or not revision or Path(revision).name!=revision or revision in ('.','..'):
        raise ValueError('Invalid maintenance revision')
    return {k:request[k] for k in ('revision','session','invocation_started_at')}

def completed(root,request):
    expected=identity(request)
    # The root-owned deployer's private receipt directory is intentionally not
    # readable by the coordinator. It publishes this verified acknowledgement
    # atomically through the existing service-owned deployment metadata instead.
    receipt=(read_optional(root/'deployment.json') or {}).get('maintenance',{})
    runtime=receipt.get('runtime',{})
    return (receipt.get('phase')=='complete' and receipt.get('revision')==expected['revision']
            and receipt.get('session')==expected['session'] and receipt.get('live_checks',0)>0
            and receipt.get('invocation_started_at')==expected['invocation_started_at']
            and runtime.get('build_sha')==expected['revision'] and runtime.get('build_dirty') is False)

def admission_held(root,session,revision,invocation):
    """The deployer still checks the catalog, save, review and live operations separately."""
    request=read_optional(root/'maintenance-request.json')
    ready=read_optional(root/'maintenance-ready.json')
    if not request or not ready or revision is None:return False
    expected=identity(request)
    return (expected['session']==session and expected['revision']==revision
            and expected['invocation_started_at']==invocation.get('started_at')
            and ready.get('phase')=='ready' and all(ready.get(k)==v for k,v in expected.items()))

def wait_at_boundary(root,db,invocation,lock):
    request=read_optional(root/'maintenance-request.json')
    if not request or completed(root,request):return
    if db.execute("SELECT 1 FROM sessions WHERE phase!='complete'").fetchone():return
    expected=identity(request)
    last=db.execute('SELECT max(id) FROM sessions').fetchone()[0]
    if expected['session']!=last or expected['invocation_started_at']!=invocation.get('started_at'):
        raise RuntimeError('Maintenance request does not match this completed session and invocation')
    durable_json(root/'maintenance-ready.json',dict(expected,phase='ready',at=time.time()))
    durable_json(root/'status.json',dict(phase='maintenance',session=last,revision=expected['revision'],
            reason='Session saved and reviewed; waiting for verified fix deployment',updated_at=time.time()))
    # No campaign/native operations occur until the deployer publishes its verified
    # receipt. Release the actual admission lock, including across process recovery.
    fcntl.flock(lock,fcntl.LOCK_UN)
    try:
        while not completed(root,request):
            if identity(read_optional(root/'maintenance-request.json') or {})!=expected:
                raise RuntimeError('Maintenance request changed while deployment was pending')
            time.sleep(5)
    finally:fcntl.flock(lock,fcntl.LOCK_EX)
