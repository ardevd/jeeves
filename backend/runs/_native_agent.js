'use strict';
/* Native-code breakpoints for the workbench (decision: native breakpoints, 2026-09-10).
 *
 * A SECOND frida script loaded on the same session as the Java agent (_agent.js). The Java agent is
 * a minified frida-java-bridge build with no source in-repo and a Java-only hook surface; rather
 * than touch it, native breakpoints live here in a small script we own. Hits ride the SAME message
 * channel — {type:"hit", native:true, ...} — so the bridge relays them exactly like Java hits.
 *
 * A native breakpoint names a MODULE and either an EXPORT (resolved by frida, no analysis engine
 * needed) or an OFFSET from the module base (from a disassembler — e.g. idalib — for a non-exported
 * function). If the module is not loaded yet, the hook is DEFERRED and armed when the module is
 * dlopen'd, mirroring the Java agent's classloader-deferred hooks.
 */
var hooks = {};        // hookId -> { listener, spec, armed, addr, count, window, sinceBeat, lastBeat }
var dlopenWatched = false;
var REENT = {};        // tid -> inside our onEnter (re-entrancy guard: send()/backtrace may call a hooked libc fn)
var CAP_WIN_MS = 1000; // rate cap window
var CAP_N = 50;        // per hook: > CAP_N hits in the trailing window ⇒ drop full snapshots, emit a hitcount beat

function baseName(m) { var i = m.lastIndexOf('/'); return i >= 0 ? m.slice(i + 1) : m; }
function threadName(tid) { try { return Thread.nameOf ? Thread.nameOf(tid) : null; } catch (e) { return null; } }

/* Find the module a spec names. A FULL PATH binds to that exact library — several loaded modules can
   share a basename (e.g. /apex/com.android.conscrypt/lib64/libcrypto.so vs /system/lib64/libcrypto.so,
   which put the SAME function at different offsets), and an offset is valid only for the one it was
   computed against. Process.findModuleByName matches a basename and returns the first, so a path must be
   matched against the full module list. A bare basename keeps the first-match behaviour. */
function findModule(m) {
  if (m.indexOf('/') < 0) return Process.findModuleByName(m);
  var mods = Process.enumerateModules();
  var base = m.slice(m.lastIndexOf('/') + 1);
  var byName = [];
  for (var i = 0; i < mods.length; i++) {
    if (mods[i].path === m) return mods[i];            // exact path — the disambiguation we want
    if (mods[i].name === base) byName.push(mods[i]);
  }
  // No exact-path match. The pull SOURCE path can differ from frida's loaded REALPATH — a symlinked
  // /system→/apex lib, or an uncompressed in-APK lib whose module path is base.apk!/lib/…. If exactly
  // ONE loaded library carries this basename it is unambiguous → bind it (what basename did before,
  // correctly, for single-instance libs). If several do and none matched the path we cannot choose
  // safely → return null so the hook DEFERS rather than binding the wrong one.
  return byName.length === 1 ? byName[0] : null;
}

function resolveAddr(spec) {
  try {
    /* frida 17 instance API: Module.getExportByName was removed — resolve through the module handle
       (module.findExportByName / module.base), the form the Java agent uses. */
    var mod = findModule(spec.module);
    if (mod === null) return null;                     // module not loaded yet
    if (spec.export != null && spec.export !== '') {
      var ex = mod.findExportByName(spec.export);      // null when absent (no throw)
      return ex;
    }
    if (spec.offset != null)
      return mod.base.add(ptr('' + spec.offset));
  } catch (e) {
    return null;
  }
  return null;
}

function toHex(ab) {
  var u = new Uint8Array(ab), s = '';
  for (var i = 0; i < u.length; i++) { var h = u[i].toString(16); s += h.length < 2 ? '0' + h : h; }
  return s;
}

/* The GNU build-id of a loaded module, read from its .note.gnu.build-id ELF note (walked via the
   PT_NOTE program header in the mapped image). Same value the on-disk file carries, so it identifies
   the exact binary. null when the module has no build-id note or the header can't be parsed. */
function readBuildId(mod) {
  try {
    var base = mod.base;
    if (base.readU32() !== 0x464c457f) return null;    // not "\x7fELF" (LE)
    var is64 = base.add(4).readU8() === 2;
    var e_phoff   = is64 ? base.add(0x20).readU64() : base.add(0x1c).readU32();
    var e_phentsz = is64 ? base.add(0x36).readU16() : base.add(0x2a).readU16();
    var e_phnum   = is64 ? base.add(0x38).readU16() : base.add(0x2c).readU16();
    var ph = base.add(e_phoff);
    for (var i = 0; i < e_phnum; i++) {
      var p = ph.add(i * e_phentsz);
      if (p.readU32() !== 4) continue;                  // PT_NOTE == 4
      var p_vaddr  = is64 ? p.add(0x10).readU64() : p.add(0x08).readU32();
      var p_filesz = is64 ? p.add(0x20).readU64() : p.add(0x10).readU32();
      var cur = base.add(p_vaddr), end = cur.add(p_filesz);
      while (cur.compare(end) < 0) {
        var namesz = cur.readU32(), descsz = cur.add(4).readU32(), ntype = cur.add(8).readU32();
        if (namesz > 0xffff || descsz > 0xffff) break;   // malformed/hostile note — bound the walk (int32 wrap)
        var nameEnd = 12 + ((namesz + 3) & ~3);
        // NT_GNU_BUILD_ID (type 3, name "GNU"). readCString(namesz) can carry the trailing NUL as a
        // stray char, so match the prefix rather than the whole string.
        if (ntype === 3 && namesz >= 3 && (cur.add(12).readCString(namesz) || '').indexOf('GNU') === 0) {
          var ab = descsz > 0 ? cur.add(nameEnd).readByteArray(descsz) : null;
          return ab ? toHex(ab) : null;                  // empty/unreadable descriptor ⇒ null (unverified), not a false mismatch
        }
        cur = cur.add(nameEnd + ((descsz + 3) & ~3));
      }
    }
  } catch (e) { /* unreadable header/note — caller treats as unverified */ }
  return null;
}

/* Guard against binding an offset to the wrong bytes: if the bp carries an expected build-id (the one
   the pulled copy the offset was computed against had), the LOADED module must match it. A definite
   mismatch (both present, different) means a stale pull or a different same-basename library — refuse.
   Unverifiable (no note, or unreadable) arms best-effort with a flag. No expected id ⇒ nothing to check. */
function verifyBuildId(spec) {
  if (!spec.buildId) return { ok: true };
  var mod = findModule(spec.module);
  if (mod === null) return { ok: true };               // resolved a moment ago; don't block on a race
  var actual = readBuildId(mod);
  if (actual === null) return { ok: true, unverified: true };
  if (actual.toLowerCase() !== String(spec.buildId).toLowerCase())
    return { ok: false, actual: actual };
  return { ok: true, actual: actual };
}

/* resolve a libc/libdl global export (dlopen etc.) across the frida-17 API surface */
function globalExport(name) {
  try { if (typeof Module.findGlobalExportByName === 'function') return Module.findGlobalExportByName(name); }
  catch (e) { /* fall through */ }
  var mods = ['libdl.so', 'libc.so'];
  for (var i = 0; i < mods.length; i++) {
    var m = Process.findModuleByName(mods[i]);
    if (m) { var p = m.findExportByName(name); if (p) return p; }
  }
  return null;
}

function symbolLabel(spec) {
  if (spec.export != null && spec.export !== '') return spec.export;
  if (spec.offset != null) {
    var n = ('' + spec.offset);
    return '+' + (n.indexOf('0x') === 0 ? n : '0x' + Number(spec.offset).toString(16));
  }
  return '?';
}

function readArgs(args) {
  var out = [];
  for (var i = 0; i < 4; i++) {
    try { out.push({ type: 'pointer', repr: String(args[i]) }); }
    catch (e) { out.push(null); }
  }
  return out;
}

function attach(spec, addr) {
  var hookId = spec.hookId;
  var h = hooks[hookId] || { spec: spec };
  h.spec = spec; h.armed = true; h.addr = String(addr);
  h.count = h.count || 0; h.window = h.window || []; h.sinceBeat = h.sinceBeat || 0; h.lastBeat = h.lastBeat || 0;
  hooks[hookId] = h;
  h.listener = Interceptor.attach(addr, {
    onEnter: function (args) {
      var tid = Process.getCurrentThreadId();
      // Re-entrancy guard: send()/backtrace/DebugSymbol may themselves call a hooked libc fn
      // (malloc/memcpy/read…) on this same thread — bail rather than recurse into ourselves.
      if (REENT[tid]) return;
      REENT[tid] = true;
      try {
        // Rate cap, mirroring the Java agent (_agent.js): keep a trailing CAP_WIN_MS window of hit
        // timestamps; once it exceeds CAP_N, DROP the full snapshot (expensive — ACCURATE backtrace +
        // DebugSymbol ×12) and emit a `hitcount` heartbeat at most once per window carrying the
        // dropped/count totals, which the bridge relays to RunsManager.onHitCount.
        var now = Date.now();
        h.count++;
        var w = h.window;
        w.push(now);
        while (w.length && w[0] <= now - CAP_WIN_MS) w.shift();
        if (w.length > CAP_N) {
          h.sinceBeat++;
          if (now - h.lastBeat >= CAP_WIN_MS) {
            send({ type: 'hitcount', hookId: hookId, ts: now, dropped: h.sinceBeat, count: h.count });
            h.sinceBeat = 0; h.lastBeat = now;
          }
          return;
        }
        var bt = [];
        try {
          bt = Thread.backtrace(this.context, Backtracer.ACCURATE)
            .slice(0, 12)
            .map(function (a) { return String(DebugSymbol.fromAddress(a)); });
        } catch (e) { /* backtrace best-effort */ }
        send({
          type: 'hit', native: true, hookId: hookId,
          module: spec.module, symbol: symbolLabel(spec), addr: String(addr),
          args: readArgs(args),
          thread: { tid: tid, name: threadName(tid) },
          stack: bt,          // symbolized native backtrace (strings) — the backend's recordHit reads `stack`
          ts: now
        });
      } finally {
        delete REENT[tid];    // drop the key rather than leave a false — REENT stays bounded by live tids
      }
    }
  });
}

/* arm the dlopen watch once — after any library loads, retry every still-disarmed hook. Retrying all
   (rather than keying pending hooks by name) is simple and correct for both basename and full-path specs:
   resolveAddr decides whether each one's module is now present. */
function watchDlopen() {
  if (dlopenWatched) return;
  dlopenWatched = true;
  ['android_dlopen_ext', 'dlopen'].forEach(function (fn) {
    var p = globalExport(fn);
    if (p === null) return;
    Interceptor.attach(p, {
      onLeave: function () {
        Object.keys(hooks).forEach(function (hookId) {
          var h = hooks[hookId];
          if (!h || h.armed || h.rejected) return;     // rejected = a terminal build-id mismatch
          var addr = resolveAddr(h.spec);
          if (addr === null) return;                   // its module still isn't loaded
          var v = verifyBuildId(h.spec);
          if (!v.ok) { h.rejected = true;
            send({ type: 'native-mismatch', hookId: hookId, module: h.spec.module, expected: h.spec.buildId, actual: v.actual }); return; }
          try { attach(h.spec, addr); }
          catch (e) { send({ type: 'native-armfail', hookId: hookId, module: h.spec.module, error: String(e) }); return; }
          if (v.unverified) send({ type: 'native-unverified', hookId: hookId, module: h.spec.module });
          send({ type: 'native-armed', hookId: hookId, module: h.spec.module, addr: String(addr) });
        });
      }
    });
  });
}

rpc.exports = {
  ping: function () { return { ok: true, native: true, hooks: Object.keys(hooks).length }; },
  /* spec: { hookId, module, export? , offset? } */
  addNativeHook: function (spec) {
    var ex = hooks[spec.hookId];
    if (ex) return { ok: true, addr: ex.addr || null, already: true, deferred: !ex.armed };
    var addr = resolveAddr(spec);
    if (addr === null) {
      // module not loaded (or export missing in a loaded module). If the module simply isn't loaded
      // yet, defer and arm on the next dlopen; otherwise the caller sees ok:false.
      var loaded = findModule(spec.module) !== null;
      if (!loaded) {
        hooks[spec.hookId] = { listener: null, spec: spec, armed: false, addr: null };  // attach() inits the rate-cap state
        watchDlopen();
        return { ok: true, deferred: true };
      }
      return { ok: false, reason: 'export not found in ' + spec.module };
    }
    var v = verifyBuildId(spec);
    if (!v.ok) return { ok: false, mismatch: true, actual: v.actual, expected: spec.buildId,
      reason: 'build-id mismatch on ' + spec.module + ': loaded ' + v.actual + ' != expected ' + spec.buildId };
    try { attach(spec, addr); }
    catch (e) { return { ok: false, reason: 'attach failed at ' + String(addr) + ': ' + e }; }  // non-exec / bad addr
    return v.unverified ? { ok: true, addr: String(addr), unverified: true } : { ok: true, addr: String(addr) };
  },
  removeNativeHook: function (hookId) {
    var h = hooks[hookId];
    if (!h) return { removed: false };
    if (h.listener) { try { h.listener.detach(); } catch (e) { /* */ } }
    delete hooks[hookId];
    return { removed: true };
  },
  listNativeHooks: function () { return Object.keys(hooks); }
};

send({ type: 'native-ready', loaded: true });
