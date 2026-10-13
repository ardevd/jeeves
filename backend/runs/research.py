#!/usr/bin/env python3
"""Preview/export explicitly selected local Capture journal events; never uploads.

This projection preserves existing day1-event-v1 envelopes. It is optional research
evidence, not a phone run, authenticated student identity or an external chat export.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import sys

sys.path.insert(0,str(Path(__file__).resolve().parent))
import bundle


def select(root,run,offset,limit,payloads=False):
    bundle.require(re.fullmatch(r'[A-Za-z0-9_-]{1,120}',run),'Invalid local journal identifier')
    bundle.require(0<=offset<=1000000 and 1<=limit<=100,'Invalid selection window')
    fd=os.open(root,os.O_RDONLY|os.O_DIRECTORY|os.O_NOFOLLOW)
    try:
        raw=bundle.validator.read_member(fd,'runs/'+run+'/events.jsonl',128<<20)
        digest=hashlib.sha256(raw).hexdigest()
        lines=raw.splitlines();selected=[];size=0
        for line in lines[offset:offset+limit]:
            bundle.require(0<len(line)<=65536,'Journal envelope exceeds selection bound')
            event=bundle.validator.strict_json(line)
            bundle.require(event.get('schema')=='day1-event-v1','Unknown capture schema')
            item={'event':event}
            if payloads:
                sha=event.get('payload_blob_sha256')
                bundle.require(isinstance(sha,str) and re.fullmatch('[0-9a-f]{64}',sha),'Invalid payload reference')
                data=bundle.validator.read_member(fd,'blobs/'+sha,16<<20)
                bundle.require(hashlib.sha256(data).hexdigest()==sha,'Capture payload digest mismatch')
                size+=len(data);bundle.require(size<15<<20,'Selected payloads exceed projection bound; select fewer events')
                item['payload']=data.decode('utf-8')
            selected.append(item)
        projection={'schema':'jeeves-selected-research-v1','capture_location':'local','journal_sha256':digest,
                    'run':run,'offset':offset,'limit':limit,'payloads_included':payloads,'events':selected,
                    'attribution':'local-unverified','server_capture_claim':False,'external_conversations_claim':False}
        encoded=bundle.encode(projection);bundle.require(len(encoded)<=16<<20,'Projection exceeds file bound')
        summary={'schema':projection['schema'],'journal_sha256':digest,'selected_sha256':hashlib.sha256(encoded).hexdigest(),
                 'selected_bytes':len(encoded),'selected_events':len(selected),'next_offset':offset+limit if len(lines)>offset+limit else None,
                 'events':[{'event_id':r['event'].get('event_id'),'type':r['event'].get('event_type'),
                            'provider':r['event'].get('provider'),'model':r['event'].get('model_returned',r['event'].get('model_requested')),
                            'original_method':r['event'].get('original_method'),'payload_bytes':r['event'].get('payload_bytes')} for r in selected],
                 'automatic_upload':False,'payloads_included':payloads}
        return encoded,summary
    finally: os.close(fd)


def main():
    p=argparse.ArgumentParser(description=__doc__,allow_abbrev=False)
    p.add_argument('action',choices=('preview','export'));p.add_argument('--capture',type=Path,required=True)
    p.add_argument('--run',required=True);p.add_argument('--offset',type=int,default=0);p.add_argument('--limit',type=int,default=20)
    p.add_argument('--include-payloads',action='store_true');p.add_argument('--expected-sha256');p.add_argument('--destination',type=Path)
    a=p.parse_args()
    try:
        raw,summary=select(a.capture,a.run,a.offset,a.limit,a.include_payloads)
        if a.action=='export':
            bundle.require(a.expected_sha256==summary['selected_sha256'],'Preview pin is required and must still match')
            bundle.require(a.destination is not None,'New destination file is required')
            with a.destination.open('xb') as stream:
                os.chmod(a.destination,0o600);stream.write(raw);stream.flush();os.fsync(stream.fileno())
            summary['destination']=str(a.destination)
            summary['sensitive_content_review']='not-reviewed; inspect the local projection before course submission'
        print(json.dumps(summary));return 0
    except Exception as error:
        print(json.dumps({'ok':False,'error':str(error) if isinstance(error,ValueError) else type(error).__name__}));return 1

if __name__=='__main__':raise SystemExit(main())
