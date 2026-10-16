package dev.workbench.registry;

import com.sun.net.httpserver.HttpServer;
import dev.workbench.ops.Ops;
import dev.workbench.server.AgentManager;
import dev.workbench.server.EventBus;
import dev.workbench.server.Workspace;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/** Deterministic dispatcher checks, including a real loopback HTTP client that disconnects. */
public final class ActiveCallsTest {
    private static int checks;
    private static synchronized void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        checks++;
        System.out.println("PASS " + message);
    }
    private static void await(CountDownLatch latch, String message) {
        try { check(latch.await(10, TimeUnit.SECONDS), message); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
    }
    @SuppressWarnings("unchecked")
    private static Map<String,Object> map(Object value) { return (Map<String,Object>)value; }
    private static final class Harness {
        final Registry registry;
        final Dispatcher dispatcher;
        Harness() throws Exception {
            Workspace workspace = new Workspace();
            var dir = Files.createTempDirectory("jeeves-active-calls-");
            workspace.setWorkbenchRoot(dir.toFile());
            AgentManager manager = new AgentManager(workspace, new EventBus(), dir.resolve("agents").toFile());
            workspace.setAgents(manager);
            registry = Ops.registerAll(workspace);
            dispatcher = new Dispatcher(registry);
            manager.setDispatcher(dispatcher);
        }
        List<?> calls() {
            Map<String,Object> response = dispatcher.dispatch("ops.active", Map.of(), "human");
            check(Boolean.TRUE.equals(response.get("ok")), "ops.active remains available while work is blocked");
            return (List<?>)map(response.get("data")).get("calls");
        }
    }
    private static void disconnectedClient() throws Exception {
        Harness h = new Harness();
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1), done = new CountDownLatch(1);
        h.registry.register(new Op("fixture.block", "", false, Op.Cost.heavy, request -> {
            entered.countDown(); await(release, "disconnected operation is explicitly released"); return Map.of("completed", true);
        }));
        ExecutorService workers = Executors.newCachedThreadPool();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(workers);
        server.createContext("/api/fixture.block", exchange -> {
            try {
                Map<String,Object> body = map(Json.parse(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
                Map<String,Object> response = h.dispatcher.dispatch("fixture.block", map(body.get("params")), String.valueOf(body.get("actor")));
                byte[] bytes = Json.write(response).getBytes(StandardCharsets.UTF_8);
                try {
                    exchange.sendResponseHeaders(200, bytes.length);
                    exchange.getResponseBody().write(bytes);
                } catch (java.io.IOException expectedDisconnect) { /* the client has intentionally gone */ }
            } finally { exchange.close(); done.countDown(); }
        });
        server.start();
        try {
            Socket client = new Socket("127.0.0.1", server.getAddress().getPort());
            try {
                byte[] body = Json.write(Map.of("actor", "llm:detached-worker", "params",
                        Map.of("target", "t1", "symbol", "Lfixture/Message;", "query", "PRIVATE_QUERY", "token", "PRIVATE_TOKEN"))).getBytes(StandardCharsets.UTF_8);
                String headers = "POST /api/fixture.block HTTP/1.1\r\nHost: localhost\r\nContent-Type: application/json\r\nContent-Length: " + body.length + "\r\n\r\n";
                client.getOutputStream().write(headers.getBytes(StandardCharsets.US_ASCII));
                client.getOutputStream().write(body); client.getOutputStream().flush();
                await(entered, "HTTP handler is waiting inside the operation");
            } finally { client.close(); }
            List<?> calls = h.calls();
            check(calls.size() == 1, "disconnect does not falsely clear an in-flight operation");
            Map<String,Object> call = map(calls.get(0));
            check("llm:detached-worker".equals(call.get("actor")) && "fixture.block".equals(call.get("op")), "active row identifies the detached worker and operation");
            check("t1".equals(call.get("target")) && "Lfixture/Message;".equals(call.get("symbol")), "active row preserves target and symbol identity");
            check(call.get("id") instanceof Number && call.get("startedAt") instanceof String, "active row has a stable call id and start timestamp");
            check(Boolean.FALSE.equals(call.get("mutating")), "read operation reports its mutation classification");
            check(call.keySet().equals(Set.of("id", "op", "actor", "mutating", "startedAt", "target", "symbol")), "active row excludes query text, tokens and the full parameter body");
            release.countDown(); await(done, "detached HTTP operation reaches its own completion");
            check(h.calls().isEmpty(), "success clears active state even when the HTTP response has no client");
        } finally { release.countDown(); server.stop(0); workers.shutdownNow(); }
    }
    private static void parallelAndNested() throws Exception {
        Harness h = new Harness();
        CountDownLatch entered = new CountDownLatch(5), release = new CountDownLatch(1);
        h.registry.register(new Op("fixture.child", "", true, Op.Cost.heavy, request -> {
            entered.countDown(); await(release, "parallel child is released"); return "done";
        }));
        h.registry.register(new Op("fixture.parent", "", false, Op.Cost.cheap,
                request -> h.dispatcher.dispatch("fixture.child", request.params(), request.actor())));
        ExecutorService workers = Executors.newFixedThreadPool(5);
        List<Future<?>> futures = new ArrayList<>();
        try {
            for (int i=0; i<5; i++) {
                String actor = i == 0 ? "human:parallel" : "llm:parallel-" + i;
                futures.add(workers.submit(() -> h.dispatcher.dispatch("fixture.parent", Map.of("target", "t1"), actor)));
            }
            await(entered, "five independent nested operations are concurrently blocked");
            List<?> calls = h.calls();
            check(calls.size() == 10, "all five parents and children are reported without a reporting cap");
            check(calls.stream().filter(row -> Boolean.TRUE.equals(map(row).get("mutating"))).count()==5,
                    "all nested writes report mutation even beneath read-only facade operations");
            check(calls.stream().anyMatch(row -> "human:parallel".equals(map(row).get("actor")) && Boolean.TRUE.equals(map(row).get("mutating"))),
                    "deployment can identify in-flight human writes independently of actor prefixes");
            check(calls.stream().map(row -> map(row).get("id")).distinct().count() == 10, "nested and parallel calls have distinct ids");
            check(calls.stream().noneMatch(row -> "ops.active".equals(map(row).get("op"))), "the query never reports itself as active work");
            release.countDown();
            for (Future<?> future : futures) future.get(10, TimeUnit.SECONDS);
            check(h.calls().isEmpty(), "all nested rows clear after completion");
        } finally { release.countDown(); workers.shutdownNow(); }
    }
    private static void failureCleanup() throws Exception {
        Harness h = new Harness();
        h.registry.register(new Op("fixture.error", "", false, Op.Cost.cheap, request -> { throw OpException.io("expected fixture failure", ""); }));
        h.registry.register(new Op("fixture.fatal", "", false, Op.Cost.cheap, request -> { throw new AssertionError("expected fatal fixture failure"); }));
        Map<String,Object> failed = h.dispatcher.dispatch("fixture.error", Map.of(), "llm:failed-worker");
        check(!Boolean.TRUE.equals(failed.get("ok")), "ordinary op errors keep their existing response contract");
        check(h.calls().isEmpty(), "ordinary op errors clear active state");
        try { h.dispatcher.dispatch("fixture.fatal", Map.of(), "llm:failed-worker"); throw new IllegalStateException("Error was swallowed"); }
        catch (AssertionError expected) { check(expected.getMessage().contains("fatal fixture"), "fatal errors preserve their propagation"); }
        check(h.calls().isEmpty(), "finally clears active state even for a thrown Error");
    }
    public static void main(String[] args) throws Exception {
        disconnectedClient(); parallelAndNested(); failureCleanup();
        System.out.println("Active operation checks passed: " + checks);
    }
}
