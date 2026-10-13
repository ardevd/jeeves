#!/usr/bin/env python3
"""Generic local Runs recorder. Background recording is NOT HIBERNATION-SAFE.

Use plan before start. Templates are canonical v1 bundles whose selected evidence
files describe the acquisition; downloaded hooks are not executed. Built-in DEX
observation only; custom/connection controllers use canonical external import.
"""
import argparse
import fcntl
import hashlib
import json
import os
from pathlib import Path
import signal
import subprocess
import sys
import time
import uuid

HERE=Path(__file__).resolve().parent
sys.path.insert(0,str(HERE))  # only this explicitly selected, verified application directory
import bundle
from lifecycle import Lifecycle

WARNING='NOT HIBERNATION-SAFE — laptop must remain awake and connected'


def read(path):
    path=Path(path)
    fd=os.open(path.parent,os.O_RDONLY|os.O_DIRECTORY|os.O_NOFOLLOW)
    try: return bundle.validator.strict_json(bundle.validator.read_member(fd,path.name,1<<20))
    finally: os.close(fd)


def owner_lock(root):
    fd=os.open(Path(root)/'owner.lock',os.O_RDWR|os.O_CREAT|os.O_NOFOLLOW,0o600)
    return os.fdopen(fd,'a+b')


def producer(fixture):
    names=sorted(path.relative_to(HERE).as_posix() for path in HERE.rglob('*') if path.is_file() and path.suffix in ('.py','.js') and not any(part in ('venv','.venv','__pycache__') for part in path.relative_to(HERE).parts))
    digest=hashlib.sha256(bundle.encode([{ 'path':name,'sha256':hashlib.sha256((HERE/name).read_bytes()).hexdigest()}
                                       for name in names])).hexdigest()
    revision='unpackaged-development'
    if (bundle.ROOT/'.git').exists():
        try:
            sha=subprocess.run(['git','rev-parse','HEAD'],cwd=bundle.ROOT,capture_output=True,text=True,check=True,timeout=5).stdout.strip()
            dirty=subprocess.run(['git','status','--porcelain','--untracked-files=all','--','backend/runs'],cwd=bundle.ROOT,capture_output=True,text=True,check=True,timeout=5).stdout.strip()
            revision=sha+('-dirty' if dirty else '')
        except (OSError,subprocess.SubprocessError): pass
    return {'name':'jeeves-local-recorder','version':'1.0.0','mode':'fixture' if fixture else 'local',
            'engine':None,'build_revision':revision,'build_sha256':digest}


def host_identity():
    pid=os.getpid()
    result=subprocess.run(['ps','-p',str(pid),'-o','lstart=','-o','comm='],text=True,capture_output=True,check=True)
    return {'pid':pid,'start_and_executable':result.stdout.strip(),'python':str(Path(sys.executable).resolve())}


def plan(template, fixture=False):
    report=bundle.validator.validate(template);doc=bundle.manifest(template)
    bundle.require((doc['producer']['mode']=='fixture')==fixture,'Fixture and real acquisition must remain distinct')
    bundle.require(all(row['role']=='events' or not row['path'].startswith('events/') for row in doc['files']), 'events/ is reserved for recorder segments')
    if not fixture:
        bundle.require(sum(len(h['origSigs']) for h in doc['hooks'])<=128,'Built-in recorder supports at most 128 observation sites')
        digest=hashlib.sha256((HERE/'_agent.js').read_bytes()).hexdigest()
        bundle.require(len(doc['conditions'])<=58,'Leave room for measured lifecycle conditions')
        bundle.require(all(h['source_sha256']==digest and h['role']=='observation' and not h['native_sites'] for h in doc['hooks']),
                       'This recorder supports the pinned built-in DEX observer only; external controllers import their own canonical bundle')
    return {'schema':'jeeves-local-run-plan-v1','template_manifest_sha256':report['manifest_sha256'],
            'target':doc['target'],'recipe':doc['recipe'],'device_label':doc['device']['id'],
            'hooks':[{'id':h['id'],'role':h['role'],'origSigs':h['origSigs'],'sha256':h['source_sha256']} for h in doc['hooks']],
            'fixture':fixture,'executes_uploaded_scripts':False,'automatic_composition':False,
            'notice':WARNING,'stop':'stop, then inspect cleanup; recover preserves partial evidence without replay'}


def status(root):
    result=read(Path(root)/'state.json')
    if result['state'] in ('starting','ready') and 'host_process' in result:
        with owner_lock(root) as lock:
            try:
                fcntl.flock(lock,fcntl.LOCK_EX|fcntl.LOCK_NB)
                # start writes state before the worker acquires its lock. Only a
                # published owner identity establishes that a missing lock is loss.
                result=read(Path(root)/'state.json')
                if result['state'] in ('starting','ready') and 'host_process' in result: result.update(state='owner-exited',cleanup='unknown',next='recover')
            except BlockingIOError: pass
    return {k:v for k,v in result.items() if k not in ('device','server_path','template','private_options','token')}


def start(args):
    report=plan(args.template,args.fixture)
    bundle.require(args.fixture or (args.device and args.server_path and args.ownership_confirmed),
                   'Explicit device, installed server path and controller ownership confirmation required')
    root=args.run.resolve();root.mkdir(mode=0o700)
    (root/'commands').mkdir(mode=0o700)
    bundle.selected_copy(args.template,root/'template')
    control={'run_id':str(uuid.uuid4()),'token':uuid.uuid4().hex,'state':'starting',
             'fixture':args.fixture,'device':args.device,'server_path':args.server_path,
             'ownership_confirmed':args.ownership_confirmed,'seconds':args.seconds,'event_budget':args.event_budget,
             'template_manifest_sha256':report['template_manifest_sha256'],'cleanup':'unknown'}
    bundle.atomic(root/'state.json',control)
    print(WARNING+': local worker, USB, result collection and cleanup depend on this laptop; deadline '+str(args.seconds)+' seconds plus cleanup.',file=sys.stderr)
    with (root/'worker.log').open('xb') as log:
        os.chmod(root/'worker.log',0o600)
        child=subprocess.Popen([sys.executable,'-I',str(Path(__file__).resolve()),'_worker','--run',str(root)],
                               stdin=subprocess.DEVNULL,stdout=log,stderr=log,start_new_session=True)
    # Wait for readiness, never kill an unqualified PID or launch a replacement.
    until=time.monotonic()+100
    while time.monotonic()<until:
        result=status(root)
        if result['state']!='starting': return result
        if child.poll() is not None: return {**result,'state':'owner-exited','cleanup':'unknown','next':'recover'}
        time.sleep(0.1)
    return {**status(root),'readiness':'unknown','next':'status or stop; no automatic retry'}


def command(root,kind,text=None):
    root=Path(root);state=read(root/'state.json')
    current=status(root)
    if current['state'] in ('closed','failed','owner-exited'):
        return current
    row={'token':state['token'],'kind':kind,'text':text}
    bundle.require(len(list((root/'commands').iterdir()))<256,'Control queue is full; inspect status before retrying')
    bundle.validator.strict_json(bundle.encode(row))
    if text is not None: bundle.require(len(text)<=500,'Marker exceeds 500 characters')
    bundle.atomic(root/'commands'/(uuid.uuid4().hex+'.json'),row)
    return {'accepted':True,'run_id':state['run_id'],'command':kind,'cleanup':'pending','next':'status'}


def cleanup_projection(row, fixture=False):
    if fixture: return {'state':'not-applicable','basis':'fixture','resources':[]}
    return {'state':row.get('cleanup','unknown'),'basis':'measured' if row.get('cleanup')=='complete' else 'unknown',
            'resources':[{'kind':'other','id':entry['resource'],'state':'released' if entry['state'] in ('released','absent') else 'unknown',
                          'basis':'measured' if entry['state'] in ('released','absent') else 'unknown',
                          'detail':'Owned lifecycle result; no global state changed'}
                         for entry in row.get('cleanup_results',[])[-128:]
                         if row.get('cleanup')!='complete' or entry['state'] in ('released','absent')]}


def worker(root):
    root=Path(root);state=read(root/'state.json')
    with owner_lock(root) as lock:
        fcntl.flock(lock,fcntl.LOCK_EX)
        state=read(root/'state.json')
        bundle.require(state['state']=='starting','This run has already started; no replay permitted')
        state['host_process']=host_identity();bundle.atomic(root/'state.json',state)
        bundle.require(bundle.validator.validate(root/'template')['manifest_sha256']==state['template_manifest_sha256'],'Frozen plan changed')
        recorder=bundle.Recorder(root/'template',root/'bundle',state['run_id'],state['event_budget'])
        fixture=state['fixture'];bridge=None;session=None;reason='deadline';cleanup={'cleanup':'unknown'}
        ending=[];stopping=False
        for sig in (signal.SIGINT,signal.SIGTERM): signal.signal(sig,lambda signum,frame: ending.append('manual'))
        try:
            recorder.doc['producer']=producer(fixture)
            recorder.checkpoint()
            if fixture:
                template=bundle.manifest(root/'template')
                for entry in template['files']:
                    if entry['role']=='events':
                        for line in (root/'template'/entry['path']).read_bytes().splitlines():
                            recorder.append(bundle.validator.strict_json(line))
            else:
                os.environ['WORKBENCH_RUNS_STATE']=str(root/'ownership')
                import bridge as live_bridge
                bridge=live_bridge
                doc=recorder.doc
                inventory=[]
                for source in doc['hooks']:
                    for original in source['origSigs']:
                        item=dict(source)
                        item.update(id='observer-'+hashlib.sha256((source['id']+'|'+original).encode()).hexdigest()[:24],
                                    origSigs=[original],state='deferred',state_basis='measured',error=None)
                        inventory.append(item)
                doc['hooks']=inventory
                doc['coverage'].update(installed_methods=0,observed_methods=0)
                row=bridge.op_provision({'device':state['device'],'serverPath':state['server_path'],'ownershipConfirmed':state['ownership_confirmed']})['ownership']
                phone=bridge._lifecycle.verify(row)
                pkg=doc['target']['package']
                import re, shlex
                bundle.require(re.fullmatch(r'[A-Za-z0-9_.]+',pkg),'Invalid package')
                paths=phone.shell('pm path '+shlex.quote(pkg)).splitlines()
                bundle.require(len(paths)==1 and paths[0].startswith('package:'),'Exact single-APK measurement required; split APK collection is not qualified')
                apk=paths[0][8:]
                digest=phone.shell('sha256sum '+shlex.quote(apk)).split()[0]
                size=int(phone.shell('stat -c %s '+shlex.quote(apk)))
                bundle.require(digest==doc['target']['artifact']['sha256'] and size==doc['target']['artifact']['size'],'Installed APK differs from recipe; no attach performed')
                version=bridge.op_appversion({'device':state['device'],'package':pkg})
                bundle.require(doc['target']['version'] in (version['versionName'],version['versionCode']),'Installed package version differs from recipe')
                doc['target']['identity_basis']='measured'
                doc['device'].update(id='android-'+hashlib.sha256(state['device'].encode()).hexdigest()[:16],identity_basis='measured')
                doc['session'].update(id=state['run_id'],controller_id=row['controller_id'],ownership='coordinated-local',processes=[])
                for key,value in (('frida-client',row['client_version']),('frida-server',row['server']['version']),('server-sha256',row['server']['sha256']),('selinux',row['observations']['selinux'])):
                    doc['conditions'].append({'name':key,'value':value,'provenance':'measured','source':'local lifecycle'})
                specs=[];mapping={}
                for hook in doc['hooks']:
                    for original in hook['origSigs']:
                        key='observer-'+str(len(specs));mapping[key]=(hook,original)
                        specs.append({'hookId':key,'signature':original})
                pending=[]
                events_ready=False
                def event(name,**value):
                    with recorder.lock:
                        consume(name,value)
                def consume(name,value):
                    if stopping and name=='session' and value.get('state')=='dead': return
                    # Before attach returns we retain events, not speculative process attribution.
                    if not events_ready:
                        if len(pending)<10000: pending.append((name,value))
                        else: recorder.loss('Attach readiness event buffer exceeded',known=1)
                        return
                    if name=='hit' and value.get('hookId') in mapping:
                        hook,original=mapping[value['hookId']]
                        hook.update(state='installed',error=None)
                        recorder.append({'kind':'exception' if value.get('threw') else 'leave','hook_id':hook['id'],
                            'origSig':original,'native':None,'process_id':'app','thread_id':str(value.get('thread','unknown'))[:120],
                            'phase':None,'correlation':None,'args':[],'result':None,
                            'summary':'Built-in observer completed a call; argument/return content omitted'})
                    elif name=='hitcount': recorder.loss('Agent rate limit dropped snapshots',known=max(0,int(value.get('dropped',0))))
                    elif name in ('agent_error',) or (name=='session' and value.get('state')=='dead'):
                        recorder.loss('Instrumentation ended or reported an error',unknown=True);ending.append('error')
                bridge.emit=event
                attached=bridge.op_attach({'device':state['device'],'package':pkg,'spawn':False,'hooks':specs})
                with recorder.lock:
                    session=attached['session']
                    process=attached['processIdentity']
                    doc['session']['processes']=[{'id':'app','pid':attached['pid'],'start_identity':process['boot_id']+':'+process['start_ticks']+':exe-'+hashlib.sha256(process['executable'].encode()).hexdigest(),'identity_basis':'measured'}]
                    for installed in attached['hooks']:
                        hook,_=mapping[installed['hookId']]
                        if not installed['ok']: hook.update(state='failed',error='Built-in observer failed to install')
                        elif not installed.get('deferred') and hook['state']!='failed': hook.update(state='installed')
                    recorder.checkpoint()
                    events_ready=True
                    for name,value in pending: event(name,**value)
            state.update(state='ready',ready_at=bundle.now());bundle.atomic(root/'state.json',state)
            deadline=time.monotonic()+state['seconds']
            while time.monotonic()<deadline and not ending and not recorder.stopped_by_budget:
                for path in sorted((root/'commands').glob('*.json')):
                    request=read(path)
                    bundle.require(request['token']==state['token'],'Control identity changed')
                    if request['kind']=='stop': ending.append('manual')
                    elif request['kind']=='mark': recorder.marker(request['text'])
                    path.unlink()
                time.sleep(0.1)
            reason=ending[0] if ending else 'event-budget' if recorder.stopped_by_budget else 'deadline'
        except Exception as error:
            reason='error';state['error']=type(error).__name__;recorder.loss('Recorder failed: '+type(error).__name__,unknown=True)
        finally:
            stopping=True
            if bridge is not None:
                try: cleanup=bridge.op_cleanup({'device':state['device']})['cleanup']
                except Exception: cleanup={'cleanup':'unknown'}
            report=recorder.finish(reason,cleanup_projection(cleanup,fixture))
            state.update(state='failed' if reason=='error' else 'closed',cleanup=report['cleanup'],completion=report['completion'],
                         ended_at=bundle.now(),manifest_sha256=report['manifest_sha256'])
            bundle.atomic(root/'state.json',state)
    return status(root)


def recover(root, cleanup_owned=False):
    root=Path(root)
    with owner_lock(root) as lock:
        try: fcntl.flock(lock,fcntl.LOCK_EX|fcntl.LOCK_NB)
        except BlockingIOError: raise ValueError('Owner still holds this run; request stop and inspect status')
        state=read(root/'state.json')
        if not (root/'bundle').exists():
            # Startup ended before any acquisition bundle existed. Preserve this as
            # a partial empty attempt, never restart instrumentation.
            recorder=bundle.Recorder(root/'template',root/'bundle',state['run_id'],state['event_budget'])
            recorder.doc['producer']=producer(state['fixture'])
            recorder.loss('Owner ended before acquisition became ready',unknown=True)
            recorder.finish('owner-crash',{'state':'unknown','basis':'fixture' if state['fixture'] else 'unknown','resources':[]})
        result=bundle.recover_bundle(root)
        recovery_cleanup=[]
        if cleanup_owned and not state['fixture']:
            import bridge
            owner=Lifecycle(bridge.ADB,bridge.FRIDA_VER,root/'ownership')
            for path in (root/'ownership').glob('*.json'):
                row=read(path)
                bundle.require(row['device']==state['device'],'Ownership receipt device differs')
                updated=owner.cleanup(row)
                recovery_cleanup.append({"controller_id":row["controller_id"],"cleanup":updated["cleanup"],"resources":updated.get("cleanup_results",[])})
            # Keep the finalized original unknown-cleanup manifest immutable. A separate
            # recovery receipt records later cleanup observations; exports do not rewrite history.
        state.update(state='closed',completion=result['completion'],cleanup=result['cleanup'],recovered=True)
        bundle.atomic(root/'state.json',state)
        return {**result,"recovery_cleanup":recovery_cleanup}


def main():
    p=argparse.ArgumentParser(description=__doc__,allow_abbrev=False)
    p.add_argument('command',choices=('plan','start','status','mark','stop','recover','cleanup','export','import','_worker'))
    p.add_argument('--template',type=Path);p.add_argument('--run',type=Path)
    p.add_argument('--destination',type=Path);p.add_argument('--source',type=Path)
    p.add_argument('--fixture',action='store_true');p.add_argument('--device');p.add_argument('--server-path')
    p.add_argument('--ownership-receipt',type=Path)
    p.add_argument('--ownership-confirmed',action='store_true');p.add_argument('--cleanup-owned',action='store_true')
    p.add_argument('--seconds',type=int,default=180);p.add_argument('--event-budget',type=int,default=50000);p.add_argument('--text')
    a=p.parse_args()
    try:
        bundle.require(1<=a.seconds<=600 and 1<=a.event_budget<=1000000,'Invalid recording bounds')
        if a.command in ('plan','start'): bundle.require(a.template is not None,'template is required')
        if a.command not in ('plan','import','cleanup'): bundle.require(a.run is not None,'run directory is required')
        if a.command=='plan': result=plan(a.template,a.fixture)
        elif a.command=='start': result=start(a)
        elif a.command=='_worker': result=worker(a.run)
        elif a.command=='status': result=status(a.run)
        elif a.command in ('mark','stop'):
            if a.command=='mark': bundle.require(a.text is not None,'marker text is required')
            result=command(a.run,a.command,a.text)
        elif a.command=='recover': result=recover(a.run,a.cleanup_owned)
        elif a.command=='cleanup':
            bundle.require(a.ownership_confirmed and a.ownership_receipt and a.device,'Explicit receipt, device and ownership confirmation required')
            row=read(a.ownership_receipt)
            bundle.require(row['device']==a.device and a.ownership_receipt.name==row['controller_id']+'.json','Receipt identity differs')
            owner=Lifecycle(os.environ.get('WORKBENCH_ADB') or os.environ.get('ADB') or 'adb',row['client_version'],a.ownership_receipt.parent)
            updated=owner.cleanup(row)
            result={'controller_id':row['controller_id'],'cleanup':updated['cleanup'],'resources':updated.get('cleanup_results',[]),'replayed':False}
        else:
            bundle.require(a.destination is not None,'destination is required')
            source=a.source if a.command=='import' else a.run/'bundle'
            bundle.require(source is not None,'source is required')
            result=bundle.selected_copy(source,a.destination)
        print(json.dumps(result,sort_keys=True));return 0
    except Exception as error:
        print(json.dumps({'ok':False,'error':str(error) if isinstance(error,(ValueError,RuntimeError)) else type(error).__name__}));return 1


if __name__=='__main__': raise SystemExit(main())
