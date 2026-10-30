package dev.workbench.server;

import dev.workbench.engine.Engine;
import dev.workbench.registry.OpException;
import dev.workbench.runs.FridaBridge;
import dev.workbench.runs.NativeSymbols;
import dev.workbench.runs.RunStore;
import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Runs orchestration (VIEWS_SPEC §2, MCP_SPEC §4.8): devices, attach/spawn,
 * breakpoints (target-bound, origSig install handles), hits, runs, diff, coverage.
 * Owns the {@link RunStore} (per-project, backend/.workbench/runs/) and the
 * {@link FridaBridge} (lazy sidecar). Runs are project-scoped; breakpoints bind
 * to a target's symbols.
 */
public final class RunsManager implements FridaBridge.HitListener {

    private final Workspace ws;
    private final EventBus bus;
    private final FridaBridge bridge;
    /** Per-PROJECT stores (WORKBENCH_SPEC §8: runs are project-scoped), keyed on the project
        NAME (Workspace.projectDir's claim walk) and created lazily from projects/<slug>/runs.
        Cached for the life of the process, never swapped: a store's degraded-file flags must
        survive project switches, and a live session keeps a reference to its own store. */
    private final Map<String, RunStore> stores = new LinkedHashMap<>();
    /** A live frida session: what it was attached for and the store that owns its run — hits
        arriving after a project switch or close still land in the right index. */
    record Session(String tid, String execId, String device, String pkg, String project, RunStore store) {}
    private final Map<String, Session> sessionCtx = new ConcurrentHashMap<>();

    /** <root>/bps.seq — ONE breakpoint id sequence across projects (r2 H2): per-store ids
        collided ("bp1" in every project) while bp.add routes by target and bp.rm/toggle by
        the id alone. Initialised lazily from the seq file and every project's bps.json. */
    private final File root;
    private long bpSeq = -1;

    public RunsManager(Workspace ws, EventBus bus, File legacyRunsDir) {
        this.ws = ws;
        this.bus = bus;
        this.root = legacyRunsDir.getParentFile();
        this.bridge = new FridaBridge(bus);
        this.bridge.setListener(this);
        /* legacyRunsDir (<root>/runs) is not read here any more: Workspace folds it into the
           first project activated after the upgrade (foldLegacyRunsAndProposals, batch 2) */
    }

    /** The ACTIVE project's store — every runs.* read that names no target. */
    public RunStore store() throws OpException {
        String name = ws.activeProjectName();
        if (name == null)
            throw OpException.notFound("no project open — runs are project-scoped", "open a project or a target first");
        return storeFor(name);
    }

    /** The store of a project by NAME (created on first use). The project dir is resolved
        BEFORE the cache lock: Workspace → stores is the only lock order (Workspace calls
        evict under its own monitor). */
    public RunStore storeFor(String projectName) {
        File dir = new File(ws.projectDir(projectName), "runs");
        synchronized (stores) {
            RunStore s = stores.get(projectName);
            if (s == null) {
                s = new RunStore(dir);
                stores.put(projectName, s);
            }
            return s;
        }
    }

    /** The store of the OPEN project holding tid (E_NOTFOUND for a target no project holds). */
    private RunStore storeForTarget(String tid) throws OpException {
        String name = ws.projectNameOfTarget(tid);
        if (name == null) throw OpException.notFound("target " + tid + " belongs to no open project", "");
        return storeFor(name);
    }

    private synchronized String nextBpId() {
        if (bpSeq < 0) {
            long max = 0;
            File seqFile = new File(root, "bps.seq");
            try { if (seqFile.isFile()) max = Long.parseLong(java.nio.file.Files.readString(seqFile.toPath()).trim()); }
            catch (Exception ignored) {}
            File[] dirs = new File(root, "projects").listFiles(File::isDirectory);
            if (dirs != null) for (File d : dirs) max = Math.max(max, maxBpIdIn(new File(new File(d, "runs"), "bps.json")));
            max = Math.max(max, maxBpIdIn(new File(new File(root, "runs"), "bps.json")));   // a legacy file not yet folded
            bpSeq = max;
        }
        bpSeq++;
        dev.workbench.registry.SidecarIO.writeAtomic(new File(root, "bps.seq"), Long.toString(bpSeq), "bps.seq");
        return "bp" + bpSeq;
    }

    private static long maxBpIdIn(File f) {
        if (!f.isFile()) return 0;
        long max = 0;
        try {
            Object p = dev.workbench.registry.Json.parse(java.nio.file.Files.readString(f.toPath()));
            if (p instanceof List<?> l)
                for (Object o : l)
                    if (o instanceof Map<?, ?> m && String.valueOf(m.get("id")).startsWith("bp"))
                        try { max = Math.max(max, Long.parseLong(String.valueOf(m.get("id")).substring(2))); }
                        catch (NumberFormatException ignored) {}
        } catch (Exception ignored) {}
        return max;
    }

    /** The store holding a breakpoint id: the active project's first, then every cached store —
        ids are unique across projects (nextBpId), so the search is unambiguous; a legacy
        pre-batch-2 id that exists in two stores answers from the active one. */
    private RunStore storeOfBp(String id) throws OpException {
        RunStore active = store();
        if (active.bp(id) != null) return active;
        for (String name : ws.openProjectNames()) {        // every OPEN project, cached or not (r3 M4)
            RunStore s = storeFor(name);
            if (s.bp(id) != null) return s;
        }
        return active;   // the store reports the unknown id itself
    }

    /** [target] on a read: that target's project store, else the active project's. */
    public RunStore storeForParam(String targetParam) throws OpException {
        if (targetParam == null || targetParam.isBlank()) return store();
        return storeForTarget(ws.resolveTargetIdOrActive(targetParam));
    }

    /** A run id in ONE store (execIds are per-store sequences: ex3 exists in every project —
        review b2-r1 M1); blank = that store's latest. */
    public String runIdIn(RunStore store, String run) throws OpException {
        if (run != null && !run.isBlank()) {
            if (!run.matches("ex\\d+"))
                throw OpException.syntax("bad run id: " + run, "expected ex<N> (see runs.runs)");
            boolean known = false;
            for (Object o : store.runs())
                if (o instanceof Map<?, ?> m && run.equals(m.get("execId"))) { known = true; break; }
            if (!known) throw OpException.notFound("no run " + run + " in this project", "runs.runs lists them");
            return run;
        }
        List<Object> all = store.runs();
        if (all.isEmpty()) throw OpException.notFound("no runs yet", "attach a device first");
        @SuppressWarnings("unchecked")
        Map<String, Object> latest = (Map<String, Object>) all.get(0);
        return String.valueOf(latest.get("execId"));
    }

    /** runs.rm [target] <run|all>: delete one recorded run, or every ENDED run of the store; a run
     *  with a live session is E_BUSY (detach first). Human-only (checkScope). */
    public Map<String, Object> removeRuns(String targetParam, String run) throws OpException {
        RunStore s = storeForParam(targetParam);
        boolean scoped = targetParam != null && !targetParam.isBlank();
        String tid = scoped ? ws.resolveTargetIdOrActive(targetParam) : null;
        String key = scoped ? ws.targetStoreKey(tid) : null;

        /* M2: a target means "THIS target's runs", to match runs.runs/runs.hits — not "the whole
           project store". "all" iterates the target's subset; a single id must belong to it. */
        List<String> ids = new ArrayList<>();
        if ("all".equals(run)) {
            List<Object> rows = scoped ? s.runsFor(tid, key) : s.runs();
            for (Object o : rows)
                if (o instanceof Map<?, ?> m && m.get("execId") != null) ids.add(String.valueOf(m.get("execId")));
        } else {
            String id = runIdIn(s, run);
            if (scoped && !runBelongsTo(s, id, tid, key))
                throw OpException.notFound("run " + id + " is not a run of this target",
                        "runs.runs {target} lists this target's runs");
            ids.add(id);
        }

        /* the authoritative live guard lives INSIDE RunStore.removeRun now (endTs==null under the
           store lock, b2-r5 M4) — no racy sessionCtx snapshot, no store-instance compare. L2: one
           lock, one persist for the whole batch. */
        List<Map<String, Object>> results = s.removeRuns(ids);
        List<Object> removed = new ArrayList<>();
        List<String> removedIds = new ArrayList<>();
        int skippedLive = 0;
        for (Map<String, Object> r : results) {
            if (r == null) continue;
            if (Boolean.TRUE.equals(r.get("skippedLive"))) {
                if (!"all".equals(run))
                    throw OpException.busy("run " + r.get("execId") + " has a live session — detach it first",
                            "run=" + r.get("execId"));
                skippedLive++;
                continue;
            }
            removed.add(r);
            removedIds.add(String.valueOf(r.get("execId")));
        }

        /* M5: tell every client so stale card / coverage / diff / dock references are cleared — the
           UI that issued the delete, and any other watcher, both re-point off ids that are gone. */
        if (!removedIds.isEmpty()) {
            Map<String, Object> ev = new LinkedHashMap<>();
            ev.put("type", "removed");
            String project = projectOfStore(s);
            if (project != null) ev.put("project", project);
            ev.put("execIds", removedIds);
            bus.publish("runs", ev);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("removed", removed);
        out.put("count", removed.size());
        if (skippedLive > 0) out.put("skippedLive", skippedLive);
        return out;
    }

    /** does run <id> belong to target <tid> (durable key <key>)? matches the live id or the stored
     *  targetKey, the same either-or runsFor uses (b2-r5 M2). */
    private boolean runBelongsTo(RunStore s, String id, String tid, String key) {
        for (Object o : s.runs())
            if (o instanceof Map<?, ?> m && id.equals(String.valueOf(m.get("execId")))) {
                Object rt = m.get("target"), rk = m.get("targetKey");
                return (tid != null && tid.equals(rt)) || (key != null && key.equals(rk));
            }
        return false;
    }

    /** the project name a store is filed under (for the runs event); null if it is the legacy store. */
    private String projectOfStore(RunStore s) {
        synchronized (stores) {
            for (Map.Entry<String, RunStore> e : stores.entrySet())
                if (e.getValue() == s) return e.getKey();
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    public Map<String,Object> importBundle(String targetParam, String source) throws OpException {
        if (targetParam == null || targetParam.isBlank()) throw OpException.syntax("Import requires an explicit target pin", "target=<id>");
        String tid=ws.resolveTargetIdOrActive(targetParam);
        RunStore store=storeForTarget(tid);
        String project=ws.projectNameOfTarget(tid);
        java.nio.file.Path staging=null;
        try {
            store.directory().mkdirs();
            staging=java.nio.file.Files.createTempDirectory(store.directory().toPath(),".bundle-import-");
            java.nio.file.Path frozen=staging.resolve("bundle");
            Map<String,Object> receipt=dev.workbench.runs.RunBundles.copy(java.nio.file.Path.of(source),frozen);
            Map<String,Object> doc=dev.workbench.registry.Json.parseObject(java.nio.file.Files.readString(frozen.resolve("manifest.json")));
            boolean fixture="fixture".equals(((Map<?,?>)doc.get("producer")).get("mode"));
            String expected=String.valueOf(((Map<?,?>)((Map<?,?>)doc.get("target")).get("artifact")).get("sha256"));
            synchronized (ws) {
                if (!java.util.Objects.equals(project,ws.projectNameOfTarget(tid))) throw new IllegalStateException("Target/project changed during import");
                String actual=ws.executableSha(tid);
                if (fixture ? ws.sidecarDirFor(tid)!=null : !expected.equals(actual))
                    throw new IllegalStateException("Bundle and analysis target identities differ; fixture imports require a mock target");
                Map<String,Object> imported=store.importCanonical(frozen.toFile(),tid,ws.targetStoreKey(tid),ws.targetNameOf(tid),
                        String.valueOf(receipt.get("manifest_sha256")),fixture);
                // An idempotent import does not move the new frozen duplicate into
                // the store. Remove only this call's private validated staging tree.
                if (java.nio.file.Files.exists(frozen)) try (var paths=java.nio.file.Files.walk(frozen)) {
                    for (java.nio.file.Path path:paths.sorted(java.util.Comparator.reverseOrder()).toList())
                        java.nio.file.Files.delete(path);
                }
                return imported;
            }
        } catch (Exception e) {
            throw OpException.unsupported("Run import failed: " + e.getMessage(), "Retained staging is private; no hook executed");
        } finally {
            if (staging != null) try { java.nio.file.Files.deleteIfExists(staging); } catch (Exception ignored) {}
        }
    }

    public Map<String,Object> exportBundle(String targetParam, String run, String destination) throws OpException {
        RunStore store=storeForParam(targetParam);
        String id=runIdForRead(store,targetParam,run);
        try {
            java.nio.file.Path out=java.nio.file.Path.of(destination);
            if (!out.isAbsolute() || out.getParent()==null) throw new IllegalArgumentException("Export destination must be an absolute new directory");
            return dev.workbench.runs.RunBundles.copy(store.canonicalBundle(id).toPath(),out);
        } catch (Exception e) { throw OpException.unsupported("Canonical export failed: " + e.getMessage(), "No upload or publication was performed"); }
    }

    public Map<String, Object> hits(String targetParam, String run, String method, int offset, int limit) throws OpException {
        RunStore s = storeForParam(targetParam);
        return s.hits(runIdForRead(s, targetParam, run), method, offset, limit);
    }

    /** resolve the run id for a TARGET-scoped read (runs.hits/runs.coverage): a blank run picks
     *  the target's own latest run, never the whole store's latest — which may belong to another
     *  target in the project (b2-r5 M6: the code gutter painted another target's coverage). A given
     *  run must belong to the target. With no target scoped, the store-wide rule stands. */
    private String runIdForRead(RunStore s, String targetParam, String run) throws OpException {
        if (targetParam == null || targetParam.isBlank()) return runIdIn(s, run);
        String tid = ws.resolveTargetIdOrActive(targetParam);
        String key = ws.targetStoreKey(tid);
        if (run != null && !run.isBlank()) {
            if (!run.matches("ex\\d+"))
                throw OpException.syntax("bad run id: " + run, "expected ex<N> (see runs.runs)");
            if (!runBelongsTo(s, run, tid, key))
                throw OpException.notFound("no run " + run + " for this target", "runs.runs {target} lists them");
            return run;
        }
        List<Object> rows = s.runsFor(tid, key);
        if (rows.isEmpty()) throw OpException.notFound("no runs for this target yet", "attach a device first");
        @SuppressWarnings("unchecked")
        Map<String, Object> latest = (Map<String, Object>) rows.get(0);
        return String.valueOf(latest.get("execId"));
    }

    public Map<String, Object> diff(String targetParam, String a, String b) throws OpException {
        RunStore s = storeForParam(targetParam);
        return s.diff(runIdIn(s, a), runIdIn(s, b));
    }

    /** Drop a project's cached store (after the legacy fold, or a close); sessions keep theirs. */
    public void evict(String projectName) {
        synchronized (stores) { stores.remove(projectName); }
    }

    /** project.close: the project's sessions end with it; its store leaves the cache. Called
        OFF the Workspace monitor (frida round trips). */
    public void projectClosed(String projectName, List<String> tids) {
        /* close by the session's OWN project, not only the tids still in it (b2-r5 M4): a session
           whose target left the project keeps the old evicted RunStore object — if it is left
           open, its next persistRuns rewrites index.json from a stale copy and resurrects deleted
           rows. The tids list stays as a fallback for a session whose project field is stale. */
        for (Map.Entry<String, Session> e : new ArrayList<>(sessionCtx.entrySet()))
            if (projectName.equals(e.getValue().project()) || tids.contains(e.getValue().tid()))
                closeSession(e.getKey());
        evict(projectName);
    }

    /** runs.runs [target]: the active project's runs, or one target's subset (live id or
        durable key — the RUNTIME dock's filter, WORKBENCH_SPEC §8). */
    public List<Object> runsFor(String targetParam) throws OpException {
        if (targetParam == null || targetParam.isBlank()) return store().runs();
        String tid = ws.resolveTargetIdOrActive(targetParam);
        return storeForTarget(tid).runsFor(tid, ws.targetStoreKey(tid));
    }

    /** runs.bp.list [target]: all of the active project's breakpoints, or one target's. */
    public List<Object> bpsFor(String targetParam) throws OpException {
        if (targetParam == null || targetParam.isBlank()) {
            RunStore s = store();
            for (String tid : ws.openTargetIds()) s.rebindTarget(tid, ws.targetStoreKey(tid));   // dead ids from an earlier boot re-bind here too (r4 L1)
            return s.bps(null);
        }
        String tid = ws.resolveTargetIdOrActive(targetParam);
        RunStore s = storeForTarget(tid);
        s.rebindTarget(tid, ws.targetStoreKey(tid));       // listing re-binds too (r3 H1)
        return s.bpsFor(tid, ws.targetStoreKey(tid));
    }

    /** system.doctor: the sidecar's own view — {pong, frida, serverBin} — or {error} when it
     *  cannot start (no venv, wrong frida). Never throws. */
    public Map<String, Object> ping() {
        try {
            return bridge.ping();
        } catch (RuntimeException e) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("error", e.getMessage());
            return m;
        }
    }

    /** runs.coverage [--run] [--class]: coverage rows for a run, class-label filtered. */
    public Map<String, Object> coverage(String targetParam, String run, String classFilter) throws OpException {
        RunStore s = storeForParam(targetParam);
        Map<String, Object> cov = s.coverage(runIdForRead(s, targetParam, run));
        if (classFilter == null || classFilter.isBlank()) return cov;
        @SuppressWarnings("unchecked")
        List<Object> addrs = (List<Object>) cov.get("addrs");
        List<Object> sel = new ArrayList<>();
        for (Object o : addrs)
            if (o instanceof Map<?, ?> m && String.valueOf(m.get("class")).contains(classFilter)) sel.add(o);
        Map<String, Object> out = new LinkedHashMap<>(cov);
        out.put("addrs", sel);
        out.put("count", sel.size());
        out.put("classFilter", classFilter);
        return out;
    }

    public java.util.concurrent.CompletableFuture<Void> beginShutdown() { return bridge.beginShutdown(); }
    public void shutdown() { bridge.shutdown(); }

    // ------------------------------------------------------------ devices / attach

    public Map<String, Object> devices() throws OpException { return devices(true); }

    public Map<String, Object> devices(boolean probe) throws OpException {
        Map<String, Object> r;
        try {
            r = new LinkedHashMap<>(bridge.devices());
            r.put("sidecarAvailable", true);
            Object ds = r.get("devices");
            if (ds instanceof List<?> l) {
                for (Object o : l) {
                    if (o instanceof Map<?, ?> m && m.get("serial") != null) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> d = (Map<String, Object>) m;
                        String serial = String.valueOf(d.get("serial"));
                        if (d.get("root") == null) {
                            try {
                                d.put("root", ws.devices().rootOf(serial, probe));
                            } catch (Exception ignored) {
                                d.put("root", Boolean.TRUE.equals(d.get("adbdRoot")) ? "adbd-root" : "none");
                            }
                        }
                    }
                }
            }
        } catch (RuntimeException e) {
            r = new LinkedHashMap<>();
            try {
                Map<String, Object> fallback = ws.devices().list(probe);
                r.put("devices", fallback.getOrDefault("devices", List.of()));
            } catch (Exception ignored) {
                r.put("devices", List.of());
            }
            r.put("sidecarAvailable", false);
            r.put("sidecarError", e.getMessage());
        }
        if (!sessionCtx.isEmpty()) reconcileSessions(null);
        // live session state per serial, for the device bar
        for (Map.Entry<String, Session> e : sessionCtx.entrySet()) {
            Session ctx = e.getValue();
            Object ds = r.get("devices");
            if (ds instanceof List<?> l)
                for (Object o : l)
                    if (o instanceof Map<?, ?> m && ctx.device().equals(m.get("serial"))) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> d = (Map<String, Object>) m;
                        d.put("session", e.getKey());
                        d.put("package", ctx.pkg());
                        d.put("target", ctx.tid());
                        d.put("project", ctx.project());
                        d.put("live", true);
                    }
        }
        return r;
    }

    private static String abiToFridaArch(String abi) {
        if (abi == null) return null;
        return switch (abi.trim()) {
            case "arm64-v8a", "arm64", "android-arm64" -> "arm64";
            case "x86_64", "android-x86_64" -> "x86_64";
            case "armeabi-v7a", "armeabi", "arm", "android-arm" -> "arm";
            case "x86", "android-x86" -> "x86";
            default -> null;
        };
    }

    /**
     * runs.setup: build backend/runs/venv, download matching frida-server binary into
     * backend/runs/server/, verify sidecar ping, and optionally push /data/local/tmp/frida-server
     * to the selected or connected Android device.
     */
    public Map<String, Object> setup(String deviceParam, boolean pushServer) throws OpException {
        File script = FridaBridge.locate("runs/pull-frida.sh");
        if (script == null || !script.isFile())
            throw OpException.notFound("pull-frida.sh not found", "expected backend/runs/pull-frida.sh");
        String serial = (deviceParam != null && !deviceParam.isBlank()) ? deviceParam.trim() : null;
        if (serial == null) {
            try {
                for (Map<String, Object> d : ws.devices().listFast()) {
                    if ("device".equals(d.get("state")) && d.get("serial") != null) {
                        serial = String.valueOf(d.get("serial"));
                        break;
                    }
                }
            } catch (Exception ignored) {}
        }
        String arch = null;
        if (serial != null) {
            try {
                Map<String, Object> cap = ws.devices().rootCapability(serial);
                arch = abiToFridaArch(String.valueOf(cap.getOrDefault("abi", "")));
            } catch (Exception ignored) {}
        }
        List<String> cmd = new ArrayList<>();
        cmd.add("bash");
        cmd.add(script.getAbsolutePath());
        if (arch != null) cmd.add(arch);
        java.nio.file.Path tmp = null;
        try {
            tmp = java.nio.file.Files.createTempFile("wb-frida-setup-", ".out");
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.to(tmp.toFile())).start();
            p.getOutputStream().close();
            if (!p.waitFor(180, java.util.concurrent.TimeUnit.SECONDS)) {
                p.destroyForcibly();
                throw OpException.busy("pull-frida.sh timed out after 180s", "");
            }
            String out = java.nio.file.Files.readString(tmp).trim();
            if (p.exitValue() != 0)
                throw OpException.unsupported("pull-frida.sh failed (exit " + p.exitValue() + "): " + out, "");
        } catch (OpException e) {
            throw e;
        } catch (Exception e) {
            throw OpException.unsupported("Failed to run pull-frida.sh: " + e.getMessage(), "");
        } finally {
            if (tmp != null) try { java.nio.file.Files.deleteIfExists(tmp); } catch (Exception ignored) {}
        }
        Map<String, Object> ping;
        try {
            ping = bridge.ping();
        } catch (RuntimeException e) {
            throw OpException.unsupported("Frida sidecar ping failed after setup: " + e.getMessage(), "");
        }
        String ver = String.valueOf(ping.getOrDefault("frida", "17.18.0"));
        String effectiveArch = arch != null ? arch : "arm64";
        File srvDir = new File(script.getParentFile(), "server");
        File serverBin = new File(srvDir, "frida-server-" + ver + "-android-" + effectiveArch);
        if (!serverBin.isFile()) {
            File[] files = srvDir.listFiles((dir, name) -> name.startsWith("frida-server-" + ver + "-android-") && !name.contains(".tmp."));
            if (files != null && files.length > 0) serverBin = files[0];
        }
        String deviceServerPath = "/data/local/tmp/frida-server";
        Boolean pushed = null;
        if (pushServer && serial != null) {
            try {
                Map<String, Object> pushRes = bridge.pushServer(serial, deviceServerPath);
                if (pushRes.get("serverBin") != null) serverBin = new File(String.valueOf(pushRes.get("serverBin")));
                if (pushRes.get("arch") != null) effectiveArch = String.valueOf(pushRes.get("arch"));
                pushed = Boolean.TRUE.equals(pushRes.get("pushed"));
            } catch (RuntimeException e) {
                throw OpException.unsupported("Host venv is ready, but pushing frida-server to " + serial + " failed: " + e.getMessage(), "");
            }
        }
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("ok", true);
        res.put("frida", ver);
        res.put("venvReady", true);
        res.put("arch", effectiveArch);
        res.put("serverBin", serverBin.getAbsolutePath());
        res.put("deviceServerPath", deviceServerPath);
        if (serial != null) res.put("device", serial);
        if (pushed != null) res.put("pushed", pushed);
        return res;
    }

    /**
     * runs.attach / runs.spawn: provision frida-server, attach (or spawn) the
     * package with the target's enabled BPs, open a run, wire hit attribution.
     * Legacy broadcast arguments are refused; human actions stay explicit.
     */
    public Map<String, Object> attach(String device, String pkg, String targetParam,
            boolean spawn, String coldAction, String coldComponent) throws OpException {
        throw OpException.syntax("Local capture needs explicit serverPath and ownershipConfirmed", "use the current Runs setup controls");
    }

    public Map<String, Object> attach(String device, String pkg, String targetParam,
            boolean spawn, String coldAction, String coldComponent, String serverPath,
            boolean ownershipConfirmed, int serverPort) throws OpException {
        if (device == null || device.isBlank()) throw OpException.syntax("runs.attach needs a device", "");
        synchronized (deviceLocks.computeIfAbsent(device,k -> new Object())) {
            return attachOwned(device,pkg,targetParam,spawn,coldAction,coldComponent,serverPath,ownershipConfirmed,serverPort);
        }
    }

    private final java.util.concurrent.ConcurrentHashMap<String,Object> deviceLocks=new java.util.concurrent.ConcurrentHashMap<>();

    private void reconcileSessions(String deviceFilter) {
        if (sessionCtx.isEmpty()) return;
        java.util.Set<String> active = new java.util.HashSet<>();
        try {
            Map<String, Object> res = bridge.sessions();
            if (res.get("sessions") instanceof List<?> l) {
                for (Object o : l) {
                    if (o instanceof Map<?, ?> m && m.get("session") != null)
                        active.add(String.valueOf(m.get("session")));
                }
            }
        } catch (RuntimeException e) {
            return;
        }
        for (Map.Entry<String, Session> e : new ArrayList<>(sessionCtx.entrySet())) {
            if (deviceFilter != null && !deviceFilter.equals(e.getValue().device())) continue;
            if (!active.contains(e.getKey())) {
                onSessionDead(Map.of("session", e.getKey(), "reason", "process-terminated"));
            }
        }
    }

    private Map<String, Object> attachOwned(String device, String pkg, String targetParam,
            boolean spawn, String coldAction, String coldComponent, String serverPath,
            boolean ownershipConfirmed, int serverPort) throws OpException {
        if (!ownershipConfirmed || serverPath == null || serverPath.isBlank())
            throw OpException.syntax("Select an installed server and confirm exclusive controller ownership", "external controllers must use import");
        if (targetParam == null || targetParam.isBlank())
            throw OpException.syntax("Capture requires an explicit target pin", "target=<open target id>");
        if (coldAction != null || coldComponent != null)
            throw OpException.unsupported("Automatic force-stop/broadcast was retired", "perform and mark the actions explicitly");
        if (device == null || device.isBlank()) throw OpException.syntax("runs.attach needs a device", "");
        if (pkg == null || pkg.isBlank()) throw OpException.syntax("runs.attach needs a package", "");
        String tid = ws.resolveTargetIdOrActive(targetParam);
        RunStore store = storeForTarget(tid);   // the run is recorded with the target's PROJECT
        store.rebindTarget(tid, ws.targetStoreKey(tid));   // rows from an earlier boot install too (r3 H1)
        List<Map<String, Object>> hooks = store.hooksFor(tid);
        String project=ws.projectNameOfTarget(tid);
        reconcileSessions(device);
        boolean alreadyOwned=sessionCtx.values().stream().anyMatch(s -> device.equals(s.device()));
        if (alreadyOwned) throw OpException.busy("Device already has an owned session", "stop it explicitly before a new capture");
        boolean acquired=false;
        try {
            bridge.provision(device, serverPath, ownershipConfirmed, serverPort);
            acquired=true;
            Map<String, String> cold = coldAction != null && coldComponent != null
                    ? Map.of("action", coldAction, "component", coldComponent) : null;
            Map<String, Object> at = bridge.attach(device, pkg, spawn, cold, hooks);
            String sid = String.valueOf(at.get("session"));
            List<String> bpIds = new ArrayList<>();
            for (Map<String, Object> h : hooks) bpIds.add(String.valueOf(h.get("hookId")));
            Map<String, Object> ex;
            synchronized (ws) {
                if (!java.util.Objects.equals(project,ws.projectNameOfTarget(tid))) throw new IllegalStateException("Target/project changed during attach");
                ex=store.startRun(sid, device, pkg, null,
                        System.currentTimeMillis(), bpIds, tid, ws.targetStoreKey(tid), ws.targetNameOf(tid));
                sessionCtx.put(sid, new Session(tid, String.valueOf(ex.get("execId")), device, pkg,project,store));
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("session", sid);
            out.put("pid", at.get("pid"));
            out.put("spawned", at.get("spawned"));
            out.put("execId", ex.get("execId"));
            out.put("hooks", at.get("hooks"));
            out.put("ownership", at.get("ownership"));
            out.put("processIdentity", at.get("processIdentity"));
            out.put("device", device);
            out.put("package", pkg);
            out.put("target", tid);
            Map<String, Object> ev = new LinkedHashMap<>();
            ev.put("type", "session");
            ev.put("state", "live");
            ev.put("session", sid);
            ev.put("device", device);
            ev.put("package", pkg);
            ev.put("execId", ex.get("execId"));
            bus.publish("runs", ev);
            return out;
        } catch (RuntimeException e) {
            Map<String,Object> cleanup=Map.of("cleanup","unknown");
            if (acquired) try { cleanup=bridge.cleanup(device); } catch (RuntimeException ignored) {}
            throw OpException.busy("attach failed: " + e.getMessage(), "cleanup=" + dev.workbench.registry.Json.write(cleanup));
        }
    }

    /** Detach a session (or all of them), closing its run. */
    public Map<String, Object> detach(String session) {
        List<Object> detached = new ArrayList<>();
        if (session != null && !session.isBlank()) {
            if (closeSession(session)) detached.add(session);
        } else {
            /* "all" = every live session of the ACTIVE project (batch 2) — a DETACH in one
               project's Runs view must not end another project's sessions */
            String active = ws.activeProjectName();
            for (Map.Entry<String, Session> e : new ArrayList<>(sessionCtx.entrySet()))
                if (active == null || active.equals(e.getValue().project()))
                    if (closeSession(e.getKey())) detached.add(e.getKey());
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("detached", detached);
        return out;
    }

    private boolean closeSession(String sid) {
        Session current=sessionCtx.get(sid);
        if (current==null) return false;
        synchronized (deviceLocks.computeIfAbsent(current.device(),k -> new Object())) { return closeOwnedSession(sid); }
    }

    private boolean closeOwnedSession(String sid) {
        Session ctx = sessionCtx.remove(sid); // detach event must not consume the context before its cleanup receipt
        if (ctx==null) return false;
        boolean ok = false;
        Map<String, Object> cleanup = Map.of("cleanup", "unknown");
        try { cleanup = bridge.detach(sid); ok = Boolean.TRUE.equals(cleanup.get("detached")); } catch (RuntimeException ignored) {}
        if (ctx != null) {
            ctx.store().stopRun(ctx.execId(), System.currentTimeMillis());
            Map<String, Object> serverCleanup = Map.of("cleanup", "unknown");
            try { serverCleanup = bridge.cleanup(ctx.device()); } catch (RuntimeException ignored) {}
            ctx.store().lifecycleResult(ctx.execId(), cleanup, serverCleanup);
        }
        return ok;
    }

    // ------------------------------------------------------------ breakpoints

    /** runs.bp.add <symbol> [--cond] [target] — resolve to the origSig install handle.
     *  Mock targets (no real artifact) → E_UNSUPPORTED: their symbols can't exist on a device. */
    public Map<String, Object> bpAdd(String targetParam, String symbol, String cond) throws OpException {
        String tid = ws.resolveTargetIdOrActive(targetParam);
        if (ws.sidecarDirFor(tid) == null)
            throw OpException.unsupported("breakpoints need a real target (mock fixtures have no device code)",
                    "target=" + tid);
        Map<String, Object> r;
        try {
            r = ws.resolveForEvidence(tid, symbol);   // a hook on a contradicting descriptor is refused (handoff §B: strict for evidence, hooks, corpus)
        } catch (OpException e) {
            throw e;
        } catch (Exception e) {
            throw OpException.notFound("symbol not found: " + symbol, "target=" + tid);
        }
        String kind = String.valueOf(r.getOrDefault("kind", ""));
        if (!"method".equals(kind))
            throw OpException.unsupported("breakpoints are method-level", "kind=" + kind);
        String origSig = r.get("origSig") != null ? String.valueOf(r.get("origSig")) : null;
        if (origSig == null)
            throw OpException.unsupported("no original signature for: " + symbol, "target=" + tid);
        RunStore store = storeForTarget(tid);
        store.rebindTarget(tid, ws.targetStoreKey(tid));   // dedupe against the row this target already has (r3 H1)
        Map<String, Object> bp = store.setBp(tid, origSig, cond, true, this::nextBpId);
        store.stampBpKey(String.valueOf(bp.get("id")), ws.targetStoreKey(tid), ws.targetNameOf(tid));
        syncSessions(tid);
        return bp;
    }

    /** runs.bp.add for a NATIVE breakpoint: a module + an export (resolved by frida) or a base
     *  offset (from a disassembler), not a JEB method — so no engine.resolve. Stored on the target
     *  like a Java BP and installed on the native agent at attach / via syncSessions. */
    public Map<String, Object> bpAddNative(String targetParam, String module, String export,
            String offset, String function, String so, String cond) throws OpException {
        if (module == null || module.isBlank())
            throw OpException.syntax("a native breakpoint needs a module", "e.g. libssl.so");
        boolean hasExport = export != null && !export.isBlank();
        boolean hasOffset = offset != null && !offset.isBlank();
        boolean hasFunction = function != null && !function.isBlank();
        if ((hasExport ? 1 : 0) + (hasOffset ? 1 : 0) + (hasFunction ? 1 : 0) != 1)
            throw OpException.syntax("a native breakpoint needs exactly one of export, offset, or function",
                    "export=" + export + " offset=" + offset + " function=" + function);
        String tid = ws.resolveTargetIdOrActive(targetParam);
        if (ws.sidecarDirFor(tid) == null)
            throw OpException.unsupported("breakpoints need a real target (mock fixtures have no device code)",
                    "target=" + tid);

        // A full-path module names the EXACT library to bind — several loaded libraries can share a
        // basename (e.g. /apex/.../libcrypto.so vs /system/lib64/libcrypto.so, the SAME function at
        // different offsets). Display/label keep the basename; `path` drives the agent bind + resolution.
        String path = null, buildId = null;
        if (module.indexOf('/') >= 0) { path = module; module = module.substring(module.lastIndexOf('/') + 1); }
        if (module.isBlank())   // e.g. a trailing-slash path — the initial blank check ran before the split
            throw OpException.syntax("a native breakpoint needs a module name", "got a path with no filename: " + path);

        // function mode: resolve the NAME to a module-base offset via idalib against a local .so. The
        // offset becomes frida's binding; the bp is still keyed/shown by name (see setNativeBp).
        if (hasFunction) {
            if (so == null || so.isBlank()) {         // no explicit path — use the pulled copy of the module
                java.io.File pulled = ws.devices().pulledFileFor(module);
                if (pulled != null && pulled.isFile()) {
                    so = pulled.getAbsolutePath();
                    // bind to the exact library this copy came from — an offset computed against it must
                    // NOT be applied to a different same-basename module loaded in the process.
                    if (path == null) path = pulledDevicePath(pulled);
                } else throw OpException.notFound("no local copy of " + module + " to resolve a name against",
                        "pull it first (device.pull for " + module + ") or pass so=<path to the .so>");
            }
            offset = NativeSymbols.resolve(so, java.util.List.of(function)).get(function);
            if (offset == null)
                throw OpException.notFound("no function '" + function + "' in " + module,
                        "idalib found no function ENTRY by that name in " + so
                        + " (unknown, or a data/label address — unsafe to hook; names are IDA's stored/mangled form)");
            buildId = NativeSymbols.buildId(new java.io.File(so));   // the agent refuses to bind a different build
        }
        // offset mode: a hand-fed offset carries no source binary, but if a local/pulled copy of the
        // module exists, pin ITS build-id too — so a device lib that changed since the pull is caught the
        // same way. (export mode needs none: frida resolves the symbol in whatever is loaded.)
        if (hasOffset && buildId == null) {
            java.io.File ref = (so != null && !so.isBlank()) ? new java.io.File(so)
                    : ws.devices().pulledFileFor(module);
            if (ref != null && ref.isFile()) buildId = NativeSymbols.buildId(ref);
        }

        RunStore store = storeForTarget(tid);
        store.rebindTarget(tid, ws.targetStoreKey(tid));
        Map<String, Object> bp = store.setNativeBp(tid, module,
                hasExport ? export : null,
                hasExport ? null : offset,          // offset drives frida for both offset & function modes
                hasFunction ? function : null,
                path, buildId, cond, this::nextBpId);
        store.stampBpKey(String.valueOf(bp.get("id")), ws.targetStoreKey(tid), ws.targetNameOf(tid));
        syncSessions(tid);
        return bp;
    }

    /** The device path a pulled library came from (from its family prov record), else null — lets a
        native bp bind to the EXACT loaded module its offset was computed against. */
    private String pulledDevicePath(java.io.File pulledSo) {
        String p = pulledSo.getAbsolutePath();
        String jbase = p.endsWith(".so") ? p.substring(0, p.length() - 3) : p;   // prov is <jbase>.prov.json
        Map<String, Object> prov = ws.devices().provFor(jbase);
        if (prov == null || prov.get("pulledFrom") == null) return null;
        String pf = String.valueOf(prov.get("pulledFrom"));   // "<serial>:/device/path"; serial may be host:port
        int c = pf.indexOf(":/");                              // device paths are absolute → ":/" is unambiguous
        if (c < 0) c = pf.indexOf(':');
        return c >= 0 ? pf.substring(c + 1) : pf;
    }

    public Map<String, Object> bpRm(String id) throws OpException {
        RunStore store = storeOfBp(id);
        Map<String, Object> bp = store.bp(id);
        Map<String, Object> out = store.removeBp(id);
        if (bp != null) {
            for (Map.Entry<String, Session> e : sessionCtx.entrySet())
                if (bp.get("target") != null && bp.get("target").equals(e.getValue().tid()))
                    try { bridge.removeHook(e.getKey(), id); } catch (RuntimeException ignored) {}
        }
        return out;
    }

    public Map<String, Object> bpToggle(String id) throws OpException {
        Map<String, Object> bp = storeOfBp(id).toggleBp(id);
        Object target = bp.get("target");
        if (target != null && ws.projectNameOfTarget(String.valueOf(target)) != null)
            syncSessions(String.valueOf(target));   // a stale (pre-restart) target id has no session to sync
        return bp;
    }

    /** Live-apply the target's BP set onto its live sessions (no re-attach). */
    private void syncSessions(String tid) throws OpException {
        RunStore s = storeForTarget(tid);
        s.rebindTarget(tid, ws.targetStoreKey(tid));
        List<Map<String, Object>> want = s.hooksFor(tid);
        Map<String, Map<String, Object>> wantById = new LinkedHashMap<>();
        for (Map<String, Object> h : want) wantById.put(String.valueOf(h.get("hookId")), h);
        for (Map.Entry<String, Session> e : sessionCtx.entrySet()) {
            if (!tid.equals(e.getValue().tid())) continue;
            String sid = e.getKey();
            // install enabled bps not yet hooked, remove disabled ones
            Map<String, Object> installed = null;
            try { installed = bridge.sessions(); } catch (RuntimeException ignored) {}
            List<String> have = new ArrayList<>();
            if (installed != null && installed.get("sessions") instanceof List<?> l)
                for (Object o : l)
                    if (o instanceof Map<?, ?> m && sid.equals(m.get("session"))
                            && m.get("hooks") instanceof List<?> hl)
                        for (Object h : hl) have.add(String.valueOf(h));
            for (Map<String, Object> h : want)
                if (!have.contains(String.valueOf(h.get("hookId"))))
                    try {
                        if (Boolean.TRUE.equals(h.get("native")))
                            bridge.addNativeHook(sid, String.valueOf(h.get("hookId")),
                                    String.valueOf(h.get("module")),
                                    h.get("export") == null ? null : String.valueOf(h.get("export")),
                                    h.get("offset") == null ? null : String.valueOf(h.get("offset")),
                                    h.get("buildId") == null ? null : String.valueOf(h.get("buildId")));
                        else
                            bridge.addHook(sid, String.valueOf(h.get("hookId")),
                                    String.valueOf(h.get("signature")), null);
                    } catch (RuntimeException ignored) {}
        }
    }

    // ------------------------------------------------------------ hit relay (FridaBridge.HitListener)

    @Override
    public void onHit(Map<String, Object> ev) {
        Session ctx = sessionCtx.get(String.valueOf(ev.get("session")));
        if (ctx == null) return;
        String bpId = String.valueOf(ev.get("hookId"));
        RunStore store = ctx.store();
        Map<String, Object> bp = store.bp(bpId);
        String cond = bp == null || bp.get("cond") == null ? null : String.valueOf(bp.get("cond"));
        Map<String, Object> hit = store.recordHit(ctx.execId(), bpId, cond, ev);
        if (hit == null) return;
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("type", "hit");
        out.put("execId", ctx.execId());
        out.put("target", ctx.tid());
        out.put("bpId", bpId);
        out.put("addr", hit.get("addr"));
        out.put("name", hit.get("addr") == null ? null : RunStore.labelOf(String.valueOf(hit.get("addr"))));
        out.put("hitId", hit.get("hitId"));
        out.put("ts", ev.get("ts"));
        out.put("thread", ev.get("thread"));
        out.put("argsBrief", brief(ev.get("args")));
        out.put("stackId", hit.get("stackId"));
        if (hit.get("stackId") != null)
            out.put("frames", store.stackOf(String.valueOf(hit.get("stackId"))));   // the session's store
        bus.publish("runs", out);
    }

    @Override
    public void onHitCount(Map<String, Object> ev) {
        Session ctx = sessionCtx.get(String.valueOf(ev.get("session")));
        if (ctx == null) return;
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("type", "hitcount");
        out.put("execId", ctx.execId());
        out.put("bpId", ev.get("hookId"));
        out.put("dropped", ev.get("dropped"));
        out.put("count", ev.get("count"));
        out.put("ts", ev.get("ts"));
        bus.publish("runs", out);
    }

    @Override
    public void onSessionDead(Map<String, Object> ev) {
        String sid = String.valueOf(ev.get("session"));
        Session ctx = sessionCtx.remove(sid);
        if (ctx != null) {
            ctx.store().stopRun(ctx.execId(), System.currentTimeMillis());
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("type", "session");
            out.put("state", "dead");
            out.put("session", sid);
            out.put("execId", ctx.execId());
            ctx.store().lifecycleResult(ctx.execId(), Map.of("detached", false, "cleanup", "unknown"), Map.of("cleanup", "unknown"));
            out.put("completion", "partial");
            out.put("cleanup", "unknown");
            out.put("reason", ev.get("reason"));
            bus.publish("runs", out);
        }
    }

    @Override
    public void onSessionLive(Map<String, Object> ev) { /* attach() publishes the rich row */ }

    private static String brief(Object args) {
        if (!(args instanceof List<?> a)) return "";
        StringBuilder sb = new StringBuilder("(");
        for (int i = 0; i < a.size() && i < 4; i++) {
            if (i > 0) sb.append(", ");
            String v = String.valueOf(a.get(i));
            sb.append(v.length() > 40 ? v.substring(0, 39) + "…" : v);
        }
        if (a.size() > 4) sb.append(", …");
        return sb.append(")").toString();
    }
}
