package dev.workbench.it;

import dev.workbench.device.DeviceManager;
import dev.workbench.engine.jeb.JebCore;
import dev.workbench.ops.Ops;
import dev.workbench.registry.Dispatcher;
import dev.workbench.server.AgentManager;
import dev.workbench.server.EventBus;
import dev.workbench.server.Workspace;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** Actual JEB + public annotate facade, on the redistributable DEX fixture, without a model. */
public final class ConcurrentRenameIT {
    private static int checks;
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        checks++; System.out.println("PASS " + message);
    }
    @SuppressWarnings("unchecked")
    private static Map<String,Object> map(Object value) { return (Map<String,Object>) value; }
    private static Map<String,Object> item(Dispatcher dispatcher, String target, String symbol,
                                           String name, String actor, String local, String expected) {
        Map<String,Object> edit = new LinkedHashMap<>();
        edit.put("symbol", symbol); edit.put("name", name);
        if (local != null) edit.put("local", local);
        if (expected != null) edit.put("expectedName", expected);
        Map<String,Object> result = dispatcher.dispatch("tool.call", Map.of("tool", "annotate", "args",
                Map.of("target", target, "items", List.of(edit))), actor);
        if (!Boolean.TRUE.equals(result.get("ok"))) throw new AssertionError(result.toString());
        return map(map(((List<?>) map(result.get("data")).get("results")).get(0)).get("rename"));
    }
    private static void applied(Map<String,Object> result, String message) { check("applied".equals(result.get("status")), message + " " + result); }
    private static void rejected(Map<String,Object> result, String name, String message) {
        Map<String,Object> error = map(result.get("errorDetails"));
        check("failed".equals(result.get("status")) && error != null
                && "RENAME_CONFLICT".equals(error.get("code")) && name.equals(error.get("currentName")), message + " " + result);
    }
    private static void ready(Workspace workspace, String target) throws Exception {
        for (;;) {
            Map<String,Object> info = workspace.engineFor(target).info();
            if ("ready".equals(info.get("state"))) return;
            if ("error".equals(info.get("state"))) throw new AssertionError(info.toString());
            Thread.sleep(100);
        }
    }
    public static void main(String[] args) throws Exception {
        Path output = Path.of(args[1]).toAbsolutePath(); Files.createDirectories(output);
        Path input = output.resolve("ConcurrentRenameFixture.dex");
        Files.copy(Path.of(args[0]).toAbsolutePath(), input);
        Workspace workspace = new Workspace();
        workspace.setWorkbenchRoot(output.resolve("home").toFile());
        AgentManager manager = new AgentManager(workspace, new EventBus(), output.resolve("home").toFile());
        workspace.setAgents(manager);
        workspace.setDevices(new DeviceManager(output.resolve("home").toFile()));
        manager.reviewPolicy().configure("auto", null, 0);
        Dispatcher dispatcher = new Dispatcher(Ops.registerAll(workspace));
        manager.setDispatcher(dispatcher); dispatcher.setScopeChecker(manager::checkScope);
        try {
            workspace.openProject("ConcurrentRenameFixture");
            String target = String.valueOf(workspace.openTarget(input.toString(), "jeb").get("id"));
            ready(workspace, target);
            CountDownLatch start = new CountDownLatch(1);
            var pool = Executors.newFixedThreadPool(2);
            String winner;
            String winnerActor;
            try {
                var a = pool.submit(() -> { start.await(); return item(dispatcher,target,"Lfixture/Intent;","IntentRequest","llm:alpha",null,null); });
                var b = pool.submit(() -> { start.await(); return item(dispatcher,target,"Lfixture/Intent;","IntentEnvelope","llm:beta",null,null); });
                start.countDown();
                var ar = a.get(30, TimeUnit.SECONDS); var br = b.get(30, TimeUnit.SECONDS);
                boolean wonA = "applied".equals(ar.get("status"));
                winner = wonA ? "IntentRequest" : "IntentEnvelope";
                winnerActor = wonA ? "llm:alpha" : "llm:beta";
                applied(wonA ? ar : br, "native class race has one winner");
                rejected(wonA ? br : ar, winner, "native class loser receives exact winning name");
            } finally { pool.shutdownNow(); }
            check(workspace.historyList(target,0).size()==1,"native rejected class rename has no applied history entry");
            check(winner.equals(workspace.engineFor(target).resolve("Lfixture/Intent;").get("name")),"native original signature resolves to winning name");
            check(String.valueOf(workspace.engineFor(target).decompile("Lfixture/Intent;",Map.of()).get("text")).contains(winner),"actual decompiled source contains winning class name");
            applied(item(dispatcher,target,"Lfixture/Intent;","IntentRefined",winnerActor,null,winner),"native owner can refine its name");
            String method="Lfixture/Params;->annotOnly(ILjava/lang/String;)I";
            applied(item(dispatcher,target,method,"readCount","llm:method-owner",null,null),"method owner assigns name");
            applied(item(dispatcher,target,"Lfixture/Params;","ParametersExpanded","llm:class-owner",null,null),"another agent names the containing class");
            rejected(item(dispatcher,target,method,"readLabel","llm:other",null,null),"readCount","original method identity remains protected after class rename");
            rejected(item(dispatcher,target,"Lfixture/ParametersExpanded;->readCount(ILjava/lang/String;)I","readLabel","llm:other",null,null),"readCount","current method address cannot evade the claim");
            applied(item(dispatcher,target,"Lfixture/Params;->flagsMask:I","messageFlags","llm:field-owner",null,null),"field rename applies through original owner signature");
            rejected(item(dispatcher,target,"Lfixture/Params;->flagsMask:I","otherFlags","llm:other",null,null),"messageFlags","field name is protected before save");
            String localMethod="Lfixture/Params;->absent(I)I";
            List<?> locals=workspace.engineFor(target).locals(localMethod);
            check(!locals.isEmpty(),"fixture exposes a real decompiler local or argument");
            String originalLocal=String.valueOf(map(locals.get(0)).get("name"));
            applied(item(dispatcher,target,localMethod,"argumentCount","llm:local-owner",originalLocal,null),"local rename applies");
            rejected(item(dispatcher,target,localMethod,"argumentOther","llm:other",originalLocal,null),"argumentCount","original local identifier cannot overwrite another agent's name");
            applied(item(dispatcher,target,localMethod,"argumentCountRefined","llm:local-owner","argumentCount","argumentCount"),"same local owner refines the current name");
            rejected(item(dispatcher,target,localMethod,"argumentOther","llm:other","argumentCountRefined",null),"argumentCountRefined","local conflict returns current refined name");
            String autoMethod="Lfixture/Params;->permuted(Lfixture/Intent;Lfixture/IntentSender;II)V";
            Map<String,Object> objectLocal=workspace.engineFor(target).locals(autoMethod).stream().map(ConcurrentRenameIT::map)
                    .filter(row->String.valueOf(row.get("type")).contains("Intent") && !"this".equals(row.get("name"))).findFirst().orElseThrow();
            String objectName=String.valueOf(objectLocal.get("name"));
            applied(item(dispatcher,target,autoMethod,"selectedEnvelope","llm:auto-owner",objectName,null),"explicit local owner establishes an object-variable name");
            Map<String,Object> autoConflict=dispatcher.dispatch("locals.auto",Map.of("target",target,"symbol",autoMethod,"all",true),"llm:auto-other");
            check(!Boolean.TRUE.equals(autoConflict.get("ok")) && "RENAME_CONFLICT".equals(map(autoConflict.get("error")).get("code"))
                    && "selectedEnvelope".equals(map(autoConflict.get("error")).get("currentName")),"bulk automatic local names cannot overwrite another actor's unsaved name");
            check(workspace.engineFor(target).locals(autoMethod).stream().map(ConcurrentRenameIT::map).anyMatch(row->"selectedEnvelope".equals(row.get("name"))),"rejected automatic rename leaves the chosen name intact");
            Path historyFile;
            try (var paths=Files.walk(output.resolve("home"))) { historyFile=paths.filter(path->path.getFileName().toString().equals("history.json")).findFirst().orElseThrow(); }
            Path historyBackup=historyFile.resolveSibling("history.injected-backup.json");
            var injected=new java.util.concurrent.atomic.AtomicBoolean();
            workspace.setHistoryListener((tid,entry)->{
                if ("type".equals(entry.get("auto")) && injected.compareAndSet(false,true)) try {
                    Files.move(historyFile,historyBackup); Files.createDirectory(historyFile);
                    Files.writeString(historyFile.resolve("blocker"),"injected journal failure after first bulk item");
                } catch (java.io.IOException failure) { throw new java.io.UncheckedIOException(failure); }
            });
            Map<String,Object> autoResult;
            try { autoResult=dispatcher.dispatch("locals.auto",Map.of("target",target,"symbol",autoMethod,"all",true),"llm:auto-owner"); }
            finally {
                workspace.setHistoryListener(null);
                if (Files.isDirectory(historyFile)) { Files.delete(historyFile.resolve("blocker")); Files.delete(historyFile); Files.move(historyBackup,historyFile); }
            }
            check(injected.get() && !Boolean.TRUE.equals(autoResult.get("ok")) && "E_IO".equals(map(autoResult.get("error")).get("code")),"mid-bulk journal failure reports the actual applied-but-not-durable state");
            List<?> autoRenames=(List<?>)map(autoResult.get("error")).get("appliedRenames");
            check(autoRenames.size()>=2,"bulk failure fixture applied more than one real object-local rename");
            for (Object value:autoRenames) {
                String assigned=String.valueOf(map(value).get("name"));
                rejected(item(dispatcher,target,autoMethod,"otherLocalGuess","llm:auto-other",assigned,null),assigned,"every applied bulk local keeps ownership after a journal failure");
                check(workspace.historyList(target,0).stream().map(ConcurrentRenameIT::map).anyMatch(row->assigned.equals(row.get("newName"))
                        && "type".equals(row.get("auto")) && row.get("originalSymbol")!=null && row.get("originalMethod")!=null),"every applied bulk local has stable recovery metadata");
            }
            applied(item(dispatcher,target,"Lfixture/Intent;","IntentFollowBlocked",winnerActor,null,null),"class owner can refine the class while a different actor owns its bound local");
            check(workspace.engineFor(target).locals(autoMethod).stream().map(ConcurrentRenameIT::map).anyMatch(row->"intentRefined".equals(row.get("name"))),"automatic type following cannot overwrite another actor's unsaved local name");
            check(workspace.historyList(target,0).stream().map(ConcurrentRenameIT::map)
                    .filter(row->"IntentFollowBlocked".equals(row.get("newName"))).map(row->row.get("followed"))
                    .anyMatch(value->value instanceof List<?> rows && rows.stream().map(ConcurrentRenameIT::map)
                            .anyMatch(row->"RENAME_CONFLICT".equals(map(row.get("errorDetails")).get("code")))),"skipped type-follow rename records its conflict in parent history");
            check(Boolean.TRUE.equals(workspace.saveTarget(target).get("saved")),"native save succeeds with all guarded edits");
            applied(item(dispatcher,target,"Lfixture/Intent;","IntentRoundTwo",winnerActor,null,null),"after save, automatic type-follow applies with the new class name");
            rejected(item(dispatcher,target,autoMethod,"otherFollowGuess","llm:auto-other","intentRoundTwo",null),"intentRoundTwo","automatic type-follow establishes an unsaved local claim");
            for (Object value:autoRenames) if ("intentRefined".equals(map(value).get("name"))) map(value).put("name","intentRoundTwo");

            rejected(item(dispatcher,target,method,"readAfterSave","llm:other",null,"annotOnly"),"readCount","CAS rejects stale read after successful save");
            applied(item(dispatcher,target,method,"readAfterSave","llm:other",null,"readCount"),"confirmed save releases previous unsaved ownership");
            check(Boolean.TRUE.equals(workspace.saveTarget(target).get("saved")),"next round saves successfully");
            workspace.closeTarget(target);
            String reopened=String.valueOf(workspace.openTarget(input.toString(),"jeb").get("id")); ready(workspace,reopened);
            check("IntentRoundTwo".equals(workspace.engineFor(reopened).resolve("Lfixture/Intent;").get("name")),"reopened native database retains class name");
            check("readAfterSave".equals(workspace.engineFor(reopened).resolve(method).get("name")),"reopened native database retains method name");
            check("messageFlags".equals(workspace.engineFor(reopened).resolve("Lfixture/Params;->flagsMask:I").get("name")),"reopened native database retains field name");
            check(workspace.engineFor(reopened).locals(localMethod).stream().anyMatch(x->"argumentCountRefined".equals(map(x).get("name"))),"reopened native database retains local name");
            for (Object value:autoRenames) check(workspace.engineFor(reopened).locals(autoMethod).stream().map(ConcurrentRenameIT::map)
                    .anyMatch(row->map(value).get("name").equals(row.get("name"))),"reopened database retains an automatically named local after repaired journal failure");
            System.out.println("Native concurrent rename checks passed: "+checks);
        } catch (Exception | AssertionError failure) {
            failure.printStackTrace(System.out); throw failure;
        } finally { workspace.closeAll(); JebCore.shutdown(); }
    }
}
