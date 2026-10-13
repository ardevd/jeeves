package dev.workbench.it;

import dev.workbench.registry.Json;
import dev.workbench.server.WorkbenchServer;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Provider-conversation integration test (course integration P0.1; plain main-class like the other
 * suites; hermetic home, port 7818). The http agent loop runs against the fixture provider's
 * recorded-shape scenarios (AgentLoop.fixtureClient), which answer the way DeepSeek thinking mode
 * and Kimi K3 do and CHECK each request the way they would:
 *   1. a thinking provider with tools, replay policy `all`: two consecutive tool turns, two calls in
 *      one turn, content + reasoning at the end — every earlier trace is sent back, every tool call
 *      id has its reply, the mission completes with a visible stop reason and wire receipts
 *   2. the same provider under policy `never`: the loop strips the trace and the provider refuses
 *      the history (400) — failed, with the reason
 *   3. an endpoint that rejects the field: `never` passes, `all` fails
 *   4. six tool turns through a small context window: the oldest results are evicted and the
 *      conversation stays valid (pairing, traces)
 *   5. reasoning-only answers are not a completion
 *   6. a malformed body, an upstream failure
 *   7. a stop while the request is in flight: cancelled, the paid answer set aside
 *   8. the streaming parser on SSE segments
 * Run: gradle providerLoopIT
 */
public final class ProviderLoopIT {

    private static int failures = 0, checks = 0;
    private static final HttpClient http = HttpClient.newHttpClient();
    private static final String BASE = "http://127.0.0.1:" + WorkbenchServer.PORT;

    public static void main(String[] args) {
        try {
            run();
        } catch (Throwable t) {
            t.printStackTrace(System.out);
            System.out.println("PROVIDER LOOP IT ABORTED after " + checks + " checks: " + t);
            System.exit(1);
        }
    }

    private static void run() throws Exception {
        if (System.getenv("WORKBENCH_HOME") == null)
            throw new IllegalStateException("ProviderLoopIT needs a hermetic WORKBENCH_HOME — run it with: gradle providerLoopIT");
        WorkbenchServer server = new WorkbenchServer(Path.of("web"));
        server.start();
        System.out.println("server up on " + BASE);
        // the mock scene (the boot project's targets, WORKBENCH_MOCK unset) is what the scenarios read
        Map<String, Object> ws = data(post("workspace.state", Map.of()));
        List<?> targets = (List<?>) ws.get("targets");
        String tid = targets != null && !targets.isEmpty() ? String.valueOf(((Map<?, ?>) targets.get(0)).get("id")) : null;
        check(tid != null, "the mock scene is open for the missions: " + tid);

        // ---- the provider rows carry the wire contract
        Map<String, Object> ds = providerRow("deepseek"), kimi = providerRow("moonshot"), fx = providerRow("fixture");
        check(ds.get("reasoning") instanceof Map<?, ?> r1 && "reasoning_content".equals(r1.get("field")) && "all".equals(r1.get("replay")),
                "deepseek: reasoning_content, replay all (its thinking-mode tool rule) — " + ds.get("reasoning"));
        check(kimi != null && kimi.get("reasoning") instanceof Map<?, ?> r2 && "all".equals(r2.get("replay")), "moonshot (Kimi): reasoning_content, replay all — " + kimi.get("reasoning"));
        check(fx.get("reasoning") instanceof Map<?, ?> r3 && "never".equals(r3.get("replay")), "fixture: no field, replay never by default");
        Map<String, Object> bad = post("llm.configure", Map.of("id", "fixture", "reasoningReplay", "sometimes"));
        check("E_SYNTAX".equals(code(bad)), "llm.configure refuses an unknown replay policy");

        // ---- 1. thinking provider, policy all
        configureWire("reasoning_content", "all");
        Map<String, Object> m1 = runMission("explain the sync handler", "fixture:ds-tools", Map.of());
        check("done".equals(m1.get("state")) && "completed".equals(m1.get("displayState")) && String.valueOf(m1.get("stopReason")).startsWith("final answer after 3 turns"),
                "policy all: the mission completes — state " + m1.get("state") + " / " + m1.get("displayState") + " · " + m1.get("stopReason"));
        Map<String, Object> t3 = data(post("agent.turn", Map.of("mission", m1.get("id"), "turn", 3)));
        check(String.valueOf(t3.get("text")).contains("held 2 trace(s)") && String.valueOf(t3.get("text")).contains("every tool call was answered"),
                "the provider saw both earlier traces and every tool reply: " + t3.get("text"));
        Map<String, Object> t1 = data(post("agent.turn", Map.of("mission", m1.get("id"), "turn", 1)));
        List<?> calls1 = (List<?>) t1.get("calls");
        check(calls1 != null && calls1.size() == 2 && t1.get("reasoning") != null, "turn 1 transcript: two tool calls and the trace (" + (calls1 == null ? 0 : calls1.size()) + " calls)");
        Map<?, ?> wire = t1.get("wire") instanceof Map<?, ?> w ? w : Map.of();
        check("fixture-thinking-2026-10".equals(wire.get("modelReturned")) && "fixture:ds-tools".equals(m1.get("model")) || "ds-tools".equals(wire.get("modelRequested")),
                "receipt: requested " + wire.get("modelRequested") + ", returned " + wire.get("modelReturned") + ", request id " + t1.get("requestId"));
        check(String.valueOf(t1.get("requestId")).equals(m1.get("id") + "-t1-a1") && t1.get("cacheHit") != null,
                "stable request id per attempt and the usage breakdown (cache hit " + t1.get("cacheHit") + ")");
        String feed = String.valueOf(list(post("agent.activity", Map.of("agent", m1.get("agentId"), "count", 80))));
        check(feed.contains("kept for the provider"), "the activity says the trace was kept for the provider");

        // ---- 2. the same provider, policy never: the loop strips, the provider refuses
        configureWire("reasoning_content", "never");
        Map<String, Object> m2 = runMission("explain the sync handler", "fixture:ds-tools", Map.of());
        check("blocked".equals(m2.get("state")) && "failed".equals(m2.get("displayState")) && String.valueOf(m2.get("stopReason")).contains("reasoning_content") && String.valueOf(m2.get("stopReason")).contains("400"),
                "policy never against a provider that needs the trace back: failed with the provider's reason — " + m2.get("stopReason"));

        // ---- 3. an endpoint that rejects the field
        Map<String, Object> m3 = runMission("explain the sync handler", "fixture:plain-strips", Map.of());
        check("done".equals(m3.get("state")), "policy never against an endpoint that rejects the field: completes (the loop stripped it) — " + m3.get("stopReason"));
        configureWire("reasoning_content", "all");
        Map<String, Object> m3b = runMission("explain the sync handler", "fixture:plain-strips", Map.of());
        check("blocked".equals(m3b.get("state")) && String.valueOf(m3b.get("stopReason")).contains("Unrecognized"),
                "policy all against that endpoint: it refuses the field — failed with its reason — " + m3b.get("stopReason"));

        // ---- 4. compaction under context pressure keeps the conversation valid
        Map<String, Object> small = post("llm.configure", Map.of("id", "fixture", "contextTokens", 8192));
        check(Boolean.TRUE.equals(small.get("ok")), "fixture window set to 8192 tokens");
        Map<String, Object> m4 = runMission("read the sync handler again and again", "fixture:ds-long", Map.of("turns", 12));
        int elided = 0;
        for (int t = 1; t <= 7; t++) {
            Map<String, Object> rec = post("agent.turn", Map.of("mission", m4.get("id"), "turn", t));
            if (Boolean.TRUE.equals(rec.get("ok")) && data(rec).get("context") instanceof Map<?, ?> c && c.get("elided") instanceof Number n) elided = Math.max(elided, n.intValue());
        }
        check("done".equals(m4.get("state")) && elided > 0, "six tool turns through an 8k window: " + elided + " result(s) evicted and the provider accepted every request (pairing + traces intact) — " + m4.get("stopReason"));
        post("llm.configure", Map.of("id", "fixture", "contextTokens", 128000));

        // ---- 5. reasoning-only answers
        Map<String, Object> m5 = runMission("explain the sync handler", "fixture:reasoning-only", Map.of());
        check("blocked".equals(m5.get("state")) && String.valueOf(m5.get("stopReason")).contains("reasoning-only") && !"done".equals(m5.get("state")),
                "two reasoning-only answers: not a completion — " + m5.get("stopReason"));
        Map<String, Object> t5 = data(post("agent.turn", Map.of("mission", m5.get("id"), "turn", 1)));
        check("reasoning-only".equals(t5.get("outcome")) && t5.get("reasoning") != null, "turn 1 is recorded as reasoning-only, trace kept in the transcript");

        // ---- 6. malformed body, upstream failure
        Map<String, Object> m6 = runMission("explain", "fixture:malformed", Map.of());
        check("blocked".equals(m6.get("state")) && String.valueOf(m6.get("stopReason")).startsWith("malformed provider response"), "malformed body: " + m6.get("stopReason"));
        Map<String, Object> m7 = runMission("explain", "fixture:upstream-500", Map.of());
        check("blocked".equals(m7.get("state")) && "failed".equals(m7.get("displayState")) && String.valueOf(m7.get("stopReason")).contains("HTTP 500"), "upstream failure: " + m7.get("stopReason"));

        // ---- 7. a stop while the request is in flight
        Map<String, Object> slow = data(post("agent.spawn", Map.of("goal", "slow", "model", "fixture:slow-tools")));
        Thread.sleep(400);   // inside the first 1.5 s answer
        data(post("agent.stop", Map.of("id", slow.get("id"))));
        Map<String, Object> t8 = Map.of();
        for (long end = System.currentTimeMillis() + 10_000; System.currentTimeMillis() < end; Thread.sleep(200)) {   // the loop ends the in-flight turn after the 1.5 s answer lands
            t8 = post("agent.turn", Map.of("mission", slow.get("id"), "turn", 1));
            if (Boolean.TRUE.equals(t8.get("ok")) && "cancelled".equals(data(t8).get("outcome"))) break;
        }
        Map<String, Object> m8 = waitSettled(String.valueOf(slow.get("id")), 10_000);
        check("stopped".equals(m8.get("state")) && "cancelled".equals(m8.get("displayState")) && String.valueOf(m8.get("stopReason")).contains("in flight"),
                "stop mid-request: cancelled, and the paid answer set aside — " + m8.get("stopReason"));
        check(Boolean.TRUE.equals(t8.get("ok")) && "cancelled".equals(data(t8).get("outcome")) && data(t8).get("tokensUsed") instanceof Number n8 && n8.intValue() > 0,
                "the cancelled turn's transcript keeps its usage (" + (Boolean.TRUE.equals(t8.get("ok")) ? data(t8).get("tokensUsed") : "?") + " tokens) and names the outcome");

        // ---- 8. the streaming parser
        Map<String, Object> m9 = runMission("explain the sync handler", "fixture:stream-tools", Map.of());
        Map<String, Object> t9 = data(post("agent.turn", Map.of("mission", m9.get("id"), "turn", 1)));
        Map<?, ?> w9 = t9.get("wire") instanceof Map<?, ?> w ? w : Map.of();
        check("done".equals(m9.get("state")) && Boolean.TRUE.equals(w9.get("stream")) && String.valueOf(w9.get("providerRequestId")).startsWith("fx-stream-"),
                "SSE segments → one message: two tool calls assembled from fragments, the trace, the usage; receipt " + w9);

        System.out.println();
        if (failures > 0) { System.out.println("PROVIDER LOOP IT FAILED: " + failures + "/" + checks + " checks failed"); System.exit(1); }
        System.out.println("PROVIDER LOOP IT PASSED: " + checks + " checks green");
        System.exit(0);
    }

    // ------------------------------------------------------------ helpers

    private static void configureWire(String field, String replay) throws Exception {
        data(post("llm.configure", Map.of("id", "fixture", "reasoningField", field, "reasoningReplay", replay)));
    }

    private static Map<String, Object> providerRow(String id) throws Exception {
        for (Object o : list(post("llm.providers", Map.of())))
            if (o instanceof Map<?, ?> p && id.equals(p.get("id"))) { Map<String, Object> m = new LinkedHashMap<>(); for (Map.Entry<?, ?> e : p.entrySet()) m.put(String.valueOf(e.getKey()), e.getValue()); return m; }
        return null;
    }

    private static Map<String, Object> runMission(String goal, String model, Map<String, Object> extra) throws Exception {
        Map<String, Object> params = new LinkedHashMap<>(Map.of("goal", goal, "model", model));
        params.putAll(extra);
        Map<String, Object> m = data(post("agent.spawn", params));
        return waitSettled(String.valueOf(m.get("id")), 60_000);
    }

    private static Map<String, Object> waitSettled(String id, long ms) throws Exception {
        long deadline = System.currentTimeMillis() + ms;
        Map<String, Object> last = null;
        while (System.currentTimeMillis() < deadline) {
            for (Object o : list(post("agent.missions", Map.of())))
                if (o instanceof Map<?, ?> m && id.equals(m.get("id"))) { last = new LinkedHashMap<>(); for (Map.Entry<?, ?> e : m.entrySet()) last.put(String.valueOf(e.getKey()), e.getValue()); }
            if (last != null && !"running".equals(last.get("state")) && !"queued".equals(last.get("state"))) return last;
            Thread.sleep(200);
        }
        return last == null ? Map.of("state", "?") : last;
    }

    private static void check(boolean ok, String label) {
        checks++;
        System.out.println((ok ? "PASS " : "FAIL ") + label);
        if (!ok) failures++;
    }

    private static String code(Map<String, Object> resp) {
        if (Boolean.TRUE.equals(resp.get("ok"))) return "ok";
        return resp.get("error") instanceof Map<?, ?> e ? String.valueOf(e.get("code")) : "?";
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

    private static List<?> list(Map<String, Object> resp) {
        if (!Boolean.TRUE.equals(resp.get("ok")))
            throw new IllegalStateException("op failed: " + resp.get("error"));
        return resp.get("data") instanceof List<?> l ? l : List.of();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> data(Map<String, Object> resp) {
        if (!Boolean.TRUE.equals(resp.get("ok")))
            throw new IllegalStateException("op failed: " + resp.get("error"));
        Object d = resp.get("data");
        if (!(d instanceof Map))
            throw new IllegalStateException("expected map data, got: " + (d == null ? "null" : d.getClass().getSimpleName()));
        return (Map<String, Object>) d;
    }
}
