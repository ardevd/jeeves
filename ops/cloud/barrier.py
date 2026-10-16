#!/usr/bin/env python3
"""Save, independently reopen and back up the shared database between analysis rounds."""
from __future__ import annotations
import argparse
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import tarfile
import time
import urllib.request

from checkpoint_backup import create_checkpoint, durable_json, sha256

ROOT = Path(os.environ.get("JEEVES_CAMPAIGN_ROOT", "/var/lib/jeeves/campaign")).resolve()
REPO = Path(os.environ.get("JEEVES_REPO", "/opt/jeeves/source"))
JEB = Path(os.environ.get("JEB_HOME", "/opt/jeeves/jeb"))
APK_SHA = os.environ.get("JEEVES_APK_SHA256", "")
LIVE_HOME = Path(os.environ.get("WORKBENCH_HOME", "/var/lib/jeeves/home"))

def historical_aliases():
    path=ROOT/'historical-aliases.json'
    if not path.exists(): return {'aliases':{}}
    record=json.loads(path.read_text())
    if record.get('apk_sha256')!=APK_SHA or not isinstance(record.get('aliases'),dict):
        raise ValueError('Historical alias evidence belongs to a different executable')
    return record

class API:
    def __init__(self, url, timeout=None):
        from urllib.parse import urlsplit
        parsed=urlsplit(url)
        if parsed.scheme != "http" or parsed.hostname != "127.0.0.1" or parsed.path: raise ValueError("A loopback HTTP endpoint is required")
        self.url = url
        self.timeout = timeout
        self.opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    def call(self, op, **params):
        payload = json.dumps({'params':params,'actor':'human:campaign-coordinator'}).encode()
        request = urllib.request.Request(self.url+'/api/'+op, data=payload, headers={'Content-Type':'application/json'})
        with self.opener.open(request, timeout=self.timeout) as response: result = json.load(response)
        if not result.get('ok'): raise RuntimeError(f'{op}: {result.get("error")}')
        return result['data']

def canonical_candidates(history):
    legacy=historical_aliases()['aliases']
    classes = {}
    for entry in history:
        if entry.get('op')=='rename' and entry.get('kind')=='class':
            original = entry.get('originalSymbol') or classes.get(entry.get('addr'),entry.get('addr'))
            if original:
                classes[entry.get('addr')] = original
                classes[entry.get('newAddr')] = original
    def normalize(value):
        if not isinstance(value,str): return value
        return re.sub(r'L[^;]+;', lambda m: classes.get(m.group(),m.group()), value)
    aliases = {}
    for entry in history:
        if entry.get('op')=='rename' and entry.get('kind') not in ('class','local','parameters'):
            old = normalize(entry.get('addr')); new = normalize(entry.get('newAddr'))
            original = entry.get('originalSymbol') or aliases.get(old,old)
            original = legacy.get(original,original)
            if original:
                aliases[old]=original; aliases[new]=original
    def canonical(value):
        normalized=normalize(value)
        result=aliases.get(normalized,normalized)
        return legacy.get(result,legacy.get(normalized,result))
    candidates={}
    locals_to_check=[]
    for entry in history:
        if entry.get('op') not in ('rename','comment'): continue
        if entry.get('kind')=='local':
            locals_to_check.append(entry)
            continue
        original=canonical(entry.get('originalSymbol') or entry.get('addr'))
        if original:
            row=candidates.setdefault(original,{'symbol':original,'comment':False,'history_ids':[]})
            row['comment'] |= entry.get('op')=='comment'
            row['history_ids'].append(entry.get('id'))
        for affected in entry.get('renameTargets',[]):
            original=affected.get('originalSymbol')
            if original: candidates.setdefault(original,{'symbol':original,'comment':False,'history_ids':[]})
    return candidates,locals_to_check,canonical

def capture_expected(api,target,history,output):
    candidates,local_entries,canonical=canonical_candidates(history)
    expected={}; failures=[]
    for candidate in candidates.values():
        try:
            resolved=api.call('symbol.resolve',target=target,symbol=candidate['symbol'])
            original=resolved.get('origSig')
            if not original: raise RuntimeError(f'Missing original identity: {resolved}')
            if resolved.get('contradiction') or original!=candidate['symbol']:
                raise RuntimeError(f'Canonical identity mismatch: requested {candidate["symbol"]}, resolved {resolved}')
            row=expected.setdefault(original,{'original_symbol':original,'name':resolved['name']})
            if candidate['comment']:
                row['comment']=api.call('code.hover',target=target,symbol=original,offset=0).get('comment','')
        except Exception as error:
            failures.append({'candidate':candidate,'error':str(error)})
    # Check each current local on methods touched by a local rename, including undone entries.
    methods={canonical(e.get('originalMethod') or e.get('addr')) for e in local_entries}
    for method in methods:
        try:
            resolved=api.call('symbol.resolve',target=target,symbol=method)
            original=resolved['origSig']
            if resolved.get('contradiction') or original!=method:
                raise RuntimeError(f'Canonical local-method identity mismatch: {resolved}')
            rows=api.call('locals.list',target=target,symbol=original)
            if isinstance(rows,dict): rows=rows.get('locals',[])
            for local in rows:
                name=local.get('name')
                if name:
                    identity=local.get('originalSymbol')
                    if not identity or not identity.startswith(original+'#local:') or local.get('originalMethod')!=original:
                        raise RuntimeError(f'Missing stable local identity: {local}')
                    expected[identity]={'original_symbol':original,'local_name':name,'local_identity':identity}
        except Exception as error:
            failures.append({'local_method':method,'error':str(error)})
    parameter_methods={canonical(e.get('original_method') or e.get('originalMethod') or e.get('addr'))
                       for e in history if e.get('kind')=='parameters'}
    for method in parameter_methods:
        try:
            resolved=api.call('symbol.resolve',target=target,symbol=method)
            if resolved.get('contradiction') or resolved.get('origSig')!=method:
                raise RuntimeError(f'Canonical parameter-method identity mismatch: {resolved}')
            meta=api.call('code.params',target=target,symbol=method)
            if meta.get('original_method')!=method or not isinstance(meta.get('parameters'),list):
                raise RuntimeError('Parameter metadata lacks the exact original method and parameter list')
            expected.setdefault(method,{'original_symbol':method,'name':resolved['name']})['parameters']=[
                {'index':row['index'],'name':row.get('name')} for row in meta['parameters']]
        except Exception as error:
            failures.append({'parameter_method':method,'error':str(error)})
    record={'symbols':list(expected.values()),'capture_failures':failures,'history_entries':len(history),
            'historical_alias_evidence':historical_aliases()}
    durable_json(output,record)
    if failures: raise RuntimeError(f'{len(failures)} historical symbols could not be captured; see {output}')
    if not expected: raise RuntimeError('Refusing an empty native verification')
    return record

def verify_live(api,target,expected):
    failures=[]; local_cache={}
    for item in expected['symbols']:
        symbol=item['original_symbol']
        try:
            if 'local_name' in item:
                if symbol not in local_cache:
                    rows=api.call('locals.list',target=target,symbol=symbol)
                    if isinstance(rows,dict): rows=rows.get('locals',[])
                    local_cache[symbol]=rows
                rows=local_cache[symbol]
                matched=[x for x in rows if x.get('originalSymbol')==item['local_identity']]
                if len(matched)!=1 or matched[0].get('name')!=item['local_name'] or matched[0].get('originalMethod')!=symbol:
                    raise RuntimeError('Local identity/name pair not retained')
            else:
                actual=api.call('symbol.resolve',target=target,symbol=symbol)
                if actual.get('contradiction') or actual.get('origSig')!=symbol:
                    raise RuntimeError('Canonical symbol identity changed after reopen')
                if actual.get('name')!=item['name']: raise RuntimeError(f'Expected {item["name"]}, observed {actual.get("name")}')
            if 'comment' in item:
                actual=api.call('code.hover',target=target,symbol=symbol,offset=0)
                if actual.get('comment','')!=item['comment']: raise RuntimeError('Comment changed after reopen')
            if 'parameters' in item:
                actual=api.call('code.params',target=target,symbol=symbol)
                parameters=[{'index':row['index'],'name':row.get('name')} for row in actual.get('parameters',[])]
                if actual.get('original_method')!=symbol or parameters!=item['parameters']:
                    raise RuntimeError('Parameter names or indices changed after reopen')
        except Exception as error: failures.append({'original_symbol':symbol,'error':str(error)})
    for method in expected.get('coverage_methods',[]):
        try:
            current=api.call('naming.coverage',target=target,symbol=method['original_method'])
            if current.get('inventoryErrors') or current.get('inventoryFingerprint')!=method['inventory_fingerprint']:
                raise RuntimeError('Complete argument/local inventory changed after reopen')
        except Exception as error:failures.append({'coverage_method':method['original_method'],'error':str(error)})
    return {'ok':not failures,'checked':len(expected['symbols'])+len(expected.get('coverage_methods',[])),'failures':failures}

def wait_ready(api,target):
    while True:
        info=api.call('target.info',target=target)
        if info['state']=='ready':
            if info.get('replayFailed'): raise RuntimeError(f'History replay failed: {info}')
            return info
        if info['state'] in ('closed','error'): raise RuntimeError(f'Target did not open: {info}')
        time.sleep(1)

def native_verify(db,expected_file,folder,checkpoint):
    copied=folder/'native-copy'; copied.mkdir(mode=0o700)
    copy=copied/db.name
    # Restore from the verified local archive. No off-host transfer is performed.
    # Deliberately omit history sidecars so they cannot hide a failed native save.
    with tarfile.open(checkpoint/'checkpoint.tar','r') as archive:
        for filename in (db.name,db.name+'.key'):
            member=archive.getmember('target/'+filename)
            with archive.extractfile(member) as src, (copied/filename).open('xb') as dst:
                shutil.copyfileobj(src,dst)
                dst.flush(); os.fsync(dst.fileno())
    if sha256(copy)!=sha256(db): raise RuntimeError('Native verification copy digest mismatch')
    java_home=Path(os.environ["JAVA_HOME"])
    classes=ROOT/'verification-classes'; classes.mkdir(exist_ok=True)
    java_cp=str(REPO/'backend/build/classes/java/main')+':'+str(JEB/'bin/app/*')
    subprocess.run([str(java_home/'bin/javac'),'-cp',java_cp,'-d',str(classes),str(Path(__file__).with_name("NativeSnapshotVerifier.java"))],check=True)
    isolated=folder/'verification-java-home'; isolated.mkdir(mode=0o700)
    tmp=folder/'verification-tmp'; tmp.mkdir(mode=0o700)
    receipt=folder/'native-verification.json'
    options=JEB/'jvmopt.txt'
    installed_options=options.read_text().split() if options.is_file() else []
    command=[str(java_home/'bin/java'),*installed_options,'-Xms1g',"-Xmx"+os.environ.get("JEEVES_VERIFY_XMX", "24g"),'-XX:+UseG1GC',
             '-Djava.awt.headless=true','-Djava.security.manager=allow',
             '-Duser.home='+str(isolated),'-Djava.io.tmpdir='+str(tmp),
             '-Dworkbench.jebHome='+str(JEB),'--add-opens','java.base/java.lang=ALL-UNNAMED',
             '-cp',str(classes)+':'+java_cp,
             'dev.workbench.it.NativeSnapshotVerifier',str(copy),str(expected_file),str(receipt)]
    env=dict(os.environ,JEB_HOME=str(JEB),WORKBENCH_MOCK='0',WORKBENCH_COURSE='0',
             WORKBENCH_HOME=str(folder/'verification-home'),WORKBENCH_NOCG='1',WORKBENCH_INDEX_MODE='on-demand')
    with (folder/'native-verifier.log').open('w') as log:
        result=subprocess.run(command,env=env,cwd=REPO,stdout=log,stderr=subprocess.STDOUT)
    if result.returncode: raise RuntimeError(f'Native verifier exited {result.returncode}; see {folder}/native-verifier.log')
    verified=json.loads(receipt.read_text())
    if not verified.get('ok') or not verified.get('native_only') or verified.get('history_replayed'):
        raise RuntimeError('Native-only verification failed')
    return verified

def ensure_target(api, artifact):
    path=str(Path(artifact).resolve())
    for row in api.call('target.list'):
        if row.get('path')==path and row.get('state') not in ('closed','error'):
            wait_ready(api,row['id']);return row['id']
    opened=api.call('target.open',path=path,engine='jeb')
    wait_ready(api,opened['id']);return opened['id']


def run_barrier(api, round_dir, artifact):
    """Resume only idempotent stages; a fresh save precedes every new archive.

    Native verification uses an archive copy with no edit-history sidecar. A
    failed stage retains its files and receipt; no later session may pass it.
    """
    round_dir=Path(round_dir).resolve();artifact=Path(artifact).resolve()
    if not round_dir.is_relative_to(ROOT) or not artifact.is_relative_to(ROOT):
        raise ValueError('Round and artifact must be inside campaign root')
    # Campaign rounds already exist; a maintenance save starts a new round.
    round_dir.mkdir(parents=True,mode=0o700,exist_ok=True)
    folder=round_dir/'barrier';folder.mkdir(mode=0o700,exist_ok=True)
    state_path=folder/'state.json'
    state=json.loads(state_path.read_text()) if state_path.exists() else {'phase':'capture','started_at':time.time()}
    def phase(value,**extra):
        state.update(phase=value,updated_at=time.time(),**extra);state.pop('error',None)
        durable_json(state_path,state)
    try:
        while True:
            missions=api.call('agent.missions')
            if any(m.get('state') not in ('done','blocked','stopped','interrupted')
                   or m.get('loopActive') or m.get('cancellationUnconfirmed') for m in missions):
                time.sleep(1);continue
            calls=api.call('ops.active')
            if isinstance(calls,dict):calls=calls.get('calls',[])
            if not [x for x in calls if str(x.get('actor','')).startswith(('llm:','agent:'))]:break
            time.sleep(1)
        if sha256(artifact)!=APK_SHA:raise RuntimeError('Pinned executable hash mismatch')
        if state['phase']=='capture':
            target=ensure_target(api,artifact);info=wait_ready(api,target)
            history=json.loads(Path(str(artifact)+'.workbench/history.json').read_text())
            durable_json(folder/'history-before-save.json',history)
            expected=capture_expected(api,target,history,folder/'expected.json')
            coverage_path=round_dir/'coverage-before.json'
            if coverage_path.exists():
                from naming_coverage import native_expectations
                expected['coverage_methods']=native_expectations(json.loads(coverage_path.read_text()))
                durable_json(folder/'expected.json',expected)
            phase('save',target=target,db=info['jdb2'],history_entries=len(history))
        if state['phase']=='save':
            target=ensure_target(api,artifact)
            # Retrying this stage may save again after a lost acknowledgement;
            # saving is idempotent and no agents run across this barrier.
            prior=api.call('target.saveStatus',target=target)
            while prior.get('saving'):
                time.sleep(1);prior=api.call('target.saveStatus',target=target)
            saved=api.call('target.save',target=target,**{'async':True})
            started=saved.get('startedAt')
            if not started:raise RuntimeError('Save did not return a fresh identity')
            while True:
                saved=api.call('target.saveStatus',target=target);last=saved.get('last',{})
                if not saved.get('saving') and last.get('at',0)>=started:
                    if not last.get('ok'):raise RuntimeError('Native save failed: '+str(last))
                    break
                time.sleep(1)
            durable_json(folder/'save.json',saved)
            phase('close',target=target)
        if state['phase']=='close':
            for row in api.call('target.list'):
                if row.get('path')==str(artifact) and row.get('state')!='closed':
                    api.call('target.close',target=row['id'],save=True,**{'async':False})
            if Path(state['db']+'.lock').exists():raise RuntimeError('Native database is still locked')
            phase('archive')
        if state['phase']=='archive':
            checkpoint=ROOT/'checkpoints'/round_dir.name
            if checkpoint.exists() and not (checkpoint/'receipt.json').exists():
                # Interrupted copy stays as evidence, never used as a backup.
                checkpoint.rename(checkpoint.with_name(checkpoint.name+'.incomplete-'+str(time.time_ns())))
            if (checkpoint/'receipt.json').exists():
                record=json.loads((checkpoint/'receipt.json').read_text())
                if sha256(Path(record['archive']))!=record['sha256']:raise RuntimeError('Existing archive hash mismatch')
            else:
                record=create_checkpoint(checkpoint,{'target':artifact.parent,'project-state':LIVE_HOME/'projects','round':round_dir})
            phase('native',checkpoint=str(checkpoint),backup_sha256=record['sha256'])
        if state['phase']=='native':
            attempt=folder/('native-'+str(time.time_ns()));attempt.mkdir(mode=0o700)
            result=native_verify(Path(state['db']),folder/'expected.json',attempt,Path(state['checkpoint']))
            phase('live',native_receipt=str(attempt/'native-verification.json'),native_checks=result['checked'])
        if state['phase']=='live':
            target=ensure_target(api,artifact)
            expected=json.loads((folder/'expected.json').read_text())
            result=verify_live(api,target,expected);durable_json(folder/'live-verification.json',result)
            if not result['ok']:raise RuntimeError('Reopened live database differs from saved expectations')
            phase('complete',target=target,ok=True,live_checks=result['checked'],database_sha256=sha256(Path(state['db'])))
            durable_json(folder/'receipt.json',state)
        if state['phase']!='complete':raise RuntimeError('Unknown barrier phase: '+str(state['phase']))
        # A controller/server restart after verification needs a current target id.
        state['target']=ensure_target(api,artifact)
        return state
    except BaseException as error:
        state.update(error=str(error),updated_at=time.time());durable_json(state_path,state)
        raise
