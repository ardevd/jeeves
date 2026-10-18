import hashlib, io, json, os, tarfile, tempfile, unittest
from pathlib import Path
from unittest.mock import patch
import verification_cleanup as cleanup

class Tests(unittest.TestCase):
    def setUp(self):
        self.tmp=tempfile.TemporaryDirectory();self.root=Path(self.tmp.name).resolve()
        self.proc=self.root/'proc';self.proc.mkdir()
        self.barrier=self.root/'rounds/cloud-000001/barrier';self.copy=self.barrier/'native-123/native-copy'
        self.copy.mkdir(parents=True);self.db=self.copy/'test.jdb2';self.db.write_bytes(b'native saved names')
        (self.copy/'test.jdb2.key').write_bytes(b'fixture key')
        self.payload=self.db.read_bytes();self.digest=hashlib.sha256(self.payload).hexdigest()
        self.cp=self.root/'checkpoints/cloud-000001';self.cp.mkdir(parents=True)
        self.archive=self.cp/'checkpoint.tar'
        with tarfile.open(self.archive,'w') as tar:
            member=tarfile.TarInfo('target/test.jdb2');member.size=len(self.payload);tar.addfile(member,io.BytesIO(self.payload))
        self.receipt=dict(local_verified=True,archive=str(self.archive),sha256=cleanup.sha256(self.archive),
                          entries=[dict(path='target/test.jdb2',bytes=len(self.payload),sha256=self.digest)])
        self.write(self.cp/'receipt.json',self.receipt)
        self.complete()
        self.live=self.root/'inputs/test.jdb2';self.live.parent.mkdir();self.live.write_bytes(self.payload)
    def tearDown(self):self.tmp.cleanup()
    def write(self,p,v):p.write_text(json.dumps(v))
    def complete(self):
        for name in ['state.json','receipt.json']:
            self.write(self.barrier/name,dict(phase='complete',ok=True,native_checks=1,live_checks=1))
    def run_cleanup(self,apply=True):return cleanup.sweep(self.root,apply,self.proc)
    def test_remove_copy_only_after_reading_retained_archive_and_keep_evidence(self):
        before=self.archive.read_bytes();result=self.run_cleanup()
        self.assertEqual(result['removed_bytes'],len(self.payload));self.assertFalse(self.db.exists())
        self.assertEqual(self.archive.read_bytes(),before);self.assertEqual(self.live.read_bytes(),self.payload)
        self.assertTrue((self.copy/'test.jdb2.key').exists());self.assertTrue((self.barrier/'receipt.json').exists())
        receipt=cleanup.read(self.copy.parent/'native-copy-cleanup.json')
        self.assertEqual(receipt['phase'],'complete');self.assertEqual(receipt['restore']['sha256'],self.digest)
        with tarfile.open(self.archive) as tar:self.assertEqual(tar.extractfile('target/test.jdb2').read(),self.payload)
        self.assertEqual(self.run_cleanup()['removed_bytes'],0)
    def test_dry_run_never_removes_or_writes_a_cleanup_receipt(self):
        result=self.run_cleanup(False);self.assertEqual(result['eligible_bytes'],len(self.payload));self.assertTrue(self.db.exists())
        self.assertFalse((self.copy.parent/'native-copy-cleanup.json').exists())
    def test_incomplete_or_failed_barrier_keeps_copy(self):
        for change in [dict(phase='native',ok=False),dict(phase='complete',ok=False),dict(phase='complete',ok=True,native_checks=1,live_checks=0)]:
            self.write(self.barrier/'receipt.json',change)
            self.assertEqual(self.run_cleanup()['rows'][0]['status'],'deferred');self.assertTrue(self.db.exists())
    def test_corrupt_or_missing_archive_keeps_copy(self):
        self.archive.write_bytes(b'bad')
        self.assertEqual(self.run_cleanup()['rows'][0]['status'],'deferred');self.assertTrue(self.db.exists())
        self.archive.unlink();self.assertEqual(self.run_cleanup()['rows'][0]['status'],'deferred')
    def test_wrong_copy_checksum_keeps_copy(self):
        self.db.write_bytes(b'different data');self.assertEqual(self.run_cleanup()['rows'][0]['status'],'deferred')
        self.assertTrue(self.db.exists())
    def test_active_verifier_or_open_descriptor_defers_cleanup(self):
        p=self.proc/'123';p.mkdir();(p/'fd').mkdir()
        (p/'cmdline').write_bytes(b'java\0dev.workbench.it.NativeSnapshotVerifier\0')
        self.assertEqual(self.run_cleanup()['rows'][0]['status'],'deferred')
        (p/'cmdline').write_bytes(b'java\0');(p/'fd/3').symlink_to(self.db)
        self.assertEqual(self.run_cleanup()['rows'][0]['status'],'deferred');self.assertTrue(self.db.exists())
    def test_symlink_copy_cannot_remove_live_database(self):
        self.db.unlink();self.db.symlink_to(self.live)
        self.assertEqual(self.run_cleanup()['rows'][0]['status'],'deferred');self.assertTrue(self.live.exists())
    def test_crash_after_unlink_finishes_receipt_without_redeleting(self):
        real=cleanup.durable_json
        def write(path,value):
            if value.get('phase')=='complete':raise OSError('fixture power loss')
            return real(path,value)
        with patch.object(cleanup,'durable_json',side_effect=write):self.run_cleanup()
        self.assertFalse(self.db.exists());self.assertEqual(cleanup.read(self.copy.parent/'native-copy-cleanup.json')['phase'],'prepared')
        self.run_cleanup();self.assertEqual(cleanup.read(self.copy.parent/'native-copy-cleanup.json')['phase'],'complete')
    def test_initial_qualification_uses_exact_retained_native_checkpoint(self):
        initial=self.root/'qualification/native-copy';initial.mkdir(parents=True)
        (initial/'old.jdb2').write_bytes(b'old initial names')
        self.write(initial.parent/'native-verification.json',dict(ok=True,native_only=True,history_replayed=False))
        saved=self.root/'inputs/.save-checkpoints';saved.mkdir();original=saved/'old-snapshot.jdb2';original.write_bytes(b'old initial names')
        self.run_cleanup();self.assertFalse((initial/'old.jdb2').exists());self.assertTrue(original.exists())
    def test_failed_old_attempt_is_reproducible_after_qualification_completed(self):
        copy=self.root/'maintenance/qualify-release/native-old/native-copy';copy.mkdir(parents=True)
        (copy/'test.jdb2').write_bytes(self.payload)
        self.write(copy.parent.parent/'receipt.json',dict(ok=True))
        result=self.run_cleanup();self.assertEqual(result['removed_bytes'],2*len(self.payload));self.assertFalse((copy/'test.jdb2').exists())
    def test_unrecognized_directory_and_archive_symlink_are_refused(self):
        bad=self.root/'inputs/native-copy';bad.mkdir()
        with self.assertRaises(ValueError):cleanup.eligible(bad,self.root)
        retained=self.root/'original.tar';self.archive.rename(retained);self.archive.symlink_to(retained)
        with self.assertRaises(ValueError):self.run_cleanup()

if __name__=='__main__':unittest.main()
