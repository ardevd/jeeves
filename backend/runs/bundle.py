"""One canonical run-bundle writer/copy adapter, shared by local CLI and Runs import.

Only selected files enter exports. The pinned semantic validator is authoritative;
no uploaded hook, recipe, URL or external controller is executed on import.
"""
import copy
from datetime import datetime, timezone
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import re
import shutil
import tempfile
import threading
import time
import uuid

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location('jeeves_bundle_validator', Path(__file__).resolve().with_name('validate_bundle.py'))
validator = importlib.util.module_from_spec(spec)
spec.loader.exec_module(validator)
require = validator.require


def now():
    return datetime.now(timezone.utc).isoformat(timespec='microseconds').replace('+00:00','Z')


def encode(value):
    return (json.dumps(value,sort_keys=True,ensure_ascii=True,allow_nan=False,separators=(',',':'))+'\n').encode()


def atomic(path, value):
    path=Path(path);temp=path.with_name(path.name+'.'+uuid.uuid4().hex+'.tmp')
    try:
        with temp.open('xb') as stream:
            os.chmod(temp,0o600);stream.write(encode(value));stream.flush();os.fsync(stream.fileno())
        os.replace(temp,path)
    finally: temp.unlink(missing_ok=True)


def manifest(root):
    fd=os.open(root,os.O_RDONLY|os.O_DIRECTORY|os.O_NOFOLLOW)
    try: return validator.strict_json(validator.read_member(fd,'manifest.json',validator.MAX_MANIFEST))
    finally: os.close(fd)


def selected_copy(source, destination):
    """Freeze exactly the validator-approved bytes, verify again, then publish a new directory."""
    source,destination=Path(source),Path(destination)
    report=validator.validate(source)
    require(not destination.exists() and not destination.is_symlink(),'Destination already exists')
    destination.parent.mkdir(parents=True,exist_ok=True)
    stage=Path(tempfile.mkdtemp(prefix='.jeeves-selected-',dir=destination.parent))
    try:
        fd=os.open(source,os.O_RDONLY|os.O_DIRECTORY|os.O_NOFOLLOW)
        try:
            raw=validator.read_member(fd,'manifest.json',validator.MAX_MANIFEST)
            require(hashlib.sha256(raw).hexdigest()==report['manifest_sha256'],'Manifest changed during copy')
            doc=validator.strict_json(raw)
            for row in doc['files']:
                data=validator.read_member(fd,row['path'],validator.MAX_FILE)
                require(len(data)==row['size'] and hashlib.sha256(data).hexdigest()==row['sha256'],'Selected bytes changed during copy')
                path=stage/row['path'];path.parent.mkdir(parents=True,exist_ok=True)
                with path.open('xb') as stream:
                    os.chmod(path,0o600);stream.write(data);stream.flush();os.fsync(stream.fileno())
            with (stage/'manifest.json').open('xb') as stream:
                os.chmod(stage/'manifest.json',0o600);stream.write(raw);stream.flush();os.fsync(stream.fileno())
        finally: os.close(fd)
        validator.validate(stage)
        require(not destination.exists() and not destination.is_symlink(),'Destination appeared during copy')
        os.rename(stage,destination)
        return {**report,'selected_only':True,'destination':str(destination),'executed_scripts':False}
    finally:
        if stage.exists(): shutil.rmtree(stage)


class Recorder:
    def __init__(self, template, directory, run_id, event_budget=50000):
        self.root=Path(directory);self.root.mkdir(mode=0o700)
        self.lock=threading.RLock();self.event_budget=event_budget
        validator.validate(template)
        self.doc=manifest(template)
        self.doc['run_id']=run_id
        self.doc['capture'].update(started_at=now(),ended_at=now(),completion='partial',stop_reason='error',
                                   event_count=0,first_seq=None,last_seq=None,sequence_gaps=0,
                                   loss={'known_dropped':0,'unknown_loss':False,'details':[]})
        self.doc['coverage']['observed_methods']=0
        self.doc['research']={'capture_location':'none','contributions':[]}
        files=[]
        fd=os.open(template,os.O_RDONLY|os.O_DIRECTORY|os.O_NOFOLLOW)
        try:
            for row in self.doc['files']:
                if row['role'] in ('events','research'): continue
                data=validator.read_member(fd,row['path'],validator.MAX_FILE)
                require(len(data)==row['size'] and hashlib.sha256(data).hexdigest()==row['sha256'],'Template changed')
                path=self.root/row['path'];path.parent.mkdir(parents=True,exist_ok=True)
                path.write_bytes(data);os.chmod(path,0o600);files.append(row)
        finally: os.close(fd)
        self.doc['files']=files
        self.count=0;self.segment=0;self.stream=None;self.segment_row=None
        self.observed=set();self.closed=False;self.stopped_by_budget=False
        self.event_schema=validator.schemas()[1]
        self.total=sum(row['size'] for row in files)
        self.checkpoint()

    def checkpoint(self):
        atomic(self.root.parent/'draft.json',self.doc)

    def loss(self, detail, known=0, unknown=False):
        with self.lock:
            loss=self.doc['capture']['loss'];loss['known_dropped']+=known
            loss['unknown_loss'] = loss['unknown_loss'] or unknown
            if detail not in loss['details'] and len(loss['details'])<32: loss['details'].append(detail[:500])
            self.checkpoint()

    def append(self, event):
        with self.lock:
            if self.closed: return False
            if self.count>=self.event_budget or self.total>=120*1024*1024:
                self.stopped_by_budget=True;self.loss('Recorder budget exhausted',known=1);return False
            row=copy.deepcopy(event)
            row.update(schema='jeeves-run-event-v1',run_id=self.doc['run_id'],seq=self.count+1,monotonic_ns=str(time.monotonic_ns()))
            validator.checked_schema(self.event_schema,row)
            raw=encode(row);require(len(raw)<=validator.MAX_EVENT,'Event exceeds canonical bound')
            if self.stream is None or self.stream.tell()+len(raw)>(1<<20):
                self.close_segment()
                require(len(self.doc['files'])<250,'Segment count exhausted')
                self.segment+=1;name='events/%06d.jsonl'%self.segment
                path=self.root/name;path.parent.mkdir(exist_ok=True)
                self.stream=path.open('xb');os.chmod(path,0o600)
                self.segment_row={'path':name,'role':'events','size':0,'sha256':'0'*64,'event_count':0,'first_seq':None,'last_seq':None}
                self.doc['files'].append(self.segment_row)
            self.stream.write(raw);self.stream.flush();os.fsync(self.stream.fileno())
            self.count+=1;self.total+=len(raw)
            entry=self.segment_row;entry['event_count']+=1
            entry['first_seq']=entry['first_seq'] or self.count;entry['last_seq']=self.count
            if row['kind'] in ('enter','leave','exception') and row['origSig']: self.observed.add(row['origSig'])
            return True

    def marker(self, text, phase=None):
        return self.append({'kind':'marker','hook_id':None,'origSig':None,'native':None,'process_id':None,
                            'thread_id':None,'phase':phase,'correlation':None,'args':[],'result':None,'summary':text})

    def close_segment(self):
        if self.stream:
            self.stream.flush();os.fsync(self.stream.fileno());self.stream.close();self.stream=None
            data=(self.root/self.segment_row['path']).read_bytes()
            self.segment_row.update(size=len(data),sha256=hashlib.sha256(data).hexdigest())
            self.checkpoint()

    def finish(self, reason, cleanup):
        with self.lock:
            self.closed=True;self.close_segment()
            cap=self.doc['capture'];cap.update(ended_at=now(),stop_reason=reason,event_count=self.count,
                                               first_seq=1 if self.count else None,last_seq=self.count or None)
            partial=reason in ('error','owner-crash') or cap['loss']['unknown_loss'] or cap['loss']['known_dropped'] or any(h['state']!='installed' for h in self.doc['hooks'])
            cap['completion']='partial' if partial else 'complete'
            self.doc['cleanup']=cleanup
            self.doc['coverage']['installed_methods']=len({s for h in self.doc['hooks'] if h['state']=='installed' for s in h['origSigs']})
            self.doc['coverage']['observed_methods']=len(self.observed)
            atomic(self.root/'manifest.json',self.doc)
            self.checkpoint()
            return validator.validate(self.root)


def recover_bundle(directory):
    """Finalize retained complete JSONL lines as partial; never contact a phone or replay."""
    directory=Path(directory);root=directory/'bundle'
    flags=os.O_RDONLY|os.O_DIRECTORY|os.O_NOFOLLOW
    directory_fd=os.open(directory,flags)
    try:
        root_fd=os.open('bundle',flags,dir_fd=directory_fd)
        try: return _recover_open(directory_fd,root_fd,root)
        finally: os.close(root_fd)
    finally: os.close(directory_fd)


def _recover_open(directory_fd,root_fd,root):
    if 'manifest.json' in os.listdir(root_fd): return validator.validate(root)
    doc=validator.strict_json(validator.read_member(directory_fd,'draft.json',validator.MAX_MANIFEST))
    files=[row for row in doc['files'] if row['role']!='events'];count=0;observed=set();last=None;gaps=0;first=None
    schema=validator.schemas()[1]
    total=sum(row['size'] for row in files)
    try: events_fd=os.open('events',os.O_RDONLY|os.O_DIRECTORY|os.O_NOFOLLOW,dir_fd=root_fd)
    except FileNotFoundError: events_fd=None
    try:
        names=os.listdir(events_fd) if events_fd is not None else []
        require(len(names)+len(files)<=256,'Recovery member count exceeded')
        for name in sorted(names):
            require(re.fullmatch(r'[0-9]{6}\.jsonl',name),'Unexpected recovery event member')
            raw=validator.read_member(events_fd,name,validator.MAX_FILE)
            total+=len(raw);require(total<=validator.MAX_TOTAL,'Recovery byte bound exceeded')
            end=raw.rfind(b'\n')+1;data=raw[:end];events=[]
            for line in data.splitlines():
                require(0<len(line)<=validator.MAX_EVENT,'Recovery event bound exceeded')
                row=validator.strict_json(line);validator.checked_schema(schema,row)
                require(row['run_id']==doc['run_id'],'Recovered event has another run identity')
                require(row['seq']>(last or 0),'Recovered sequence is not increasing')
                gaps+=row['seq']-(last or 0)-1;last=row['seq'];first=first or last
                if row['origSig'] and row['kind'] in ('enter','leave','exception'): observed.add(row['origSig'])
                events.append(row);count+=1;require(count<=1000000,'Recovery event count exceeded')
            # Replace our directory entry, never follow a replaced link or truncate a
            # foreign inode. Complete bytes were opened through no-follow descriptors.
            temp=name+'.'+uuid.uuid4().hex+'.tmp'
            try:
                fd=os.open(temp,os.O_WRONLY|os.O_CREAT|os.O_EXCL|os.O_NOFOLLOW,0o600,dir_fd=events_fd)
                with os.fdopen(fd,'wb') as stream:
                    stream.write(data);stream.flush();os.fsync(stream.fileno())
                os.replace(temp,name,src_dir_fd=events_fd,dst_dir_fd=events_fd)
            finally:
                try: os.unlink(temp,dir_fd=events_fd)
                except FileNotFoundError: pass
            if not data:
                os.unlink(name,dir_fd=events_fd)
                continue
            files.append({'path':'events/'+name,'role':'events','size':len(data),
                          'sha256':hashlib.sha256(data).hexdigest(),'event_count':len(events),
                          'first_seq':events[0]['seq'] if events else None,'last_seq':events[-1]['seq'] if events else None})
    finally:
        if events_fd is not None: os.close(events_fd)
    doc['files']=files
    doc['capture'].update(ended_at=now(),completion='partial',stop_reason='owner-crash',event_count=count,
                          first_seq=first,last_seq=last,sequence_gaps=gaps)
    doc['capture']['loss']['unknown_loss']=True
    doc['capture']['loss']['details']=(doc['capture']['loss']['details']+['Owner ended before finalization; final tail and cleanup are unknown'])[-128:]
    fixture=doc['producer']['mode']=='fixture'
    doc['cleanup']={'state':'unknown','basis':'fixture' if fixture else 'unknown','resources':[]}
    doc['coverage']['observed_methods']=len(observed)
    atomic(root/'manifest.json',doc)
    return validator.validate(root)
