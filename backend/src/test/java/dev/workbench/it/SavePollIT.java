package dev.workbench.it;

import dev.workbench.engine.Engine;
import dev.workbench.llm.ToolFacade;
import dev.workbench.registry.Json;
import dev.workbench.registry.OpException;
import dev.workbench.server.WorkbenchServer;
import dev.workbench.server.Workspace;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

/** Actual HTTP/facade with latch-controlled synthetic saves; no engine/device/provider. */
public final class SavePollIT {
    private static int checks;
    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final Map<String,Object> LIMITATION = Map.of("apkResourceCacheSweep",
            Map.of("status", "unqualified-version", "reclamationMeasured", false));

    private static final class Save {
        final CountDownLatch entered = new CountDownLatch(1), begin = new CountDownLatch(1),
                writing = new CountDownLatch(1), finish = new CountDownLatch(1), done = new CountDownLatch(1);
        volatile Map<String,Object> status = Map.of("saving", false, "last", Map.of("ok", true, "at", 1L));
        final boolean ok;
        Save(boolean ok) { this.ok = ok; }
        Object run() throws Exception {
            entered.countDown();
            try {
                if (!begin.await(10, TimeUnit.SECONDS)) throw new AssertionError("begin timeout");
                status = Map.of("saving", true, "startedAt", System.currentTimeMillis()); writing.countDown();
                if (!finish.await(10, TimeUnit.SECONDS)) throw new AssertionError("finish timeout");
                Map<String,Object> last = new LinkedHashMap<>(Map.of("ok", ok, "at", System.currentTimeMillis(), "ms", 1, "bytes", ok ? 12 : 0));
                if (!ok) last.put("error", "synthetic write failure");
                status = Map.of("saving", false, "last", last);
                if (!ok) throw OpException.io("synthetic write failure", "fixture");
                return Map.of("saved", true);
            } finally { done.countDown(); }
        }
        void release() { begin.countDown(); finish.countDown(); }
    }

    @SuppressWarnings("unchecked")
    public static void main(String[] args) throws Exception {
        if (!"1".equals(System.getenv("WORKBENCH_MOCK")) || System.getenv("WORKBENCH_HOME") == null
                || System.getenv("WORKBENCH_PORT") == null) throw new IllegalStateException("Private mock home and port required");
        WorkbenchServer server = new WorkbenchServer(Path.of(System.getenv("WORKBENCH_TEST_WEB")));
        Field wf = WorkbenchServer.class.getDeclaredField("workspace"); wf.setAccessible(true);
        Workspace workspace = (Workspace)wf.get(server);
        Field ef = Workspace.class.getDeclaredField("engines"); ef.setAccessible(true);
        Map<String,Engine> engines = (Map<String,Engine>)ef.get(workspace);
        List<Object> samples = new ArrayList<>();
        server.start();
        try {
            for (boolean success : List.of(true, false)) {
                Save save = new Save(success);
                Engine original = engines.get("t1");
                Engine controlled = (Engine)Proxy.newProxyInstance(Engine.class.getClassLoader(), new Class<?>[]{Engine.class}, (proxy, method, values) -> {
                    if (method.getName().equals("save")) return save.run();
                    if (method.getName().equals("saveStatus")) return save.status;
                    try { return method.invoke(original, values); }
                    catch (InvocationTargetException e) { throw e.getCause(); }
                });
                engines.put("t1", controlled);
                try {
                    Map<String,Object> started = data(call("tool.call", Map.of("tool", "target", "args", Map.of("action", "save", "target", "t1"))));
                    Map<String,Object> example = (Map<String,Object>)started.get("pollTool");
                    samples.add(example);
                    check(Boolean.TRUE.equals(started.get("started")), "async save accepted");
                    check(save.entered.await(5, TimeUnit.SECONDS), "save thread started");
                    long at = ((Number)started.get("startedAt")).longValue();
                    Map<String,Object> stale = data(call("tool.call", example));
                    check(!completed(stale, at), "saving:false with old successful last is not completion");
                    check(!completed(Map.of("saving", false), at), "missing last is not completion");
                    save.begin.countDown(); check(save.writing.await(5, TimeUnit.SECONDS), "write began");
                    check(Boolean.TRUE.equals(data(call("tool.call", example)).get("saving")), "returned example observes in-flight write");
                    Map<String,Object> already = data(call("tool.call", Map.of("tool", "target", "args", Map.of("action", "save", "target", "t1"))));
                    check(Boolean.TRUE.equals(already.get("alreadySaving")) && already.containsKey("startedAt"), "existing save has timestamp");
                    samples.add(already.get("pollTool"));
                    check(Boolean.TRUE.equals(data(call("tool.call", (Map<String,Object>)already.get("pollTool"))).get("saving")), "already-saving example callable");
                    save.finish.countDown(); check(save.done.await(5, TimeUnit.SECONDS), "write finished");
                    Map<String,Object> finalStatus = data(call("tool.call", example));
                    check(completed(finalStatus, at), "fresh last with saving:false is completion");
                    Map<?,?> last = (Map<?,?>)finalStatus.get("last");
                    check(Boolean.valueOf(success).equals(last.get("ok")), "success and failure are distinct");
                    check(success || last.containsKey("error"), "failed save includes diagnostic");
                    check(!completed(finalStatus, ((Number)last.get("at")).longValue() + 1), "last.at below startedAt refused");
                    check(completed(finalStatus, ((Number)last.get("at")).longValue()), "last.at equal to startedAt accepted");
                    Map<String,Object> op = (Map<String,Object>)started.get("pollOp");
                    check(data(call((String)op.get("op"), (Map<String,Object>)op.get("params"))).equals(finalStatus), "legacy op still returns same result");
                    check(Boolean.FALSE.equals(call("tool.call", Map.of("tool", "target", "args", Map.of("action", "saveStatus", "target", "t1"))).get("ok")), "no untested camel-case alias introduced");
                } finally { save.release(); save.done.await(5, TimeUnit.SECONDS); engines.put("t1", original); }
            }
            for (boolean async : List.of(true, false)) {
                String tid = async ? "t1" : "t2";
                Engine original = engines.get(tid);
                engines.put(tid, (Engine)Proxy.newProxyInstance(Engine.class.getClassLoader(), new Class<?>[]{Engine.class}, (proxy, method, values) -> {
                    if (method.getName().equals("closeCapabilities")) return LIMITATION;
                    try { return method.invoke(original, values); } catch (InvocationTargetException e) { throw e.getCause(); }
                }));
                Map<String,Object> close = data(call("tool.call", Map.of("tool", "target", "args", Map.of("action", "close", "target", tid, "async", async))));
                check(LIMITATION.equals(close.get("closeCapabilities")), (async ? "async" : "sync") + " facade close preserves capability limitation");
            }
            Object schema = ToolFacade.catalogue().stream().map(x -> (Map<?,?>)x).filter(x -> "target".equals(x.get("tool"))).findFirst().orElseThrow().get("parameters");
            Files.writeString(Path.of(System.getenv("WORKBENCH_HOME"), "save-poll-receipt.json"), Json.write(Map.of("schema", schema, "examples", samples, "checks", checks)));
            System.out.println("Save poll / close capability HTTP checks: " + checks + " passed");
        } finally { server.stop(); }
    }

    private static boolean completed(Map<String,Object> status, long startedAt) {
        return Boolean.FALSE.equals(status.get("saving")) && status.get("last") instanceof Map<?,?> last
                && last.get("at") instanceof Number at && at.longValue() >= startedAt && last.get("ok") instanceof Boolean;
    }
    private static void check(boolean test, String message) { if (!test) throw new AssertionError(message); checks++; }
    @SuppressWarnings("unchecked")
    private static Map<String,Object> data(Map<String,Object> response) {
        if (!Boolean.TRUE.equals(response.get("ok"))) throw new AssertionError(Json.write(response));
        return (Map<String,Object>)response.get("data");
    }
    @SuppressWarnings("unchecked")
    private static Map<String,Object> call(String op, Map<String,Object> params) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + System.getenv("WORKBENCH_PORT") + "/api/" + op))
                .timeout(Duration.ofSeconds(10)).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(Json.write(Map.of("params", params, "actor", "human")))).build();
        return (Map<String,Object>)Json.parse(HTTP.send(request, HttpResponse.BodyHandlers.ofString()).body());
    }
}
