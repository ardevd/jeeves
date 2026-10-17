"""Check durable, actor-specific method receipts against the current native inventory."""
from __future__ import annotations
import time
from checkpoint_backup import durable_json

def audit_session(api,target,slots,folder,stage):
    classes=[]
    for slot in slots:
        original=slot['original_class'];methods=[];errors=[];method_total=0
        actor=slot.get('coverage_actor')
        if not actor:errors.append('Missing mission actor; prior agents cannot supply this session\'s coverage')
        try:
            outline=api.call('code.outline',target=target,**{'class':original})
            if outline['symbol'].get('origSig')!=original:raise RuntimeError('Class identity mismatch')
            identities=[m['origSig'] for m in outline['methods']]
            method_total=len(identities)
            if len(set(identities))!=len(identities):raise RuntimeError('Duplicate method identity')
            for method in identities:
                try:
                    row=api.call('naming.coverage',target=target,symbol=method,recordedBy=actor or 'missing-mission-actor')
                    if row.get('originalMethod')!=method:raise RuntimeError('Method identity mismatch')
                    if row.get('recordedBy')!=actor:row=dict(row,status='needs_review')
                    methods.append(row)
                except Exception as error:
                    errors.append({'method':method,'error':str(error)})
        except Exception as error:errors.append(str(error))
        counts={key:sum(m.get('counts',{}).get(key,0) for m in methods)
                for key in ('total','missing','unresolved','renamed','retained')}
        classes.append({'original_class':original,'current_name':slot.get('current_name'),
                        'actor':actor,'methods':methods,'errors':errors,'counts':counts,
                        'methods_total':method_total,'methods_complete':sum(m.get('status')=='complete' for m in methods),
                        'status':'complete' if not errors and all(m.get('status')=='complete' for m in methods) else 'needs_review'})
        # Full per-variable evidence is durable, never retrieved from a truncated model transcript.
        durable_json(folder/('coverage-'+stage+'-partial.json'),{'stage':stage,'classes':classes,'at':time.time()})
    result={'stage':stage,'classes':classes,'at':time.time(),
            'status':'complete' if all(c['status']=='complete' for c in classes) else 'needs_review'}
    durable_json(folder/('coverage-'+stage+'.json'),result)
    return result

def native_expectations(coverage):
    # Unavailable inventories stay incomplete. Never describe them as native-verified.
    return [{'original_method':m['originalMethod'],'inventory_fingerprint':m['inventoryFingerprint']}
            for c in coverage['classes'] for m in c['methods'] if not m.get('inventoryErrors')]

def summary(coverage):
    return {'status':coverage.get('status','collecting'),'stage':coverage['stage'],'at':coverage['at'],
            'classes':[{k:v for k,v in c.items() if k!='methods'} for c in coverage['classes']]}
