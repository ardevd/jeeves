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
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;

/** Real HTTP transport, mock analysis, synthetic provider and CLI. No JEB or paid requests. */
public final class AgentTurnsTest {
    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final String BASE = "http://127.0.0.1:" + WorkbenchServer.PORT;
    private static final Map<String, AtomicInteger> COUNTS = new ConcurrentHashMap<>();
    private static final CountDownLatch EIGHT = new CountDownLatch(8);
    private static final CountDownLatch STOP_ENTERED = new CountDownLatch(1);
    private static final CountDownLatch STOP_RELEASE = new CountDownLatch(1);
    private static int checks;

    private static void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
        checks++;
        System.out.println("PASS " + message);
    }
    @SuppressWarnings("unchecked") private static Map<String, Object> map(Object o) { return (Map<String, Object>) o; }
    private static int number(Object n) { return n instanceof Number v ? v.intValue() : 0; }

    private static Object op(String name, Map<String, Object> params) throws Exception {
        var req = HttpRequest.newBuilder(URI.create(BASE + "/api/" + name))
                .timeout(Duration.ofSeconds(20)).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(Json.write(Map.of("params", params)))).build();
        Map<String, Object> result = Json.parseObject(HTTP.send(req, HttpResponse.BodyHandlers.ofString()).body());
        if (!Boolean.TRUE.equals(result.get("ok"))) throw new AssertionError(name + ": " + result);
        return result.get("data");
    }
    private static Map<String, Object> mission(String id) throws Exception {
        for (Object item : (List<?>) op("agent.missions", Map.of()))
            if (id.equals(map(item).get("id"))) return map(item);
        throw new AssertionError("missing mission " + id);
    }
    private static Map<String, Object> await(String id, Predicate<Map<String, Object>> condition) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(45);
        Map<String, Object> row;
        do {
            row = mission(id);
            if (condition.test(row)) return row;
            if ("blocked".equals(row.get("state"))) throw new AssertionError(row);
            Thread.sleep(20);
        } while (System.nanoTime() < end);
        throw new AssertionError("test wait expired: " + row);
    }
    private static String spawn(String model, Map<String, Object> more) throws Exception {
        Map<String, Object> params = new LinkedHashMap<>(Map.of("goal", "Research the handler and name what is understood.",
                "model", "turns-test:" + model, "budget", 1_000_000));
        params.putAll(more);
        return String.valueOf(map(op("agent.spawn", params)).get("id"));
    }
    private static Map<String, Object> response(Map<String, Object> request) throws Exception {
        String model = String.valueOf(request.get("model"));
        int turn = COUNTS.computeIfAbsent(model, x -> new AtomicInteger()).incrementAndGet();
        if (model.startsWith("parallel-") && turn == 1) {
            EIGHT.countDown();
            if (!EIGHT.await(15, TimeUnit.SECONDS)) throw new AssertionError("eight requests did not overlap");
        }
        if ("live".equals(model)) Thread.sleep(30);
        if ("stop-held".equals(model)) {
            STOP_ENTERED.countDown();
            if (!STOP_RELEASE.await(15, TimeUnit.SECONDS)) throw new AssertionError("held response was not released");
        }
        Map<String, Object> msg = new LinkedHashMap<>(Map.of("role", "assistant"));
        if ((model.startsWith("parallel-") && turn <= 104) || "live".equals(model)
                || (!model.startsWith("parallel-") && turn <= 3)) {
            String tool = turn == 2 ? "annotate" : "symbols";
            Map<String, Object> args = turn == 2
                    ? Map.of("items", List.of(Map.of("symbol", "onMessage", "name", "onEnvelope", "confidence", 0.9)))
                    : Map.of("limit", 1);
            msg.put("tool_calls", List.of(Map.of("id", model + "-" + turn, "type", "function",
                    "function", Map.of("name", tool, "arguments", Json.write(args)))));
        } else msg.put("content", "Synthetic research finished.");
        return Map.of("model", model, "choices", List.of(Map.of("index", 0, "message", msg,
                "finish_reason", msg.containsKey("tool_calls") ? "tool_calls" : "stop")),
                "usage", Map.of("prompt_tokens", 1, "completion_tokens", 1, "total_tokens", 2));
    }

    private static void headlessChecks() throws Exception {
        check(Depth.loopTurns(null, 14) == 0 && Depth.loopTurns("quick", 14) == 0
                && Depth.loopTurns("deep", 14) == 0 && Depth.cliTurns("deep", 40) == 0,
                "all headless depth defaults are unlimited");
        op("task.configure", Map.of("task", "reverse", "depth", "quick", "model", "turns-test:parallel-7", "turns", 2));
        List<String> ids = new ArrayList<>();
        for (int n = 0; n < 8; n++) {
            Map<String, Object> extra = new LinkedHashMap<>();
            if (n % 3 == 1) extra.put("depth", "quick");
            if (n % 3 == 2) extra.put("depth", "deep");
            if (n == 0) extra.put("turns", 1);
            if (n == 7) { extra.put("depth", "quick"); extra.put("model", ""); }
            ids.add(spawn("parallel-" + n, extra));
        }
        check(EIGHT.await(15, TimeUnit.SECONDS), "eight provider requests are simultaneously in flight");
        Map<String, Object> ignored = map(op("agent.setturns", Map.of("id", ids.get(0), "turns", 1)));
        check(Boolean.TRUE.equals(ignored.get("turnsUnlimited")) && ignored.get("note") != null,
                "setting a turn cap cannot reintroduce a headless ceiling and says it was ignored");
        for (String id : ids) {
            Map<String, Object> row = await(id, m -> "done".equals(m.get("state")));
            check(number(row.get("turnsDone")) > 100 && Boolean.TRUE.equals(row.get("turnsUnlimited"))
                    && number(row.get("turnCap")) == 0 && !row.containsKey("maxTurns") && !row.containsKey("turnsNeeded"),
                    id + " completes beyond 100 turns without a default, task or spawn ceiling");
            AgentManager.Mission restored = AgentManager.Mission.fromMap(row);
            check(Boolean.TRUE.equals(restored.toMap().get("turnsUnlimited")), id + " archive preserves unlimited policy");
        }
        Map<String, Object> task = map(((List<?>) op("task.list", Map.of())).get(0));
        Map<String, Object> quick = map(((List<?>) task.get("depths")).get(0));
        check(number(quick.get("turns")) == 2 && Boolean.TRUE.equals(quick.get("turnsUnlimited"))
                && number(map(quick.get("defaults")).get("turnsLoop")) == 0,
                "task API distinguishes preserved stored turns from effective unlimited execution");

        String live = spawn("live", Map.of());
        await(live, m -> number(m.get("turnsDone")) > 2);
        op("agent.pause", Map.of("id", live));
        Thread.sleep(150); // let the current synthetic response drain
        int before = COUNTS.get("live").get();
        Thread.sleep(150);
        check("paused".equals(mission(live).get("state")) && COUNTS.get("live").get() == before,
                "manual pause still prevents new requests");
        op("agent.resume", Map.of("id", live, "turns", 1));
        await(live, m -> number(m.get("turnsDone")) > before + 1);
        op("agent.stop", Map.of("id", live));
        Thread.sleep(150);
        int stopped = COUNTS.get("live").get();
        Thread.sleep(150);
        check("stopped".equals(mission(live).get("state")) && COUNTS.get("live").get() == stopped,
                "manual resume works and explicit stop ends the unlimited loop");

        String budget = spawn("budget", Map.of("budget", 1));
        Map<String, Object> paused = await(budget, m -> "paused".equals(m.get("state")));
        check(number(paused.get("budgetNeeded")) > 0 && !paused.containsKey("turnsNeeded"),
                "existing token budget remains separately identified");
        op("agent.stop", Map.of("id", budget));

        String held = spawn("stop-held", Map.of());
        check(STOP_ENTERED.await(15, TimeUnit.SECONDS), "stop test holds a real provider response in flight");
        op("agent.stop", Map.of("id", held));
        Map<String, Object> draining = mission(held);
        check(Boolean.TRUE.equals(draining.get("loopActive")) && Boolean.TRUE.equals(draining.get("cancellationUnconfirmed"))
                && "draining".equals(draining.get("displayState")), "stop remains visibly draining until the provider returns");
        STOP_RELEASE.countDown();
        Map<String, Object> settled = await(held, m -> !Boolean.TRUE.equals(m.get("loopActive")));
        check("stopped".equals(settled.get("state")) && !Boolean.TRUE.equals(settled.get("cancellationUnconfirmed"))
                && number(settled.get("inputTokens")) == 1 && number(settled.get("outputTokens")) == 1
                && COUNTS.get("stop-held").get() == 1, "settled stop retains actual paid usage and starts no next request");
    }

    private static void boundedChecks() throws Exception {
        check(Depth.loopTurns(null, 14) == 14 && Depth.loopTurns("quick", 14) == 10
                && Depth.loopTurns("deep", 14) == 100, "ordinary defaults stay bounded");
        String id = spawn("bounded", Map.of("turns", 1));
        Map<String, Object> paused = await(id, m -> "paused".equals(m.get("state")));
        check(number(paused.get("turnsDone")) == 1 && number(paused.get("turnsNeeded")) > 1
                && !Boolean.TRUE.equals(paused.get("turnsUnlimited")), "ordinary explicit cap pauses after one turn");
        op("agent.resume", Map.of("id", id, "turns", 10));
        check("done".equals(await(id, m -> "done".equals(m.get("state"))).get("state")),
                "ordinary raise and resume completes the same mission");
    }

    public static void main(String[] args) throws Exception {
        String home = System.getenv("WORKBENCH_HOME");
        if (home == null || !"1".equals(System.getenv("WORKBENCH_MOCK")))
            throw new IllegalStateException("Use the isolated agentTurnsHeadlessTest/agentTurnsBoundedTest tasks");
        Path fakeHome = Path.of(System.getProperty("user.home"));
        if (!fakeHome.startsWith(Path.of(home).getParent())) throw new IllegalStateException("test user.home must be isolated");
        Path cli = fakeHome.resolve(".local/bin/claude");
        Files.createDirectories(cli.getParent());
        Files.writeString(cli, "#!/bin/sh\nprintf '%s\\n' \"$@\" >\"$WORKBENCH_HOME/cli-args.txt\"\nprintf '{\"loggedIn\":true}\\n'\n");
        if (!cli.toFile().setExecutable(true)) throw new IllegalStateException("cannot make fake CLI executable");
        var workers = Executors.newCachedThreadPool();
        HttpServer provider = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        provider.setExecutor(workers);
        provider.createContext("/v1/chat/completions", ex -> {
            try {
                Map<String, Object> req = Json.parseObject(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                byte[] payload = Json.write(response(req)).getBytes(StandardCharsets.UTF_8);
                ex.getResponseHeaders().set("Content-Type", "application/json");
                ex.sendResponseHeaders(200, payload.length);
                ex.getResponseBody().write(payload);
            } catch (Exception e) { throw new RuntimeException(e); }
            finally { ex.close(); }
        });
        provider.start();
        WorkbenchServer server = new WorkbenchServer(Path.of("web"));
        server.start();
        try {
            op("llm.configure", Map.of("id", "turns-test", "create", true, "local", true,
                    "baseUrl", "http://127.0.0.1:" + provider.getAddress().getPort() + "/v1", "model", "bounded",
                    "models", "bounded,live,budget,stop-held,parallel-0,parallel-1,parallel-2,parallel-3,parallel-4,parallel-5,parallel-6,parallel-7"));
            if (Depth.unlimitedTurns()) headlessChecks(); else boundedChecks();
            Map<String, Object> cliMission = map(op("agent.spawn", Map.of("goal", "Synthetic CLI check", "model", "anthropic:claude-code", "turns", 1)));
            await(String.valueOf(cliMission.get("id")), m -> "done".equals(m.get("state")));
            List<String> cliArgs = Files.readAllLines(Path.of(home, "cli-args.txt"));
            check(cliArgs.contains("--max-turns") != Depth.unlimitedTurns(),
                    "real process argv omits the CLI turn ceiling only in headless mode");
            System.out.println("AGENT TURNS TEST PASSED: " + checks + " checks; headless=" + Depth.unlimitedTurns());
        } finally {
            STOP_RELEASE.countDown();
            for (Object item : (List<?>) op("agent.missions", Map.of()))
                op("agent.stop", Map.of("id", map(item).get("id")));
            server.stop();
            provider.stop(0);
            workers.shutdownNow();
        }
    }
}
