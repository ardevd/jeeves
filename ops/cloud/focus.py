#!/usr/bin/env python3
"""Build a durable FMessage class/reference queue without launching agents."""
from __future__ import annotations
import argparse, fcntl, hashlib, json, time
from pathlib import Path
from barrier import API, ROOT, APK_SHA, ensure_target
from checkpoint_backup import durable_json, sha256

def read(path):return json.loads(Path(path).read_text())

def all_rows(value):
    if isinstance(value,list):return value
    if value.get('truncated'):raise RuntimeError('Focus discovery received truncated references')
    rows=value['rows']
    if value.get('total',len(rows))!=len(rows):raise RuntimeError('Incomplete focus reference listing')
    return rows

def build(api,target,db,folder,artifact,first_classes=()):
    """Include named FMessage classes and every direct type/member referrer.

    Cache complete read responses against the exact APK/history pair. No recursive
    walk through arbitrary dependencies, decompilation, mutation or provider call.
    All direct reference rows remain in the evidence files; none are capped.
    """
    folder=Path(folder);folder.mkdir(parents=True,exist_ok=True)
    history=Path(str(artifact)+'.workbench/history.json')
    identity={'apk_sha256':sha256(artifact),'history_sha256':sha256(history)}
    if identity['apk_sha256']!=APK_SHA:raise RuntimeError('Focus APK identity mismatch')
    cache=folder/('reads-'+identity['history_sha256']);cache.mkdir(exist_ok=True)
    def call(op,**params):
        key=hashlib.sha256(json.dumps([op,params],sort_keys=True).encode()).hexdigest()
        path=cache/(key+'.json')
        if path.exists():return read(path)['result']
        result=api.call(op,target=target,**params)
        durable_json(path,{'op':op,'params':params,'result':result})
        return result
    resolved={}
    def canonical(symbol):
        if symbol not in resolved:
            row=call('symbol.resolve',symbol=symbol)
            if not row.get('origSig') or row.get('contradiction'):
                raise RuntimeError('Ambiguous focus identity: '+symbol)
            resolved[symbol]=row
        return resolved[symbol]
    seeds={}
    for row in all_rows(call('symbol.search',query='FMessage',kind='class')):
        # Search is case-insensitive; ProofMessage is not the FMessage family.
        if 'FMessage' not in str(row.get('name','')) and 'FMessage' not in str(row.get('fqcn','')):continue
        symbol=canonical(row.get('origSig') or row['addr'])
        seeds[symbol['origSig']]=symbol
    if not seeds:raise RuntimeError('No FMessage classes found; do not fall back to the whole catalog')
    if any(original not in seeds for original in first_classes):
        raise RuntimeError('A requested first class is not a verified FMessage class')
    selected={original:{'original_class':original,'current_name':row['name'],
                        'role':'FMessage class','connections':[]} for original,row in seeds.items()}
    seen=set();reference_count=0
    for index,original in enumerate(sorted(seeds),1):
        outline=call('code.outline',**{'class':original})
        symbols=[original]+[r['origSig'] for r in outline['fields']+outline['methods']]
        for symbol in symbols:
            for ref in all_rows(call('xref.to',symbol=symbol)):
                site=ref.get('from')
                if not site:raise RuntimeError('Reference has no site: '+str(ref))
                native,separator,offset=site.partition('+')
                resolved_site=canonical(native)['origSig']
                owner=resolved_site.split('->',1)[0]
                if owner==original:continue  # self references remain in raw evidence
                key=(symbol,resolved_site,offset,str(ref.get('type','')))
                if key in seen:continue
                seen.add(key);reference_count+=1
                if owner not in selected:
                    if db.execute('SELECT 1 FROM classes WHERE original=?',(owner,)).fetchone() is None:
                        raise RuntimeError('Reference owner is absent from the full class catalog: '+owner)
                    selected[owner]={'original_class':owner,'current_name':canonical(owner)['name'],
                                     'role':'FMessage reference site','connections':[]}
                selected[owner]['connections'].append({'fmessage_class':original,'referenced_symbol':symbol,
                    'site_symbol':resolved_site,'site':resolved_site+(separator+offset if separator else ''),
                    'type':ref.get('type','')})
        durable_json(folder/'status.json',{'phase':'collecting','seeds_done':index,'seeds_total':len(seeds),
            'classes':len(selected),'reference_sites':reference_count,'at':time.time()})
    # The operator's first classes are a concrete next round. Subsequent entries
    # alternate FMessage classes and reference owners, never unrelated padding.
    roots=sorted((s for s in seeds if s not in first_classes),key=lambda s:(selected[s]['current_name'],s))
    refs=sorted((s for s in selected if s not in seeds),key=lambda s:(-len(selected[s]['connections']),s))
    order=list(first_classes)
    while roots or refs:
        if roots:order.append(roots.pop(0))
        if refs:order.append(refs.pop(0))
    if sha256(history)!=identity['history_sha256']:raise RuntimeError('Names changed during focus discovery; regenerate from current state')
    result={'schema':'fmessage-focus-v1',**identity,'at':time.time(),'seed_classes':list(seeds),
            'reference_sites':reference_count,'classes':[selected[s] for s in order],
            'evidence':str(cache),'scope':'FMessage-named classes and all direct class/field/method reference owners'}
    durable_json(folder/'manifest.json',result)
    durable_json(folder/'status.json',{'phase':'complete','classes':len(selected),'seed_classes':len(seeds),
                                      'reference_sites':reference_count,'at':time.time()})
    return result

def load(path,db,apk_sha):
    manifest=read(path)
    if manifest.get('schema')!='fmessage-focus-v1' or manifest.get('apk_sha256')!=apk_sha:
        raise RuntimeError('Focus manifest belongs to a different executable/schema')
    rows=manifest.get('classes',[]);seen=set()
    for row in rows:
        original=row['original_class']
        if original in seen or not db.execute('SELECT 1 FROM classes WHERE original=?',(original,)).fetchone():
            raise RuntimeError('Duplicate or unknown focus class: '+original)
        seen.add(original)
    if len(rows)<8:raise RuntimeError('Focused queue needs eight distinct classes for the requested eight agents')
    return rows

def context(row):
    # Preserve every distinct referencing symbol and FMessage relationship while
    # leaving duplicate instruction offsets in the complete discovery evidence.
    connections=row.get('connections',[])
    return {'role':row['role'],'related_fmessage_classes':sorted({x['fmessage_class'] for x in connections}),
            'entry_symbols':sorted({x['site_symbol'] for x in connections}),
            'referenced_fmessage_symbols':sorted({x['referenced_symbol'] for x in connections}),
            'reference_sites':len(connections)}

def main():
    parser=argparse.ArgumentParser();parser.add_argument('--config',required=True);args=parser.parse_args()
    config=read(args.config);root=Path(config['root']).resolve()
    if root!=ROOT:raise ValueError('Unexpected campaign root')
    if Path(config['focus_manifest']).name!='manifest.json':raise ValueError('focus_manifest must end with manifest.json')
    from campaign import catalog_db
    with (root/'controller.lock').open('a') as lock:
        fcntl.flock(lock,fcntl.LOCK_EX|fcntl.LOCK_NB)
        if read(root/'invocation.json').get('phase')!='complete':raise RuntimeError('Wait for the current invocation to finish')
        api=API(config['url'])
        if any(m.get('loopActive') or m.get('cancellationUnconfirmed') or m.get('state') not in ('done','blocked','stopped','interrupted') for m in api.call('agent.missions')):
            raise RuntimeError('Agents are still active')
        api.call('project.open',name=config['project']);target=ensure_target(api,config['artifact'])
        db=catalog_db(root)
        try:result=build(api,target,db,Path(config['focus_manifest']).parent,Path(config['artifact']),config.get('focus_first_classes',[]))
        finally:db.close()
        print(json.dumps({'classes':len(result['classes']),'seeds':len(result['seed_classes']),
                          'reference_sites':result['reference_sites'],'next_eight':[r['current_name'] for r in result['classes'][:8]]}))

if __name__=='__main__':main()
