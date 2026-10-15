package dev.workbench.rename;

import dev.workbench.device.DeviceManager;
import dev.workbench.ops.Ops;
import dev.workbench.registry.Dispatcher;
import dev.workbench.registry.OpException;
import dev.workbench.server.AgentManager;
import dev.workbench.server.EventBus;
import dev.workbench.server.RenameCoordinator;
import dev.workbench.server.Workspace;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/** Real facade/dispatcher concurrency on the mock engine; no server, JEB, model or paid API. */
public final class ConcurrentRenameTest {
    private static int checks;

    private static void check(boolean condition, String description) {
        if (!condition) throw new AssertionError(description);
        checks++;
        System.out.println("PASS " + description);
    }

    @SuppressWarnings("unchecked")
    private static Map<String,Object> map(Object value) { return (Map<String,Object>) value; }

    private static final class Harness {
        final Workspace workspace;
        final Dispatcher dispatcher;
        Harness() throws Exception {
            this.workspace = new Workspace();
            Path dir = Files.createTempDirectory("jeeves-concurrent-rename-");
            workspace.setWorkbenchRoot(dir.toFile());
            AgentManager manager = new AgentManager(workspace, new EventBus(), dir.resolve("agents").toFile());
            workspace.setAgents(manager);
            workspace.setDevices(new DeviceManager(dir.resolve("devices").toFile()));
            manager.reviewPolicy().configure("auto", null, 0);
            this.dispatcher = new Dispatcher(Ops.registerAll(workspace));
            manager.setDispatcher(dispatcher);
            dispatcher.setScopeChecker(manager::checkScope);
        }
        Map<String,Object> annotate(String target, String symbol, String name, String actor, String expected) {
            Map<String,Object> item = new java.util.LinkedHashMap<>();
            item.put("symbol", symbol); item.put("name", name);
            if (expected != null) item.put("expectedName", expected);
            return dispatcher.dispatch("tool.call", Map.of("tool", "annotate", "args",
                    Map.of("target", target, "items", List.of(item))), actor);
        }
        Map<String,Object> rename(String symbol, String name, String actor) {
            return dispatcher.dispatch("rename", Map.of("target", "t1", "symbol", symbol, "name", name), actor);
        }
        String name(String target, String symbol) throws Exception {
            return String.valueOf(workspace.engineFor(target).resolve(symbol).get("name"));
        }
    }

    private static Map<String,Object> outcome(Map<String,Object> response) {
        check(Boolean.TRUE.equals(response.get("ok")), "annotate retains per-item result envelope");
        Map<String,Object> data = map(response.get("data"));
        List<?> rows = (List<?>) data.get("results");
        return map(map(rows.get(0)).get("rename"));
    }

    private static Map<String,Object> conflict(Map<String,Object> response) {
        Map<String,Object> result = outcome(response);
        check("failed".equals(result.get("status")), "conflicting rename is a failed item");
        Map<String,Object> details = map(result.get("errorDetails"));
        check("RENAME_CONFLICT".equals(details.get("code")), "typed conflict survives Ops batch and MCP facade");
        check(String.valueOf(result.get("error")).contains(String.valueOf(details.get("currentName"))),
                "readable conflict names the current assignment");
        return details;
    }

    private static void concurrentFacade() throws Exception {
        Harness h = new Harness();
        String symbol = "MessageSyncHandler";
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Map<String,Object>> a = pool.submit(() -> { start.await(); return h.annotate("t1", symbol, "MessageDispatch", "llm:worker-a", null); });
            Future<Map<String,Object>> b = pool.submit(() -> { start.await(); return h.annotate("t1", symbol, "MessageRouter", "llm:worker-b", null); });
            start.countDown();
            Map<String,Object> ar = a.get(20, TimeUnit.SECONDS), br = b.get(20, TimeUnit.SECONDS);
            int applied = ((Number) map(ar.get("data")).get("applied")).intValue()
                    + ((Number) map(br.get("data")).get("applied")).intValue();
            check(applied == 1, "simultaneous distinct actors produce exactly one applied rename");
            boolean aWon = ((Number) map(ar.get("data")).get("applied")).intValue() == 1;
            String winner = aWon ? "MessageDispatch" : "MessageRouter";
            String actor = aWon ? "worker-a" : "worker-b";
            Map<String,Object> error = conflict(aWon ? br : ar);
            check(winner.equals(error.get("currentName")) && actor.equals(error.get("currentActor")), "loser receives winner's actual name and actor");
            check(winner.equals(h.name("t1", symbol)), "subsequent reads retain winner's name");
            check(h.workspace.historyList("t1", 0).size() == 1, "rejected rename creates no second history write");
            check(error.get("historyId") != null, "conflict links the winning history entry");
            check(winner.equals(h.name("t1", winner)), "the winner is readable by its assigned name");
            Map<String,Object> refined = h.annotate("t1", symbol, "MessageDispatchRefined", "llm:" + actor, winner);
            check("applied".equals(outcome(refined).get("status")), "same agent can refine its own unsaved name");
            Map<String,Object> latest = conflict(h.annotate("t1", "MessageDispatchRefined", "OtherGuess", "llm:third", null));
            check("MessageDispatchRefined".equals(latest.get("currentName")), "conflict returns latest refinement through current-name selector");
            String refinedId = String.valueOf(map(h.workspace.historyList("t1", 0).get(0)).get("id"));
            h.workspace.historyUndo("t1", refinedId);
            Map<String,Object> afterUndo = conflict(h.annotate("t1", symbol, "OtherGuess", "llm:third", null));
            check(winner.equals(afterUndo.get("currentName")), "undo restores previous unsaved ownership and current name");
            h.workspace.historyRedo("t1", refinedId);
            Map<String,Object> afterRedo = conflict(h.annotate("t1", symbol, "OtherGuess", "llm:third", null));
            check("MessageDispatchRefined".equals(afterRedo.get("currentName")), "redo restores latest unsaved ownership");
            check("applied".equals(outcome(h.annotate("t2", symbol, "IndependentTarget", "llm:third", null)).get("status")), "same symbol in a different target has independent ownership");
            check("applied".equals(outcome(h.annotate("t1", "MailSyncService", "IndependentSymbol", "llm:third", null)).get("status")), "different symbols in one target remain independent");
        } finally { pool.shutdownNow(); }
    }

    private static void humanAndCas() throws Exception {
        Harness h = new Harness();
        check(Boolean.TRUE.equals(h.rename("MailSyncService", "AnalystNamed", "human").get("ok")), "analyst rename applies");
        Map<String,Object> blocked = h.rename("MailSyncService", "AgentGuess", "llm:worker-a");
        check(!Boolean.TRUE.equals(blocked.get("ok")) && "E_SCOPE".equals(map(blocked.get("error")).get("code")), "human name remains protected from agents");
        check("AnalystNamed".equals(h.name("t1", "MailSyncService")), "human protection leaves the engine name unchanged");
        h.annotate("t1", "MessageSyncHandler", "AgentNamed", "llm:worker-a", null);
        check(Boolean.TRUE.equals(h.rename("MessageSyncHandler", "HumanCorrection", "human").get("ok")), "analyst can correct an agent's unsaved name");
        check("HumanCorrection".equals(h.name("t1", "MessageSyncHandler")), "analyst correction is visible");
        Map<String,Object> mismatch = conflict(h.annotate("t1", "InviteCipher", "CipherOne", "llm:worker-a", "OldGuess"));
        check("InviteCipher".equals(mismatch.get("currentName")) && "OldGuess".equals(mismatch.get("expectedName")), "CAS mismatch includes both observed and expected name");
        check(!Boolean.TRUE.equals(mismatch.get("unsaved")), "stale-name refusal does not pretend there is an unsaved owner");
        check("InviteCipher".equals(h.name("t1", "InviteCipher")), "CAS mismatch performs no engine mutation");
    }

    private static void saveGateAndOwnership() throws Exception {
        RenameCoordinator coordinator = new RenameCoordinator();
        String[] current = {"Original"};
        coordinator.transact("target", () -> {
            coordinator.check("target", "Lfixture/Original;", current[0], "llm:a", null);
            current[0] = "First";
            coordinator.renamed("target", "Lfixture/Original;", current[0], "agent:a auto-applied", "h1");
            return null;
        });
        try {
            coordinator.transact("target", () -> { throw OpException.io("injected save failure", ""); });
        } catch (OpException expected) { check("E_IO".equals(expected.code), "failed save is explicitly reported"); }
        try {
            coordinator.transact("target", () -> { coordinator.check("target", "Lfixture/Original;", current[0], "llm:b", null); return null; });
            throw new AssertionError("failed save released ownership");
        } catch (OpException expected) { check("RENAME_CONFLICT".equals(expected.code), "failed save keeps the first agent's claim"); }
        CountDownLatch saveEntered = new CountDownLatch(1), releaseSave = new CountDownLatch(1), writerReady = new CountDownLatch(1);
        List<String> order = new ArrayList<>();
        ExecutorService pool = Executors.newFixedThreadPool(3);
        try {
            Future<?> save = pool.submit(() -> coordinator.transact("target", () -> {
                saveEntered.countDown();
                try { releaseSave.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw OpException.io("interrupted test", ""); }
                coordinator.saved("target"); order.add("saved"); return null;
            }));
            check(saveEntered.await(20, TimeUnit.SECONDS), "save holds the target transaction before rename starts");
            Future<?> write = pool.submit(() -> {
                writerReady.countDown();
                return coordinator.transact("target", () -> {
                    coordinator.check("target", "Lfixture/Original;", current[0], "llm:b", null);
                    current[0] = "Second";
                    coordinator.renamed("target", "Lfixture/Original;", current[0], "llm:b", "h2");
                    order.add("renamed"); return null;
                });
            });
            check(writerReady.await(20, TimeUnit.SECONDS), "competing writer has started");
            Future<?> independent = pool.submit(() -> coordinator.transact("other", () -> "independent"));
            check("independent".equals(independent.get(20, TimeUnit.SECONDS)), "another target can progress while this save holds its gate");
            check(!write.isDone(), "writer cannot cross the in-progress save");
            releaseSave.countDown();
            save.get(20, TimeUnit.SECONDS); write.get(20, TimeUnit.SECONDS);
            check(order.equals(List.of("saved", "renamed")), "confirmed save releases the prior claim before the next write");
            coordinator.transact("target", () -> { coordinator.saved("target"); return null; });
            try {
                coordinator.transact("target", () -> { coordinator.check("target", "Lfixture/Original;", current[0], "llm:c", "First"); return null; });
                throw new AssertionError("stale expectedName succeeded after save");
            } catch (OpException expected) { check("Second".equals(expected.extra.get("currentName")), "CAS still rejects a stale caller after claims clear on save"); }
        } finally { releaseSave.countDown(); pool.shutdownNow(); }
    }

    public static void main(String[] args) throws Exception {
        check("beta".equals(RenameCoordinator.owner("agent:beta auto-applied (follows class rename)")),
                "chained class-follow actor suffixes preserve the same owner");
        check("beta".equals(RenameCoordinator.owner("llm:agent:beta (follows class rename) accepted by human")),
                "repeated actor wrappers and reversed suffix order preserve the same owner");

        concurrentFacade();
        humanAndCas();
        saveGateAndOwnership();
        System.out.println("Concurrent rename checks passed: " + checks);
    }
}
