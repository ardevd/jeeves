package dev.workbench.it;

import dev.workbench.registry.Json;
import dev.workbench.server.WorkbenchServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * M12 — the scope model (UI audit batch 2, 2026-09-10): runs, breakpoints and proposals
 * are PROJECT-scoped. Hermetic: WORKBENCH_HOME is a throwaway root (build.gradle m12IT),
 * WORKBENCH_MOCK=1 gives the fixture scene (project "privacy-invite-flow", mock targets
 * t1..t4) so there is a boot project for the legacy fold to land in.
 *
 * Checks, in order:
 *  1. the legacy global runs/ + proposals.json fold into the boot project's sidecar
 *     (per file, parked as runs.migrated), and the ops answer from the project store;
 *  2. [target] filters on runs.runs / runs.bp.list match the live id;
 *  3. a fresh project sees NO runs, breakpoints or proposals;
 *  4. a proposal filed while another project is active lands in THAT project's queue,
 *     carries project/target stamps, and never appears in the other queue;
 *  5. agent.review settles against the active project's queue;
 *  6. project.close of the fresh project lands back on the boot project with its data;
 *  7. with no project open the ops refuse honestly (E_NOTFOUND), not with a global store.
 */
public final class M12IT {

    private static final String BASE = "http://127.0.0.1:" + WorkbenchServer.PORT;
    private static int failures = 0, checks = 0;

    public static void main(String[] args) throws Exception {
        String home = System.getenv("WORKBENCH_HOME");
        if (home == null || home.isBlank()) { System.err.println("M12IT needs WORKBENCH_HOME (gradle m12IT)"); System.exit(2); }
        Path root = Path.of(home);
        seedLegacy(root);

        WorkbenchServer server = new WorkbenchServer(Path.of("web"));
        server.start();
        System.out.println("server up on " + BASE + " home=" + root);
        try {
            run(root);
        } finally {
            try { server.stop(); } catch (Throwable ignored) {}
        }
        System.out.println(failures == 0 ? "M12 IT PASSED: " + checks + " checks green"
                : "M12 IT FAILED: " + failures + " of " + checks + " checks");
        System.exit(failures == 0 ? 0 : 1);
    }

    private static void seedLegacy(Path root) throws IOException {
        Path runs = root.resolve("runs");
        Files.createDirectories(runs);
        List<Object> index = new ArrayList<>();
        index.add(run("ex1", "t1", "com.example.a", 1000L));
        index.add(run("ex2", "t9", "com.example.b", 2000L));   // a target id no open project holds
        Files.writeString(runs.resolve("index.json"), Json.write(index));
        Map<String, Object> bp = new LinkedHashMap<>();
        bp.put("id", "bp1"); bp.put("target", "t1"); bp.put("origSig", "Lcom/example/a/M;->m()V");
        bp.put("addr", "Lcom/example/a/M;->m()V"); bp.put("name", "m"); bp.put("owner", "com.example.a.M");
        bp.put("enabled", true); bp.put("hits", 0L);
        Files.writeString(runs.resolve("bps.json"), Json.write(List.of(bp)));
        Files.writeString(runs.resolve("ex1.jsonl"), "");
        List<Object> props = new ArrayList<>();
        props.add(prop("pp1", "pending", "t1"));
        props.add(prop("pp2", "accepted", "t1"));
        Files.writeString(root.resolve("proposals.json"), Json.write(props));
    }

    private static Map<String, Object> run(String execId, String target, String pkg, long ts) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("execId", execId); m.put("session", "s-" + execId); m.put("device", "emulator-5554");
        m.put("package", pkg); m.put("version", null); m.put("target", target); m.put("color", 0);
        m.put("startTs", ts); m.put("endTs", ts + 1); m.put("hitCount", 0L); m.put("bpIds", List.of());
        m.put("coverage", List.of());
        return m;
    }

    private static Map<String, Object> prop(String id, String state, String target) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id); m.put("kind", "comment"); m.put("state", state); m.put("agentId", "agent-legacy");
        m.put("target", target); m.put("symbol", "com.example.a.M.m"); m.put("newText", "legacy note");
        m.put("ts", "2026-09-09T00:00:00Z");
        return m;
    }

    private static void run(Path root) throws Exception {
        // ---- 1. the fold
        Map<String, Object> st = data(post("workspace.state", Map.of()));
        String bootName = null;
        for (Object o : (List<?>) st.get("projects"))
            if (Boolean.TRUE.equals(((Map<?, ?>) o).get("active"))) bootName = String.valueOf(((Map<?, ?>) o).get("name"));
        check(bootName != null, "a boot project is active (" + bootName + ")");
        Path bootDir = root.resolve("projects").resolve(bootName.toLowerCase().replaceAll("[^a-z0-9]+", "-"));
        check(Files.isRegularFile(bootDir.resolve("runs").resolve("index.json")), "legacy index.json folded into " + bootDir.resolve("runs"));
        check(Files.isRegularFile(bootDir.resolve("runs").resolve("bps.json")), "legacy bps.json folded");
        check(Files.isRegularFile(bootDir.resolve("runs").resolve("ex1.jsonl")), "legacy hit log folded");
        check(Files.isDirectory(root.resolve("runs.migrated")), "legacy runs/ parked as runs.migrated");
        check(!Files.exists(root.resolve("proposals.json")), "legacy proposals.json gone from the root");
        check(Files.isRegularFile(bootDir.resolve("proposals.json")), "proposals.json folded into the project dir");

        List<Object> runs = list(post("runs.runs", Map.of()));
        check(runs.size() == 2, "runs.runs (boot project) → 2 legacy runs, got " + runs.size());
        List<Object> bps = list(post("runs.bp.list", Map.of()));
        check(bps.size() == 1, "runs.bp.list (boot project) → 1 legacy bp, got " + bps.size());
        List<Object> props = list(post("agent.proposals", Map.of()));
        check(props.size() == 1 && "pp1".equals(((Map<?, ?>) props.get(0)).get("id")),
                "agent.proposals (boot project) → the one pending legacy proposal");

        // ---- 2. target filters (the fixture's first target is t1)
        List<Object> t1runs = list(post("runs.runs", Map.of("target", "t1")));
        check(t1runs.size() == 1 && "ex1".equals(((Map<?, ?>) t1runs.get(0)).get("execId")), "runs.runs {target:t1} → ex1 only");
        check(list(post("runs.bp.list", Map.of("target", "t1"))).size() == 1, "runs.bp.list {target:t1} → 1");
        check(list(post("agent.proposals", Map.of("target", "t1"))).size() == 1, "agent.proposals {target:t1} → 1");
        Map<String, Object> bad = post("runs.runs", Map.of("target", "t77"));
        check(!Boolean.TRUE.equals(bad.get("ok")), "runs.runs {target:t77} refuses an unknown target");

        // ---- 3. a fresh project is empty
        check(Boolean.TRUE.equals(post("project.open", Map.of("name", "beta")).get("ok")), "project.open beta");
        check(list(post("runs.runs", Map.of())).isEmpty(), "beta: runs.runs → 0");
        check(list(post("runs.bp.list", Map.of())).isEmpty(), "beta: runs.bp.list → 0");
        check(list(post("agent.proposals", Map.of())).isEmpty(), "beta: agent.proposals → 0");

        // ---- 4. a proposal filed in beta stays in beta
        Map<String, Object> filed = post("comment", Map.of("symbol", "x.Y.z", "text", "beta note"), "llm:agent-9");
        check(Boolean.TRUE.equals(filed.get("ok")) && Boolean.TRUE.equals(data(filed).get("proposal")),
                "an agent comment in beta files a proposal");
        List<Object> betaProps = list(post("agent.proposals", Map.of()));
        check(betaProps.size() == 1, "beta: agent.proposals → 1, got " + betaProps.size());
        Map<?, ?> bp0 = (Map<?, ?>) betaProps.get(0);
        check("beta".equals(bp0.get("project")), "the proposal is stamped project=beta (got " + bp0.get("project") + ")");
        String betaId = String.valueOf(bp0.get("id"));
        check(Files.isRegularFile(root.resolve("projects").resolve("beta").resolve("proposals.json")), "beta's queue persisted under projects/beta/");
        check(Boolean.TRUE.equals(post("project.switch", Map.of("project", bootName)).get("ok")), "project.switch back to " + bootName);
        props = list(post("agent.proposals", Map.of()));
        check(props.size() == 1 && "pp1".equals(((Map<?, ?>) props.get(0)).get("id")), "boot project still sees only pp1");
        check(list(post("runs.runs", Map.of())).size() == 2, "boot project still sees its 2 runs");

        // ---- 4b. missions default to the pinned target's scope (audit decision 3)
        /* an http mission on the local (key-less) ollama provider: the loop blocks on the absent
           daemon, but the spawn has already stamped scope/project (a scripted triage mission
           overwrites its scope with pkg:<prefix> by design, so it cannot show the default) */
        Map<String, Object> sp = post("agent.spawn", Map.of("goal", "explain com.example.a", "model", "ollama:test"));
        check(Boolean.TRUE.equals(sp.get("ok")) && ("target:" + data(sp).get("target")).equals(data(sp).get("scope")),
                "agent.spawn without a scope → scope target:<pinned> (got " + data(sp).get("scope") + ")");
        check(bootName.equals(data(sp).get("project")), "the mission carries its project (" + data(sp).get("project") + ")");
        Map<String, Object> sp2 = post("agent.spawn", Map.of("goal", "explain com.example.a", "model", "ollama:test", "scope", "project"));
        check(Boolean.TRUE.equals(sp2.get("ok")) && "project".equals(data(sp2).get("scope")),
                "agent.spawn {scope:project} → the whole-project scope");
        Map<String, Object> widen = post("agent.setscope", Map.of("id", String.valueOf(data(sp).get("id")), "scope", "project"), "llm:agent-x");
        check(!Boolean.TRUE.equals(widen.get("ok")), "an agent cannot widen target:<id> → project without the human");

        // ---- 4c. agent.purge: a criterion is required; an agent cannot; a matching row settles as purged
        Map<String, Object> pg0 = post("agent.purge", Map.of());
        check(!Boolean.TRUE.equals(pg0.get("ok")) && "E_SYNTAX".equals(errCode(pg0)), "agent.purge without a criterion is E_SYNTAX");
        Map<String, Object> pgA = post("agent.purge", Map.of("noRationale", true), "llm:agent-9");
        check(!Boolean.TRUE.equals(pgA.get("ok")) && "E_SCOPE".equals(errCode(pgA)), "an agent cannot purge (E_SCOPE)");
        Map<String, Object> pgFuture = post("agent.purge", Map.of("before", "2000-01-01T00:00:00Z"));
        check(Boolean.TRUE.equals(pgFuture.get("ok")) && ((Number) data(pgFuture).get("count")).intValue() == 0, "agent.purge before 2000 matches nothing");

        // ---- 5. review settles against the ACTIVE queue
        check(!"pp1".equals(betaId) && !"pp2".equals(betaId), "beta's proposal id (" + betaId + ") does not collide with the boot project's");
        Map<String, Object> wrongQueue = post("agent.review", Map.of("id", betaId, "action", "reject"));
        check(!Boolean.TRUE.equals(wrongQueue.get("ok")) && "E_NOTFOUND".equals(errCode(wrongQueue)),
                "reviewing beta's proposal id from the boot project is E_NOTFOUND, not a settle");
        check(Files.isRegularFile(root.resolve("proposals.seq")), "proposals.seq persisted beside projects/");
        check(Boolean.TRUE.equals(post("project.switch", Map.of("project", "beta")).get("ok")), "project.switch beta");
        Map<String, Object> rej = post("agent.review", Map.of("id", betaId, "action", "reject"));
        check(Boolean.TRUE.equals(rej.get("ok")) && "rejected".equals(data(rej).get("state")), "beta: agent.review reject → rejected");
        check(list(post("agent.proposals", Map.of())).isEmpty(), "beta: queue empty after the reject");

        // ---- 6. close beta → back on the boot project with its data
        check(Boolean.TRUE.equals(post("project.close", Map.of("project", "beta")).get("ok")), "project.close beta");
        check(list(post("runs.runs", Map.of())).size() == 2, "after closing beta: runs.runs → 2 (boot project)");

        // ---- 6b. runs.rm: one run, an unknown one, an agent actor, then every ended run
        // M2 (b2-r5): a target scopes runs.rm to ITS runs — ex2 is t9's, not t1's → E_NOTFOUND
        Map<String, Object> rmWrongTarget = post("runs.rm", Map.of("target", "t1", "run", "ex2"));
        check(!Boolean.TRUE.equals(rmWrongTarget.get("ok")) && "E_NOTFOUND".equals(errCode(rmWrongTarget)),
                "runs.rm {target:t1, run:ex2} → E_NOTFOUND (ex2 is not t1's run)");
        check(list(post("runs.runs", Map.of())).size() == 2, "the wrong-target rm deleted nothing");
        Map<String, Object> rm1 = post("runs.rm", Map.of("run", "ex2"));
        check(Boolean.TRUE.equals(rm1.get("ok")) && ((Number) data(rm1).get("count")).intValue() == 1, "runs.rm ex2 → 1 removed");
        check(list(post("runs.runs", Map.of())).size() == 1, "runs.runs → 1 after the delete");
        // M1 (b2-r5): the full index row is preserved in removed.jsonl, not just the hit log
        java.nio.file.Path removedLog = bootDir.resolve("runs").resolve("removed.jsonl");
        boolean rowKept = Files.isRegularFile(removedLog)
                && Files.readAllLines(removedLog).stream().anyMatch(l -> l.contains("\"ex2\"") && l.contains("com.example.b"));
        check(rowKept, "runs.rm preserves the deleted run's index row (device/pkg) in removed.jsonl");
        Map<String, Object> rmBad = post("runs.rm", Map.of("run", "ex999"));
        check(!Boolean.TRUE.equals(rmBad.get("ok")) && "E_NOTFOUND".equals(errCode(rmBad)), "runs.rm ex999 → E_NOTFOUND");
        Map<String, Object> rmAgent = post("runs.rm", Map.of("run", "ex1"), "llm:agent-9");
        check(!Boolean.TRUE.equals(rmAgent.get("ok")) && "E_SCOPE".equals(errCode(rmAgent)), "an agent cannot delete runs (E_SCOPE)");
        Map<String, Object> rmAll = post("runs.rm", Map.of("run", "all"));
        check(Boolean.TRUE.equals(rmAll.get("ok")) && ((Number) data(rmAll).get("count")).intValue() == 1, "runs.rm all → the remaining run removed");
        check(list(post("runs.runs", Map.of())).isEmpty(), "runs.runs → 0 after runs.rm all");
        boolean parked = false;
        try (var files = Files.list(bootDir.resolve("runs"))) { parked = files.anyMatch(f -> f.getFileName().toString().startsWith("ex1.jsonl.deleted-")); }
        check(parked, "the deleted run's hit log was parked, not unlinked");
        check(list(post("agent.proposals", Map.of())).size() == 1, "after closing beta: agent.proposals → 1 (boot project)");

        // ---- 7. no project at all: honest refusal
        check(Boolean.TRUE.equals(post("project.close", Map.of("project", bootName)).get("ok")), "project.close " + bootName + " (last project)");
        Map<String, Object> noRuns = post("runs.runs", Map.of());
        check(!Boolean.TRUE.equals(noRuns.get("ok")) && "E_NOTFOUND".equals(errCode(noRuns)), "no project: runs.runs → E_NOTFOUND");
        Map<String, Object> noProps = post("agent.proposals", Map.of());
        check(!Boolean.TRUE.equals(noProps.get("ok")) && "E_NOTFOUND".equals(errCode(noProps)), "no project: agent.proposals → E_NOTFOUND");
        Map<String, Object> noFile = post("comment", Map.of("symbol", "x.Y.z", "text", "nowhere"), "llm:agent-9");
        check(!Boolean.TRUE.equals(noFile.get("ok")), "no project: an agent comment cannot be filed anywhere");
        Map<String, Object> noSpawn = post("agent.spawn", Map.of("goal", "triage-package x"));
        check(!Boolean.TRUE.equals(noSpawn.get("ok")) && "E_SYNTAX".equals(errCode(noSpawn)), "no project, no target: agent.spawn is refused (E_SYNTAX)");
    }

    // ------------------------------------------------------------ helpers

    private static void check(boolean ok, String what) {
        checks++;
        if (ok) System.out.println("  ok   " + what);
        else { failures++; System.out.println("  FAIL " + what); }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> data(Map<String, Object> resp) {
        Object d = resp.get("data");
        return d instanceof Map ? (Map<String, Object>) d : new LinkedHashMap<>();
    }

    @SuppressWarnings("unchecked")
    private static List<Object> list(Map<String, Object> resp) {
        Object d = resp.get("data");
        return d instanceof List ? (List<Object>) d : new ArrayList<>();
    }

    private static String errCode(Map<String, Object> resp) {
        Object e = resp.get("error");
        return e instanceof Map<?, ?> m ? String.valueOf(m.get("code")) : "";
    }

    private static Map<String, Object> post(String op, Map<String, Object> params) throws Exception {
        return post(op, params, "human");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> post(String op, Map<String, Object> params, String actor) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("params", params);
        body.put("actor", actor);
        HttpURLConnection c = (HttpURLConnection) URI.create(BASE + "/api/" + op).toURL().openConnection();
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "application/json");
        try (OutputStream os = c.getOutputStream()) { os.write(Json.write(body).getBytes(StandardCharsets.UTF_8)); }
        int code = c.getResponseCode();
        byte[] raw = (code >= 400 ? c.getErrorStream() : c.getInputStream()).readAllBytes();
        return (Map<String, Object>) Json.parse(new String(raw, StandardCharsets.UTF_8));
    }
}
