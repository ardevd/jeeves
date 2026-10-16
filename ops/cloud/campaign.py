#!/usr/bin/env python3
"""Cloud-resident eight-agent catalog runner. No SSH, model keys, or laptop loop."""
from __future__ import annotations
import argparse, fcntl, json, os, sqlite3, time, uuid
from pathlib import Path
from barrier import API, ROOT, APK_SHA, ensure_target, run_barrier
from checkpoint_backup import durable_json, sha256

HERE=Path(__file__).resolve().parent
TERMINAL={'done','blocked','stopped','interrupted'}

def catalog_db(root):
    db=sqlite3.connect(root/'catalog.sqlite3');db.row_factory=sqlite3.Row
    db.execute('PRAGMA journal_mode=WAL');db.execute('PRAGMA synchronous=FULL')
    db.executescript('''
    CREATE TABLE IF NOT EXISTS classes(original TEXT PRIMARY KEY COLLATE BINARY,
      name TEXT NOT NULL, state TEXT NOT NULL DEFAULT 'pending', attempts INTEGER NOT NULL DEFAULT 0,
      session INTEGER, result TEXT);
    CREATE TABLE IF NOT EXISTS sessions(id INTEGER PRIMARY KEY AUTOINCREMENT,
      phase TEXT NOT NULL, slots TEXT NOT NULL, created REAL NOT NULL, receipt TEXT);
    ''')
    return db

def catalog(api,target,db):
    expected=api.call('target.info',target=target)['classes']
    pending=[''];seen=set();classes={}
    while pending:
        prefix=pending.pop()
        if prefix in seen:continue
        seen.add(prefix)
        page=api.call('symbol.tree',target=target,prefix=prefix,limit=expected)
        if page.get('truncated'):raise RuntimeError('Catalog listing truncated: '+prefix)
        for row in page['items']:
            if row['kind']=='package':pending.append(row['prefix'])
            else:
                original=row.get('origSig')
                if not original:raise RuntimeError('Class has no original identity: '+str(row))
                if original in classes:raise RuntimeError('Duplicate class identity: '+original)
                classes[original]=row.get('fqcn') or row['name']
    if len(classes)!=expected:raise RuntimeError(f'Catalog count mismatch: {len(classes)} != {expected}')
    with db:
        for original,name in classes.items():
            db.execute('INSERT INTO classes(original,name) VALUES(?,?) ON CONFLICT(original) DO UPDATE SET name=excluded.name',(original,name))
    return expected

def claim_session(db,models):
    if len(models)!=8:raise ValueError('This campaign requires exactly eight model slots')
    # One transaction reserves all eight; binary original descriptors never fold case.
    with db:
        existing=db.execute("SELECT * FROM sessions WHERE phase!='complete' ORDER BY id LIMIT 1").fetchone()
        if existing:return dict(existing)
        rows=db.execute("SELECT * FROM classes WHERE state IN ('pending','needs_review') ORDER BY attempts, original COLLATE BINARY LIMIT 8").fetchall()
        if not rows:return None
        if len(rows)<8:
            # Finish the last partial page with distinct previously completed
            # classes for a verification pass, retaining exactly eight agents.
            rows+=db.execute("SELECT * FROM classes WHERE state='done' ORDER BY attempts, original COLLATE BINARY LIMIT ?",(8-len(rows),)).fetchall()
            if len(rows)!=8:raise RuntimeError('The target itself contains fewer than eight classes')
        slots=[{'original_class':r['original'],'current_name':r['name'],'model':models[i],
                'assignment_id':str(uuid.uuid4()),'spawn':'prepared','mission':None} for i,r in enumerate(rows)]
        cursor=db.execute("INSERT INTO sessions(phase,slots,created) VALUES('agents',?,?)",(json.dumps(slots),time.time()))
        sid=cursor.lastrowid
        db.executemany("UPDATE classes SET state='claimed',attempts=attempts+1,session=? WHERE original=?",[(sid,r['original']) for r in rows])
        return dict(db.execute('SELECT * FROM sessions WHERE id=?',(sid,)).fetchone())

def save_slots(db,sid,slots):
    with db:db.execute('UPDATE sessions SET slots=? WHERE id=?',(json.dumps(slots),sid))

def prompt(slot,target):
    text=(HERE/'class-prompt.txt').read_text()
    for key,value in dict(slot,target=target).items():text=text.replace('{'+key+'}',str(value))
    return text

def completion(api,mission,original):
    claim=None
    for turn in api.call('agent.turns',mission=mission):
        rec=api.call('agent.turn',mission=mission,turn=turn['turn'])
        # Read the durable assistant text, never trust a progress/activity label.
        for line in str(rec.get('text','')).splitlines():
            if line.startswith('CLASS_RESULT:'):
                try:claim=json.loads(line.split(':',1)[1].strip())
                except ValueError:pass
    fields=['fields_seen','methods_seen','arguments_seen','locals_seen','renamed','retained']
    valid=isinstance(claim,dict) and claim.get('original_class')==original and all(type(claim.get(k))==int and claim[k]>=0 for k in fields)
    if not valid:return {'original_class':original,'status':'needs_review','unresolved':['Missing or malformed final class receipt']}
    if claim.get('status')!='complete' or claim.get('unresolved'):claim['status']='needs_review'
    return claim

def run_session(api,db,session,artifact,root):
    sid=session['id'];folder=root/'rounds'/f'cloud-{sid:06d}';folder.mkdir(parents=True,exist_ok=True)
    slots=json.loads(session['slots'])
    if session['phase']=='agents':
        target=ensure_target(api,artifact)
        while True:
            missions=api.call('agent.missions')
            by_id={m['id']:m for m in missions}
            for slot in slots:
                if slot['mission']:continue
                matched=[m for m in missions if ('Campaign assignment: '+slot['assignment_id']+'.') in m.get('goal','')]
                if len(matched)>1:raise RuntimeError('Duplicate assignment missions; reconciliation required')
                if matched:
                    slot.update(mission=matched[0]['id'],spawn='acknowledged');save_slots(db,sid,slots);continue
                if slot['spawn']=='ambiguous':
                    raise RuntimeError('Ambiguous spawn has no mission receipt; preserved for reconciliation: '+slot['assignment_id'])
                slot['spawn']='ambiguous';save_slots(db,sid,slots)
                # Never blindly replay a mutation after a lost HTTP acknowledgement.
                mission=api.call('agent.spawn',target=target,scope='target:'+target,model=slot['model'],effort='max',goal=prompt(slot,target))
                if not mission.get('turnsUnlimited') or not mission.get('budgetUnlimited'):
                    raise RuntimeError('Headless unlimited settings are not effective')
                slot.update(mission=mission['id'],spawn='acknowledged');save_slots(db,sid,slots)
            missions=api.call('agent.missions');by_id={m['id']:m for m in missions}
            if all(by_id.get(s['mission'],{}).get('state') in TERMINAL for s in slots):break
            durable_json(root/'status.json',{'phase':'agents','session':sid,'slots':[dict(s,state=by_id.get(s['mission'],{}).get('state')) for s in slots],'updated_at':time.time()})
            time.sleep(5)
        for slot in slots:
            m=by_id[slot['mission']]
            result=completion(api,slot['mission'],slot['original_class']) if m['state']=='done' else {'status':'needs_review','unresolved':[m.get('reason') or m['state']]}
            slot['result']=result;slot['usage']={k:m.get(k) for k in ['inputTokens','outputTokens','cacheHitTokens','turnsDone','state']}
        save_slots(db,sid,slots)
        durable_json(folder/'assignments.json',slots)
        with db:db.execute("UPDATE sessions SET phase='barrier' WHERE id=?",(sid,))
    durable_json(root/'status.json',{'phase':'barrier','session':sid,'updated_at':time.time()})
    snapshot=sqlite3.connect(folder/'catalog-before-save.sqlite3')
    try:db.backup(snapshot)
    finally:snapshot.close()
    receipt=run_barrier(api,folder,artifact)
    if not receipt.get('ok'):raise RuntimeError('Verification barrier not complete')
    with db:
        for slot in slots:
            result=slot['result'];status='done' if result.get('status')=='complete' else 'needs_review'
            db.execute('UPDATE classes SET state=?,result=? WHERE original=?',(status,json.dumps(result),slot['original_class']))
        db.execute("UPDATE sessions SET phase='complete',receipt=? WHERE id=?",(json.dumps(receipt),sid))
    return receipt

def main():
    parser=argparse.ArgumentParser();parser.add_argument('command',choices=['catalog','run','status'])
    parser.add_argument('--config',required=True);parser.add_argument('--rounds',type=int)
    args=parser.parse_args();config=json.loads(Path(args.config).read_text())
    root=Path(config['root']).resolve()
    if root!=ROOT:raise ValueError('Config root must match JEEVES_CAMPAIGN_ROOT')
    root.mkdir(parents=True,exist_ok=True);db=catalog_db(root)
    if args.command=='status':
        print(json.dumps({'classes':dict(db.execute('SELECT state,count(*) FROM classes GROUP BY state').fetchall()),'sessions':[dict(r) for r in db.execute('SELECT id,phase,created FROM sessions ORDER BY id')]}));return
    lock=(root/'controller.lock').open('a');fcntl.flock(lock,fcntl.LOCK_EX|fcntl.LOCK_NB)
    api=API(config['url']);artifact=Path(config['artifact'])
    if sha256(artifact)!=APK_SHA:raise ValueError('APK identity mismatch')
    api.call('project.open',name=config['project'])
    if args.command=='catalog':
        target=ensure_target(api,artifact)
        count=catalog(api,target,db);durable_json(root/'catalog-receipt.json',{'classes':count,'apk_sha256':APK_SHA,'at':time.time()});print('Catalog verified:',count);return
    if args.rounds is None or args.rounds<1:raise ValueError('Specify the intended number of eight-agent sessions with --rounds')
    invocation_path=root/'invocation.json'
    if invocation_path.exists():
        invocation=json.loads(invocation_path.read_text())
        if invocation['phase']=='complete':invocation=None
    else:invocation=None
    if invocation is None:
        baseline=db.execute("SELECT count(*) FROM sessions WHERE phase='complete'").fetchone()[0]
        invocation={'phase':'running','start_completed':baseline,'rounds':args.rounds};durable_json(invocation_path,invocation)
    while db.execute("SELECT count(*) FROM sessions WHERE phase='complete'").fetchone()[0]<invocation['start_completed']+invocation['rounds']:
        if (root/'stop-after-session').exists() and not db.execute("SELECT 1 FROM sessions WHERE phase!='complete'").fetchone():break
        session=claim_session(db,config['models'])
        if session is None:break
        try:run_session(api,db,session,artifact,root)
        except Exception as error:
            durable_json(root/'status.json',{'phase':'retrying','session':session['id'],'error':str(error),'updated_at':time.time()})
            print('Retained state; retrying after 30 seconds:',error,flush=True);time.sleep(30)
    invocation['phase']='complete';durable_json(invocation_path,invocation)
    durable_json(root/'status.json',{'phase':'idle','updated_at':time.time(),'reason':'Requested sessions finished or graceful stop requested'})

if __name__=='__main__':main()
