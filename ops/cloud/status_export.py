#!/usr/bin/env python3
"""Export only campaign progress for the read-only laptop dashboard."""
from __future__ import annotations
import argparse, collections, json, shutil, sqlite3, time
from pathlib import Path
from barrier import API
from checkpoint_backup import durable_json
from quality import read
from naming_coverage import summary as coverage_summary
from review_debt import progress as debt_progress

def agent_owner(actor):
    if not isinstance(actor,str) or not actor.startswith(('llm:','agent:')):return None
    for suffix in (' (follows class rename)',' auto-applied',' accepted by human'):
        actor=actor.removesuffix(suffix)
    while actor.startswith(('llm:','agent:')):actor=actor.split(':',1)[1]
    return actor

def counts(entries):
    names={};comments=set()
    for row in entries:
        if row.get('undone') or row.get('discarded'):continue
        original=row.get('originalSymbol') or row.get('addr','')
        if row.get('op')=='comment':comments.add(original)
        if row.get('op')!='rename':continue
        if row.get('kind')=='parameters':
            for change in row.get('changes',[]):names[str(row.get('original_method') or original)+'#parameter:'+str(change.get('index'))]='argument'
            continue
        for target in row.get('renameTargets') or [{'originalSymbol':original}]:
            symbol=target.get('originalSymbol') or original
            kind=row.get('kind') or ('class' if '->' not in symbol else 'method' if '(' in symbol else 'field')
            if row.get('argument') is not None:kind='argument'
            names[symbol]=kind
    return {'symbols':len(names),'comments':len(comments),'kinds':dict(collections.Counter(names.values()))}

def snapshot(config,api):
    root=Path(config['root']);db=sqlite3.connect('file:'+str(root/'catalog.sqlite3')+'?mode=ro',uri=True);db.row_factory=sqlite3.Row
    try:
        catalog=dict(db.execute('SELECT state,count(*) FROM classes GROUP BY state').fetchall())
        sessions=[dict(r) for r in db.execute('SELECT * FROM sessions ORDER BY id')]
    finally:db.close()
    state=read(root/'status.json',{'phase':'qualifying'})
    result={'at':time.time(),'state':state,'catalog':catalog,'sessions':[],'workers':[],
            'deployment':read(root/'deployment.json',{}),'errors':[],
            'disk_free_bytes':shutil.disk_usage(root).free,'invocation':read(root/'invocation.json',{}),
            'qualification':read(root/'rounds/qualification-save-barrier-20261016/barrier/state.json',{}),
            'quality':{'status':'pending','classes':[]},'quality_history':[],
            'configured_agents':len(config.get('models',[]))}
    result['review_debt']=debt_progress(catalog,sessions,config.get('review_debt_first'))
    try:missions={m['id']:m for m in api.call('agent.missions')}
    except Exception as error:missions={};result['errors'].append(str(error))
    try:
        calls=api.call('ops.active')
        if isinstance(calls,dict):calls=calls.get('calls',[])
        result['active_tools']=[{k:r.get(k) for k in ('op','actor','startedAt','elapsedMs','target')} for r in calls]
    except Exception as error:result['errors'].append(str(error))
    history=read(Path(str(config['artifact'])+'.workbench/history.json'),[])
    current_names={}
    for edit in history:
        if edit.get('op')=='rename' and edit.get('kind')=='class' and not edit.get('undone') and not edit.get('discarded'):
            current_names[edit.get('originalSymbol') or edit.get('addr')]=edit.get('newName')
    baseline=None
    for session in sessions:
        folder=root/'rounds'/f"cloud-{session['id']:06d}"
        saved=read(folder/'barrier/state.json',{})
        row={'id':session['id'],'phase':session['phase'],'created':session['created'],'barrier':saved,
             'agent_count':len(json.loads(session['slots']))}
        result['sessions'].append(row)
        quality=read(folder/'quality/receipt.json')
        if quality is None:quality={'status':read(folder/'quality/status.json',{}).get('phase','pending'),'classes':read(folder/'quality/partial.json',[])}
        if quality.get('classes') or quality.get('status')!='pending':
            result['quality_history'].append({'session':session['id'],'agent_count':row['agent_count'],'quality':quality})
        if baseline is None:baseline={r.get('id') for r in read(folder/'baseline-history.json',history)}
        if session['id']!=sessions[-1]['id']:continue
        session_baseline={r.get('id') for r in read(folder/'baseline-history.json',history)}
        session_edits=[r for r in history if r.get('id') not in session_baseline and not r.get('undone') and not r.get('discarded')]
        for slot in json.loads(session['slots']):
            mission=missions.get(slot.get('mission'),{})
            worker={k:mission.get(k) for k in ('id','agentId','state','displayState','loopActive','cancellationUnconfirmed','turnsDone','inputTokens','outputTokens','cacheHitTokens','writes','reason','stopReason','progress','startedAt')}
            worker.update(original_class=slot['original_class'],current_name=current_names.get(slot['original_class']) or slot['current_name'],model=slot['model'],result=slot.get('result'),assignment_kind=slot.get('assignment_kind'))
            owner=mission.get('agentId') or agent_owner(slot.get('coverage_actor'))
            applied=[r for r in session_edits if owner and agent_owner(r.get('actor'))==owner]
            worker.update(apiWrites=worker.get('writes'),writes=len(applied),localWrites=sum(r.get('kind')=='local' for r in applied),
                          writesBasis='current-session durable history; excludes undone/discarded edits')
            result['workers'].append(worker)
        result['quality']=quality
        result['barrier']=saved
        coverage=(read(folder/'coverage-after.json') or read(folder/'coverage-after-partial.json')
                  or read(folder/'coverage-before.json') or read(folder/'coverage-before-partial.json'))
        if coverage:
            result['coverage']=dict(coverage_summary(coverage),classes_total=len(result['workers']))
    edits=[r for r in history if baseline is not None and r.get('id') not in baseline]
    result['edits']=edits;result['counts']=counts(edits);result['history_entries']=len(history)
    # Read counts from durable state; do not ask a model or force a project switch.
    return result

def main():
    parser=argparse.ArgumentParser();parser.add_argument('--config',required=True);args=parser.parse_args()
    config=read(args.config);root=Path(config['root']);api=API(config['url'],timeout=30)
    while True:
        try:durable_json(root/'dashboard.json',snapshot(config,api))
        except Exception as error:print('Snapshot retained:',error,flush=True)
        time.sleep(5)

if __name__=='__main__':main()
