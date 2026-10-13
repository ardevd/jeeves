package dev.workbench.runs;

import dev.workbench.registry.Json;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Per-project dynamic-analysis store (VIEWS_SPEC §2): method breakpoints, runs
 * (executions), hits, coverage. Persisted in {@code backend/.workbench/runs/} —
 * {@code bps.json} (breakpoint defs: target + origSig + cond + enabled),
 * {@code index.json} (run metadata + per-run coverage), {@code <execId>.jsonl}
 * (append-only hits), {@code stacks.jsonl} (deduped backtraces). Thread-safe.
 *
 * A breakpoint carries the ORIGINAL Dalvik signature (the device runs the
 * obfuscated names) plus its binding target; hooks install the origSig.
 * Method-level only. No suspend/modify — a hit is an observation.
 */
public final class RunStore {

    private final File dir;
    private final Map<String, Map<String, Object>> bps = new LinkedHashMap<>();      // bpId -> bp
    private final Map<String, Map<String, Object>> runs = new LinkedHashMap<>();     // execId -> run meta
    private final Map<String, Set<String>> coverage = new LinkedHashMap<>();         // execId -> method addrs
    private final Map<String, String> stackIds = new LinkedHashMap<>();              // frames-hash -> stackId
    private final Map<String, Object> stacks = new LinkedHashMap<>();                // stackId -> frames
    private int bpSeq, runSeq, hitSeq, stackSeq;
    private final long hitBudget = Long.getLong("workbench.hitBudget", 50_000L);
    /* files whose load failed transiently (fix-1-r3): the on-disk original may
       be healthy — the first write to it parks it aside as evidence instead of
       silently overwriting unseen breakpoints/runs AND resetting the seq
       counters those unseen rows had advanced (id reuse onto live references) */
    private final Set<String> degradedFiles = new java.util.HashSet<>();

    public RunStore(File dir) {
        this.dir = dir;
        try {
            load();
        } catch (Throwable t) {
            /* a corrupt runs sidecar must never block the BOOT (fix-1-r6 HIGH-1:
               an unguarded (Number) cast on a hand-edited bps.json propagated
               out of new RunStore → new RunsManager → the server never started).
               Log, count, boot with whatever loaded — AND mark the primary files
               degraded: the state is PARTIAL (seqs undercounted, rows missing),
               so the next persist parks the on-disk originals before overwriting
               them (fix-1-r7 F4: the bare net routed around the very machinery
               the series built). */
            System.err.println("[runs] WARNING: partial load — the runs store is degraded: " + t);
            dev.workbench.registry.SidecarIO.loadFailures.incrementAndGet();
            degradedFiles.add("bps.json");
            degradedFiles.add("index.json");
        }
    }

    public File directory() { return dir; }

    /** Import the already frozen and semantically validated canonical bytes. No hook is installed. */
    @SuppressWarnings("unchecked")
    public synchronized Map<String, Object> importCanonical(File frozen, String target, String targetKey,
            String targetName, String manifestSha, boolean fixtureBinding) throws IOException {
        if (!manifestSha.matches("[0-9a-f]{64}")) throw new IOException("Invalid canonical digest");
        Map<String,Object> doc = Json.parseObject(Files.readString(new File(frozen,"manifest.json").toPath()));
        String runId = String.valueOf(doc.get("run_id"));
        for (Map<String,Object> prior : runs.values()) if (runId.equals(prior.get("sourceRunId"))) {
            if (!manifestSha.equals(prior.get("sourceManifestSha256"))) throw new IOException("Run UUID already imported with different immutable bytes");
            if (!belongs(prior,target,targetKey)) throw new IOException("Run UUID is already bound to another target");
            return new LinkedHashMap<>(prior);
        }
        String execId = "ex" + (++runSeq);
        if (!persistSeq()) throw new IOException("Run sequence could not be persisted; no bundle imported");
        Map<String,Object> cap = (Map<String,Object>) doc.get("capture");
        Map<String,Object> dev = (Map<String,Object>) doc.get("device");
        Map<String,Object> app = (Map<String,Object>) doc.get("target");
        Map<String,Object> producer = (Map<String,Object>) doc.get("producer");
        Map<String,Object> clean = (Map<String,Object>) doc.get("cleanup");
        File kept = new File(new File(dir,"bundles"),manifestSha);
        kept.getParentFile().mkdirs();
        // A deleted or interrupted import can retain these bytes. Preserve that
        // directory as evidence, and use the newly validated frozen copy for retry.
        if (Files.exists(kept.toPath(),java.nio.file.LinkOption.NOFOLLOW_LINKS))
            Files.move(kept.toPath(),new File(kept.getPath()+".unindexed-"+java.util.UUID.randomUUID()).toPath());
        Files.move(frozen.toPath(),kept.toPath());
        Map<String,Object> row = new LinkedHashMap<>();
        row.put("execId",execId);row.put("session",((Map<?,?>)doc.get("session")).get("id"));
        row.put("sourceRunId",runId);row.put("sourceManifestSha256",manifestSha);
        row.put("producerMode",producer.get("mode"));row.put("completion",cap.get("completion"));row.put("cleanup",clean.get("state"));
        row.put("canonicalBundle", "bundles/"+manifestSha);row.put("fixtureBinding",fixtureBinding);
        row.put("device",dev.get("id"));row.put("package",app.get("package"));row.put("version",app.get("version"));
        row.put("target",target);row.put("targetKey",targetKey);row.put("targetName",targetName);
        row.put("startTs",java.time.Instant.parse(String.valueOf(cap.get("started_at"))).toEpochMilli());
        row.put("endTs",java.time.Instant.parse(String.valueOf(cap.get("ended_at"))).toEpochMilli());
        row.put("color",(runSeq-1)%6);row.put("bpIds",List.of());row.put("bpHits",Map.of());
        Set<String> observed = new LinkedHashSet<>();
        long hits=0, projected=0;
        File log = new File(dir,execId+".jsonl");
        try (java.io.BufferedWriter output=Files.newBufferedWriter(log.toPath(),StandardCharsets.UTF_8,java.nio.file.StandardOpenOption.CREATE_NEW)) {
            for (Object entry : (List<?>)doc.get("files")) {
                Map<?,?> file=(Map<?,?>)entry;
                if (!"events".equals(file.get("role"))) continue;
                try (BufferedReader input=Files.newBufferedReader(new File(kept,String.valueOf(file.get("path"))).toPath(),StandardCharsets.UTF_8)) {
                    String line;
                    while ((line=input.readLine())!=null) {
                        Map<String,Object> event=Json.parseObject(line);
                        if (!List.of("enter","leave","exception").contains(event.get("kind"))) continue;
                        hits++;
                        String original=event.get("origSig")==null?null:String.valueOf(event.get("origSig"));
                        if (original!=null) observed.add(original);
                        if (projected>=hitBudget) continue;
                        Map<String,Object> hit=new LinkedHashMap<>();
                        hit.put("hitId","h"+(++hitSeq));hit.put("execId",execId);hit.put("bpId","external:"+event.get("hook_id"));hit.put("sourceHookId",event.get("hook_id"));
                        hit.put("addr",original);hit.put("thread",event.get("thread_id")==null?null:Map.of("name",event.get("thread_id")));hit.put("args",event.get("args"));
                        if (original==null && event.get("native") instanceof Map<?,?> nativeSite)
                            hit.put("name",String.valueOf(nativeSite.get("module"))+"+"+nativeSite.get("relative_offset"));
                        hit.put("native",event.get("native"));
                        hit.put("ret",event.get("result"));hit.put("threw","exception".equals(event.get("kind")));
                        hit.put("ts",row.get("startTs"));hit.put("timestampBasis","run-start-placeholder-use-monotonic_ns");
                        hit.put("monotonic_ns",event.get("monotonic_ns"));hit.put("sourceSeq",event.get("seq"));
                        output.write(Json.write(hit));output.newLine();projected++;
                    }
                }
            }
        }
        row.put("hitCount",hits);row.put("projectedHits",projected);row.put("projectionTruncated",hits>projected);
        row.put("coverageScope","hooked-methods");
        if (!persistSeq()) throw new IOException("Hit sequence could not be persisted; canonical evidence retained at " + kept);
        runs.put(execId,row);coverage.put(execId,observed);
        if (!persistRuns()) {
            runs.remove(execId);coverage.remove(execId);
            throw new IOException("Run index could not be persisted; canonical evidence retained at " + kept + "; inspect retained evidence before retry");
        }
        return new LinkedHashMap<>(row);
    }

    public synchronized File canonicalBundle(String execId) {
        Map<String,Object> row=runs.get(execId);
        if (row==null || row.get("sourceManifestSha256")==null) throw new IllegalArgumentException("Run has no canonical imported bundle");
        String sha=String.valueOf(row.get("sourceManifestSha256"));
        if (!sha.matches("[0-9a-f]{64}")) throw new IllegalStateException("Stored canonical digest is invalid");
        return new File(new File(dir,"bundles"),sha);
    }

    // ------------------------------------------------------------ breakpoints

    /** Create or update (idempotent by origSig) a breakpoint bound to a target. */
    public synchronized Map<String, Object> setBp(String target, String origSig, String cond,
            boolean enabled) {
        return setBp(target, origSig, cond, enabled, null);
    }

    /** newId: mints the id of a NEW bp (RunsManager's cross-project sequence, r2 H2) — asked
     *  only on a miss, so a re-add of an existing bp burns nothing (r3 L2); null keeps the
     *  store's own counter (tests, legacy). */
    public synchronized Map<String, Object> setBp(String target, String origSig, String cond,
            boolean enabled, java.util.function.Supplier<String> newId) {
        Map<String, Object> bp = null;
        for (Map<String, Object> b : bps.values())
            if (origSig.equals(b.get("origSig")) && target.equals(b.get("target"))) { bp = b; break; }
        if (bp == null) {
            bp = new LinkedHashMap<>();
            String id = newId == null ? null : newId.get();
            if (id != null) { bp.put("id", id); bpSeq = Math.max(bpSeq, seqOf(id, "bp")); }
            else bp.put("id", "bp" + (++bpSeq));
            bp.put("hits", 0L);
            bps.put(String.valueOf(bp.get("id")), bp);
        }
        bp.put("target", target);
        bp.put("origSig", origSig);
        bp.put("addr", origSig);
        bp.put("name", labelOf(origSig));
        bp.put("owner", ownerOf(origSig));
        bp.put("enabled", enabled);
        if (cond != null) bp.put("cond", cond);
        persistBps();
        return bp;
    }

    /** a NATIVE breakpoint (native breakpoints, 2026-09-10): a module + an export or a base offset,
     *  not a JEB method. Its origSig ("native:<module>!<export>" | "native:<module>+<offset>") is a
     *  stable per-module key — the coverage id and hit label — never a runtime address (which reminds
     *  per boot). Deduped on origSig+target like a Java BP; the hook spec goes to the native agent. */
    public synchronized Map<String, Object> setNativeBp(String target, String module, String export,
            String offset, String function, String path, String buildId, String cond,
            java.util.function.Supplier<String> newId) {
        boolean isExport = export != null && !export.isBlank();
        boolean isFunction = function != null && !function.isBlank();
        // origSig/label uses the NAME form for an export or a resolved function (a stable, human key);
        // a bare offset falls back to "+0x…". A function is bound to frida by its resolved offset but
        // is keyed/shown by name, so a hit reads "libcrypto.so!aes_nohw_encrypt", not "+0x90a9c".
        String tail = isExport ? "!" + export : isFunction ? "!" + function : "+" + offset;
        String origSig = "native:" + module + tail;
        Map<String, Object> bp = null;
        // dedup on origSig + target + PATH: two bps on the same basename+function but DIFFERENT libraries
        // (e.g. conscrypt vs /system libcrypto.so) must coexist — that is the whole point of `path`. A
        // basename-only bp has path==null and dedups with other basename bps as before.
        for (Map<String, Object> b : bps.values())
            if (origSig.equals(b.get("origSig")) && target.equals(b.get("target"))
                    && java.util.Objects.equals(path, b.get("path"))) { bp = b; break; }
        boolean existed = bp != null;
        boolean wasEnabled = existed && Boolean.TRUE.equals(bp.get("enabled"));
        if (bp == null) {
            bp = new LinkedHashMap<>();
            String id = newId == null ? null : newId.get();
            if (id != null) { bp.put("id", id); bpSeq = Math.max(bpSeq, seqOf(id, "bp")); }
            else bp.put("id", "bp" + (++bpSeq));
            bp.put("hits", 0L);
            bps.put(String.valueOf(bp.get("id")), bp);
        }
        bp.put("target", target);
        bp.put("kind", "native");
        bp.put("module", module);
        bp.remove("export"); bp.remove("offset"); bp.remove("function"); bp.remove("path"); bp.remove("buildId");
        if (isExport) bp.put("export", export);
        else { bp.put("offset", offset); if (isFunction) bp.put("function", function); }   // offset drives frida
        // exact binding path (disambiguates same-basename libraries — e.g. two libcrypto.so at
        // different offsets); display/origSig stay the clean basename. hooksFor emits this to the agent.
        if (path != null && !path.isBlank()) bp.put("path", path);
        // build-id of the copy the offset was computed against — the agent refuses to bind if the LOADED
        // module's build-id differs (a stale pull, or a lone wrong same-basename lib).
        if (buildId != null && !buildId.isBlank()) bp.put("buildId", buildId);
        bp.put("origSig", origSig);
        bp.put("addr", origSig);
        bp.put("name", module + tail);
        bp.put("owner", module);
        bp.put("enabled", existed ? wasEnabled : true);   // re-adding a toggled-off bp keeps it off
        if (cond != null) bp.put("cond", cond);
        persistBps();
        return bp;
    }

    public synchronized List<Object> bps(String target) {
        List<Object> out = new ArrayList<>();
        for (Map<String, Object> bp : bps.values())
            if (target == null || target.equals(bp.get("target"))) out.add(new LinkedHashMap<>(bp));
        return out;
    }

    /** Breakpoints of one target across restarts: the live id OR the durable store key. */
    public synchronized List<Object> bpsFor(String tid, String targetKey) {
        List<Object> out = new ArrayList<>();
        for (Map<String, Object> bp : bps.values())
            if (belongs(bp, tid, targetKey)) out.add(new LinkedHashMap<>(bp));
        return out;
    }

    /** Runs of one target across restarts (see bpsFor); newest first. */
    public synchronized List<Object> runsFor(String tid, String targetKey) {
        List<Object> out = new ArrayList<>();
        for (Object o : runs()) {
            @SuppressWarnings("unchecked")
            Map<String, Object> ex = (Map<String, Object>) o;
            if (belongs(ex, tid, targetKey)) out.add(ex);
        }
        return out;
    }

    private static boolean belongs(Map<String, Object> row, String tid, String targetKey) {
        if (tid != null && tid.equals(row.get("target"))) return true;
        return targetKey != null && targetKey.equals(row.get("targetKey"));
    }

    /** Re-bind the rows of a target that survived a restart: a row whose durable key is this
     *  target's but whose live id is a previous boot's gets the live id, so every id-only path
     *  (hooksFor at attach, setBp's dedupe, syncSessions) sees it (review r3 H1). Persists once
     *  when anything changed; returns the count. */
    public synchronized int rebindTarget(String tid, String targetKey) {
        if (tid == null || targetKey == null) return 0;
        int n = 0;
        for (Map<String, Object> bp : bps.values())
            if (targetKey.equals(bp.get("targetKey")) && !tid.equals(bp.get("target"))) {
                bp.put("target", tid);
                n++;
            }
        if (n > 0) persistBps();
        return n;
    }

    /** Stamp a bp with its target's durable identity (idempotent; persists only on change). */
    public synchronized void stampBpKey(String id, String targetKey, String targetName) {
        Map<String, Object> bp = bps.get(id);
        if (bp == null || targetKey == null || targetKey.equals(bp.get("targetKey"))) return;
        bp.put("targetKey", targetKey);
        if (targetName != null) bp.put("targetName", targetName);
        persistBps();
    }

    public synchronized Map<String, Object> toggleBp(String id) {
        Map<String, Object> bp = bps.get(id);
        if (bp == null) throw new IllegalArgumentException("no such breakpoint: " + id);
        bp.put("enabled", !Boolean.TRUE.equals(bp.get("enabled")));
        persistBps();
        return new LinkedHashMap<>(bp);
    }

    public synchronized Map<String, Object> removeBp(String id) {
        Map<String, Object> bp = bps.remove(id);
        persistBps();
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("removed", bp != null);
        r.put("id", id);
        return r;
    }

    /** Hooks to install for a target: enabled BPs bound to it, as {hookId, signature, cond}. */
    public synchronized List<Map<String, Object>> hooksFor(String target) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> bp : bps.values()) {
            if (!Boolean.TRUE.equals(bp.get("enabled"))) continue;
            if (!target.equals(bp.get("target"))) continue;
            Map<String, Object> h = new LinkedHashMap<>();
            h.put("hookId", bp.get("id"));
            if ("native".equals(bp.get("kind"))) {   // native BP → the native agent spec (2026-09-10)
                h.put("native", true);
                // bind by the exact path when known, else the basename (agent's findModule handles both)
                h.put("module", bp.get("path") != null ? bp.get("path") : bp.get("module"));
                if (bp.get("export") != null) h.put("export", bp.get("export"));
                if (bp.get("offset") != null) h.put("offset", bp.get("offset"));
                if (bp.get("buildId") != null) h.put("buildId", bp.get("buildId"));   // agent verifies before binding
            } else {
                h.put("signature", bp.get("origSig"));
            }
            out.add(h);
        }
        return out;
    }

    public synchronized Map<String, Object> bp(String id) {
        return bps.get(id);
    }

    // ------------------------------------------------------------ runs

    public synchronized Map<String, Object> startRun(String sessionId, String device, String pkg,
            String version, long ts, List<String> bpIds, String target) {
        return startRun(sessionId, device, pkg, version, ts, bpIds, target, null, null);
    }

    /** targetKey/targetName: the target's DURABLE store identity and display name (batch 2 —
     *  target ids remint per boot, so a run keyed on the id alone is unfilterable after a
     *  restart; runsFor matches either). */
    public synchronized Map<String, Object> startRun(String sessionId, String device, String pkg,
            String version, long ts, List<String> bpIds, String target, String targetKey, String targetName) {
        String execId = "ex" + (++runSeq);
        Map<String, Object> ex = new LinkedHashMap<>();
        ex.put("execId", execId);
        ex.put("session", sessionId);
        ex.put("device", device);
        ex.put("package", pkg);
        ex.put("version", version);
        ex.put("target", target);
        if (targetKey != null) ex.put("targetKey", targetKey);
        if (targetName != null) ex.put("targetName", targetName);
        ex.put("color", (runSeq - 1) % 6);
        ex.put("startTs", ts);
        ex.put("endTs", null);
        ex.put("hitCount", 0L);
        ex.put("bpIds", bpIds == null ? new ArrayList<>() : new ArrayList<>(bpIds));
        runs.put(execId, ex);
        coverage.put(execId, new LinkedHashSet<>());
        persistRuns();
        persistSeq();                                        // M3: a new id must survive an emptied index
        return ex;
    }

    /** runs.rm: drop a run's index row and coverage. The full row (with coverage) is preserved in
     *  removed.jsonl and the hit log is PARKED beside the others (<execId>.jsonl.deleted-<ts>),
     *  never unlinked — so a deleted run is recoverable in full (index row + hits), not just its
     *  raw hits (b2-r5 M1). A LIVE/unclosed run (endTs==null) is refused under this lock and
     *  reported {skippedLive:true} (b2-r5 M4). Stacks stay (shared across runs). Returns what was
     *  removed, {skippedLive:true}, or null if the id is unknown. */
    public synchronized Map<String, Object> removeRun(String execId) {
        Map<String, Object> r = removeOneNoPersist(execId);
        if (r == null || Boolean.TRUE.equals(r.get("skippedLive"))) return r;
        persistRuns();
        persistSeq();
        parkLog(execId, r);
        return r;
    }

    /** batch delete under ONE lock and ONE persist, then the renames (b2-r5 L2: the per-run
     *  persist rewrote the whole index once per id — O(n²) under the lock, starving recordHit on
     *  the frida read thread). Returns a result per id in order: the removed row, {skippedLive},
     *  or null if unknown. */
    public synchronized List<Map<String, Object>> removeRuns(java.util.Collection<String> execIds) {
        List<Map<String, Object>> results = new ArrayList<>();
        List<Map<String, Object>> parkable = new ArrayList<>();
        for (String id : execIds) {
            Map<String, Object> r = removeOneNoPersist(id);
            results.add(r);
            if (r != null && !Boolean.TRUE.equals(r.get("skippedLive"))) parkable.add(r);
        }
        if (!parkable.isEmpty()) { persistRuns(); persistSeq(); }
        for (Map<String, Object> r : parkable) parkLog(String.valueOf(r.get("execId")), r);
        return results;
    }

    /** the map mutation of one delete WITHOUT persisting or parking: the live guard (M4), the
     *  bp-count rollback (L3) and the removed-row preservation (M1). */
    private Map<String, Object> removeOneNoPersist(String execId) {
        Map<String, Object> ex = runs.get(execId);
        if (ex == null) return null;
        if (ex.get("endTs") == null) {                       // M4: never delete a live/unclosed run
            Map<String, Object> live = new LinkedHashMap<>();
            live.put("execId", execId);
            live.put("skippedLive", true);
            return live;
        }
        subtractBpHits(ex, execId);                          // L3: give the run's hits back (from its own tally)
        Map<String, Object> row = new LinkedHashMap<>(ex);   // M1: preserve the full index row (with coverage)
        Set<String> cov = coverage.get(execId);
        if (cov != null && !cov.isEmpty()) row.put("coverage", new ArrayList<>(cov));
        row.put("deletedTs", System.currentTimeMillis());
        appendJsonl("removed.jsonl", row);
        runs.remove(execId);
        coverage.remove(execId);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("execId", execId);
        out.put("hitCount", ex.get("hitCount"));
        return out;
    }

    /** subtract a deleted run's per-bp hits from the live bp counters (b2-r5 L3) — the counter is
     *  otherwise inflated by a deleted run's hits, in the BREAKPOINTS column and the delete-confirm.
     *  Reads the run row's own bpHits tally (UNCAPPED, no log read under the lock — b2-r6 L1/L2);
     *  falls back to the capped log for legacy rows recorded before that tally existed. Clamps at 0
     *  (a restart recomputes bp.hits from the capped logs, so a high-hit row can exceed the live
     *  counter). */
    @SuppressWarnings("unchecked")
    private void subtractBpHits(Map<String, Object> ex, String execId) {
        Map<String, Long> per = new LinkedHashMap<>();
        Object bh = ex.get("bpHits");
        if (bh instanceof Map<?, ?> m) {
            for (Map.Entry<?, ?> e : m.entrySet())
                per.merge(String.valueOf(e.getKey()),
                        e.getValue() instanceof Number ? ((Number) e.getValue()).longValue() : 0L, Long::sum);
        } else {
            for (Map<String, Object> h : readJsonl(execId + ".jsonl")) {   // legacy rows: best-effort from the log
                Object bpId = h.get("bpId");
                if (bpId != null) per.merge(String.valueOf(bpId), 1L, Long::sum);
            }
        }
        for (Map.Entry<String, Long> e : per.entrySet()) {
            Map<String, Object> bp = bps.get(e.getKey());
            if (bp == null) continue;
            Object hv = bp.get("hits");
            long cur = hv instanceof Number ? ((Number) hv).longValue() : 0L;
            bp.put("hits", Math.max(0L, cur - e.getValue()));
        }
    }

    private void parkLog(String execId, Map<String, Object> out) {
        File log = new File(dir, execId + ".jsonl");
        if (!log.isFile()) return;
        String ts = java.time.Instant.now().toString().replace(':', '-');
        File park = new File(dir, execId + ".jsonl.deleted-" + ts);
        if (log.renameTo(park)) out.put("hitLogParked", park.getName());
        else {
            dev.workbench.registry.SidecarIO.writeFailures.incrementAndGet();
            System.err.println("[runs] WARNING: could not park " + log + " — the deleted run's hit log stays in place");
            out.put("hitLogParked", null);
        }
    }

    /** Cleanup evidence is independent of whether a partial hit log remains usable. */
    public synchronized void lifecycleResult(String execId, Map<String, Object> sessionCleanup, Map<String, Object> deviceCleanup) {
        Map<String, Object> run = runs.get(execId);
        if (run == null) return;
        run.put("sessionCleanup", sessionCleanup);
        run.put("deviceCleanup", deviceCleanup);
        run.put("completion", Boolean.TRUE.equals(sessionCleanup.get("detached")) ? "stopped" : "partial");
        Object device=deviceCleanup.get("cleanup");
        Object deviceState=device instanceof Map<?,?> m ? m.get("cleanup") : device;
        run.put("cleanup", "complete".equals(sessionCleanup.get("cleanup")) && "complete".equals(deviceState) ? "complete" : "unknown");
        persistRuns();
    }

    public synchronized void stopRun(String execId, long ts) {
        Map<String, Object> ex = runs.get(execId);
        if (ex != null && ex.get("endTs") == null) {
            ex.put("endTs", ts);
            persistRuns();
        }
    }

    public synchronized List<Object> runs() {
        List<Object> out = new ArrayList<>();
        for (Map<String, Object> run : runs.values()) {
            Map<String, Object> row = new LinkedHashMap<>(run);
            row.put("coverage", new ArrayList<>(coverage.getOrDefault(String.valueOf(run.get("execId")), new LinkedHashSet<>())));
            out.add(row);
        }
        out.sort((a, b) -> {
            @SuppressWarnings("unchecked")
            Map<String, Object> ma = (Map<String, Object>) a;
            @SuppressWarnings("unchecked")
            Map<String, Object> mb = (Map<String, Object>) b;
            return Long.compare(numAt(mb, "startTs"), numAt(ma, "startTs"));   // fix-1-r7 F7
        });
        return out;
    }

    /** Hand-edited/legacy index.json rows may lack startTs — an unguarded
        (Number) cast NPE'd the whole runs list (fix-1-r7 F7). */
    private static long numAt(Map<String, Object> m, String key) {
        Object v = m.get(key);
        return v instanceof Number ? ((Number) v).longValue() : 0L;
    }

    // ------------------------------------------------------------ hits

    /** Record a hit snapshot under a run. {@code cond} filters (server-side, see
     *  {@link #condMatches}); returns null when the run is closed or the hit is filtered. */
    @SuppressWarnings("unchecked")
    public synchronized Map<String, Object> recordHit(String execId, String bpId, String cond,
            Map<String, Object> snapshot) {
        Map<String, Object> ex = runs.get(execId);
        if (ex == null) return null;
        if (cond != null && !cond.isBlank() && !condMatches(cond, snapshot.get("args"))) return null;

        Object hcv = ex.get("hitCount");
        long n = ((hcv instanceof Number ? ((Number) hcv).longValue() : 0L)) + 1;   // fix-1-r7 F1
        ex.put("hitCount", n);
        Map<String, Object> bp = bps.get(bpId);
        String addr = bp == null ? null : String.valueOf(bp.get("origSig"));
        if (addr != null) coverage.computeIfAbsent(execId, k -> new LinkedHashSet<>()).add(addr);
        if (bp != null) {
            /* same guard as load() (fix-1-r7 F1): getOrDefault returns the STORED
               value when the key is present-but-null — a hand-edited bps.json
               NPE'd here, and the throw rode relay() up into the frida read loop,
               killing it and every pending future */
            Object hv = bp.get("hits");
            bp.put("hits", (hv instanceof Number ? ((Number) hv).longValue() : 0L) + 1);
            /* per-run, per-bp tally (UNCAPPED, b2-r6 L1/L2): a delete gives back exactly these
               counts without re-reading the hit log (which is capped at hitBudget and would be
               read under the store lock, starving recordHit on the frida thread). */
            @SuppressWarnings("unchecked")
            Map<String, Object> bh = ex.get("bpHits") instanceof Map ? (Map<String, Object>) ex.get("bpHits") : new LinkedHashMap<>();
            Object c = bh.get(bpId);
            bh.put(bpId, (c instanceof Number ? ((Number) c).longValue() : 0L) + 1);
            ex.put("bpHits", bh);
        }
        if (n > hitBudget) {
            if (n % 1024 == 0) persistRuns();
            return null;
        }

        Object frames = snapshot.get("stack");
        String stackId = null;
        if (frames instanceof List && !((List<?>) frames).isEmpty()) {
            String h = "s" + frames.hashCode();
            stackId = stackIds.get(h);
            if (stackId == null) {
                stackId = "st" + (++stackSeq);
                stackIds.put(h, stackId);
                stacks.put(stackId, frames);
                {
                Map<String, Object> srow = new LinkedHashMap<>();
                srow.put("stackId", stackId);
                srow.put("frames", frames);
                appendJsonl("stacks.jsonl", srow);
            }
            }
        }

        Map<String, Object> hit = new LinkedHashMap<>();
        hit.put("hitId", "h" + (++hitSeq));
        hit.put("execId", execId);
        hit.put("bpId", bpId);
        hit.put("addr", addr);
        hit.put("ts", snapshot.get("ts"));
        hit.put("thread", snapshot.get("thread"));
        hit.put("args", snapshot.get("args"));
        hit.put("this", snapshot.get("this"));
        hit.put("ret", snapshot.get("ret"));
        hit.put("threw", snapshot.get("threw"));
        if (snapshot.get("exc") != null) hit.put("exc", snapshot.get("exc"));
        hit.put("stackId", stackId);
        appendJsonl(execId + ".jsonl", hit);
        if (n % 16 == 0) persistRuns();
        return hit;
    }

    /**
     * Server-side BP conditions (spec's `--cond` grammar, deliberately tiny):
     * {@code argN != null}, {@code argN == null}, {@code !argN}, {@code argN} (truthy).
     * Evaluated against the agent's decoded args (a null arg dumps as JSON null).
     */
    static boolean condMatches(String cond, Object argsObj) {
        String c = cond.trim();
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("^(!?)arg(\\d+)\\s*(==|!=)?\\s*null$|^(!?)arg(\\d+)$").matcher(c);
        if (!m.matches()) return true;                       // unknown form → don't filter (visible, not silent)
        int idx;
        Boolean expectNull = null;
        boolean negated;
        if (m.group(2) != null) {                            // argN (==|!=) null
            idx = Integer.parseInt(m.group(2));
            expectNull = "==".equals(m.group(3));
            negated = "!".equals(m.group(1));
        } else {                                             // bare argN / !argN
            idx = Integer.parseInt(m.group(5));
            negated = "!".equals(m.group(4));
        }
        Object v = null;
        if (argsObj instanceof List<?> a && idx < a.size()) v = a.get(idx);
        boolean isNull = v == null;
        boolean pass = expectNull != null ? (isNull == expectNull) : !isNull;
        return negated ? !pass : pass;
    }

    /** Recorded hits for a run (optionally method-filtered), newest last, with the
     *  deduped backtrace joined inline ({@code frames}). */
    public synchronized Map<String, Object> hits(String execId, String addr, int offset, int limit) {
        List<Map<String, Object>> all = readJsonl(execId + ".jsonl");
        List<Map<String, Object>> sel = new ArrayList<>();
        for (Map<String, Object> h : all) {
            if (addr != null && !addr.isEmpty() && !addr.equals(h.get("addr"))) continue;
            Object sid = h.get("stackId");
            if (sid != null) h.put("frames", stacks.get(sid));
            sel.add(h);
        }
        int from = Math.max(0, offset), to = Math.min(sel.size(), from + (limit <= 0 ? 200 : limit));
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("execId", execId);
        r.put("total", sel.size());
        r.put("hits", from <= to ? sel.subList(from, to) : new ArrayList<>());
        return r;
    }

    /** The deduped frames for a stackId (null when unknown). */
    @SuppressWarnings("unchecked")
    public synchronized List<Object> stackOf(String stackId) {
        Object f = stacks.get(stackId);
        return f instanceof List ? (List<Object>) f : null;
    }

    // ------------------------------------------------------------ coverage + diff

    public synchronized Map<String, Object> coverage(String execId) {
        List<Object> addrs = new ArrayList<>();
        for (String a : coverage.getOrDefault(execId, new LinkedHashSet<>())) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("addr", a);
            row.put("label", labelOf(a));
            row.put("class", ownerOf(a));
            addrs.add(row);
        }
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("execId", execId);
        Map<String, Object> ex = runs.get(execId);
        r.put("color", ex == null ? 0 : ex.get("color"));
        r.put("count", addrs.size());
        r.put("addrs", addrs);
        return r;
    }

    public synchronized Map<String, Object> diff(String a, String b) {
        Set<String> ca = coverage.getOrDefault(a, new LinkedHashSet<>());
        Set<String> cb = coverage.getOrDefault(b, new LinkedHashSet<>());
        List<Object> onlyA = new ArrayList<>(), onlyB = new ArrayList<>(), both = new ArrayList<>();
        for (String x : ca) (cb.contains(x) ? both : onlyA).add(labelRow(x));
        for (String x : cb) if (!ca.contains(x)) onlyB.add(labelRow(x));
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("a", a);
        r.put("b", b);
        r.put("onlyA", onlyA);
        r.put("onlyB", onlyB);
        r.put("both", both);
        return r;
    }

    private static Map<String, Object> labelRow(String addr) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("addr", addr);
        row.put("label", labelOf(addr));
        return row;
    }

    // ------------------------------------------------------------ labels

    /** "Lcom/foo/Bar;->m(II)V" -> "Bar.m"; a class addr -> "Bar". */
    public static String labelOf(String addr) {
        if (addr.startsWith("native:")) return addr.substring(7);   // "libc.so!read" / "libcrypto.so+0x90a9c"
        String owner = ownerOf(addr);
        int arrow = addr.indexOf("->");
        if (arrow < 0) return owner;
        String m = addr.substring(arrow + 2);
        int cut = m.indexOf('(');
        if (cut < 0) cut = m.indexOf(':');
        if (cut >= 0) m = m.substring(0, cut);
        return owner + "." + m;
    }

    /** Simple class name from any addr: "Lcom/foo/Bar;->m()V" -> "Bar". */
    public static String ownerOf(String addr) {
        if (addr.startsWith("native:")) {              // module is the owner: native:libc.so!read -> libc.so
            String s = addr.substring(7);
            int cut = s.indexOf('!'); if (cut < 0) cut = s.indexOf('+');
            return cut >= 0 ? s.substring(0, cut) : s;
        }
        String c = addr;
        int arrow = c.indexOf("->");
        if (arrow >= 0) c = c.substring(0, arrow);
        if (c.startsWith("L")) c = c.substring(1);
        int semi = c.indexOf(';');
        if (semi >= 0) c = c.substring(0, semi);
        int slash = c.lastIndexOf('/');
        return slash >= 0 ? c.substring(slash + 1) : c;
    }

    // ------------------------------------------------------------ persistence

    private void ensureDir() { if (!dir.exists()) dir.mkdirs(); }

    private void persistBps() {
        List<Object> out = new ArrayList<>();
        for (Map<String, Object> bp : bps.values()) out.add(bp);
        writeJson("bps.json", out);
    }

    private boolean persistRuns() {
        List<Object> list = new ArrayList<>();
        for (Map<String, Object> ex : runs.values()) {
            Map<String, Object> m = new LinkedHashMap<>(ex);
            m.put("coverage", new ArrayList<>(coverage.getOrDefault(String.valueOf(ex.get("execId")), new LinkedHashSet<>())));
            list.add(m);
        }
        return writeJson("index.json", list);
    }

    /** the id high-water mark, persisted so it survives an emptied index (b2-r5 M3). bps.json and
     *  the append-only hit logs can all be deleted while runs remain; this is the one record that
     *  keeps ex<N>/h<N>/st<N> monotonic across a restart after a bulk delete. */
    private boolean persistSeq() {
        if (dir == null) return false;
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("run", runSeq);
        m.put("hit", hitSeq);
        m.put("stack", stackSeq);
        m.put("bp", bpSeq);
        return writeJson("seq.json", m);
    }

    private static int numOf(Object v) { return v instanceof Number ? ((Number) v).intValue() : 0; }

    private static final java.util.regex.Pattern RUN_LOG =
            java.util.regex.Pattern.compile("ex(\\d+)\\.jsonl(?:\\.deleted-.*)?");

    private boolean writeJson(String name, Object value) {
        ensureDir();
        /* shared sidecar discipline (fix-1-r3): the bespoke version raced two
           writers on one fixed "<name>.tmp" and leaked the tmp on failure */
        File target = new File(dir, name);
        if (degradedFiles.contains(name)) {
            /* never overwrite un-parked evidence: a failed park keeps the flag
               and SKIPS the write (fix-1-r4 #5) */
            if (!dev.workbench.registry.SidecarIO.parkHealthyBeforeOverwrite(target, "runs"))
                return false;
            degradedFiles.remove(name);
        }
        return dev.workbench.registry.SidecarIO.writeAtomic(target, Json.write(value), "runs");
    }

    private void appendJsonl(String name, Object obj) {
        ensureDir();
        checkLogName(name);                                  // fix-1-r7 F6
        try (Writer w = new FileWriter(new File(dir, name), StandardCharsets.UTF_8, true)) {
            w.write(Json.write(obj));
            w.write("\n");
        } catch (IOException e) {
            /* loud + counted (fix-1-r6 MED-4): a dropped hit/stack used to be
               invisible — the UI showed a running breakpoint with a frozen hit
               count and no diagnostic anywhere */
            dev.workbench.registry.SidecarIO.writeFailures.incrementAndGet();
            System.err.println("[runs] WARNING: append failed " + new File(dir, name) + ": " + e);
        }
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> readJsonl(String name) {
        List<Map<String, Object>> out = new ArrayList<>();
        checkLogName(name);                                  // fix-1-r7 F6
        File f = new File(dir, name);
        if (!f.isFile()) return out;
        try (BufferedReader r = new BufferedReader(new FileReader(f, StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (line.isBlank()) continue;
                /* a torn LINE (a crash mid-append leaves one — the buffered
                   FileWriter is not line-atomic) must not kill the BOOT:
                   Json.parse's RuntimeException used to propagate out of load()
                   → new RunStore → the server never started (fix-1-r5 #5) */
                Object o;
                try {
                    o = Json.parse(line);
                } catch (RuntimeException parse) {
                    System.err.println("[runs] WARNING: skipping unparseable line in "
                            + f.getName() + ": " + parse);
                    continue;
                }
                if (o instanceof Map) out.add((Map<String, Object>) o);
            }
        } catch (IOException e) {
            /* a mid-file read failure returns the PARTIAL list: seq counters
               undercount, and a reissued st<N>/h<N> then resolves to ANOTHER
               row's data (silently wrong UI). Loud; the append-only logs are
               never rewritten so the FILE is not at overwrite risk — the seq
               undercount is a known residual (fix-1-r5 #5, see HANDOFF). */
            System.err.println("[runs] WARNING: partial read of " + f
                    + " — rows beyond the failure are invisible this boot: " + e);
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private void load() {
        if (dir == null || !dir.isDirectory()) return;
        Object bs = parseFile("bps.json");
        if (bs instanceof List)
            for (Object o : (List<Object>) bs)
                if (o instanceof Map) {
                    Map<String, Object> bp = new LinkedHashMap<>((Map<String, Object>) o);
                    bps.put(String.valueOf(bp.get("id")), bp);
                    bpSeq = Math.max(bpSeq, seqOf(bp.get("id"), "bp"));
                }
        Object idx = parseFile("index.json");
        if (idx instanceof List)
            for (Object o : (List<Object>) idx)
                if (o instanceof Map) {
                    Map<String, Object> ex = new LinkedHashMap<>((Map<String, Object>) o);
                    String execId = String.valueOf(ex.get("execId"));
                    runs.put(execId, ex);
                    runSeq = Math.max(runSeq, seqOf(execId, "ex"));
                    Set<String> cov = new LinkedHashSet<>();
                    Object c = ex.remove("coverage");
                    if (c instanceof List)
                        for (Object a : (List<Object>) c) cov.add(String.valueOf(a));
                    coverage.put(execId, cov);
                }
        /* a run still open in the index cannot be live: frida sessions die with the process
           that owned them, so an endTs==null row is a run the previous server never closed
           (a crash, a kill). Close it at load, say why, persist once (batch 2: the run card
           and the RUNTIME dock read endTs==null as LIVE) */
        int orphaned = 0;
        for (Map<String, Object> ex : runs.values())
            if (ex.get("endTs") == null) {
                ex.put("endTs", System.currentTimeMillis());   // the true end is unknown: now, marked (not startTs — a zero-duration lie, b2-r1 L5)
                ex.put("closedBy", "boot");
                orphaned++;
            }
        if (orphaned > 0 && !degradedFiles.contains("index.json")) {
            System.err.println("[runs] " + orphaned + " run(s) left open by a previous server closed at load (" + dir + ")");
            persistRuns();
        }
        for (Map<String, Object> row : readJsonl("stacks.jsonl")) {
            String sid = String.valueOf(row.get("stackId"));
            stacks.put(sid, row.get("frames"));
            stackSeq = Math.max(stackSeq, seqOf(sid, "st"));
        }
        // hit seq continues past the highest recorded hitId in any run's log
        for (String execId : runs.keySet())
            for (Map<String, Object> h : readJsonl(execId + ".jsonl")) {
                hitSeq = Math.max(hitSeq, seqOf(h.get("hitId"), "h"));
                Object bpId = h.get("bpId");
                Map<String, Object> bp = bpId == null ? null : bps.get(String.valueOf(bpId));
                if (bp != null) {
                    /* defensive (fix-1-r6 HIGH-1): a hand-edited bps.json can
                       hold "hits": null or a string — an unguarded (Number) cast
                       here killed the BOOT before the constructor net existed */
                    Object hv = bp.get("hits");
                    bp.put("hits", (hv instanceof Number ? ((Number) hv).longValue() : 0L) + 1);
                }
            }
        /* seq high-water (b2-r5 M3): seed from the persisted seq.json AND from every
           ex<N>.jsonl(.deleted-…) still on disk, so a reissued run id can never land a
           NEW run on a parked log (a name collision, and a live append into the deleted
           run's log if the park then fails). index.json alone reset the counter to 0 the
           restart after a bulk delete. */
        Object seq = parseFile("seq.json");
        if (seq instanceof Map) {
            Map<String, Object> sm = (Map<String, Object>) seq;
            runSeq = Math.max(runSeq, numOf(sm.get("run")));
            hitSeq = Math.max(hitSeq, numOf(sm.get("hit")));
            stackSeq = Math.max(stackSeq, numOf(sm.get("stack")));
            bpSeq = Math.max(bpSeq, numOf(sm.get("bp")));
        }
        File[] present = dir.listFiles();
        if (present != null)
            for (File lf : present) {
                java.util.regex.Matcher rm = RUN_LOG.matcher(lf.getName());
                if (rm.matches()) {
                    try { runSeq = Math.max(runSeq, Integer.parseInt(rm.group(1))); }
                    catch (NumberFormatException ignore) { /* guarded by the pattern */ }
                }
            }
        persistSeq();   // write back the computed high-water (first boot after upgrade has no seq.json)
    }

    private Object parseFile(String name) {
        File f = new File(dir, name);
        if (!f.isFile()) return null;
        try {
            return Json.parse(Files.readString(f.toPath()));
        } catch (java.io.IOException e) {
            /* transient read hiccup ≠ corruption: routed by permanence — a
               charset-parked file is already aside; a transient one marks the
               file degraded so the next writeJson parks the healthy original
               before overwriting (fix-1-r3 — the old silent-null also reset
               bpSeq/runSeq, reissuing ids onto live references) */
            if (!dev.workbench.registry.SidecarIO.readFailedOrPark(f, "runs", e))
                degradedFiles.add(name);
        } catch (Exception e) {
            /* torn bps.json/index.json is evidence, not "absent"; a FAILED park
               leaves it in place — mark degraded so the next write retries the
               park before overwriting (fix-1-r5 #2) */
            if (!dev.workbench.registry.SidecarIO.parkCorrupt(f, "runs", e))
                degradedFiles.add(name);
        }
        return null;
    }

    private static void checkLogName(String name) {
        /* the jsonl names are path segments — a hand-edited index.json execId
           (or a client-supplied run id that slipped a caller) must not resolve
           outside the runs dir (fix-1-r7 F6) */
        if (name == null || name.indexOf('/') >= 0 || name.indexOf('\\') >= 0
                || name.contains(".."))
            throw new IllegalArgumentException("bad log file name: " + name);
    }

    private static int seqOf(Object id, String prefix) {
        if (id == null) return 0;
        String s = String.valueOf(id);
        if (!s.startsWith(prefix)) return 0;
        try { return Integer.parseInt(s.substring(prefix.length())); }
        catch (NumberFormatException e) { return 0; }
    }

    private static Map<String, Object> one(String k, Object v) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put(k, v);
        return m;
    }
}
