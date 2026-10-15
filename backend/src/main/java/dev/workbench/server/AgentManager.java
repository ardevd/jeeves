package dev.workbench.server;

import dev.workbench.registry.OpException;
import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Agent orchestration (AGENTS_SPEC §2/§5): missions, write-scope enforcement,
 * the review queue, the activity stream.
 *
 * - llm-actor mutations on write ops become PROPOSALS here (applied:false),
 *   never silent writes. Humans settle via agent.review.
 * - Write-scope is enforced server-side (MCP_SPEC §3): the Dispatcher calls
 *   {@link #checkScope} for every mutating op by an llm actor; a mission-scoped
 *   agent acting outside its scope gets E_SCOPE and a BLOCKED activity row.
 *   llm actors without a registered mission (e.g. raw MCP clients) are allowed
 *   to propose — nothing applies without human review anyway.
 * - Scripted missions (mode "scripted") run the deterministic "triage-package
 *   <prefix>" loop: walk the tree, decompile a few classes, emit rename/tag
 *   proposals with confidence + rationale — the full loop without an LLM.
 * - Mode "claude" spawns headless `claude -p` with an MCP config pointing at
 *   mcp/workbench_mcp.py (old Jeeves Agent.java pattern), when the CLI exists.
 */
public final class AgentManager {

    /** Mission record (AGENTS_SPEC §2). Rule: an activity event of kind
     *  "blocked" is the ONLY thing that sets {@link #reason} (act() captures
     *  it), so every path that parks a mission in state=blocked must emit one —
     *  spawn-time refusals and the claude watcher's non-zero exit alike
     *  (fix-review reverse r1 H2/L8). */
    public static final class Mission {
        public String id, goal, mode, model, scope, agentId, state = "queued";
        public String provider, providerLabel;              // multi-LLM: registry row backing the model
        public String depth;    /* quick|deep|null — the reversing brief the prompt carries
                                   (llm.Depth); null = the goal text is the whole brief */
        public String effort;   /* low|medium|high|xhigh|max|null — the claude CLI's --effort /
                                   an http provider's reasoning_effort; null = the provider's own */
        public int maxTurns;    /* an explicit turn cap (task settings / agent.spawn turns); 0 =
                                   the depth's default per mode (llm.Depth) */
        public String target;   /* the target id the mission is PINNED to — the spawn's own,
                                   else the active one at spawn time; every tool call the http
                                   loop / the MCP bridge makes carries it, so a chip click
                                   mid-mission cannot re-aim the agent (fix-review reverse r1
                                   M2). null = no target was open (a map-only mission) */
        public String project;  /* the project the mission belongs to (its pinned target's, else the
                                   active one at spawn) — what scope "project" means (batch 2) */
        public String reason;   /* WHY a blocked mission blocked — act() sets it from the
                                   blocked event's text so the card/inspector can show it;
                                   the activity row alone scrolls away (agents P0.3) */
        public boolean local;
        public int budget, tokensUsed;      /* tokensUsed is COST-weighted (cache hits at 1/10); the raw count and the hits ride beside it */
        public long tokensRaw, cacheHitTokens;
        public volatile int budgetNeeded;   /* >0 while paused at the budget ceiling: the raise the next turn needs (AgentLoop.pauseAtCeiling) */
        public volatile int budgetFloor;    /* the least budget that lets the loop send the next request at all (tokensUsed + prompt estimate + 1) */
        public volatile int turnCap;        /* the effective turn cap the http loop runs under (explicit maxTurns or the depth default) */
        public volatile int turnsDone;      /* turns completed so far (http loop) */
        public volatile int turnsNeeded;    /* >0 while paused AT the turn cap: the raise suggested (AgentLoop.pauseAtTurnCap) */
        public volatile boolean dismissed;  /* removed from the view by the user (agent.dismiss) — the record stays */
        /** WHY the run ended or stands where it does, in words the card shows: "final answer after 6
         *  turns", "stopped by the user", "provider failure: …", "two reasoning-only answers in a
         *  row …" (AgentLoop sets it). */
        public volatile String stopReason;
        /** The mission's own cap on one answer's tokens (0 = the loop's default); the endpoint's cap
         *  (Provider.maxOutputTokens) applies too. */
        public int maxOutputTokens;
        public long startedAt;
        public final List<Object> progress = new ArrayList<>();
        /** Per-turn transcripts (http loop) — the reasoning / text / tool calls the feed rows
         *  digest; in-memory like the mission itself. Capped so a runaway mission stays bounded. */
        public final List<Map<String, Object>> turns = new ArrayList<>();
        public volatile boolean pauseRequested, stopRequested;
        /** cancellationUnconfirmed stays true until the loop's thread has actually returned: an
         *  upstream request may still be in flight, and "stopped" is not said before. */
        public volatile boolean cancellationUnconfirmed;

        static Mission fromMap(Map<?, ?> r) {
            Mission m = new Mission();
            m.id = str(r.get("id")); m.goal = str(r.get("goal")); m.mode = str(r.get("mode")); m.model = str(r.get("model"));
            m.scope = str(r.get("scope")); m.depth = str(r.get("depth")); m.effort = str(r.get("effort"));
            m.maxTurns = num(r.get("maxTurns")); m.target = str(r.get("target")); m.project = str(r.get("project"));
            m.agentId = str(r.get("agentId")); m.state = str(r.get("state")); m.reason = str(r.get("reason"));
            m.budget = num(r.get("budget")); m.tokensUsed = num(r.get("tokensUsed"));
            m.tokensRaw = r.get("tokensRaw") instanceof Number n1 ? n1.longValue() : 0L; m.cacheHitTokens = r.get("cacheHitTokens") instanceof Number n2 ? n2.longValue() : 0L;
            m.startedAt = r.get("startedAt") instanceof Number n ? n.longValue() : 0L;
            m.provider = str(r.get("provider")); m.providerLabel = str(r.get("providerLabel"));
            m.local = Boolean.TRUE.equals(r.get("local"));
            m.turnCap = num(r.get("turnCap")); m.turnsDone = num(r.get("turnsDone"));
            m.dismissed = Boolean.TRUE.equals(r.get("dismissed"));
            m.stopReason = str(r.get("stopReason")); m.maxOutputTokens = num(r.get("maxOutputTokens"));
            if (r.get("progress") instanceof List<?> p) m.progress.addAll(p);
            return m;
        }
        private static String str(Object o) { return o == null ? null : String.valueOf(o); }
        private static int num(Object o) { return o instanceof Number n ? n.intValue() : 0; }

        Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", id);
            m.put("goal", goal);
            m.put("mode", mode);
            m.put("model", model);
            m.put("scope", scope);
            if (depth != null) m.put("depth", depth);
            if (effort != null) m.put("effort", effort);
            if (maxTurns > 0) m.put("maxTurns", maxTurns);
            if (target != null) m.put("target", target);
            if (project != null) m.put("project", project);
            m.put("agentId", agentId);
            m.put("state", state);
            if (reason != null) m.put("reason", reason);
            m.put("budget", budget);
            m.put("tokensUsed", tokensUsed);
            if (tokensRaw > 0) { m.put("tokensRaw", tokensRaw); m.put("cacheHitTokens", cacheHitTokens); }
            if (budgetNeeded > 0) { m.put("budgetNeeded", budgetNeeded); m.put("budgetFloor", budgetFloor); }
            if (turnCap > 0) m.put("turnCap", turnCap);
            m.put("turnsDone", turnsDone);
            if (turnsNeeded > 0) m.put("turnsNeeded", turnsNeeded);
            if (stopReason != null) m.put("stopReason", stopReason);
            if (maxOutputTokens > 0) m.put("maxOutputTokens", maxOutputTokens);
            /* the state as a workflow names it (queued/running/paused/cancelled/failed/completed);
               `state` keeps the workbench's own vocabulary that every client and record key on */
            /* a stopped mission whose thread has not returned is DRAINING (cancellation_unconfirmed), never
               "cancelled": the upstream may still be answering, and the capture records whatever comes */
            m.put("displayState", "stopped".equals(state) && cancellationUnconfirmed ? "draining" : switch (String.valueOf(state)) {
                case "done" -> "completed"; case "stopped" -> "cancelled"; case "blocked", "interrupted" -> "failed"; default -> String.valueOf(state);
            });
            if (cancellationUnconfirmed) m.put("cancellationUnconfirmed", true);
            if (dismissed) m.put("dismissed", true);
            m.put("startedAt", startedAt);
            m.put("progress", new ArrayList<>(progress));
            if (provider != null) m.put("provider", provider);
            if (providerLabel != null) m.put("providerLabel", providerLabel);
            m.put("local", local);
            return m;
        }
    }

    private final Workspace ws;
    private final File sidecarDir;   /* claude-mode mission logs live under it (missions/) — not the JVM temp dir (r2 R2-13) */
    private final dev.workbench.llm.TaskSettings tasks;   /* the built-in tasks' model/effort/caps (tasks.json) */
    private final EventBus bus;
    /** Per-PROJECT proposal queues (MCP_SPEC §3: proposals are saved with the project),
        keyed on the project name, created lazily from projects/<slug>/proposals.json. */
    private final Map<String, ProposalStore> proposalStores = new LinkedHashMap<>();
    private final Map<String, Mission> missions = new LinkedHashMap<>();
    private final List<Map<String, Object>> activity = new ArrayList<>();
    private final Map<String, Process> procs = new ConcurrentHashMap<>();
    private final dev.workbench.llm.KeyStore llmKeys;
    private final dev.workbench.llm.LlmRegistry llm;
    private dev.workbench.registry.Dispatcher dispatcher;   // set post-construction (WorkbenchServer)
    private volatile String followed;   /* watcher/loop threads release it, HTTP threads write it (review) */
    private int agentSeq, missionSeq;
    /* ---- mission records are saved for good, per project (user rule): projects/<slug>/missions/<id>.json
       holds the mission, its turn transcripts and its activity rows; written debounced off the
       monitor after every change, immediately at a terminal state, and loaded back when the
       project opens (a mission that was running when the server died comes back "interrupted").
       Ids are seeded past every archived one so a new mint never collides with a record. */
    private final java.util.concurrent.ScheduledExecutorService persistPool =
            java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> { Thread t = new Thread(r, "mission-persist"); t.setDaemon(true); return t; });
    private final java.util.Map<String, java.util.concurrent.ScheduledFuture<?>> persistPending = new java.util.concurrent.ConcurrentHashMap<>();
    private boolean seqsSeeded;
    private static final int ACTIVITY_CAP = 500;

    public AgentManager(Workspace ws, EventBus bus, File sidecarDir) {
        this.ws = ws;
        this.bus = bus;
        this.sidecarDir = sidecarDir;
        this.tasks = new dev.workbench.llm.TaskSettings(sidecarDir);
        this.llmKeys = new dev.workbench.llm.KeyStore(sidecarDir);
        this.llm = new dev.workbench.llm.LlmRegistry(sidecarDir, llmKeys);
        this.reviewPolicy = new ReviewPolicy(sidecarDir);
    }

    /** review gate or direct apply — the application-side switch (review.policy / review.configure) */
    private final ReviewPolicy reviewPolicy;
    public ReviewPolicy reviewPolicy() { return reviewPolicy; }

    /** The ACTIVE project's queue — what the PROPS ribbon, the review queue and agent.review see. */
    public ProposalStore proposals() throws OpException {
        String name = ws.activeProjectName();
        if (name == null)
            throw OpException.notFound("no project open — proposals are project-scoped", "open a project or a target first");
        return proposalsFor(name);
    }

    public ProposalStore proposalsFor(String projectName) {
        File dir = ws.projectDir(projectName);   // resolved BEFORE the cache lock (Workspace → stores order)
        synchronized (proposalStores) {
            ProposalStore s = proposalStores.get(projectName);
            if (s == null) {
                s = new ProposalStore(dir);
                proposalStores.put(projectName, s);
            }
            return s;
        }
    }

    /** The queue a target's proposals belong to: its project's, else the active project's,
        else null (no project open at all). */
    private ProposalStore proposalsForTarget(String rawTarget) {
        String project = null;
        try {
            String tid = ws.resolveTargetIdOrActive(rawTarget);
            project = ws.projectNameOfTarget(tid);
        } catch (OpException ignored) {}
        if (project == null) project = ws.activeProjectName();
        return project == null ? null : proposalsFor(project);
    }

    private ProposalStore activeProposalsOrNull() {
        String name = ws.activeProjectName();
        return name == null ? null : proposalsFor(name);
    }

    /* ONE proposal id sequence across projects (batch 2): initialised lazily from the highest id
       in proposals.seq and in every project queue on disk (a not-yet-loaded project's queue
       included), persisted after every mint. Per-store sequences collided ("pp1" in two projects)
       and agent.review, which takes an id alone, settled a look-alike under the wrong project. */
    private long proposalSeq = -1;

    private synchronized String nextProposalId() {
        if (proposalSeq < 0) {
            long max = 0;
            File seqFile = new File(sidecarDir, "proposals.seq");
            try {
                if (seqFile.isFile()) max = Long.parseLong(java.nio.file.Files.readString(seqFile.toPath()).trim());
            } catch (Exception ignored) {}
            File[] dirs = new File(sidecarDir, "projects").listFiles(File::isDirectory);
            if (dirs != null) for (File d : dirs) max = Math.max(max, maxProposalIdIn(new File(d, "proposals.json")));
            max = Math.max(max, maxProposalIdIn(new File(sidecarDir, "proposals.json")));   // a legacy file not yet folded
            proposalSeq = max;
        }
        proposalSeq++;
        dev.workbench.registry.SidecarIO.writeAtomic(new File(sidecarDir, "proposals.seq"), Long.toString(proposalSeq), "proposals.seq");   // atomic: a torn seq restarts the sequence (b2-r1 L2)
        return "pp" + proposalSeq;
    }

    private static long maxProposalIdIn(File f) {
        if (!f.isFile()) return 0;
        long max = 0;
        try {
            Object p = dev.workbench.registry.Json.parse(java.nio.file.Files.readString(f.toPath()));
            if (p instanceof List<?> l)
                for (Object o : l)
                    if (o instanceof Map<?, ?> m && String.valueOf(m.get("id")).startsWith("pp"))
                        try { max = Math.max(max, Long.parseLong(String.valueOf(m.get("id")).substring(2))); }
                        catch (NumberFormatException ignored) {}
        } catch (Exception ignored) {}   // a torn queue is the store's business (it parks it); the seq only needs a floor
        return max;
    }

    /** Drop a project's cached queue (after the legacy fold, or a close). */
    public void evictProposals(String projectName) {
        synchronized (proposalStores) { proposalStores.remove(projectName); }
    }
    public dev.workbench.llm.LlmRegistry llm() { return llm; }
    public dev.workbench.llm.TaskSettings tasks() { return tasks; }

    /** llm.remove: the provider, its key, and every task pin that named it
     *  (settings r1 M2 — a dangling pin blocked every ⚡ Reverse while the
     *  window claimed the default) */
    public synchronized Map<String, Object> removeProvider(String id) throws OpException {
        if (llm.isDegraded() || llmKeys.isDegraded() || tasks.isDegraded())   // a multi-store composite refuses up front, like rename (§5, review r-f31a574 L11)
            throw OpException.busy("a configuration store is degraded (its on-disk evidence is parked) — remove refused until it is repaired", "");
        Map<String, Object> out = new LinkedHashMap<>(llm.remove(id));
        List<String> reset = tasks.forgetProvider(id == null ? "" : id.trim());
        if (!reset.isEmpty()) out.put("tasksReset", reset);
        return out;
    }
    /** llm.configure — routed through THIS monitor only so it serializes with a
     *  concurrent rename of the same provider (the Dispatcher does not serialize
     *  ops; llm-config design §6.1). The registry itself stays pin-blind. */
    public synchronized Map<String, Object> configureProvider(String id, String baseUrl, String model,
            String label, List<String> models, Boolean local, Map<String, Object> extraParams,
            Boolean create, String auth, Integer contextTokens) throws OpException {
        return llm.configure(id, baseUrl, model, label, models, local, extraParams, create, auth, contextTokens);
    }

    public Map<String, Object> configureProviderWire(String id, String reasoningField, String reasoningReplay, Boolean streamOnly, Integer maxOutputTokens) throws OpException {
        return llm.configureWire(id, reasoningField, reasoningReplay, streamOnly, maxOutputTokens);
    }
    /** llm.rename — the multi-store composite (keys → registry → task pins), under
     *  this monitor; the stores' own monitors are never nested (design §6.2). */
    public synchronized Map<String, Object> renameProvider(String id, String newId) throws OpException {
        return dev.workbench.llm.LlmComposites.rename(llm, llmKeys, tasks, id, newId);
    }
    /** llm.models {id}: discover the provider's models from its endpoint. The baseUrl
     *  and key are read UNDER the monitor (fast), then the HTTP GET runs OUTSIDE any
     *  lock — a slow/hung endpoint must never block spawn/configure/rename. The key
     *  authenticates the call and is never returned (KeyStore stays write-only to ops). */
    public Map<String, Object> discoverProviderModels(String id) throws OpException {
        String baseUrl, key, pid;
        synchronized (this) {
            dev.workbench.llm.LlmRegistry.Provider pr = llm.get(id == null ? "" : id.trim());
            if (pr == null) throw OpException.notFound("provider not found: " + id, "");
            if (!"http".equals(pr.driver))
                throw OpException.unsupported("model discovery needs an OpenAI-compatible http endpoint — "
                        + pr.id + " is a " + pr.driver + " provider", "");
            if (pr.baseUrl == null || pr.baseUrl.isBlank())
                throw OpException.syntax("provider " + pr.id + " has no baseUrl to query for models", "");
            if (pr.needsKey && !llmKeys.has(pr.id))
                throw OpException.syntax("add a key for " + pr.id + " first — model discovery authenticates with it", "");
            baseUrl = pr.baseUrl; key = llmKeys.get(pr.id); pid = pr.id;
        }
        List<String> models;
        try {
            models = dev.workbench.llm.AgentLoop.discoverModels(baseUrl, key);
        } catch (Exception e) {
            throw OpException.io("could not fetch models from " + pid + " — " + e.getMessage(), "");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", pid);
        out.put("models", models);
        return out;
    }
    public dev.workbench.llm.KeyStore llmKeys() { return llmKeys; }
    public void setDispatcher(dev.workbench.registry.Dispatcher d) { this.dispatcher = d; }
    public dev.workbench.registry.Dispatcher dispatcher() { return dispatcher; }

    // ------------------------------------------------------------ activity stream

    private void act(String agentId, String kind, String text) { act(agentId, kind, text, null, 0); }
    /** A row tagged with the mission it belongs to and (inside the http loop) its turn, so the
     *  feed can group rows per turn and open that turn's transcript (agent.turn). */
    private void act(String agentId, String kind, String text, String missionId, int turn) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("ts", java.time.Instant.now().toString());
        row.put("agent", agentId);
        row.put("kind", kind);
        row.put("text", text);
        if (missionId != null) row.put("mission", missionId);
        if (turn > 0) row.put("turn", turn);
        /* a blocked event's text IS the mission's reason — capture it at the one
           choke point so no blocked transition can forget it (agents P0.3) */
        if ("blocked".equals(kind)) {
            synchronized (this) {
                for (Mission m : missions.values())
                    if (agentId.equals(m.agentId)) { m.reason = text; break; }
            }
        }
        synchronized (activity) {
            activity.add(row);
            while (activity.size() > ACTIVITY_CAP) activity.remove(0);
        }
        bus.publish("activity", row);
        Mission owner = null;
        synchronized (this) { for (Mission m : missions.values()) if (agentId != null && agentId.equals(m.agentId)) { owner = m; break; } }
        if (owner != null) schedulePersist(owner);
    }

    public synchronized List<Object> activityList(String agent, String kinds, int offset, int count) {
        List<Object> out = new ArrayList<>();
        List<String> kindSet = kinds == null || kinds.isBlank() ? List.of() : List.of(kinds.split(","));
        synchronized (activity) {
            for (Map<String, Object> row : activity) {
                if (agent != null && !agent.isBlank() && !agent.equals(row.get("agent"))) continue;
                if (!kindSet.isEmpty() && !kindSet.contains(String.valueOf(row.get("kind")))) continue;
                out.add(new LinkedHashMap<>(row));
            }
        }
        int from = Math.max(0, out.size() - (offset + (count <= 0 ? 100 : count)));
        int to = Math.max(from, out.size() - offset);
        return out.subList(Math.min(from, out.size()), Math.min(to, out.size()));
    }

    // ------------------------------------------------------------ turn transcripts
    private static final int TURNS_CAP = 400;

    /** Upsert by turn number: a turn parked at the budget ceiling records a partial transcript
     *  (outcome "paused") and the completed turn replaces it after the raise. */
    private void recordTurn(Mission m, Map<String, Object> rec) {
        synchronized (m.turns) {
            Object n = rec.get("turn");
            for (int i = m.turns.size() - 1; i >= 0; i--)
                if (n != null && n.equals(m.turns.get(i).get("turn"))) { m.turns.set(i, new LinkedHashMap<>(rec)); return; }
            m.turns.add(new LinkedHashMap<>(rec));
            while (m.turns.size() > TURNS_CAP) m.turns.remove(0);
        }
        schedulePersist(m);
    }

    // ------------------------------------------------------------ mission archive (per project)
    private static boolean terminal(String state) {
        return "done".equals(state) || "blocked".equals(state) || "stopped".equals(state) || "interrupted".equals(state);
    }

    /** Debounced write: 1 s after the last change, at once for a terminal state. */
    private void schedulePersist(Mission m) {
        if (m == null || m.project == null) return;
        long delay = terminal(m.state) ? 0 : 1_000;
        java.util.concurrent.ScheduledFuture<?> prev = persistPending.put(m.id,
                persistPool.schedule(() -> persistMission(m), delay, java.util.concurrent.TimeUnit.MILLISECONDS));
        if (prev != null) prev.cancel(false);
    }

    private File missionFile(Mission m) {
        File dir = ws.projectDir(m.project);   // resolved outside this manager's monitor (Workspace → stores order)
        return dir == null ? null : new File(new File(dir, "missions"), m.id + ".json");
    }

    /** The snapshot is taken under the monitors; the write happens off them. */
    private void persistMission(Mission m) {
        try {
            persistPending.remove(m.id);
            File f = missionFile(m);
            if (f == null) return;
            Map<String, Object> rec = new LinkedHashMap<>();
            List<Object> turns;
            List<Object> rows = new ArrayList<>();
            synchronized (this) { rec.put("mission", m.toMap()); }
            synchronized (m.turns) { turns = new ArrayList<>(m.turns); }
            synchronized (activity) {
                for (Map<String, Object> row : activity)
                    if (m.agentId != null && m.agentId.equals(row.get("agent"))) rows.add(new LinkedHashMap<>(row));
            }
            rec.put("turns", turns);
            rec.put("activity", rows);
            rec.put("savedAt", java.time.Instant.now().toString());
            f.getParentFile().mkdirs();
            dev.workbench.registry.SidecarIO.writeAtomic(f, dev.workbench.registry.Json.write(rec), "mission");
        } catch (Exception e) {
            System.err.println("[agents] mission persist failed for " + m.id + ": " + e);
        }
    }

    /** Every project's archive, once, before the first mint: ids must clear every record on disk. */
    private synchronized void seedSeqsFromArchive() {
        if (seqsSeeded) return;
        seqsSeeded = true;
        File[] dirs = new File(sidecarDir, "projects").listFiles(File::isDirectory);
        if (dirs == null) return;
        for (File d : dirs) {
            File[] files = new File(d, "missions").listFiles((x, n) -> n.endsWith(".json"));
            if (files == null) continue;
            for (File f : files) {
                try {
                    Object parsed = dev.workbench.registry.Json.parse(java.nio.file.Files.readString(f.toPath()));
                    if (!(parsed instanceof Map<?, ?> rec) || !(rec.get("mission") instanceof Map<?, ?> mm)) continue;
                    missionSeq = Math.max(missionSeq, numSuffix(String.valueOf(mm.get("id")), "m"));
                    agentSeq = Math.max(agentSeq, numSuffix(String.valueOf(mm.get("agentId")), "agent-"));
                } catch (Exception ignored) {}
            }
        }
    }

    private static int numSuffix(String id, String prefix) {
        if (id == null || !id.startsWith(prefix)) return 0;
        try { return Integer.parseInt(id.substring(prefix.length())); } catch (NumberFormatException e) { return 0; }
    }

    /** project.open: the project's saved missions come back — cards, transcripts and rows — as
     *  records. A mission that was live when the server died reads "interrupted" (its loop is
     *  gone; the record stays). Ids already live (the project was merely switched) are skipped. */
    public void loadArchivedMissions(String projectName, File projectDir) {
        if (projectDir == null) return;
        File[] files = new File(projectDir, "missions").listFiles((x, n) -> n.endsWith(".json"));
        if (files == null || files.length == 0) return;
        java.util.Arrays.sort(files, java.util.Comparator.comparing(File::getName));
        List<Map<String, Object>> loadedRows = new ArrayList<>();
        int loaded = 0;
        synchronized (this) {
            seedSeqsFromArchive();
            for (File f : files) {
                try {
                    Object parsed = dev.workbench.registry.Json.parse(java.nio.file.Files.readString(f.toPath()));
                    if (!(parsed instanceof Map<?, ?> rec) || !(rec.get("mission") instanceof Map<?, ?> mm)) continue;
                    String id = String.valueOf(mm.get("id"));
                    if (missions.containsKey(id)) continue;
                    Mission m = Mission.fromMap(mm);
                    if (m.project == null) m.project = projectName;
                    if (!terminal(m.state)) { m.state = "interrupted"; m.reason = "the server restarted while this mission was " + mm.get("state") + " — its record is kept, the run cannot continue"; }
                    if (rec.get("turns") instanceof List<?> ts) for (Object t : ts) if (t instanceof Map<?, ?> tm) { Map<String, Object> copy = new LinkedHashMap<>(); tm.forEach((k, v) -> copy.put(String.valueOf(k), v)); m.turns.add(copy); }
                    if (rec.get("activity") instanceof List<?> rs) for (Object r : rs) if (r instanceof Map<?, ?> rm) { Map<String, Object> copy = new LinkedHashMap<>(); rm.forEach((k, v) -> copy.put(String.valueOf(k), v)); loadedRows.add(copy); }
                    missions.put(id, m);
                    loaded++;
                } catch (Exception e) {
                    dev.workbench.registry.SidecarIO.readFailed(f, "mission", e);
                }
            }
        }
        if (!loadedRows.isEmpty()) synchronized (activity) {
            activity.addAll(0, loadedRows);   // history first, the live tail stays the tail
            while (activity.size() > ACTIVITY_CAP) activity.remove(0);
        }
        if (loaded > 0) System.err.println("[agents] " + loaded + " saved mission record(s) loaded for project " + projectName);
    }

    /** A mission by id, or by its agent id (the feed rows carry the agent). */
    private synchronized Mission missionRef(String ref) throws OpException {
        Mission m = missions.get(ref);
        if (m == null) for (Mission c : missions.values()) if (ref != null && ref.equals(c.agentId)) { m = c; break; }
        if (m == null) throw OpException.notFound("no such mission: " + ref, "");
        return m;
    }

    /** agent.turns — one summary per recorded turn (the full record is agent.turn). */
    public List<Object> turnList(String ref) throws OpException {
        Mission m = missionRef(ref);
        List<Object> out = new ArrayList<>();
        synchronized (m.turns) {
            for (Map<String, Object> t : m.turns) {
                Map<String, Object> s = new LinkedHashMap<>();
                for (String k : List.of("turn", "ts", "ms", "tokens", "tokensUsed", "outcome")) if (t.get(k) != null) s.put(k, t.get(k));
                Object calls = t.get("calls");
                int n = calls instanceof List<?> l ? l.size() : 0, ok = 0;
                if (calls instanceof List<?> l) for (Object c : l) if (c instanceof Map<?, ?> cm && Boolean.TRUE.equals(cm.get("ok"))) ok++;
                s.put("calls", n); s.put("ok", ok);
                s.put("reasoning", t.get("reasoning") instanceof String r ? r.length() : 0);
                s.put("text", t.get("text") instanceof String r ? r.length() : 0);
                out.add(s);
            }
        }
        return out;
    }

    /** agent.turn — the full transcript of one turn. */
    public Map<String, Object> turnGet(String ref, int turn) throws OpException {
        Mission m = missionRef(ref);
        synchronized (m.turns) {
            for (Map<String, Object> t : m.turns) if (t.get("turn") instanceof Number n && n.intValue() == turn) return new LinkedHashMap<>(t);
        }
        boolean live = "running".equals(m.state) || "paused".equals(m.state) || "queued".equals(m.state);
        throw OpException.notFound(!"http".equals(m.mode)
                ? "mission " + m.id + " records no transcripts — a " + m.mode + "-mode mission's rows are its MCP calls (only http-mode missions record turns)"
                : live ? "turn " + turn + " of mission " + m.id + " is still in progress (" + m.state + ") — its transcript lands when the turn ends"
                : "mission " + m.id + " has no recorded turn " + turn, "");
    }

    // ------------------------------------------------------------ missions

    /** agent.revert {id}: undo every rename and comment the mission wrote, newest first — the
     *  human's control over an agent that applies directly (John, 2026-09-18: "revert, not
     *  approve"). Refused while the mission runs (its reads would change under it). Tags and map
     *  verdicts are not History entries and stay. Undone entries can be redone from History. */
    public Map<String, Object> revertMission(String id) throws OpException {
        Mission m;
        synchronized (this) { m = missions.get(id); }
        if (m == null) throw OpException.notFound("no such mission: " + id, "");
        if ("running".equals(m.state) || "queued".equals(m.state))
            throw OpException.busy("mission " + id + " is " + m.state + " — pause or stop it before reverting its writes", "id=" + id);
        int reverted = 0;
        List<Object> failed = new ArrayList<>();
        List<String> tids = new ArrayList<>();
        List<String> open = ws.openTargetIds();
        if (m.target != null && open.contains(m.target)) tids.add(m.target);
        else tids.addAll(open);
        for (String tid : tids) {
            Map<String, Object> r = ws.revertBy(tid, m.agentId);
            reverted += ((Number) r.get("reverted")).intValue();
            @SuppressWarnings("unchecked") List<Object> f = (List<Object>) r.get("failed");
            failed.addAll(f);
        }
        act(m.agentId, "note", "mission " + id + " REVERTED · " + reverted + " write(s) undone"
                + (failed.isEmpty() ? "" : " · " + failed.size() + " could not be undone"), id, 0);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", id);
        out.put("reverted", reverted);
        out.put("failed", failed);
        return out;
    }

    public List<Object> missionList() {
        List<Object> out = new ArrayList<>();
        synchronized (this) {
            for (Mission m : missions.values()) {
                Map<String, Object> row = m.toMap();
                // FOLLOW is server state (agent.follow/unfollow), and a booting client has
                // to paint the switch from something. It rides the mission rows rather than
                // a new op: "following" is a property OF a mission, and follow(id) takes one.
                row.put("following", m.agentId != null && m.agentId.equals(followed));
                out.add(row);
            }
        }
        /* writes: the mission's applied (not undone) renames and comments on its target — what
           REVERT would undo. Counted outside this monitor (the workspace has its own). */
        try {
            List<String> open = ws.openTargetIds();
            for (Object o : out) {
                @SuppressWarnings("unchecked") Map<String, Object> row = (Map<String, Object>) o;
                String tid = row.get("target") == null ? null : String.valueOf(row.get("target"));
                String agent = row.get("agentId") == null ? null : String.valueOf(row.get("agentId"));
                if (agent == null) continue;
                int n = 0;
                for (String t : (tid != null && open.contains(tid)) ? List.of(tid) : open) n += ws.writesBy(t, agent);
                row.put("writes", n);
            }
        } catch (Exception ignored) {}
        return out;
    }

    private Mission requireMission(String id) throws OpException {
        Mission m = missions.get(id);
        if (m == null) throw OpException.notFound("mission not found: " + id,
                "missions: " + missions.keySet());
        return m;
    }

    /** agent.spawn <goal> [--scope] [--model provider:model] [--budget]
     *  [--mode scripted|claude|http] [--depth quick|deep]. Mode is inferred from
     *  the model's provider driver when not given (builtin→scripted, cli→claude,
     *  http→http). Depth (llm.Depth) sets the reversing brief the prompt carries,
     *  the turn cap and — when no budget is given — the budget; the scripted
     *  mode ignores it. Target pins the mission (Mission.target): the given id,
     *  else the active target at spawn time. Effort / turns: the CLI's --effort
     *  and an explicit turn cap. A built-in task (a depth with no explicit
     *  model) runs on the task's configured model, effort and caps
     *  (TaskSettings — the REVERSING AGENT window). */
    public synchronized Map<String, Object> spawn(String goal, String scope, String model,
            int budget, String mode, String depth, String target, String effort, int turns) throws OpException {
        return spawnMission(goal, scope, model, budget, mode, depth, target, effort, turns, 0);
    }

    public synchronized Map<String, Object> spawn(String goal, String scope, String model,
            int budget, String mode, String depth, String target, String effort, int turns, int maxOutput) throws OpException {
        return spawnMission(goal, scope, model, budget, mode, depth, target, effort, turns, maxOutput);
    }

    private Map<String, Object> spawnMission(String goal, String scope, String model,
            int budget, String mode, String depth, String target, String effort, int turns, int maxOutput) throws OpException {
        if (maxOutput < 0) throw OpException.syntax("maxOutput is the cap on one answer's tokens (0 = the default)", "maxOutput=" + maxOutput);
        if (goal == null || goal.isBlank()) throw OpException.syntax("agent.spawn needs a goal", "");
        goal = goal.trim();                                              // ONE goal string for every rule below (r2 M4)
        String depthNorm = dev.workbench.llm.Depth.normalize(depth);   // E_SYNTAX before the mission exists
        String effortNorm = dev.workbench.llm.TaskSettings.normalizeEffort(effort);
        if (turns < 0) throw OpException.syntax("turns must be positive (0 = the depth's default)", "turns=" + turns);   // refused like task.configure (r2 LAT-4)
        int turnsCap = turns;
        if (depthNorm != null) {
            dev.workbench.llm.TaskSettings.Entry cfg = tasks.get(dev.workbench.llm.TaskSettings.REVERSE, depthNorm);
            /* the task's settings are ONE unit, applied only when the spawn is
               the built-in routine (no explicit model): a caller naming its own
               model keeps its own caps too — a CLI-scale turn cap on an http
               loop blows the budget (settings r1 M1) */
            if (cfg != null && (model == null || model.isBlank())) {
                model = cfg.model;                                      // the task's model (null = server default)
                if (effortNorm == null) effortNorm = cfg.effort;        // and its effort, unless the caller set one
                if (budget <= 0 && cfg.budget > 0) budget = cfg.budget;
                if (turnsCap <= 0 && cfg.turns > 0) turnsCap = cfg.turns;
            }
        }
        /* an unknown target id is E_NOTFOUND here, before a mission number is
           spent; no target at all (a map-only workspace) pins nothing */
        String pinned = target != null && !target.isBlank() ? ws.resolveTargetIdOrActive(target.trim()) : ws.activeTarget();
        /* the write scope (batch 2, audit decision 3): a blank scope no longer means "anywhere".
           It is the pinned target; with no target but a project, the project; "project" is the
           explicit whole-project opt-in; with neither there is nothing to scope to — refused
           before a mission number is spent. */
        String projectName = pinned != null ? ws.projectNameOfTarget(pinned) : ws.activeProjectName();
        String scopeNorm = scope == null ? "" : scope.trim();
        if (scopeNorm.isBlank()) {
            if (pinned != null) scopeNorm = "target:" + pinned;
            else if (projectName != null) scopeNorm = "project";
            else throw OpException.syntax("agent.spawn needs a target or a project to scope the mission to"
                    + " — open one (target.open / project.open), or pass scope", "scope-default");
        }
        if ("project".equals(scopeNorm) && projectName == null)
            throw OpException.syntax("scope \"project\" needs an open project", "scope=project");
        /* NO implicit provider (user directive 2026-09-09): a mission with no
           model and no explicit mode runs only the scripted triage goal — a
           reversing depth needs the REVERSING AGENT window (task.configure), a
           custom goal a picked provider. The old fallback assumed the claude
           CLI was installed and logged in, which nothing guarantees. Checked
           before a mission number is spent. */
        if ((model == null || model.isBlank()) && (mode == null || mode.isBlank())
                && !goal.startsWith("triage-package"))
            throw OpException.syntax(depthNorm != null
                    ? "the reversing agent's " + depthNorm + " depth has no model — configure it first"
                            + " (Agents view → ⚡ REVERSING AGENT, or task.configure {task: reverse, depth: "
                            + depthNorm + ", model: provider:model})"
                    : "agent.spawn needs a model (provider:model — llm.providers lists them); only"
                            + " \"triage-package <prefix>\" runs without one",
                    /* the detail is a CONTRACT the GUI keys on (reverse.ts): "task-unconfigured"
                       turns the error into a clickable way to the window (settings r2 L9) */
                    depthNorm != null ? "task-unconfigured depth=" + depthNorm : "goal=" + goal);
        Mission m = new Mission();
        seedSeqsFromArchive();
        m.id = "m" + (++missionSeq);
        m.goal = goal;
        m.depth = depthNorm;
        m.effort = effortNorm;
        m.maxTurns = turnsCap;
        m.maxOutputTokens = maxOutput;
        m.target = pinned;
        if (model != null && model.isBlank()) model = null;
        final boolean modelGiven = model != null;            // guard applies to USER models only
        String[] resolved = llm.resolve(model);
        if (mode == null || mode.isBlank()) {
            if (resolved != null) {
                dev.workbench.llm.LlmRegistry.Provider pr = llm.get(resolved[0]);
                m.mode = switch (pr.driver) {
                    case "builtin" -> "scripted";
                    case "cli" -> "cli";           // the Claude Code CLI runner — the mode is named for the TRANSPORT, never "claude" beside the anthropic id (llm-anthropic-vendor B1)
                    default -> "http";
                };
            } else {
                m.mode = goal.startsWith("triage-package") ? "scripted" : "cli";
            }
        } else {
            m.mode = "claude".equals(mode) ? "cli" : mode;   // a client's legacy mode:"claude" is the cli runner (B1)
        }
        if (model == null || model.isBlank())
            model = "scripted".equals(m.mode) ? "builtin:triage" : "claude-code";
        m.model = model;
        if (resolved == null && !modelGiven) resolved = llm.resolve(model);   // the mode-explicit hatch still gets its provider label (settings r3 L3)
        if (resolved != null) {
            dev.workbench.llm.LlmRegistry.Provider pr = llm.get(resolved[0]);
            m.provider = pr.id;
            m.providerLabel = pr.label;
            m.local = pr.local;
        }
        m.scope = scopeNorm;
        m.project = projectName;
        m.budget = budget <= 0 ? dev.workbench.llm.Depth.defaultBudget(m.depth, 200_000) : budget;
        m.agentId = "agent-" + (++agentSeq);
        m.startedAt = System.currentTimeMillis();
        missions.put(m.id, m);
        if (modelGiven && resolved == null) {
            // an explicit --model must resolve to a provider — never silently
            // fall back to another mode (glm52 once spawned as claude this way)
            m.state = "blocked";
            act(m.agentId, "blocked", "unknown provider in --model: " + model
                    + " — expected provider:model (e.g. " + llm.exampleSpec(model)
                    + ") or a bare provider id · registered: " + String.join(", ", llm.ids())
                    + " — see llm.providers");
            return m.toMap();
        }
        act(m.agentId, "note", "mission " + m.id + " spawned: " + m.goal
                + (m.depth == null ? "" : " · depth " + m.depth)
                + (m.effort == null || "scripted".equals(m.mode) ? "" : " · effort " + m.effort)
                + (m.maxTurns > 0 && !"scripted".equals(m.mode) ? " · turns " + m.maxTurns : "")
                + (m.scope.isBlank() ? "" : " · scope " + m.scope)
                + (m.target == null ? "" : " · target " + m.target)
                + ("http".equals(m.mode) && resolved != null
                        ? " · " + m.providerLabel + " (" + resolved[1] + ")" : ""));

        if ("scripted".equals(m.mode)) startScripted(m);
        else if ("cli".equals(m.mode) || "claude".equals(m.mode)) startClaude(m);   // "claude" = the legacy mode name, still accepted (B1)
        else if ("http".equals(m.mode)) startHttp(m, resolved);
        else {
            m.state = "blocked";
            act(m.agentId, "blocked", "unknown mode: " + m.mode);
        }
        return m.toMap();
    }

    public synchronized Map<String, Object> pause(String id) throws OpException {
        Mission m = requireMission(id);
        if (!"running".equals(m.state))   /* pausing a blocked/done mission would fake followability (review) */
            throw OpException.scope("mission " + id + " is " + m.state + " — only running missions pause", "state=" + m.state);
        m.pauseRequested = true;
        m.state = "paused";
        act(m.agentId, "note", "mission " + id + " paused");
        return m.toMap();
    }

    public synchronized Map<String, Object> resume(String id) throws OpException {
        Mission m = requireMission(id);
        if (!"paused".equals(m.state))
            throw OpException.scope("mission " + id + " is " + m.state + " — only paused missions resume", "state=" + m.state);
        /* paused AT THE BUDGET with the budget still spent: resuming would only re-park it with the
           same message — refuse loudly and name the raise (agent.resume {id, budget}) */
        /* paused AT THE TURN CAP with the cap not raised: same rule as the budget — refuse loudly */
        if (m.turnsNeeded > 0 && m.maxTurns <= m.turnCap)
            throw OpException.scope("mission " + id + " is paused at its turn limit — the mission's own cap (" + m.turnsDone + "/" + m.turnCap
                    + " turns) — raise it first: agent.resume {id, turns: " + m.turnsNeeded + "}", "turnsNeeded=" + m.turnsNeeded);
        if (m.budgetNeeded > 0 && m.budget < m.budgetFloor)
            throw OpException.scope("mission " + id + " is paused at its budget — the mission's own TOKEN ceiling, not your provider balance (" + m.tokensUsed + "/" + m.budget
                    + " tokens, next prompt ≈ " + (m.budgetFloor - 1 - m.tokensUsed) + ") — raise it first: the next turn needs ≥ " + m.budgetNeeded
                    + " (agent.resume {id, budget: " + m.budgetNeeded + "})", "budgetNeeded=" + m.budgetNeeded);
        m.pauseRequested = false;
        m.state = "running";
        act(m.agentId, "note", "mission " + id + " resumed");
        return m.toMap();
    }

    public synchronized Map<String, Object> stop(String id) throws OpException {
        Mission m = requireMission(id);
        m.stopRequested = true;
        m.state = "stopped";
        if (m.stopReason == null) m.stopReason = "stopped by the user";
        Process p = procs.remove(id);
        if (p != null) p.destroy();
        releaseFollowIfTerminal(m);
        act(m.agentId, "note", "mission " + id + " stopped");
        return m.toMap();
    }

    /** The http loops' threads, by mission id — the loop a stop waits for (a request in flight returns
     *  at once with the interruption recorded as its failure) and what confirms a stop when it ends. */
    private final Map<String, Thread> loopThreads = new java.util.concurrent.ConcurrentHashMap<>();

    /** A mission outside the running/paused set never emits presence again, so a
        follow on it must be RELEASED and the flip broadcast — otherwise clients
        keep "Following agent-N" for a corpse, a fresh browser paints the switch
        ON for a dead mission, and agent.follow refuses to re-establish it
        (review F2). EVERY terminal path funnels here: stop(), the claude process
        watcher, scripted-mission end, and the AgentLoop thread wrap — natural
        deaths (state written outside stop()) are covered exactly like explicit
        ones. */
    synchronized void releaseFollowIfTerminal(Mission m) {
        if (m.state == null || "running".equals(m.state) || "paused".equals(m.state)) return;
        if (m.agentId == null || !m.agentId.equals(followed)) return;
        followed = null;
        Map<String, Object> flip = new LinkedHashMap<>();
        flip.put("following", null);
        bus.publish("presence", flip);
    }

    /** agent.dismiss — the user removes a FINISHED run from the view; the record stays in the project
     *  archive (persisted at once — dismissed missions are terminal). undo brings it back. */
    public synchronized Map<String, Object> dismiss(String id, boolean undo) throws OpException {
        Mission m = requireMission(id);
        if (!undo && !terminal(m.state))
            throw OpException.scope("mission " + id + " is " + m.state + " — stop it before dismissing it", "state=" + m.state);
        m.dismissed = !undo;
        schedulePersist(m);
        return m.toMap();
    }

    /** agent.setturns — raise (or lower, while running) THIS mission's turn cap; the loop reads it
     *  at the cap pause. Future missions are the task settings' business (task.configure {turns}). */
    public synchronized Map<String, Object> setTurns(String id, int turns) throws OpException {
        Mission m = requireMission(id);
        if (turns <= 0) throw OpException.syntax("turns must be positive", "");
        m.maxTurns = turns;
        return m.toMap();
    }

    public synchronized Map<String, Object> setBudget(String id, int budget) throws OpException {
        Mission m = requireMission(id);
        if (budget <= 0) throw OpException.syntax("budget must be positive", "");
        m.budget = budget;
        return m.toMap();
    }

    /** Scope shrink is agent-allowed; expansion requires human confirm (MCP_SPEC §3). */
    public synchronized Map<String, Object> setScope(String id, String scope, boolean confirmed,
            String actor) throws OpException {
        Mission m = requireMission(id);
        String newScope = scope == null ? "" : scope;
        boolean human = "human".equals(EventBus.actorKind(actor));   /* user/human spellings both count */
        if (!human && !isShrink(m.scope, newScope))
            throw OpException.scope("scope expansion requires human confirm",
                    "current=" + m.scope + " requested=" + newScope);
        m.scope = newScope;
        act(m.agentId, "note", "mission " + id + " scope → " + (newScope.isBlank() ? "(none)" : newScope));
        return m.toMap();
    }

    private static boolean isShrink(String oldScope, String newScope) {
        if (newScope == null || newScope.isBlank()) return false;          // clearing = expansion
        if (newScope.startsWith("experiment:") && !newScope.equals(oldScope)) return false;
        if (oldScope == null || oldScope.isBlank()) return true;           // any scope is a shrink of none
        if (newScope.equals(oldScope)) return true;
        if ("project".equals(oldScope)) return true;    // the whole project contains every narrower form
        if ("project".equals(newScope)) return false;   // widening to the whole project needs the human
        /* a shrink extends the old scope AT A BOUNDARY: pkg:com.foo → pkg:com.foo.Bar,
           never pkg:com.foobar (the inPrefix rule, r2 R2-10) */
        return newScope.startsWith(oldScope)
                && (oldScope.endsWith(":") || ".$;/-".indexOf(newScope.charAt(oldScope.length())) >= 0);
    }

    // ------------------------------------------------------------ write-scope enforcement (Dispatcher hook)

    /**
     * Called by the Dispatcher before a MUTATING op by an llm actor. Scope forms:
     *   pkg:<prefix>    — symbol params must lie UNDER the prefix (a package or a
     *                     class): L<prefix>; / L<prefix>$… / L<prefix>/… or the
     *                     dotted forms — never a sibling that merely starts with
     *                     the same letters (inPrefix)
     *   <prefix>        — bare package/namespace prefix; same matching as pkg:
     *   target:<id>     — the op's target param (default: active) must equal it
     *   map:<id>        — only map.* ops on that map
     * An llm actor WITHOUT a mission (raw MCP client) is allowed to propose.
     */
    public void checkScope(String actor, String opName, Map<String, Object> params) throws OpException {
        /* agent-ness by the SHARED definition (EventBus.actorKind): llm / llm:<id> /
           bare agent-N — a bare agent actor used to sail through here as "human"
           while presence counted it as an agent (review F5's split) */
        if (actor == null || !"agent".equals(EventBus.actorKind(actor))) return;
        String agentId = agentIdOf(actor);   /* identical spelling to propose() */
        Mission mission = null;
        for (Mission m : missions.values())
            if (m.agentId.equals(agentId) && !"stopped".equals(m.state)) { mission = m; break; }
        /* agent.* mutations are human-only for EVERY agent actor, missioned or
           not — an unmissioned llm:mcp client used to fall through the
           mission==null return below and could settle its own proposal
           (app-review-opus P0-2). Only mutating ops reach this gate (the
           Dispatcher calls checkScope for those), so agent.proposals /
           agent.activity stay readable. A human driving the MCP bridge
           identifies with WORKBENCH_ACTOR=human:<name>. */
        /* deleting recorded runs is evidence disposal — humans only, like agent.* */
        if (opName.equals("runs.attach") || opName.equals("runs.spawn"))
            throw OpException.scope("Device controller ownership must be confirmed by the operator; agents may analyze imported runs", "actor=" + actor);
        if (opName.equals("runs.rm"))
            throw OpException.scope("runs.rm deletes recorded evidence — a human actor only", "actor=" + actor);
        if (opName.equals("review.configure"))
            throw OpException.scope("the review policy is the operator's — review.configure needs a human actor", "actor=" + actor);
        if (opName.startsWith("agent."))
            throw OpException.scope("agents propose, humans dispose — " + opName
                    + " needs a human actor (MCP: WORKBENCH_ACTOR=human:<name>)", "actor=" + actor);
        /* llm.* (providers, keys) and task.* (the built-in routines' models) are
           the OPERATOR's: a mission must not re-route or wipe them (settings r1
           L13 — llm.remove / llm.setkey were reachable), but an unmissioned
           MCP client — the user's own assistant configuring the workbench for
           them (user directive 2026-09-09) — may */
        boolean everMissioned = false;   // a STOPPED mission's actor is still a mission's, not the operator's (r2 LAT-1)
        for (Mission m : missions.values()) if (m.agentId.equals(agentId)) { everMissioned = true; break; }
        if (everMissioned && (opName.startsWith("llm.") || opName.startsWith("task.") || opName.startsWith("system.") || opName.startsWith("ui.prefs")))
            throw OpException.scope("a mission cannot reconfigure the workbench — " + opName
                    + " belongs to the operator (a human, or an MCP client that is not a mission)", "actor=" + actor);
        if (mission == null || mission.scope == null || mission.scope.isBlank()) return;
        /* the batch form of a write op: its scope is checked PER ITEM by the op itself
           (Ops.enableBatch calls back here with each item's params) */
        if (dev.workbench.ops.Ops.BATCH_OPS.contains(opName) && params.get("items") instanceof List<?>) return;

        String scope = mission.scope;
        boolean allowed;
        if ("project".equals(scope)) {
            /* the whole-project opt-in (batch 2): any target the mission's project holds, and
               the project's maps and device catalog (project-level ops, unlike a pkg: scope) —
               map.* / device.* go to the ACTIVE project's stores, so they are in scope only while
               the mission's project IS the active one (review b2-r1 H3) */
            if (opName.startsWith("map.") || opName.startsWith("device."))
                allowed = mission.project != null && mission.project.equals(ws.activeProjectName());
            else {
                String target = params.get("target") == null ? null : String.valueOf(params.get("target"));
                try {
                    String tid = ws.resolveTargetIdOrActive(target);
                    allowed = mission.project != null && mission.project.equals(ws.projectNameOfTarget(tid));
                } catch (OpException e) {
                    throw OpException.notFound("mission target " + (target == null ? "(active)" : target)
                            + " is gone: " + e.getMessage(), "scope=" + scope);
                }
            }
        } else if (scope.startsWith("target:")) {
            String want = scope.substring(7);
            String target = params.get("target") == null ? null : String.valueOf(params.get("target"));
            try {
                allowed = want.equals(ws.resolveTargetIdOrActive(target));
            } catch (OpException e) {
                /* the op's target does not resolve (a pinned target closed
                   mid-mission): that is E_NOTFOUND, not "outside scope" — the
                   loop parks the mission on it, and a scope hint would have
                   told the model to summarize instead (r2 R2-5) */
                throw OpException.notFound("mission target " + (target == null ? want : target)
                        + " is gone: " + e.getMessage(), "scope=" + scope);
            }
        } else if (scope.startsWith("map:")) {
            String mapId = scope.substring(4);
            allowed = opName.startsWith("map.") && mapId.equals(params.get("id"));
        } else {
            // package/namespace scope — both the explicit pkg:<prefix> form and
            // the bare prefix form (spec §2: "package/namespace or map"; a bare
            // prefix previously fell through to always-block, silently nuking
            // every write in missions like m2's front-door audit)
            String prefix = scope.startsWith("pkg:") ? scope.substring(4) : scope;
            String sym = params.get("symbol") == null ? "" : String.valueOf(params.get("symbol"));
            /* a package/class scope admits SYMBOL writes under it and nothing
               else: a map verdict (no symbol) used to pass as "empty symbol ⇒
               allowed", so a mission scoped to one class could judge any node
               of any map (r2 lateral). Map work needs a map: scope. */
            allowed = !opName.startsWith("map.") && !sym.isEmpty() && inPrefix(prefix, sym);
        }
        /* every form stays inside the mission's PROJECT (review b2-r1 H4): a target:/pkg:/map:
           scope — typed at spawn, narrowed to by agent.setscope, or a scripted triage's own
           pkg: — never reaches a target another project holds, nor another project's maps */
        if (allowed && mission.project != null && !"project".equals(scope)) {
            if (opName.startsWith("map.") || opName.startsWith("device.")) {
                allowed = mission.project.equals(ws.activeProjectName());
            } else {
                String tp = params.get("target") == null ? null : String.valueOf(params.get("target"));
                try {
                    allowed = mission.project.equals(ws.projectNameOfTarget(ws.resolveTargetIdOrActive(tp)));
                } catch (OpException e) {
                    throw OpException.notFound("mission target " + (tp == null ? "(active)" : tp)
                            + " is gone: " + e.getMessage(), "scope=" + scope);
                }
            }
        }
        if (!allowed) {
            String text = "⛔ BLOCKED: " + opName + " outside scope " + scope
                    + " — " + String.valueOf(params.getOrDefault("symbol", params.getOrDefault("id", "")));
            act(agentId, "blocked", text);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("type", "blocked");
            out.put("agent", agentId);
            out.put("op", opName);
            out.put("scope", scope);
            bus.publish("activity", out);
            throw OpException.scope("mutation outside mission scope: " + opName,
                    "scope=" + scope + " mission=" + mission.id);
        }
    }

    /** Is `sym` under the package/class `prefix`? The boundary is the point
     *  (fix-review reverse r1 M3): a bare startsWith let `pkg:com.foo.Bar`
     *  admit `Lcom/foo/BarView;` and `com.foo.Barn` — tolerable while pkg:
     *  only ever held packages, wrong once a quick reversing mission scopes
     *  itself to ONE class. Accepts the JEB form (`L<p>;`, `L<p>;->m`, `L<p>$I`,
     *  `L<p>/Sub`) and the dotted form (`<p>`, `<p>.m`, `<p>$I`); a trailing
     *  `.*` / `*` is the documented wildcard spelling of the same prefix. */
    static boolean inPrefix(String prefix, String sym) {
        String p = prefix == null ? "" : prefix.trim();
        if (p.endsWith(".*")) p = p.substring(0, p.length() - 2);
        else if (p.endsWith("*")) p = p.substring(0, p.length() - 1);
        while (p.endsWith(".") || p.endsWith("/") || p.endsWith(";")) p = p.substring(0, p.length() - 1);   // `triage-package Lcom/foo/Bar;` → pkg:com.foo.Bar; (r2 R2-9)
        if (p.isEmpty()) return true;
        String slashed = "L" + p.replace('.', '/');
        if (sym.startsWith(slashed))
            return sym.length() == slashed.length() || ";$/-".indexOf(sym.charAt(slashed.length())) >= 0;
        if (sym.startsWith(p))
            return sym.length() == p.length() || ".$;/-".indexOf(sym.charAt(p.length())) >= 0;
        return false;
    }

    // ------------------------------------------------------------ proposals (the llm write path)

    /** The ONE agentId spelling (review F11): strip the "llm:" prefix, everything
        else verbatim — "llm" stays "llm", "llmbot" stays "llmbot". Shared by
        propose/checkScope/syncTagProposal and by Annotations' actor matching, so
        a tag row, a proposal and a scope check always agree on who an actor is. */
    public static String agentIdOf(String actor) {
        return actor != null && actor.startsWith("llm:") ? actor.substring(4) : actor;
    }

    /** An llm mutation becomes a proposal: {applied:false, proposal:true, status:pending, id}. */
    public Map<String, Object> propose(String kind, Map<String, Object> body, String actor) throws OpException {
        /* bare "llm" keeps "llm" as the agentId — the same value checkScope sees
           (Dispatcher normalizes a null/blank actor to "human" upstream) */
        String agentId = agentIdOf(actor);
        body.put("agentId", agentId);
        /* the proposal is filed with its PROJECT (batch 2): the target resolved NOW — a raw
           null target used to ride into the queue and be re-resolved against whatever was
           active at review time — plus the durable key/name the queue's target chip shows.
           A MISSION's proposal belongs to the mission's project and, for a symbol edit, to
           its pinned target — never to whatever the human is looking at while the mission
           thread files it (r2 H3/M4). Target-less kinds (catalog flags, map verdicts) carry
           no target identity at all (r2 M4/L3). */
        Mission mission = missionOfAgent(agentId);
        boolean symbolKind = "rename".equals(kind) || "comment".equals(kind) || "tag".equals(kind);
        String rawTarget = body.get("target") == null ? null : String.valueOf(body.get("target"));
        if (rawTarget == null && symbolKind && mission != null && mission.target != null) rawTarget = mission.target;
        String tid = null;
        if (symbolKind) {
            try { tid = ws.resolveTargetIdOrActive(rawTarget); } catch (OpException ignored) {}
        } else {
            body.remove("target");
        }
        String project = mission != null && mission.project != null ? mission.project
                : (tid == null ? null : ws.projectNameOfTarget(tid));
        if (project == null) project = ws.activeProjectName();
        if (project == null)
            throw OpException.notFound("no project open — a proposal is filed with a project", "open a project or a target first");
        if (tid != null) {
            body.put("target", tid);
            if (ws.targetStoreKey(tid) != null) body.put("targetKey", ws.targetStoreKey(tid));
            if (ws.targetNameOf(tid) != null) body.put("targetName", ws.targetNameOf(tid));
        }
        body.put("project", project);
        /* A human name is protected at filing time in either review mode. The atomic apply path
           checks this again and refuses another agent's unsaved name under the target write gate. */
        if ("rename".equals(kind) && tid != null) {
            String humanEntry = ws.humanNamedBy(tid, String.valueOf(body.get("symbol")));
            if (humanEntry != null)
                throw OpException.scope("this symbol was named by a human (History " + humanEntry + ") — not an agent's to rename",
                        "symbol=" + body.get("symbol"));
        }
        ProposalStore store = proposalsFor(project);
        Map<String, Object> p = store.add(kind, body, nextProposalId());
        String pid = String.valueOf(p.get("id"));
        act(agentId, kind, describe(kind, p));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("proposal", true);
        out.put("id", pid);
        out.put("actor", actor);
        /* the application-side switch (ReviewPolicy; a mission may carry its own mode): apply at
           once, recorded in History with the agent as actor and undoable like any edit — the
           record stays, settled accepted with auto:true. A write the engine refuses (a taken
           name) is NOT left in the queue: it settles refused and the agent gets the reason, so
           it can pick another name in the same turn. */
        if (reviewPolicy.appliesDirectly(kind, body.get("confidence"), null)) {
            try {
                applyProposal(p, "agent:" + agentId + " auto-applied");
            } catch (OpException e) {
                store.retouch(pid, "refusal", e.getMessage());
                store.settle(pid, "refused");
                act(agentId, "note", "proposal " + pid + " REFUSED on auto-apply — " + e.getMessage() + " · " + describe(kind, p));
                publishPropsCount();
                throw e;
            }
            store.retouch(pid, "auto", true);
            store.settle(pid, "accepted");
            act(agentId, "note", "proposal " + pid + " AUTO-APPLIED · " + describe(kind, p));
            publishPropsCount();
            out.put("applied", true);
            out.put("status", "auto-accepted");
            return out;
        }
        publishPropsCount();
        out.put("applied", false);
        out.put("status", "pending");
        return out;
    }

    /** The mission an agent id belongs to: a live one first, else the newest. */
    private synchronized Mission missionOfAgent(String agentId) {
        Mission any = null;
        for (Mission m : missions.values())
            if (m.agentId.equals(agentId)) {
                any = m;
                if ("running".equals(m.state) || "paused".equals(m.state) || "queued".equals(m.state)) return m;
            }
        return any;
    }

    /** A proposal's symbol against a history entry's addr: equal, or the entry's post-rename
     *  address, or the same owner and descriptor with a different member name (JEB renamed the
     *  override through its interface before the agent filed the same name for it). */
    private static boolean sameSymbol(String sym, Map<?, ?> h) {
        String addr = String.valueOf(h.get("addr")), newAddr = String.valueOf(h.get("newAddr"));
        if (sym.equals(addr) || sym.equals(newAddr)) return true;
        int a = sym.indexOf("->"), b = addr.indexOf("->");
        if (a < 0 || b < 0) return false;
        if (!sym.substring(0, a).equals(addr.substring(0, b))) return false;
        String sa = sym.substring(a + 2), ha = addr.substring(b + 2);
        int pa = sa.indexOf('('), pb = ha.indexOf('(');
        if (pa < 0 || pb < 0) { pa = sa.indexOf(':'); pb = ha.indexOf(':'); }   // a field: name:type
        return pa >= 0 && pb >= 0 && sa.substring(pa).equals(ha.substring(pb));
    }

    /** The store a proposal row lives in: its own project's, else the active project's. */
    private ProposalStore storeOf(Map<String, Object> p) throws OpException {
        Object proj = p.get("project");
        return proj == null ? proposals() : proposalsFor(String.valueOf(proj));
    }

    private static String describe(String kind, Map<String, Object> p) {
        return switch (kind) {
            case "rename" -> "propose rename " + p.get("symbol") + " → " + p.get("newName")   // the full symbol: "A01 → x" left the class to guess (user, 2026-09-13)
                    + (p.get("proposedName") != null && !String.valueOf(p.get("proposedName")).equals(String.valueOf(p.get("newName")))
                        ? " (edited from " + p.get("proposedName") + ")" : "")
                    + " · conf " + p.getOrDefault("confidence", "—");
            case "comment" -> "propose comment on " + p.get("symbol");
            case "tag" -> "propose tag " + p.get("name") + " on " + p.get("symbol");
            case "verdict" -> "propose verdict " + p.get("verdict") + " on " + p.get("map") + "/" + p.get("node");
            default -> "propose " + kind;
        };
    }

    /** agent.proposals [--mission] [--kind] [--target]: the ACTIVE project's pending queue,
        optionally one target's subset (live id or durable key). */
    public List<Object> proposalList(String mission, String kind, String targetParam) throws OpException {
        return proposalList(mission, kind, targetParam, null);
    }

    /** state: pending (default, the review queue) | all | one state. Applied rows carry
     *  `reverted` when their history entry was undone (the EDITS pane shows them dim). */
    public List<Object> proposalList(String mission, String kind, String targetParam, String state) throws OpException {
        List<Object> rows = proposals().list(mission, kind, state);
        Map<String, List<Object>> histByTarget = new java.util.HashMap<>();
        java.util.Set<String> usedHistory = new java.util.HashSet<>();
        for (Object o : rows) {
            @SuppressWarnings("unchecked")
            Map<String, Object> p = (Map<String, Object>) o;
            if (p.get("target") == null) continue;
            String tid = String.valueOf(p.get("target"));
            if (p.get("historyId") == null && "accepted".equals(p.get("state")) && Boolean.TRUE.equals(p.get("auto"))) {
                /* rows applied before 2026-09-19 carry no history id: find their entry once (the
                   agent's own rename/comment of that symbol to that name) and stamp it for good */
                List<Object> hist = histByTarget.computeIfAbsent(tid, t -> { try { return ws.historyList(t, Integer.MAX_VALUE); } catch (Exception e) { return List.of(); } });
                String agent = String.valueOf(p.get("agentId"));
                for (Object ho : hist) {
                    if (!(ho instanceof Map<?, ?> h)) continue;
                    if (usedHistory.contains(String.valueOf(h.get("id")))) continue;
                    if (!String.valueOf(h.get("actor")).contains("agent:" + agent)) continue;
                    if (!String.valueOf(p.get("kind")).equals(String.valueOf(h.get("op")))) continue;
                    /* one annotate batch renames first and comments after, and History records the
                       address AS IT WAS when the entry was written — a class renamed earlier in the
                       batch shows under its new name. So: a rename matches on old + new name (the
                       symbol when it still agrees), a comment on its text; each entry is used once. */
                    if ("rename".equals(p.get("kind"))) {
                        if (!String.valueOf(p.get("newName")).equals(String.valueOf(h.get("newName")))) continue;
                        if (!sameSymbol(String.valueOf(p.get("symbol")), h)
                                && !(p.get("oldName") != null && String.valueOf(p.get("oldName")).equals(String.valueOf(h.get("oldName"))))) continue;
                    } else if ("comment".equals(p.get("kind"))) {
                        if (!String.valueOf(p.get("newText")).equals(String.valueOf(h.get("newText")))) continue;
                    } else continue;
                    usedHistory.add(String.valueOf(h.get("id")));
                    p.put("historyId", h.get("id"));
                    if (h.get("newAddr") != null) p.put("newAddr", h.get("newAddr"));
                    ProposalStore st = storeOf(p);
                    st.stamp(String.valueOf(p.get("id")), "historyId", h.get("id"));
                    if (h.get("newAddr") != null) st.stamp(String.valueOf(p.get("id")), "newAddr", h.get("newAddr"));
                    break;
                }
            }
            if (p.get("historyId") != null) {
                Boolean undone = ws.historyUndone(tid, String.valueOf(p.get("historyId")));
                if (undone != null) p.put("reverted", undone);
            }
        }
        /* display only: a row that names an OPEN target by live id but carries no name (a
           pre-batch-2 row) gets the name the queue's chip shows (persona pass M5) */
        for (Object o : rows) {
            @SuppressWarnings("unchecked")
            Map<String, Object> p = (Map<String, Object>) o;
            if (p.get("targetName") == null && p.get("target") != null) {
                String nm = ws.targetNameOf(String.valueOf(p.get("target")));
                if (nm != null) p.put("targetName", nm);
            }
        }
        if (targetParam == null || targetParam.isBlank()) return rows;
        String tid = ws.resolveTargetIdOrActive(targetParam);
        String key = ws.targetStoreKey(tid);
        List<Object> out = new ArrayList<>();
        for (Object o : rows) {
            Map<?, ?> p = (Map<?, ?>) o;
            if (tid.equals(p.get("target")) || (key != null && key.equals(p.get("targetKey")))) out.add(o);
        }
        return out;
    }

    /** A re-proposal that deduped into the agent's OWN pending row may carry a
        newer note: sync the open proposal's body so the human reviews the text
        that accept will actually apply (review F3). Matches by KEY (spelling-proof),
        LINE and proposer — name/symbol alone retouched a different line's proposal
        (review). Returns the open proposal's id, or null when none matches (or the
        match settled mid-flight) — the caller then answers with the tag row. */
    public synchronized String syncTagProposal(String actor, String target, String key,
            String name, Integer line, String note) {
        ProposalStore proposals = proposalsForTarget(target);
        if (proposals == null) return null;   // no project open: nothing to sync
        String agentId = agentIdOf(actor);
        for (Object o : proposals.list(agentId, "tag")) {   // newest-first
            @SuppressWarnings("unchecked")
            Map<String, Object> p = (Map<String, Object>) o;
            if (!name.equals(p.get("name"))) continue;
            int pl = p.get("line") instanceof Number n ? n.intValue() : -1;
            if (pl != (line == null ? -1 : line.intValue())) continue;
            /* target-scoped like the supersede sweep: two targets can hold the same
               class (two versions of one APK — the canonical diff workflow), so
               agentId+name+line+key still isn't unique without it (review) */
            if (target != null && p.get("target") != null && !target.equals(p.get("target"))) continue;
            String pk = p.get("key") == null ? null : String.valueOf(p.get("key"));
            if (pk == null) {
                /* legacy key-less body: attributable only when its OWN target is
                   known and is this op's — resolving an untargeted spelling under
                   the caller's target would attribute it arbitrarily (review) */
                if (p.get("target") == null || target == null) continue;
                pk = ws.annotations().keyOf(target, String.valueOf(p.get("symbol")));
            }
            if (key != null && !key.equals(pk)) continue;
            String id = String.valueOf(p.get("id"));
            if (note != null && !note.equals(p.get("note")) && !proposals.retouch(id, "note", note))
                return null;                            // settled between list and retouch
            /* the list() snapshot may already be stale — review() settles on
               another thread without the AgentManager monitor; never answer with
               a settled proposal's id (review) */
            return proposals.isPending(id) ? id : null;
        }
        return null;
    }

    /** Settle open "tag" proposals whose pending ROWS were superseded — a live
        row for the key now exists (a human disposed, or a rival proposal was
        accepted), so the queue must stop offering reviews that could only
        stale out (review F6). Matched by KEY (a differently-spelled symbol that
        resolves the same no longer escapes, review) + line + name, target-scoped
        when both sides know theirs (review); legacy key-less proposals resolve
        their symbol under the op's target. exceptId skips the proposal whose
        accept triggered the sweep. */
    public synchronized int supersedeTagProposals(String target, String key, String name,
            Integer line, String exceptId) {
        ProposalStore proposals = proposalsForTarget(target);
        if (proposals == null) return 0;   // no project open: nothing to sync
        int n = 0;
        for (Object o : proposals.list(null, "tag")) {
            @SuppressWarnings("unchecked")
            Map<String, Object> p = (Map<String, Object>) o;
            if (exceptId != null && exceptId.equals(p.get("id"))) continue;
            if (!name.equals(p.get("name"))) continue;
            int pl = p.get("line") instanceof Number ln ? ln.intValue() : -1;
            if (pl != (line == null ? -1 : line.intValue())) continue;
            if (target != null && p.get("target") != null && !target.equals(p.get("target"))) continue;
            String pk = p.get("key") == null ? null : String.valueOf(p.get("key"));
            if (pk == null) {
                /* legacy key-less body: attributable only when its OWN target is
                   known and is this op's — never resolved under a caller's target
                   the proposal knows nothing about (review) */
                if (p.get("target") == null || target == null) continue;
                pk = ws.annotations().keyOf(target, String.valueOf(p.get("symbol")));
            }
            if (key != null && !key.equals(pk)) continue;
            proposals.settle(String.valueOf(p.get("id")), "superseded");
            act(String.valueOf(p.getOrDefault("agentId", "agent")), "note",
                    "proposal " + p.get("id") + " SUPERSEDED — " + name + " went live directly");
            n++;
        }
        if (n > 0) publishPropsCount();
        return n;
    }

    /** agent.review <id> <accept|reject> — accept applies the underlying op as
     *  human; the result is recorded in history with actor "agent:<id> accepted
     *  by human". Reject marks rejected (and drops underlying pending records). */
    /** agent.review {id, action, newName?}: newName is the reviewer's edit of a RENAME proposal
     *  (a collision, a better name — any reason): the record keeps the agent's original as
     *  proposedName, the edit becomes newName, and the accept applies the edit. The engine's
     *  identifier check and JEB's refusal apply to the edit exactly as to the original, so a
     *  bad edit leaves the proposal pending. */
    public Map<String, Object> review(String id, String action, String newName) throws OpException {
        ProposalStore proposals = proposals();   // the active project's queue (ids are per project)
        Map<String, Object> p = proposals.get(id);
        if (!"pending".equals(p.get("state")))
            throw OpException.syntax("proposal already settled: " + id, "state=" + p.get("state"));
        boolean accept = "accept".equals(action);
        if (!accept && !"reject".equals(action))
            throw OpException.syntax("agent.review action must be accept|reject", "action=" + action);
        String agentId = String.valueOf(p.getOrDefault("agentId", "agent"));
        String human = "agent:" + agentId + " accepted by human";

        if (newName != null && !newName.isBlank() && !newName.trim().equals(String.valueOf(p.get("newName")))) {
            if (!"rename".equals(String.valueOf(p.get("kind"))))
                throw OpException.syntax("newName edits a rename proposal only (this one is " + p.get("kind") + ")", "id=" + id);
            if (!accept)
                throw OpException.syntax("newName goes with action:accept", "id=" + id);
            String edit = newName.trim();
            if (!edit.matches("[A-Za-z_$][A-Za-z0-9_$]*"))
                throw OpException.syntax("bad Java identifier: " + edit, "id=" + id);
            if (p.get("proposedName") == null) proposals.retouch(id, "proposedName", p.get("newName"));   // the agent's original, once
            proposals.retouch(id, "newName", edit);
            p = proposals.get(id);
        }

        if (accept) applyProposal(p, human);
        else rejectUnderlying(p);

        Map<String, Object> settled = proposals.settle(id, accept ? "accepted" : "rejected");
        act(agentId, "note", "proposal " + id + " " + (accept ? "ACCEPTED" : "rejected") + " · "
                + describe(String.valueOf(p.get("kind")), p));
        publishPropsCount();
        return settled;
    }

    /** agent.purge [--before ISO] [--mission] [--noRationale] [--targetGone]: settle PENDING
     *  proposals of the active project that match every given criterion as "purged" — the queue
     *  cleaner for rows nobody will review one by one (a dead mission's leftovers, rows filed
     *  before a date, rows without a rationale, rows whose target is gone). Humans only
     *  (agent.* is human-only in checkScope). Nothing is applied or un-applied. */
    public Map<String, Object> purge(String before, String mission, boolean noRationale, boolean targetGone) throws OpException {
        if ((before == null || before.isBlank()) && (mission == null || mission.isBlank()) && !noRationale && !targetGone)
            throw OpException.syntax("agent.purge needs a criterion: before, mission, noRationale or targetGone", "");
        long beforeMs = 0;
        if (before != null && !before.isBlank()) {
            try { beforeMs = java.time.Instant.parse(before.trim()).toEpochMilli(); }
            catch (Exception e) { throw OpException.syntax("before must be an ISO instant, e.g. 2026-09-10T00:00:00Z", "before=" + before); }
        }
        ProposalStore ps = proposals();
        List<Object> purged = new ArrayList<>();
        for (Object o : ps.list(mission, null)) {
            @SuppressWarnings("unchecked")
            Map<String, Object> p = (Map<String, Object>) o;
            if (beforeMs > 0) {
                long ts = 0;
                try { ts = java.time.Instant.parse(String.valueOf(p.get("ts"))).toEpochMilli(); } catch (Exception ignored) {}
                if (ts == 0 || ts >= beforeMs) continue;
            }
            if (noRationale && p.get("rationale") != null && !String.valueOf(p.get("rationale")).isBlank()) continue;
            if (targetGone) {
                String key = p.get("targetKey") == null ? null : String.valueOf(p.get("targetKey"));
                String tid = p.get("target") == null ? null : String.valueOf(p.get("target"));
                boolean open = key != null ? ws.tidForStoreKey(key) != null : (tid != null && ws.projectNameOfTarget(tid) != null);
                if (open) continue;
            }
            String id = String.valueOf(p.get("id"));
            Map<String, Object> settled = ps.settle(id, "purged");
            if (settled != null) {
                purged.add(id);
                act(String.valueOf(p.getOrDefault("agentId", "agent")), "note", "proposal " + id + " PURGED · " + describe(String.valueOf(p.get("kind")), p));
            }
        }
        if (!purged.isEmpty()) publishPropsCount();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("purged", purged);
        out.put("count", purged.size());
        out.put("pending", ps.pendingCount());
        return out;
    }

    /** agent.review — stale-referent tolerant: a proposal whose underlying
     *  map/flow/symbol was deleted since it was filed settles as "stale"
     *  (auto-rejected, out of the queue) instead of failing the review. */
    public Map<String, Object> reviewTolerant(String id, String action, String newName) throws OpException {
        try {
            return review(id, action, newName);
        } catch (OpException e) {
            if (!"E_NOTFOUND".equals(e.code)) throw e;
            ProposalStore proposals = proposals();
            Map<String, Object> p = proposals.get(id);
            if (!"pending".equals(p.get("state"))) throw e;
            Map<String, Object> settled = proposals.settle(id, "stale");
            String agentId = String.valueOf(p.getOrDefault("agentId", "agent"));
            act(agentId, "note", "proposal " + id + " STALE — referent gone (" + e.getMessage()
                    + ") · " + describe(String.valueOf(p.get("kind")), p));
            publishPropsCount();
            return settled;
        }
    }

    /** The target a symbol proposal applies to (review b2-r1 H2): its durable store key first,
        then its live id if that id is an OPEN target's; a legacy row that named neither applies to
        the viewed target only when its symbol resolves there. A named target that is not open is
        E_BUSY (open it, then review) — never a silent re-aim at whatever is active, and never
        E_NOTFOUND, which reviewTolerant would settle as stale. */
    private String proposalTarget(Map<String, Object> p) throws OpException {
        String key = p.get("targetKey") == null ? null : String.valueOf(p.get("targetKey"));
        String tid = p.get("target") == null ? null : String.valueOf(p.get("target"));
        if (key != null) {
            String live = ws.tidForStoreKey(key);
            if (live != null) return live;
            throw OpException.busy("the proposal's target " + p.getOrDefault("targetName", key)
                    + " is not open — open it, then review", "targetKey=" + key);
        }
        if (tid != null) {
            if (ws.projectNameOfTarget(tid) != null) return tid;
            throw OpException.busy("the proposal's target " + tid + " is not open (an id from an earlier session)"
                    + " — open the artifact it was filed against, then review", "target=" + tid);
        }
        String active = ws.activeTarget();
        if (active == null) throw OpException.busy("no target open to apply the proposal to", "");
        Object sym = p.get("symbol");
        if (sym != null) {
            try { ws.engineFor(active).resolve(String.valueOf(sym)); }
            catch (Exception e) {
                throw OpException.busy("the proposal names no target and its symbol " + sym
                        + " is not in the viewed target — open the right artifact, then review", "target=" + active);
            }
        }
        return active;
    }

    private void applyProposal(Map<String, Object> p, String human) throws OpException {
        String kind = String.valueOf(p.get("kind"));
        String target = ("rename".equals(kind) || "comment".equals(kind) || "tag".equals(kind))
                ? proposalTarget(p) : null;
        switch (kind) {
            case "rename" -> {
                Map<String, Object> res = ws.renameSymbol(target, String.valueOf(p.get("symbol")),
                        String.valueOf(p.get("newName")), human, p.get("confidence"),
                        p.get("expectedName") == null ? null : String.valueOf(p.get("expectedName")));
                /* The edits pane refers to the same history entry as the guarded mutation. */
                if (res.get("historyId") != null) storeOf(p).stamp(String.valueOf(p.get("id")), "historyId", res.get("historyId"));
                if (res.get("newAddr") != null) storeOf(p).stamp(String.valueOf(p.get("id")), "newAddr", res.get("newAddr"));
            }
            case "comment" -> {
                ws.withTargetWrite(target, () -> {
                    Map<String, Object> res = ws.engineFor(target).comment(
                            String.valueOf(p.get("symbol")),
                            p.get("newText") == null ? "" : String.valueOf(p.get("newText")), human);
                    Map<String, Object> h = new LinkedHashMap<>();
                    h.put("op", "comment");
                    h.put("actor", human);
                    if (p.get("confidence") != null) h.put("confidence", p.get("confidence"));
                    h.put("addr", res.get("addr"));
                    h.put("newAddr", res.get("addr"));
                    h.put("oldText", res.get("oldText"));
                    h.put("newText", p.get("newText"));
                    h.put("owner", p.getOrDefault("owner", ""));
                    ws.recordHistory(target, h);
                    storeOf(p).stamp(String.valueOf(p.get("id")), "historyId", h.get("id"));
                    return null;
                });
            }
            case "tag" -> {
                /* match the proposer's OWN pending row at the proposal's LINE
                   (review F1: first-match promoted whichever row came first).
                   The stamped KEY goes to tagSettle, not the raw symbol: a
                   'symbol:line'-spelled body never resolves through keyFor and
                   its row would orphan as un-settleable (review) */
                String forAgent = p.get("agentId") == null ? null : String.valueOf(p.get("agentId"));
                Integer pline = p.get("line") instanceof Number ln ? ln.intValue() : null;
                Map<String, Object> st = ws.annotations().tagSettle(target,
                        p.get("key") != null ? String.valueOf(p.get("key")) : String.valueOf(p.get("symbol")),
                        String.valueOf(p.get("name")), true,
                        forAgent, pline);
                /* the accept made the key live — settle any OTHER open tag
                   proposals for it as superseded (review F6), except this one
                   (review() settles it "accepted" right after). Key + line from
                   the SETTLED ROW: spelling-proof and per-line (review) */
                if (st.get("superseded") instanceof Number ns && ns.intValue() > 0)
                    supersedeTagProposals(target, String.valueOf(st.get("key")),
                            String.valueOf(p.get("name")),
                            st.get("line") instanceof Number rl ? rl.intValue() : null,
                            String.valueOf(p.get("id")));
            }
            case "catalog" -> ws.devices().applyAnnotation(String.valueOf(p.get("serial")),
                    p.get("entryKind") == null ? null : String.valueOf(p.get("entryKind")),
                    String.valueOf(p.get("name")), String.valueOf(p.get("note")), human);
            case "verdict" -> ws.maps().setVerdict(String.valueOf(p.get("map")),
                    String.valueOf(p.get("node")), String.valueOf(p.get("verdict")),
                    p.get("rationale") == null ? null : String.valueOf(p.get("rationale")),
                    p.get("field") == null ? null : String.valueOf(p.get("field")), "human", false);
            default -> throw OpException.unsupported("review for kind: " + kind, "");
        }
    }

    private void rejectUnderlying(Map<String, Object> p) throws OpException {
        if ("tag".equals(p.get("kind"))) {
            String target;
            try {
                target = proposalTarget(p);   // key-first, like accept (r2 M3): a reopened target's pending tag row settles, not "stale"
            } catch (OpException e) {
                /* the target is not open (or its store entry is gone): a rejection has nothing to
                   un-apply there — the proposal still leaves the queue; the pending tag row in
                   that target's own sidecar shows as pending until it is opened and cleaned
                   (r3 M2: accept AND reject used to be blocked, the row stuck forever) */
                act(String.valueOf(p.getOrDefault("agentId", "agent")), "note", "proposal " + p.get("id")
                        + " rejected without settling its tag row — " + e.getMessage());
                return;
            }
            ws.annotations().tagSettle(target,
                    p.get("key") != null ? String.valueOf(p.get("key")) : String.valueOf(p.get("symbol")),
                    String.valueOf(p.get("name")), false,
                    p.get("agentId") == null ? null : String.valueOf(p.get("agentId")),
                    p.get("line") instanceof Number ln ? ln.intValue() : null);
        }
        if ("verdict".equals(p.get("kind")))
            ws.maps().setVerdict(String.valueOf(p.get("map")), String.valueOf(p.get("node")),
                    null, null, null, "human", true);   // reject:true drops the node pending
    }

    private void publishPropsCount() {
        Map<String, Object> out = new LinkedHashMap<>();
        ProposalStore active = activeProposalsOrNull();
        out.put("pending", active == null ? 0 : active.pendingCount());
        out.put("project", ws.activeProjectName());
        bus.publish("proposals", out);
    }

    // ------------------------------------------------------------ follow (presence)

    public synchronized Map<String, Object> follow(String id) throws OpException {
        Mission m = requireMission(id);
        /* allowlist: only a running/paused mission can ever emit presence — following
           a blocked/queued/done one promises a jump that will never come (review F6).
           synchronized: the watcher/loop threads release `followed` under the same
           monitor — an interleaved follow(B) must never be nulled by mission A's
           terminal release (review) */
        if (!"running".equals(m.state) && !"paused".equals(m.state))
            throw OpException.scope("mission " + id + " is " + m.state + " — only running/paused missions can be followed",
                    "state=" + m.state);
        followed = m.agentId;
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("following", m.agentId);
        out.put("mission", m.id);
        bus.publish("presence", new LinkedHashMap<>(out));   // other clients repaint the switch
        return out;
    }

    public synchronized Map<String, Object> unfollow() {
        followed = null;
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("following", null);
        out.put("mission", null);
        bus.publish("presence", new LinkedHashMap<>(out));
        return out;
    }

    // ------------------------------------------------------------ scripted mission

    /**
     * "triage-package <prefix>" — the deterministic verification mission: walk
     * symbol.tree for the prefix, decompile the first few classes, and propose
     * one method rename (short/obfuscated names → triageN) plus a tag per class.
     * Proves the full propose→review→apply loop with no LLM.
     */
    private void startScripted(Mission m) {
        /* the scripted agent understands ONE goal grammar; any other goal
           (a "reverse method …" spawned with the Scripted provider picked)
           used to be sliced as if it were one, overwrite the scope with the
           slice and report DONE over an empty walk (fix-review reverse r1 M4) */
        if (!m.goal.startsWith("triage-package")) {
            m.state = "blocked";
            act(m.agentId, "blocked", "scripted mode runs only \"triage-package <prefix>\" goals — pick an LLM provider for: " + m.goal);
            return;
        }
        String prefix = m.goal.substring("triage-package".length()).trim();
        if (prefix.isEmpty()) {
            m.state = "blocked";
            act(m.agentId, "blocked", "triage-package needs a package prefix");
            return;
        }
        String scopePrefix = prefix.startsWith("L") ? prefix.substring(1).replace('/', '.') : prefix;
        m.scope = "pkg:" + scopePrefix;
        m.state = "running";
        Thread t = new Thread(() -> runScripted(m, prefix), "mission-" + m.id);
        t.setDaemon(true);
        t.start();
    }

    private void runScripted(Mission m, String prefix) {
        try {
            act(m.agentId, "note", "triaging " + prefix + " · scope " + m.scope);
            @SuppressWarnings("unchecked")
            /* the name search (classes whose name contains the text, with members): it takes four, so it
               asks for sixteen — unbounded, "X" on a WhatsApp build built 90,000 class nodes with every
               member under the engine lock to pick four */
            List<Object> tree = (List<Object>) ws.engineFor(m.target).tree(prefix, 16).get("items");   // the pinned target, else the active one (r2 R2-14)
            List<Map<String, Object>> classes = new ArrayList<>();
            collectClasses(tree, classes, 4);
            int n = 0;
            for (Map<String, Object> cls : classes) {
                if (m.stopRequested) return;
                while (m.pauseRequested && !m.stopRequested) Thread.sleep(300);
                if (m.stopRequested) return;
                String addr = String.valueOf(cls.get("addr"));
                String name = String.valueOf(cls.get("name"));
                act(m.agentId, "xref", "decompile " + name);
                Map<String, Object> dec = ws.engineFor(m.target).decompile(addr, Map.of());
                stepProgress(m, "decompiled " + name);

                // propose a rename for the first short-named method
                @SuppressWarnings("unchecked")
                List<Object> methods = (List<Object>) dec.get("methods");
                Map<String, Object> picked = null;
                for (Object mo : methods) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> mm = (Map<String, Object>) mo;
                    String mn = String.valueOf(mm.get("name"));
                    if (mn.length() <= 2 && !mn.startsWith("<")) { picked = mm; break; }
                }
                n++;
                if (picked != null) {
                    Map<String, Object> body = new LinkedHashMap<>();
                    body.put("target", m.target);   // the PINNED target, not the viewed one (r2 H3)
                    body.put("symbol", picked.get("addr"));
                    body.put("oldName", picked.get("name"));
                    body.put("newName", "triage" + n);
                    body.put("owner", name);
                    body.put("confidence", 0.82);
                    body.put("rationale", "short obfuscated name in " + name
                            + " — triage candidate (" + n + ")");
                    propose("rename", body, "llm:" + m.agentId);
                }
                Map<String, Object> tagRow = ws.annotations().tagAdd(m.target, addr, "triage:review",
                        "scripted triage of " + prefix, "llm:" + m.agentId, null);
                /* dedupe is PER-PROPOSER: a re-walk by THIS agent files nothing new,
                   but a new mission's different agentId files its own row + proposal
                   (N agents, N pending rows — by design) */
                if (!Boolean.TRUE.equals(tagRow.get("deduped"))) {
                    Map<String, Object> tagBody = new LinkedHashMap<>();
                    /* resolved target/key/line from the ROW — the same canonical
                       values Ops stamps, so review/sweep matching is uniform */
                    tagBody.put("target", m.target != null ? m.target : ws.resolveTargetIdOrActive(null));
                    tagBody.put("symbol", addr);
                    tagBody.put("key", tagRow.get("symbol"));
                    tagBody.put("line", tagRow.get("line"));
                    tagBody.put("name", "triage:review");
                    tagBody.put("note", "scripted triage of " + prefix);
                    tagBody.put("confidence", 0.9);
                    tagBody.put("rationale", "walked by scripted mission " + m.id);
                    propose("tag", tagBody, "llm:" + m.agentId);
                }
            }
            m.state = "done";
            /* no fabricated spend: the scripted triage is not an LLM (UI audit 2026-09-09, fix-review r1 LAT-1) */
            act(m.agentId, "note", "mission " + m.id + " done · " + n + " classes triaged");
        } catch (Exception e) {
            m.state = "blocked";
            act(m.agentId, "blocked", "mission failed: " + e.getMessage());
        }
        releaseFollowIfTerminal(m);
    }

    @SuppressWarnings("unchecked")
    private static void collectClasses(List<Object> nodes, List<Map<String, Object>> out, int cap) {
        for (Object o : nodes) {
            if (out.size() >= cap) return;
            Map<String, Object> n = (Map<String, Object>) o;
            if ("class".equals(n.get("kind")) && n.get("addr") != null) out.add(n);
            Object kids = n.get("children");
            if (kids instanceof List) collectClasses((List<Object>) kids, out, cap);
        }
    }

    private void stepProgress(Mission m, String text) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("ts", System.currentTimeMillis());
        row.put("text", text);
        m.progress.add(row);
    }

    // ------------------------------------------------------------ catalog missions (device view)

    private static final java.util.Set<String> GOAL_STOPWORDS =
            java.util.Set.of("related", "flows", "surface", "with", "for", "the", "and", "triage");

    /** device.spawn-mission <serial> <goal> — scripted catalog triage: match goal
     *  keywords against catalog entries and propose annotations for the hits. */
    public synchronized Map<String, Object> spawnCatalog(String serial, String goal) throws OpException {
        if (serial == null || serial.isBlank()) throw OpException.syntax("device.spawn-mission needs a serial", "");
        if (goal == null || goal.isBlank()) throw OpException.syntax("device.spawn-mission needs a goal", "");
        /* no unscoped mission (batch 2, review b2-r1 M7): the flags this scan files are
           catalog proposals of the active project — that project is the scope */
        String projectName = ws.activeProjectName();
        if (projectName == null)
            throw OpException.syntax("device.spawn-mission needs an open project — its flags are filed with the project", "");
        Mission m = new Mission();
        seedSeqsFromArchive();
        m.id = "m" + (++missionSeq);
        m.goal = "catalog-triage " + serial + " · " + goal.trim();
        m.mode = "catalog";
        m.model = "builtin:catalog";
        m.scope = "project";
        m.project = projectName;
        m.budget = 0;   // a substring scan spends nothing: no budget bar to read as a ceiling (fix-review r1 LAT-2)
        m.agentId = "agent-" + (++agentSeq);
        m.startedAt = System.currentTimeMillis();
        missions.put(m.id, m);
        act(m.agentId, "note", "catalog triage on " + serial + " · goal: " + goal);
        Thread t = new Thread(() -> runCatalog(m, serial, goal.toLowerCase()), "mission-" + m.id);
        t.setDaemon(true);
        t.start();
        return m.toMap();
    }

    private void runCatalog(Mission m, String serial, String goal) {
        m.state = "running";
        // the flip happens on the mission thread, AFTER spawnCatalog's row already went
        // out with state "queued". Without a row here a mission that matches nothing
        // never announces that it ran — the agents view would show it queued, then done.
        act(m.agentId, "note", "mission " + m.id + " running · scanning the " + serial + " catalog");
        try {
            List<Map<String, Object>> entries = ws.devices().catalogForMission(serial);
            List<String> words = new ArrayList<>();
            for (String w : goal.split("[^a-z0-9]+"))
                if (w.length() >= 3 && !GOAL_STOPWORDS.contains(w)) words.add(w);
            int n = 0;
            for (Map<String, Object> e : entries) {
                if (m.stopRequested) return;
                while (m.pauseRequested && !m.stopRequested) Thread.sleep(300);
                if (m.stopRequested) return;
                String hay = (String.valueOf(e.getOrDefault("name", "")) + " "
                        + String.valueOf(e.getOrDefault("path", ""))).toLowerCase();
                for (String w : words) {
                    if (!hay.contains(w)) continue;
                    Map<String, Object> body = new LinkedHashMap<>();
                    body.put("serial", serial);
                    body.put("entryKind", e.get("kind"));
                    body.put("name", e.get("name"));
                    body.put("path", e.get("path"));
                    body.put("note", w + "-related (" + e.get("kind") + "): " + e.get("name"));
                    body.put("confidence", 0.75);
                    body.put("rationale", "matches '" + w + "' in catalog entry name/path on " + serial);
                    propose("catalog", body, "llm:" + m.agentId);
                    n++;
                    break;
                }
            }
            /* no fabricated spend: this is a substring scan, not an LLM (UI audit 2026-09-09) */
            stepProgress(m, "scanned " + entries.size() + " entries · " + n + " matches");
            m.state = "done";
            act(m.agentId, "note", "catalog triage done · " + n + " annotation proposals");
        } catch (Exception e) {
            m.state = "blocked";
            act(m.agentId, "blocked", "catalog mission failed: " + e.getMessage());
        }
    }

    // ------------------------------------------------------------ http mode (multi-LLM agent loop)

    /** Who records a mission's requests, answers and tool results exactly (AgentLoop.Capture) — an
     *  integration may set it; by default the loop records nothing beyond its transcripts. */
    private volatile java.util.function.Function<Mission, dev.workbench.llm.AgentLoop.Capture> captureFactory;
    public void captureFactory(java.util.function.Function<Mission, dev.workbench.llm.AgentLoop.Capture> f) { this.captureFactory = f; }

    /** Mode "http": the OpenAI-compatible agent loop over the internal dispatcher.
     *  Model spec is provider:model (or bare provider → its default model). The
     *  `fixture` provider plays canned responses — no network, no key. */
    private void startHttp(Mission m, String[] resolved) throws OpException {
        /* a mission blocked before its first request carries its stopReason like one blocked in the
           loop — a mission report shows stopReason, not the activity row (persona pass 2026-10-07) */
        if (resolved == null) {
            m.state = "blocked";
            m.stopReason = "http mode needs --model <provider:model> — see llm.providers";
            act(m.agentId, "blocked", m.stopReason);
            return;
        }
        dev.workbench.llm.LlmRegistry.Provider pr = llm.get(resolved[0]);
        String model = resolved[1];
        if (pr.needsKey && !llmKeys.has(pr.id)) {
            m.state = "blocked";
            m.stopReason = "no API key for " + pr.id + " — llm.setkey or " + dev.workbench.llm.KeyStore.envName(pr.id);
            act(m.agentId, "blocked", m.stopReason);
            return;
        }
        if (dispatcher == null) {
            m.state = "blocked";
            m.stopReason = "internal dispatcher not wired";
            act(m.agentId, "blocked", m.stopReason);
            return;
        }
        dev.workbench.llm.AgentLoop.Wire wire = new dev.workbench.llm.AgentLoop.Wire(pr.reasoningField, pr.reasoningReplay, pr.streamOnly, pr.maxOutputTokens);
        dev.workbench.llm.AgentLoop.ChatClient client = "fixture".equals(pr.id)
                ? dev.workbench.llm.AgentLoop.fixtureClient(model)
                : pr.streamOnly ? dev.workbench.llm.AgentLoop.openAiStreamingClient(pr.baseUrl, llmKeys.get(pr.id))
                : dev.workbench.llm.AgentLoop.openAiClient(pr.baseUrl, llmKeys.get(pr.id));
        /* the provider's extras (DeepSeek's thinking mode) plus the mission's
           effort as the OpenAI-compatible reasoning_effort — the mission's wins */
        Map<String, Object> extras = new LinkedHashMap<>(pr.extraParams);
        /* the OpenAI-compatible enum stops at high; xhigh/max are the claude
           CLI's (settings r1 H3) — clamp, never send a value the endpoint 400s on */
        if (m.effort != null) extras.put("reasoning_effort",
                "xhigh".equals(m.effort) || "max".equals(m.effort) ? "high" : m.effort);
        dev.workbench.llm.AgentLoop loop = new dev.workbench.llm.AgentLoop(
                dispatcher, m, client, model,
                new dev.workbench.llm.AgentLoop.Activity() {   // rows tagged mission+turn; the transcript lands on the mission (agent.turn)
                    int cur;
                    @Override public void row(String kind, String text) { act(m.agentId, kind, text, m.id, cur); }
                    @Override public void turn(int n) { cur = n; }
                    @Override public void record(Map<String, Object> rec) { recordTurn(m, rec); }
                },
                m.maxTurns > 0 ? m.maxTurns : dev.workbench.llm.Depth.loopTurns(m.depth, 14), extras, pr.contextTokens, wire);
        /* a configured capture that cannot attach blocks the mission: nothing dispatches uncaptured
           because a factory threw (the operator asked for capture) */
        if (captureFactory != null) {
            try { loop.capture(captureFactory.apply(m)); }
            catch (Exception e) {
                m.state = "blocked";
                m.stopReason = "capture could not be attached to this mission (" + e.getMessage() + ") — not dispatched";
                act(m.agentId, "blocked", m.stopReason);
                return;
            }
        }
        loop.onWindowLearned(n -> { try { llm.setContextTokens(pr.id, n); } catch (Exception e) { System.err.println("[agents] learned window not saved for " + pr.id + ": " + e); } });
        m.state = "running";
        /* the loop writes m.state directly (done/blocked) with no manager
           round-trip — the wrap is the single point where a NATURAL death
           releases a follow, exactly like stop() does an intentional one */
        Thread t = new Thread(() -> {
            try { loop.run(); }
            finally {
                /* the thread's return is the one confirmation that nothing of this mission is in flight
                   any more — a stopped mission stops being "draining" here, nowhere earlier */
                loopThreads.remove(m.id);
                if (m.cancellationUnconfirmed) { m.cancellationUnconfirmed = false; act(m.agentId, "note", "mission " + m.id + " stop confirmed — its loop has returned"); }
                releaseFollowIfTerminal(m);
            }
        }, "mission-" + m.id);
        t.setDaemon(true);
        loopThreads.put(m.id, t);
        t.start();
        act(m.agentId, "note", "http loop started: " + pr.label + " · " + model
                + (pr.local ? " · LOCAL" : ""));
    }

    // ------------------------------------------------------------ claude mode (headless claude -p)

    /** Launch `claude -p` with an MCP config pointing at mcp/workbench_mcp.py
     *  (old Jeeves Agent.java pattern: restricted toolset, no shell). */
    private void startClaude(Mission m) throws OpException {
        String cli = findClaude();
        if (cli == null) {
            m.state = "blocked";
            act(m.agentId, "blocked", "claude CLI not found — use mode scripted");
            return;
        }
        try {
            File cfg = File.createTempFile("wb-mcp-", ".json");
            java.nio.file.Files.writeString(cfg.toPath(), """
                    {"mcpServers":{"jeeves":{"command":"python3","args":["%s"]}}}
                    """.formatted(mcpScript().getAbsolutePath().replace("\"", "\\\"")));
            String prompt = "You are a reverse-engineering agent inside the workbench. Mission: "
                    + m.goal + dev.workbench.llm.Depth.brief(m.depth, m.goal)
                    + (m.scope.isBlank() ? "" : " Write-scope: " + m.scope
                    + " — all mutations outside it are BLOCKED.")
                    + " Use the workbench MCP tools. " + dev.workbench.llm.AgentLoop.RENAME_AS_YOU_GO
                    + " Budget: " + m.budget + " tokens. Be terse.";
            List<String> cmd = new ArrayList<>(List.of(cli, "-p", prompt,
                    "--mcp-config", cfg.getAbsolutePath(),
                    // explicit curated tool list, NOT a registry-wide wildcard:
                    // mission / review / llm must stay unreachable for missions
                    // (agents propose, humans dispose)
                    "--allowedTools", dev.workbench.llm.AgentLoop.curatedMcpTools(),
                    "--max-turns", String.valueOf(m.maxTurns > 0 ? m.maxTurns
                            : dev.workbench.llm.Depth.cliTurns(m.depth, 40))));
            /* the model: "claude-code" is the CLI's own default (no flag); an
               alias / full name from the provider's list goes as --model.
               The effort is the CLI's --effort (low … max). */
            String[] spec = llm.resolve(m.model);
            String cliModel = spec == null ? "" : spec[1];
            if (!cliModel.isBlank() && !"claude-code".equals(cliModel)) { cmd.add("--model"); cmd.add(cliModel); }
            if (m.effort != null) { cmd.add("--effort"); cmd.add(m.effort); }
            ProcessBuilder pb = new ProcessBuilder(cmd);
            // mission-bound actor: scopes apply (llm:mcp would be unscoped)
            pb.environment().put("WORKBENCH_ACTOR", "llm:" + m.agentId);
            /* the bridge advertises the whole facade (24 tools); --allowedTools above keeps a
               mission to the loop-visible nine (mission / review / llm stay unreachable), and the
               routed ops refuse an agent actor on them anyway (checkScope) */
            if (m.target != null) pb.environment().put("WORKBENCH_TARGET", m.target);   // the bridge pins every call (r1 M2)
            /* billing (AGENTS.md: headless claude runs `env -u ANTHROPIC_API_KEY`
               so the Max subscription pays, not a key the server's shell happened
               to export): strip the credential overrides — unless a base URL is
               set, which is a deliberate proxy/gateway config that needs its
               credential (fix-review reverse r1 M7). Said in the stream, so an
               auth failure afterwards is diagnosable. */
            Map<String, String> env = pb.environment();
            String baseUrl = env.get("ANTHROPIC_BASE_URL");
            String billing = applyBillingEnv(env);
            /* a cloud-provider / model override in the env is honoured (it is
               a deliberate config, like the base URL) — but the note must not
               claim the subscription paid when Bedrock/Vertex did (r2 R2-1) */
            StringBuilder overrides = new StringBuilder();
            for (String k : List.of("CLAUDE_CODE_USE_BEDROCK", "CLAUDE_CODE_USE_VERTEX", "ANTHROPIC_MODEL"))
                if (env.get(k) != null && !env.get(k).isBlank()) overrides.append(overrides.length() == 0 ? "" : ", ").append(k);
            if (overrides.length() > 0) billing += " · overrides in env: " + overrides;
            pb.redirectErrorStream(true);
            /* the log lives beside the other sidecars, named uniquely: the JVM
               temp dir's m<N>.log was overwritten by the next server's m<N>
               and the path the blocked row advertises went stale (r2 R2-13) */
            File logDir = new File(sidecarDir, "missions");
            logDir.mkdirs();
            File log = new File(logDir, m.id + "-" + m.startedAt + ".log");
            pb.redirectOutput(log);
            Process p = pb.start();
            /* the CLI waits 3 s for a piped prompt on stdin before it proceeds
               ("no stdin data received in 3s") — the prompt is the -p argument,
               so close the pipe at once: every mission started 3 s late */
            try { p.getOutputStream().close(); } catch (java.io.IOException ignored) {}
            procs.put(m.id, p);
            m.state = "running";
            Thread watcher = new Thread(() -> {
                try {
                    int rc = p.waitFor();
                    procs.remove(m.id);
                    try { java.nio.file.Files.deleteIfExists(cfg.toPath()); } catch (Exception ignored) {}   // the mission's mcp config outlives nothing (2026-09-15)
                    // QA m7: an intentional stop stays STOPPED — the watcher must
                    // not overwrite it with blocked (rc=143 from destroy())
                    if (!m.stopRequested){
                        m.state = rc == 0 ? "done" : "blocked";
                        releaseFollowIfTerminal(m);
                    }
                    /* a non-zero exit is a BLOCKED event (auth, turn cap, a crash):
                       only that kind reaches Mission.reason, and the card/inspector
                       showed a bare vermillion chip with no text (r1 H2). The log's
                       full path travels with it — the temp dir is not guessable. */
                    boolean failed = rc != 0 && !m.stopRequested;
                    act(m.agentId, failed ? "blocked" : "note", "mission " + m.id + " exited rc=" + rc
                            + (failed ? " — see the log: " : " · log ") + log.getAbsolutePath());
                } catch (InterruptedException ignored) {}
            }, "mission-watch-" + m.id);
            watcher.setDaemon(true);
            watcher.start();
            act(m.agentId, "note", "claude spawned for mission " + m.id
                    + (cliModel.isBlank() || "claude-code".equals(cliModel) ? " · model: CLI default" : " · model " + cliModel)
                    + (m.effort == null ? "" : " · effort " + m.effort)
                    + " · " + billing + " · log " + log.getAbsolutePath());
        } catch (Exception e) {
            m.state = "blocked";
            act(m.agentId, "blocked", "claude spawn failed: " + e.getMessage());
        }
    }

    /** The credential policy a headless `claude` child gets (AGENTS.md: the subscription
     *  pays, not a key the server's shell happened to export): ANTHROPIC_API_KEY and
     *  ANTHROPIC_AUTH_TOKEN are stripped unless ANTHROPIC_BASE_URL names a gateway that
     *  needs them; CLAUDE_CODE_OAUTH_TOKEN is a login (`claude setup-token` on a headless
     *  box) and is never stripped. system.doctor's login probe applies the same policy so
     *  the doctor's "logged in" is the spawn's (prov review r3 H1). Returns the note that
     *  goes in the mission stream. */
    public static String applyBillingEnv(Map<String, String> env) {
        String baseUrl = env.get("ANTHROPIC_BASE_URL");
        if (baseUrl == null || baseUrl.isBlank()) {
            boolean hadKey = env.remove("ANTHROPIC_API_KEY") != null;
            boolean hadTok = env.remove("ANTHROPIC_AUTH_TOKEN") != null;
            return "claude login (subscription)" + (hadKey || hadTok
                    ? " — " + (hadKey ? "ANTHROPIC_API_KEY " : "") + (hadTok ? "ANTHROPIC_AUTH_TOKEN " : "")
                            + "stripped from the child env" : "");
        }
        return "gateway " + baseUrl + " (credentials passed through)";
    }

    private static String findClaude() {
        return dev.workbench.llm.LlmRegistry.claudeBinary();   // one lookup (PATH-aware) for CLI + spawn + doctor
    }

    private static File mcpScript() throws OpException {
        for (String base : List.of("mcp/workbench_mcp.py", "../mcp/workbench_mcp.py")) {
            File f = new File(base);
            if (f.isFile()) return f.getAbsoluteFile();
        }
        throw OpException.unsupported("mcp/workbench_mcp.py not found", "");
    }
}
