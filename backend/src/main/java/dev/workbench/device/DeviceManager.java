package dev.workbench.device;

import dev.workbench.registry.Json;
import dev.workbench.registry.OpException;
import java.io.File;
import java.nio.file.Files;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Live devices as target sources (DEVICE_SPEC): adb catalog, browse, pull.
 * Talks to `adb` directly (ProcessBuilder; the frida sidecar owns instrumentation,
 * this is the inventory side). Safety per §5: read-only except device.pull
 * (device→host). All device paths are passed as single argv elements and
 * validated against shell metacharacters — no injection surface.
 *
 * Catalogs persist per project: backend/.workbench/devices/&lt;serial&gt;.catalog.json;
 * pulled artifacts land in backend/.workbench/pulled/ with sha256 provenance.
 */
public final class DeviceManager {

    private final File devicesDir;
    private final File pulledDir;
    /* serials whose catalog read failed transiently (fix-1-r3): the on-disk
       catalog may be healthy — the next writeCatalog parks it aside as evidence
       before overwriting */
    private final java.util.Set<String> degradedCatalogs = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final Map<String, long[]> rootCache = new ConcurrentHashMap<>();   // serial -> {isRoot, ts} (r2 M1: the value used to sit where the timestamp belonged, so the 30 s cache never held)
    private static final int PKG_CAP = 2000, LIB_CAP = 300, PROC_CAP = 200, SVC_CAP = 500;
    /** /apex subdirs we descend into for libs — each one is its own adb round trip. */
    private static final int APEX_CAP = 60, APEX_LIB_CAP = 20, PART_CAP = 200;
    /** Tombstone rows carried in the response (diff.removed keeps the true count). */
    private static final int TOMB_CAP = 500;

    public DeviceManager(File workbenchDir) {
        this.devicesDir = new File(workbenchDir, "devices");
        this.pulledDir = new File(workbenchDir, "pulled");
    }

    // ------------------------------------------------------------ adb plumbing

    // WORKBENCH_ADB overrides the binary — needed when adb talks to a remote
    // server (ADB_SERVER_SOCKET tunnel): the client version must match the
    // remote server (a 36.x client against a 37.x server kills it), and the
    // SDK adb below may not be that version.
    private static final String[] ADB_CANDIDATES = {
            System.getenv("WORKBENCH_ADB"),
            System.getProperty("user.home") + "/Library/Android/sdk/platform-tools/adb",   // Android Studio, macOS
            System.getProperty("user.home") + "/Android/Sdk/platform-tools/adb",           // Android Studio, Linux
            System.getenv("ANDROID_HOME") == null ? null : System.getenv("ANDROID_HOME") + "/platform-tools/adb",
            System.getenv("ANDROID_SDK_ROOT") == null ? null : System.getenv("ANDROID_SDK_ROOT") + "/platform-tools/adb",
            "adb" };

    /** The adb binary adb() will run, resolved to a file — or null when none exists anywhere
     *  (candidates above, then PATH). system.doctor's "adb" row. */
    public static String adbBinary() {
        for (String c : ADB_CANDIDATES) {
            if (c == null || c.isBlank()) continue;
            if (c.contains("/")) { File f = new File(c); if (f.isFile() && f.canExecute()) return f.getAbsolutePath(); continue; }
            String path = System.getenv("PATH");
            if (path != null)
                for (String d : path.split(File.pathSeparator)) {
                    if (!d.startsWith("/")) continue;   // "." or an empty entry would resolve against the server cwd / root
                    File f = new File(d, c);
                    if (f.isFile() && f.canExecute()) return f.getAbsolutePath();
                }
        }
        return null;
    }

    private static String adb() {
        String b = adbBinary();          // one resolver (executable file, PATH) for ops and the doctor
        return b == null ? "adb" : b;
    }

    /** Run adb with args (each a single argv element — no shell between us and the device). */
    private static String[] run(List<String> args, long timeoutSec) throws OpException {
        return run(args, timeoutSec, null);
    }

    /** Run adb with args; stdinData (nullable) is piped to the process's stdin.
     *  CONSTRAINT: stdinData must stay well under the pipe buffer (~64KB) — the
     *  write completes before stdout is read, so a larger payload with a
     *  concurrently-streaming child would deadlock. Scripts here are ~1KB. */
    private static String[] run(List<String> args, long timeoutSec, String stdinData) throws OpException {
        List<String> cmd = new ArrayList<>();
        cmd.add(adb());
        cmd.addAll(args);
        java.nio.file.Path tmp = null;
        try {
            /* output goes to a file, not a pipe drained to EOF before waitFor: that let a
               child holding stdout open (a su awaiting an on-screen grant, a wedged shell
               service) ignore timeoutSec entirely (prov review r4 H1). CRLF is folded like
               readLine did; malformed bytes are replaced, not thrown. */
            tmp = Files.createTempFile("wb-adb-", ".out");
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.to(tmp.toFile())).start();
            if (stdinData != null) {
                try (java.io.OutputStream os = p.getOutputStream()) {
                    os.write(stdinData.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                }
            } else {
                p.getOutputStream().close();
            }
            boolean done = p.waitFor(timeoutSec, TimeUnit.SECONDS);
            if (!done) {
                p.destroyForcibly();
                throw OpException.busy("adb timed out: " + String.join(" ", args), "");
            }
            String out = new String(Files.readAllBytes(tmp), java.nio.charset.StandardCharsets.UTF_8).replace("\r\n", "\n");
            return new String[] { String.valueOf(p.exitValue()), out };
        } catch (OpException e) {
            throw e;
        } catch (Exception e) {
            throw OpException.busy("adb failed: " + e.getMessage(), "");
        } finally {
            if (tmp != null) try { Files.deleteIfExists(tmp); } catch (Exception ignore) { }
        }
    }

    private static String[] adb(String serial, String... args) throws OpException {
        List<String> a = new ArrayList<>();
        if (serial != null) {
            a.add("-s");
            a.add(serial);
        }
        a.addAll(List.of(args));
        return run(a, 25);
    }

    private boolean adbdIsRoot(String serial) throws OpException {
        long[] cached = rootCache.get(serial);
        long now = System.currentTimeMillis();
        if (cached != null && now - cached[1] < 30_000) return cached[0] == 1L;
        String[] r = adb(serial, "shell", "id", "-u");
        boolean root = "0".equals(r[0]) && r[1].trim().equals("0");
        rootCache.put(serial, new long[] { root ? 1L : 0L, now });
        return root;
    }

    /** `adb devices -l` with every line's RAW state — device / unauthorized / offline /
     *  "no permissions" — and no per-device shelling (list() drops non-"device" lines and
     *  costs ~5 adb round trips per device). For system.doctor and the preflight row. */
    public List<Map<String, Object>> listFast() throws OpException {
        String[] r = run(new ArrayList<>(List.of("devices", "-l")), 15);
        /* stderr is merged into r[1]: a client that cannot reach its server prints log lines
           that parse as devices — refuse on rc, and read only the body after the header (r5 M1) */
        if (!"0".equals(r[0])) throw OpException.busy("adb devices failed: " + r[1].trim(), "");
        List<Map<String, Object>> out = new ArrayList<>();
        boolean body = false;
        for (String line : r[1].split("\n")) {
            line = line.trim();
            if (!body) { if (line.startsWith("List of devices")) body = true; continue; }
            if (line.isEmpty() || line.startsWith("*")) continue;
            String[] f = line.split("\\s+");
            if (f.length < 2) continue;
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("serial", f[0]);
            d.put("state", line.contains("no permissions") ? "no permissions" : f[1]);
            out.add(d);
        }
        return out;
    }

    /* ONE root probe for the bars and the doctor (r4 H2): rootCapability's 8 s inline probes,
       its "a wedged shell is ?" rule, cached 30 s per serial — the device bar and runs.devices
       ask on every refresh, and a Magisk grant prompt must not be re-triggered each time */
    private final Map<String, Object[]> rootOfCache = new ConcurrentHashMap<>();

    /** adbd-root | su | none | "?" for a serial (rootCapability behind a 30 s cache) — device.list
        and runs.devices rows carry it. "?" = the device is not answering shell commands: not
        "unrooted". */
    public String rootOf(String serial) { return rootOf(serial, true); }

    /** probe=false (the 10 s device poll, the ribbon): answer from the cache at ANY age, "?"
        when never probed — a Magisk grant prompt must come from an explicit REFRESH, an attach
        or the doctor, never from a timer (dilemma 4, 2026-09-10). probe=true refreshes a stale
        (30 s) entry. */
    public String rootOf(String serial, boolean probe) {
        Object[] c = rootOfCache.get(serial);
        long now = System.currentTimeMillis();
        if (c != null && (!probe || now - (Long) c[1] < 30_000)) return String.valueOf(c[0]);
        if (!probe) return "?";
        String root = String.valueOf(rootCapability(serial).get("root"));
        rootOfCache.put(serial, new Object[] { root, now });
        return root;
    }

    /** Root capability for the preflight/doctor rows: "adbd-root" (emulators, userdebug after
     *  `adb root` — commands run plainly), "su" (a rooted device: the `su root <cmd>` form this
     *  class uses everywhere — toybox su has no -c; Magisk/KernelSU/APatch accept it too) or
     *  "none". Also reports SELinux when a root path exists (the sidecar sets permissive at
     *  attach) and whether a frida-server is up. Short timeouts: a hung su must not stall the row. */
    public Map<String, Object> rootCapability(String serial) {
        Map<String, Object> m = new LinkedHashMap<>();
        String root = "none";
        try {
            /* inline 8 s probes on purpose: adbdIsRoot()/fridaStatus() ride the 25 s adb()
               path (+ a su retry), which is 58 s per unrooted device inside one op */
            String[] r = run(new ArrayList<>(List.of("-s", serial, "shell", "id", "-u")), 8);
            if ("0".equals(r[0]) && r[1].trim().equals("0")) root = "adbd-root";
            else {
                /* WHO form + `sh -c` (2026-09-10, verified on a Magisk Pixel 9): the bare-flag
                   form `su root id -u` makes MagiskSU parse `-u` as an option and print usage
                   (rc=2) — it never detected su-root on the most common real-world root. Wrapping
                   the command in `sh -c '…'` keeps the toybox-friendly `su root` WHO form while the
                   `-u` sits inside the quoted command where no su parses it as an option. */
                r = run(new ArrayList<>(List.of("-s", serial, "shell", "su", "root", "sh", "-c", "'id -u'")), 8);
                if ("0".equals(r[0]) && r[1].trim().equals("0")) root = "su";
            }
        } catch (OpException e) {
            m.put("error", e.getMessage());
            root = "?";   // a wedged shell is not "unrooted" (r5 L3)
        }
        m.put("root", root);
        if (!"none".equals(root)) {
            try {
                List<String> a = new ArrayList<>(List.of("-s", serial, "shell"));
                if ("su".equals(root)) a.addAll(List.of("su", "root", "sh", "-c", "'getenforce'"));
                else a.add("getenforce");
                m.put("selinux", run(a, 8)[1].trim());
            } catch (OpException e) {
                m.put("selinux", "?");
            }
        }
        Map<String, Object> fs = new LinkedHashMap<>();
        try {   // ps -A needs no root on Android; the sidecar's server shows as frida-server-jeeves
            String ps = run(new ArrayList<>(List.of("-s", serial, "shell", "ps", "-A", "-o", "NAME")), 8)[1];
            fs.put("running", ps.lines().anyMatch(l -> l.trim().startsWith("frida-server")));
        } catch (OpException e) {
            fs.put("running", false);
        }
        m.put("fridaServer", fs);
        try {
            /* one round trip for the label: a constant `;`-joined shell string — the one
               deliberate exception to run()'s "each arg a single argv element" contract
               (no device-derived text in it). Positional parse: an unset prop is an empty
               line, so no edge trim before the split; a non-zero rc is adb's error text. */
            String[] r = run(new ArrayList<>(List.of("-s", serial, "shell",
                    "getprop ro.product.model; getprop ro.build.version.release; getprop ro.product.cpu.abi")), 8);
            String[] lines = "0".equals(r[0]) ? r[1].split("\n", -1) : new String[0];
            m.put("model", lines.length > 0 ? lines[0].trim() : "");
            m.put("android", lines.length > 1 ? lines[1].trim() : "");
            m.put("abi", lines.length > 2 ? lines[2].trim() : "");   // attach hard-fails off arm64-v8a (bridge.py)
        } catch (OpException e) {
            m.put("model", "");
            m.put("android", "");
            m.put("abi", "");
        }
        return m;
    }

    /** Shell command on device as root where needed (adbd-root emulators run plainly). */
    private String shell(String serial, String cmd) throws OpException {
        if (adbdIsRoot(serial)) return adb(serial, "shell", cmd)[1];
        // toybox su is `su [WHO [COMMAND...]]`, and adb joins argv unquoted — the
        // command must be single-quoted through the hop or only its first word runs
        return adb(serial, "shell", "su", "root", "sh", "-c", "'" + cmd.replace("'", "'\\''") + "'")[1];
    }

    /**
     * A multi-command shell script on the device, as ONE round trip over STDIN —
     * {@code adb shell} with no command argument reads the script from stdin, so
     * nothing secret (the marker nonce) ever appears on a device-side command line
     * where /proc could read it back (review finding).
     */
    private String[] shellScript(String serial, String script) throws OpException {
        List<String> a = new ArrayList<>(List.of("-s", serial, "shell"));
        if (adbdIsRoot(serial)) return run(a, 25, script);
        /* `su root sh` (no -s): sh with no script arg reads the script from stdin, so the marker
           nonce still never touches a device-side command line. NOT `sh -s` — MagiskSU parses -s
           as its --shell option and swallows the next token, running nothing (verified: empty
           output on a Magisk Pixel 9). Plain `sh` reading stdin works on both toybox and Magisk. */
        a.addAll(List.of("su", "root", "sh"));
        return run(a, 25, script);
    }

    /** Reject anything that could become shell syntax on the device. */
    private static String safePath(String path) throws OpException {
        if (path == null || path.isBlank())
            throw OpException.syntax("path required", "");
        if (path.matches(".*[;|&$`<>'\"\\\\\\s].*"))
            throw OpException.syntax("unsafe device path: " + path, "metacharacters rejected");
        return path;
    }

    // ------------------------------------------------------------ device.list

    public Map<String, Object> list() throws OpException { return list(true); }

    /** probe=false: no root probe (cached answer or "?"), for pollers. */
    public Map<String, Object> list(boolean probe) throws OpException {
        String[] r = run(List.of("devices", "-l"), 15);
        List<Object> devices = new ArrayList<>();
        for (String line : r[1].split("\n")) {
            line = line.trim();
            if (line.isEmpty() || line.startsWith("List") || !line.contains("device")) continue;
            String serial = line.split("\\s+")[0];
            if ("offline".equals(line.split("\\s+")[1])) continue;
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("serial", serial);
            d.put("model", getprop(serial, "ro.product.model"));
            d.put("android", getprop(serial, "ro.build.version.release"));
            d.put("abi", getprop(serial, "ro.product.cpu.abi"));
            String rootCap = rootOf(serial, probe);   // adbd-root | su | none | ? — the capability, with its cost said in the UI (audit decision 14)
            d.put("adbdRoot", "adbd-root".equals(rootCap));
            d.put("root", rootCap);
            d.put("rootProbed", !"?".equals(rootCap));
            d.put("fridaServer", fridaStatus(serial));
            devices.add(d);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("devices", devices);
        return out;
    }

    private String getprop(String serial, String prop) {
        try {
            return adb(serial, "shell", "getprop", prop)[1].trim();
        } catch (OpException e) {
            return "";
        }
    }

    /** Frida-server status for a device (ps-based; shared by device.list and runs.devices). */
    public Map<String, Object> fridaStatus(String serial) {
        Map<String, Object> m = new LinkedHashMap<>();
        try {
            String ps = shell(serial, "ps -A -o NAME");
            boolean up = ps.lines().anyMatch(l -> l.trim().startsWith("frida-server"));
            m.put("running", up);
        } catch (OpException e) {
            m.put("running", false);
        }
        return m;
    }

    // ------------------------------------------------------------ catalog

    private File catalogFile(String serial) {
        return new File(devicesDir, serial.replaceAll("[^A-Za-z0-9._-]", "_") + ".catalog.json");
    }

    /** device.catalog [--refresh] [--kind] [--filter] — paged entries + diff on refresh. */
    public synchronized Map<String, Object> catalog(String serial, boolean refresh,
            String kind, String filter, int offset, int count) throws OpException {
        Map<String, Object> cat = refresh || !catalogFile(serial).isFile()
                ? buildCatalog(serial) : readCatalog(serial);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> entries = (List<Map<String, Object>>) cat.get("entries");
        String ql = filter == null ? "" : filter.toLowerCase();
        /* ONE predicate for rows, tombstones and diff counts alike (review F5/F8):
           kind + text filter, path included for both entry classes — hoisted ABOVE
           the row loop so rows run the same code as the recount, not a copy */
        java.util.function.Predicate<Map<String, Object>> keep = e ->
                (kind == null || kind.isBlank() || kind.equals(e.get("kind")))
                && (ql.isEmpty()
                    || String.valueOf(e.getOrDefault("name", "")).toLowerCase().contains(ql)
                    || String.valueOf(e.getOrDefault("path", "")).toLowerCase().contains(ql));
        List<Object> rows = new ArrayList<>();
        int cap = count <= 0 ? 500 : Math.min(count, 5000);
        int matched = 0;
        for (Map<String, Object> e : entries) {
            if (!keep.test(e)) continue;
            if (matched++ < offset) continue;
            if (rows.size() < cap) rows.add(new LinkedHashMap<>(e));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("serial", serial);
        out.put("built", cat.get("built"));
        out.put("total", matched);
        out.put("totalAll", entries.size());
        out.put("offset", offset);
        out.put("count", rows.size());
        out.put("truncated", offset + rows.size() < matched);
        // per-kind DEVICE truth (review F3): the scans cap what they collect, so
        // entries.size() is what we KEPT, never what the device has. kindTotals is
        // counted before the cap; cappedKinds names the kinds the tab chips must
        // render as "N+" so a chip never passes a cap off as an inventory.
        if (cat.get("kindTotals") != null) out.put("kindTotals", cat.get("kindTotals"));
        if (cat.get("cappedKinds") != null) out.put("cappedKinds", cat.get("cappedKinds"));
        if (cat.get("diff") != null){
            if (kind == null && ql.isEmpty()) out.put("diff", cat.get("diff"));
            else {
                /* recount matching the WRITER's vocabulary (added|updated|removed)
                   and the same predicate as the rows/tombstones */
                int a = 0, rm = 0, ch = 0;
                for (Map<String, Object> e : entries){
                    if (!keep.test(e)) continue;
                    if ("added".equals(e.get("changed"))) a++;
                    else if ("updated".equals(e.get("changed"))) ch++;
                }
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> remAll = (List<Map<String, Object>>) cat.get("removed");
                if (remAll != null) for (Map<String, Object> e : remAll) if (keep.test(e)) rm++;
                Map<String, Object> diff = new LinkedHashMap<>();
                diff.put("added", a); diff.put("removed", rm); diff.put("changed", ch);
                out.put("diff", diff);
            }
        }
        // tombstones (review F4): entries the last refresh REMOVED. They are not in
        // `entries` (a gone package must not answer annotate/pull), but the CHANGED
        // tab can only count "−N" honestly if it can also list them.
        if (cat.get("removed") != null){
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> remAll = (List<Map<String, Object>>) cat.get("removed");
            List<Map<String, Object>> rem = new ArrayList<>();
            for (Map<String, Object> e : remAll)
                if (keep.test(e)) rem.add(e);
            out.put("removed", rem);
            /* the writer's diff.removed is the TRUE count, the tombstone list is
               TOMB_CAP-capped: when the cap bit, say the list is a WINDOW — else
               the default path's header and its own list read as two answers, and
               a filtered recount silently undercounts past the cap (review F7) */
            if (cat.get("diff") instanceof Map<?, ?> wd
                    && wd.get("removed") instanceof Number wn
                    && wn.intValue() > remAll.size())
                out.put("removedTruncated", true);
        }
        out.put("entries", rows);
        return out;
    }

    /** Full scan (DEVICE_SPEC §3: packages, libs, processes, partitions). */
    private Map<String, Object> buildCatalog(String serial) throws OpException {
        Map<String, Object> prev = catalogFile(serial).isFile() ? readCatalog(serial) : null;
        List<Map<String, Object>> entries = new ArrayList<>();
        // per-kind truth: counted from the device BEFORE any cap trims what we keep
        Map<String, Object> kindTotals = new LinkedHashMap<>();
        List<Object> cappedKinds = new ArrayList<>();

        // packages (user -3, system -s), with apk paths
        for (String[] spec : new String[][] { { "-3", "app" }, { "-s", "system-app" } }) {
            String out = adb(serial, "shell", "pm", "list", "packages", "-f", spec[0])[1];
            int n = 0, seen = 0;
            for (String line : out.split("\n")) {
                if (!line.startsWith("package:")) continue;
                String body = line.substring(8).trim();
                int eq = body.lastIndexOf('=');
                if (eq < 0) continue;
                seen++;
                if (n >= PKG_CAP) continue;
                Map<String, Object> e = entry(spec[1], body.substring(eq + 1), body.substring(0, eq));
                entries.add(e);
                n++;
            }
            kindTotals.put(spec[1], seen);
            if (seen > n) cappedKinds.add(spec[1]);
        }
        // versions for user apps (dumpsys is slow per-package — best-effort for third-party only)
        for (Map<String, Object> e : entries) {
            if (!"app".equals(e.get("kind"))) continue;
            String v = adb(serial, "shell", "dumpsys", "package", String.valueOf(e.get("name")))[1];
            java.util.regex.Matcher vc = java.util.regex.Pattern.compile("versionName=(\\S+)")
                    .matcher(v);
            if (vc.find()) e.put("version", vc.group(1));
            break;                                                          // one is enough for the bar's flavor
        }

        // libs: /system/lib64 (capped) + /apex module lib64 dirs (shallow, capped).
        // The listing is parsed in full — the cap trims what we KEEP, so libSeen is
        // the device's real .so count for the dirs we looked in.
        int libKept = 0, libSeen = 0;
        for (Map<String, String> row : ls(serial, "/system/lib64")) {
            if (!row.get("name").endsWith(".so")) continue;
            libSeen++;
            if (libKept >= LIB_CAP) continue;
            entries.add(entry("lib", row.get("name"), "/system/lib64/" + row.get("name"),
                    row.get("size")));
            libKept++;
        }
        List<String> apexes = lsNames(serial, "/apex");
        /* /apex exposes each module twice: com.android.art AND com.android.art@<ver>
           are the same mount — the @-suffixed twin would double-count and double-list
           every apex lib (review F6). apex-info-list.xml is a file, not a module. */
        apexes.removeIf(a -> a.contains("@") || a.endsWith(".xml"));
        boolean apexCapped = apexes.size() > APEX_CAP;
        for (String apex : apexes.subList(0, Math.min(APEX_CAP, apexes.size()))) {
            int kept = 0;
            for (Map<String, String> row : ls(serial, "/apex/" + apex + "/lib64")) {
                if (!row.get("name").endsWith(".so")) continue;
                libSeen++;
                if (kept >= APEX_LIB_CAP) continue;
                entries.add(entry("lib", apex + "/" + row.get("name"),
                        "/apex/" + apex + "/lib64/" + row.get("name"), row.get("size")));
                kept++;
                libKept++;
            }
        }
        kindTotals.put("lib", libSeen);
        // an /apex list past APEX_CAP means whole module dirs went unvisited: libSeen
        // itself is a floor there, so the kind is capped even if nothing else trimmed
        if (libSeen > libKept || apexCapped) cappedKinds.add("lib");

        // processes (ps -A): USERSPACE ONLY — ps -A leads with ~250 kernel threads
        // ([bracketed] names) on Android 15+, which would exhaust the cap before
        // zygote/system_server/every app process; and no frida target is a kthread.
        // The pid→name map stays uncapped: it resolves the host process of every
        // binder service below, including ones past PROC_CAP.
        String ps = adb(serial, "shell", "ps", "-A")[1];
        Map<String, String> pidProc = new LinkedHashMap<>();
        int np = 0, procSeen = 0;
        for (String line : ps.split("\n")) {
            String[] parts = line.trim().split("\\s+");
            if (parts.length < 9 || "PID".equals(parts[1])) continue;
            String name = parts[parts.length - 1];
            pidProc.put(parts[1], name);
            if (name.startsWith("[")) continue;              // kernel thread
            procSeen++;
            if (np >= PROC_CAP) continue;
            Map<String, Object> e = entry("process", name, null);
            e.put("pid", parts[1]);
            e.put("uid", parts[0]);
            entries.add(e);
            np++;
        }
        kindTotals.put("process", procSeen);
        if (procSeen > np) cappedKinds.add("process");

        // services: the ServiceManager binder registry, each mapped to its host process
        // and (where the device tells us) the package/apk behind it. Needs the package
        // entries collected above for apk paths, so it runs last of the scans.
        int[] svcSeen = new int[1];
        List<Map<String, Object>> svcRows = buildServices(serial, entries, pidProc, svcSeen);
        entries.addAll(svcRows);
        kindTotals.put("service", svcSeen[0]);
        if (svcSeen[0] > svcRows.size()) cappedKinds.add("service");

        // partitions (root) — by-name entries are symlinks; the name is the partition
        // (super, vbmeta…) and the link target is the device node it resolves to
        int partKept = 0, partSeen = 0;
        for (Map<String, String> row : ls(serial, "/dev/block/by-name")) {
            partSeen++;
            if (partKept >= PART_CAP) continue;
            Map<String, Object> e = entry("partition", row.get("name"),
                    "/dev/block/by-name/" + row.get("name"), row.get("size"));
            if (row.get("link") != null) e.put("link", row.get("link"));
            entries.add(e);
            partKept++;
        }
        kindTotals.put("partition", partSeen);
        if (partSeen > partKept) cappedKinds.add("partition");

        // merge prior annotations/pulls + compute the diff
        Map<String, Map<String, Object>> byKey = new LinkedHashMap<>();
        for (Map<String, Object> e : entries) byKey.put(e.get("kind") + ":" + e.get("name"), e);
        List<Map<String, Object>> tombstones = new ArrayList<>();
        int added = 0, removed = 0, changed = 0;
        if (prev != null) {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> oldEntries = (List<Map<String, Object>>) prev.get("entries");
            Map<String, Map<String, Object>> oldByKey = new LinkedHashMap<>();
            for (Map<String, Object> oe : oldEntries) {
                /* baseline migration: the scanner once listed /apex @-versioned twin
                   DIRS as separate libs — but HAL libs legitimately carry '@' in the
                   FILENAME (android.hardware.atrace@1.0.so). Strip only when the '@'
                   is in the apex dir segment, or annotations/pull provenance die on
                   real libs (review F1 — data loss) */
                String oname = String.valueOf(oe.get("name"));
                if ("lib".equals(oe.get("kind")) && oname.contains("/")
                        && oname.substring(0, oname.indexOf('/')).contains("@")) continue;
                oldByKey.put(oe.get("kind") + ":" + oname, oe);
            }
            for (Map.Entry<String, Map<String, Object>> en : byKey.entrySet()) {
                Map<String, Object> old = oldByKey.get(en.getKey());
                if (old == null) { added++; en.getValue().put("changed", "added"); }
                else {
                    // carry annotations + pull provenance forward
                    if (old.get("annotations") != null) en.getValue().put("annotations", old.get("annotations"));
                    if (old.get("pulled") != null) en.getValue().put("pulled", old.get("pulled"));
                    if (old.get("sha256") != null) en.getValue().put("sha256", old.get("sha256"));
                    if (!java.util.Objects.equals(old.get("size"), en.getValue().get("size"))
                            || !java.util.Objects.equals(old.get("version"), en.getValue().get("version"))) {
                        changed++;
                        en.getValue().put("changed", "updated");
                    }
                }
            }
            // TOMBSTONES (review F4): a removed entry vanishes from `entries`, so
            // "−N" was a number the CHANGED tab could never show a row for. Keep the
            // old rows themselves, flagged, in a SEPARATE list — they must not answer
            // annotate/pull/proc (the thing is gone), only be listable. Scoped to this
            // refresh: last refresh's tombstones are not carried forward.
            for (Map.Entry<String, Map<String, Object>> oe : oldByKey.entrySet()) {
                if (byKey.containsKey(oe.getKey())) continue;
                removed++;
                if (tombstones.size() >= TOMB_CAP) continue;   // diff.removed stays the truth
                Map<String, Object> t = new LinkedHashMap<>(oe.getValue());
                t.put("removed", true);
                t.put("changed", "removed");
                tombstones.add(t);
            }
        }

        Map<String, Object> cat = new LinkedHashMap<>();
        cat.put("serial", serial);
        cat.put("built", Instant.now().toString());
        cat.put("entries", entries);
        cat.put("kindTotals", kindTotals);
        cat.put("cappedKinds", cappedKinds);
        if (!tombstones.isEmpty()) cat.put("removed", tombstones);
        if (prev != null) {
            Map<String, Object> diff = new LinkedHashMap<>();
            diff.put("added", added);
            diff.put("removed", removed);
            diff.put("changed", changed);
            cat.put("diff", diff);
        }
        writeCatalog(serial, cat);
        return cat;
    }

    // ------------------------------------------------------------ services (binder registry)

    /** Framework services live in system_server; its code is the platform, not a package. */
    private static final String FRAMEWORK_JAR = "/system/framework/framework.jar";

    /** One `service list` row: index, then "name: [iface]" (tab-separated, spaces tolerated). */
    private static final java.util.regex.Pattern SVC_LINE =
            java.util.regex.Pattern.compile("\\d+\\s+(.+)");

    /**
     * The ServiceManager registry as catalog entries: every published binder service with
     * its interface descriptor, the process hosting it and the package/apk that process
     * belongs to.
     *
     * <p>Sources: {@code service list} for the registry itself, {@code dumpsys --pid} for
     * the authoritative service→pid mapping (one call for the whole registry), the ps map
     * for pid→process, and {@code dumpsys activity services} for process→package/apk on
     * app-hosted services. Nothing is inferred from names: a service whose host the device
     * won't tell us keeps only what {@code service list} said, and a process hosting more
     * than one package (com.android.networkstack.process hosts three) resolves to no
     * package at all rather than an arbitrary one.
     */
    private List<Map<String, Object>> buildServices(String serial,
            List<Map<String, Object>> pkgEntries, Map<String, String> pidProc, int[] seenOut) {
        List<Map<String, Object>> out = new ArrayList<>();
        // package -> apk path, from the pm list packages -f entries already collected
        Map<String, String> pkgApk = new LinkedHashMap<>();
        for (Map<String, Object> e : pkgEntries)
            if (("app".equals(e.get("kind")) || "system-app".equals(e.get("kind")))
                    && e.get("path") != null)
                pkgApk.put(String.valueOf(e.get("name")), String.valueOf(e.get("path")));

        Map<String, String> svcPid = servicePids(serial);
        Map<String, List<String>> procPkgs = new LinkedHashMap<>();
        Map<String, String> pkgBase = new LinkedHashMap<>();
        appServiceHosts(serial, procPkgs, pkgBase);

        String list;
        try {
            list = adb(serial, "shell", "service", "list")[1];
        } catch (OpException e) {
            return out;                                       // no registry → no service rows
        }
        for (String line : list.split("\n")) {
            // "12\tandroid.frameworks...ICameraService/default: [android.frameworks...]"
            java.util.regex.Matcher m = SVC_LINE.matcher(line.trim());
            if (!m.matches()) continue;                                      // "Found N services:"
            String rest = m.group(1);
            int colon = rest.lastIndexOf(": [");
            String name = colon < 0 ? rest.trim() : rest.substring(0, colon).trim();
            String iface = colon < 0 ? "" : rest.substring(colon + 3).replaceAll("\\]$", "").trim();
            if (name.isEmpty()) continue;
            seenOut[0]++;                       // registry truth, counted before the cap
            if (out.size() >= SVC_CAP) continue;
            Map<String, Object> e = entry("service", name, null);
            if (!iface.isEmpty()) e.put("iface", iface);
            String host = pidProc.get(svcPid.get(name));
            if (host != null) {
                e.put("host", host);
                if ("system_server".equals(host)) {
                    e.put("apkPath", FRAMEWORK_JAR);
                } else {
                    String pkg = resolvePkg(host, pkgApk, procPkgs);
                    if (pkg != null) {
                        e.put("pkg", pkg);
                        String apk = pkgApk.containsKey(pkg) ? pkgApk.get(pkg) : pkgBase.get(pkg);
                        if (apk != null) e.put("apkPath", apk);
                    }
                }
            }
            out.add(e);
        }
        return out;
    }

    /** Host process → package: named after it, its {@code pkg:sub} base, or an unambiguous host. */
    private static String resolvePkg(String host, Map<String, String> pkgApk,
            Map<String, List<String>> procPkgs) {
        if (pkgApk.containsKey(host)) return host;
        int c = host.indexOf(':');
        if (c > 0 && pkgApk.containsKey(host.substring(0, c))) return host.substring(0, c);
        List<String> cands = procPkgs.get(host);
        return cands != null && cands.size() == 1 ? cands.get(0) : null;
    }

    /**
     * {@code dumpsys --pid} → service name → pid of the hosting process, for every
     * registered service in one call (the per-service dump is just the pid).
     */
    private Map<String, String> servicePids(String serial) {
        Map<String, String> out = new LinkedHashMap<>();
        String txt;
        try {
            txt = adb(serial, "shell", "dumpsys", "--pid")[1];
        } catch (OpException e) {
            return out;                                       // no mapping → host stays absent
        }
        String[] lines = txt.split("\n");
        for (int i = 0; i + 1 < lines.length; i++) {
            String l = lines[i].trim();
            if (!l.startsWith("DUMP OF SERVICE ") || !l.endsWith(":")) continue;
            String name = l.substring(16, l.length() - 1).trim()
                    .replaceFirst("^(CRITICAL|HIGH|NORMAL) ", "");   // --priority headers
            String pid = lines[i + 1].trim();
            if (!name.isEmpty() && pid.matches("\\d+")) out.put(name, pid);
        }
        return out;
    }

    /**
     * {@code dumpsys activity services} → process name → packages hosted there, and
     * package → apk (baseDir). Only covers processes with a running app service, which is
     * exactly the set that can publish an app-hosted binder service.
     */
    private void appServiceHosts(String serial, Map<String, List<String>> procPkgs,
            Map<String, String> pkgBase) {
        String txt;
        try {
            txt = adb(serial, "shell", "dumpsys", "activity", "services")[1];
        } catch (OpException e) {
            return;
        }
        String pkg = null, proc = null;
        for (String line : txt.split("\n")) {
            String t = line.trim();
            if (t.startsWith("packageName=")) pkg = t.substring(12).trim();
            else if (t.startsWith("processName=")) proc = t.substring(12).trim();
            else if (t.startsWith("baseDir=") && pkg != null && proc != null) {
                List<String> l = procPkgs.computeIfAbsent(proc, k -> new ArrayList<>());
                if (!l.contains(pkg)) l.add(pkg);
                pkgBase.putIfAbsent(pkg, t.substring(8).trim());
                pkg = proc = null;
            }
        }
    }

    private static Map<String, Object> entry(String kind, String name, String path) {
        return entry(kind, name, path, null);
    }

    private static Map<String, Object> entry(String kind, String name, String path, String size) {
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("kind", kind);
        e.put("name", name);
        if (path != null) e.put("path", path);
        if (size != null) e.put("size", size);
        e.put("annotations", new ArrayList<>());
        return e;
    }

    /**
     * The entry name in an {@code ls -la} row. A symlink row ends
     * "&lt;name&gt; -&gt; &lt;target&gt;", so the LAST token is the target, not the name —
     * taking it verbatim named every /dev/block/by-name partition after the device
     * node it points at and then built {@code /dev/block/by-name/} + that absolute
     * target as its path. Returns null for . and .. (callers skip those rows).
     */
    private static String lsName(String[] parts) {
        int last = parts.length - 1;
        // "-> target" only counts at the end of the row, and only for a symlink row
        if (last >= 2 && parts[0].startsWith("l") && "->".equals(parts[last - 1])) last -= 2;
        String name = parts[last];
        return name.equals(".") || name.equals("..") ? null : name;
    }

    /** Symlink target of an ls -la row, or null when the row is not a symlink. */
    private static String lsLink(String[] parts) {
        int last = parts.length - 1;
        return last >= 2 && parts[0].startsWith("l") && "->".equals(parts[last - 1]) ? parts[last] : null;
    }

    /** ls -la parsed into {name, size, link?} rows (skips . and ..). Parsed in FULL:
     *  the adb round trip already carried the whole listing, so truncating here only
     *  hid how much the dir holds. Callers cap what they keep and report the total. */
    private List<Map<String, String>> ls(String serial, String dir) {
        List<Map<String, String>> out = new ArrayList<>();
        String out2;
        try {
            out2 = shell(serial, "ls -la " + dir);
        } catch (OpException e) {
            return out;
        }
        for (String line : out2.split("\n")) {
            String[] parts = line.trim().split("\\s+");
            if (parts.length < 8) continue;
            String name = lsName(parts);
            if (name == null) continue;
            Map<String, String> row = new LinkedHashMap<>();
            row.put("name", name);
            row.put("size", parts.length >= 5 ? parts[4] : "");
            String link = lsLink(parts);
            if (link != null) row.put("link", link);
            out.add(row);
        }
        return out;
    }

    private List<String> lsNames(String serial, String dir) {
        List<String> out = new ArrayList<>();
        for (Map<String, String> row : ls(serial, dir)) out.add(row.get("name"));
        return out;
    }

    /* catalogIoLock guards the catalog FILE protocol — the degraded flag, the
       evidence park, the write — never the adb scan (round-19 F2: a Workspace→
       DeviceManager monitor bridge froze the server behind a 6s catalog scan;
       method-level `synchronized` here reintroduced it, fix-1-r6 MED-3). Both
       readCatalog and writeCatalog take it (fix-1-r7 F2: a lock-free read could
       flag a serial degraded AFTER a writer's contains() check, and the writer
       then overwrote the un-parked original; a lock-free park could rename a
       freshly-written healthy catalog). Lock ordering is DeviceManager →
       catalogIoLock everywhere; the lock is only ever held across file I/O.
       RESIDUAL (documented, fix-1-r7 F2c): applyAnnotation/pull's
       read→mutate→write spans ops outside this lock — a first-use buildCatalog
       racing them can still drop an annotation; closing that needs the RMW under
       one lock, a larger change. */
    private final Object catalogIoLock = new Object();

    @SuppressWarnings("unchecked")
    private Map<String, Object> readCatalog(String serial) {
        File f = catalogFile(serial);
        if (!f.isFile()) return Map.of("entries", new ArrayList<>());
        synchronized (catalogIoLock) {
        try {
            Object p = Json.parse(Files.readString(f.toPath()));
            if (p instanceof Map) return (Map<String, Object>) p;
            /* right file, wrong shape — park the evidence (was: catch-all silent,
               and the next refresh overwrote the catalog with no trace). A FAILED
               park leaves it in place — mark degraded (fix-1-r5 #2). */
            if (!dev.workbench.registry.SidecarIO.parkCorrupt(f, "catalog",
                    new IllegalStateException("expected a JSON object")))
                degradedCatalogs.add(serial);
        } catch (java.io.IOException e) {
            /* transient read hiccup ≠ corruption: routed by permanence — a
               charset-parked file is already aside (a FAILED charset park
               reports un-parked and takes the degraded path too, fix-1-r5 #2);
               a transient one marks the serial degraded (see writeCatalog) */
            if (!dev.workbench.registry.SidecarIO.readFailedOrPark(f, "catalog", e))
                degradedCatalogs.add(serial);
        } catch (Exception e) {
            if (!dev.workbench.registry.SidecarIO.parkCorrupt(f, "catalog", e))
                degradedCatalogs.add(serial);
        }
        return Map.of("entries", new ArrayList<>());
        }
    }

    private void writeCatalog(String serial, Map<String, Object> cat) {
        /* atomic + loud via the shared sidecar discipline (was: plain
           writeString, catch-all silent); the degraded guard+park+write runs
           under catalogIoLock ONLY (never the device monitor — see above) */
        File f = catalogFile(serial);
        synchronized (catalogIoLock) {
            if (degradedCatalogs.contains(serial)) {
                /* never overwrite un-parked evidence: a failed park keeps the
                   flag and SKIPS the write (fix-1-r4 #5) */
                if (!dev.workbench.registry.SidecarIO.parkHealthyBeforeOverwrite(f, "catalog"))
                    return;
                degradedCatalogs.remove(serial);
            }
            dev.workbench.registry.SidecarIO.writeAtomic(f, Json.write(cat), "catalog");
        }
    }

    // ------------------------------------------------------------ browse

    /** device.browse <serial> <path> — root-shell ls -la (sanitized). */
    public List<Object> browse(String serial, String path) throws OpException {
        safePath(path);
        String out = shell(serial, "ls -la " + path);
        List<Object> rows = new ArrayList<>();
        for (String line : out.split("\n")) {
            String[] parts = line.trim().split("\\s+");
            if (parts.length < 8) continue;
            String name = lsName(parts);
            if (name == null) continue;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("name", name);
            row.put("size", parts.length >= 5 ? parts[4] : "");
            row.put("perms", parts[0]);
            row.put("dir", parts[0].startsWith("d"));
            String link = lsLink(parts);
            if (link != null) row.put("link", link);
            rows.add(row);
        }
        return rows;
    }

    // ------------------------------------------------------------ device.proc

    /** How many per-thread rows a proc answer carries; `threads` always reports the total. */
    private static final int THREAD_CAP = 64;

    /** A pid is the only value interpolated into the /proc script — digits, nothing else. */
    private static final java.util.regex.Pattern PID_NUM = java.util.regex.Pattern.compile("\\d{1,7}");

    /** The /proc/&lt;pid&gt;/status memory keys the UI charts, in display order (all kB). */
    private static final List<String> MEM_KEYS =
            List.of("VmPeak", "VmSize", "VmRSS", "RssAnon", "RssFile", "VmSwap");

    /** Fallback clock tick when the device's getconf is missing — Android is 100 everywhere. */
    private static final int DEFAULT_HZ = 100;

    /**
     * device.proc &lt;serial&gt; &lt;pid&gt; — the live detail behind one process row:
     * status/stat/cmdline/fd/oom/exe/cwd plus the per-thread table, in ONE adb round
     * trip. Everything is read from /proc in a single device-side script whose sections
     * are delimited by {@code @@} markers; N separate {@code adb shell} calls would cost
     * ~80ms each and would sample the process at N different instants.
     *
     * <p>Pids race: the catalog names a process that may exit before the row is clicked,
     * so a missing /proc entry is E_NOTFOUND (the UI says "process exited"), never a
     * shell error. The same check catches a process that dies mid-read: the script tests
     * readability first, and a status block without a Name: line is treated as gone.
     */
    public Map<String, Object> proc(String serial, String pid) throws OpException {
        if (pid == null || !PID_NUM.matcher(pid).matches())
            throw OpException.syntax("bad pid: " + pid, "expected a decimal pid");
        String p = "/proc/" + pid;
        /* Section markers carry a per-call random nonce: a hostile process could
           otherwise forge them through its own cmdline (argv newlines survive the
           tr) and hide itself or fake its /proc fields — the anti-analysis case
           (review finding). Payload newlines are stripped ONLY where the framing
           survives it (cmdline gets a trailing echo) — a `tr -d '\n'` elsewhere
           would glue the next marker onto the payload's last line. */
        String nonce = java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        String M = "@@" + nonce + "_";
        String script = "if [ ! -r " + p + "/status ]; then echo " + M + "GONE; else"
                + " echo " + M + "STATUS; cat " + p + "/status 2>/dev/null;"
                + " echo " + M + "STAT; base64 < " + p + "/stat 2>/dev/null | tr -d '\\n'; echo;"
                + " echo " + M + "CMDLINE; base64 < " + p + "/cmdline 2>/dev/null;"
                + " echo " + M + "FD; ls " + p + "/fd 2>/dev/null | wc -l;"
                + " echo " + M + "OOM; cat " + p + "/oom_score 2>/dev/null;"
                + " cat " + p + "/oom_score_adj 2>/dev/null;"
                + " echo " + M + "EXE; readlink " + p + "/exe 2>/dev/null | base64;"
                + " echo " + M + "CWD; readlink " + p + "/cwd 2>/dev/null | base64;"
                + " echo " + M + "UPTIME; cat /proc/uptime 2>/dev/null;"
                + " echo " + M + "HZ; getconf CLK_TCK 2>/dev/null;"
                + " echo " + M + "TASKS; for tf in " + p + "/task/*/stat; do base64 < \"$tf\" 2>/dev/null | tr -d '\\n'; echo; done | head -" + THREAD_CAP + ";"
                + " fi";
        String[] rr = shellScript(serial, script);
        Map<String, List<String>> sec = sections(rr[1], M);
        if (sec.containsKey("GONE") || !sec.containsKey("STATUS")) {
            /* no markers at all ≠ gone — transport/permission failures (su, offline
               device, adb error text) land here; the exit code used to be discarded */
            if (sec.isEmpty())
                throw OpException.io("cannot read " + p + " on " + serial,
                        "no section markers in device output (su/adb transport failure, exit " + rr[0] + ")");
            throw OpException.notFound("no process " + pid + " on " + serial, "process exited");
        }

        Map<String, String> st = new LinkedHashMap<>();
        for (String line : sec.getOrDefault("STATUS", List.of())) {
            int c = line.indexOf(':');
            if (c > 0) st.put(line.substring(0, c).trim(), line.substring(c + 1).trim());
        }
        if (!st.containsKey("Name")) {
            /* a truncated read that kept STATUS but lost Name: is a transport
               failure, not an exit — fold the exit code in (review finding) */
            if (!"0".equals(rr[0]))
                throw OpException.io("cannot read " + p + " on " + serial,
                        "truncated device output (exit " + rr[0] + ")");
            throw OpException.notFound("no process " + pid + " on " + serial, "process exited");
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("serial", serial);
        out.put("pid", Long.parseLong(pid));
        out.put("name", st.get("Name"));
        String state = st.getOrDefault("State", "");
        out.put("state", state.isEmpty() ? "" : state.substring(0, 1));
        out.put("stateText", state);
        out.put("ppid", num(st.get("PPid")));
        out.put("uid", firstField(st.get("Uid")));
        out.put("gid", firstField(st.get("Gid")));
        out.put("threads", num(st.get("Threads")));
        out.put("ctxtVoluntary", num(st.get("voluntary_ctxt_switches")));
        out.put("ctxtNonvoluntary", num(st.get("nonvoluntary_ctxt_switches")));

        Map<String, Object> mem = new LinkedHashMap<>();
        for (String k : MEM_KEYS) if (st.containsKey(k)) mem.put(k, num(st.get(k)));   // absent (kernel threads) ≠ 0
        out.put("mem", mem);                                       // kB, as /proc reports them

        String cmdline = b64(sec, "CMDLINE");
        if (cmdline != null) cmdline = cmdline.replace('\0', ' ').replaceAll("\\s+", " ").trim();
        out.put("cmdline", cmdline);
        out.put("fds", num(first(sec.get("FD"))));
        List<String> oom = sec.getOrDefault("OOM", List.of());
        out.put("oomScore", num(oom.size() > 0 ? oom.get(0) : null));
        out.put("oomScoreAdj", num(oom.size() > 1 ? oom.get(1) : null));
        out.put("exe", b64(sec, "EXE"));
        out.put("cwd", b64(sec, "CWD"));

        int hz = (int) num(first(sec.get("HZ")));
        if (hz <= 0) hz = DEFAULT_HZ;
        out.put("clockTick", hz);
        double devUptime = dbl(firstField(first(sec.get("UPTIME"))));
        out.put("deviceUptime", round1(devUptime));

        // /proc/<pid>/stat — fields are 1-based in proc(5); comm (2) may itself contain
        // spaces, parens and raw NEWLINES (the kernel escapes status' Name: but not
        // stat's comm), so every stat record rides base64, one per line — a crafted
        // comm can never split a record or forge a phantom one (review rounds 2/3).
        String[] f = statFields(b64(sec, "STAT"));
        if (f.length >= 22) {
            out.put("utime", round2(num(f[13]) / (double) hz));
            out.put("stime", round2(num(f[14]) / (double) hz));
            out.put("cpuSeconds", round2((num(f[13]) + num(f[14])) / (double) hz));
            out.put("numThreads", num(f[19]));                     // cross-check vs status Threads
            if (f.length >= 39) out.put("processor", num(f[38]));   // last CPU it ran on
            double start = num(f[21]) / (double) hz;
            out.put("startTime", round1(start));
            out.put("uptime", round1(Math.max(0, devUptime - start)));
        }

        List<Object> threads = new ArrayList<>();
        for (String line : sec.getOrDefault("TASKS", List.of())) {
            String[] tf = statFields(b64one(line));   /* one base64 record per line */
            if (tf.length < 3) continue;
            Map<String, Object> t = new LinkedHashMap<>();
            t.put("tid", num(tf[0]));
            t.put("comm", tf[1]);
            t.put("state", tf[2]);
            threads.add(t);
        }
        // The glob that produced the task list sorts lexically ("1000" before "670"), and
        // the head cap can drop the main thread entirely — put it first from the process's
        // own stat, which IS the main thread's, so the table always opens on it.
        long mainTid = Long.parseLong(pid);
        threads.removeIf(t -> mainTid == ((Number) ((Map<?, ?>) t).get("tid")).longValue());
        if (f.length >= 3) {
            Map<String, Object> t = new LinkedHashMap<>();
            t.put("tid", mainTid);
            t.put("comm", f[1]);
            t.put("state", f[2]);
            t.put("main", true);
            threads.add(0, t);
        }
        while (threads.size() > THREAD_CAP) threads.remove(threads.size() - 1);
        out.put("threadList", threads);
        out.put("threadsShown", threads.size());
        long total = num(st.get("Threads"));
        out.put("threadsTruncated", total > threads.size());
        return out;
    }

    /** Split nonce-prefixed marker sections into name → its lines (markers dropped). */
    private static Map<String, List<String>> sections(String out, String prefix) {
        Map<String, List<String>> sec = new LinkedHashMap<>();
        List<String> cur = null;
        for (String raw : out.split("\n")) {
            String line = raw.replace("\r", "");
            if (line.startsWith(prefix)) {
                cur = sec.computeIfAbsent(line.substring(prefix.length()).trim(), k -> new ArrayList<>());
                continue;
            }
            if (cur != null && !line.isEmpty()) cur.add(line);
        }
        return sec;
    }

    /** Base64-decode a section's joined lines (payload fields ride base64 so framing survives any byte).
     *  STRICT decoder: error text leaking into a section must fail closed (null), not decode
     *  leniently into garbage bytes that statFields might parse (round-3 review note). */
    private static String b64(Map<String, List<String>> sec, String key) {
        List<String> lines = sec.get(key);
        if (lines == null || lines.isEmpty()) return null;
        try {
            byte[] raw = java.util.Base64.getDecoder().decode(String.join("", lines));
            return new String(raw, java.nio.charset.StandardCharsets.UTF_8).trim();
        } catch (Exception e) { return null; }
    }

    /** Decode one single-line base64 record (the per-task stat lines). */
    private static String b64one(String line) {
        try {
            return new String(java.util.Base64.getDecoder().decode(line.trim()),
                    java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) { return null; }
    }

    /**
     * A /proc stat line as fields, 0-based (so proc(5)'s field N is index N-1). comm
     * keeps its spaces and loses its parens; everything after the last ')' splits on
     * whitespace. Returns an empty array for a line that is not a stat line.
     */
    private static String[] statFields(String line) {
        if (line == null) return new String[0];
        int open = line.indexOf('('), close = line.lastIndexOf(')');
        if (open < 0 || close < open) return new String[0];
        String[] tail = line.substring(close + 1).trim().split("\\s+");
        String[] f = new String[2 + tail.length];
        f[0] = line.substring(0, open).trim();
        f[1] = line.substring(open + 1, close);
        System.arraycopy(tail, 0, f, 2, tail.length);
        return f;
    }

    private static String first(List<String> lines) {
        return lines == null || lines.isEmpty() ? "" : lines.get(0);
    }

    /** First whitespace/tab-separated field ("1000\t1000\t1000\t1000" → "1000"). */
    private static String firstField(String s) {
        if (s == null || s.isBlank()) return "";
        return s.trim().split("\\s+")[0];
    }

    private static final java.util.regex.Pattern NUM_RE = java.util.regex.Pattern.compile("-?\\d+");

    /** Leading integer of a /proc value ("  298484 kB" → 298484); 0 when there is none. */
    private static long num(String s) {
        if (s == null) return 0;
        java.util.regex.Matcher m = NUM_RE.matcher(s);
        return m.find() ? Long.parseLong(m.group()) : 0;
    }

    private static double dbl(String s) {
        try { return Double.parseDouble(s.trim()); } catch (Exception e) { return 0; }
    }

    private static double round1(double v) { return Math.round(v * 10) / 10.0; }

    private static double round2(double v) { return Math.round(v * 100) / 100.0; }

    // ------------------------------------------------------------ components

    /**
     * Package-name grammar for device.components. Stricter than
     * {@link #safePath}: {@code adb shell} re-joins its argv into one command line
     * that a shell on the device interprets, so the only safe policy for a value
     * we forward is an allow-list.
     */
    private static final java.util.regex.Pattern PKG_NAME =
            java.util.regex.Pattern.compile("[A-Za-z0-9][A-Za-z0-9._]{0,254}");

    /** Declared-component list headers inside a Package block → component kind. */
    private static final Map<String, String> DECL_SECTIONS = Map.of(
            "activities:", "activity", "services:", "service",
            "receivers:", "receiver", "providers:", "provider");

    /** Intent-resolver table headers → component kind. */
    private static final Map<String, String> RESOLVER_SECTIONS = Map.of(
            "Activity Resolver Table:", "activity", "Service Resolver Table:", "service",
            "Receiver Resolver Table:", "receiver", "Provider Resolver Table:", "provider");

    /** Emission order — the reversing-relevant kinds first, UI groups follow it. */
    private static final List<String> KIND_ORDER = List.of("service", "receiver", "provider", "activity");

    /** A resolver-table component row: "&lt;hex&gt; &lt;pkg&gt;/&lt;class&gt; filter &lt;hex&gt; [permission &lt;perm&gt;]". */
    private static final java.util.regex.Pattern RESOLVER_ROW = java.util.regex.Pattern.compile(
            "^[0-9a-f]+ (\\S+)/(\\S+) filter \\S+(?: permission (\\S+))?\\s*$");

    /** "Provider{&lt;hex&gt; &lt;pkg&gt;/&lt;class&gt;}" under a ContentProvider authority. */
    private static final java.util.regex.Pattern PROVIDER_ROW =
            java.util.regex.Pattern.compile("^Provider\\{\\S+ (\\S+)/([^}]+)\\}\\s*$");

    private static final int COMP_CAP = 2000;

    /**
     * device.components &lt;serial&gt; &lt;pkg&gt; — the components one package DECLARES,
     * read off {@code dumpsys package --all-components &lt;pkg&gt;}.
     *
     * <p>Two sources in one dump, and only one of them is complete: the
     * {@code activities:/services:/receivers:/providers:} lists in the package's
     * own block name every declared component (that is what --all-components adds);
     * the intent-resolver tables above them name only the filtered subset, but are
     * the only place the dump prints a component's actions and its
     * {@code android:permission}. So the lists decide membership and the tables
     * decorate. Nothing here is inferred: dumpsys never prints per-component
     * process names on any Android the workbench targets, so no component carries
     * a `process` field rather than a guessed one.
     */
    public Map<String, Object> components(String serial, String pkg) throws OpException {
        if (pkg == null || !PKG_NAME.matcher(pkg).matches())
            throw OpException.syntax("bad package name: " + pkg,
                    "expected dotted alphanumerics, e.g. com.android.phone");
        String dump = adb(serial, "shell", "dumpsys", "package", "--all-components", pkg)[1];
        return parseComponents(serial, pkg, dump);
    }

    /** The dumpsys parse, split out so it can be exercised against captured dumps. */
    @SuppressWarnings("unchecked")
    static Map<String, Object> parseComponents(String serial, String pkg, String dump) {
        Map<String, Map<String, Object>> comps = new LinkedHashMap<>();   // kind|fqcn -> component
        java.util.Set<String> declaredKeys = new java.util.LinkedHashSet<>();
        Map<String, Object> out = new LinkedHashMap<>();
        String versionName = null, versionCode = null, codePath = null, apkPath = null, apexModule = null;
        boolean found = false, sawDeclList = false, truncated = false;

        String section = "";          // current top-level section (indent 0)
        String declKind = null;       // current declared-list kind inside the package block
        String resolverKind = null;   // current resolver table's kind
        String authority = null;      // current ContentProvider authority
        boolean inPkg = false;        // inside "Packages: > Package [<pkg>]"
        boolean inDexoptPkg = false;
        Map<String, Object> lastFiltered = null;   // resolver row the Action: lines belong to

        for (String raw : dump.split("\n")) {
            String line = raw.replace("\r", "");
            if (line.isBlank()) continue;
            int indent = 0;
            while (indent < line.length() && line.charAt(indent) == ' ') indent++;
            String t = line.substring(indent);

            if (indent == 0) {                                   // top-level section header
                section = t;
                resolverKind = RESOLVER_SECTIONS.get(t);
                inPkg = inDexoptPkg = false;
                declKind = null;
                authority = null;
                lastFiltered = null;
                continue;
            }

            if (resolverKind != null) {
                java.util.regex.Matcher m = RESOLVER_ROW.matcher(t);
                if (m.matches()) {
                    lastFiltered = m.group(1).equals(pkg)
                            ? component(comps, resolverKind, pkg, m.group(2)) : null;
                    if (lastFiltered != null && m.group(3) != null)
                        lastFiltered.putIfAbsent("permission", m.group(3));
                } else if (lastFiltered != null && t.startsWith("Action: \"") && t.endsWith("\"")) {
                    addUnique(lastFiltered, "actions", t.substring(9, t.length() - 1));
                }
                continue;
            }

            if ("Packages:".equals(section)) {
                if (indent == 2 && t.startsWith("Package [")) {
                    inPkg = t.startsWith("Package [" + pkg + "]");
                    found |= inPkg;
                    declKind = null;
                    continue;
                }
                if (!inPkg) continue;
                if (indent <= 4) {
                    declKind = DECL_SECTIONS.get(t);              // null for every other 4-indent key
                    if (declKind != null) { sawDeclList = true; continue; }
                    if (t.startsWith("versionName=")) versionName = t.substring(12).trim();
                    else if (t.startsWith("versionCode=")) versionCode = t.substring(12).split("\\s+")[0];
                    else if (t.startsWith("codePath=")) codePath = t.substring(9).trim();
                    else if (t.startsWith("apexModuleName=") && !t.endsWith("=null"))
                        apexModule = t.substring(15).trim();
                    continue;
                }
                if (declKind == null) continue;
                int slash = t.indexOf('/');                       // "<pkg>/<class>"
                if (slash <= 0) continue;
                String owner = t.substring(0, slash);
                String cls = t.substring(slash + 1).split("\\s+")[0];
                if (cls.isEmpty() || !owner.equals(pkg)) continue;
                if (component(comps, declKind, pkg, cls) == null) truncated = true;
                else declaredKeys.add(declKind + "|" + fqcn(pkg, cls));
                continue;
            }

            if ("ContentProvider Authorities:".equals(section)) {
                if (indent == 2 && t.startsWith("[") && t.endsWith("]:"))
                    authority = t.substring(1, t.length() - 2);
                else if (authority != null) {
                    java.util.regex.Matcher m = PROVIDER_ROW.matcher(t);
                    if (m.matches() && m.group(1).equals(pkg))
                        addUnique(component(comps, "provider", pkg, m.group(2)), "authorities", authority);
                }
                continue;
            }

            if ("Dexopt state:".equals(section)) {               // the only place the real apk path prints
                if (indent == 2 && t.startsWith("[")) inDexoptPkg = t.equals("[" + pkg + "]");
                else if (inDexoptPkg && apkPath == null && t.startsWith("path: "))
                    apkPath = t.substring(6).trim();
            }
        }

        // A component the resolver tables name but the declared lists don't is format
        // drift, not a silent addition — it ships flagged rather than indistinguishable.
        // (Skipped when there were no declared lists at all: `partial` already says so.)
        if (sawDeclList)
            for (Map.Entry<String, Map<String, Object>> e : comps.entrySet())
                if (!declaredKeys.contains(e.getKey())) e.getValue().put("fromFilterOnly", true);

        // group by kind in KIND_ORDER, so the payload arrives pre-grouped for the rail
        List<Object> ordered = new ArrayList<>();
        Map<String, Object> counts = new LinkedHashMap<>();
        for (String kind : KIND_ORDER) {
            int n = 0;
            for (Map.Entry<String, Map<String, Object>> e : comps.entrySet())
                if (e.getKey().startsWith(kind + "|")) { ordered.add(e.getValue()); n++; }
            if (n > 0) counts.put(kind, n);
        }

        out.put("serial", serial);
        out.put("pkg", pkg);
        out.put("found", found);
        if (versionName != null) out.put("versionName", versionName);
        if (versionCode != null) out.put("versionCode", versionCode);
        if (codePath != null) out.put("codePath", codePath);
        if (apkPath != null) out.put("apkPath", apkPath);
        if (apexModule != null) out.put("apexModule", apexModule);
        out.put("counts", counts);
        out.put("total", ordered.size());
        out.put("truncated", truncated);
        out.put("partial", !found || (!sawDeclList && !ordered.isEmpty()));
        if (!found)
            out.put("note", "no package block for '" + pkg + "' in dumpsys package output"
                    + " — not installed for this user, or a different package name");
        else if (!sawDeclList)
            out.put("note", "dumpsys printed no declared-component lists (--all-components"
                    + " unsupported?) — listing only components that carry an intent filter");
        else if (truncated)
            out.put("note", "component list capped at " + COMP_CAP + " entries");
        out.put("components", ordered);
        return out;
    }

    /**
     * Get-or-create one component row, keyed kind+FQCN — the resolver tables repeat
     * a component once per intent-filter bucket it matches. Returns null once
     * COMP_CAP distinct components exist and the key is new (the caller flags it).
     */
    private static Map<String, Object> component(Map<String, Map<String, Object>> comps,
            String kind, String pkg, String cls) {
        String key = kind + "|" + fqcn(pkg, cls);
        Map<String, Object> c = comps.get(key);
        if (c != null) return c;
        if (comps.size() >= COMP_CAP) return null;
        c = new LinkedHashMap<>();
        c.put("kind", kind);
        c.put("class", fqcn(pkg, cls));
        comps.put(key, c);
        return c;
    }

    /** Manifest class names are relative to the package: ".Foo" and bare "Foo" expand. */
    private static String fqcn(String pkg, String cls) {
        if (cls.startsWith(".")) return pkg + cls;
        return cls.contains(".") ? cls : pkg + "." + cls;
    }

    /** Append to a component's string list, no duplicates. Null-tolerant (cap hit). */
    @SuppressWarnings("unchecked")
    private static void addUnique(Map<String, Object> c, String key, String value) {
        if (c == null) return;
        List<Object> l = (List<Object>) c.computeIfAbsent(key, k -> new ArrayList<>());
        if (!l.contains(value)) l.add(value);
    }

    /** device.services &lt;serial&gt; — the binder service registry (name, iface, host, pkg, apkPath). */
    public List<Object> services(String serial) throws OpException {
        List<Object> out = new ArrayList<>();
        for (Map<String, Object> e : catalogEntries(serial))
            if ("service".equals(e.get("kind"))) out.add(new LinkedHashMap<>(e));
        return out;
    }

    /** Catalog entries for the catalog mission (builds the catalog on first use). */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> catalogForMission(String serial) throws OpException {
        return catalogEntries(serial);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> catalogEntries(String serial) throws OpException {
        Map<String, Object> cat = catalogFile(serial).isFile()
                ? readCatalog(serial) : buildCatalog(serial);
        return (List<Map<String, Object>>) cat.get("entries");
    }

    // ------------------------------------------------------------ annotations

    private Map<String, Object> findEntry(List<Map<String, Object>> entries, String kind,
            String name) throws OpException {
        for (Map<String, Object> e : entries)
            if ((kind == null || kind.equals(e.get("kind"))) && name.equals(e.get("name"))) return e;
        throw OpException.notFound("catalog entry not found: " + (kind == null ? "" : kind + ":") + name, "");
    }

    /** device.annotate — human annotation (applied immediately). */
    public synchronized Map<String, Object> annotate(String serial, String kind, String name,
            String note, String by) throws OpException {
        return applyAnnotation(serial, kind, name, note, by == null ? "human" : by);
    }

    /** Apply an annotation to a catalog entry (also the agent.review accept path). */
    @SuppressWarnings("unchecked")
    public synchronized Map<String, Object> applyAnnotation(String serial, String kind,
            String name, String note, String by) throws OpException {
        Map<String, Object> cat = readCatalog(serial);
        List<Map<String, Object>> entries = (List<Map<String, Object>>) cat.get("entries");
        Map<String, Object> e = findEntry(entries, kind, name);
        List<Object> anns = (List<Object>) e.computeIfAbsent("annotations", k -> new ArrayList<>());
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("by", by);
        a.put("note", note);
        a.put("ts", Instant.now().toString());
        anns.add(a);
        writeCatalog(serial, cat);
        return new LinkedHashMap<>(e);
    }

    // ------------------------------------------------------------ pull

    /** The local pull destination for a device path — pure computation, no side
        effects. Lets device.pull's callers check the destination BEFORE paying
        an adb pull (e.g. "a live target is backed by this file"). */
    public File pulledFileFor(String path) {
        return new File(pulledDir, new File(path).getName());
    }

    /** On-device sha256 of a path — and its existence PROOF: a bogus path throws
        instead of ever short-circuiting into a fake success (review R1). */
    public String deviceSha256(String serial, String path) throws OpException {
        safePath(path);
        String chk = shell(serial, "test -f " + path + " && echo OK").trim();
        if (!"OK".equals(chk))
            throw OpException.notFound("no such file on " + serial + ": " + path, "");
        return shell(serial, "sha256sum " + path).trim().split("\\s+")[0];
    }

    /** Local sha256 — the destination side of the short-circuit comparison. */
    public static String localSha256(File f) {
        try (java.io.InputStream in = new java.io.BufferedInputStream(new java.io.FileInputStream(f))) {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[1 << 20];
            for (int n; (n = in.read(buf)) > 0; ) md.update(buf, 0, n);
            StringBuilder sb = new StringBuilder();
            for (byte b : md.digest()) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return null;
        }
    }

    /* pulledDir case-sensitivity delegates to Workspace.ciVolume — a COMPLETED
       probe caches permanently per directory (the dir itself or, for a missing
       one, its nearest existing ancestor — round-26 F4); a probe FAILURE
       answers conservatively without caching (round-28 F2). */
    private boolean pulledDirCI() {
        return dev.workbench.server.Workspace.ciVolume(pulledDir);
    }

    private boolean nameStartsWith(String name, String prefix) {
        return pulledDirCI()
                ? name.toLowerCase(java.util.Locale.ROOT).startsWith(
                        prefix.toLowerCase(java.util.Locale.ROOT))
                : name.startsWith(prefix);
    }

    private boolean nameEndsWith(String name, String suffix) {
        /* same gate as the prefix (round-23 F6: half-folded filters) */
        return pulledDirCI()
                ? name.toLowerCase(java.util.Locale.ROOT).endsWith(
                        suffix.toLowerCase(java.util.Locale.ROOT))
                : name.endsWith(suffix);
    }

    /** The namespace guard, shared by Ops (hoisted, so a refused pull costs no
        device round-trip — round-19 F8) and pull() itself (defense in depth).
        Anchored to the ACTUAL namespace (round-19 F6) and matched CASE-
        INSENSITIVELY (round-20 F1: the destination resolves to the same inode
        on case-insensitive APFS, so 'zfam.PROV.JSON' sailed through). The
        .jdb2 slot is the workbench's whether or not it is OCCUPIED (round-20
        F2: empty right after any invalidating pull — exactly when the plant
        lands); a device .jdb2 belongs to target.open from any OTHER path.
        .restore-me is the rollback's only-copy namespace (round-20 F3). */
    public OpException namespaceCollision(String base) {
        String lower = base.toLowerCase(java.util.Locale.ROOT);
        if (lower.matches(".*\\.(stale|part)-\\d+$") || lower.matches(".*\\.prov\\.json(\\.tmp)?")
                || lower.endsWith(".restore-me"))
            return OpException.syntax("destination name collides with the workbench"
                    + " provenance/staging namespace: " + base, "");
        /* backstop for any caller that bypasses the Ops gate (round-21 N2: the
           clause moved to Ops and left this shared guard claiming coverage it no
           longer had): the .jdb2 slot is the workbench's, occupied or not */
        if (lower.endsWith(".jdb2"))
            return OpException.syntax("pulled/" + base + " is the workbench's"
                    + " database slot — `adb pull` the device's .jdb2 by hand to a path"
                    + " outside pulled/ and open it with target.open", "jdb2=" + new File(pulledDir, base).getAbsolutePath());
        return null;
    }

    /** device.pull <serial> <path> — adb pull into backend/.workbench/pulled/,
     *  sha256 recorded (change detection on re-pull). Returns {file, sha256, warn?}. */
    public synchronized Map<String, Object> pull(String serial, String path) throws OpException {
        return pull(serial, path, null);
    }

    /** knownSha: a caller that already hashed the source (the Ops short-circuit)
     *  skips the third full read (review F9) — and the response carries the same
     *  sample the gate compared. */
    public synchronized Map<String, Object> pull(String serial, String path, String knownSha) throws OpException {
        safePath(path);
        String base = new File(path).getName();
        OpException collision = namespaceCollision(base);
        if (collision != null) throw collision;
        pulledDir.mkdirs();
        File dst = new File(pulledDir, base);
        String sha = knownSha != null ? knownSha : shell(serial, "sha256sum " + path).trim().split("\\s+")[0];
        // change detection: a previously pulled file whose on-device sha changed → warn
        String priorSha = null;
        Map<String, Object> cat = readCatalog(serial);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> entries = (List<Map<String, Object>>) cat.get("entries");
        for (Map<String, Object> e : entries)
            if (path.equals(e.get("path")) && e.get("sha256") != null)
                priorSha = String.valueOf(e.get("sha256"));
        String p = dst.getAbsolutePath();
        int dot = p.lastIndexOf('.');
        int slash = Math.max(p.lastIndexOf('/'), p.lastIndexOf(File.separatorChar));
        String jbase = dot > slash ? p.substring(0, dot) : p;
        String jName = new File(jbase).getName();
        /* read the family prov — the ONLY evidence for what the family's
           database was built from. When there is NO record, the honest answer
           is "don't know" → INVALIDATE (round-17 F2: the dst-bytes proxy was
           the exact evidence F1 rejected, it was today's default for every
           pre-scheme family, and a wrong keep laundered a foreign database
           into an authoritative record and silenced basisWarn forever). The
           cost is one re-analysis per family on its first pull under this
           scheme; after that, everything is recorded. */
        Map<String, Object> prov = readProv(jbase);
        /* scratch files from a hard-killed earlier pull — reaped only when OLD
           (round-17 F6: the blind reaper ate the rescue copy a failed commit
           deliberately leaves behind) and only when NUMERIC-suffixed (round-18
           F6: a user's .part-old is just as sacred as their .keep-my-annotations).
           This method is the only writer in one JVM (the pulled dir is
           single-process by convention; a second backend on the same directory
           is not a supported configuration). */
        String dstName = new File(p).getName();
        String[] orphans = pulledDir.list((dir, n) -> {
            if (!n.startsWith(dstName + ".part-")) return false;
            String suffix = n.substring(dstName.length() + 6);
            return suffix.matches("\\d+");
        });
        if (orphans != null) for (String n : orphans) {
            File o = new File(pulledDir, n);
            if (System.currentTimeMillis() - o.lastModified() > 7L * 24 * 3600 * 1000) deleteTree(o);
        }
        /* migration: the round-15 per-destination record is superseded by the
           family one — deleted only when it is a DIFFERENT file (an
           extensionless destination has jbase == p, and deleting then wipes
           the live family prov before every transfer — round-17 F1) */
        if (!jbase.equals(p)) new File(p + ".prov.json").delete();

        /* pull to a scratch file FIRST (round-15 F2/F5): nothing is moved or
           decided until the bytes are down and hashed — a failed pull leaves the
           world exactly as it was, and the invalidation compares LANDED bytes,
           never the predicted source sha (the destination hash is read under
           this monitor — a threaded-through snapshot went stale between two
           interleaved pulls, round-15 F6) */
        File part = new File(p + ".part-" + System.currentTimeMillis());
        List<String> warns = new ArrayList<>();
        List<String> createdCorpses = new ArrayList<>();
        List<String[]> movedAside = new ArrayList<>();   // [original, aside] pairs for rollback
        List<String> sidecarsMoved = new ArrayList<>();
        String jdb2Aside = null;
        /* unrestored only-copies from an earlier failed rollback deserve a
           reminder on every pull of this family — nothing else ever mentions
           them again (round-20 F8) */
        String[] restoreMe = pulledDir.list((dir, n) -> nameStartsWith(n, jName + ".")
                && nameEndsWith(n, ".restore-me"));
        if (restoreMe != null && restoreMe.length > 0)
            warns.add(restoreMe.length + " unrestored analysis copy(ies) from a failed rollback"
                    + " sitting in pulled/: " + String.join(", ", restoreMe));
        try {
            String[] r = run(List.of("-s", serial, "pull", path, part.getAbsolutePath()), 300);
            if (!"0".equals(r[0]))
                throw OpException.busy("adb pull failed: " + r[1].trim(), path);
            String landed = localSha256(part);
            /* a staging read that yields nothing is a failed transfer, loudly —
               never fabricate a provenance record from the predicted sha
               (round-16 F6). A mismatch with the device sha means the file moved
               mid-transfer (an app update): nothing was decided yet and the
               staged copy is cleaned up by the finally — fail, don't commit a
               Franken-file (round-17 F5: warn-and-proceed invalidated a live
               target's database under it) */
            if (landed == null)
                throw OpException.busy("could not hash the staged bytes for " + path
                        + " — the transfer did not land cleanly", path);
            if (!landed.equals(sha))
                throw OpException.busy("the device file changed mid-transfer for " + path
                        + " — it is being rewritten on the device (an app update, a live log)."
                        + " Nothing was committed. Pull it once it stabilizes.", path);
            /* the invalidation: provSha decides; no record → don't know →
               invalidate (see the prov comment). A record missing the field is
               no record (the mirror in Ops keys the same way, round-17 F3) */
            String provSha = prov == null || prov.get("sha256") == null ? null
                    : String.valueOf(prov.get("sha256"));
            boolean dstChanged = provSha == null || !landed.equals(provSha);
            if (dstChanged) {
                File sibling = new File(jbase + ".jdb2");
                if (sibling.isFile()) {
                    File aside = new File(jbase + ".jdb2.stale-" + System.currentTimeMillis());
                    if (sibling.renameTo(aside)) {
                        createdCorpses.add(aside.getName());
                        movedAside.add(new String[] { sibling.getAbsolutePath(), aside.getAbsolutePath() });
                        jdb2Aside = aside.getName();
                    } else
                        throw OpException.busy("stale " + sibling.getName() + " could not be moved aside"
                                + " (rename failed) — refusing to open the target onto the OLD app;"
                                + " check permissions/locks and retry", sibling.getName());
                }
                /* every sidecar in the jbase FAMILY (round-15 F8: the destination's
                   own spelling moved but a sibling extension's tags stayed behind,
                   ready to bleed back) */
                String[] wbDirs = pulledDir.list((dir, n) -> nameStartsWith(n, jName + ".")
                        && nameEndsWith(n, ".workbench")
                        && new File(dir, n).isDirectory());
                if (wbDirs != null) for (String n : wbDirs) {
                    File sidecar = new File(pulledDir, n);
                    File scAside = new File(pulledDir, n + ".stale-" + System.currentTimeMillis());
                    if (sidecar.renameTo(scAside)) {
                        createdCorpses.add(scAside.getName());
                        movedAside.add(new String[] { sidecar.getAbsolutePath(), scAside.getAbsolutePath() });
                        sidecarsMoved.add(scAside.getName());
                    } else warns.add("sidecar " + n + " could not be moved aside"
                            + " — annotations may bleed into the new app");
                }
            }
            /* commit the bytes atomically (round-16 F3: dst.delete() before the
               rename meant a rename failure lost BOTH copies) */
            try {
                java.nio.file.Files.move(part.toPath(), dst.toPath(),
                        java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            } catch (Exception mx) {
                String partName = part.getName();
                part = null;   // never delete the staged copy once dst may be gone
                /* the bytes are atomic, but the invalidation already COMMITTED —
                   roll it back, CHECKING each restore (round-18 F1: an unchecked
                   renameTo plus "nothing changed" was total silent data loss,
                   and clearing the corpse names fed the only surviving copy to
                   the reaper) */
                List<String> unrestored = new ArrayList<>();
                for (String[] pair : movedAside)
                    if (!new File(pair[1]).renameTo(new File(pair[0])))
                        unrestored.add(pair[1]);
                if (unrestored.isEmpty()) {
                    createdCorpses.clear();
                    sidecarsMoved.clear();
                    jdb2Aside = null;
                    /* and don't claim a rollback that never happened (round-18 F5:
                       dstChanged false → movedAside empty → there was nothing
                       TO roll back). The staged copy is NOT "nothing" either —
                       name it (round-19 F7) */
                    throw OpException.busy("could not move " + partName + " into place: " + mx
                            + (movedAside.isEmpty() ? "" : " — the invalidation was rolled back")
                            + " — the staged new bytes are preserved at " + partName
                            + " (reaped after 7 days)", p);
                }
                /* partial rollback: rename the stranded asides OUT of the numeric
                   reaper namespace — a '.stale-<ts>.restore-me' never matches
                   \d+$, so no later pull can silently delete the only copy
                   (round-19 F1). When the rename itself fails the name stays in
                   the namespace — and the sentence must say so (round-20 F6) */
                List<String> protected_ = new ArrayList<>();
                boolean allSafe = true;
                for (String strandedPath : unrestored) {
                    File stranded = new File(strandedPath);
                    File safe = new File(strandedPath + ".restore-me");
                    if (stranded.renameTo(safe)) protected_.add(safe.getName());
                    else { protected_.add(stranded.getName()); allSafe = false; }
                }
                throw OpException.busy("could not move " + partName + " into place: " + mx
                        + " — and the rollback FAILED for " + unrestored.size()
                        + " file(s), now preserved as " + protected_
                        + " — those are the ONLY copies of the original analysis;"
                        + " restore them by hand"
                        + (allSafe ? " (they are kept out of the reaper)"
                            : " — rename-failed files are STILL in the reaper's namespace;"
                            + " restore them before newer corpses accumulate")
                        + ". The staged new bytes are preserved at " + partName
                        + " (reaped after 7 days)", p);
            }
            part = null;
            writeProv(jbase, serial + ":" + path, landed, warns);
            // record provenance in the catalog entry
            for (Map<String, Object> e : entries)
                if (path.equals(e.get("path"))) {
                    e.put("sha256", landed);
                    e.put("pulled", dst.getAbsolutePath());
                    e.put("pulledFrom", serial + ":" + path);
                }
            writeCatalog(serial, cat);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("file", dst.getAbsolutePath());
            out.put("sha256", landed);
            out.put("bytes", dst.length());
            if (priorSha != null && !priorSha.equals(landed)) warns.add("on-device version differs from previous pull");
            if (dstChanged) {
                if (jdb2Aside != null) out.put("jdb2Invalidated", jdb2Aside);
                if (!sidecarsMoved.isEmpty()) out.put("sidecarInvalidated", sidecarsMoved);
                /* reap stale corpses per family, newest 3 of EACH, numeric-suffixed
                   only (a user's .keep-my-annotations is sacred, round-15 F9) */
                reapFamilies(jName, createdCorpses);
            }
            if (!warns.isEmpty()) out.put("warn", String.join(" · ", warns));   // accumulate, never overwrite
            return out;
        } finally {
            if (part != null && part.isFile()) part.delete();
        }
    }

    /* the FAMILY provenance record: <jbase>.prov.json — {pulledFrom, sha256, ts}.
       Keyed on the jbase family because the jdb2 it vouches for is (X.apk and
       X.odex share X.jdb2, round-15 F1). Written AFTER the bytes land, read
       BEFORE the next pull decides whether the sibling jdb2/sidecars match the
       incoming bytes; deleted ONLY when a write fails, so a broken record
       degenerates to "no record" (conservative) instead of a stale record that
       false-matches later (round-15 F3, corrected round-18 F7). */
    private File provFileFor(String jbase) {
        /* one entry per family on this volume — APFS's case-preserving rename
           keeps the FIRST writer's case; an on-disk-spelling lookup here was
           dead code (round-24 F7: the variant spelling is always isFile()-true
           on a case-insensitive fs, so the lookup never fired) */
        /* canonicalize the KEY (r3 #6): the pull writes from a plain absolute
           path while the Workspace's auto-namer reads with a canonicalized one
           — a symlinked component in between (/tmp → /private/tmp) silently
           split the record from the lookup */
        try {
            return new File(new File(jbase).getCanonicalPath() + ".prov.json");
        } catch (java.io.IOException e) {
            return new File(jbase + ".prov.json");   // unresolvable: keep the given spelling
        }
    }

    /** The full guarded family prov record (null on any read problem) — for
        callers that need more than the sha (basisWarn's pulledFrom). NOT
        synchronized: writeProv publishes via tmp+ATOMIC_MOVE so reads are
        never torn, and a Workspace→DeviceManager monitor bridge froze the
        whole server behind a 6s catalog scan (round-19 F2). */
    public Map<String, Object> provFor(String jbase) {
        return readProv(jbase);
    }

    /** The recorded family prov sha — the SAME evidence the invalidation uses
        (the Ops refusal shares the predicate). NOT synchronized, same reason:
        the remaining TOCTOU window (gate reads prov, then calls pull) resolves
        conservatively in pull()'s own re-read under its monitor. */
    public String provShaFor(String jbase) {
        Map<String, Object> prov = readProv(jbase);
        return prov == null || prov.get("sha256") == null ? null : String.valueOf(prov.get("sha256"));
    }

    private Map<String, Object> readProv(String jbase) {
        File f = provFileFor(jbase);
        if (!f.isFile()) return null;
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> m = (Map<String, Object>) dev.workbench.registry.Json.parse(
                    java.nio.file.Files.readString(f.toPath()));
            return m;
        } catch (java.io.IOException e) {
            /* loud, still null (fix-1-r6 nit): a null prov only suppresses
               basisWarn — conservative — but a silent one hid WHY */
            dev.workbench.registry.SidecarIO.readFailedOrPark(f, "prov", e);
            return null;
        } catch (Exception e) {
            dev.workbench.registry.SidecarIO.parkCorrupt(f, "prov", e);
            return null;
        }
    }

    private void writeProv(String jbase, String pulledFrom, String sha256, List<String> warns) {
        /* tmp beside the CANONICALIZED destination (r5 F2): provFileFor now
           canonicalizes, so a tmp built from the raw jbase could sit in a
           different directory than the ATOMIC_MOVE target — the move throws
           and the prov silently never lands */
        File tmp = new File(provFileFor(jbase).getPath() + ".tmp");
        try {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("pulledFrom", pulledFrom);
            m.put("sha256", sha256);
            m.put("ts", java.time.Instant.now().toString());
            /* tmp + atomic move: a concurrent provShaFor must never parse a torn
               file (round-15 F11) */
            java.nio.file.Files.writeString(tmp.toPath(), dev.workbench.registry.Json.write(m));
            java.nio.file.Files.move(tmp.toPath(), provFileFor(jbase).toPath(),
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception e) {
            /* a failed write must leave NO record — a kept-but-stale record
               false-matches later (round-15 F3), a missing one is merely
               conservative. And never leave the tmp behind (round-16 F9) */
            provFileFor(jbase).delete();
            tmp.delete();
            warns.add("provenance record not written (" + e.getClass().getSimpleName()
                    + ") — every later pull of this family invalidates conservatively"
                    + " until a record lands");
        }
    }

    /** decision 16: copy the family prov record from one jbase to another so it survives the
     *  reaping of the staging copy — the store's jbase becomes the record auto-naming, basisWarn
     *  and dedupe read after `pulled/` is gone. Best-effort and non-clobbering: a store prov that
     *  is already present wins (a re-pull re-adopts the same bytes), and a missing source (a
     *  non-pulled target.open) is a no-op. */
    public void copyProv(String fromJbase, String toJbase) {
        File src = provFileFor(fromJbase);
        if (!src.isFile()) return;
        File dst = provFileFor(toJbase);
        if (dst.getAbsolutePath().equals(src.getAbsolutePath()) || dst.isFile()) return;
        try {
            File tmp = new File(dst.getPath() + ".tmp");
            java.nio.file.Files.copy(src.toPath(), tmp.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            java.nio.file.Files.move(tmp.toPath(), dst.toPath(),
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception e) {
            /* best-effort: a missing store prov degrades to "no record" (conservative), never a
               half-written one — leave no tmp */
            new File(dst.getPath() + ".tmp").delete();
        }
    }

    /** decision 16 dedupe index: the catalog's recorded pull identity for a device path —
     *  {sha256, storeKey, pulled(=store artifact path once adopted)}. The sha256→storeKey map the
     *  re-pull short-circuit reads instead of the staging file (which Step 3 reaps). null if the
     *  path was never pulled or the catalog is absent. */
    public synchronized Map<String, Object> catalogPullInfo(String serial, String devicePath) {
        if (!catalogFile(serial).isFile()) return null;
        Map<String, Object> cat = readCatalog(serial);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> entries = (List<Map<String, Object>>) cat.get("entries");
        if (entries == null) return null;
        for (Map<String, Object> e : entries)
            if (devicePath.equals(e.get("path")) && e.get("sha256") != null) {
                Map<String, Object> r = new LinkedHashMap<>();
                r.put("sha256", e.get("sha256"));
                r.put("storeKey", e.get("storeKey"));
                r.put("pulled", e.get("pulled"));
                return r;
            }
        return null;
    }

    /** decision 16 step 3: reap the staging copy (bytes + its prov) once the store has adopted the
     *  artifact — the store is the home now. Best-effort; only the openable artifact staged at
     *  pulledFileFor(path) is removed (a non-openable pull keeps its bytes in pulled/). */
    public void discardPulled(String path) {
        File dst = pulledFileFor(path);
        String jb = dst.getAbsolutePath();
        int dot = jb.lastIndexOf('.');
        int slash = Math.max(jb.lastIndexOf('/'), jb.lastIndexOf(File.separatorChar));
        String jbase = dot > slash ? jb.substring(0, dot) : jb;
        dst.delete();
        provFileFor(jbase).delete();
    }

    /** decision 16: after openTarget adopts a pull into the target store, repoint the catalog
     *  entry's pulled location to the STORE artifact and record its durable store key — so re-pull
     *  dedupe keys off the store (not the staging copy that Step 3 reaps) and the Device view shows
     *  where the bytes actually live. Disk-backed catalog, so read-modify-write. */
    public synchronized void recordPulledStore(String serial, String devicePath, String storeKey, String storePath) {
        if (!catalogFile(serial).isFile()) return;
        Map<String, Object> cat = readCatalog(serial);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> entries = (List<Map<String, Object>>) cat.get("entries");
        if (entries == null) return;
        boolean changed = false;
        for (Map<String, Object> e : entries)
            if (devicePath.equals(e.get("path"))) {
                if (storePath != null) { e.put("pulled", storePath); changed = true; }
                if (storeKey != null) { e.put("storeKey", storeKey); changed = true; }
            }
        if (changed) writeCatalog(serial, cat);
    }

    /* delete all but the newest 3 corpses of EACH stale family in the jbase
       group, never touching the corpses this call just created. Only numeric
       suffixes count as corpses: a user's .keep-my-annotations is sacred and a
       non-numeric suffix breaks the lexicographic 'newest' order (round-15 F9) */
    private void reapFamilies(String jName, List<String> exclude) {
        String[] stale = pulledDir.list((dir, n) -> nameStartsWith(n, jName + ".")
                && n.contains(".stale-"));
        if (stale == null) return;
        Map<String, List<String>> byFamily = new java.util.HashMap<>();
        for (String n : stale) {
            int i = n.indexOf(".stale-");
            if (i < 0 || !n.substring(i + 7).matches("\\d+")) continue;
            /* grouping uses the same normalization as the scan (round-22 F9:
               the filter was case-insensitive but the cap keyed on the exact
               spelling — two spellings of one family got 3 corpses each) */
            String key = pulledDirCI() ? n.substring(0, i).toLowerCase(java.util.Locale.ROOT)
                    : n.substring(0, i);
            byFamily.computeIfAbsent(key, k -> new ArrayList<>()).add(n);
        }
        for (List<String> fam : byFamily.values()) {
            fam.removeAll(exclude);
            if (fam.size() <= 3) continue;
            java.util.Collections.sort(fam);
            for (int i = 0; i < fam.size() - 3; i++) deleteTree(new File(pulledDir, fam.get(i)));
        }
    }

    private static void deleteTree(File f) {
        /* corpses are user-visible files users are told to move/restore by hand:
           never follow a symlink into its target's contents (round-14 F9) */
        try { if (java.nio.file.Files.isSymbolicLink(f.toPath())) { f.delete(); return; } }
        catch (Exception ignored) {}
        if (f.isDirectory()) {
            File[] kids = f.listFiles();
            if (kids != null) for (File k : kids) deleteTree(k);
        }
        f.delete();
    }
}
