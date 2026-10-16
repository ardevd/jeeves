#!/usr/bin/env python3
"""Activate a prepared release only after the requested session/save/review ends.

No model calls, agent launches, turn limits or polling deadlines. Run this under
systemd, with a durable request and a prebuilt release. Existing releases and
databases are retained. A failed check leaves a durable error and retries the
same deployment stage, never another campaign session.
"""
from __future__ import annotations
import argparse, fcntl, json, os, pwd, sqlite3, subprocess, sys, time
from pathlib import Path
from barrier import API, ROOT, ensure_target, run_barrier, verify_live
from checkpoint_backup import durable_json, sha256

TERMINAL={'done','blocked','stopped','interrupted'}

def read(path):
    return json.loads(Path(path).read_text())

def boundary(root,session,api):
    invocation=read(root/'invocation.json')
    if invocation.get('phase')!='complete':return None
    with sqlite3.connect('file:'+str(root/'catalog.sqlite3')+'?mode=ro',uri=True) as db:
        sessions=db.execute('SELECT id,phase FROM sessions ORDER BY id').fetchall()
    if not sessions or sessions[-1][0]!=session or any(phase!='complete' for _,phase in sessions):return None
    folder=root/'rounds'/f'cloud-{session:06d}'
    receipt=read(folder/'barrier/receipt.json');quality=read(folder/'quality/receipt.json')
    if not receipt.get('ok') or receipt.get('phase')!='complete':return None
    if quality.get('status')!='complete' or not quality.get('read_only') or not quality.get('history_unchanged'):return None
    if len(quality.get('classes',[]))!=8:return None
    missions=api.call('agent.missions')
    if any(m.get('state') not in TERMINAL or m.get('loopActive') or m.get('cancellationUnconfirmed') for m in missions):return None
    calls=api.call('ops.active')
    if isinstance(calls,dict):calls=calls.get('calls',[])
    if any(str(call.get('actor','')).startswith(('agent:','llm:')) or call.get('mutating') for call in calls):return None
    return folder,receipt,quality

def verify_release(release,revision):
    manifest=read(release/'release-manifest.json')
    if manifest['revision']!=revision:raise RuntimeError('Release revision mismatch')
    for relative,digest in manifest['files'].items():
        path=release/relative
        if not path.resolve().is_relative_to(release.resolve()) or path.is_symlink() or sha256(path)!=digest:
            raise RuntimeError('Release file mismatch: '+relative)
    properties=(release/'backend/build/classes/java/main/build-info.properties').read_text()
    if 'git.sha='+revision+'\n' not in properties or 'git.dirty=false\n' not in properties:
        raise RuntimeError('Compiled build is not the requested clean revision')

def activate(source,release,previous):
    """Recoverable directory-to-symlink migration; never delete the old release."""
    if source.is_symlink():
        if source.resolve()==release.resolve():return
        if source.resolve()!=previous.resolve():raise RuntimeError('Unexpected active release')
    elif source.exists():
        if previous.exists():raise RuntimeError('Previous release destination already exists')
        source.rename(previous)
    elif not previous.exists():raise RuntimeError('Neither active nor previous release exists')
    link=source.with_name(source.name+'.next')
    if link.is_symlink():link.unlink()
    elif link.exists():raise RuntimeError('Unexpected activation staging path')
    link.symlink_to(release,target_is_directory=True);os.replace(link,source)
    fd=os.open(source.parent,os.O_RDONLY)
    try:os.fsync(fd)
    finally:os.close(fd)

def deployment_note(root,**fields):
    path=root/'deployment.json';current=read(path);owner=path.stat()
    current.update(fields)
    tmp=path.with_name(path.name+'.maintenance-tmp')
    with tmp.open('w') as output:
        os.chmod(tmp,0o600);os.chown(tmp,owner.st_uid,owner.st_gid)
        json.dump(current,output,indent=2);output.write('\n');output.flush();os.fsync(output.fileno())
    os.replace(tmp,path)

def publish_completion(root,folder,state):
    durable_json(folder/'receipt.json',state)
    deployment_note(root,revision=state['revision'],pending_revision=None,
        performance_note='Current-name attribution resolves relevant identities only; the optimized backend is active.',
        naming_audit_note='Collision diagnostics and local/argument attribution/lookup fixes are deployed. The completed session and its quality findings are preserved; no additional reversing session was started.',
        maintenance={'phase':'complete','revision':state['revision'],'receipt':str(folder/'receipt.json')})

def presave(api,folder,artifact):
    """Keep native JEB and private checkpoint files under the licensed account.

    Only release activation/systemctl need root. The child uses the same pinned
    maintenance code and inherits the installed JEB/runtime environment.
    """
    account=pwd.getpwnam('jeeves')
    if os.geteuid()!=0:raise RuntimeError('Deployment control requires root')
    if not folder.exists():
        folder.mkdir(mode=0o700)
        os.chown(folder,account.pw_uid,account.pw_gid)
    owner=folder.stat()
    if (owner.st_uid,owner.st_gid)!=(account.pw_uid,account.pw_gid):
        raise RuntimeError('Maintenance save directory must belong to jeeves')
    subprocess.run([sys.executable,str(Path(__file__).resolve()),'--presave',str(folder),
                    '--artifact',str(artifact),'--url',api.url],
                   user=account.pw_uid,group=account.pw_gid,
                   extra_groups=os.getgrouplist(account.pw_name,account.pw_gid),
                   env=dict(os.environ,HOME=account.pw_dir,USER=account.pw_name,LOGNAME=account.pw_name),check=True)
    return read(folder/'barrier/receipt.json')

def saved_baseline(root,receipt,expected,history_hash):
    """A previous deployment may have saved review analysis after the session.

    Accept that later database only by its completed native/live maintenance
    receipt and exact bytes/history. An unexplained mismatch still fails closed.
    """
    actual=sha256(Path(receipt['db']))
    if actual==receipt['database_sha256']:return receipt,Path(expected)
    revision=read(root/'deployment.json').get('revision')
    prior_path=root/'maintenance'/('deploy-'+str(revision))/'receipt.json'
    if prior_path.is_file():
        prior=read(prior_path);barrier=prior.get('barrier',{})
        prior_expected=Path(prior.get('expected',''))
        if (prior.get('phase')=='complete' and prior.get('revision')==revision and prior.get('db')==receipt['db']
                and prior.get('database_sha256')==actual and prior.get('history_sha256')==history_hash
                and barrier.get('ok') and barrier.get('phase')=='complete' and barrier.get('db')==receipt['db']
                and barrier.get('database_sha256')==actual and barrier.get('native_checks',0)>0
                and barrier.get('live_checks',0)>0 and prior_expected.is_file()
                and prior_expected.resolve().is_relative_to(root.resolve())):
            return barrier,prior_expected
    raise RuntimeError('Database changed after verification; no matching completed maintenance receipt')

def deploy(request,api,systemctl):
    root=Path(request['root']).resolve();release=Path(request['release']).resolve()
    source=Path(request['source']);revision=request['revision'];session=request['session']
    if root!=ROOT:raise ValueError('Request must use the configured campaign root')
    folder=root/'maintenance'/('deploy-'+revision);folder.mkdir(parents=True,exist_ok=True)
    state_path=folder/'state.json'
    state=read(state_path) if state_path.exists() else {'phase':'waiting','revision':revision,'session':session}
    if not state_path.exists():durable_json(state_path,state)
    if state['phase']=='complete':
        publish_completion(root,folder,state)
        return state
    def phase(name,**fields):
        state.update(phase=name,at=time.time(),**fields);state.pop('error',None);durable_json(state_path,state)
        deployment_note(root,maintenance={'phase':name,'revision':revision,'at':state['at']})
    try:
        verify_release(release,revision)
        # Never compete with a campaign coordinator. This lock is also held
        # across activation/reopen so a manual coordinator cannot race it.
        with (root/'controller.lock').open('a') as lock:
            if state['phase']=='waiting':
                while True:
                    if read(root/'invocation.json').get('phase')!='complete':time.sleep(5);continue
                    try:fcntl.flock(lock,fcntl.LOCK_EX|fcntl.LOCK_NB)
                    except BlockingIOError:time.sleep(5);continue
                    ended=boundary(root,session,api)
                    if ended:break
                    fcntl.flock(lock,fcntl.LOCK_UN);time.sleep(5)
                ended_folder,receipt,quality=ended
                artifact=Path(request['artifact'])
                history=Path(str(artifact)+'.workbench/history.json')
                if sha256(history)!=quality['history_sha256']:raise RuntimeError('Edits changed after quality review')
                maintenance=root/'maintenance'/('presave-'+revision)
                pending=(maintenance/'barrier/state.json').exists()
                # A resumed save may have deliberately closed the target for
                # archive/native verification. Do not reopen it prematurely or
                # skip the durable barrier just because decompiled is now zero.
                targets=[t for t in api.call('target.list') if t.get('path')==str(artifact)
                         and t.get('state') not in ('closed','error')]
                if not pending and not targets:
                    targets=[{'id':ensure_target(api,artifact)}]
                changes=[api.call('target.changes',target=t['id']) for t in targets]
                if any(c.get('dirty') or c.get('symbols') for c in changes):
                    raise RuntimeError('Unexpected unsaved edits after session verification')
                if pending or any(c.get('decompiled') for c in changes):
                    # Preserve analysis produced by read-only review too. This
                    # is a save/reopen barrier, never a new reversing session.
                    deployment_note(root,maintenance={'phase':'presaving','revision':revision})
                    receipt=presave(api,maintenance,artifact)
                    expected=maintenance/'barrier/expected.json'
                else:receipt,expected=saved_baseline(root,receipt,ended_folder/'barrier/expected.json',quality['history_sha256'])
                previous=source.resolve() if source.is_symlink() else source.parent/'releases'/('before-'+revision)
                previous.parent.mkdir(parents=True,exist_ok=True)
                phase('closing',previous=str(previous),target=receipt['target'],db=receipt['db'],
                      database_sha256=receipt['database_sha256'],history_sha256=quality['history_sha256'],
                      expected=str(expected),barrier=receipt)
            else:fcntl.flock(lock,fcntl.LOCK_EX)
            if state['phase']=='closing':
                if boundary(root,session,api) is None:raise RuntimeError('Session no longer quiescent before deployment')
                # Also recover a prior deployer that already closed the target
                # before noticing it had selected the older session checksum.
                baseline,expected=saved_baseline(root,state['barrier'],state['expected'],state['history_sha256'])
                if baseline['database_sha256']!=state['database_sha256']:
                    phase('closing',barrier=baseline,expected=str(expected),
                          database_sha256=baseline['database_sha256'],baseline_reconciled=True)
                for target in api.call('target.list'):
                    if target.get('path')==request['artifact'] and target.get('state')!='closed':
                        changes=api.call('target.changes',target=target['id'])
                        if changes.get('dirty') or changes.get('symbols') or changes.get('decompiled'):
                            raise RuntimeError('New unsaved work appeared before close')
                        api.call('target.close',target=target['id'],save=True,**{'async':False})
                if Path(state['db']+'.lock').exists():raise RuntimeError('Database remains locked')
                if sha256(Path(state['db']))!=state['database_sha256']:raise RuntimeError('Database changed after verification')
                phase('switching')
            if state['phase']=='switching':
                systemctl('stop','jeeves.service')
                activate(source,release,Path(state['previous']))
                phase('verifying')
            if state['phase']=='verifying':
                systemctl('start','jeeves.service')
                while True:
                    try:runtime=api.call('workspace.state')['runtime'];break
                    except (OSError,RuntimeError):time.sleep(2)
                if runtime['build_sha']!=revision or runtime.get('build_dirty') is not False:
                    raise RuntimeError('Unexpected running backend build: '+str(runtime))
                api.call('project.open',name=request['project'])
                target=ensure_target(api,request['artifact'])
                result=verify_live(api,target,read(state['expected']))
                durable_json(folder/'live-verification.json',result)
                if not result.get('ok'):raise RuntimeError('Saved names differ after release activation')
                if sha256(Path(request['artifact']+'.workbench/history.json'))!=state['history_sha256']:
                    raise RuntimeError('Deployment changed rename history')
                if boundary(root,session,api) is None:raise RuntimeError('Unexpected session activity after deployment')
                phase('complete',runtime=api.call('workspace.state')['runtime'],live_checks=result['checked'],target=target)
                publish_completion(root,folder,state)
        return state
    except Exception as error:
        state.update(error=str(error),at=time.time());durable_json(state_path,state)
        deployment_note(root,maintenance={'phase':state['phase'],'revision':revision,'error':str(error)})
        raise

def main():
    parser=argparse.ArgumentParser()
    mode=parser.add_mutually_exclusive_group(required=True)
    mode.add_argument('--request');mode.add_argument('--presave')
    parser.add_argument('--artifact');parser.add_argument('--url');args=parser.parse_args()
    if args.presave:
        account=pwd.getpwnam('jeeves')
        if os.geteuid()!=account.pw_uid:raise RuntimeError('Native save must run as jeeves')
        if not args.artifact or not args.url:parser.error('--presave needs --artifact and --url')
        folder=Path(args.presave)
        result=run_barrier(API(args.url),folder,args.artifact)
        durable_json(folder/'barrier/receipt.json',result)
        return
    request=read(args.request)
    def systemctl(*args):subprocess.run(['systemctl',*args],check=True)
    deploy(request,API(request['url']),systemctl)

if __name__=='__main__':main()
