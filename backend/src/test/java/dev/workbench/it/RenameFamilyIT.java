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
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/** Native virtual-method propagation must not let a second actor overwrite a related method. */
public final class RenameFamilyIT {
    private static int checks;
    private static final String CHILD = "Lfixture/RenameFamily$Child;->a(I)I";
    private static final String PEER = "Lfixture/RenameFamily$Peer;->a(I)I";
    private static final String BASE = "Lfixture/RenameFamily$Base;->a(I)I";
    private static final String READER = "Lfixture/RenameFamily$Reader;->a(I)I";
    private static final String UNRELATED = "Lfixture/RenameFamily$Unrelated;->a(I)I";
    private static final Set<String> FAMILY = Set.of(CHILD, PEER, BASE, READER);
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        checks++; System.out.println("PASS " + message);
    }
    @SuppressWarnings("unchecked")
    private static Map<String,Object> map(Object value) { return (Map<String,Object>) value; }
    private static Map<String,Object> item(Dispatcher dispatcher, String target, String symbol, String name, String actor) {
        Map<String,Object> result = dispatcher.dispatch("tool.call", Map.of("tool", "annotate", "args",
                Map.of("target", target, "items", List.of(Map.of("symbol",symbol,"name",name)))), actor);
        if (!Boolean.TRUE.equals(result.get("ok"))) throw new AssertionError(result.toString());
        return map(map(((List<?>) map(result.get("data")).get("results")).get(0)).get("rename"));
    }
    private static void applied(Map<String,Object> result, String message) {
        check("applied".equals(result.get("status")),message+" "+result);
    }
    private static void rejected(Map<String,Object> result,String expected,String message) {
        var error=map(result.get("errorDetails"));
        check("failed".equals(result.get("status")) && error!=null
                && "RENAME_CONFLICT".equals(error.get("code")) && expected.equals(error.get("currentName")),message+" "+result);
    }
    private static void ready(Workspace workspace,String target) throws Exception {
        for (;;) {
            var info=workspace.engineFor(target).info();
            if ("ready".equals(info.get("state"))) return;
            if ("error".equals(info.get("state"))) throw new AssertionError(info.toString());
            Thread.sleep(100);
        }
    }
    private static void allNamed(Workspace workspace,String target,String expected,String message) throws Exception {
        for(String original:FAMILY) check(expected.equals(workspace.engineFor(target).resolve(original).get("name")),message+" "+original);
    }
    public static void main(String[] args) throws Exception {
        Path output=Path.of(args[1]).toAbsolutePath(); Files.createDirectories(output);
        Path input=output.resolve("RenameFamilyFixture.dex"); Files.copy(Path.of(args[0]).toAbsolutePath(),input);
        Workspace workspace=new Workspace(); workspace.setWorkbenchRoot(output.resolve("home").toFile());
        AgentManager manager=new AgentManager(workspace,new EventBus(),output.resolve("home").toFile());
        workspace.setAgents(manager); workspace.setDevices(new DeviceManager(output.resolve("home").toFile()));
        manager.reviewPolicy().configure("auto",null,0);
        Dispatcher dispatcher=new Dispatcher(Ops.registerAll(workspace));
        manager.setDispatcher(dispatcher); dispatcher.setScopeChecker(manager::checkScope);
        try {
            workspace.openProject("RenameFamilyFixture");
            String target=String.valueOf(workspace.openTarget(input.toString(),"jeb").get("id")); ready(workspace,target);
            var family=workspace.engineFor(target).renameTargets(CHILD);
            check(family.stream().map(x->String.valueOf(x.get("origSig"))).collect(Collectors.toSet()).equals(FAMILY),"public JEB query identifies the complete family and excludes unrelated method");
            CountDownLatch start=new CountDownLatch(1); var pool=Executors.newFixedThreadPool(2);
            String winner,owner;
            try {
                var a=pool.submit(()->{start.await(); return item(dispatcher,target,CHILD,"readValue","llm:child-owner");});
                var b=pool.submit(()->{start.await(); return item(dispatcher,target,PEER,"readPayload","llm:peer-owner");});
                start.countDown(); var ar=a.get(30,TimeUnit.SECONDS); var br=b.get(30,TimeUnit.SECONDS);
                boolean wonA="applied".equals(ar.get("status")); winner=wonA?"readValue":"readPayload"; owner=wonA?"llm:child-owner":"llm:peer-owner";
                applied(wonA?ar:br,"one related-method writer wins");
                rejected(wonA?br:ar,winner,"other related-method writer receives the winning current name");
            } finally { pool.shutdownNow(); }
            allNamed(workspace,target,winner,"JEB applied the winning name throughout the family");
            check(workspace.historyList(target,0).size()==1,"propagated rename has one journal entry and rejected writer has none");
            rejected(item(dispatcher,target,READER,"overwriteViaInterface","llm:third"),winner,"interface selector cannot bypass ownership");
            applied(item(dispatcher,target,BASE,"readRefined",owner),"owner can refine via another family member");
            allNamed(workspace,target,"readRefined","owner refinement propagates consistently");
            applied(item(dispatcher,target,UNRELATED,"readUnrelated","llm:third"),"unrelated same-signature method remains independent");
            var history=workspace.historyList(target,0);
            String refinement=history.stream().map(RenameFamilyIT::map).filter(x->"readRefined".equals(x.get("newName"))).map(x->String.valueOf(x.get("id"))).findFirst().orElseThrow();
            workspace.historyUndo(target,refinement);
            rejected(item(dispatcher,target,PEER,"overwriteAfterUndo","llm:third"),winner,"undo rebuilds all family claims");
            workspace.historyRedo(target,refinement);
            rejected(item(dispatcher,target,READER,"overwriteAfterRedo","llm:third"),"readRefined","redo rebuilds all family claims");
            check(Boolean.TRUE.equals(workspace.saveTarget(target).get("saved")),"family rename saves natively");
            applied(item(dispatcher,target,READER,"readAfterSave","llm:next-round"),"confirmed save releases complete family ownership");
            check(Boolean.TRUE.equals(workspace.saveTarget(target).get("saved")),"new family name saves");
            workspace.closeTarget(target);
            String reopened=String.valueOf(workspace.openTarget(input.toString(),"jeb").get("id")); ready(workspace,reopened);
            allNamed(workspace,reopened,"readAfterSave","native reopen retains complete method family");
            var human=dispatcher.dispatch("rename",Map.of("target",reopened,"symbol",CHILD,"name","readByAnalyst"),"human");
            check(Boolean.TRUE.equals(human.get("ok")),"analyst names the family");
            var blocked=item(dispatcher,reopened,PEER,"overwriteAnalyst","llm:next-round");
            check("failed".equals(blocked.get("status")) && "E_SCOPE".equals(map(blocked.get("errorDetails")).get("code")),"related selector cannot overwrite analyst-owned family");
            allNamed(workspace,reopened,"readByAnalyst","analyst family name remains unchanged");
            System.out.println("Native method-family checks passed: "+checks);
        } catch (Exception | AssertionError failure) {
            failure.printStackTrace(System.out); throw failure;
        } finally { workspace.closeAll(); JebCore.shutdown(); }
    }
}
