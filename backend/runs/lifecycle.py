"""Owned local Android instrumentation resources; no provisioning or global setting changes.

The caller explicitly selects a device, an already installed executable and exclusive
controller ownership. A server/lease left by another controller is never adopted.
Recover inspects only this controller's durable receipt; it never reattaches/replays.
"""
import hashlib
import json
import os
from pathlib import Path
import re
import shlex
import subprocess
import time
import uuid

LEASE = '/data/local/tmp/jeeves-local-run-owner'
DEFAULT_SERVER_PATH = '/data/local/tmp/frida-server'


def abi_to_frida_arch(abi):
    abi = (abi or '').strip()
    if abi in ('arm64-v8a', 'arm64', 'android-arm64'):
        return 'arm64'
    if abi in ('x86_64', 'android-x86_64'):
        return 'x86_64'
    if abi in ('armeabi-v7a', 'armeabi', 'arm', 'android-arm'):
        return 'arm'
    if abi in ('x86', 'android-x86'):
        return 'x86'
    return None


def require(ok, message):
    if not ok:
        raise RuntimeError(message)


def atomic_json(path, value):
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
    temp = path.with_name(path.name + '.' + uuid.uuid4().hex + '.tmp')
    try:
        with temp.open('x', encoding='utf-8') as stream:
            os.chmod(temp, 0o600)
            json.dump(value, stream, sort_keys=True, allow_nan=False)
            stream.write('\n')
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temp, path)
    finally:
        temp.unlink(missing_ok=True)


class Phone:
    def __init__(self, adb, serial):
        require(isinstance(serial, str) and bool(serial.strip()) and not any(c.isspace() for c in serial),
                'An explicit device serial is required')
        self.adb, self.serial, self.root = adb, serial, None

    def command(self, *args, timeout=10):
        result = subprocess.run([self.adb, '-s', self.serial, *args], capture_output=True,
                                text=True, stdin=subprocess.DEVNULL, timeout=timeout)
        require(result.returncode == 0, 'Device command failed; cleanup may be unknown')
        return result.stdout.strip()

    def shell(self, text):
        require(self.root is not None, 'Root discovery has not completed')
        return self.command('shell', text if not self.root else self.root + ' -c ' + shlex.quote(text))

    def discover(self):
        require(self.command('get-state') == 'device', 'Selected device is unavailable')
        if self.command('shell', 'id -u') == '0':
            self.root = ''
        else:
            for candidate in ('/debug_ramdisk/su', '/system/bin/su', '/system/xbin/su', '/system_ext/bin/su', 'su'):
                try:
                    if self.command('shell', candidate + ' -c ' + shlex.quote('id -u')) == '0':
                        self.root = candidate
                        break
                except (RuntimeError, subprocess.TimeoutExpired):
                    continue
        require(self.root is not None, 'No usable root command; no adb restart or security-setting change was attempted')
        return {'root': self.root or 'adbd-already-root',
                'boot_id': self.shell('cat /proc/sys/kernel/random/boot_id'),
                'selinux': self.shell('getenforce'),
                'freezer_setting': self.shell('settings get global cached_apps_freezer')}

    def binary(self, path):
        require(isinstance(path, str) and re.fullmatch(r'/[A-Za-z0-9_./-]+', path)
                and '..' not in path.split('/'), 'Select an explicit installed server executable path')
        canonical = self.shell('readlink -f ' + shlex.quote(path))
        require(canonical == path, 'Server path must be canonical, not a symlink')
        sha = self.shell('sha256sum ' + shlex.quote(path)).split()[0]
        require(re.fullmatch(r'[0-9a-f]{64}', sha), 'Cannot measure server executable digest')
        version = self.shell(shlex.quote(path) + ' --version')
        require(re.fullmatch(r'[0-9]+\.[0-9]+\.[0-9]+', version), 'Cannot measure server version')
        return {'executable': path, 'sha256': sha, 'version': version}

    def ensure_server(self, path, version):
        require(isinstance(path, str) and re.fullmatch(r'/[A-Za-z0-9_./-]+', path)
                and '..' not in path.split('/'), 'Select an explicit installed server executable path')
        if self.root is None:
            try:
                self.discover()
            except (RuntimeError, subprocess.TimeoutExpired):
                self.root = None

        def run_sh(cmd):
            if self.root is not None:
                return self.shell(cmd)
            return self.command('shell', cmd)

        abi = self.command('shell', 'getprop', 'ro.product.cpu.abi').strip()
        arch = abi_to_frida_arch(abi)
        if arch is None:
            raise RuntimeError(f'Unsupported device ABI for frida-server: {abi or "unknown"}')
        here = Path(__file__).resolve().parent
        host_bin = here / 'server' / f'frida-server-{version}-android-{arch}'
        if not host_bin.is_file() or host_bin.stat().st_size == 0:
            pull_script = here / 'pull-frida.sh'
            require(pull_script.is_file(), f'Missing host binary {host_bin.name} and pull-frida.sh')
            res = subprocess.run(['bash', str(pull_script), arch], capture_output=True, text=True,
                                 stdin=subprocess.DEVNULL, timeout=180)
            require(res.returncode == 0 and host_bin.is_file() and host_bin.stat().st_size > 0,
                    f'Failed to pull {host_bin.name}: {(res.stderr or res.stdout).strip()}')
        host_sha = hashlib.sha256(host_bin.read_bytes()).hexdigest()
        already_installed = False
        try:
            canonical = run_sh('readlink -f ' + shlex.quote(path))
            dev_sha = run_sh('sha256sum ' + shlex.quote(path)).split()[0]
            dev_ver = run_sh(shlex.quote(path) + ' --version')
            already_installed = canonical == path and dev_sha == host_sha and dev_ver == version
        except (RuntimeError, subprocess.TimeoutExpired, IndexError):
            already_installed = False
        if already_installed:
            return {'pushed': False, 'arch': arch, 'serverBin': str(host_bin),
                    'deviceServerPath': path, 'sha256': host_sha, 'version': version}
        tmp_remote = f'{path}.tmp.{uuid.uuid4().hex[:8]}'
        try:
            self.command('push', str(host_bin), tmp_remote, timeout=60)
            run_sh(f'chmod 0755 {shlex.quote(tmp_remote)} && mv -f {shlex.quote(tmp_remote)} {shlex.quote(path)}')
        finally:
            try:
                run_sh(f'rm -f {shlex.quote(tmp_remote)}')
            except (RuntimeError, subprocess.TimeoutExpired):
                tmp_remote = ''
        return {'pushed': True, 'arch': arch, 'serverBin': str(host_bin),
                'deviceServerPath': path, 'sha256': host_sha, 'version': version}

    def foreign_servers(self):
        # Advisory detection, not an ownership proof. Renamed/invisible controllers cannot
        # universally be detected; the explicit exclusive-controller declaration is still required.
        rows = self.shell('ps -A -o PID,NAME,ARGS')
        found = []
        for line in rows.splitlines()[1:]:
            parts = line.split(None, 2)
            if len(parts) < 2:
                continue
            pid, name = parts[0], parts[1]
            args = parts[2] if len(parts) > 2 else ''
            tokens = args.split()
            if '--version' in tokens:
                continue
            exe_name = tokens[0].rsplit('/', 1)[-1] if tokens else ''
            if re.search(r'frida[-_](server|gadget)', name, re.I) or re.search(r'frida[-_](server|gadget)', exe_name, re.I):
                found.append(pid)
        return found

    def claim(self, token):
        self.shell('umask 077; mkdir ' + LEASE + ' && printf %s ' + shlex.quote(token) + ' > ' + LEASE + '/token')

    def listeners(self, port):
        found = set()
        for line in self.shell('cat /proc/net/tcp /proc/net/tcp6').splitlines():
            fields = line.split()
            if len(fields) >= 10 and fields[3] == '0A' and fields[1].split(':')[-1].lower() == '%04x' % port:
                found.add(fields[9])
        return found

    def owns_listener(self, identity, port):
        require(self.identity(identity['pid']) == identity, 'Server identity changed while checking listener')
        files = self.shell('for f in /proc/' + str(identity['pid']) + '/fd/*; do readlink "$f" 2>/dev/null || true; done')
        sockets = set(re.findall(r'socket:\[(\d+)\]', files))
        listeners = self.listeners(port)
        return bool(listeners) and listeners.issubset(sockets)

    def forward_matches(self, host, port):
        rows = [line.split() for line in self.command('forward', '--list').splitlines()]
        return [self.serial, 'tcp:' + str(host), 'tcp:' + str(port)] in rows

    def owns(self, token):
        return self.shell('cat ' + LEASE + '/token') == token

    def lease_token(self):
        value=self.shell('if [ ! -e '+LEASE+' ]; then printf absent; elif [ -f '+LEASE+'/token ]; then printf token:; cat '+LEASE+'/token; else printf unknown; fi')
        if value=='absent': return None
        require(value.startswith('token:') and len(value)>6,'Lease token is unavailable; ownership remains unknown')
        return value[6:]

    def launch(self, path, port):
        text = 'setsid ' + shlex.quote(path) + ' -l 127.0.0.1:' + str(port)
        output = self.shell(text + ' >' + LEASE + '/server.log 2>&1 </dev/null & p=$!; '
                            'printf %s "$p" > ' + LEASE + '/pid; printf %s "$p"')
        require(output.isdigit(), 'Server launch did not return a PID; cleanup is unknown')
        return int(output)

    def identity(self, pid):
        require(type(pid) is int and pid > 1, 'Invalid process PID')
        text = self.shell('cat /proc/' + str(pid) + '/stat; readlink /proc/' + str(pid) + '/exe')
        rows = text.splitlines()
        require(len(rows) == 2 and ') ' in rows[0], 'Process identity unavailable')
        fields = rows[0].rsplit(') ', 1)[1].split()
        require(len(fields) >= 20, 'Process start identity unavailable')
        return {'pid': pid, 'start_ticks': fields[19], 'executable': rows[1],
                'boot_id': self.shell('cat /proc/sys/kernel/random/boot_id')}

    def forward(self, port):
        host = self.command('forward', 'tcp:0', 'tcp:' + str(port))
        require(host.isdigit() and 0 < int(host) < 65536, 'Cannot identify owned ADB forward')
        return int(host)

    def remove_forward(self, host, port):
        rows = [line.split() for line in self.command('forward', '--list').splitlines()]
        expected = [self.serial, 'tcp:' + str(host), 'tcp:' + str(port)]
        matches = [row for row in rows if len(row) == 3 and row[1] == expected[1]]
        if not matches:
            return 'absent'
        require(matches == [expected], 'Forward ownership changed; leaving it untouched')
        self.command('forward', '--remove', expected[1])
        return 'released'

    def terminate(self, token, identity):
        require(self.owns(token), 'Device lease is foreign; leaving it untouched')
        present = self.shell('if [ -e /proc/' + str(identity['pid']) + '/stat ]; then echo present; else echo absent; fi')
        if present == 'absent':
            return 'absent'
        require(self.identity(identity['pid']) == identity, 'PID/start/executable identity changed; leaving it untouched')
        # Repeat the identity test in the same shell as the signal, never a process-name kill.
        pid = str(identity['pid'])
        start = shlex.quote(identity['start_ticks'])
        exe = shlex.quote(identity['executable'])
        self.shell('s=$(cat /proc/' + pid + '/stat) && s=${s##*) }; set -- $s; '
                   '[ "${20}" = ' + start + ' ] && [ "$(readlink /proc/' + pid + '/exe)" = ' + exe + ' ] && kill -TERM ' + pid)
        for _ in range(20):
            if self.shell('if [ -e /proc/' + pid + '/stat ]; then echo present; else echo absent; fi') == 'absent':
                return 'released'
            time.sleep(0.1)
        raise RuntimeError('Owned server did not exit; no escalation to a broader kill')

    def release_claim(self, token):
        require(self.owns(token), 'Device lease changed; leaving it untouched')
        self.shell('rm -f ' + LEASE + '/pid ' + LEASE + '/server.log ' + LEASE + '/token && rmdir ' + LEASE)

    def foreground(self, package, pid):
        require(re.fullmatch(r'[A-Za-z0-9_.]+', package or ''), 'Invalid package identifier')
        activity = self.shell('dumpsys activity activities')
        resumed = any(re.search(r'(mResumedActivity|topResumedActivity).*\b' + re.escape(package) + r'/', line)
                      for line in activity.splitlines())
        status = self.shell('cat /proc/' + str(pid) + '/status')
        wchan = self.shell('cat /proc/' + str(pid) + '/wchan')
        require(not re.search(r'^State:\s*[TtZ]', status, re.M) and 'freez' not in wchan.lower(),
                'Selected process is stopped/frozen; no global freezer change is permitted')
        return {'foreground': bool(resumed), 'freeze_observation': 'no stopped state/freezer wait observed',
                'basis': 'measured', 'whole_session_guarantee': False}


class Lifecycle:
    def __init__(self, adb, version, directory, phone_factory=Phone):
        self.adb, self.version, self.directory, self.factory = adb, version, Path(directory), phone_factory
        self.owned = {}
        self.last_cleanup = {}

    def save(self, row):
        atomic_json(self.directory / (row['controller_id'] + '.json'), row)

    def provision(self, serial, path, ownership_confirmed, port=27183):
        if not path:
            path = DEFAULT_SERVER_PATH
        require(ownership_confirmed is True, 'Explicit exclusive-controller confirmation required; use import for an external controller')
        require(type(port) is int and 1024 <= port <= 65535, 'Invalid selected device server port')
        if serial in self.owned:
            row = self.owned[serial]
            saved=json.loads((self.directory/(row['controller_id']+'.json')).read_text())
            require(saved['controller_id']==row['controller_id'] and saved['device']==serial,'Stored ownership changed')
            if saved.get('cleanup')=='complete': self.owned.pop(serial)
            else:
                self.owned[serial]=row=saved
                self.verify(row)
                require(path==row['server']['executable'] and port==row['port'],'Existing owned session uses different launch inputs; clean it up first')
                return row
        self.last_cleanup.pop(serial,None)
        phone = self.factory(self.adb, serial)
        observations = phone.discover()
        if path == DEFAULT_SERVER_PATH and hasattr(phone, 'ensure_server'):
            phone.ensure_server(path, self.version)
        binary = phone.binary(path)
        require(binary['version'] == self.version, 'Measured Frida client/server version mismatch')
        require(not phone.foreign_servers(), 'Existing foreign instrumentation server detected; it will not be adopted or stopped')
        require(not phone.listeners(port), 'Selected device port is already in use; no existing listener is adopted')
        row = {'controller_id': str(uuid.uuid4()), 'device': serial, 'port': port,
               'client_version': self.version, 'server': binary, 'observations': observations,
               'process': None, 'launch_pid': None, 'launch_attempted': False, 'forward': None, 'claim_attempted': False, 'claimed': False, 'cleanup': 'unknown', 'state': 'starting'}
        self.save(row)  # durable intent before resource acquisition
        self.owned[serial] = row
        try:
            row['claim_attempted'] = True
            self.save(row)
            phone.claim(row['controller_id'])
            row['claimed'] = True
            self.save(row)
            row['launch_attempted']=True
            self.save(row)
            pid = phone.launch(path, port)
            row['launch_pid']=pid
            self.save(row)
            # The shell may return before setsid has exec'd the selected binary. Never
            # accept a different start identity while waiting for that one exec transition.
            identity = phone.identity(pid)
            row['initial_process']=identity
            self.save(row)
            initial_start = (identity['start_ticks'], identity['boot_id'])
            for _ in range(30):
                require((identity['start_ticks'], identity['boot_id']) == initial_start,
                        'PID changed during launch; cleanup is unknown')
                if identity['executable'] == path:
                    break
                time.sleep(0.1)
                identity = phone.identity(pid)
            require(identity['executable'] == path and identity['boot_id'] == observations['boot_id'],
                    'Started server identity does not match the selected executable/boot')
            row['process'] = identity
            self.save(row)
            for _ in range(30):
                if phone.owns_listener(identity, port):
                    break
                time.sleep(0.1)
            else:
                raise RuntimeError('Started process did not own the selected listening socket')
            row['forward'] = phone.forward(port)
            row['state'] = 'ready'
            self.save(row)
            return row
        except Exception:
            row['state'] = 'failed'
            self.save(row)
            # Preserve the receipt. A partially acquired launch needs explicit recovery,
            # not blind guessing of a PID or automatic replay.
            raise

    def verify(self, row):
        require(row['state'] == 'ready', 'Owned session is not ready; inspect its receipt')
        phone = self.factory(self.adb, row['device'])
        observations = phone.discover()
        require(observations['boot_id'] == row['observations']['boot_id'] and phone.owns(row['controller_id']),
                'Stale device ownership; no automatic reconnect or replay')
        require(phone.identity(row['process']['pid']) == row['process'], 'Owned server identity changed')
        require(phone.owns_listener(row['process'], row['port']), 'Device listener is not owned by this server')
        require(phone.forward_matches(row['forward'], row['port']), 'ADB forward changed; no automatic replacement')
        require(phone.binary(row['server']['executable']) == row['server'], 'Server executable/version changed')
        return phone

    def cleanup(self, row):
        saved=json.loads((self.directory/(row['controller_id']+'.json')).read_text())
        require(saved['controller_id']==row['controller_id'] and saved['device']==row['device'],'Stored cleanup identity changed')
        if saved.get('cleanup') == 'complete':
            self.last_cleanup[row['device']]=saved
            current=self.owned.get(row['device'])
            if current and current['controller_id']==row['controller_id']: self.owned.pop(row['device'])
            return saved
        row.update(saved)
        results = list(row.get('cleanup_results', []))
        done = {r['resource'] for r in results if r['state'] in ('released', 'absent')}
        try:
            phone = self.factory(self.adb, row['device'])
            observations = phone.discover()
            require(observations['boot_id'] == row['observations']['boot_id'], 'Device boot changed; cleanup unknown')
            if row.get('claim_attempted') and not row.get('claimed'):
                require(not row.get('launch_attempted'),'Unverified launch; cleanup unknown')
                token=phone.lease_token()
                if token==row['controller_id']:
                    phone.release_claim(row['controller_id'])
                    results.append({'resource':'lease','state':'released'})
                else:
                    results.append({'resource':'lease','state':'absent','detail':'No owned lease; any foreign token was left untouched'})
            if row['claimed']:
                require(phone.owns(row['controller_id']), 'Lease is foreign; cleanup unknown')
                if row['process'] is None and not row.get('launch_attempted',True):
                    phone.release_claim(row['controller_id'])
                    results.append({'resource':'lease','state':'released'})
                    row['claimed']=False
                require(not row['claimed'] or row['process'] is not None,
                        'Launch identity was not captured; retain receipt and lease for manual owner inspection; no unverified process will be signalled')
                if row['claimed'] and 'server' not in done:
                    results.append({'resource': 'server', 'state': phone.terminate(row['controller_id'], row['process'])})
                    row['cleanup_results'] = results
                    self.save(row)
                if row['forward'] is not None and 'forward' not in done:
                    results.append({'resource': 'forward', 'state': phone.remove_forward(row['forward'], row['port'])})
                    row['cleanup_results'] = results
                    self.save(row)
                if row['claimed']:
                    phone.release_claim(row['controller_id'])
                    results.append({'resource': 'lease', 'state': 'released'})
            row['cleanup'], row['state'] = 'complete', 'stopped'
        except Exception as error:
            row['cleanup'], row['state'] = 'unknown', 'stopped'
            results.append({'resource': 'unresolved', 'state': 'unknown', 'error': type(error).__name__})
        row['cleanup_results'] = results
        self.last_cleanup[row['device']]=row
        self.save(row)
        if row['cleanup'] == 'complete' and self.owned.get(row['device'],{}).get('controller_id')==row['controller_id']:
            self.owned.pop(row['device'], None)
        return row
