package dev.workbench.course;

import dev.workbench.llm.AgentLoop;
import dev.workbench.registry.Json;
import dev.workbench.server.AgentManager;
import dev.workbench.server.Log;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The private, lossless capture (course integration P0.2) and its export to the class store.
 *
 * What the workbench shows of a mission — transcripts capped at 24,000 characters of text and
 * 8,000 of tool JSON, 120-character activity rows — is a projection for people. The record a
 * replay or a training step needs is this journal: under &lt;home&gt;/capture/, one line per event in
 * runs/&lt;mission&gt;/events.jsonl and every payload whole, content-addressed, in blobs/&lt;sha256&gt;
 * (a decompile fetched twice is stored once). The events, in the Day 1 envelope (day1-event-v1):
 *
 *   model.request    the request exactly as it leaves (messages after compaction, tool
 *                    definitions, extra params) — credentials travel as headers, never here
 *   model.response   the answer exactly as received: the parsed body or every SSE segment, the
 *                    status, the provider's request id, the gateway's replay marker
 *   model.failure    an attempt that got no usable answer (timeout, HTTP error, interrupted stream)
 *   tool.result      one tool result exactly as the model consumed it (after the loop's cap — the
 *                    cap itself is recorded with the full size)
 *   annotation       a rename or comment as the workspace recorded it, with the original symbol
 *   mission.terminal how the run ended: completed | cancelled | failed, and why
 *
 * Correlation: run_id = the mission id, conversation_id = run + "-c1", turn_id, request_id = the
 * logical id the client also sent the gateway as X-Request-ID (so the gateway's own records of
 * the same request join on it), attempt. Identity: the room (server-bound; the browser's actor
 * string is never used for it), the target's artifact sha256, the course manifest's digest, the
 * mission's original subject, the workspace snapshot revision at spawn.
 *
 * Export: every event is queued (queue/&lt;event&gt;.json) and posted to the class store
 * (POST /v1/events) until the store acknowledges it durably; the store deduplicates by event id,
 * so a lost acknowledgement is retried with the same id and comes back `duplicate`. Bounded
 * backoff; a store that stays away parks the queue and course.status says how many records are
 * not yet delivered — nothing is dropped silently. A payload larger than the store's record
 * limit travels as its digest with the blob retained here, declared as such.
 *
 * Strict mode (the classroom default): a model request is dispatched only once its model.request
 * record is durable at the store; a store that will not take it stops the mission with the reason.
 * Best-effort: journaled at once, exported as the store allows. Off: the local journal only.
 *
 * Redaction: anything a payload might carry that looks like a credential — a Bearer token, the
 * server's wb_ tokens and WBK- codes, an Authorization header value — is replaced before it is
 * written anywhere.
 */
public final class Capture {

    public static final String SCHEMA = Course.SCHEMA;
    /** Above this a payload is exported as its digest only (the store's record limit is 8 MiB). */
    static final int INLINE_LIMIT = 1 << 20;

    private final File root, blobs, runs, queue, acked;
    private final CourseClient client;
    private final String mode;
    private final boolean evalTaint;
    /** The operator declared the root durable (the central per-owner mount); undeclared, a payload
     *  kept only here is reported as not durable — a room cannot find this out by itself. */
    private final boolean durableDeclared;
    /** Whether the blob directory takes an fsync on this filesystem (checked at the first blob write;
     *  a payload is called durable locally only after file AND directory fsync). */
    private volatile Boolean dirSyncOk;
    /** Before a model request leaves: the reason it may not (the access policy), or null. */
    private volatile java.util.function.Supplier<String> dispatchGate;
    /** The identity of this room's owner for envelopes and the receipt (set once the policy knows it). */
    private volatile java.util.function.Supplier<Map<String, Object>> ownerFields;
    /** This home's capture instance (8 hex, minted once, kept in capture/instance.id): mission ids
     *  count per home (m1, m2 …), so a re-provisioned room, or the class day after the rehearsal,
     *  would post the same event ids to the store under another body — a 409 the strict admission
     *  then refuses. Every exported id carries the instance: run_id, request_id, event_id. */
    private final String instance;
    private final Map<String, Object> target = new ConcurrentHashMap<>();   // the identity block, set per run
    private final AtomicLong journaled = new AtomicLong(), exported = new AtomicLong(), duplicates = new AtomicLong(), failedExports = new AtomicLong(), conflicts = new AtomicLong();
    /** Strict admission: how often and how long one request record is offered to the store before
     *  the dispatch is refused — the store's own protocol says "503: retry with the same id", and a
     *  lost acknowledgement comes back `duplicate` on the retry. Bounded: a store that stays away
     *  refuses the mission with the reason, never an invisible wait. */
    static final int ADMIT_TRIES = 4;
    static final long ADMIT_WINDOW_MS = 12_000;
    private volatile String lastExportError;
    private volatile long lastAckAt;
    private volatile boolean parked;
    private final Thread exporter;
    private volatile boolean closed;
    private final Object wake = new Object();

    /** @param root the capture root itself: &lt;home&gt;/capture, or the central per-owner durable mount
     *              the deployment names (Course.captureRoot) */
    public Capture(File root, CourseClient client, String mode, boolean evalTaint, boolean durableDeclared) throws IOException {
        this.root = root;
        this.durableDeclared = durableDeclared;
        this.blobs = new File(root, "blobs");
        this.runs = new File(root, "runs");
        this.queue = new File(root, "queue");
        this.acked = new File(root, "acked");
        for (File d : List.of(blobs, runs, queue, acked)) Files.createDirectories(d.toPath());
        this.client = client;
        this.mode = mode;
        this.evalTaint = evalTaint;
        syncDir(blobs);   // does this filesystem take a directory fsync? known from boot, not from the first blob
        File inst = new File(root, "instance.id");
        String id = inst.isFile() ? Files.readString(inst.toPath()).trim() : "";
        if (!id.matches("[0-9a-f]{8}")) {
            id = String.format("%08x", new java.security.SecureRandom().nextInt());
            Files.writeString(inst.toPath(), id + "\n");
        }
        this.instance = id;
        this.exporter = new Thread(this::exportLoop, "course-capture-export");
        this.exporter.setDaemon(true);
        if (!"off".equals(mode)) {
            recoverJournal();
            this.exporter.start();   // restart recovery: whatever queue/ holds goes first
        }
    }

    public String mode() { return mode; }
    public File root() { return root; }
    public String instance() { return instance; }
    public void dispatchGate(java.util.function.Supplier<String> gate) { this.dispatchGate = gate; }
    public void ownerFields(java.util.function.Supplier<Map<String, Object>> f) { this.ownerFields = f; }
    /** The exported run id of a local mission id: instance-qualified. */
    public String runIdOf(String missionId) { return instance + "-" + missionId; }

    /** The runs this home has journaled (local ids: missions, human-&lt;target&gt;, human-share). */
    public List<String> runs() {
        List<String> out = new ArrayList<>();
        String[] names = runs.list((d, n) -> new File(new File(d, n), "events.jsonl").isFile());
        if (names != null) { java.util.Arrays.sort(names); out.addAll(java.util.Arrays.asList(names)); }
        return out;
    }

    // ------------------------------------------------------------ the journal

    /** sha256 of bytes. */
    static String sha256(byte[] b) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            StringBuilder sb = new StringBuilder();
            for (byte x : md.digest(b)) sb.append(String.format("%02x", x));
            return sb.toString();
        } catch (Exception e) { throw new IllegalStateException(e); }
    }

    /* what a payload might carry that is a credential: an Authorization value (Bearer, Basic), the
       class server's wb_ tokens and WBK- codes, provider keys (sk-…), a key in a query string, and
       the value of a header-like JSON key — also when the JSON is itself escaped inside a string */
    private static final java.util.regex.Pattern SECRETS = java.util.regex.Pattern.compile(
            "(?i)(Bearer\\s+)[A-Za-z0-9._~+/=-]{8,}"
            + "|(Basic\\s+)[A-Za-z0-9+/=]{8,}"
            + "|\\bwb_[A-Za-z0-9_-]{43}\\b"
            + "|\\bWBK-[A-Z0-9]{5}(?:[- ][A-Z0-9]{5}){5}\\b"
            + "|\\bsk-[A-Za-z0-9_-]{16,}\\b"
            + "|([?&](?:key|api_key|apikey|token|access_token|secret|password)=)[^&\\s\"']+"
            + "|(\\\\{0,2}\"(?:authorization|api[_-]?key|x-api-key|cookie|set-cookie|secret|token|password)\\\\{0,2}\"\\s*:\\s*\\\\{0,2}\")((?:[^\"\\\\]|\\\\.)*?)(\\\\{0,2}\")");

    /** Credentials out, whatever the payload. The shape stays valid JSON. */
    static String redact(String s) {
        if (s == null) return null;
        java.util.regex.Matcher m = SECRETS.matcher(s);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String rep;
            if (m.group(1) != null) rep = m.group(1) + "[REDACTED]";
            else if (m.group(2) != null) rep = m.group(2) + "[REDACTED]";
            else if (m.group(3) != null) rep = m.group(3) + "[REDACTED]";
            else if (m.group(4) != null) rep = m.group(4) + "[REDACTED]" + m.group(6);
            else rep = "[REDACTED]";
            m.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(rep));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /** Write a payload as a blob (once per content) and return {sha256, bytes}. The bytes are
     *  fsynced before the rename and the directory after it: a payload the envelope points at
     *  exists on disk when the envelope does, through a crash. */
    private String[] blob(String payload) throws IOException {
        byte[] b = redact(payload).getBytes(StandardCharsets.UTF_8);
        String sha = sha256(b);
        File f = new File(blobs, sha);
        if (!f.isFile()) {
            File tmp = new File(blobs, sha + ".tmp-" + Thread.currentThread().getId());
            try (java.io.FileOutputStream out = new java.io.FileOutputStream(tmp)) { out.write(b); out.getFD().sync(); }
            try { Files.move(tmp.toPath(), f.toPath(), java.nio.file.StandardCopyOption.ATOMIC_MOVE); }
            catch (IOException e) { if (!f.isFile()) throw e; tmp.delete(); }
            syncDir(blobs);
        }
        return new String[] { sha, String.valueOf(b.length) };
    }

    /** fsync a directory (the rename that put a file in it is durable only then). Whether the
     *  filesystem takes it is remembered: a root that cannot is not called durable. */
    private void syncDir(File dir) {
        try (java.nio.channels.FileChannel ch = java.nio.channels.FileChannel.open(dir.toPath(), java.nio.file.StandardOpenOption.READ)) {
            ch.force(true);
            if (dirSyncOk == null) dirSyncOk = Boolean.TRUE;
        } catch (Exception e) {
            if (dirSyncOk == null) { dirSyncOk = Boolean.FALSE; Log.warn("course capture", "the capture directory does not take an fsync (" + e.getMessage() + ") — payloads here are not called durable", null); }
        }
    }

    /** The root is durable when the operator declared it so AND its directory fsync works. */
    public boolean homeDurable() { return durableDeclared && Boolean.TRUE.equals(dirSyncOk); }

    /** The journal line for one event, written durably, queued for export. Returns the envelope. */
    public Map<String, Object> record(String runId, Map<String, Object> envelope, String payload) throws IOException {
        String[] blobRef = blob(payload);
        Map<String, Object> ev = new LinkedHashMap<>();
        ev.put("schema", SCHEMA);
        ev.putAll(envelope);
        ev.put("payload_blob_sha256", blobRef[0]);
        ev.put("payload_bytes", Integer.parseInt(blobRef[1]));
        ev.put("visibility", "private-research");
        ev.put("eval_taint", evalTaint);
        ev.put("ts", java.time.Instant.now().toString());
        File dir = new File(runs, safe(runId));
        boolean fresh = !dir.isDirectory();
        Files.createDirectories(dir.toPath());
        File f = new File(dir, "events.jsonl");
        boolean newFile = !f.isFile();
        try (java.io.FileOutputStream out = new java.io.FileOutputStream(f, true)) {
            out.write((Json.write(ev) + "\n").getBytes(StandardCharsets.UTF_8));
            out.getFD().sync();   // durable before anything depends on it
        }
        if (fresh || newFile) { syncDir(dir); if (fresh) syncDir(runs); }   // a new run directory / journal file: the directory entry too
        journaled.incrementAndGet();
        if (!"off".equals(mode)) {
            String name = safe(String.valueOf(ev.get("event_id"))) + ".json";
            /* a reused id with ANOTHER body is a conflict, never an admission: a mission id minted
               again in this home (project.delete, then a restart), a tool-call id a model repeated
               within a turn. A retried attempt sends the same body and is the same record. */
            File prior = new File(acked, name).isFile() ? new File(acked, name) : new File(queue, name);
            if (prior.isFile()) {
                String priorSha = null;
                try { priorSha = String.valueOf(Json.parseObject(Files.readString(prior.toPath())).get("payload_blob_sha256")); } catch (Exception ignored) {}
                if (priorSha != null && !priorSha.equals(blobRef[0])) {
                    String why = "event id " + ev.get("event_id") + " was already recorded with another payload (" + priorSha.substring(0, 12) + "… before, " + blobRef[0].substring(0, 12) + "… now) — not exported under that id";
                    conflicts.incrementAndGet();
                    Log.error("course capture", why, null);
                    ev.put("conflict", why);
                    if ("strict".equals(mode)) throw new IOException(why);
                    return ev;
                }
            }
            if (!prior.isFile()) enqueue(name, ev);
        }
        return ev;
    }

    /** The queue file, written whole or not at all (tmp + atomic move: a truncated file would sort
     *  first forever and stall every record behind it). */
    private void enqueue(String name, Map<String, Object> ev) throws IOException {
        File q = new File(queue, name), tmp = new File(queue, name + ".tmp");
        Files.writeString(tmp.toPath(), Json.write(ev), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        Files.move(tmp.toPath(), q.toPath(), java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        synchronized (wake) { wake.notifyAll(); }
    }

    /** Boot: every journaled event that is neither acknowledged, queued nor set aside goes back on
     *  the queue — a crash between the journal's fsync and the queue write would otherwise leave a
     *  record that is never exported and never counted. */
    private int recoverJournal() {
        int requeued = 0;
        File[] dirs = runs.listFiles(File::isDirectory);
        if (dirs == null) return 0;
        for (File d : dirs) {
            File f = new File(d, "events.jsonl");
            if (!f.isFile()) continue;
            try {
                for (String line : Files.readAllLines(f.toPath())) {
                    if (line.isBlank()) continue;
                    Map<String, Object> ev;
                    try { ev = Json.parseObject(line); } catch (Exception e) { continue; }
                    if (ev.get("conflict") != null) continue;
                    String name = safe(String.valueOf(ev.get("event_id"))) + ".json";
                    if (new File(acked, name).isFile() || new File(queue, name).isFile() || new File(acked, name + ".refused").isFile() || new File(acked, name + ".unreadable").isFile()) continue;
                    enqueue(name, ev);
                    requeued++;
                }
            } catch (IOException e) { Log.warn("course capture", "journal not scanned at boot: " + f + " — " + e, null); }
        }
        if (requeued > 0) Log.info("course capture", requeued + " journaled record(s) re-queued for export at boot");
        return requeued;
    }

    /** The journal of one run — every envelope, with whether the store has it. */
    public List<Map<String, Object>> events(String runId) {
        List<Map<String, Object>> out = new ArrayList<>();
        File f = new File(new File(runs, safe(runId)), "events.jsonl");
        if (!f.isFile()) return out;
        try {
            for (String line : Files.readAllLines(f.toPath())) {
                if (line.isBlank()) continue;
                Map<String, Object> ev = new LinkedHashMap<>(Json.parseObject(line));
                String id = safe(String.valueOf(ev.get("event_id")));
                ev.put("exported", new File(acked, id + ".json").isFile() ? "acked" : new File(queue, id + ".json").isFile() ? "pending"
                        : new File(acked, id + ".json.refused").isFile() ? "refused (the store holds another body under this id — kept aside for the operator)"
                        : "off".equals(mode) ? "not-exported (capture off)" : "unknown");
                out.add(ev);
            }
        } catch (IOException e) { Log.warn("course capture", "journal unreadable: " + f + " — " + e, null); }
        return out;
    }

    /** A blob's text, whole. */
    public String payload(String sha256) throws IOException {
        File f = new File(blobs, sha256.replaceAll("[^0-9a-f]", ""));
        return f.isFile() ? Files.readString(f.toPath()) : null;
    }

    // ------------------------------------------------------------ export

    private enum Delivery { ACKED, REFUSED, RETRY }
    /** Held around every delivery: the exporter thread and a strict admission reach for the same
     *  queue file at the same moment (record() wakes the exporter), and the loser must find the
     *  acknowledgement, not a vanished file. */
    private final Object deliveryLock = new Object();

    /** One try at the store for a queued event: ACKED (new or duplicate), REFUSED (409 — kept aside)
     *  or RETRY (the store is away). */
    private Delivery deliver(File q) {
        synchronized (deliveryLock) { return deliverLocked(q); }
    }

    private Delivery deliverLocked(File q) {
        if (!q.isFile()) {
            if (new File(acked, q.getName()).isFile()) return Delivery.ACKED;        // the other path delivered it
            if (new File(acked, q.getName() + ".refused").isFile()) return Delivery.REFUSED;
            lastExportError = "queued record vanished: " + q.getName();
            failedExports.incrementAndGet();
            Log.error("course capture", lastExportError, null);
            return Delivery.REFUSED;
        }
        Map<String, Object> ev;
        try { ev = Json.parseObject(Files.readString(q.toPath())); }
        catch (Exception e) {
            /* an unreadable queue file would sort first and stall the queue: set aside, declared */
            lastExportError = "queued record unreadable: " + q.getName() + " — " + e.getMessage();
            failedExports.incrementAndGet();
            try { Files.move(q.toPath(), new File(acked, q.getName() + ".unreadable").toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING); } catch (IOException ignored) {}
            Log.error("course capture", lastExportError, null);
            return Delivery.REFUSED;
        }
        try {
            String id = String.valueOf(ev.get("event_id"));
            Map<String, Object> payload = new LinkedHashMap<>(ev);
            int bytes = ev.get("payload_bytes") instanceof Number n ? n.intValue() : 0;
            if (bytes > 0 && bytes <= INLINE_LIMIT) {
                String body = payload(String.valueOf(ev.get("payload_blob_sha256")));
                if (body != null) { payload.put("payload", body); payload.put("payload_complete", true); }
                else { payload.put("payload_missing", "the blob " + ev.get("payload_blob_sha256") + " is not in the room's capture store — the envelope is exported without it"); payload.put("payload_complete", false); }
            } else if (bytes > INLINE_LIMIT) {
                /* above the inline limit the central event carries the digest and size and says WHERE
                   the bytes are and whether that place is durable — an absent payload is never called
                   complete (A10: capture_home_durable and event_export_durable are separate facts) */
                Map<String, Object> loc = new LinkedHashMap<>();
                loc.put("where", "capture_home"); loc.put("path", new File(blobs, String.valueOf(ev.get("payload_blob_sha256"))).getPath());
                loc.put("sha256", ev.get("payload_blob_sha256")); loc.put("bytes", bytes);
                loc.put("capture_home_durable", homeDurable());
                loc.put("why", "above the store's inline limit (" + INLINE_LIMIT + " bytes)");
                payload.put("payload_location", loc);
                payload.put("payload_complete", false);
                payload.put("payload_retained", "the payload (" + bytes + " bytes) stays in the room's capture blob store under its digest" + (homeDurable() ? " on the declared durable root" : " — on a root NOT declared durable: the only copy"));
            }
            CourseClient.Ack ack = client.storeEvent(id, "jeeves." + ev.get("event_type"), payload, evalTaint);
            if (!ack.durable()) throw new CourseClient.Unavailable("store", "acknowledged without durable:true", true);
            Files.move(q.toPath(), new File(acked, q.getName()).toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            exported.incrementAndGet();
            if (ack.duplicate()) duplicates.incrementAndGet();
            lastAckAt = System.currentTimeMillis();
            lastExportError = null;
            parked = false;
            return Delivery.ACKED;
        } catch (CourseClient.Refused e) {
            if (e.status == 401 || e.status == 403) {
                /* the room's credential is not accepted: a rotation in progress, or the room not
                   enrolled — the record is right, the moment is not; retried, named for the operator */
                lastExportError = "the store refused the room's credential (HTTP " + e.status + ") — retrying; operator: the room's store credential";
                return Delivery.RETRY;
            }
            /* 409: the store holds another body under this id; 400/404/413: it will not take this
               record — a real conflict; kept aside with the store's answer, declared in course.status,
               never retried blindly */
            failedExports.incrementAndGet();
            lastExportError = e.getMessage();
            try { Files.move(q.toPath(), new File(acked, q.getName() + ".refused").toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING); } catch (IOException ignored) {}
            Log.error("course capture", "the store refused " + q.getName() + ": " + e.getMessage(), null);
            return Delivery.REFUSED;
        } catch (CourseClient.Unavailable e) {
            lastExportError = e.getMessage();
            return Delivery.RETRY;
        } catch (Exception e) {
            lastExportError = String.valueOf(e);
            failedExports.incrementAndGet();
            Log.error("course capture", "export of " + q.getName() + " failed: " + e, null);
            return Delivery.RETRY;
        }
    }

    private void exportLoop() {
        long backoff = 1000;
        while (!closed) {
            File[] pending = queue.listFiles((d, n) -> n.endsWith(".json"));
            if (pending == null || pending.length == 0) {
                parked = false;
                synchronized (wake) { try { wake.wait(2000); } catch (InterruptedException e) { return; } }
                continue;
            }
            java.util.Arrays.sort(pending, java.util.Comparator.comparingLong(File::lastModified));
            boolean progressed = false;
            for (File q : pending) {
                if (closed) return;
                if (deliver(q) != Delivery.RETRY) { progressed = true; backoff = 1000; }
                else break;   // the store is away: keep the order, back off
            }
            if (!progressed) {
                parked = true;
                synchronized (wake) { try { wake.wait(backoff); } catch (InterruptedException e) { return; } }
                backoff = Math.min(backoff * 2, 60_000);
            }
        }
    }

    /** Strict admission: deliver this one event now — up to ADMIT_TRIES times within ADMIT_WINDOW_MS
     *  while the store asks for a retry (503, a lost acknowledgement, a connection that dropped) —
     *  or say why not (the caller does not dispatch). An acknowledgement the exporter thread already
     *  holds counts, a `duplicate` on the retry counts; a record kept aside does not. */
    private void admitNow(Map<String, Object> ev) throws Exception {
        String name = safe(String.valueOf(ev.get("event_id"))) + ".json";
        long started = System.currentTimeMillis();
        for (int attempt = 1; ; attempt++) {
            Delivery d;
            synchronized (deliveryLock) {
                if (new File(acked, name).isFile()) return;   // acknowledged already (the exporter was first, or a retried attempt)
                d = deliverLocked(new File(queue, name));
            }
            switch (d) {
                case ACKED -> { return; }
                case REFUSED -> throw new IllegalStateException("the class store refused the request record (" + lastExportError + ") — not dispatched; the record is journaled and kept aside for the operator");
                default -> {
                    long elapsed = System.currentTimeMillis() - started;
                    if (attempt >= ADMIT_TRIES || elapsed >= ADMIT_WINDOW_MS || closed)
                        throw new IllegalStateException("the class store did not take the request record after " + attempt + " tries in " + (elapsed / 1000) + " s (" + lastExportError
                                + ") — not dispatched; the record is journaled and will be exported when the store answers. Run the mission again then");
                    Thread.sleep(Math.min(ADMIT_WINDOW_MS - elapsed, 700L * attempt));   // 0.7 s, 1.4 s, 2.1 s — the store's backlog clears in seconds
                }
            }
        }
    }

    public void close() { closed = true; synchronized (wake) { wake.notifyAll(); } }

    /** What course.status says about the capture. */
    public Map<String, Object> status() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("mode", mode);
        m.put("instance", instance);
        m.put("journaled", journaled.get());
        m.put("exported", exported.get());
        m.put("duplicatesAcked", duplicates.get());
        File[] pending = queue.listFiles((d, n) -> n.endsWith(".json"));
        File[] refused = acked.listFiles((d, n) -> n.endsWith(".refused"));
        m.put("pending", pending == null ? 0 : pending.length);
        m.put("refused", refused == null ? 0 : refused.length);
        File[] unreadable = acked.listFiles((d, n) -> n.endsWith(".unreadable"));
        if (unreadable != null && unreadable.length > 0) m.put("unreadable", unreadable.length);
        if (conflicts.get() > 0) m.put("conflicts", conflicts.get());
        m.put("parked", parked);
        /* two separate facts (A10): is the ROOT durable (declared central mount + directory fsync
           works), and is every journaled EVENT exported durably (nothing pending, nothing parked) */
        m.put("homeDurable", homeDurable());
        m.put("homeDurableWhy", !durableDeclared ? "the capture root is not declared durable (WORKBENCH_COURSE_CAPTURE_DURABLE) — a payload above the inline limit lives here only"
                : dirSyncOk == null ? "declared durable; the directory fsync is checked at the first blob write" : dirSyncOk ? "declared durable, directory fsync works" : "declared durable but the directory does not take an fsync");
        int pendingN = pending == null ? 0 : pending.length;
        m.put("eventExportDurable", !"off".equals(mode) && pendingN == 0 && !parked && (journaled.get() == 0 || lastAckAt > 0));
        m.put("covers", "http missions (requests, answers, tool results, terminal), every rename/comment/undo/redo, finding drafts — NOT cli (claude-code) or scripted missions, which have no wire the room can see");
        if (lastExportError != null) m.put("lastError", lastExportError);
        if (lastAckAt > 0) m.put("lastAckAt", java.time.Instant.ofEpochMilli(lastAckAt).toString());
        m.put("root", root.getPath());
        return m;
    }

    /**
     * The operator's cleanup receipt (jeeves-cleanup-receipt-v1): what this room holds that a
     * reset or a reassignment must not lose — the owner it belongs to, every run's journal with
     * its digest, the blobs, what is still pending export — written beside the capture at
     * shutdown and on demand (course.receipt). The server's cleanup job validates the expected
     * account/generation against it, checks the process is gone, and keeps the paths it names.
     */
    public Map<String, Object> receipt(String why) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("schema", "jeeves-cleanup-receipt-v1");
        m.put("room", Course.room());
        java.util.function.Supplier<Map<String, Object>> of = ownerFields;
        Map<String, Object> owner = of == null ? Map.of() : of.get();
        m.put("account_id", owner.get("account_id")); m.put("workspace_generation", owner.get("workspace_generation")); m.put("course_session_id", owner.get("course_session_id"));
        m.put("instance", instance);
        m.put("pid", ProcessHandle.current().pid());
        m.put("capture_root", root.getPath());
        m.put("capture_home_durable", homeDurable());
        List<Object> runRows = new ArrayList<>();
        long blobBytes = 0; int blobCount = 0;
        for (String run : runs()) {
            File f = new File(new File(runs, run), "events.jsonl");
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("run", run); row.put("exported_run_id", runIdOf(run)); row.put("journal", f.getPath()); row.put("bytes", f.length());
            try { row.put("sha256", sha256(Files.readAllBytes(f.toPath()))); int n = 0; for (String l : Files.readAllLines(f.toPath())) if (!l.isBlank()) n++; row.put("events", n); } catch (IOException e) { row.put("unreadable", e.getMessage()); }
            runRows.add(row);
        }
        File[] bl = blobs.listFiles(File::isFile);
        if (bl != null) for (File b : bl) if (!b.getName().contains(".tmp-")) { blobCount++; blobBytes += b.length(); }
        m.put("runs", runRows);
        m.put("blobs", Map.of("count", blobCount, "bytes", blobBytes, "dir", blobs.getPath()));
        File[] pend = queue.listFiles((d, n) -> n.endsWith(".json"));
        File[] ref = acked.listFiles((d, n) -> n.endsWith(".refused"));
        m.put("pending_export", pend == null ? 0 : pend.length);
        m.put("refused", ref == null ? 0 : ref.length);
        m.put("exported", exported.get()); m.put("journaled", journaled.get());
        m.put("event_export_durable", (pend == null || pend.length == 0) && !parked);
        m.put("why", why);
        m.put("generated_at", java.time.Instant.now().toString());
        return m;
    }

    /** Write the receipt to &lt;root&gt;/receipt.json (whole or not at all, fsynced). Returns the file. */
    public File writeReceipt(String why) throws IOException {
        File f = new File(root, "receipt.json"), tmp = new File(root, "receipt.json.tmp");
        try (java.io.FileOutputStream out = new java.io.FileOutputStream(tmp)) { out.write(Json.write(receipt(why)).getBytes(StandardCharsets.UTF_8)); out.getFD().sync(); }
        Files.move(tmp.toPath(), f.toPath(), java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        syncDir(root);
        return f;
    }

    /** An event id the store accepts: the id's own alphabet ([A-Za-z0-9._:-]) and at most 160 chars —
     *  a model's tool-call id or a history id longer than that is cut and keyed by its digest, so two
     *  long ids never collide (fix-review 2026-10-09). */
    static String eventId(String raw) {
        String s = raw.replaceAll("[^A-Za-z0-9._:-]", "_");
        if (s.length() <= 160) return s;
        return s.substring(0, 120) + ":" + sha256(raw.getBytes(StandardCharsets.UTF_8)).substring(0, 16);
    }

    private static String safe(String s) { return s.replaceAll("[^A-Za-z0-9._:-]", "_"); }

    // ------------------------------------------------------------ a mission's capture

    /** The identity block every event of this mission carries: the room and its owner (stable account,
     *  workspace generation, course session, the policy revision in force), the classroom's activity
     *  identity, the target's bytes, the subject, the snapshot. */
    public record RunIdentity(String room, String actor, String activity, String targetSha256, String manifestSha256,
                              String originalMethod, String snapshotId, String targetName, String provider, String model,
                              Map<String, Object> owner, CourseActivity.Identity activityIdentity) {
        /** The pre-A10 shape (tests, the mock scene): no owner, the label only. */
        public RunIdentity(String room, String actor, String activity, String targetSha256, String manifestSha256,
                           String originalMethod, String snapshotId, String targetName, String provider, String model) {
            this(room, actor, activity, targetSha256, manifestSha256, originalMethod, snapshotId, targetName, provider, model, Map.of(), null);
        }
    }

    /** The owner and activity fields of an envelope, from the identity. */
    static void identityFields(Map<String, Object> e, RunIdentity id) {
        e.put("activity_id", id.activityIdentity() != null && id.activityIdentity().id() != null ? id.activityIdentity().id() : id.activity());
        if (id.activityIdentity() != null) {
            e.put("activity_revision", id.activityIdentity().revision());
            e.put("activity_sha256", id.activityIdentity().sha256());
            e.put("activity_source", id.activityIdentity().source());
            e.put("activity_stale", id.activityIdentity().stale());
        }
        if (id.owner() != null) for (String k : List.of("account_id", "workspace_generation", "course_session_id", "policy_revision")) if (id.owner().get(k) != null) e.put(k, id.owner().get(k));
    }

    /** The AgentLoop.Capture of one mission: envelopes, blobs, strict admission. */
    public AgentLoop.Capture forMission(AgentManager.Mission m, RunIdentity id) {
        return new AgentLoop.Capture() {
            /* the logical request id the client sends the gateway as X-Request-ID — instance-qualified,
               so the gateway's records of two homes' "m1" never share a key */
            @Override public String logicalId(String missionId, int turn, int attempt) { return instance + "-" + missionId + "-t" + turn + "-a" + attempt; }
            private Map<String, Object> envelope(String type, int turn, int attempt, String logicalId, String suffix) {
                Map<String, Object> e = new LinkedHashMap<>();
                e.put("event_id", eventId(instance + ":" + m.id + ":" + (turn > 0 ? "t" + turn + ":" : "") + (attempt > 0 ? "a" + attempt + ":" : "") + type + (suffix == null ? "" : ":" + suffix)));
                e.put("room", id.room());
                e.put("actor", id.actor());
                e.put("run_id", runIdOf(m.id));
                e.put("mission", m.id);
                e.put("conversation_id", runIdOf(m.id) + "-c1");
                if (turn > 0) e.put("turn_id", "t" + turn);
                if (logicalId != null) e.put("request_id", logicalId);
                if (attempt > 0) e.put("attempt", attempt);
                identityFields(e, id);
                e.put("target", id.targetName());
                e.put("target_sha256", id.targetSha256());
                e.put("target_manifest_sha256", id.manifestSha256());
                e.put("original_method", id.originalMethod());
                e.put("snapshot_id", id.snapshotId());
                e.put("provider", id.provider());
                e.put("model_requested", id.model());
                e.put("event_type", type);
                return e;
            }
            @Override public void request(String lid, int turn, int attempt, Map<String, Object> request) throws Exception {
                /* the access policy first: a request never leaves a room whose access is closed, fenced
                   or unconfirmed — recorded as a request that was NOT dispatched, with the reason */
                java.util.function.Supplier<String> gate = dispatchGate;
                String block = gate == null ? null : gate.get();
                if (block != null) {
                    Map<String, Object> env = envelope("model.request", turn, attempt, lid, null);
                    env.put("dispatched", false); env.put("fenced", true); env.put("fence_reason", block);
                    try { record(m.id, env, Json.write(request)); } catch (IOException e) { Log.error("course capture", "fenced request not journaled: " + e, null); }
                    throw new IllegalStateException("fenced: " + block + " — the request was not dispatched");
                }
                if ("off".equals(mode)) { record(m.id, envelope("model.request", turn, attempt, lid, null), Json.write(request)); return; }
                Map<String, Object> ev = record(m.id, envelope("model.request", turn, attempt, lid, null), Json.write(request));
                if ("strict".equals(mode)) admitNow(ev);
            }
            @Override public void response(String lid, int turn, int attempt, Map<String, Object> response, Map<String, Object> raw, long ms) {
                Map<String, Object> env = envelope("model.response", turn, attempt, lid, null);
                env.put("ms", ms);
                if (raw != null) { for (String k : List.of("status", "providerRequestId", "replay", "stream")) if (raw.get(k) != null) env.put(k, raw.get(k)); }
                if (response.get("model") != null) env.put("model_returned", response.get("model"));
                if (response.get("usage") != null) env.put("usage", response.get("usage"));
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("response", response);
                if (raw != null && raw.get("segments") != null) body.put("segments", raw.get("segments"));
                else if (raw != null && raw.get("body") != null) body.put("body", raw.get("body"));
                try { record(m.id, env, Json.write(body)); } catch (IOException e) { Log.error("course capture", "response not journaled: " + e, null); }
            }
            @Override public void failure(String lid, int turn, int attempt, String error, long ms, Map<String, Object> raw) {
                /* the attempt that got no usable answer — with everything the wire returned (status,
                   error body whole, the stream segments before the cut, the provider's request id):
                   a paid, ambiguous attempt is a record, not a sentence */
                Map<String, Object> env = envelope("model.failure", turn, attempt, lid, null);
                env.put("ms", ms);
                if (raw != null) { for (String k : List.of("status", "providerRequestId", "replay", "stream")) if (raw.get(k) != null) env.put(k, raw.get(k)); if (raw.get("segments") instanceof List<?> sl) env.put("segments", sl.size()); }
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("error", redact(String.valueOf(error)));
                if (raw != null && raw.get("segments") != null) body.put("segments", raw.get("segments"));
                else if (raw != null && raw.get("body") != null) body.put("body", raw.get("body"));
                try { record(m.id, env, Json.write(body)); } catch (IOException e) { Log.error("course capture", "failure not journaled: " + e, null); }
            }
            @Override public void toolResult(String lid, int turn, String callId, String tool, Map<String, Object> args, String exactContent, boolean ok, int fullChars) {
                Map<String, Object> env = envelope("tool.result", turn, 0, lid, callId);
                env.put("tool", tool); env.put("tool_call_id", callId); env.put("ok", ok);
                /* the content is what the model consumed — after the loop's result cap; the cap is
                   declared with the size it removed, never hidden */
                int exact = exactContent == null ? 0 : exactContent.length();
                env.put("result_chars", exact);
                env.put("result_chars_full", Math.max(fullChars, exact));
                if (fullChars > exact) env.put("capped", true);
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("tool", tool); body.put("args", args); body.put("content", exactContent); body.put("ok", ok);
                if (fullChars > exact) body.put("capped", Map.of("fullChars", fullChars, "note", "the loop's tool-result cap applied before the model saw it; this content is exactly what the model consumed"));
                try { record(m.id, env, Json.write(body)); } catch (IOException e) { Log.error("course capture", "tool result not journaled: " + e, null); }
            }
            @Override public void terminal(String outcome, String reason) {
                Map<String, Object> env = envelope("mission.terminal", 0, 0, null, null);
                env.put("outcome", outcome); env.put("reason", redact(reason));   // a provider's error text may echo a header
                env.put("tokens", Map.of("weighted", m.tokensUsed, "raw", m.tokensRaw, "cacheHit", m.cacheHitTokens, "budget", m.budget));
                env.put("turns", m.turnsDone);
                try { record(m.id, env, Json.write(Map.of("outcome", outcome, "reason", String.valueOf(reason), "state", String.valueOf(m.state)))); }
                catch (IOException e) { Log.error("course capture", "terminal not journaled: " + e, null); }
            }
        };
    }

    /** An annotation (rename / comment) as the workspace recorded it, under the run that made it
     *  when an agent did, else under the human's own run "human". */
    public void annotation(String runId, RunIdentity id, Map<String, Object> entry, String originalSymbol) {
        Map<String, Object> env = new LinkedHashMap<>();
        env.put("event_id", eventId(instance + ":" + runId + ":annotation:" + entry.get("id")));
        /* actor = who made the edit (the agent of the run, or the student); original_method = the
           edited symbol's original descriptor; snapshot_id = the target's revision AFTER the edit */
        env.put("room", id.room()); env.put("actor", String.valueOf(entry.get("actor")));
        env.put("run_id", runIdOf(runId)); env.put("mission", runId); env.put("conversation_id", runIdOf(runId) + "-c1");
        identityFields(env, id);
        env.put("target", id.targetName()); env.put("target_sha256", id.targetSha256()); env.put("target_manifest_sha256", id.manifestSha256());
        env.put("original_method", originalSymbol); env.put("snapshot_id", id.snapshotId());
        env.put("event_type", "annotation");
        env.put("op", entry.get("op")); env.put("history_id", entry.get("id"));
        try { record(runId, env, Json.write(entry)); } catch (IOException e) { Log.error("course capture", "annotation not journaled: " + e, null); }
    }
}
