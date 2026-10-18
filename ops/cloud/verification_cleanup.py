#!/usr/bin/env python3
"""Remove only reproducible, idle native-verification JDBs; preserve all archives."""
from __future__ import annotations
import argparse, fcntl, hashlib, json, os, stat, tarfile, time
from pathlib import Path
from checkpoint_backup import durable_json, sha256

def read(path):
    try: return json.loads(path.read_text())
    except FileNotFoundError: return {}

def contained(path, root):
    path = Path(os.path.abspath(path))
    path.relative_to(root)
    for parent in [path, *path.parents]:
        if parent == root: break
        if parent.is_symlink(): raise ValueError('Symlink is not a cleanup source: ' + str(parent))
    if path.resolve() != path: raise ValueError('Noncanonical path')
    return path

def identity(path):
    s = path.stat()
    if not stat.S_ISREG(s.st_mode): raise ValueError('Expected regular file: ' + str(path))
    return [s.st_dev, s.st_ino, s.st_size, s.st_mtime_ns, s.st_ctime_ns]

def eligible(copy, root):
    relative = copy.relative_to(root).parts
    if relative == ('qualification', 'native-copy'):
        receipt = read(copy.parent/'native-verification.json')
        if not (receipt.get('ok') and receipt.get('native_only') and not receipt.get('history_replayed')):
            raise ValueError('Initial qualification has not succeeded')
        return
    if len(relative) == 5 and relative[0] in ('rounds', 'maintenance') and relative[2] == 'barrier' and relative[3].startswith('native-') and relative[4] == 'native-copy':
        barrier = copy.parent.parent
        state, receipt = read(barrier/'state.json'), read(barrier/'receipt.json')
        if not (state.get('phase') == receipt.get('phase') == 'complete' and receipt.get('ok')
                and receipt.get('native_checks', 0) > 0 and receipt.get('live_checks', 0) > 0):
            raise ValueError('Native/live barrier is not complete')
        return
    if len(relative) == 4 and relative[0] == 'maintenance' and relative[1].startswith('qualify-') and relative[2].startswith('native-') and relative[3] == 'native-copy':
        receipt = read(copy.parent.parent/'receipt.json')
        if not receipt.get('ok'): raise ValueError('Maintenance qualification has not completed')
        return
    raise ValueError('Not a recognized verification-copy directory')

def busy(copy, proc):
    if not proc.is_dir(): raise RuntimeError('Linux process state unavailable')
    prefix = str(copy) + '/'
    for process in proc.iterdir():
        if not process.name.isdecimal(): continue
        try:
            args = (process/'cmdline').read_bytes().split(b'\0')
            # A new native verification can start while a scan is running. Completed
            # barriers never reuse their old copy; still defer during any verifier.
            if any(b'NativeSnapshotVerifier' in arg or arg.decode(errors='replace').startswith(prefix) for arg in args):
                return True
            for fd in (process/'fd').iterdir():
                try:
                    if os.readlink(fd).startswith(prefix): return True
                except FileNotFoundError: pass
        except (FileNotFoundError, ProcessLookupError): pass
    return False

class Restores:
    def __init__(self, root):
        self.root, self.entries, self.checked = root, {}, {}
        for path in sorted((root/'checkpoints').glob('*/receipt.json')):
            contained(path, root)
            receipt = read(path)
            if not receipt.get('local_verified'): continue
            archive = contained(Path(receipt['archive']), root)
            if archive != path.parent/'checkpoint.tar': raise ValueError('Unexpected archive path')
            for entry in receipt.get('entries', []):
                if entry['path'].endswith('.jdb2'):
                    key = (Path(entry['path']).name, entry['bytes'], entry['sha256'])
                    self.entries.setdefault(key, []).append((archive, receipt, entry))

    def verify(self, name, size, digest):
        for archive, receipt, entry in self.entries.get((name, size, digest), []):
            before = identity(archive)
            cache_key = (str(archive), tuple(before), receipt['sha256'], entry['path'], digest)
            if cache_key not in self.checked:
                if sha256(archive) != receipt['sha256']: raise ValueError('Checkpoint archive checksum mismatch')
                with tarfile.open(archive, 'r') as tar:
                    member = tar.getmember(entry['path'])
                    if not member.isfile() or member.size != size: raise ValueError('Bad archived JDB member')
                    h = hashlib.sha256()
                    with tar.extractfile(member) as source:
                        for block in iter(lambda: source.read(1024 * 1024), b''): h.update(block)
                    if h.hexdigest() != digest: raise ValueError('Archived JDB checksum mismatch')
                if identity(archive) != before: raise ValueError('Archive changed during verification')
                self.checked[cache_key] = True
            return dict(kind='checkpoint', path=str(archive), archive_sha256=receipt['sha256'],
                        member=entry['path'], sha256=digest, identity=before)
        # Initial pre-campaign qualification used an existing native save checkpoint.
        # Keep that source in place; never consider the current writable database.
        for path in sorted((self.root/'inputs/.save-checkpoints').glob('*.jdb2')):
            contained(path, self.root)
            before = identity(path)
            if before[2] != size: continue
            if sha256(path) == digest:
                if identity(path) != before: raise ValueError('Native checkpoint changed')
                return dict(kind='native-checkpoint', path=str(path), sha256=digest, identity=before)
        raise ValueError('No byte-identical retained checkpoint for ' + name)

def clean_copy(copy, root, restores, apply=False, proc=Path('/proc')):
    copy = contained(copy, root)
    eligible(copy, root)
    if busy(copy, proc): return dict(copy=str(copy), status='deferred', reason='Verifier or reader is active')
    record_path = copy.parent/'native-copy-cleanup.json'
    previous = read(record_path)
    files = list(copy.glob('*.jdb2'))
    if not files:
        if previous.get('phase') == 'prepared':
            name, size, digest = previous['name'], previous['bytes'], previous['sha256']
            if Path(name).name != name or not name.endswith('.jdb2'): raise ValueError('Invalid cleanup receipt')
            restores.verify(name, size, digest)
            previous.update(phase='complete', recovered_after_unlink=True, completed_at=time.time())
            if apply: durable_json(record_path, previous)
        return dict(copy=str(copy), status='already_clean', bytes=0)
    if len(files) != 1: raise ValueError('Unexpected number of copied databases')
    path = contained(files[0], root)
    before = identity(path)
    digest = sha256(path)
    if identity(path) != before: raise ValueError('Copied database changed')
    restore = restores.verify(path.name, before[2], digest)
    record = dict(phase='prepared', copy=str(copy), name=path.name, bytes=before[2],
                  sha256=digest, restore=restore, prepared_at=time.time())
    if apply:
        # Persist the restore evidence before unlink; a restart can finish the receipt.
        durable_json(record_path, record)
        eligible(copy, root)
        if busy(copy, proc): return dict(copy=str(copy), status='deferred', reason='Verifier or reader became active')
        if identity(path) != before or identity(Path(restore['path'])) != restore['identity']:
            raise ValueError('Copy or retained checkpoint changed before removal')
        path.unlink()
        fd = os.open(copy, os.O_RDONLY)
        try: os.fsync(fd)
        finally: os.close(fd)
        record.update(phase='complete', completed_at=time.time())
        durable_json(record_path, record)
    return dict(copy=str(copy), status='removed' if apply else 'eligible', bytes=before[2])

def sweep(root, apply=False, proc=Path('/proc'), progress=None):
    root = root.resolve()
    restores = Restores(root)
    patterns = ['rounds/*/barrier/native-*/native-copy', 'maintenance/*/barrier/native-*/native-copy',
                'maintenance/qualify-*/native-*/native-copy', 'qualification/native-copy']
    copies = sorted({p for pattern in patterns for p in root.glob(pattern)})
    result = dict(at=time.time(), apply=apply, rows=[], removed_bytes=0, eligible_bytes=0)
    for copy in copies:
        try: row = clean_copy(copy, root, restores, apply, proc)
        except Exception as error: row = dict(copy=str(copy), status='deferred', reason=str(error))
        result['rows'].append(row)
        if row['status'] == 'removed': result['removed_bytes'] += row['bytes']
        if row['status'] == 'eligible': result['eligible_bytes'] += row['bytes']
        if progress: progress(result)
    result['completed_at'] = time.time()
    return result

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--root', type=Path, required=True)
    parser.add_argument('--apply', action='store_true')
    args = parser.parse_args()
    root = args.root.resolve()
    ledger = root/'maintenance/verification-cleanup'
    ledger.mkdir(parents=True, exist_ok=True, mode=0o700)
    with (ledger/'cleanup.lock').open('a') as lock:
        fcntl.flock(lock, fcntl.LOCK_EX)
        run = ledger/('run-' + str(time.time_ns()) + '.json')
        result = sweep(root, args.apply, progress=lambda r: durable_json(run, r))
        durable_json(run, result)
        durable_json(ledger/'latest.json', dict(result, receipt=str(run)))
        print(json.dumps(dict(receipt=str(run), removed_bytes=result['removed_bytes'],
                              eligible_bytes=result['eligible_bytes'],
                              statuses={s:sum(r['status']==s for r in result['rows'])
                                        for s in sorted({r['status'] for r in result['rows']})})))

if __name__ == '__main__': main()
