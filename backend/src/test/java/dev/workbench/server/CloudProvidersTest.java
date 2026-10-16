package dev.workbench.server;

import com.sun.net.httpserver.HttpServer;
import dev.workbench.llm.Depth;
import dev.workbench.registry.Json;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Eight concurrent HTTP/SSE agents using the DS V4 / GLM 5.3 wire contracts. No paid calls. */
public final class CloudProvidersTest {
    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final String BASE = "http://127.0.0.1:" + WorkbenchServer.PORT;
    private static final CountDownLatch EIGHT = new CountDownLatch(8);
    private static final AtomicReference<Throwable> FAILURE = new AtomicReference<>();
    private static int checks;
    @SuppressWarnings("unchecked") private static Map<String, Object> map(Object o) { return (Map<String, Object>) o; }
    private static long number(Object n) { return n instanceof Number v ? v.longValue() : 0; }
    private static void require(boolean ok, String text) { if (!ok) throw new AssertionError(text); }
    private static void check(boolean ok, String text) { require(ok, text); checks++; System.out.println("PASS " + text); }
    private static Object op(String name, Map<String, Object> params) throws Exception {
        var req = HttpRequest.newBuilder(URI.create(BASE + "/api/" + name)).timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(Json.write(Map.of("params", params)))).build();
        var result = Json.parseObject(HTTP.send(req, HttpResponse.BodyHandlers.ofString()).body());
        require(Boolean.TRUE.equals(result.get("ok")), name + ": " + result);
        return result.get("data");
    }
    private static Map<String, Object> mission(String id) throws Exception {
        for (Object row : (List<?>) op("agent.missions", Map.of())) if (id.equals(map(row).get("id"))) return map(row);
        throw new AssertionError("missing mission " + id);
    }
    private static Map<String, Object> awaitDone(String id) throws Exception {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(45);
        do {
            if (FAILURE.get() != null) throw new AssertionError("synthetic provider", FAILURE.get());
            var m = mission(id);
            if ("done".equals(m.get("state"))) return m;
            require(!List.of("blocked", "paused", "interrupted").contains(m.get("state")), m.toString());
            Thread.sleep(20);
        } while (System.nanoTime() < until);
        throw new AssertionError("test deadline: " + mission(id));
    }
    private static String response(Map<String, Object> req) throws Exception {
        boolean glm = "glm-5.3".equals(req.get("model"));
        require(glm || "deepseek-v4-pro".equals(req.get("model")), "model must be exact");
        require("max".equals(req.get("reasoning_effort")), "Max must reach the provider without clamping");
        require(number(req.get("max_tokens")) == (glm ? 131072 : 393216), "provider output allowance must survive");
        require(Boolean.TRUE.equals(req.get("stream")), "streaming required");
        require("enabled".equals(map(req.get("thinking")).get("type")), "thinking enabled");
        if (glm) require(Boolean.FALSE.equals(map(req.get("thinking")).get("clear_thinking")), "GLM preserved thinking");
        int turn = 1;
        boolean localsReply = false;
        for (Object o : (List<?>) req.get("messages")) {
            var m = map(o);
            if ("assistant".equals(m.get("role"))) {
                require("trace".equals(m.get("reasoning_content")), "all prior reasoning traces replayed");
                turn++;
            }
            if ("tool".equals(m.get("role")) && "local-call".equals(m.get("tool_call_id"))) {
                // The mock engine cannot list native locals: its error proves dispatch reached the engine.
                String body = String.valueOf(m.get("content"));
                require(body.contains("E_UNSUPPORTED") && !body.contains("unknown view"), "locals routes to engine");
                localsReply = true;
            }
        }
        if (turn > 1) require(localsReply, "tool reply must follow the locals request");
        if (turn == 1) {
            EIGHT.countDown();
            require(EIGHT.await(15, TimeUnit.SECONDS), "eight requests overlap");
        }
        var delta = new LinkedHashMap<String, Object>(Map.of("role", "assistant", "reasoning_content", "trace"));
        if (turn <= 2) delta.put("tool_calls", List.of(Map.of("index", 0, "id", turn == 1 ? "local-call" : "symbols-call", "type", "function",
                "function", Map.of("name", turn == 1 ? "code" : "symbols", "arguments", Json.write(turn == 1
                        ? Map.of("symbol", "onMessage", "view", "locals") : Map.of("limit", 1))))));
        else delta.put("content", "Synthetic complete.");
        var usage = new LinkedHashMap<String, Object>(Map.of("prompt_tokens", 100000, "completion_tokens", 5000, "total_tokens", 105000));
        if (glm) usage.put("prompt_tokens_details", Map.of("cached_tokens", 80000));
        else { usage.put("prompt_cache_hit_tokens", 90000); usage.put("prompt_cache_miss_tokens", 10000); }
        return "data: " + Json.write(Map.of("model", req.get("model"), "choices", List.of(Map.of("index", 0, "delta", delta,
                "finish_reason", turn <= 2 ? "tool_calls" : "stop")), "usage", usage)) + "\n\ndata: [DONE]\n\n";
    }
    public static void main(String[] args) throws Exception {
        require(Depth.unlimitedBudget() && "1".equals(System.getenv("WORKBENCH_MOCK")), "isolated cloudProvidersTest task required");
        var workers = Executors.newCachedThreadPool();
        var provider = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        provider.setExecutor(workers);
        provider.createContext("/v1/chat/completions", ex -> {
            try {
                var req = Json.parseObject(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                byte[] data = response(req).getBytes(StandardCharsets.UTF_8);
                ex.getResponseHeaders().set("Content-Type", "text/event-stream");
                ex.sendResponseHeaders(200, 0); ex.getResponseBody().write(data);
            } catch (Throwable e) { FAILURE.compareAndSet(null, e); }
            finally { ex.close(); }
        });
        provider.start();
        var server = new WorkbenchServer(Path.of("web")); server.start();
        try {
            for (String id : List.of("cloud-ds", "cloud-glm")) {
                boolean glm = id.endsWith("glm");
                var p = new LinkedHashMap<String, Object>();
                p.put("id", id); p.put("create", true); p.put("local", true);
                p.put("baseUrl", "http://127.0.0.1:" + provider.getAddress().getPort() + "/v1");
                p.put("model", glm ? "glm-5.3" : "deepseek-v4-pro");
                p.put("extraParams", Map.of("thinking", glm ? Map.of("type", "enabled", "clear_thinking", false) : Map.of("type", "enabled")));
                p.put("reasoningField", "reasoning_content"); p.put("reasoningReplay", "all");
                p.put("streamOnly", true); p.put("maxOutputTokens", glm ? 131072 : 393216);
                op("llm.configure", p);
            }
            List<String> ids = new ArrayList<>();
            for (int i = 0; i < 8; i++) ids.add(String.valueOf(map(op("agent.spawn", Map.of("goal", "Synthetic class research " + i,
                    "model", i % 2 == 0 ? "cloud-ds:deepseek-v4-pro" : "cloud-glm:glm-5.3", "effort", "max", "budget", 1, "turns", 1))).get("id")));
            check(EIGHT.await(15, TimeUnit.SECONDS), "eight Max provider requests overlap");
            for (int i = 0; i < ids.size(); i++) {
                var m = awaitDone(ids.get(i));
                check(Boolean.TRUE.equals(m.get("budgetUnlimited")) && number(m.get("budget")) == 0
                        && Boolean.TRUE.equals(m.get("turnsUnlimited")) && number(m.get("turnsDone")) == 4,
                        ids.get(i) + " ignores explicit turn and mission-token ceilings");
                check(number(m.get("inputTokens")) == 400000 && number(m.get("outputTokens")) == 20000
                        && number(m.get("cacheHitTokens")) == (i % 2 == 0 ? 360000 : 320000),
                        ids.get(i) + " records raw input/output and provider-specific cache usage");
                var t = map(op("agent.turn", Map.of("mission", ids.get(i), "turn", 1)));
                check(t.get("usage") instanceof Map<?, ?>, "exact provider usage survives in turn transcript");
            }
            var ignored = map(op("agent.setbudget", Map.of("id", ids.get(0), "budget", 1)));
            check(Boolean.TRUE.equals(ignored.get("budgetUnlimited")) && ignored.get("note") != null, "budget control reports a no-op");
            var archived = new LinkedHashMap<>(mission(ids.get(0)));
            archived.put("tokensUsed", 4_000_000_000L);
            check(number(AgentManager.Mission.fromMap(archived).toMap().get("tokensUsed")) == 4_000_000_000L,
                    "long campaigns preserve counters beyond signed 32-bit range");
            check("high".equals(Depth.httpEffort("other-model", "max")), "ordinary provider compatibility preserved");
            System.out.println("CLOUD PROVIDERS TEST PASSED: " + checks + " checks");
        } finally {
            for (Object m : (List<?>) op("agent.missions", Map.of())) op("agent.stop", Map.of("id", map(m).get("id")));
            server.stop(); provider.stop(0); workers.shutdownNow();
        }
    }
}
