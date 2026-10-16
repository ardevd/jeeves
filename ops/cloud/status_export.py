#!/usr/bin/env python3
"""Export only campaign progress for the read-only laptop dashboard."""
from __future__ import annotations
import argparse, collections, json, shutil, sqlite3, time
from pathlib import Path
from barrier import API
from checkpoint_backup import durable_json
from quality import read

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
            'quality':{'status':'pending','classes':[]}}
    try:missions={m['id']:m for m in api.call('agent.missions')}
    except Exception as error:missions={};result['errors'].append(str(error))
    try:
        calls=api.call('ops.active')
        if isinstance(calls,dict):calls=calls.get('calls',[])
        result['active_tools']=[{k:r.get(k) for k in ('op','actor','startedAt','elapsedMs','target')} for r in calls]
    except Exception as error:result['errors'].append(str(error))
    history=read(Path(str(config['artifact'])+'.workbench/history.json'),[])
    baseline=None
    for session in sessions:
        folder=root/'rounds'/f"cloud-{session['id']:06d}"
        saved=read(folder/'barrier/state.json',{})
        row={'id':session['id'],'phase':session['phase'],'created':session['created'],'barrier':saved}
        result['sessions'].append(row)
        if baseline is None:baseline={r.get('id') for r in read(folder/'baseline-history.json',history)}
        if session['id']!=sessions[-1]['id']:continue
        for slot in json.loads(session['slots']):
            mission=missions.get(slot.get('mission'),{})
            worker={k:mission.get(k) for k in ('id','agentId','state','displayState','loopActive','cancellationUnconfirmed','turnsDone','inputTokens','outputTokens','cacheHitTokens','writes','reason','stopReason','progress','startedAt')}
            worker.update(original_class=slot['original_class'],current_name=slot['current_name'],model=slot['model'],result=slot.get('result'))
            result['workers'].append(worker)
        quality=read(folder/'quality/receipt.json')
        if quality is None:quality={'status':read(folder/'quality/status.json',{}).get('phase','pending'),'classes':read(folder/'quality/partial.json',[])}
        result['quality']=quality
        result['barrier']=saved
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
