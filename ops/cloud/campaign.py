#!/usr/bin/env python3
"""Cloud-resident catalog runner with configured model slots. No SSH, model keys, or laptop loop."""
from __future__ import annotations
import argparse, fcntl, json, math, os, sqlite3, time, uuid
from pathlib import Path
from barrier import API, ROOT, APK_SHA, ensure_target, run_barrier
from checkpoint_backup import durable_json, sha256
from quality import review_session
from naming_coverage import audit_session
from maintenance import wait_at_boundary
from credit import mission_exhaustion, review_exhaustion, record_exhaustion
import review_debt

HERE=Path(__file__).resolve().parent
TERMINAL={'done','blocked','stopped','interrupted'}

def settled(mission):
    return mission.get('state') in TERMINAL and not mission.get('loopActive') and not mission.get('cancellationUnconfirmed')

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

def claim_session(db,models,focus=None,repairs=None,priority=None,revisit_completed=False,review_debt_first=False):
    # Recovery owns its persisted assignments even when the next-session config changes.
    # One transaction reserves every slot; original descriptors never fold case.
    with db:
        existing=db.execute("SELECT * FROM sessions WHERE phase!='complete' ORDER BY id LIMIT 1").fetchone()
        if existing:return dict(existing)
        if not isinstance(models,list) or not models or any(not isinstance(m,str) or not m.strip() for m in models):
            raise ValueError('Configure a nonempty list of model slots')
        count=len(models)
        focused={r['original_class']:r for r in focus} if focus is not None else None
        repair_by_class={}
        for repair in repairs or []:
            original=repair.get('original_class');after=repair.get('after_session')
            if (not isinstance(original,str) or original in repair_by_class
                    or type(after) is not int or after<0 or not isinstance(repair.get('goal'),str) or not repair['goal'].strip()):
                raise ValueError('Repair requests require a unique original class, source session and concrete goal')
            if not db.execute('SELECT 1 FROM classes WHERE original=?',(original,)).fetchone():raise ValueError('Unknown repair class: '+original)
            if focused is not None and original not in focused:raise ValueError('Repair class is outside the FMessage focus: '+original)
            repair_by_class[original]=repair
        def repair_for(row):
            request=repair_by_class.get(row['original'])
            # A request applies to the observed session, once. A later assignment
            # remains ordinarily eligible but cannot consume this request twice.
            return request if request and (row['session'] or 0)<=request['after_session'] else None
        debt_rows=(db.execute("SELECT * FROM classes WHERE state='needs_review' ORDER BY coalesce(session,0), original COLLATE BINARY").fetchall()
                   if review_debt_first else [])
        repairing=bool(debt_rows)
        if repairing:
            rows=debt_rows[:count]
        else:
            if focused is not None:
                rank={s:i for i,s in enumerate(focused)}
                eligible=[db.execute('SELECT * FROM classes WHERE original=?',(s,)).fetchone() for s in focused]
                if any(r is None for r in eligible):raise RuntimeError('Focus contains an unknown class')
                eligible.sort(key=lambda r:(0 if repair_for(r) else 1,r['attempts'],rank[r['original']]))
                rows=[r for r in eligible if r['state'] in ('pending','needs_review')][:count]
            else:
                rows=db.execute("SELECT * FROM classes WHERE state IN ('pending','needs_review') ORDER BY attempts, original COLLATE BINARY").fetchall()
                rows.sort(key=lambda r:(0 if repair_for(r) else 1,r['attempts'],r['original']))
                rows=rows[:count]
            priority=priority or []
            if len(priority)>len(models) or len(set(priority))!=len(priority):
                raise ValueError('First-session priorities must be distinct and fit the configured model slots')
            preferred=[]
            for original in priority:
                row=db.execute('SELECT * FROM classes WHERE original=?',(original,)).fetchone()
                if row is None or (focused is not None and original not in focused):
                    raise ValueError('Priority class must exist in the selected focus: '+original)
                if row['state'] not in ('pending','needs_review','done'):
                    raise ValueError('Priority class is already claimed: '+original)
                preferred.append(row)
            rows=(preferred+[r for r in rows if r['original'] not in priority])[:count]
        if not rows and revisit_completed:
            rows=([r for r in eligible if r['state']=='done'][:count] if focused is not None else
                  db.execute("SELECT * FROM classes WHERE state='done' ORDER BY attempts, original COLLATE BINARY LIMIT ?",(count,)).fetchall())
        if not rows:return None
        if len(rows)<count:
            # Finish the last partial page with distinct previously completed
            # classes for a verification pass, retaining the configured number of agents.
            selected={r['original'] for r in rows}
            completed=(eligible if focused is not None and not repairing else
                       db.execute("SELECT * FROM classes WHERE state='done' ORDER BY attempts, original COLLATE BINARY").fetchall())
            rows+=[r for r in completed if r['state']=='done' and r['original'] not in selected][:count-len(rows)]
            if len(rows)!=count:raise RuntimeError(f'Fewer than {count} previously worked classes; untouched work remains held' if repairing else f'The selected scope contains fewer than {count} classes')
        slots=[{'original_class':r['original'],'current_name':r['name'],'model':models[i],
                'assignment_id':str(uuid.uuid4()),'spawn':'prepared','mission':None,
                'assignment_kind':'repair' if repairing and r['state']=='needs_review' else ('verification' if r['state']=='done' else 'discovery')} for i,r in enumerate(rows)]
        for slot,row in zip(slots,rows):
            if row['result']:slot['prior_result']=json.loads(row['result'])
            repair=repair_for(row)
            if repairing and row['state']=='needs_review':slot['repair_request']=review_debt.repair_request(row,repair)
            elif repair:slot['repair_request']=repair
            if repairing and row['state']=='done':
                slot['repair_request']={'goal':'Verification of an already completed class to fill this repair session. Preserve supported names, recheck all coverage and report any concrete defect. No untouched class is assigned.'}
        if focused is not None:
            from focus import context
            for slot in slots:
                row=focused.get(slot['original_class'])
                if row is None:continue  # All existing debt is in scope, including pre-FMessage work.
                slot.update(current_name=row['current_name'],research_focus=context(row))
        cursor=db.execute("INSERT INTO sessions(phase,slots,created) VALUES('agents',?,?)",(json.dumps(slots),time.time()))
        sid=cursor.lastrowid
        db.executemany("UPDATE classes SET state='claimed',attempts=attempts+1,session=? WHERE original=?",[(sid,r['original']) for r in rows])
        return dict(db.execute('SELECT * FROM sessions WHERE id=?',(sid,)).fetchone())

def save_slots(db,sid,slots):
    with db:db.execute('UPDATE sessions SET slots=? WHERE id=?',(json.dumps(slots),sid))

def prompt(slot,target):
    text=(HERE/'class-prompt.txt').read_text()
    values={key:str(slot[key]) for key in ('original_class','current_name','assignment_id')}
    values['target']=target
    for key,label in [('research_focus','FMessage assignment context'),('prior_result','Prior session evidence and independent review'),('repair_request','Requested follow-up')]:
        values[key]=(label+': '+json.dumps(slot[key],ensure_ascii=False)) if slot.get(key) else ''
    # Replace template fields once; braces in quoted code/evidence remain data.
    import re
    text=re.sub(r'\{(original_class|current_name|assignment_id|target|research_focus|prior_result|repair_request)\}',lambda m:values[m.group(1)],text)
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
        baseline=folder/'baseline-history.json'
        if not baseline.exists():durable_json(baseline,json.loads(Path(str(artifact)+'.workbench/history.json').read_text()))
        target=ensure_target(api,artifact)
        while True:
            missions=api.call('agent.missions')
            by_id={m['id']:m for m in missions}
            for slot in slots:
                if slot['mission']:continue
                matched=[m for m in missions if ('Campaign assignment: '+slot['assignment_id']+'.') in m.get('goal','')]
                if len(matched)>1:raise RuntimeError('Duplicate assignment missions; reconciliation required')
                if matched:
                    slot.update(mission=matched[0]['id'],spawn='acknowledged',coverage_actor='llm:'+matched[0]['agentId']);save_slots(db,sid,slots);continue
                if slot['spawn']=='ambiguous':
                    raise RuntimeError('Ambiguous spawn has no mission receipt; preserved for reconciliation: '+slot['assignment_id'])
                # The catalog/focus names were captured at discovery. Previous
                # agents may have renamed this class since then; resolve its
                # stable original descriptor before claiming the label is current.
                current=api.call('symbol.resolve',target=target,symbol=slot['original_class'])
                name=current.get('name')
                if (current.get('origSig')!=slot['original_class'] or current.get('contradiction')
                        or not isinstance(name,str) or not name.strip()):
                    raise RuntimeError('Cannot resolve current assignment identity: '+slot['original_class'])
                slot['current_name']=name
                slot['spawn']='ambiguous';save_slots(db,sid,slots)
                # Never blindly replay a mutation after a lost HTTP acknowledgement.
                mission=api.call('agent.spawn',target=target,scope='target:'+target,model=slot['model'],effort='max',goal=prompt(slot,target))
                if not mission.get('turnsUnlimited') or not mission.get('budgetUnlimited'):
                    raise RuntimeError('Headless unlimited settings are not effective')
                slot.update(mission=mission['id'],spawn='acknowledged',coverage_actor='llm:'+mission['agentId']);save_slots(db,sid,slots)
            missions=api.call('agent.missions');by_id={m['id']:m for m in missions}
            if all(settled(by_id.get(s['mission'],{})) for s in slots):break
            durable_json(root/'status.json',{'phase':'agents','session':sid,'slots':[dict(s,state=by_id.get(s['mission'],{}).get('state')) for s in slots],'updated_at':time.time()})
            time.sleep(5)
        for slot in slots:
            m=by_id[slot['mission']]
            slot['coverage_actor']='llm:'+m['agentId']
            exhaustion=mission_exhaustion(api,m,slot['model'])
            if exhaustion:record_exhaustion(root,sid,[exhaustion])
            result=completion(api,slot['mission'],slot['original_class']) if m['state']=='done' else {'status':'needs_review','unresolved':[m.get('stopReason') or m.get('reason') or m['state']]}
            slot['result']=result;slot['usage']={k:m.get(k) for k in ['inputTokens','outputTokens','cacheHitTokens','turnsDone','state']}
        save_slots(db,sid,slots)
        durable_json(folder/'assignments.json',slots)
        audit_session(api,target,slots,folder,'before')
        with db:db.execute("UPDATE sessions SET phase='barrier' WHERE id=?",(sid,))
    durable_json(root/'status.json',{'phase':'barrier','session':sid,'updated_at':time.time()})
    snapshot=sqlite3.connect(folder/'catalog-before-save.sqlite3')
    try:db.backup(snapshot)
    finally:snapshot.close()
    receipt=run_barrier(api,folder,artifact)
    if not receipt.get('ok'):raise RuntimeError('Verification barrier not complete')
    coverage=audit_session(api,receipt['target'],slots,folder,'after')
    covered={c['original_class']:c for c in coverage['classes']}
    with db:db.execute("UPDATE sessions SET phase='quality' WHERE id=?",(sid,))
    durable_json(root/'status.json',{'phase':'quality','session':sid,'updated_at':time.time()})
    quality=review_session(api,receipt['target'],slots,folder,artifact)
    record_exhaustion(root,sid,review_exhaustion(folder/'quality'))
    reviews={r['original_class']:r for r in quality.get('classes',[])}
    receipt=dict(receipt,quality=str(folder/'quality/receipt.json'))
    with db:
        for slot in slots:
            result=slot['result'];status='done' if result.get('status')=='complete' else 'needs_review'
            review=reviews.get(slot['original_class'],{})
            audit=covered.get(slot['original_class'],{})
            if audit.get('status')!='complete' or quality.get('status')!='complete' or review.get('collection_errors') or review.get('review',{}).get('verdict')!='acceptable':status='needs_review'
            result=dict(result,quality_review=review.get('review',{}),possible_unnamed=review.get('possible_unnamed'),
                        coverage={k:v for k,v in audit.items() if k!='methods'},
                        review_debt=review_debt.remaining_findings(slot,review.get('review',{}) if quality.get('status')=='complete' and not review.get('collection_errors') else {}),
                        review_debt_source_session=sid)
            if result['review_debt']:status='needs_review'
            db.execute('UPDATE classes SET state=?,result=? WHERE original=?',(status,json.dumps(result),slot['original_class']))
        db.execute("UPDATE sessions SET phase='complete',receipt=? WHERE id=?",(json.dumps(receipt),sid))
    return receipt

def invocation_state(root,db,*,rounds=None,hours=None,continuous=False):
    path=root/'invocation.json'
    if path.exists():return json.loads(path.read_text())
    if sum((rounds is not None,hours is not None,bool(continuous)))!=1:
        raise ValueError('Specify exactly one of --rounds, --hours or --continuous')
    now=time.time()
    invocation={'phase':'running','started_at':now,
                'start_completed':db.execute("SELECT count(*) FROM sessions WHERE phase='complete'").fetchone()[0]}
    invocation['start_session_id']=db.execute('SELECT coalesce(max(id),0) FROM sessions').fetchone()[0]
    if continuous:invocation.update(mode='continuous')
    elif hours is not None:
        if not math.isfinite(hours) or hours<=0:raise ValueError('--hours must be positive and finite')
        invocation.update(mode='window',hours=hours,deadline_at=now+hours*3600)
    elif rounds is not None and rounds>=1:invocation.update(mode='rounds',rounds=rounds)
    else:raise ValueError('Specify positive --rounds, positive --hours or --continuous')
    durable_json(path,invocation)
    return invocation

def boundary_reason(root,db,invocation,now):
    # A deadline or stop request only prevents claiming another session. Recovery,
    # saving and reviewing the current session must finish, even after the deadline.
    if db.execute("SELECT 1 FROM sessions WHERE phase!='complete'").fetchone():return None
    if (root/'stop-after-session').exists():return 'Graceful stop requested; active session saved and reviewed'
    credit=root/'credit-stop.json'
    if credit.exists():
        evidence=json.loads(credit.read_text())
        if evidence['session']>invocation.get('start_session_id',invocation['start_completed']):
            providers=', '.join(sorted({r['provider'] for r in evidence['failures']}))
            return 'Provider credit exhausted ('+providers+'); active session saved and review outcomes recorded'
    if invocation.get('mode')=='continuous':return None
    if invocation.get('mode')=='window':
        return 'Run window finished; active session saved and reviewed' if now>=invocation['deadline_at'] else None
    completed=db.execute("SELECT count(*) FROM sessions WHERE phase='complete'").fetchone()[0]
    return 'Requested sessions saved and reviewed' if completed>=invocation['start_completed']+invocation['rounds'] else None

def run_invocation(api,db,config,artifact,root,invocation,before_session=None):
    while True:
        if before_session:before_session()
        reason=boundary_reason(root,db,invocation,time.time())
        if reason:break
        focus=None
        recovering=db.execute("SELECT 1 FROM sessions WHERE phase!='complete'").fetchone() is not None
        if config.get('focus_manifest') and not recovering:
            from focus import load
            focus=load(config['focus_manifest'],db,APK_SHA,required_slots=len(config['models']))
        if before_session:before_session()
        reason=boundary_reason(root,db,invocation,time.time())
        if reason:break
        repairs=[]
        if config.get('repair_queue'):
            path=Path(config['repair_queue']).resolve()
            if not path.is_relative_to(root.resolve()):raise ValueError('Repair queue must be inside the campaign root')
            repairs=json.loads(path.read_text())
            if not isinstance(repairs,list):raise ValueError('Repair queue must contain a list')
        if config.get('review_debt_first'):review_debt.seed_backlog(db,root)
        first=db.execute("SELECT count(*) FROM sessions WHERE phase='complete'").fetchone()[0]==invocation['start_completed']
        session=claim_session(db,config['models'],focus,repairs,
                              priority=config.get('priority_classes') if first else None,
                              revisit_completed=invocation.get('mode')=='continuous',
                              review_debt_first=config.get('review_debt_first',False))
        if session is None:
            reason='All eligible classes have completed the coverage and quality checks'
            break
        try:
            # Target ops are scoped to the selected project, even with an explicit
            # target ID. A user viewing another project must not strand recovery.
            api.call('project.open',name=config['project'])
            run_session(api,db,session,artifact,root)
        except Exception as error:
            durable_json(root/'status.json',{'phase':'retrying','session':session['id'],'error':str(error),'updated_at':time.time()})
            print('Retained state; retrying after 30 seconds:',error,flush=True);time.sleep(30)
    invocation.update(phase='complete',completed_at=time.time(),reason=reason)
    durable_json(root/'invocation.json',invocation)
    durable_json(root/'status.json',{'phase':'idle','updated_at':time.time(),'reason':reason})

def main():
    parser=argparse.ArgumentParser();parser.add_argument('command',choices=['catalog','run','status'])
    parser.add_argument('--config',required=True)
    duration=parser.add_mutually_exclusive_group()
    duration.add_argument('--rounds',type=int)
    duration.add_argument('--hours',type=float,help='Start new sessions for this many hours; finish the active session before pausing')
    duration.add_argument('--continuous',action='store_true',help='Continue configured agent sessions until a graceful stop or exhausted provider credit')
    args=parser.parse_args();config=json.loads(Path(args.config).read_text())
    root=Path(config['root']).resolve()
    if root!=ROOT:raise ValueError('Config root must match JEEVES_CAMPAIGN_ROOT')
    root.mkdir(parents=True,exist_ok=True);db=catalog_db(root)
    if args.command=='status':
        print(json.dumps({'classes':dict(db.execute('SELECT state,count(*) FROM classes GROUP BY state').fetchall()),'sessions':[dict(r) for r in db.execute('SELECT id,phase,created FROM sessions ORDER BY id')]}));return
    lock=(root/'controller.lock').open('a');fcntl.flock(lock,fcntl.LOCK_EX|fcntl.LOCK_NB)
    api=API(config['url']);artifact=Path(config['artifact'])
    if sha256(artifact)!=APK_SHA:raise ValueError('APK identity mismatch')
    if args.command=='catalog':
        api.call('project.open',name=config['project'])
        target=ensure_target(api,artifact)
        count=catalog(api,target,db);durable_json(root/'catalog-receipt.json',{'classes':count,'apk_sha256':APK_SHA,'at':time.time()});print('Catalog verified:',count);return
    invocation=invocation_state(root,db,rounds=args.rounds,hours=args.hours,continuous=args.continuous)
    try:
        # On restart this must precede project.open or any other native call: the
        # deployer may have deliberately closed the database for verification.
        before_session=lambda:wait_at_boundary(root,db,invocation,lock)
        before_session()
        if invocation['phase']=='complete':
            print('Requested invocation already complete. A new invocation requires an explicit operator action.',flush=True)
            return
        print('Running durable invocation:',json.dumps(invocation),flush=True)
        run_invocation(api,db,config,artifact,root,invocation,before_session)
    finally:lock.close();db.close()

if __name__=='__main__':main()
