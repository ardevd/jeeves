#!/usr/bin/env python3
"""Create immutable, hash-verified checkpoints on the campaign server."""
from __future__ import annotations
import argparse
from contextlib import ExitStack
import fcntl
import hashlib
import json
import os
from pathlib import Path
import subprocess
import tarfile
import time

ROOT = Path(os.environ.get("JEEVES_CAMPAIGN_ROOT", "/var/lib/jeeves/campaign")).resolve()

def sha256(path):
    h = hashlib.sha256()
    with Path(path).open('rb') as f:
        for chunk in iter(lambda: f.read(1024 * 1024), b''):
            h.update(chunk)
    return h.hexdigest()

def durable_json(path, value):
    path = Path(path)
    tmp = path.with_name(path.name + '.tmp')
    with tmp.open('w') as f:
        os.chmod(tmp, 0o600)
        json.dump(value, f, indent=2)
        f.write('\n'); f.flush(); os.fsync(f.fileno())
    os.replace(tmp, path)
    fd = os.open(path.parent, os.O_RDONLY)
    try: os.fsync(fd)
    finally: os.close(fd)

def create_checkpoint(destination, sources):
    destination = Path(destination).resolve()
    if not destination.is_relative_to(ROOT) or destination.exists():
        raise ValueError('Checkpoint must be a new directory within the private campaign')
    destination.mkdir(parents=True, mode=0o700)
    entries = []
    released_coordination_locks = []
    tarpath = destination / 'checkpoint.tar'
    with ExitStack() as guards, tarfile.open(tarpath, 'x', format=tarfile.PAX_FORMAT) as archive:
        os.chmod(tarpath, 0o600)
        for label, source in sources.items():
            if not label or any(c not in 'abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789_-.' for c in label):
                raise ValueError('Unsafe archive source label')
            source = Path(source).resolve()
            if not source.exists():
                raise ValueError(f'Checkpoint source is missing: {source}')
            paths = sorted(source.rglob('*')) if source.is_dir() else [source]
            for path in paths:
                rel = path.relative_to(source) if source.is_dir() else Path(path.name)
                # Search text is rebuildable. Accepted edits, keys, APK, DB and metadata stay.
                if any(p in ('search-index', '.save-checkpoints', 'tmp') for p in rel.parts):
                    continue
                if path.is_symlink():
                    raise ValueError(f'Checkpoint refuses an unexpected symlink: {path}')
                if not path.is_file():
                    continue
                # The round runner deliberately keeps its flock inode after exit.
                # Prove that no writer holds it, and hold a shared lock throughout
                # the copy. Native JEB locks and every other lock still reject.
                if label == 'round' and rel == Path('supervisor.lock'):
                    guard = guards.enter_context(path.open('rb'))
                    try:
                        fcntl.flock(guard.fileno(), fcntl.LOCK_SH | fcntl.LOCK_NB)
                    except BlockingIOError as error:
                        raise ValueError(f'Round runner still holds its coordination lock: {path}') from error
                    released_coordination_locks.append(str(path))
                    continue
                if path.name.endswith('.lock') or '.save-in-progress.' in path.name:
                    raise ValueError(f'Checkpoint source still has an active/interrupted write: {path}')
                before = path.stat()
                digest = sha256(path)
                name = str(Path(label) / rel)
                archive.add(path, arcname=name, recursive=False)
                after = path.stat()
                if (before.st_size, before.st_mtime_ns) != (after.st_size, after.st_mtime_ns):
                    raise RuntimeError(f'Checkpoint source changed while copying: {path}')
                entries.append({'path': name, 'source': str(path), 'bytes': before.st_size, 'sha256': digest})
    with tarpath.open('rb') as stream: os.fsync(stream.fileno())
    # Read every archived byte back; same-disk copy alone is not a verification.
    with tarfile.open(tarpath, 'r') as archive:
        indexed = {e['path']: e for e in entries}
        for member in archive:
            stream = archive.extractfile(member)
            if stream is None: raise RuntimeError('Unexpected non-file archive member')
            h = hashlib.sha256()
            for chunk in iter(lambda: stream.read(1024 * 1024), b''): h.update(chunk)
            if h.hexdigest() != indexed[member.name]['sha256']:
                raise RuntimeError(f'Archive verification failed: {member.name}')
    receipt = {'created_at': time.time(), 'archive': str(tarpath),
               'sha256': sha256(tarpath), 'bytes': tarpath.stat().st_size,
               'entries': entries, 'local_verified': True, 'remote_verified': False}
    receipt['released_coordination_locks'] = released_coordination_locks
    durable_json(destination / 'receipt.json', receipt)
    return receipt
