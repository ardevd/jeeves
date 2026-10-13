package dev.workbench.course;

import dev.workbench.registry.Json;
import dev.workbench.server.AgentManager;
import dev.workbench.server.Log;
import dev.workbench.server.Workspace;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The course integration's live parts, built once at boot when WORKBENCH_COURSE is on: the class
 * server client, the access policy (CoursePolicy), the identity gate, the classroom's activity
 * (CourseActivity), the private capture, the target manifest, the room's health observations and
 * the Day 1 missions' snapshots. {@link #current()} is null on a standalone workbench — every course
 * op answers E_UNSUPPORTED through CourseOps then, and nothing else consults this.
 *
 * Boot fails closed (A10): no room, no auth credential, no manifest setting, or no real engine
 * (unless a test says allow_mock) and the process does not start — a room that boots without its
 * identity, its course target or its engine is not a deployment that happens to be degraded.
 *
 * The health contract (course.status) answers THREE questions apart, because they fail apart:
 *   access     enabled | disabled | fenced | unknown | starting — the instructor's release as the
 *              auth service tells this room (CoursePolicy)
 *   state      the ANALYSIS verdict: ready | loading | degraded | target-mismatch | unavailable —
 *              identity reachable, the engine and the open target, the course manifest and the
 *              target's bytes in it, the capture exporting
 *   model      the MODEL route: ready | blocked | degraded | unavailable | unconfigured — the class
 *              gateway's scheduler state and its route's qualification; a fixture or an unqualified
 *              route is never shown as a verified real route
 * and the server's launch-seam vocabulary beside the analysis verdict (healthy | degraded | stale |
 * unavailable). Nothing here says "ok" because the web process answered HTTP.
 */
public final class CourseRuntime {

    private static volatile CourseRuntime current;
    public static CourseRuntime current() { return current; }

    private final Workspace ws;
    private final CourseClient client;
    private final CoursePolicy policy;
    private final CourseIdentity identity;
    private final CourseActivity activity;
    private final Capture capture;
    private final CourseManifest manifest;
    private final File home;
    private final long startedAt = System.currentTimeMillis();
    /** mission id → the snapshot course.mission took at spawn (also persisted beside the capture) */
    private final Map<String, Map<String, Object>> snapshots = new ConcurrentHashMap<>();
    /** open SSE streams (the observation's open_streams) */
    private final AtomicInteger streams = new AtomicInteger();
    private volatile Map<String, Object> lastObservation;   // what the room last told the auth service, and its answer
    private volatile Thread observer;
    private volatile boolean closed;
    /** the health probes' threads (status() asks four services at once) */
    private final java.util.concurrent.ExecutorService probes = java.util.concurrent.Executors.newCachedThreadPool(r -> { Thread t = new Thread(r, "course-health-probe"); t.setDaemon(true); return t; });

    private CourseRuntime(Workspace ws, File home) throws IOException {
        this.ws = ws;
        this.home = home;
        this.client = new CourseClient();
        this.policy = new CoursePolicy(client);
        this.identity = new CourseIdentity(client, policy);
        this.activity = new CourseActivity(client);
        File root = Course.captureRoot(home);
        Files.createDirectories(root.toPath());
        this.capture = new Capture(root, client, Course.captureMode(), Course.evalTaint(), Course.captureDurableDeclared());
        this.capture.dispatchGate(policy::dispatchBlock);
        this.capture.ownerFields(this::ownerFields);
        this.manifest = new CourseManifest(Course.manifestFile());
        File snaps = new File(capture.root(), "missions");
        Files.createDirectories(snaps.toPath());
        File[] saved = snaps.listFiles((d, n) -> n.endsWith(".json"));
        if (saved != null) for (File f : saved) {
            try { snapshots.put(f.getName().replace(".json", ""), Json.parseObject(Files.readString(f.toPath()))); } catch (Exception ignored) {}
        }
        CourseFiles.init(home);
    }

    /** Boot: null when the switch is off; refuses to start course mode without its identity, its
     *  credential, its course target or its engine. */
    public static CourseRuntime start(File home, Workspace ws) throws IOException {
        if (!Course.on()) { current = null; return null; }
        if (Course.room().isBlank()) throw new IOException("WORKBENCH_COURSE is on but WORKBENCH_COURSE_ROOM names no room (room-N)");
        File auth = Course.credentialFile("auth");
        if (auth == null) throw new IOException("course mode needs the room's credentials (WORKBENCH_COURSE_CREDENTIALS, or WORKBENCH_COURSE_AUTH_TOKEN_FILE) — a room without its auth credential cannot tell who anyone is");
        if (!auth.isFile()) throw new IOException("course mode: the room's auth credential file is missing: " + auth.getPath());
        if (Course.manifestFile() == null) throw new IOException("course mode needs the course target manifest (WORKBENCH_COURSE_MANIFEST) — a room that cannot check the target's bytes is not a classroom room");
        if (!dev.workbench.engine.Engines.jebAvailable() && !Course.allowMock())
            throw new IOException("course mode needs the real engine on the classpath (JEB_HOME / backend/engine): a room on the mock engine is not a deployment — WORKBENCH_COURSE_ALLOW_MOCK=1 is for tests only");
        CourseRuntime rt = new CourseRuntime(ws, home);
        current = rt;
        rt.registerClassProvider();
        rt.policy.poll();   // the first answer before the first request, when the auth service is up
        rt.policy.start();
        rt.activity.refresh();
        rt.activity.start();
        rt.startObserver();
        return rt;
    }

    /** The class inference gateway as this room's `class` provider: the gateway URL (+ /v1), the
     *  room's gateway credential as a FILE-backed key (never in env or argv), stream-only, the
     *  served model ids from the connection card (WORKBENCH_COURSE_CLASS_MODELS). Without a model id
     *  the provider exists but has no model — course.status.model says what is missing. The launch
     *  wrapper sets no WORKBENCH_LLM_* and no key: this is how a deployed room reaches the gateway
     *  at all (fix-review 2026-10-09 H2). */
    void registerClassProvider() {
        try {
            File gw = Course.credentialFile("gateway");
            String url = Course.gatewayUrl().replaceAll("/+$", "");
            if (url.isEmpty() || gw == null) return;
            String[] reasoning = Course.classReasoning().split(":", 2);
            ws.agents().llm().registerManaged("class", Course.classLabel(), url.endsWith("/v1") ? url : url + "/v1", Course.classModels(),
                    reasoning[0].trim(), reasoning.length > 1 ? reasoning[1].trim() : "all", true, Course.classMaxOutput());
            ws.agents().llmKeys().fileBacked("class", gw);
            Log.info("course", "class provider registered: " + url + "/v1 · models " + (Course.classModels().isEmpty() ? "(none configured — WORKBENCH_COURSE_CLASS_MODELS)" : Course.classModels()) + " · key file " + gw.getName());
        } catch (Exception e) {
            Log.warn("course", "class provider not registered: " + e, null);
        }
    }

    public CourseClient client() { return client; }
    public CoursePolicy policy() { return policy; }
    public CourseIdentity identity() { return identity; }
    public CourseActivity activity() { return activity; }
    public Capture capture() { return capture; }
    public CourseManifest manifest() { return manifest; }
    public Workspace workspace() { return ws; }
    public File home() { return home; }

    public void streamOpened() { streams.incrementAndGet(); }
    public void streamClosed() { streams.decrementAndGet(); }
    public int openStreams() { return streams.get(); }

    /** The last observation sent (what /healthz shows), or a fresh one when none was sent yet. */
    public Map<String, Object> observation() {
        Map<String, Object> last = lastObservation;
        if (last != null && last.get("sent") instanceof Map<?, ?> s) { Map<String, Object> m = new LinkedHashMap<>(); for (Map.Entry<?, ?> e : s.entrySet()) m.put(String.valueOf(e.getKey()), e.getValue()); return m; }
        return buildObservation();
    }

    /**
     * Boot: open the seed the operator copied into this home — the registered projects
     * (projects/&lt;slug&gt;/.project-name + targets.json, the server's seed files) and their targets by
     * store path; a member whose recorded path is not here (the seed was made in another home) is
     * looked for at the store's own place, &lt;home&gt;/targets/&lt;key&gt;/&lt;name&gt;. Analysis runs in the
     * background; the observation says `starting` until the target is ready. A failed open is
     * logged and reported (course.status target), never fatal: the student can open it by hand.
     */
    public List<Map<String, Object>> openSeed() {
        List<Map<String, Object>> out = new ArrayList<>();
        File[] dirs = new File(home, "projects").listFiles(File::isDirectory);
        if (dirs == null) return out;
        java.util.Arrays.sort(dirs);
        for (File d : dirs) {
            File nameF = new File(d, ".project-name"), members = new File(d, "targets.json");
            if (!nameF.isFile() || !members.isFile()) continue;
            Map<String, Object> row = new LinkedHashMap<>();
            try {
                String name = Files.readString(nameF.toPath()).trim();
                row.put("project", name);
                ws.openProject(name);
                List<Object> opened = new ArrayList<>(), failed = new ArrayList<>();
                Object parsed = Json.parse(Files.readString(members.toPath()));
                List<?> rows = parsed instanceof Map<?, ?> pm && pm.get("targets") instanceof List<?> l ? l : parsed instanceof List<?> l2 ? l2 : List.of();
                for (Object o : rows) if (o instanceof Map<?, ?> m) {
                    /* THIS home's own copy first (targets/<key>/<name> — where the server copied the
                       seed's files); the recorded storePath is a hint from the home the seed was made
                       in, taken only when it lies inside this home — a path that happens to exist
                       elsewhere on the machine is another room's, never opened (rehearsal 2026-10-09) */
                    String path = String.valueOf(m.get("storePath"));
                    File f = null;
                    if (m.get("key") != null && m.get("name") != null) f = new File(new File(new File(home, "targets"), String.valueOf(m.get("key"))), String.valueOf(m.get("name")));
                    if (f == null || !f.isFile()) {
                        File rec = new File(path);
                        try { if (rec.isFile() && rec.getCanonicalFile().toPath().startsWith(home.getCanonicalFile().toPath())) f = rec; } catch (IOException ignored) {}
                    }
                    if (f == null || !f.isFile()) { failed.add(Map.of("name", String.valueOf(m.get("name")), "why", "not in this home: " + path)); continue; }
                    try { ws.openTarget(f.getPath(), null, "system:course-boot"); opened.add(f.getPath()); }
                    catch (Exception e) { failed.add(Map.of("name", String.valueOf(m.get("name")), "why", String.valueOf(e.getMessage()))); }
                }
                row.put("opened", opened); row.put("failed", failed);
                Log.info("course boot", "seed project " + name + ": " + opened.size() + " target(s) opening" + (failed.isEmpty() ? "" : ", " + failed.size() + " not opened: " + failed));
            } catch (Exception e) {
                row.put("failed", e.getMessage());
                Log.warn("course boot", "seed project in " + d.getName() + " not opened: " + e, null);
            }
            out.add(row);
        }
        seedOpened = out;
        return out;
    }
    private volatile List<Map<String, Object>> seedOpened = List.of();

    /** Shutdown: the missions fenced first (their terminal records written while the process still
     *  can), a short wait for their threads, then the cleanup receipt beside the capture, then the
     *  pollers stopped (fix-review 2026-10-09 M8). */
    public void close(String why) {
        closed = true;
        try {
            List<Map<String, Object>> fenced = ws.agents().fence("fenced: the room is shutting down (" + why + ") — the run's work is kept; nothing restarts by itself");
            long deadline = System.currentTimeMillis() + 3000;
            while (!fenced.isEmpty() && missionCounts()[1] > 0 && System.currentTimeMillis() < deadline) Thread.sleep(100);
        } catch (Throwable e) { Log.warn("course", "missions not fenced at shutdown: " + e, null); }
        try { capture.writeReceipt(why); } catch (Exception e) { Log.warn("course", "receipt not written at shutdown: " + e, null); }
        policy.close(); activity.close(); capture.close();
        Thread o = observer; if (o != null) o.interrupt();
    }

    // ------------------------------------------------------------ identity of a run

    /** The owner's fields every event carries (the room credential's context; empty before it is known). */
    Map<String, Object> ownerFields() {
        Map<String, Object> m = new LinkedHashMap<>();
        CourseClient.RoomContext o = policy.owner();
        if (o == null) return m;
        m.put("account_id", o.accountId()); m.put("workspace_generation", o.workspaceGeneration()); m.put("course_session_id", o.courseSessionId());
        m.put("policy_revision", policy.policyRevision());
        return m;
    }

    /** The identity block a mission's events carry: who, which room, which bytes, which snapshot. */
    public Capture.RunIdentity identityFor(AgentManager.Mission m) {
        CourseIdentity.Principal p = CourseIdentity.CURRENT.get();
        String actor = p != null ? p.actor() : "llm:" + m.agentId;
        String sha = ws.executableSha(m.target);   // the executable's measured digest (never the file beside a database)
        Map<String, Object> snap = snapshots.get(m.id);
        String snapshotId = snap != null ? String.valueOf(snap.get("snapshot_id")) : m.target + "@h" + ws.historyRevision(m.target);
        /* the mission's model string is "provider:model"; the envelope carries the two apart
           (provider, model_requested) so the gateway's own record of the request joins on the id
           the endpoint saw */
        String model = m.model;
        if (model != null && m.provider != null && model.startsWith(m.provider + ":")) model = model.substring(m.provider.length() + 1);
        /* original_method: the subject's ORIGINAL descriptor — the goal names the symbol as the student
           sees it (possibly a display name); the engine knows the dex's */
        String subject = dev.workbench.llm.AgentLoop.subjectOf(m.goal);
        if (subject != null && m.target != null) {
            try {
                Map<String, Object> rs = ws.resolveSymbol(m.target, subject);
                /* the dex's identity of the subject — unless the goal's complete descriptor CONTRADICTS the method found:
                   then the goal keeps its own spelling (the mission is about what it named, not a re-aim) */
                if (rs.get("origSig") != null && !Boolean.TRUE.equals(rs.get("contradiction"))) subject = String.valueOf(rs.get("origSig"));
            } catch (Exception ignored) {}   // a symbol the engine does not know stays as the goal spelled it
        }
        CourseActivity.Identity act = activity.current();
        return new Capture.RunIdentity(Course.room(), actor, act.id(), sha, manifest.manifestSha256(),
                subject, snapshotId, ws.targetNameOf(m.target), m.provider, model, ownerFields(), act);
    }

    /** The capture's own thread for history entries: the listener is called under the Workspace
     *  monitor (recordHistory), and the work — asking the agent manager which mission the actor is,
     *  resolving the symbol's original descriptor, hashing the artifact, an fsync — must not run
     *  there: AgentManager.spawn holds ITS monitor while it calls into the Workspace, so taking the
     *  manager's monitor under the Workspace's is a lock-order inversion that deadlocks the room
     *  the moment an agent's rename lands while a student spawns (fix-review 2026-10-07). */
    private final java.util.concurrent.ExecutorService historyWork = java.util.concurrent.Executors.newSingleThreadExecutor(r -> { Thread t = new Thread(r, "course-history-capture"); t.setDaemon(true); return t; });

    /** Every history entry as it is recorded (and every undo/redo): captured under the run that
     *  made it (an agent's mission) or under the student's own run "human-&lt;target&gt;". Returns at
     *  once; the journal line is written on the capture's thread, in order. */
    public void onHistory(String tid, Map<String, Object> entry) {
        /* read on the caller's thread: the principal and the client the op ran under — an external
           assistant's write is filed under the assistant's own run, stamped, never under the student's
           (addendum req. 7; fix-review addendum M7) */
        CourseIdentity.Principal p = CourseIdentity.CURRENT.get();
        String client = dev.workbench.registry.Dispatcher.CLIENT.get();
        historyWork.submit(() -> {
            try {
                if (p != null && p.machine()) {
                    Map<String, Object> stamped = new LinkedHashMap<>(entry);
                    stamped.put("machine", true); if (client != null) stamped.put("client", client);
                    captureAnnotation(assistantRun(p, client), tid, stamped); return;
                }
                String actor = String.valueOf(entry.get("actor"));
                if (actor.startsWith("llm:") || actor.startsWith("agent:")) {
                    String agentId = actor.substring(actor.indexOf(':') + 1).replaceAll(" .*$", "");
                    for (Object o : ws.agents().missionList()) if (o instanceof Map<?, ?> row && agentId.equals(row.get("agentId"))) { captureAnnotation(String.valueOf(row.get("id")), tid, entry); return; }
                }
                captureAnnotation("human-" + tid, tid, entry);
            } catch (Throwable t) { Log.warn("course capture", "history entry not captured: " + t, null); }
        });
    }

    private void captureAnnotation(String runId, String tid, Map<String, Object> entry) {
        String sha = ws.executableSha(tid);
        String orig = null;
        try {
            Map<String, Object> res = ws.engineFor(tid).resolve(String.valueOf(entry.get("newAddr") != null ? entry.get("newAddr") : entry.get("addr")));
            orig = res.get("origSig") == null ? null : String.valueOf(res.get("origSig"));
        } catch (Exception ignored) {}
        CourseActivity.Identity act = activity.current();
        Capture.RunIdentity id = new Capture.RunIdentity(Course.room(), String.valueOf(entry.get("actor")), act.id(), sha, manifest.manifestSha256(),
                orig, tid + "@h" + ws.historyRevision(tid), ws.targetNameOf(tid), null, null, ownerFields(), act);
        capture.annotation(runId, id, entry, orig);
    }

    // ------------------------------------------------------------ an external assistant's tool traffic

    /** The provenance an evidence answer carries (student MCP addendum): the EXACT target the call ran
     *  on (id, name, artifact sha256, the course manifest's target id and match), this room's project
     *  revision, the tool and its action, the client, the call id, and whether the facade cut the
     *  result. The symbol-level provenance (original DEX locator, analyst renames) is in the rows
     *  themselves (origSig, analystNamed). */
    public Map<String, Object> provenanceFor(String tool, Map<String, Object> args, Map<String, Object> res, String client, String callId, String tid) {
        Map<String, Object> prov = new LinkedHashMap<>();
        prov.put("schema", "jeeves-evidence-provenance-v1");
        if (tid != null && ws.targetIds().contains(tid)) {
            prov.put("target", tid); prov.put("target_name", ws.targetNameOf(tid));
            Map<String, Object> exe = ws.executableIdentity(tid);
            String exeSha = ws.executableSha(tid);
            prov.put("artifact_sha256", exeSha);   // the executable's measured digest; null when recorded/missing/unknown
            prov.put("executable_identity", exe.get("state"));
            if (!"measured".equals(exe.get("state"))) prov.put("executable_basis", exe.get("basis"));
            String match = String.valueOf(manifest.resolveDigest(exeSha, ws.targetNameOf(tid)).get("match"));
            prov.put("manifest_match", match);
            if ("match".equals(match)) prov.put("course_target_id", manifest.targetId());   // the course's id only when these ARE the course's bytes
            prov.put("project_revision", ws.historyRevision(tid)); prov.put("snapshot_id", tid + "@h" + ws.historyRevision(tid));
        } else { prov.put("target", null); prov.put("target_note", "no target was open for this call"); }
        prov.put("tool", tool); if (args.get("action") != null) prov.put("action", cut(String.valueOf(args.get("action")), 40));
        if (args.get("symbol") != null) {
            String sym = cut(String.valueOf(args.get("symbol")), 400);
            prov.put("symbol", sym);   // as ASKED — and beside it what the engine actually answered for
            if (tid != null) {
                try {
                    Map<String, Object> rs = ws.resolveSymbol(tid, sym);
                    prov.put("symbol_resolved", rs.get("origSig") != null ? rs.get("origSig") : rs.get("addr"));
                    prov.put("symbol_resolution", Boolean.TRUE.equals(rs.get("contradiction")) ? "contradiction: " + rs.get("lenient") : rs.get("lenient") != null ? "lenient: " + rs.get("lenient") : "exact");
                } catch (Exception e) { prov.put("symbol_resolution", "unresolved"); }
            }
        }
        prov.put("client", client); prov.put("call_id", callId == null ? null : cut(callId, 80));
        prov.put("room", Course.room());
        /* the generation, not the account or the session: this block travels to the assistant's vendor with
           every result; the owner's full identity stays in the room's capture and the artifact export */
        CourseClient.RoomContext o = policy.owner();
        if (o != null) prov.put("workspace_generation", o.workspaceGeneration());
        CourseActivity.Identity act = activity.current();
        prov.put("activity_id", act.id()); prov.put("activity_revision", act.revision());
        boolean truncated = (res.get("meta") instanceof Map<?, ?> meta && Boolean.TRUE.equals(meta.get("truncated")))
                || (res.get("data") instanceof Map<?, ?> dm && Boolean.TRUE.equals(dm.get("truncated")));
        prov.put("truncated", truncated);   // always said, so an absent flag never passes for "complete"
        prov.put("origin", "external-assistant");
        prov.put("note", "names in the rows marked analystNamed are hypotheses; origSig is the dex's original locator; this is tool traffic the room saw — not the assistant's model transcript");
        return prov;
    }

    private static String cut(String s, int max) { return s.length() <= max ? s : s.substring(0, max) + "…"; }

    /** The room's target for an external call (addendum req. 6: the EXACT assigned target, never a first
     *  match): the one open target whose bytes the course manifest names, put into the call's args before
     *  dispatch. A call naming another target is refused; a room whose open targets are known NOT to be
     *  the course's refuses an assistant's call; a mock or manifest-less room (resolution unknown — the
     *  fixture rooms) keeps the page's active target and the provenance says `manifest_match: unknown`.
     *  Returns the target the call runs on (null when none is open). */
    public String pinTarget(Map<String, Object> args, boolean machine) throws dev.workbench.registry.OpException {
        String match = null; int matches = 0; boolean mismatch = false;
        for (String tid : ws.targetIds()) {
            String m = String.valueOf(manifest.resolveDigest(ws.executableSha(tid), ws.targetNameOf(tid)).get("match"));
            if ("match".equals(m)) { match = tid; matches++; } else if ("mismatch".equals(m)) mismatch = true;
        }
        String named = args.get("target") == null ? null : String.valueOf(args.get("target"));
        if (matches == 1) {
            if (named == null || named.isBlank()) { args.put("target", match); return match; }
            String ntid = null;
            try { ntid = ws.resolveTargetIdOrActive(named); } catch (dev.workbench.registry.OpException ignored) {}
            if (!match.equals(ntid))
                throw dev.workbench.registry.OpException.scope("this room's target is the course's (" + ws.targetNameOf(match) + "); '" + named + "' is not it", "course=target-pin");
            return match;
        }
        if (matches > 1)
            throw dev.workbench.registry.OpException.scope("more than one open target carries the course's bytes — close one on the page before the assistant continues", "course=target-pin");
        if (mismatch && machine)
            throw dev.workbench.registry.OpException.scope("the course's target is not open in this room (the page's target is another file) — open the course target on the page first", "course=target-pin");
        try { return ws.resolveTargetIdOrActive(named); } catch (dev.workbench.registry.OpException e) { return null; }
    }

    /** The run an external assistant's traffic is filed under: assistant-&lt;client&gt; (the bridge names
     *  the client it serves), sanitized and bounded. */
    static String assistantRun(CourseIdentity.Principal p, String client) {
        String who = (client == null || client.isBlank() ? p.actor() : client).replaceAll("[^A-Za-z0-9._-]", "_");
        if (who.length() > 48) who = who.substring(0, 48);
        return "assistant-" + who;
    }

    /** One tool call an external assistant made through the room (the MCP bridge: a machine principal):
     *  captured under the run `assistant-<client>` as `assistant.tool_call` — the call, its arguments,
     *  the answer's size and status, the target identity (the store's field names: target, target_sha256,
     *  target_manifest_sha256) — never its model transcript (the room never sees it) and never a reason
     *  to start an internal mission. */
    public void captureAssistantCall(CourseIdentity.Principal p, String client, String tool, Map<String, Object> args, Map<String, Object> res, Map<String, Object> prov, long ms) {
        try {
            String run = assistantRun(p, client);
            assistantSeq.putIfAbsent(run, new java.util.concurrent.atomic.AtomicLong());
            long n = assistantSeq.get(run).incrementAndGet();
            Map<String, Object> env = new LinkedHashMap<>();
            env.put("event_id", capture.instance() + ":" + run + ":call:" + System.currentTimeMillis() + ":" + n);
            env.put("room", Course.room()); env.put("actor", p.actor());
            env.put("run_id", capture.runIdOf(run)); env.put("mission", run); env.put("conversation_id", capture.runIdOf(run) + "-c1");
            env.put("event_type", "assistant.tool_call"); env.put("origin", "external-assistant"); env.put("client", client);
            env.put("tool", tool); if (prov.get("action") != null) env.put("action", prov.get("action"));
            env.put("ok", Boolean.TRUE.equals(res.get("ok"))); env.put("ms", ms);
            if (prov.get("target") != null) {
                env.put("target", prov.get("target_name")); env.put("target_id", prov.get("target"));
                env.put("target_sha256", prov.get("artifact_sha256")); env.put("target_manifest_sha256", manifest.manifestSha256());
                env.put("manifest_match", prov.get("manifest_match"));
            }
            for (String k : List.of("course_target_id", "project_revision", "snapshot_id", "symbol", "call_id")) if (prov.get(k) != null) env.put(k, prov.get(k));
            env.putAll(ownerFields());
            CourseActivity.Identity act = activity.current();
            env.put("activity_id", act.id()); env.put("activity_revision", act.revision()); env.put("activity_sha256", act.sha256()); env.put("activity_stale", act.stale());
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("tool", tool); body.put("args", args);
            Object payload = Boolean.TRUE.equals(res.get("ok")) ? res.get("data") : res.get("error");
            String text = Json.write(payload == null ? Map.of() : payload);
            body.put("result_chars", text.length());
            body.put("result", text.length() > 200_000 ? text.substring(0, 200_000) + "…[+" + (text.length() - 200_000) + " chars]" : payload);   // the whole answer up to a bound; what the assistant saw is the bridge's (it may cut)
            capture.record(run, env, Json.write(body));
        } catch (Throwable t) { Log.warn("course capture", "assistant tool call not captured: " + t, null); }
    }
    private final Map<String, java.util.concurrent.atomic.AtomicLong> assistantSeq = new ConcurrentHashMap<>();

    // ------------------------------------------------------------ Day 1 missions

    public Map<String, Object> snapshot(String missionId) { return snapshots.get(missionId); }

    /** Record what the mission started from — kept in memory and beside the capture. */
    public void snapshot(String missionId, Map<String, Object> snap) {
        snapshots.put(missionId, snap);
        File f = new File(new File(capture.root(), "missions"), missionId + ".json");
        try { Files.writeString(f.toPath(), Json.write(snap)); }
        catch (IOException e) { Log.warn("course mission", "snapshot not written for " + missionId + ": " + e, null); }
    }

    // ------------------------------------------------------------ health

    /** The missions the room runs now, and whether one is still draining after a fence. */
    private int[] missionCounts() {
        int active = 0, unconfirmed = 0;
        try {
            for (Object o : ws.agents().missionList()) if (o instanceof Map<?, ?> row) {
                if (List.of("running", "paused", "queued").contains(String.valueOf(row.get("state")))) active++;
                if (Boolean.TRUE.equals(row.get("cancellationUnconfirmed"))) unconfirmed++;
            }
        } catch (Exception ignored) {}
        return new int[] { active, unconfirmed };
    }

    public Map<String, Object> status() {
        Map<String, Object> st = new LinkedHashMap<>();
        st.put("config", Course.describe());
        st.put("access", policy.describe());
        st.put("identity", identity.describe());
        st.put("activity", activity.describe());
        /* the four probes at once: a service that is away costs one timeout, not four in a row — the
           observer runs this every 5 s and the server calls an observation stale after 15 */
        java.util.concurrent.CompletableFuture<CourseClient.Health> fAuth = java.util.concurrent.CompletableFuture.supplyAsync(() -> client.health("auth", Course.authUrl()), probes),
                fStore = java.util.concurrent.CompletableFuture.supplyAsync(() -> client.health("store", Course.storeUrl()), probes),
                fGateway = java.util.concurrent.CompletableFuture.supplyAsync(() -> client.health("gateway", Course.gatewayUrl()), probes),
                fBoard = java.util.concurrent.CompletableFuture.supplyAsync(() -> client.health("board", Course.boardUrl()), probes);
        CourseClient.Health auth = fAuth.join(), store = fStore.join(), gateway = fGateway.join(), board = fBoard.join();
        /* a room holds no board credential any more (findings are the student's own browser action):
           the board is probed only where a credential exists, else said to be off */
        if (!client.hasCredential("board")) board = new CourseClient.Health("board", "off", "no board credential — findings go through the classroom's own page", null);
        Map<String, Object> deps = new LinkedHashMap<>();
        deps.put("auth", auth.state()); deps.put("store", store.state()); deps.put("gateway", gateway.state()); deps.put("board", board.state());
        deps.put("classroom", activity.reachable() ? "ok" : Course.classroomUrl().isEmpty() ? "off" : "unreachable");
        st.put("dependencies", deps);
        Map<String, Object> depDetail = new LinkedHashMap<>();
        for (CourseClient.Health h : List.of(auth, store, gateway, board)) depDetail.put(h.service(), h.detail());
        if (activity.lastError() != null) depDetail.put("classroom", activity.lastError());
        st.put("dependencyDetail", depDetail);
        Map<String, Object> cap = capture.status();
        st.put("capture", cap);
        manifest.reload();
        Map<String, Object> tgt = new LinkedHashMap<>();
        String active = ws.activeTarget();
        tgt.put("open", active);
        String match = "unknown";
        boolean loading = false, engineError = false;
        if (active != null) {
            tgt.put("name", ws.targetNameOf(active));
            try {
                Map<String, Object> info = ws.engineFor(active).info();
                loading = "loading".equals(info.get("state"));
                engineError = "error".equals(info.get("state"));
                tgt.put("state", info.get("state"));
                if (engineError && info.get("error") != null) tgt.put("error", info.get("error"));
            } catch (Exception e) { engineError = true; tgt.put("error", e.getMessage()); }
            Map<String, Object> res = manifest.resolveDigest(ws.executableSha(active), ws.targetNameOf(active));
            tgt.put("resolution", res);
            tgt.put("executable", ws.executableIdentity(active));
            match = String.valueOf(res.get("match"));
        }
        tgt.put("manifest", manifest.present() ? Map.of("target_id", String.valueOf(manifest.targetId()), "label", String.valueOf(manifest.label()), "sha256", String.valueOf(manifest.manifestSha256()))
                : Map.of("error", String.valueOf(manifest.error())));
        st.put("target", tgt);
        st.put("engine", Map.of("real", dev.workbench.engine.Engines.jebAvailable(), "note", dev.workbench.engine.Engines.jebAvailable() ? "the JEB engine is on the classpath" : "MOCK engine — tests only, never a deployment"));

        /* the ANALYSIS verdict — code browsing, naming, the course bytes: identity reachable, the
           engine and the open target, the manifest, the capture of edits exporting. The store's
           admission of MODEL requests is the agent verdict's (below): a store backlog does not make
           reading code "unavailable" (persona pass H4). Each condition has a short code the student
           can quote to the instructor; the `fix` is the operator's. */
        String state, summary, fix = "", code = "";
        boolean strict = "strict".equals(Course.captureMode());
        int refused = cap.get("refused") instanceof Number rn ? rn.intValue() : 0;
        if (!auth.ok()) { state = "unavailable"; code = "auth-unreachable"; summary = "the class sign-in service is not reachable (" + auth.state() + ": " + auth.detail() + ") — no request can be attributed to a student"; fix = "start/repair the class auth service; check WORKBENCH_COURSE_AUTH_URL and the room's auth credential"; }
        else if ("mismatch".equals(match)) { state = "target-mismatch"; code = "target-mismatch"; summary = String.valueOf(((Map<?, ?>) tgt.get("resolution")).get("reason")); fix = "open the course artifact (the manifest's bytes)"; }
        else if (engineError) { state = "unavailable"; code = "engine-error"; summary = "the open target failed to analyse (" + tgt.get("error") + ") — nothing can be read from it"; fix = "close it and open the course artifact again; the workbench log has the engine's error"; }
        else if (loading) { state = "loading"; code = "analysing"; summary = "the open target is still analysing"; }
        else if (active == null) { state = "degraded"; code = "no-target"; summary = "no target is open"; fix = "open the course artifact"; }
        else if (!manifest.present()) { state = "degraded"; code = "no-manifest"; summary = "no course manifest: the target cannot be checked against the course's bytes (" + manifest.error() + ")"; fix = "set WORKBENCH_COURSE_MANIFEST to the published manifest"; }
        else if ("unknown".equals(match)) { state = "degraded"; code = "bytes-unchecked"; summary = "the open target's bytes cannot be checked against the course manifest (" + ((Map<?, ?>) tgt.get("resolution")).get("reason") + ") — a mission runs, but its events carry no artifact digest"; fix = "open the course artifact FILE (the apk), not a database"; }
        else if (!store.ok() || Boolean.TRUE.equals(cap.get("parked"))) { state = "degraded"; code = "export-behind"; summary = "the capture of your edits is behind: store " + store.state() + ", " + cap.get("pending") + " record(s) pending (nothing is lost; they are journaled here and retried)"; fix = "repair the store; records are journaled locally and retried"; }
        else if (refused > 0) { state = "degraded"; code = "capture-refused"; summary = refused + " capture record(s) the store refused (another body under the same id) — journaled here, kept aside, not exported"; fix = "operator: inspect " + new File(capture.root(), "acked").getPath() + "/*.refused"; }
        else if (!activity.reachable() && !Course.classroomUrl().isEmpty()) { state = "degraded"; code = "classroom-context"; summary = "the classroom's room context is not readable (" + activity.lastError() + ") — events carry the activity as " + activity.current().source() + (activity.current().stale() ? ", stale" : ""); fix = "operator: the classroom service and the room's classroom credential"; }
        else { state = "ready"; summary = "identity, the engine, the course target, capture and the classroom context check out"; }
        st.put("state", state);
        st.put("summary", summary);
        if (!code.isEmpty()) st.put("fixCode", code);
        if (!fix.isEmpty()) { st.put("fix", fix); st.put("fixAudience", "operator"); }
        st.put("seamState", switch (state) { case "ready", "loading" -> "healthy"; case "degraded", "target-mismatch" -> "degraded"; default -> "unavailable"; });

        /* the MODEL / agent-run verdict, apart: the class gateway's scheduler and its route's
           qualification, the Day 1 preset's provider, and — strict capture — the store that must admit
           a request before it leaves; never "verified" from here */
        Map<String, Object> model = modelStatus(gateway);
        if (strict && !store.ok()) {
            model.put("state", "blocked");
            model.put("summary", "agent runs wait for the capture store: strict capture and the store answers " + store.state() + " (" + store.detail() + ") — no model request is admitted until it recovers; code browsing is unaffected");
            model.put("fix", "repair the store, or set WORKBENCH_COURSE_CAPTURE=best-effort knowingly");
        }
        st.put("model", model);
        /* the classroom's pages a student is handed to (the classroom's `actions` or the portal's
           paths) — here so the page keeps them while the room refuses everything else */
        Map<String, Object> handoffs = new LinkedHashMap<>();
        String portal = Course.portalUrl().replaceAll("/+$", "");
        /* our names → the classroom's action keys (classroom_service.py: help, ready, withdraw, finding) and the portal's page */
        for (String[] a : new String[][] { { "findings", "finding", "findings" }, { "help", "help", "messages" }, { "ready", "ready", "today" }, { "withdraw", "withdraw", "today" }, { "thread", "help", "messages" } }) {
            Map<String, Object> h = activity.action(a[1]);
            if (h == null) h = activity.action(a[0]);
            handoffs.put(a[0], h != null && h.get("browser") != null ? h.get("browser") : (portal.isEmpty() ? "/classroom/" + a[2] : portal + "/classroom/" + a[2]));
        }
        st.put("handoffs", handoffs);
        lastStatus = st;
        if (lastObservation != null) st.put("observation", lastObservation);
        if (!seedOpened.isEmpty()) st.put("seed", seedOpened);
        st.put("observedAt", java.time.Instant.now().toString());
        return st;
    }

    /** The model route's own verdict: ready | blocked | degraded | unavailable | unconfigured. */
    Map<String, Object> modelStatus(CourseClient.Health gateway) {
        Map<String, Object> m = new LinkedHashMap<>();
        String preset = presetProblem();
        m.put("preset", Map.of("model", Course.presetModel(), "problem", preset == null ? "" : preset));
        Map<String, Object> route = gateway.route() == null ? Map.of() : gateway.route();
        m.put("route", route);
        m.put("gateway", Map.of("state", gateway.state(), "detail", gateway.detail()));
        String qual = route.get("qualification") == null ? "unknown" : String.valueOf(route.get("qualification"));
        String routeState = route.get("state") == null ? "unknown" : String.valueOf(route.get("state"));
        String state, summary;
        if (preset != null) { state = "unconfigured"; summary = preset + " — course.mission cannot run"; }
        else if (!gateway.ok() && gateway.state().startsWith("unreachable")) { state = "unavailable"; summary = "the class inference gateway is not reachable (" + gateway.detail() + ") — missions on the class provider will fail; a student's own route still works"; }
        else if (!gateway.ok()) { state = "blocked"; summary = "the class gateway's scheduler refuses work: " + gateway.detail(); }
        else if ("blocked".equals(routeState) || "unavailable".equals(routeState)) { state = "blocked"; summary = "the scheduler is open but the model route is " + routeState + (route.get("reason") == null ? "" : ": " + route.get("reason")); }
        else if (!"qualified".equals(qual)) { state = "degraded"; summary = "the scheduler is open; the model route's qualification is " + qual + (route.get("reason") == null ? "" : " (" + route.get("reason") + ")") + " — a fixture or an unqualified route is not a verified real model"; }
        else { state = "ready"; summary = "the class gateway accepts work and reports a qualified route (" + route.getOrDefault("provider", "?") + ")"; }
        m.put("state", state);
        m.put("summary", summary);
        m.put("qualification", qual);
        m.put("verified", false);
        m.put("verifiedNote", "a real model/tool loop under its own authorization verifies the route; a fixture or synthetic answer never counts as one");
        return m;
    }

    /** Why the Day 1 preset could not run, or null: no provider named, a provider the registry does
     *  not know, a keyed provider without its key. */
    String presetProblem() {
        String model = Course.presetModel();
        if (model == null || model.isBlank()) return "the class model id is not configured (WORKBENCH_COURSE_CLASS_MODELS — from the server's connection card) and no other preset is named (WORKBENCH_COURSE_PROVIDER)";
        try {
            String[] resolved = ws.agents().llm().resolve(model);
            if (resolved == null) return "the Day 1 preset's provider is not configured: " + model;
            dev.workbench.llm.LlmRegistry.Provider pr = ws.agents().llm().get(resolved[0]);
            if (pr == null) return "the Day 1 preset's provider is not configured: " + model;
            if (pr.needsKey && !ws.agents().llmKeys().has(pr.id)) return "the Day 1 preset's provider " + pr.id + " has no API key (" + dev.workbench.llm.KeyStore.envName(pr.id) + " or llm.setkey)";
            return null;
        } catch (Exception e) {
            return "the Day 1 preset cannot be resolved: " + e.getMessage();
        }
    }

    // ------------------------------------------------------------ observations

    static final long OBSERVE_MS = 5000;   // the server calls an observation stale after 15 s

    private void startObserver() {
        Thread t = new Thread(() -> {
            while (!closed) {
                try { observe(); } catch (Throwable e) { Log.warn("course observe", "observation failed: " + e, null); }
                try { Thread.sleep(OBSERVE_MS); } catch (InterruptedException e) { return; }
            }
        }, "course-observe");
        t.setDaemon(true);
        observer = t;
        t.start();
    }

    /** A component's detail: one line, at most 100 chars AND 300 UTF-8 bytes (the server's bound). */
    private static String bounded(String s) {
        String t = s == null ? "" : s.replaceAll("\\s+", " ").trim();
        if (t.length() > 100) t = t.substring(0, 97) + "…";
        while (t.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 300) t = t.substring(0, t.length() - 2) + "…";
        return t;
    }
    private static Map<String, Object> component(String state, String detail) { Map<String, Object> m = new LinkedHashMap<>(); m.put("state", state); m.put("detail", bounded(detail)); return m; }

    /** The last status computed (a snapshot the observer falls back to when a fresh one is slow). */
    private volatile Map<String, Object> lastStatus;
    /** The observation sequence: strictly increasing per generation as the server demands, persisted
     *  beside the capture (observe.seq) so a restarted room continues past what the server holds,
     *  seeded from the server's own `next_observation_sequence`; never a clock modulo (a clock-based
     *  sequence wrapped at 1e6 — fix-review 2026-10-09 H1). */
    private final java.util.concurrent.atomic.AtomicInteger sequence = new java.util.concurrent.atomic.AtomicInteger(-1);

    private File sequenceFile() { return new File(capture.root(), "observe.seq"); }

    private int nextSequence() {
        synchronized (sequence) {
            if (sequence.get() < 0) {
                int persisted = 0;
                try { if (sequenceFile().isFile()) persisted = Integer.parseInt(Files.readString(sequenceFile().toPath()).trim()); } catch (Exception ignored) {}
                sequence.set(persisted);
            }
            int next = Math.max(sequence.get() + 1, policy.nextObservationSequence());
            if (next > 1_000_000) next = 1_000_000;   // the server's bound; a generation posting every 5 s reaches it after 57 days
            sequence.set(next);
            try { Files.writeString(sequenceFile().toPath(), next + "\n"); } catch (IOException ignored) {}
            return next;
        }
    }

    /** The room's health observation, exactly the server's fields (jeeves.py observe()), posted with
     *  the room credential. Readiness never authorizes anything; a lost observation is unknown to the
     *  server, never healthy. A 409 (the sequence did not advance — another process of this
     *  generation posted, or the room restarted past what it remembered) re-reads the context and
     *  posts once more from the server's next sequence. Returns the observation sent. */
    public Map<String, Object> observe() {
        if (policy.owner() == null) return null;   // nothing to say before the room knows whose it is
        Map<String, Object> obs = buildObservation();
        Map<String, Object> record = new LinkedHashMap<>();
        for (int attempt = 1; ; attempt++) {
            obs.put("sequence", nextSequence());
            record.put("sent", obs);
            try {
                Map<String, Object> ack = client.observe(obs);
                record.put("answer", ack);
                record.put("ok", true);
                break;
            } catch (CourseClient.Refused e) {
                record.put("ok", false); record.put("error", "the auth service refused the observation (HTTP " + e.status + "): " + e.reason());
                if (e.status == 409 && attempt == 1) { policy.poll(); synchronized (sequence) { sequence.set(Math.max(sequence.get(), policy.nextObservationSequence() - 1)); } continue; }
                break;
            } catch (CourseClient.Unavailable e) {
                record.put("ok", false); record.put("error", e.getMessage());
                break;
            }
        }
        record.put("at", java.time.Instant.now().toString());
        lastObservation = record;
        return obs;
    }

    /** The engine component: the core started (JEB's version is known) → ready; JEB on the classpath
     *  but no core yet → starting; the mock engine → degraded. Read by reflection: the JEB classes
     *  are absent on a mock-only classpath. */
    private Map<String, Object> engineComponent(String pin) {
        if (!dev.workbench.engine.Engines.jebAvailable()) return component("degraded", "MOCK engine — tests only" + pin);
        String version = null;
        try { Object v = Class.forName("dev.workbench.engine.jeb.JebCore").getMethod("version").invoke(null); version = v == null ? null : String.valueOf(v); } catch (Throwable ignored) {}
        return version != null ? component("ready", "JEB " + version + pin) : component("starting", "JEB on the classpath; the core starts with the first target" + pin);
    }

    /** The observation's body — exactly the server's fields. The status it reads is computed with a
     *  3 s deadline (it touches the workspace's monitor and probes four services): a slow one falls
     *  back to the last snapshot rather than making the observation stale (fix-review M6). */
    Map<String, Object> buildObservation() {
        Map<String, Object> st;
        java.util.concurrent.Future<Map<String, Object>> f = probes.submit(this::status);
        try { st = f.get(3, java.util.concurrent.TimeUnit.SECONDS); }
        catch (Exception e) {
            f.cancel(true);
            st = lastStatus;
            if (st == null) { Log.warn("course observe", "status not computed in time and no snapshot yet: " + e, null); return minimalObservation(); }
            Log.warn("course observe", "status slow (" + e.getClass().getSimpleName() + ") — observing from the last snapshot", null);
        }
        Map<String, Object> obs = new LinkedHashMap<>();
        obs.put("sequence", 0);   // set by observe()
        obs.put("runtime_revision", Course.runtimeRevision() == null ? "0".repeat(40) : Course.runtimeRevision());
        obs.put("package_sha256", Course.packageSha256() == null ? "0".repeat(64) : Course.packageSha256());
        Map<?, ?> tgt = (Map<?, ?>) st.get("target");
        Map<?, ?> res = tgt.get("resolution") instanceof Map<?, ?> r ? r : Map.of();
        String tsha = res.get("sha256") instanceof String s && s.matches("[0-9a-f]{64}") ? s : "0".repeat(64);
        obs.put("target_sha256", tsha);
        Map<String, Object> comps = new LinkedHashMap<>();
        String pin = Course.runtimeRevision() == null ? "; runtime unpinned (development checkout)" : "";
        comps.put("engine", engineComponent(pin));
        String tstate = String.valueOf(tgt.get("state"));
        comps.put("target", tgt.get("open") == null ? component("unavailable", "no target open")
                : "loading".equals(tstate) ? component("starting", "analysing " + tgt.get("name"))
                : "error".equals(tstate) ? component("unavailable", "analysis failed: " + tgt.get("error"))
                : "mismatch".equals(res.get("match")) ? component("blocked", "the open bytes are not the course target: " + res.get("reason"))
                : "match".equals(res.get("match")) ? component("ready", tgt.get("name") + " = " + res.get("manifestArtifact"))
                : component("degraded", "open, bytes unchecked: " + res.get("reason")));
        Map<?, ?> cap = (Map<?, ?>) st.get("capture");
        Map<?, ?> deps = (Map<?, ?>) st.get("dependencies");
        int pending = cap.get("pending") instanceof Number n ? n.intValue() : 0;
        /* the home's durability: three states — declared and the fsync works, declared but it fails,
           not declared (never "FAILS" for something not yet tried: the probe runs at construction) */
        String home = Boolean.TRUE.equals(cap.get("homeDurable")) ? "durable (declared, fsync works)" : Course.captureDurableDeclared() ? (String.valueOf(cap.get("homeDurableWhy")).contains("checked at") ? "declared durable, fsync not yet checked" : "declared durable but the directory fsync FAILS") : "not declared durable";
        comps.put("capture", "off".equals(cap.get("mode")) ? component("degraded", "capture off — journal only")
                : !"ok".equals(deps.get("store")) ? component("strict".equals(cap.get("mode")) ? "blocked" : "degraded", "store " + deps.get("store") + ", " + pending + " pending")
                : pending > 0 || Boolean.TRUE.equals(cap.get("parked")) ? component("degraded", pending + " record(s) pending export")
                : component("ready", cap.get("mode") + " capture, exports acknowledged, home " + home));
        Map<?, ?> model = (Map<?, ?>) st.get("model");
        String ms = String.valueOf(model.get("state"));
        comps.put("model", component(switch (ms) { case "ready" -> "ready"; case "blocked" -> "blocked"; case "degraded" -> "degraded"; default -> "unavailable"; }, String.valueOf(model.get("summary"))));
        comps.put("classroom", activity.reachable() ? component("ready", "activity " + activity.current().id() + " rev " + activity.current().revision())
                : Course.classroomUrl().isEmpty() ? component("unavailable", "no classroom context configured") : component("degraded", "context not read: " + activity.lastError()));
        obs.put("components", comps);
        int[] mc = missionCounts();
        obs.put("active_missions", Math.min(mc[0], 1_000_000));
        obs.put("open_streams", Math.max(0, Math.min(streams.get(), 1_000_000)));
        obs.put("pending_capture", Math.min(pending, 1_000_000));
        obs.put("policy_revision", Math.max(0, policy.policyRevision()));
        obs.put("fenced", policy.access() != CoursePolicy.Access.ENABLED);
        obs.put("cancellation_unconfirmed", mc[1] > 0);
        return obs;
    }

    /** The observation when no status could be computed at all: every component unavailable, said so. */
    private Map<String, Object> minimalObservation() {
        Map<String, Object> obs = new LinkedHashMap<>();
        obs.put("sequence", 0);
        obs.put("runtime_revision", Course.runtimeRevision() == null ? "0".repeat(40) : Course.runtimeRevision());
        obs.put("package_sha256", Course.packageSha256() == null ? "0".repeat(64) : Course.packageSha256());
        obs.put("target_sha256", "0".repeat(64));
        Map<String, Object> comps = new LinkedHashMap<>();
        for (String k : List.of("engine", "target", "capture", "model", "classroom")) comps.put(k, component("unavailable", "the room's status could not be computed in time"));
        obs.put("components", comps);
        int[] mc = missionCounts();
        obs.put("active_missions", Math.min(mc[0], 1_000_000)); obs.put("open_streams", Math.max(0, Math.min(streams.get(), 1_000_000))); obs.put("pending_capture", 0);
        obs.put("policy_revision", Math.max(0, policy.policyRevision())); obs.put("fenced", policy.access() != CoursePolicy.Access.ENABLED); obs.put("cancellation_unconfirmed", mc[1] > 0);
        return obs;
    }

    public List<Object> describeForBoot() { return List.of(Course.describe()); }
}
