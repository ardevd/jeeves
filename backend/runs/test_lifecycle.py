"""Harmless phone lifecycle fixtures: no ADB, Frida, JEB or network required."""
import copy
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest

spec = importlib.util.spec_from_file_location('lifecycle', Path(__file__).with_name('lifecycle.py'))
assert spec is not None and spec.loader is not None
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class FixturePhone:
    def __init__(self, serial):
        self.serial = serial
        self.boot = 'boot-A'
        self.version = '17.18.0'
        self.token = None
        self.process = None
        self.foreign = []
        self.calls = []
        self.lost = False
        self.forward_lost = False
    def check(self):
        if self.lost:
            raise RuntimeError('fixture USB loss')
    def discover(self):
        self.check()
        return {'boot_id':self.boot,'root':'/debug_ramdisk/su','selinux':'Enforcing','freezer_setting':'enabled'}
    def binary(self, path):
        self.check()
        return {'executable':path,'sha256':'a'*64,'version':self.version}
    def foreign_servers(self):
        return self.foreign
    def listeners(self, port):
        return {'123'} if self.process else set()
    def owns_listener(self, identity, port):
        return self.process == identity
    def forward_matches(self, host, port):
        return not self.forward_lost
    def claim(self, token):
        self.calls.append('claim')
        module.require(self.token is None, 'Existing lease')
        self.token = token
    def owns(self, token):
        self.check()
        return token == self.token
    def lease_token(self):
        self.check();return self.token
    def launch(self, path, port):
        self.calls.append('launch')
        self.process = {'pid':111,'start_ticks':'123','executable':path,'boot_id':self.boot}
        return 111
    def identity(self, pid):
        self.check()
        return dict(self.process) if self.process else None
    def forward(self, port):
        self.calls.append('forward')
        return 40001 if self.serial == 'fixture-A' else 40002
    def terminate(self, token, identity):
        self.check()
        module.require(self.token == token and self.process == identity, 'Foreign/reused identity')
        self.calls.append('terminate')
        self.process = None
        return 'released'
    def remove_forward(self, host, port):
        self.check()
        if self.forward_lost:
            raise RuntimeError('fixture USB loss after stop')
        self.calls.append('remove_forward')
        return 'released'
    def release_claim(self, token):
        module.require(token == self.token,'foreign lease')
        self.calls.append('release_claim')
        self.token = None


class LifecycleTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix='jeeves lifecycle fixture ')
        self.addCleanup(self.temp.cleanup)
        self.phones = {s:FixturePhone(s) for s in ('fixture-A','fixture-B')}
        self.owner = module.Lifecycle('fixture-adb', '17.18.0', self.temp.name,
                                     lambda adb,serial:self.phones[serial])
    def start(self, serial='fixture-A'):
        return self.owner.provision(serial,'/fixture/frida-server',True)
    def test_explicit_device_path_and_confirmation(self):
        with self.assertRaises(RuntimeError):
            self.owner.provision('fixture-A','/fixture/frida-server',False)
        with self.assertRaises(RuntimeError):
            module.Phone('adb','')
        self.assertEqual(self.phones['fixture-A'].calls,[])
    def test_version_mismatch_and_foreign_server_never_acquire(self):
        phone=self.phones['fixture-A']
        phone.version='16.0.0'
        with self.assertRaises(RuntimeError): self.start()
        phone.version='17.18.0'; phone.foreign=['222']
        with self.assertRaises(RuntimeError): self.start()
        self.assertEqual(phone.calls,[])
    def test_foreign_lease_never_adopted_or_stopped(self):
        phone=self.phones['fixture-A']; phone.token='another-controller'
        with self.assertRaises(RuntimeError): self.start()
        self.owner.cleanup(self.owner.owned['fixture-A'])
        self.assertEqual(phone.token,'another-controller')
        self.assertEqual(phone.calls,['claim'])
        self.assertEqual(self.owner.last_cleanup['fixture-A']['cleanup'],'complete')
        phone.token=None
        fresh=self.start();self.owner.cleanup(fresh)
    def test_matching_owned_start_is_idempotent(self):
        row=self.start(); self.start()
        self.assertEqual(self.phones['fixture-A'].calls,['claim','launch','forward'])
        self.assertEqual(row['observations']['selinux'],'Enforcing')
        self.assertEqual(row['observations']['freezer_setting'],'enabled')
    def test_completed_cleanup_allows_new_explicit_run(self):
        first=self.start();self.owner.cleanup(first)
        second=self.start()
        self.assertNotEqual(first['controller_id'],second['controller_id'])
        self.assertEqual(self.phones['fixture-A'].calls.count('launch'),2)
        self.assertEqual(first['cleanup'],'complete')
        self.owner.cleanup(second)

    def test_separate_cleanup_receipt_is_refreshed_without_restart(self):
        row=self.start();phone=self.phones['fixture-A'];phone.lost=True
        self.owner.cleanup(row);phone.lost=False
        separate=module.Lifecycle('fixture-adb','17.18.0',self.temp.name,lambda adb,serial:self.phones[serial])
        saved=json.loads((Path(self.temp.name)/(row['controller_id']+'.json')).read_text())
        self.assertEqual(separate.cleanup(saved)['cleanup'],'complete')
        fresh=self.start()
        self.assertNotEqual(fresh['controller_id'],row['controller_id'])
        self.owner.cleanup(fresh)

    def test_failed_launch_without_verified_identity_keeps_unknown_evidence(self):
        phone=self.phones['fixture-A']
        def launch(path,port):raise RuntimeError('fixture launch reply lost')
        phone.launch=launch
        with self.assertRaises(RuntimeError):self.start()
        row=self.owner.owned['fixture-A']
        self.assertEqual(self.owner.cleanup(row)['cleanup'],'unknown')
        self.assertEqual(phone.token,row['controller_id'])
        self.assertNotIn('terminate',phone.calls)
        self.assertEqual(self.owner.last_cleanup['fixture-A']['cleanup'],'unknown')

    def test_start_identity_executable_and_boot_reuse_are_refused(self):
        row=self.start(); phone=self.phones['fixture-A']
        assert phone.process is not None
        original=copy.deepcopy(phone.process)
        for key, value in (('pid', 222), ('start_ticks', '456'), ('executable', '/foreign/tool')):
            mutated = copy.deepcopy(original)
            mutated[key] = value
            phone.process = mutated
            with self.subTest(key=key),self.assertRaises(RuntimeError): self.owner.verify(row)
            self.assertEqual(self.owner.cleanup(row)['cleanup'],'unknown')
            row['state']='ready'
        phone.process=original; phone.boot='boot-B'
        with self.assertRaises(RuntimeError): self.owner.verify(row)
        self.assertEqual(self.owner.cleanup(row)['cleanup'],'unknown')
        self.assertNotIn('terminate',phone.calls)
    def test_usb_loss_preserves_receipt_and_never_replays(self):
        row=self.start(); phone=self.phones['fixture-A']; phone.lost=True
        self.assertEqual(self.owner.cleanup(row)['cleanup'],'unknown')
        saved=json.loads((Path(self.temp.name)/(row['controller_id']+'.json')).read_text())
        self.assertEqual(saved['process'],row['process'])
        phone.lost=False
        with self.assertRaises(RuntimeError): self.start()
        self.assertEqual(phone.calls.count('launch'),1)
        self.assertEqual(self.owner.cleanup(row)['cleanup'],'complete')
    def test_partial_cleanup_can_resume_without_resignalling(self):
        row=self.start(); phone=self.phones['fixture-A']; phone.forward_lost=True
        self.assertEqual(self.owner.cleanup(row)['cleanup'],'unknown')
        phone.forward_lost=False
        self.assertEqual(self.owner.cleanup(row)['cleanup'],'complete')
        self.assertEqual(phone.calls.count('terminate'),1)
    def test_two_devices_and_repeat_cleanup_only_owned(self):
        a,b=self.start(),self.start('fixture-B')
        self.assertNotEqual(a['controller_id'],b['controller_id'])
        self.assertNotEqual(a['forward'],b['forward'])
        self.owner.cleanup(a); before=list(self.phones['fixture-A'].calls)
        self.owner.cleanup(a)
        self.assertEqual(self.phones['fixture-A'].calls,before)
        self.assertIsNotNone(self.phones['fixture-B'].process)
        self.owner.cleanup(b)

    def test_forward_change_refused_without_automatic_replacement(self):
        row=self.start();phone=self.phones['fixture-A'];phone.forward_lost=True
        with self.assertRaises(RuntimeError):self.owner.verify(row)
        self.assertEqual(phone.calls.count('forward'),1)


class TransportTests(unittest.TestCase):
    def test_listener_ownership_and_pid_start_parsing(self):
        phone=module.Phone('unused','fixture-A');phone.root=''
        fields=['S']+['0']*18+['12345']+['0']*5
        answers={'cat /proc/123/stat; readlink /proc/123/exe':'123 (name with spaces) '+' '.join(fields)+'\n/fixture/frida-server',
                 'cat /proc/sys/kernel/random/boot_id':'boot-fixture',
                 'cat /proc/net/tcp /proc/net/tcp6':'0: 0100007F:6A2F 00000000:0000 0A 0 0 0 0 0 900\n',
                 'for f in /proc/123/fd/*; do readlink "$f" 2>/dev/null || true; done':'socket:[900]\n/dev/null'}
        phone.shell=lambda command:answers[command]
        identity=phone.identity(123)
        self.assertEqual(identity['start_ticks'],'12345')
        self.assertTrue(phone.owns_listener(identity,27183))
        answers['for f in /proc/123/fd/*; do readlink "$f" 2>/dev/null || true; done']='socket:[901]'
        self.assertFalse(phone.owns_listener(identity,27183))
    def test_foreground_and_freeze_are_observed_without_writes(self):
        phone=module.Phone('unused','fixture-A');phone.root='';calls=[]
        answers={'dumpsys activity activities':'mResumedActivity: ActivityRecord fixture.app/.Main',
                 'cat /proc/123/status':'State: S (sleeping)','cat /proc/123/wchan':'ep_poll'}
        def shell(command):calls.append(command);return answers[command]
        phone.shell=shell
        obs=phone.foreground('fixture.app',123)
        self.assertTrue(obs['foreground'])
        self.assertFalse(obs['whole_session_guarantee'])
        answers['dumpsys activity activities']='mResumedActivity: ActivityRecord unrelated.app/.Main'
        bg=phone.foreground('fixture.app',123)
        self.assertFalse(bg['foreground'])
        answers['cat /proc/123/wchan']='do_freezer_trap'
        with self.assertRaises(RuntimeError):phone.foreground('fixture.app',123)
        self.assertTrue(all(c.startswith(('cat ','dumpsys ')) for c in calls))
    def test_foreign_forward_never_removed(self):
        phone=module.Phone('unused','fixture-A');calls=[]
        def command(*args,**kwargs):calls.append(args);return 'fixture-B tcp:40001 tcp:27183'
        phone.command=command
        with self.assertRaises(RuntimeError):phone.remove_forward(40001,27183)
        self.assertEqual(calls,[('forward','--list')])
    def test_foreign_servers_ignores_magisk_audit_log_args(self):
        phone = module.Phone('unused', 'fixture-A')
        phone.root = ''
        ps_out = (
            'PID NAME ARGS\n'
            '8775 10 10 /system/bin com.android.commands.content.Content call --uri content://com.topjohnwu.magisk.provider --extra command:s:/data/local/tmp/frida-server --version\n'
            '8776 sh sh -c /data/local/tmp/frida-server --version\n'
        )
        phone.shell = lambda cmd: ps_out
        self.assertEqual(phone.foreign_servers(), [])
        ps_with_real = ps_out + '9001 frida-server /data/local/tmp/frida-server -l 127.0.0.1:27183\n'
        phone.shell = lambda cmd: ps_with_real
        self.assertEqual(phone.foreign_servers(), ['9001'])
    def test_abi_mapping_and_ensure_server(self):
        import hashlib
        self.assertEqual(module.abi_to_frida_arch('arm64-v8a'), 'arm64')
        self.assertEqual(module.abi_to_frida_arch('x86_64'), 'x86_64')
        self.assertEqual(module.abi_to_frida_arch('armeabi-v7a'), 'arm')
        self.assertEqual(module.abi_to_frida_arch('x86'), 'x86')
        self.assertIsNone(module.abi_to_frida_arch('mips'))
        srv_dir = Path(__file__).resolve().parent / 'server'
        srv_dir.mkdir(exist_ok=True)
        fake_ver = '0.0.0-test'
        fake_bin = srv_dir / f'frida-server-{fake_ver}-android-x86_64'
        fake_bytes = b'fake-frida-server-x86_64-binary'
        fake_sha = hashlib.sha256(fake_bytes).hexdigest()
        fake_bin.write_bytes(fake_bin.read_bytes() if fake_bin.exists() else fake_bytes)
        try:
            phone = module.Phone('unused', 'fixture-A')
            phone.root = ''
            cmds = []
            installed = {'present': False}
            def command(*args, **kwargs):
                cmds.append(args)
                if args == ('shell', 'getprop', 'ro.product.cpu.abi'):
                    return 'x86_64'
                if args[0] == 'push':
                    installed['present'] = True
                    return ''
                if args[0] == 'shell':
                    c = args[1]
                    if c.startswith('readlink -f '):
                        return '/data/local/tmp/frida-server' if installed['present'] else ''
                    if c.startswith('sha256sum '):
                        if not installed['present']:
                            raise RuntimeError('missing')
                        return f'{fake_sha}  /data/local/tmp/frida-server'
                    if c.endswith(' --version'):
                        return fake_ver
                    return ''
                raise AssertionError(args)
            phone.command = command
            r1 = phone.ensure_server('/data/local/tmp/frida-server', fake_ver)
            self.assertTrue(r1['pushed'])
            self.assertEqual(r1['arch'], 'x86_64')
            self.assertEqual(r1['sha256'], fake_sha)
            r2 = phone.ensure_server('/data/local/tmp/frida-server', fake_ver)
            self.assertFalse(r2['pushed'])
        finally:
            fake_bin.unlink(missing_ok=True)


if __name__=='__main__': unittest.main(verbosity=2)
