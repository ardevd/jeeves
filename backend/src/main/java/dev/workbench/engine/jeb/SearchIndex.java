package dev.workbench.engine.jeb;

import dev.workbench.registry.Json;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Background decompiled-text index for one JEB target (VIEWS_SPEC §3), with a
 * persisted per-class gz corpus on disk (ROADMAP major item):
 *
 * - Every indexed class's text is written gzipped to
 *   {@code <artifact>.workbench/search-index/classes/<encoded-addr>.gz} as it is
 *   built, plus a header.json {version, builtAtMs, classes:{addr:fqcn}}.
 *   Filenames are CASE-ESCAPED (encodeAddr): APFS/Windows filesystems are
 *   case-insensitive and obfuscated targets DO contain LTt;/LtT;/Ltt;
 *   collisions (47 classes in GMS) — raw addrs would silently overwrite.
 * - On open, tryWarmLoad() validates the header (engine version) and adopts the
 *   persisted corpus with ZERO decompilation; the in-memory tier starts COLD
 *   and hydrates on the first search (12s measured at GMS scale, instant for
 *   small targets — OFF the monitor, merged with dedupe).
 * - Tracked edits (rename/comment) delete the class's gz and enqueue a
 *   re-index; pending.json records the queue so a crash between edit and
 *   re-index heals on the next open.
 * - Builder supersede safety: a full rebuild can interrupt a builder stuck in
 *   a long decompile (82s monsters don't honor Thread.interrupt); the
 *   generation counter gates the old thread's corpus writes, and the corpus
 *   dir is only wiped when the old builder is confirmed dead.
 *
 * Threading (the old P1-12 lesson — indexing must never block interactive ops):
 * a single daemon thread at MIN priority walks the target's classes; for each
 * class it takes the engine lock ONLY for the decompile burst (a few ms per
 * class), then processes/stores the text OFF the lock. Interactive decompile
 * requests interleave between classes — worst-case wait is one class decompile.
 * The snapshot lives in a CopyOnWriteArrayList, so search queries run fully
 * off the engine lock against the classes indexed so far.
 *
 * Cancellable via op.cancel (ticket "search-index" / "search-index:<target>");
 * progress is reported through target.info / search.index.
 */
final class SearchIndex {

    /** WORKBENCH_PROF=1: per-class decompile cost + corpus size logging. */
    private static final boolean PROF = System.getenv("WORKBENCH_PROF") != null;

    /** One indexed class: addr + display name + decompiled lines (snapshot). */
    record Entry(String addr, String fqcn, String[] lines) {}

    interface Host {
        List<String[]> classSnapshot();                       // [addr, fqcn] per class
        String decompileText(String classAddr);               // engine-locked decompile
        String classLabel(String classAddr);                  // display name for one class
        void evictDecompiled(String classAddr);               // engine-locked AST product eviction
        Map<String, Object> indexSourceIdentity();             // exact executable bytes + analysis generation
        long jdb2Mtime();                                     // mtime of the jdb2 as WE last wrote it (-1: none)
    }

    /** Decompile slower than this pins the class's products (monsters like the
     *  82s GMS class never get evicted — re-paying their cost is the real risk);
     *  everything cheaper is evicted right after indexing (decompiled products
     *  are the bulk-churn tier; the dex model floor is untouchable). */
    private static final long MONSTER_PIN_MS = 5_000;

    /** Corpus format marker — bumped on any on-disk layout change. v2's escape
     *  scheme passed non-ASCII through (Kelvin sign L\u212A; folded to LK;);
     *  v3 fixed-arity but used Character.isUpperCase (still caught non-ASCII
     *  Lu chars); v4 restricts the uppercase branch to ASCII A-Z. A corpus
     *  without a matching format is never warm-loaded: it would hydrate zero
     *  entries and report a healthy EMPTY index. Absent → rebuild. */
    private static final int CORPUS_FORMAT = 4;

    private final Host host;
    private final String ticket;
    private final File dir;                                   // search-index dir, or null (no persistence)
    private final String engineVersion;
    private final List<Entry> entries = new CopyOnWriteArrayList<>();
    /** Class addrs invalidated by edits (rename/comment), awaiting re-index.
     *  Drained by the builder thread itself at the end of a full build, or by a
     *  lightweight drain pass when no build is running — no third thread ever
     *  exists, and a rename burst can never start a concurrent second pass. */
    private final Queue<String> reindexQueue = new ConcurrentLinkedQueue<>();
    /** Addrs whose gz was deleted by an edit but not yet re-indexed (crash window). */
    private final Set<String> pending = new LinkedHashSet<>();
    /** The corpus's authoritative addr → fqcn map (header content): the build
     *  snapshot at build start, updated by every invalidate(), adopted from the
     *  header on warm load. Header writes always come from THIS, so a mid-build
     *  rename can't orphan a class behind its stale addr. */
    private volatile Map<String, String> live = new LinkedHashMap<>();
    private volatile String state = "idle";                   // idle|building|built|cancelled|failed
    private volatile int built, total;
    private volatile long startedAt, finishedAt;
    private volatile long builtAtMs;
    private volatile boolean persisted, warm = true;          // warm=false: corpus on disk only
    private volatile boolean adoptedFromDisk;                 // this corpus came from tryWarmLoad
    private volatile long corpusBuiltAtMs;                    // ORIGINAL build stamp (never advanced by drains)
    private volatile Map<String, Object> sourceIdentity = Map.of();
    private volatile Map<String, Object> builderRuntime = Map.of();
    private volatile String error;
    private volatile boolean cancel;
    private volatile int generation;                          // supersede gate for stuck builders
    private volatile Thread thread;   // cancel() reads it with no lock (review L3)
    /* pending.json's load failed transiently (fix-1-r3 #1): the on-disk queue
       may be healthy — the next savePending parks it as evidence before
       writing, instead of silently dropping the reindex queue it never saw */
    private boolean pendingDegraded;

    /** Reject a warm corpus without starting expensive replacement work. */
    public synchronized void rejectWarm(String reason) {
        live = new LinkedHashMap<>();
        state = "stale"; built = total = 0;
        warmRejectReason = reason; adoptedFromDisk = false; persisted = false;
    }

    SearchIndex(Host host, String ticket, File dir, String engineVersion) {
        this.host = host;
        this.ticket = ticket;
        this.dir = dir;
        this.engineVersion = engineVersion;
    }

    String ticket() { return ticket; }

    long builtAtMs() { return builtAtMs; }

    /** Why the last tryWarmLoad() rejected an existing header (null = no header
     *  or accepted). Logged by the caller so a 3h rebuild never starts silently. */
    private volatile String warmRejectReason;

    String warmRejectReason() { return warmRejectReason; }

    // ------------------------------------------------------------ persistence

    /** Case-insensitive-filesystem-safe gz filename: delegates to CorpusEncoding
     *  (fixed-arity, injective under Unicode folding). Kept as a one-line shim
     *  so the persistence code reads locally. */
    static String encodeAddr(String addr) {
        return CorpusEncoding.encodeAddr(addr);
    }

    private File gzFile(String addr) {
        return new File(dir, "classes/" + encodeAddr(addr) + ".gz");   // addr is path-shaped
    }

    private void writeGz(String addr, String text) {
        if (dir == null || text == null) return;
        try {
            File f = gzFile(addr);
            f.getParentFile().mkdirs();
            try (OutputStream os = new GZIPOutputStream(Files.newOutputStream(f.toPath()))) {
                os.write(text.getBytes(StandardCharsets.UTF_8));
            }
        } catch (IOException e) {
            /* loud + counted (fix-1-r7 F8): a class body silently failing to
               persist left the header claiming it — the next warm load read
               null and the class was silently absent from search */
            dev.workbench.registry.SidecarIO.writeFailures.incrementAndGet();
            System.err.println("workbench: corpus gz write failed for " + addr
                    + " in " + dir + ": " + e);
        }
    }

    private String readGz(String addr) {
        try {
            File f = gzFile(addr);
            if (!f.isFile()) return null;
            try (InputStream is = new GZIPInputStream(Files.newInputStream(f.toPath()))) {
                return new String(is.readAllBytes(), StandardCharsets.UTF_8);
            }
        } catch (IOException e) { return null; }
    }

    private void deleteGz(String addr) {
        if (dir != null && addr != null) gzFile(addr).delete();
    }

    private synchronized void savePending() {
        if (dir == null) return;
        File f = new File(dir, "pending.json");
        if (pendingDegraded) {
            /* never overwrite un-parked evidence: a failed park keeps the flag
               and SKIPS the write (fix-1-r4 #5) */
            if (!dev.workbench.registry.SidecarIO.parkHealthyBeforeOverwrite(f, "pending"))
                return;
            pendingDegraded = false;
        }
        dev.workbench.registry.SidecarIO.writeAtomic(f,
                Json.write(new ArrayList<>(pending)), "pending");
    }

    private List<String> loadPending() {
        File f = new File(dir, "pending.json");
        if (!f.isFile()) return List.of();
        try {
            Object p = Json.parse(Files.readString(f.toPath()));
            if (p instanceof List<?> l) {
                List<String> out = new ArrayList<>();
                for (Object o : l) out.add(String.valueOf(o));
                return out;
            }
            if (!dev.workbench.registry.SidecarIO.parkCorrupt(f, "pending",
                    new IllegalStateException("expected a JSON list")))
                pendingDegraded = true;   /* park FAILED — the file is still in
                    place; the next savePending retries the park before any
                    overwrite (fix-1-r5 #2) */
        } catch (java.io.IOException e) {
            /* transient read hiccup ≠ corruption (fix-1-r2 F2): routed by
               permanence — a charset-parked file is already aside; a transient
               one marks the queue degraded so the next savePending parks the
               healthy original before overwriting it (fix-1-r3 #1) */
            if (!dev.workbench.registry.SidecarIO.readFailedOrPark(f, "pending", e))
                pendingDegraded = true;
        } catch (Exception e) {
            /* loud + park: a torn pending.json silently DROPS the reindex queue —
               renamed classes would never be re-indexed (fix-1 review F3). A
               FAILED park leaves the file in place — mark degraded so the next
               savePending retries the park before overwriting (fix-1-r5 #2) */
            if (!dev.workbench.registry.SidecarIO.parkCorrupt(f, "pending", e))
                pendingDegraded = true;
        }
        return List.of();
    }

    /**
     * Adopt the persisted corpus if it is valid for this engine version —
     * zero decompilation. Returns the corpus build time (ms epoch), or -1 when
     * no usable corpus exists (caller runs a full build via start()).
     * The memory tier starts COLD (hydrated on first search); pending.json
     * classes (crash window) are re-queued for re-index immediately.
     */
    synchronized long tryWarmLoad() {
        if (dir == null) return -1;
        warmRejectReason = null;   // no stale reason from an earlier attempt (fix-1-r7 F5)
        File hf = new File(dir, "header.json");
        if (!hf.isFile()) return -1;
        /* header.json gets the same discipline as every sidecar (fix-1-r6
           MED-1): a torn header used to cost a SILENT full corpus rebuild —
           catch-all -1, no stderr line, and warmRejectReason left null so even
           the engine's "why rebuilding" log was suppressed. (Parked in-dir: the
           header is a derivable cache, and the rebuild may wipe the dir — the
           stderr line + warmRejectReason are the evidence that matters.) */
        final Map<String, Object> h;
        try {
            Object parsed = Json.parse(Files.readString(hf.toPath()));
            if (!(parsed instanceof Map)) {
                warmRejectReason = "header.json is not a JSON object";
                dev.workbench.registry.SidecarIO.parkCorrupt(hf, "index header",
                        new IllegalStateException("expected a JSON object"));
                return -1;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> hm = (Map<String, Object>) parsed;
            h = hm;
        } catch (java.io.IOException e) {
            dev.workbench.registry.SidecarIO.readFailedOrPark(hf, "index header", e);
            warmRejectReason = "header read failed: " + e;
            return -1;
        } catch (Exception e) {
            dev.workbench.registry.SidecarIO.parkCorrupt(hf, "index header", e);
            warmRejectReason = "header unparsable (parked): " + e;
            return -1;
        }
        try {
            if (!engineVersion.equals(String.valueOf(h.get("version")))) {
                warmRejectReason = "engine version " + h.get("version") + " != " + engineVersion;
                return -1;
            }
            if (!(h.get("format") instanceof Number n) || n.intValue() != CORPUS_FORMAT) {
                warmRejectReason = "corpus format " + h.get("format") + " != " + CORPUS_FORMAT;
                return -1;                                    // unknown layout → full rebuild
            }
            Map<String, Object> expectedIdentity = host.indexSourceIdentity();
            if (!Boolean.TRUE.equals(expectedIdentity.get("measured")) ||
                    !(h.get("sourceIdentity") instanceof Map<?, ?> recordedIdentity) ||
                    !Json.write(new java.util.TreeMap<>(expectedIdentity)).equals(Json.write(new java.util.TreeMap<>(recordedIdentity)))) {
                warmRejectReason = "index executable/analysis identity missing or different; corpus not adopted";
                return -1;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> classes = (Map<String, Object>) h.get("classes");
            if (classes == null || classes.isEmpty()) {
                /* a header that parses but lost its classes map is a torn-header
                   symptom — say so (fix-1-r7 F5: this -1 was silent) */
                warmRejectReason = "header has no classes map (torn header?)";
                return -1;
            }
            long built = ((Number) h.get("builtAtMs")).longValue();
            if (built <= 0) {
                warmRejectReason = "header builtAtMs " + h.get("builtAtMs")
                        + " — never adopt a zero-time header";
                return -1;
            }
            /* load the crash-window queue BEFORE mutating any adoption state:
               if its read failed TRANSIENTLY, adopting is a permanent silent
               hole — the pending classes' gz was deleted at invalidate and the
               header prunes them, so they would vanish from search coverage for
               the life of the corpus. Degrade to a full rebuild (fix-1-r4 #9). */
            List<String> pend = loadPending();
            if (pendingDegraded) {
                /* the rebuild this -1 triggers WIPES the whole corpus dir
                   (start() → mayWipe → renameTo + deleteRecursively) — park the
                   queue OUTSIDE it first, or the evidence dies with the wipe
                   (fix-1-r5 #1: the r4 comment claimed the rebuild's first
                   savePending would park it — by then the file is gone, and
                   parkHealthyBeforeOverwrite no-ops on a missing file). The flag
                   clears ONLY on a successful park or a vanished file — a failed
                   park with a bail-before-wipe would otherwise leave the queue
                   in place and unprotected (fix-1-r6 LOW-1). */
                File pj = new File(dir, "pending.json");
                if (!pj.isFile()) {
                    pendingDegraded = false;
                } else {
                    File dst = new File(dir.getParentFile(),
                            "pending.json.corrupt-degraded-" + System.currentTimeMillis());
                    try {
                        Files.move(pj.toPath(), dst.toPath());
                        System.err.println("pending queue parked outside the wiped corpus dir: " + dst);
                        pendingDegraded = false;
                    } catch (Exception moveErr) {
                        dev.workbench.registry.SidecarIO.parkFailures.incrementAndGet();
                        System.err.println("pending park-before-rebuild FAILED (the wipe takes it): "
                                + pj + " — " + moveErr);
                    }
                }
                warmRejectReason = "pending.json read failed transiently — adoption"
                        + " would drop queued reindexes, rebuilding";
                return -1;
            }
            Map<String, String> map = new LinkedHashMap<>();
            for (Map.Entry<String, Object> e : classes.entrySet())
                map.put(e.getKey(), String.valueOf(e.getValue()));
            live = map;
            sourceIdentity = host.indexSourceIdentity();
            if (h.get("builderRuntime") instanceof Map<?, ?> previous) {
                Map<String, Object> copy = new LinkedHashMap<>();
                previous.forEach((k,v) -> copy.put(String.valueOf(k), v));
                builderRuntime = copy;
            }
            builtAtMs = built;
            this.built = total = map.size();
            startedAt = finishedAt = builtAtMs;
            state = "built";
            persisted = true;
            adoptedFromDisk = true;
            corpusBuiltAtMs = builtAtMs;                      // original stamp survives drains
            warm = false;                                     // hydrate on first search
            for (String a : pend) {
                pending.add(a);
                reindexQueue.offer(a);
            }
            ensureDrainer();
            return builtAtMs;
        } catch (Exception e) {
            /* no silent rebuilds (fix-1-r6 MED-1): a catch-all -1 with a null
               warmRejectReason suppressed even the engine's "why rebuilding"
               log */
            warmRejectReason = "header classes unreadable: " + e;
            return -1;
        }
    }

    private void writeHeader() {
        if (dir == null) return;
        Map<String, Object> snapshot;
        synchronized (this) {
            snapshot = new LinkedHashMap<>(live);         // CME guard: copy under the monitor
            /* pending-excluded claim (findings 2+4 r38): pending classes
               had their gz deleted at invalidate and were NOT re-written
               when this header commits — prune them; pending.json
               re-queues them next open and the drain re-adds them to
               live (finding 2 r39's heal path). NOT fully gz-honest
               (finding 6 r38): null-text classes are still claimed
               without gz (pre-existing), and invalidate() deletes the
               gz before taking the monitor — both residual windows stay */
            for (String p : pending) snapshot.remove(p);
        }
        Map<String, Object> h = new LinkedHashMap<>();
        h.put("version", engineVersion);
        h.put("sourceIdentity", sourceIdentity);
        h.put("builderRuntime", builderRuntime);
        h.put("format", CORPUS_FORMAT);
        h.put("builtAtMs", finishedAt);
        h.put("classes", snapshot);                       // post-edit state, not the snapshot
        /* atomic + loud (fix-1 review F3): header.json is THE index-freshness
           record — a torn or silently-skipped write misrepresents staleness.
           The witness advances ONLY when the header landed (fix-1-r2 review F1:
           stamping it unconditionally inverted fail-safe to fail-unsafe — the
           next open would adopt the PREVIOUS header as current after a failed
           commit, exactly the warm-adopt trap the r38 comment above documents) */
        if (dev.workbench.registry.SidecarIO.writeAtomic(new File(dir, "header.json"), Json.write(h), "index header"))
            recordJdb2Mtime(host.jdb2Mtime());            // baseline for foreign-edit detection
    }

    /** The jdb2 mtime as last written by US (build completion / save / close-save)
     *  — the exact foreign-edit witness: any writer other than this process
     *  leaves jdb2.lastModified() != the recorded value. Max-merged and re-read
     *  at call sites so a concurrent save can't be clobbered by an older capture.
     *  Returns false when the witness could NOT be written — a silent failure
     *  here declares a foreign edit on the next open and rebuilds the full
     *  index for nothing, so the caller must be able to say so (round-14 F6). */
    boolean recordJdb2Mtime(long mtime) {
        if (dir == null || mtime <= 0) return true;
        try {
            dir.mkdirs();
            Files.writeString(new File(dir, "jdb2-mtime").toPath(),
                    String.valueOf(Math.max(recordedJdb2Mtime(), mtime)));
            return true;
        } catch (Exception e) {
            System.err.println("workbench: jdb2-mtime witness write failed in " + dir + ": " + e
                    + " — the next open will see a phantom foreign edit and re-index");
            return false;
        }
    }

    long recordedJdb2Mtime() {
        try {
            File f = new File(dir, "jdb2-mtime");
            if (f.isFile()) return Long.parseLong(Files.readString(f.toPath()).trim());
        } catch (Exception e) {
            /* the last silent sidecar read (fix-1-r7 F8): a witness read failure
               means the next open sees a phantom foreign edit and re-indexes —
               that must be diagnosable */
            System.err.println("workbench: jdb2-mtime witness read failed in " + dir + ": " + e);
        }
        return -1;
    }

    /** Cold → warm: hydrate the in-memory tier from the gz corpus. Single-flight
     *  (concurrent searches wait for the one reader, not N× the read + transient);
     *  disk reads run OFF the monitor so invalidate()/rename() never block; the
     *  merge dedupes against entries the drainer re-added while we read and
     *  skips classes mid-re-index (pending) — absent beats silently stale. */
    private final Object hydLock = new Object();
    private boolean hydrating;

    private void ensureWarm() {
        if (warm || !persisted || dir == null) return;
        synchronized (hydLock) {
            while (!warm && hydrating) {
                try { hydLock.wait(); }
                catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
            if (warm) return;
            hydrating = true;
        }
        try {
            Map<String, String> snapshot;
            synchronized (this) {
                snapshot = new LinkedHashMap<>(live);         // CME guard: copy under the monitor
            }
            List<Entry> loaded = new ArrayList<>(snapshot.size());
            for (Map.Entry<String, String> e : snapshot.entrySet()) {
                String text = readGz(e.getKey());
                if (text != null) loaded.add(new Entry(e.getKey(), e.getValue(), text.split("\n", -1)));
            }
            synchronized (this) {
                if (warm) return;
                Set<String> present = new HashSet<>();
                for (Entry en : entries) present.add(en.addr());
                List<Entry> toAdd = new ArrayList<>(loaded.size());
                for (Entry en : loaded)
                    if (!pending.contains(en.addr()) && present.add(en.addr()))
                        toAdd.add(en);
                /* ONE bulk add: per-element add() on a CopyOnWriteArrayList is a
                   full-array copy per class — O(N²) memcpy at GMS scale
                   (app-review-opus P1-1) */
                entries.addAll(toAdd);
                warm = true;
            }
        } finally {
            synchronized (hydLock) {
                hydrating = false;
                hydLock.notifyAll();
            }
        }
    }

    // ------------------------------------------------------------ build

    /** (Re)start the build. A running build is cancelled first; a builder that
     *  survives the 2s join (stuck in a long decompile) is SUPERSEDED — the
     *  generation gate stops its corpus writes, and the corpus dir is wiped
     *  only when the old builder is confirmed dead. NO-OP after kill() (review
     *  F6 r37: the javadoc hid that the call can be a total no-op). */
    synchronized void start() {
        if (dead) return;   // kill() owns the end state (review finding 1 r36)
        boolean oldAlive = false;
        if (thread != null && thread.isAlive()) {
            cancel = true;
            thread.interrupt();
            try { thread.join(2_000); } catch (InterruptedException ignored) {}
            oldAlive = thread.isAlive();
        }
        /* kill() lands with NO index monitor now (round-38) — re-test after
           the join, before the cancel=false overwrite: a killed index never
           respawns, and cancel stays true for any survivor's loop-top test */
        if (dead) return;
        cancel = false;
        int gen = ++generation;
        entries.clear();
        reindexQueue.clear();
        pending.clear();
        live = new LinkedHashMap<>();
        persisted = false;
        adoptedFromDisk = false;
        corpusBuiltAtMs = 0;
        warm = true;
        builtAtMs = 0;
        /* the corpus wipe moved INTO build(), behind guard 1 (finding 3 r38):
           a start() that never gets its builder running — dead-refused, or a
           reindex raced against close() — used to destroy the persisted
           corpus HERE and rebuild nothing (7/10 race iterations, E_NOTFOUND
           to the caller; "kill() is the backstop" backstopped the header,
           not the wipe) */
        built = 0;
        total = 0;
        error = null;
        startedAt = System.currentTimeMillis();
        finishedAt = 0;
        state = "building";
        final boolean mayWipe = !oldAlive;   // wipe only when the old builder is confirmed dead
        thread = new Thread(() -> build(gen, mayWipe), "search-index");
        thread.setDaemon(true);
        thread.setPriority(Thread.MIN_PRIORITY);
        thread.start();
    }

    private static void deleteRecursively(File f) {
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteRecursively(k);
        f.delete();
    }

    /**
     * Fine-grained invalidation after an edit (rename/comment): drop the class's
     * stale entries (memory AND gz) and re-queue it for background re-index.
     * {@code staleAddr} is the class addr BEFORE the edit (a class rename shifts
     * it), {@code freshAddr} the addr to re-index (same for member edits).
     * Never runs a decompile on the caller's thread. The label lookup takes the
     * engine lock OUTSIDE this class's monitor (lock order: engine → index).
     */
    void invalidate(String staleAddr, String freshAddr) {
        if (staleAddr != null) {
            entries.removeIf(e -> e.addr().equals(staleAddr));
            deleteGz(staleAddr);
        }
        if (freshAddr != null && !freshAddr.equals(staleAddr)) {
            entries.removeIf(e -> e.addr().equals(freshAddr));   // dedupe a re-added entry
            deleteGz(freshAddr);
        }
        if (freshAddr == null) return;
        String label = host.classLabel(freshAddr);             // engine lock, outside index monitor
        synchronized (this) {
            if (staleAddr != null) live.remove(staleAddr);
            live.put(freshAddr, label);
            pending.add(freshAddr);
            savePending();
        }
        reindexQueue.offer(freshAddr);
        ensureDrainer();
    }

    /** Batch form of {@code invalidate(cc, cc)} over a set: ONE entries.removeIf,
     *  one savePending, one drainer wake — the per-class loop paid a full COW
     *  array copy + a whole-file pending.json write per referrer class, so a
     *  rename over a hot method's ref set ran M×O(N) under the engine lock
     *  (fix-4 review X1). Per-element semantics identical to invalidate(cc,cc). */
    void invalidateAll(java.util.Collection<String> addrs) {
        if (addrs == null || addrs.isEmpty()) return;
        java.util.Set<String> set = new java.util.LinkedHashSet<>(addrs);
        set.remove(null);
        if (set.isEmpty()) return;
        entries.removeIf(e -> set.contains(e.addr()));
        for (String a : set) deleteGz(a);
        Map<String, String> labels = new LinkedHashMap<>();
        for (String a : set) labels.put(a, host.classLabel(a));   // engine lock, outside index monitor
        synchronized (this) {
            for (String a : set) { live.remove(a); live.put(a, labels.get(a)); }
            pending.addAll(set);
            savePending();
        }
        reindexQueue.addAll(set);
        ensureDrainer();
    }

    /** Start the drain pass only when no builder is running — a running builder
     *  drains the queue itself at the end of its pass (see build()). The
     *  generation gate stops a superseded drainer from committing stale text. */
    private synchronized void ensureDrainer() {
        if (dead) return;   // kill() owns the end state (review finding 1 r36)
        if (thread != null && thread.isAlive()) return;          // builder will drain
        if (reindexQueue.isEmpty()) return;
        final int gen = generation;
        thread = new Thread(() -> drainQueue(gen, false), "search-index-reindex");   // standalone: header write is its purpose
        thread.setDaemon(true);
        thread.setPriority(Thread.MIN_PRIORITY);
        thread.start();
    }

    /** Re-index queued classes one at a time (MIN priority, off the caller's
     *  thread; the engine lock is taken only inside host.decompileText).
     *  Pending entries clear on ANY drain attempt — a dead addr (e.g. a
     *  pre-rename handle replayed on open) must not leak in pending.json.
     *  {@code midBuild} = called from a builder mid-loop: NEVER write a
     *  header then (finding 2 r38 — see the gate below). */
    private void drainQueue(int gen, boolean midBuild) {
        boolean didWork = false;
        String addr;
        for (;;) {
            while ((addr = reindexQueue.poll()) != null) {
                if (cancel || dead || Thread.currentThread().isInterrupted() || gen != generation) return;
                final String classAddr = addr;
                String text = null;
                try {
                    text = host.decompileText(classAddr);            // engine-locked burst
                } catch (Throwable ignored) {                        // skip undecompilable
                } finally {
                    synchronized (this) {
                        pending.remove(classAddr);
                        savePending();
                    }
                }
                if (text == null) continue;
                String label = host.classLabel(classAddr);
                entries.removeIf(e -> e.addr().equals(classAddr));   // drop any older copy
                entries.add(new Entry(classAddr, label, text.split("\n", -1)));
                writeGz(classAddr, text);
                /* finding 2 r39: rejoin the header CLAIM — the writeHeader
                   pending-exclusion pruned this class and nothing added it
                   back to `live`, so it was written to disk and never
                   claimed again (a permanently shrunken corpus on the F4
                   path — reproduced as Case G2) */
                synchronized (this) { live.put(classAddr, label); }
                didWork = true;
            }
            if (cancel || dead || gen != generation) return;
            /* finding 2 r38: the gate used to re-read the mutable state string
               OUTSIDE the guard — cancel() flipped "building"→"cancelled" in
               the gap and a MID-BUILD drainer wrote a header claiming every
               class in `live` with a fraction of the gz on disk; warm-adopted
               as COMPLETE next open (witness intact, pending empty — nothing
               healed it): search silently covering 12.5% of the target.
               The state string is never read now; the caller says whether a
               build is mid-loop, and the kill tests run INSIDE the monitor. */
            synchronized (this) {
                if (cancel || dead || gen != generation) return;
                if (didWork && pending.isEmpty() && !midBuild) {
                    finishedAt = System.currentTimeMillis();
                    builtAtMs = finishedAt;
                    writeHeader();
                }
            }
            // lost-wakeup guard: ensureDrainer() returns early while this thread is
            // alive, so an invalidate() landing during the header write strands its
            // class unless we re-check AFTER writing (re-review 3, the blocker)
            if (reindexQueue.isEmpty()) return;
        }
    }

    void cancel() {
        cancel = true;
        Thread t = thread;
        if (t != null) t.interrupt();
        if ("building".equals(state)) state = "cancelled";
    }

    /** Set by the engine's close/error paths: the index dies and STAYS dead.
     *  Volatile writes ONLY, no index monitor — the round-37 synchronized
     *  kill() closed a three-party lock cycle (close() holds ENGINE → wants
     *  INDEX; start() holds INDEX joining a builder for a GUARANTEED 2s; the
     *  builder wants ENGINE) and stalled every engine-locked op, N× on
     *  closeAll's Workspace monitor (finding 3 r37). No monitor is needed
     *  because no correctness rests on `cancel` surviving an overwrite: dead
     *  is tested by start()/ensureDrainer() at spawn and by every
     *  builder/drainer loop top, and the COMMIT follows its own protocol —
     *  gen-tested, monitor-held, pending-excluded claim (round-39; finding 6
     *  r38 — the previous wording claimed `cancel` stays true and that every
     *  consumer tests dead at commit time; neither was true). */
    private volatile boolean dead;
    void kill() {
        dead = true;
        cancel();
    }

    /* finding 5 r38: the cancelled STATUS write used to run off-monitor — a
       builder descheduled past start()'s 2s join could stamp "cancelled"
       over the live generation's "building". One guarded writer. */
    private void markCancelled(int gen) {
        synchronized (this) {
            if (gen == generation && "building".equals(state)) state = "cancelled";
        }
    }

    private void build(int gen, boolean mayWipe) {
        List<String[]> classes;
        try {
            classes = host.classSnapshot();
        } catch (Throwable t) {
            state = "failed";
            error = t.toString();
            return;
        }
        /* finding 1 r37, guard 1 of 2: classSnapshot() blocks on the ENGINE
           monitor — a close() holding it kill()s us, then clears dexUnits,
           and the snapshot comes back EMPTY: the loop below runs ZERO
           iterations and every in-loop test is skipped. Test HERE... */
        if (cancel || dead || gen != generation) {
            markCancelled(gen);
            return;
        }
        /* finding 5 r39: total stays BELOW the guard — above it, a superseded
           builder's EMPTY snapshot clobbered the live generation's total
           (Case T: status rendered built=225/0); start() already zeroes it,
           so finding 7's motive was wrong */
        total = classes.size();
        /* finding 3 r38: the corpus wipe lives HERE, behind guard 1 — a
           reindex raced against close() used to wipe in start() and rebuild
           nothing. Finding 1 r39: deleteRecursively on the builder thread
           raced the SUCCESSOR's gz writes (a wipe outlasts start()'s 2s
           join at 50k files, ≈26s at GMS scale) and the successor committed
           a header claiming a corpus that no longer existed (0% coverage
           adopted, permanently). Rename is atomic and instant — nothing is
           destroyed until the new corpus exists; the delete runs off every
           monitor. Still gated on the old builder being dead (mayWipe) */
        if (mayWipe && dir != null && dir.isDirectory()) {
            File oldDir = new File(dir.getPath() + ".old-" + gen);
            if (oldDir.isDirectory()) deleteRecursively(oldDir);   // a crashed delete's leftover
            synchronized (this) { dir.renameTo(oldDir); }
            deleteRecursively(oldDir);
        }
        Map<String, String> snapshot = new LinkedHashMap<>();
        for (String[] c : classes) snapshot.put(c[0], c[1]);
        live = snapshot;
        sourceIdentity = host.indexSourceIdentity();
        builderRuntime = dev.workbench.server.Workspace.runtimeIdentity();
        // WORKBENCH_PROF=1: per-class decompile cost + corpus size (monster hunt)
        long profCount = 0, profSumMs = 0, profMaxMs = 0, profChars = 0;
        String profMaxAddr = "";
        java.util.PriorityQueue<long[]> profTop = new java.util.PriorityQueue<>(
                java.util.Comparator.comparingLong(a -> a[0]));
        for (String[] c : classes) {
            if (cancel || dead || Thread.currentThread().isInterrupted() || gen != generation) {
                markCancelled(gen);
                return;                                   // superseded/killed: touch nothing further
            }
            String text;
            long d0 = System.nanoTime();
            try {
                text = host.decompileText(c[0]);              // engine-locked burst
            } catch (Throwable t) {
                built++;                                      // skip undecompilable classes
                continue;
            }
            long dms = (System.nanoTime() - d0) / 1_000_000;
            // cheap classes: evict the decompiled products right after indexing
            // (bulk churn); monsters stay pinned — re-paying 80s+ is the real risk
            if (dms < MONSTER_PIN_MS) host.evictDecompiled(c[0]);
            /* finding 3 r39: gen ONLY — cancel/dead are deliberately NOT
               tested here. close() parked on ENGINE behind the decompile
               that just finished, so the text in hand needs no engine lock
               to be written; testing kill here discarded the whole
               completed-except-this-class corpus for a one-class-early
               close (Case K). The NEXT loop-top guard still stops a killed
               build with classes remaining; the commit protocol decides */
            if (gen != generation) return;            // superseded mid-decompile: commit nothing
            if (PROF) {
                profCount++;
                profSumMs += dms;
                if (dms > profMaxMs) { profMaxMs = dms; profMaxAddr = c[0]; }
                if (text != null) profChars += text.length();
                profTop.offer(new long[] { dms, profCount });
                if (profTop.size() > 10) profTop.poll();
                if (profCount % 5000 == 0)
                    System.err.println("PROF index n=" + profCount + "/" + total
                            + " avgMs=" + profSumMs / Math.max(1, profCount)
                            + " maxMs=" + profMaxMs + " (" + profMaxAddr + ")"
                            + " corpusMb=" + profChars / 1_048_576
                            + " heapUsedMb=" + (Runtime.getRuntime().totalMemory()
                                    - Runtime.getRuntime().freeMemory()) / 1_048_576);
            }
            if (text != null) {
                entries.add(new Entry(c[0], c[1], text.split("\n", -1)));
                writeGz(c[0], text);
            }
            built++;
            // mid-build drain: at GMS scale the class loop runs for HOURS, so
            // draining only at the end made accepted edits invisible to search
            // for the whole build (GMS MCP exercise). Drain every 500 classes.
            // The header write stays build-end only (gate in drainQueue).
            if (built % 500 == 0 && !reindexQueue.isEmpty()) drainQueue(gen, true);   // mid-build: never a header (finding 2 r38)
        }
        if (PROF)
            System.err.println("PROF index-done n=" + profCount
                    + " avgMs=" + profSumMs / Math.max(1, profCount)
                    + " maxMs=" + profMaxMs + " (" + profMaxAddr + ")"
                    + " corpusMb=" + profChars / 1_048_576
                    + " top10Ms=" + profTop.stream().mapToLong(a -> a[0]).sorted()
                            .mapToObj(String::valueOf)
                            .reduce("", (a, b) -> a.isEmpty() ? b : a + "," + b));
        /* findings 1+4 r38, the commit protocol: SUPERSEDE is the only
           discard — a reindex landing inside the drain must not let this
           builder commit over the live generation (Case J). A KILL is NOT a
           discard: close() blocks on ENGINE behind the in-flight decompile,
           so a kill can only land between completed classes or in the drain
           — the loop COMPLETED, and throwing away a finished corpus at GMS
           scale is hours of decompiles for one late close (da71057's
           behavior, which round-38 regressed). The claim stays gz-honest:
           pending (invalidated, not yet re-written) classes are EXCLUDED in
           writeHeader, and pending.json re-queues them on the next open
           (finding 2's disease was claiming what isn't on disk). One
           monitor-held, gen-tested region, BELOW the drain. */
        if (gen != generation) return;
        drainQueue(gen, true);                                    // mid-build renames; header-less by contract
        synchronized (this) {
            if (gen != generation) return;                        // superseded inside the drain (Case J)
            finishedAt = System.currentTimeMillis();
            state = "built";
            persisted = dir != null;
            builtAtMs = finishedAt;
            corpusBuiltAtMs = finishedAt;
            writeHeader();
        }
    }

    /** The snapshot to query against (hydrates from the gz corpus first when
     *  cold; classes indexed so far while building — may be partial). */
    List<Entry> entries() {
        ensureWarm();
        return entries;
    }

    Map<String, Object> status() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("state", state);
        m.put("sourceIdentity", sourceIdentity);
        m.put("builderRuntime", builderRuntime);
        m.put("engineVersion", engineVersion);
        m.put("partialCoverage", !"built".equals(state) || !reindexQueue.isEmpty());
        m.put("warmRejectReason", warmRejectReason);
        m.put("mode", System.getenv().getOrDefault("WORKBENCH_INDEX_MODE", "automatic"));
        m.put("built", built);
        m.put("total", total);
        m.put("pendingReindex", reindexQueue.size());
        m.put("persisted", persisted);
        m.put("warm", warm);                                  // corpus resident in memory
        m.put("adoptedFromDisk", adoptedFromDisk);            // warm-load adoption (the proof)
        m.put("loadedClasses", entries.size());
        if (corpusBuiltAtMs > 0) m.put("corpusBuiltAtMs", corpusBuiltAtMs);
        if (builtAtMs > 0) m.put("builtAtMs", builtAtMs);
        if (error != null) m.put("error", error);
        m.put("ticket", ticket);
        if (startedAt > 0) {
            long end = finishedAt > 0 ? finishedAt : System.currentTimeMillis();
            m.put("ms", end - startedAt);
            if (finishedAt > 0) m.put("ageMs", System.currentTimeMillis() - finishedAt);
        }
        return m;
    }
}
