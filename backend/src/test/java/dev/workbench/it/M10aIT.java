package dev.workbench.it;

import dev.workbench.llm.AgentLoop;
import dev.workbench.registry.Dispatcher;
import dev.workbench.registry.Json;
import dev.workbench.registry.Op;
import dev.workbench.registry.Registry;
import dev.workbench.server.AgentManager;
import dev.workbench.server.WorkbenchServer;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * M10a integration test (plain main-class, no JUnit): multi-LLM support —
 * provider registry (built-ins, local/configured flags), llm.configure →
 * llm.json persistence, write-only keys (llm.setkey, chmod 600, env override
 * precedence, key never in llm.providers output), and the http agent loop on
 * the `fixture` provider (canned tool calls → rename proposal + activity +
 * budget accounting). Prints PASS/FAIL; exit 1 on failure. Run: gradle m10aIT.
 */
public final class M10aIT {

    private static int failures = 0, checks = 0;
    private static final HttpClient http = HttpClient.newHttpClient();
    private static final String BASE = "http://127.0.0.1:" + WorkbenchServer.PORT;
    private static final String SIDECAR = sidecarDir();

    public static void main(String[] args) throws Exception {
        try {
            run();
        } catch (Throwable t) {
            System.out.println("M10a IT FAILED with exception: " + t);
            t.printStackTrace(System.out);
            System.exit(1);
        }
    }

    private static String sidecarDir() {
        String home = System.getenv("WORKBENCH_HOME");   // hermetic since batch 2 (build.gradle m10aIT)
        if (home != null && !home.isBlank()) return home;
        for (String p : List.of("backend/.workbench", ".workbench"))
            if (Files.isDirectory(Path.of(p))) return p;
        return "backend/.workbench";
    }

    private static void run() throws Exception {
        // back up any pre-existing user llm config/keys; restored in finally
        Path cfg = Path.of(SIDECAR, "llm.json");
        Path keysFile = Path.of(SIDECAR, "llm-keys.json");
        Path tasksFile = Path.of(SIDECAR, "tasks.json");
        /* proposals.json too: mission ids restart per server, so this test's
           agent-N collides with the user's earlier agent-N and the cleanup
           below rejected the user's REAL pending proposals (2026-09-09) */
        Path propsFile = Path.of(SIDECAR, "proposals.json");
        String cfgBak = Files.isRegularFile(cfg) ? Files.readString(cfg) : null;
        String keysBak = Files.isRegularFile(keysFile) ? Files.readString(keysFile) : null;
        String tasksBak = Files.isRegularFile(tasksFile) ? Files.readString(tasksFile) : null;
        String propsBak = Files.isRegularFile(propsFile) ? Files.readString(propsFile) : null;
        Files.deleteIfExists(cfg);
        Files.deleteIfExists(keysFile);
        Files.deleteIfExists(tasksFile);
        Files.deleteIfExists(propsFile);
        try {
            WorkbenchServer server = new WorkbenchServer(Path.of("web"));
            server.start();
            System.out.println("server up on " + BASE);

            // ---- registry: built-ins present with correct flags
            @SuppressWarnings("unchecked")
            List<Object> provs = (List<Object>) data(post("llm.providers", Map.of()));
            Map<String, Map<?, ?>> byId = new LinkedHashMap<>();
            for (Object o : provs) {
                Map<?, ?> p = (Map<?, ?>) o;
                byId.put(String.valueOf(p.get("id")), p);
            }
            for (String id : List.of("scripted", "anthropic", "deepseek", "openrouter",
                    "ollama", "custom", "fixture"))
                check(byId.containsKey(id), "provider built-in present: " + id);
            check(byId.get("deepseek") != null && Boolean.FALSE.equals(byId.get("deepseek").get("configured"))
                            && "https://api.deepseek.com".equals(byId.get("deepseek").get("baseUrl"))
                            && "deepseek-v4-pro".equals(byId.get("deepseek").get("model")),
                    "deepseek unconfigured, default model deepseek-v4-pro");
            // extraParams: DeepSeek carries thinking mode, nobody else sends extras
            Object dsExtra = byId.get("deepseek") == null ? null : byId.get("deepseek").get("extraParams");
            check(dsExtra != null && String.valueOf(dsExtra).contains("enabled")
                            && String.valueOf(dsExtra).contains("high"),
                    "deepseek extraParams pin thinking mode (enabled/high)");
            for (String id : List.of("openrouter", "ollama", "custom", "fixture"))
                check("{}".equals(String.valueOf(byId.get(id).get("extraParams"))),
                        id + " sends no extraParams (unknown-field safe)");
            check(byId.get("openrouter") != null && Boolean.FALSE.equals(byId.get("openrouter").get("configured")),
                    "openrouter unconfigured without key");
            check(byId.get("ollama") != null && Boolean.TRUE.equals(byId.get("ollama").get("local"))
                            && Boolean.TRUE.equals(byId.get("ollama").get("configured")),
                    "ollama LOCAL + needs no key (configured)");
            check(byId.get("fixture") != null && Boolean.TRUE.equals(byId.get("fixture").get("local"))
                            && "fixture://loop".equals(byId.get("fixture").get("baseUrl")),
                    "fixture LOCAL canned provider present");

            // ---- llm.configure persists to llm.json
            data(post("llm.configure", Map.of("id", "custom", "baseUrl", "http://127.0.0.1:9999",
                    "model", "my-model-7b")));
            check(Files.isRegularFile(cfg) && Files.readString(cfg).contains("http://127.0.0.1:9999")
                            && Files.readString(cfg).contains("my-model-7b"),
                    "llm.configure persisted to llm.json");
            @SuppressWarnings("unchecked")
            List<Object> provs2 = (List<Object>) data(post("llm.providers", Map.of()));
            Map<?, ?> custom = null;
            for (Object o : provs2)
                if ("custom".equals(((Map<?, ?>) o).get("id"))) custom = (Map<?, ?>) o;
            check(custom != null && "http://127.0.0.1:9999".equals(custom.get("baseUrl"))
                            && "my-model-7b".equals(custom.get("model")),
                    "llm.providers reflects the configured baseUrl + default model");

            // ---- llm.setkey: write-only, chmod 600, never in llm.providers
            String probe = "sk-probe-DEADBEEF-12345";
            Map<String, Object> sk = data(post("llm.setkey", Map.of("id", "deepseek", "key", probe)));
            check(Boolean.TRUE.equals(sk.get("configured")), "llm.setkey → configured:true");
            check(Files.isRegularFile(keysFile), "llm-keys.json written");
            String perms = PosixFilePermissions.toString(Files.getPosixFilePermissions(keysFile));
            check("rw-------".equals(perms), "llm-keys.json chmod 600 (got " + perms + ")");
            String provsJson = Json.write(data(post("llm.providers", Map.of())));
            check(!provsJson.contains(probe), "key material NEVER in llm.providers output");
            @SuppressWarnings("unchecked")
            List<Object> provs3 = (List<Object>) data(post("llm.providers", Map.of()));
            boolean dsConfigured = false;
            for (Object o : provs3)
                if ("deepseek".equals(((Map<?, ?>) o).get("id")))
                    dsConfigured = Boolean.TRUE.equals(((Map<?, ?>) o).get("configured"));
            check(dsConfigured, "deepseek flips configured:true after setkey");

            // ---- env override precedence (unit seam: KeyStore in this JVM;
            // gradle task sets WORKBENCH_LLM_ENVTEST_KEY=env-value-12345)
            dev.workbench.llm.KeyStore unit = new dev.workbench.llm.KeyStore(
                    Files.createTempDirectory("wb-keystest").toFile());
            unit.set("envtest", "file-value");
            check("env-value-12345".equals(unit.get("envtest")),
                    "env override WORKBENCH_LLM_ENVTEST_KEY beats the file");
            check(unit.has("envtest"), "env key alone counts as configured");

            // ---- URL builder: the three baseUrl forms (QA M2 — glm52 404)
            check("https://api.deepseek.com/v1/chat/completions"
                            .equals(dev.workbench.llm.AgentLoop.endpointFor("https://api.deepseek.com")),
                    "URL builder: bare host → +/v1/chat/completions");
            check("https://api.z.ai/api/paas/v4/chat/completions"
                            .equals(dev.workbench.llm.AgentLoop.endpointFor("https://api.z.ai/api/paas/v4")),
                    "URL builder: version-suffixed path → +/chat/completions only");
            check("https://api.moonshot.ai/v1/chat/completions"
                            .equals(dev.workbench.llm.AgentLoop.endpointFor("https://api.moonshot.ai/v1/chat/completions")),
                    "URL builder: full endpoint used as-is");
            check("https://api.moonshot.ai/v1/chat/completions"
                            .equals(dev.workbench.llm.AgentLoop.endpointFor("https://api.moonshot.ai/v1")),
                    "URL builder: /v1 path → +/chat/completions only");

            // ---- hard budget ceiling: fixture mission with budget 900 — turn 1
            // goes through (150 tok), the next request is capped/paused by the
            // ceiling or the prompt estimate; tokensUsed must never exceed budget
            Map<String, Object> tight = data(post("agent.spawn",
                    Map.of("goal", "tiny budget probe", "model", "fixture:fixture-1", "budget", 900)));
            String tightId = String.valueOf(tight.get("id"));
            long tDeadline = System.currentTimeMillis() + 20_000;
            Map<String, Object> tightDone = null;
            while (System.currentTimeMillis() < tDeadline) {
                @SuppressWarnings("unchecked")
                List<Object> ms = (List<Object>) data(post("agent.missions", Map.of()));
                for (Object o : ms) {
                    Map<String, Object> m = (Map<String, Object>) o;
                    if (tightId.equals(m.get("id"))) tightDone = m;
                }
                if (tightDone != null && !"running".equals(tightDone.get("state"))
                        && !"queued".equals(tightDone.get("state"))) break;
                Thread.sleep(300);
            }
            check(tightDone != null && "paused".equals(tightDone.get("state"))
                            && ((Number) tightDone.get("tokensUsed")).intValue()
                            <= ((Number) tightDone.get("budget")).intValue(),
                    "budget hard ceiling: paused at " + (tightDone == null ? "?" : tightDone.get("tokensUsed"))
                            + "/" + (tightDone == null ? "?" : tightDone.get("budget")));

            // ---- http mission on the fixture provider: full loop, no key
            Map<String, Object> mission = data(post("agent.spawn",
                    Map.of("goal", "triage the gmail sync classes", "model", "fixture:fixture-1")));
            check("http".equals(mission.get("mode")), "mode inferred http from the provider driver");
            check("fixture".equals(mission.get("provider"))
                            && Boolean.TRUE.equals(mission.get("local")),
                    "mission carries provider + LOCAL badge fields");
            String mid = String.valueOf(mission.get("id"));
            long deadline = System.currentTimeMillis() + 30_000;
            String state = "";
            Map<String, Object> done = null;
            while (System.currentTimeMillis() < deadline) {
                @SuppressWarnings("unchecked")
                List<Object> ms = (List<Object>) data(post("agent.missions", Map.of()));
                for (Object o : ms) {
                    Map<?, ?> m = (Map<?, ?>) o;
                    if (mid.equals(m.get("id"))) { state = String.valueOf(m.get("state")); done = (Map<String, Object>) m; }
                }
                if ("done".equals(state) || "blocked".equals(state) || "paused".equals(state)) break;
                Thread.sleep(400);
            }
            check("done".equals(state), "fixture http mission runs to done (state=" + state + ")");
            check(done != null && ((Number) done.get("tokensUsed")).intValue() > 0,
                    "budget accounting: tokensUsed=" + (done == null ? "?" : done.get("tokensUsed"))
                            + "/" + (done == null ? "?" : done.get("budget")));

            // rename proposal created by the loop (canned turn 3)
            @SuppressWarnings("unchecked")
            List<Object> props = (List<Object>) data(post("agent.proposals", Map.of()));
            boolean rename = false;
            for (Object o : props) {
                Map<?, ?> p = (Map<?, ?>) o;
                if ("rename".equals(p.get("kind")) && "onMessage".equals(p.get("symbol"))
                        && "onEnvelope".equals(p.get("newName"))) rename = true;
            }
            check(rename, "rename proposal onMessage → onEnvelope arrived in the queue");

            // activity rows: tool calls + token usage recorded
            @SuppressWarnings("unchecked")
            List<Object> acts = (List<Object>) data(post("agent.activity",
                    Map.of("agent", mission.get("agentId"), "count", 50)));
            String feed = String.valueOf(acts);
            check(feed.contains("symbols") && feed.contains("code") && feed.contains("annotate"),
                    "activity stream has per-turn tool rows (symbols/code/annotate)");
            check(feed.contains("tokens"), "activity stream records token usage");

            contextEconomyChecks();
            configurationChecks(cfg, tasksFile);

            // cleanup: settle the fixture proposal so it doesn't litter the queue
            for (Object o : props) {
                Map<?, ?> p = (Map<?, ?>) o;
                if ("pending".equals(p.get("state")))
                    post("agent.review", Map.of("id", p.get("id"), "action", "reject"));
            }
        } finally {
            // NOTE: System.exit skips finally — the verdict/exit below lives
            // OUTSIDE this block or the restore would never run (it once
            // clobbered the user's real llm.json / llm-keys.json).
            try {
                if (cfgBak != null) Files.writeString(cfg, cfgBak); else Files.deleteIfExists(cfg);
                if (keysBak != null) Files.writeString(keysFile, keysBak); else Files.deleteIfExists(keysFile);
                if (tasksBak != null) Files.writeString(tasksFile, tasksBak); else Files.deleteIfExists(tasksFile);
                if (propsBak != null) Files.writeString(propsFile, propsBak); else Files.deleteIfExists(propsFile);
            } catch (Exception ignored) {}
        }

        System.out.println();
        if (failures > 0) {
            System.out.println("M10a IT FAILED: " + failures + "/" + checks + " checks failed");
            System.exit(1);
        }
        System.out.println("M10a IT PASSED: " + checks + " checks green");
        System.exit(0);
    }

    // ================================================================ context economy
    // Direct AgentLoop checks (no server, no provider): a scripted ChatClient
    // plus a stub Dispatcher whose code.decompile returns an oversized payload.
    // Covers the per-result cap, the whole-history policy below the model window
    // (every request extends the previous one verbatim, so the provider's prefix
    // cache keeps hitting), oldest-first eviction above the window (the working
    // set and the subject's decompile stay), the reasoning strip, a provider
    // refusal adopting the stated window, and the actionable pause text.

    /** Chars of "decompiled source" the stub op returns per call. */
    private static final int OVERSIZED = 30_000;
    /** AgentLoop's per-result cap — the loop's own constant, mirrored here. */
    private static final int CAP = 6_000;
    /** AgentLoop's working set: results of the last KEEP_TURNS turns are never evicted. */
    private static final int KEEP_TURNS = 3;
    /** A model window small enough that a 12-turn run must evict (AgentLoop's ceiling is the window). */
    private static final int SMALL_WINDOW = 16_000;

    /** Only the three ops the scripted client calls; decompile is the fat one. */
    private static Dispatcher stubDispatcher() {
        Registry reg = new Registry();
        reg.register(new Op("code.decompile", "", false, Op.Cost.cheap,
                r -> Map.of("source", "x".repeat(OVERSIZED))));
        reg.register(new Op("symbol.tree", "", false, Op.Cost.cheap,
                r -> Map.of("nodes", List.of("Lcom/example/A;"))));
        /* the facade routes annotate → rename {items}: the stub answers the batch shape */
        reg.register(new Op("rename", "", true, Op.Cost.cheap,
                r -> Map.of("applied", 1, "pending", 0, "failed", 0,
                        "results", List.of(Map.of("symbol", "onMessage", "status", "applied", "id", "p1")))));
        return new Dispatcher(reg);
    }

    /** Scripted client: `turns-1` decompile calls (each with a reasoning trace),
     *  then final text. Records the prompt it was handed on every turn. */
    private static final class Scripted implements AgentLoop.ChatClient {
        private final int turns;
        final List<Integer> promptChars = new ArrayList<>();
        final List<List<Object>> sent = new ArrayList<>();
        volatile int turn;
        /** turn 1 decompiles this symbol instead of Lcom/example/A1; (to pin a mission subject) */
        String subjectSymbol;
        /** the N-th call throws `refusal` once (a provider's context-length refusal); the retry runs normally */
        int refuseOnTurn;
        String refusal;
        boolean refused;

        Scripted(int turns) { this.turns = turns; }

        @Override
        public Map<String, Object> chat(Map<String, Object> req) {
            if (refuseOnTurn > 0 && turn + 1 == refuseOnTurn && !refused) {
                refused = true;                          // not recorded: the loop must retry this turn
                throw new RuntimeException(refusal);
            }
            @SuppressWarnings("unchecked")
            List<Object> msgs = (List<Object>) req.get("messages");
            promptChars.add(Json.write(msgs).length());
            // shallow copy is a stable snapshot: the loop REPLACES entries, never
            // mutates the maps in place
            sent.add(new ArrayList<>(msgs));
            turn++;
            Map<String, Object> msg = new LinkedHashMap<>();
            msg.put("role", "assistant");
            msg.put("reasoning_content", "REASONING-" + turn + " " + "r".repeat(400));
            if (turn < turns) {
                Map<String, Object> fn = new LinkedHashMap<>();
                fn.put("name", "code");
                String sym = turn == 1 && subjectSymbol != null ? subjectSymbol : "Lcom/example/A" + turn + ";";
                fn.put("arguments", "{\"symbol\": \"" + sym + "\"}");
                Map<String, Object> tc = new LinkedHashMap<>();
                tc.put("id", "call-" + turn);
                tc.put("type", "function");
                tc.put("function", fn);
                msg.put("tool_calls", List.of(tc));
            } else {
                msg.put("content", "analysis complete");
            }
            Map<String, Object> choice = new LinkedHashMap<>();
            choice.put("index", 0);
            choice.put("message", msg);
            choice.put("finish_reason", turn < turns ? "tool_calls" : "stop");
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("choices", List.of(choice));
            out.put("usage", Map.of("prompt_tokens", 100, "completion_tokens", 20,
                    "total_tokens", 120));
            return out;
        }
    }

    private static AgentManager.Mission mission(String id, int budget) {
        AgentManager.Mission m = new AgentManager.Mission();
        m.id = id;
        m.agentId = "agent-" + id;
        m.goal = "decompile the sync classes";
        m.model = "scripted-1";
        m.budget = budget;
        m.state = "running";
        return m;
    }

    private static String role(Object o) {
        return o instanceof Map<?, ?> m ? String.valueOf(m.get("role")) : "";
    }

    private static String content(Object o) {
        Object c = o instanceof Map<?, ?> m ? m.get("content") : null;
        return c == null ? "" : String.valueOf(c);
    }

    /** Newest "paused at budget" row, or null — synchronized: the loop thread writes. */
    private static String pauseRow(List<String> rows) {
        synchronized (rows) {
            String hit = null;
            for (String r : rows) if (r.contains("paused at the mission's TOKEN budget")) hit = r;
            return hit;
        }
    }

    private static void contextEconomyChecks() throws Exception {
        // ---- long run: 12 decompiles, then final text (+ one nudge turn)
        Scripted script = new Scripted(12);
        List<String> rows = new ArrayList<>();
        AgentManager.Mission m = mission("m-ctx", 10_000_000);
        new AgentLoop(stubDispatcher(), m, script, "scripted-1",
                (kind, text) -> { synchronized (rows) { rows.add(kind + ": " + text); } }, 14).run();
        check("done".equals(m.state), "context run reached done (state=" + m.state + ")");
        check(script.turn >= 12, "context run made " + script.turn + " turns");

        List<Object> last = script.sent.get(script.sent.size() - 1);
        int rawLen = Json.write(Map.of("source", "x".repeat(OVERSIZED))).length();

        // (a) R1 — an oversized result lands truncated, with the marker
        String truncMarker = "[truncated — 6,000 of " + String.format("%,d", rawLen)
                + " chars; narrow the query or decompile a single method]";
        int truncated = 0, overCap = 0, elided = 0;
        for (Object o : last) {
            if (!"tool".equals(role(o))) continue;
            String c = content(o);
            if (c.startsWith("[result elided — ")) { elided++; continue; }
            if (c.contains(truncMarker)) truncated++;
            if (c.length() > CAP + 200) overCap++;
        }
        check(truncated > 0, "R1 oversized tool result stored truncated with the marker ("
                + truncated + " of " + (truncated + elided) + " results)");
        check(overCap == 0, "R1 no stored tool result exceeds the " + CAP + "-char cap");

        // (b) R2 — below the model window the history is sent whole: nothing stubbed, nothing
        //     rewritten, every request a verbatim extension of the previous one (prefix caching)
        List<Integer> toolIdx = new ArrayList<>();
        for (int i = 0; i < last.size(); i++) if ("tool".equals(role(last.get(i)))) toolIdx.add(i);
        check(elided == 0 && toolIdx.size() >= 11, "R2 history sent whole below the window: 0 of "
                + toolIdx.size() + " tool results elided (" + elided + " stubs)");
        int extended = 0, rewritten = 0;
        for (int i = 1; i < script.sent.size(); i++) {
            String prev = Json.write(script.sent.get(i - 1)), cur = Json.write(script.sent.get(i));
            if (cur.startsWith(prev.substring(0, prev.length() - 1))) extended++; else rewritten++;   // minus the closing ']'
        }
        check(rewritten == 0 && extended == script.sent.size() - 1, "R2 every request extends the previous one verbatim — "
                + "the cached prefix is never rewritten (" + extended + " extensions, " + rewritten + " rewrites)");
        boolean grows = true;
        for (int i = 1; i < script.promptChars.size(); i++)
            if (script.promptChars.get(i) < script.promptChars.get(i - 1)) grows = false;
        check(grows, "R2 the prompt only grows below the window (no compaction)");
        // OpenAI protocol: every assistant tool_calls id keeps its paired reply, in order
        int pairs = 0;
        boolean paired = true;
        for (int i = 0; i < last.size(); i++) {
            if (!"assistant".equals(role(last.get(i)))) continue;
            Object tcs = ((Map<?, ?>) last.get(i)).get("tool_calls");
            if (!(tcs instanceof List<?> calls)) continue;
            for (Object c : calls) {
                String id = String.valueOf(((Map<?, ?>) c).get("id"));
                boolean found = false;
                for (int j = i + 1; j < last.size() && !found; j++)
                    if ("tool".equals(role(last.get(j)))
                            && id.equals(String.valueOf(((Map<?, ?>) last.get(j)).get("tool_call_id"))))
                        found = true;
                pairs++;
                if (!found) paired = false;
            }
        }
        check(paired && pairs > 0, "R2 all " + pairs + " tool_call ids still have a paired tool message");

        // (c) R3 — reasoning_content never enters the history (recorded in the transcript instead)
        int assistants = 0, withReasoning = 0;
        for (Object o : last)
            if ("assistant".equals(role(o))) { assistants++; if (((Map<?, ?>) o).containsKey("reasoning_content")) withReasoning++; }
        check(assistants >= 12 && withReasoning == 0, "R3 reasoning_content stripped from all " + assistants
                + " assistant messages before they enter the history (" + withReasoning + " left)");
        String feed = String.join("\n", rows);
        check(Pattern.compile("reasoned \\d+ chars").matcher(feed).find(),
                "R3 the reasoning is recorded in the feed (\"reasoned N chars\"), not sent back");
        check(feed.contains("context: ") && feed.contains(" 0 elided · ")
                        && feed.contains(" reasoning stripped · ~"),
                "R2/R3 per-turn context row in the activity stream (0 elided below the window)");

        int peak = 0;
        for (int c : script.promptChars) peak = Math.max(peak, c);
        StringBuilder series = new StringBuilder();
        for (int i = 0; i < script.promptChars.size(); i++)
            series.append(i == 0 ? "" : " ").append("t").append(i + 1).append("=")
                    .append(script.promptChars.get(i) / 4).append("tok");
        System.out.println("  prompt growth (whole history): " + series);

        // (e) R2 above the model window — a 16,000-token window forces eviction on this run:
        //     the oldest results become stubs first, the subject's own decompile and the
        //     working set stay verbatim, and the prompt stays under the window
        check(peak / 4 > SMALL_WINDOW, "the whole history (≈" + (peak / 4) + " tok) exceeds the small window (" + SMALL_WINDOW + ") — the eviction run is meaningful");
        Scripted small = new Scripted(12);
        small.subjectSymbol = "Lcom/example/A1;->run()V";
        List<String> rows2 = new ArrayList<>();
        AgentManager.Mission m2 = mission("m-window", 10_000_000);
        m2.goal = "reverse method Lcom/example/A1;->run()V and its callees";
        new AgentLoop(stubDispatcher(), m2, small, "scripted-1",
                (kind, text) -> { synchronized (rows2) { rows2.add(kind + ": " + text); } }, 14, Map.of(), SMALL_WINDOW).run();
        check("done".equals(m2.state) && small.turn >= 12, "R2 eviction run reached done in " + small.turn + " turns (state=" + m2.state + ")");
        List<Object> req12 = small.sent.get(Math.min(11, small.sent.size() - 1));   // the request holding results 1..11
        List<Integer> toolIdx2 = new ArrayList<>();
        for (int i = 0; i < req12.size(); i++) if ("tool".equals(role(req12.get(i)))) toolIdx2.add(i);
        int stubs2 = 0;
        for (int i : toolIdx2) if (content(req12.get(i)).startsWith("[result elided — ")) stubs2++;
        check(stubs2 > 0 && stubs2 < toolIdx2.size(), "R2 above the window " + stubs2 + " of " + toolIdx2.size() + " results are evicted");
        check(toolIdx2.size() > 2 && !content(req12.get(toolIdx2.get(0))).startsWith("[result elided — "),
                "R2 the subject's own decompile (turn 1) is pinned — never evicted");
        String second = toolIdx2.size() > 1 ? content(req12.get(toolIdx2.get(1))) : "";
        check(second.startsWith("[result elided — code ") && second.contains("Lcom/example/A2;")
                        && second.contains("returned " + String.format("%,d", rawLen) + " chars"),
                "R2 the oldest evictable result is a stub naming the tool, its args and the pre-cap size: " + second);
        boolean working = true;
        for (int k = Math.max(0, toolIdx2.size() - KEEP_TURNS); k < toolIdx2.size(); k++)
            if (content(req12.get(toolIdx2.get(k))).startsWith("[result elided — ")) working = false;
        check(working, "R2 the working set (the last " + KEEP_TURNS + " turns' results) stays verbatim");
        int peak2 = 0;
        for (int c : small.promptChars) peak2 = Math.max(peak2, c);
        check(peak2 / 4 < SMALL_WINDOW, "R2 the prompt stays under the window (peak ≈" + (peak2 / 4) + " tok of " + SMALL_WINDOW + ")");
        String feed2 = String.join("\n", rows2);
        check(Pattern.compile("context: \\d+ messages · [1-9]\\d* elided").matcher(feed2).find(),
                "R2 the context row reports the eviction");

        // (f) one explicit context refusal: adopt the stated window and retry a changed request
        Scripted refusing = new Scripted(12);
        refusing.refuseOnTurn = 8;
        refusing.refusal = "This model's maximum context length is 16384 tokens. However, you requested 20000 tokens.";
        List<String> rows3 = new ArrayList<>();
        AgentManager.Mission m3 = mission("m-refusal", 10_000_000);
        int[] learned = {0};
        AgentLoop l3 = new AgentLoop(stubDispatcher(), m3, refusing, "scripted-1",
                (kind, text) -> { synchronized (rows3) { rows3.add(kind + ": " + text); } }, 14);
        l3.onWindowLearned(n -> learned[0] = n);
        l3.run();
        check("done".equals(m3.state) && refusing.turn >= 12, "R2 a refused turn is retried and the mission reaches done (state="
                + m3.state + ", " + refusing.turn + " turns)");
        String feed3 = String.join("\n", rows3);
        StringBuilder refusalRows = new StringBuilder();
        synchronized (rows3) { for (String r : rows3) if (r.contains("refus") || r.contains("window") || r.contains("blocked") || r.contains("failed")) refusalRows.append(" | ").append(r); }
        check(learned[0] == 16_384, "R2 the provider's stated window is adopted and handed back for the provider (learned=" + learned[0] + ")" + refusalRows);
        check(feed3.contains("the provider says the model's window is 16,384 tokens")
                        && rows3.stream().filter(r->r.contains("retrying the changed request")).count()==1,
                "R2 the feed reports the adopted window and one changed-request retry");
        int peakAfter = 0;
        for (int i = 7; i < refusing.promptChars.size(); i++) peakAfter = Math.max(peakAfter, refusing.promptChars.get(i));
        check(peakAfter > 0 && peakAfter / 4 < 16_384, "R2 after the refusal every prompt stays under the learned window (peak ≈" + (peakAfter / 4) + " tok)");

        // (d) R4 — the pause names the raise, and that raise really does resume
        Scripted tight = new Scripted(12);
        List<String> tightRows = new ArrayList<>();
        AgentManager.Mission tm = mission("m-pause", 1_500);
        AgentLoop loop = new AgentLoop(stubDispatcher(), tm, tight, "scripted-1",
                (kind, text) -> { synchronized (tightRows) { tightRows.add(text); } }, 14);
        Thread t = new Thread(loop::run, "m10a-pause");
        t.setDaemon(true);
        t.start();
        // wait on the row, not on the (non-volatile) state field: the synchronized
        // read is the memory barrier, and the row is emitted before state flips
        long dl = System.currentTimeMillis() + 15_000;
        while (System.currentTimeMillis() < dl && pauseRow(tightRows) == null) Thread.sleep(50);
        String pause = pauseRow(tightRows);
        while (System.currentTimeMillis() < dl && !"paused".equals(tm.state)) Thread.sleep(20);
        check(pause != null && "paused".equals(tm.state),
                "R4 tight budget pauses (state=" + tm.state + ")");
        if (pause == null) pause = "";
        Matcher mt = Pattern.compile("next turn needs ≈([\\d,]+) prompt \\+ ([\\d,]+) completion vs "
                + "([\\d,]+) remaining — resume with agent\\.resume \\{id: ([^,]+), budget: (\\d+)\\}")
                .matcher(pause);
        boolean shaped = mt.find();
        check(shaped, "R4 pause names prompt/completion/remaining + a resume budget: "
                + (pause.isEmpty() ? "(no pause row)" : pause));
        boolean sane = false, idOk = false;
        int raise = 0;
        if (shaped) {
            int p = Integer.parseInt(mt.group(1).replace(",", ""));
            int c = Integer.parseInt(mt.group(2).replace(",", ""));
            idOk = "m-pause".equals(mt.group(4));
            raise = Integer.parseInt(mt.group(5));
            // the quoted budget must clear BOTH guards: spent < budget, and the
            // next prompt still leaves completion headroom
            sane = raise > tm.budget && raise >= tm.tokensUsed + p + c
                    && raise - tm.tokensUsed - p >= c;
        }
        check(idOk, "R4 pause quotes the real mission id");
        check(sane, "R4 quoted budget " + raise + " covers prompt + completion over tokensUsed="
                + tm.tokensUsed);
        // the anti-race property: resuming with exactly that number must produce
        // real work, not an instant re-pause
        int before = tight.turn;
        tm.budget = raise;
        tm.pauseRequested = false;
        long dl2 = System.currentTimeMillis() + 15_000;
        while (System.currentTimeMillis() < dl2 && tight.turn <= before) Thread.sleep(50);
        check(tight.turn > before, "R4 resuming with the quoted budget runs a real turn "
                + "(turns " + before + " → " + tight.turn + "), no instant re-pause");
        tm.stopRequested = true;
        tm.pauseRequested = false;
        t.join(10_000);
    }

    private static void check(boolean ok, String label) {
        checks++;
        System.out.println((ok ? "PASS " : "FAIL ") + label);
        if (!ok) failures++;
    }

    /** The configuration surface (agents redesign 2026-09-09): providers
     *  (llm.configure with label/models/local, llm.remove, the id slug rule,
     *  llm.setkey's required key), the built-in task settings (task.list /
     *  task.configure: merge semantics, resolution, the scripted refusal) and
     *  agent.spawn's no-default rule + effort/turns plumbing. */
    private static void configurationChecks(Path cfg, Path tasksFile) throws Exception {
        // ---- a user-added LOCAL provider: needs no key, configured at once, persisted with label + models
        /* create:true — the create gate (llm-config design §6.1): an unknown id without it is
           E_NOTFOUND, so a stale client cannot resurrect a renamed-away provider */
        Map<String, Object> added = data(post("llm.configure", Map.of("id", "ittest-local", "create", true, "label", "IT local",
                "baseUrl", "http://127.0.0.1:1/v1", "models", "m-a, m-b", "local", true)));
        check(Boolean.FALSE.equals(added.get("builtin")) && Boolean.TRUE.equals(added.get("local"))
                        && Boolean.FALSE.equals(added.get("needsKey")) && Boolean.TRUE.equals(added.get("configured"))
                        && "m-a".equals(added.get("model")),
                "llm.configure: a LOCAL user-added provider needs no key (configured, default = first model)");
        String cfgText = Files.readString(cfg);
        check(cfgText.contains("\"ittest-local\"") && cfgText.contains("IT local") && cfgText.contains("m-b"),
                "user-added provider persists label + models to llm.json");
        /* the sidecar → memory round trip (r2 H1: needsKey was re-derived only in
           configure(), so a LOCAL provider came back "no key" after a restart) */
        dev.workbench.llm.LlmRegistry fresh = new dev.workbench.llm.LlmRegistry(
                new java.io.File(SIDECAR), new dev.workbench.llm.KeyStore(new java.io.File(SIDECAR)));
        dev.workbench.llm.LlmRegistry.Provider reloaded = fresh.get("ittest-local");
        check(reloaded != null && reloaded.local && !reloaded.needsKey && "IT local".equals(reloaded.label)
                        && List.of("m-a", "m-b").equals(reloaded.models) && fresh.configured(reloaded),
                "a fresh registry reloads the LOCAL provider as configured (needsKey follows LOCAL across restarts)");
        check(fresh.resolve("deepseek-flash") == null && fresh.resolve("claude-code") != null,
                "resolve: a bare model name stays unresolved (loud block), the claude-code sentinel resolves");
        /* the other direction (r3 M1): un-ticking LOCAL on a shipped LOCAL provider must survive a restart */
        data(post("llm.configure", Map.of("id", "ollama", "local", false)));
        dev.workbench.llm.LlmRegistry fresh2 = new dev.workbench.llm.LlmRegistry(
                new java.io.File(SIDECAR), new dev.workbench.llm.KeyStore(new java.io.File(SIDECAR)));
        check(fresh2.get("ollama") != null && !fresh2.get("ollama").local && fresh2.get("ollama").needsKey,
                "a shipped LOCAL provider un-ticked stays non-local (needs a key) after a reload");
        data(post("llm.configure", Map.of("id", "ollama", "local", true)));
        check("E_SYNTAX".equals(err(post("agent.spawn", Map.of("goal", "reverse method X.a", "depth", "quick", "turns", "twenty")))),
                "agent.spawn: a non-numeric turns is E_SYNTAX");
        Map<String, Object> badId = post("llm.configure", Map.of("id", "Bad Id!", "baseUrl", "http://x"));
        check("E_SYNTAX".equals(err(badId)), "llm.configure: a non-slug id is E_SYNTAX");
        check("E_UNSUPPORTED".equals(err(post("llm.remove", Map.of("id", "anthropic")))), "llm.remove: a built-in is refused");
        // ---- llm.setkey: the key must be present; "" clears
        check("E_SYNTAX".equals(err(post("llm.setkey", Map.of("id", "deepseek")))), "llm.setkey without a key is E_SYNTAX (never a wipe)");
        Map<String, Object> cleared = data(post("llm.setkey", Map.of("id", "deepseek", "key", "")));
        check(Boolean.FALSE.equals(cleared.get("configured")), "llm.setkey with \"\" clears the key");
        // ---- the reversing agent: unconfigured → agent.spawn {depth} is E_SYNTAX; no implicit provider anywhere
        @SuppressWarnings("unchecked")
        List<Object> tasks = (List<Object>) data(post("task.list", Map.of()));
        Map<?, ?> reverse = (Map<?, ?>) tasks.get(0);
        @SuppressWarnings("unchecked")
        List<Object> depths = (List<Object>) reverse.get("depths");
        Map<?, ?> quick = (Map<?, ?>) depths.get(0);
        check("reverse".equals(reverse.get("task")) && "quick".equals(quick.get("depth"))
                        && Boolean.TRUE.equals(((Map<?, ?>) quick.get("resolved")).get("unconfigured")),
                "task.list: reverse/quick starts unconfigured");
        check("E_SYNTAX".equals(err(post("agent.spawn", Map.of("goal", "reverse method X.a", "depth", "quick")))),
                "agent.spawn {depth} on an unconfigured depth is E_SYNTAX");
        check("E_SYNTAX".equals(err(post("agent.spawn", Map.of("goal", "any custom goal")))),
                "agent.spawn without a model (and not triage-package) is E_SYNTAX — no implicit provider");
        check("E_SYNTAX".equals(err(post("task.configure", Map.of("task", "reverse", "depth", "quick", "model", "scripted")))),
                "task.configure refuses the scripted agent");
        check("E_SYNTAX".equals(err(post("task.configure", Map.of("task", "reverse", "depth", "quick", "model", "nope:x")))),
                "task.configure refuses an unknown provider");
        // ---- configure quick on the fixture with effort + caps; merge keeps what a later call omits
        Map<String, Object> q1 = data(post("task.configure", Map.of("task", "reverse", "depth", "quick",
                "model", "fixture:fixture-1", "effort", "high", "turns", 6, "budget", 9000)));
        check("fixture:fixture-1".equals(q1.get("model")) && "high".equals(q1.get("effort"))
                        && Integer.valueOf(6).equals(((Number) q1.get("turns")).intValue())
                        && Boolean.TRUE.equals(((Map<?, ?>) q1.get("resolved")).get("configured")),
                "task.configure stores model/effort/turns/budget and resolves the fixture");
        Map<String, Object> q2 = data(post("task.configure", Map.of("task", "reverse", "depth", "quick", "effort", "low")));
        check("fixture:fixture-1".equals(q2.get("model")) && "low".equals(q2.get("effort"))
                        && Integer.valueOf(6).equals(((Number) q2.get("turns")).intValue()),
                "task.configure is a merge: an absent model/turns keep their values");
        check(Files.readString(tasksFile).contains("fixture:fixture-1"), "task settings persist to tasks.json");
        dev.workbench.llm.TaskSettings freshTasks = new dev.workbench.llm.TaskSettings(new java.io.File(SIDECAR));
        dev.workbench.llm.TaskSettings.Entry reloadedQuick = freshTasks.get("reverse", "quick");
        check(reloadedQuick != null && "fixture:fixture-1".equals(reloadedQuick.model) && "low".equals(reloadedQuick.effort)
                        && reloadedQuick.turns == 6 && reloadedQuick.budget == 9000,
                "a fresh TaskSettings reloads the depth entry from tasks.json");
        check("E_SYNTAX".equals(err(post("task.configure", Map.of("task", "reverse", "depth", "quick", "turns", "twenty")))),
                "task.configure: a non-numeric turns is E_SYNTAX, not a silent keep");
        // ---- a reversing spawn runs on the task's settings; an explicit model keeps its own caps
        Map<String, Object> m1 = data(post("agent.spawn", Map.of("goal", "reverse method X.a", "depth", "quick", "scope", "pkg:X")));
        check("fixture:fixture-1".equals(m1.get("model")) && "low".equals(m1.get("effort"))
                        && Integer.valueOf(6).equals(((Number) m1.get("maxTurns")).intValue())
                        && Integer.valueOf(9000).equals(((Number) m1.get("budget")).intValue())
                        && "quick".equals(m1.get("depth")),
                "agent.spawn {depth} runs on the configured model, effort, turn cap and budget");
        Map<String, Object> m2 = data(post("agent.spawn", Map.of("goal", "reverse method X.b", "depth", "quick",
                "model", "fixture:fixture-1", "scope", "pkg:X")));
        check(m2.get("maxTurns") == null && Integer.valueOf(120000).equals(((Number) m2.get("budget")).intValue()),
                "an explicit model keeps the depth's own caps (the task's turns/budget are one unit with its model)");
        // ---- llm.remove takes the task pin with it; a mission actor cannot reconfigure
        data(post("task.configure", Map.of("task", "reverse", "depth", "deep", "model", "ittest-local:m-a")));
        Map<String, Object> removed = data(post("llm.remove", Map.of("id", "ittest-local")));
        check(String.valueOf(removed.get("tasksReset")).contains("reverse/deep"), "llm.remove resets the depth that named the provider");
        Map<String, Object> deep = null;
        for (Object o : (List<?>) ((Map<?, ?>) ((List<?>) data(post("task.list", Map.of()))).get(0)).get("depths"))
            if ("deep".equals(((Map<?, ?>) o).get("depth"))) deep = (Map<String, Object>) o;
        check(deep != null && Boolean.TRUE.equals(((Map<?, ?>) deep.get("resolved")).get("unconfigured")),
                "task.list: the reset depth is unconfigured again");
        Map<String, Object> asMission = postAs("task.configure", Map.of("task", "reverse", "depth", "quick", "effort", "high"),
                "llm:" + m1.get("agentId"));
        check("E_SCOPE".equals(err(asMission)), "a mission actor cannot call task.configure");
        check("E_SCOPE".equals(err(postAs("llm.setkey", Map.of("id", "deepseek", "key", "x"), "llm:" + m1.get("agentId")))),
                "a mission actor cannot call llm.setkey");
        check(Boolean.TRUE.equals(postAs("task.list", Map.of(), "llm:mcp").get("ok")), "an unmissioned MCP client may read task.list");
        // ---- the MCP tool catalogue carries strict schemas for the configuration surface
        @SuppressWarnings("unchecked")
        List<Object> tools = (List<Object>) data(post("ops.tools", Map.of()));
        java.util.Set<String> names = new java.util.HashSet<>();
        for (Object o : tools) names.add(String.valueOf(((Map<?, ?>) o).get("tool")));
        check(names.containsAll(List.of("reversing_agent", "llm")),
                "ops.tools lists the configuration tools (reversing_agent, llm — the facade's action tools)");
        // cleanup: the depth pins (tasks.json is restored by the caller anyway)
        data(post("task.configure", Map.of("task", "reverse", "depth", "quick", "model", "", "effort", "", "turns", 0, "budget", 0)));
        for (String id : List.of(String.valueOf(m1.get("id")), String.valueOf(m2.get("id"))))
            post("agent.stop", Map.of("id", id));
    }

    private static String err(Map<String, Object> resp) {
        Object e = resp.get("error");
        return e instanceof Map<?, ?> m ? String.valueOf(m.get("code")) : null;
    }

    private static Map<String, Object> postAs(String op, Map<String, Object> params, String actor) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("params", params);
        body.put("actor", actor);
        HttpRequest req = HttpRequest.newBuilder(URI.create(BASE + "/api/" + op))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(Json.write(body)))
                .build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        return Json.parseObject(resp.body());
    }

    private static Map<String, Object> post(String op, Map<String, Object> params) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("params", params);
        body.put("actor", "human");
        HttpRequest req = HttpRequest.newBuilder(URI.create(BASE + "/api/" + op))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(Json.write(body)))
                .build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        return Json.parseObject(resp.body());
    }

    @SuppressWarnings("unchecked")
    private static <T> T data(Map<String, Object> resp) {
        if (!Boolean.TRUE.equals(resp.get("ok")))
            throw new IllegalStateException("op failed: " + resp.get("error"));
        return (T) resp.get("data");
    }
}
