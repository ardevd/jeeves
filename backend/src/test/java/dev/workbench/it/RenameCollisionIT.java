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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;

/** Native destination-name collisions, through the same annotate results an agent reads. */
public final class RenameCollisionIT {
    private static int checks;
    private static synchronized void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        checks++; System.out.println("PASS " + message);
    }
    @SuppressWarnings("unchecked")
    private static Map<String,Object> map(Object value) { return (Map<String,Object>) value; }
    private static Map<String,Object> annotate(Dispatcher dispatcher, String target, List<Map<String,Object>> items, String actor) {
        Map<String,Object> result = dispatcher.dispatch("tool.call", Map.of("tool", "annotate", "args",
                Map.of("target", target, "items", items)), actor);
        check(Boolean.TRUE.equals(result.get("ok")), "annotate preserves its result envelope");
        return map(result.get("data"));
    }
    private static Map<String,Object> renameResult(Dispatcher dispatcher, String target, String symbol, String name, String actor) {
        return map(map(((List<?>)annotate(dispatcher,target,List.of(Map.of("symbol",symbol,"name",name)),actor).get("results")).get(0)).get("rename"));
    }
    private static Map<String,Object> collision(Map<String,Object> result, String occupant, String name) {
        check("failed".equals(result.get("status")), "taken-name rename is a failed item");
        Map<String,Object> error = map(result.get("errorDetails"));
        check("E_BUSY".equals(error.get("code")) && name.equals(error.get("requestedName")), "existing error code and requested name survive the facade");
        check("matched".equals(error.get("collisionLookup")), "native occupant lookup succeeds: " + error.get("conflicts"));
        List<?> conflicts = (List<?>)error.get("conflicts");
        Map<String,Object> found = conflicts.stream().map(RenameCollisionIT::map)
                .filter(row->occupant.equals(row.get("originalSymbol"))).findFirst().orElseThrow(()->new AssertionError(error));
        check(name.equals(found.get("currentName")) && found.get("addr") != null, "collision returns original identity and current address/name");
        check(String.valueOf(result.get("error")).contains(occupant), "readable error also identifies the other symbol");
        return found;
    }
    private static void applied(Dispatcher dispatcher, String target, String symbol, String name, String actor) {
        check("applied".equals(renameResult(dispatcher,target,symbol,name,actor).get("status")), "fixture rename applies: " + name);
    }
    private static void ready(Workspace workspace, String target) throws Exception {
        for (;;) {
            Map<String,Object> info = workspace.engineFor(target).info();
            if ("ready".equals(info.get("state"))) return;
            if ("error".equals(info.get("state"))) throw new AssertionError(info);
            Thread.sleep(100);
        }
    }
    private static Map<String,Object> localItem(String method, String local, String name) {
        return Map.of("symbol",method,"local",local,"name",name);
    }
    private static Map<String,Object> localResult(Dispatcher dispatcher, String target, String method, String local, String name, String actor) {
        return map(map(((List<?>)annotate(dispatcher,target,List.of(localItem(method,local,name)),actor).get("results")).get(0)).get("rename"));
    }
    public static void main(String[] args) throws Exception {
        Path output = Path.of(args[1]).toAbsolutePath(); Files.createDirectories(output);
        Path input = output.resolve("RenameCollisionFixture.dex"); Files.copy(Path.of(args[0]), input);
        Workspace workspace = new Workspace(); workspace.setWorkbenchRoot(output.resolve("home").toFile());
        AgentManager manager = new AgentManager(workspace,new EventBus(),output.resolve("home").toFile());
        workspace.setAgents(manager); workspace.setDevices(new DeviceManager(output.resolve("home").toFile()));
        manager.reviewPolicy().configure("auto",null,0);
        Dispatcher dispatcher = new Dispatcher(Ops.registerAll(workspace));
        manager.setDispatcher(dispatcher); dispatcher.setScopeChecker(manager::checkScope);
        try {
            workspace.openProject("RenameCollisionFixture");
            String target = String.valueOf(workspace.openTarget(input.toString(),"jeb").get("id")); ready(workspace,target);
            applied(dispatcher,target,"Lfixture/IntentSender;","Delivery","human");
            int before = workspace.historyList(target,0).size();
            collision(renameResult(dispatcher,target,"Lfixture/Intent;","Delivery","llm:class-worker"),"Lfixture/IntentSender;","Delivery");
            check(before==workspace.historyList(target,0).size(), "refused class name creates no successful history");
            check("Intent".equals(workspace.engineFor(target).resolve("Lfixture/Intent;").get("name")), "class loser retains its old name");

            applied(dispatcher,target,"Lfixture/Params;","NamedParameters","human");
            applied(dispatcher,target,"Lfixture/Params;->flagsValues:I","reservedFlags","human");
            Map<String,Object> field = collision(renameResult(dispatcher,target,"Lfixture/Params;->flagsMask:I","reservedFlags","llm:field-worker"),"Lfixture/Params;->flagsValues:I","reservedFlags");
            check("Lfixture/NamedParameters;->reservedFlags:I".equals(field.get("addr")), "field collision reports the renamed owner address");
            String method="Lfixture/Params;->annotOnly(ILjava/lang/String;)I", other="Lfixture/Params;->debugOnly(ILjava/lang/String;)I";
            applied(dispatcher,target,other,"decodeValue","human");
            applied(dispatcher,target,"Lfixture/Params;->absent(I)I","decodeValue","llm:overload-worker");
            Map<String,Object> sameDescriptor = renameResult(dispatcher,target,method,"decodeValue","llm:method-worker");
            collision(sameDescriptor,other,"decodeValue");
            check(((List<?>)map(sameDescriptor.get("errorDetails")).get("conflicts")).size()==1, "legal overload is not blamed as a collision");

            String loop="Lfixture/Branches;->loop(I)I";
            Map<String,Object> params=workspace.engineFor(target).methodParameters(loop,true);
            String argument=String.valueOf(map(((List<?>)params.get("parameters")).get(0)).get("name"));
            List<Object> locals=workspace.engineFor(target).locals(loop);
            Map<String,Object> local=locals.stream().map(RenameCollisionIT::map).filter(row->!argument.equals(row.get("name"))).findFirst().orElseThrow();
            String localName=String.valueOf(local.get("name"));
            check("applied".equals(localResult(dispatcher,target,loop,argument,"limit","llm:local-worker").get("status")), "fixture argument name applies");
            check("applied".equals(localResult(dispatcher,target,loop,localName,"total","llm:local-worker").get("status")), "fixture local name applies");
            Map<String,Object> argumentIdentity=workspace.engineFor(target).resolveLocal(loop,"limit");
            Map<String,Object> localIdentity=workspace.engineFor(target).resolveLocal(loop,"total");
            collision(localResult(dispatcher,target,loop,"total","limit","llm:local-worker"),String.valueOf(argumentIdentity.get("originalSymbol")),"limit");
            collision(localResult(dispatcher,target,loop,"limit","total","llm:local-worker"),String.valueOf(localIdentity.get("originalSymbol")),"total");
            check("total".equals(workspace.engineFor(target).resolveLocal(loop,"total").get("name"))
                    && "limit".equals(workspace.engineFor(target).resolveLocal(loop,"limit").get("name")), "local and argument remain distinct after both refusals");

            // All eight simultaneous callers receive the occupying symbol, without claiming it or the loser.
            before=workspace.historyList(target,0).size();
            var pool=Executors.newFixedThreadPool(8); var start=new CountDownLatch(1);
            try {
                List<java.util.concurrent.Future<Map<String,Object>>> jobs=new ArrayList<>();
                for(int i=0;i<8;i++) { final String actor="llm:collision-"+i;
                    jobs.add(pool.submit(()->{ start.await(); return renameResult(dispatcher,target,method,"decodeValue",actor); })); }
                start.countDown();
                for(var job:jobs)collision(job.get(),other,"decodeValue");
            } finally { pool.shutdown(); }
            check(before==workspace.historyList(target,0).size(),"eight refused writers add no applied history");
            Map<String,Object> batch=annotate(dispatcher,target,List.of(Map.of("symbol","Lfixture/Intent;","name","Delivery"),
                    Map.of("symbol","Lfixture/Branches;","name","BranchAlgorithms")),"llm:batch-worker");
            check(((Number)batch.get("failed")).intValue()==1 && ((Number)batch.get("applied")).intValue()==1,"a collision preserves the other successful batch edit");
            Map<String,Object> invalid=localResult(dispatcher,target,loop,"total","not an identifier","llm:local-worker");
            check("E_SYNTAX".equals(map(invalid.get("errorDetails")).get("code")),"invalid identifier is not blamed on another symbol: "+invalid);
            check(Boolean.TRUE.equals(workspace.saveTarget(target).get("saved")),"native fixture save succeeds");
            workspace.closeTarget(target);
            String reopened=String.valueOf(workspace.openTarget(input.toString(),"jeb").get("id"));ready(workspace,reopened);
            check("annotOnly".equals(workspace.engineFor(reopened).resolve(method).get("name")),"native reopen retains the rejected method's original name");
            check("decodeValue".equals(workspace.engineFor(reopened).resolve(other).get("name")),"native reopen retains the occupying method's name");
            workspace.closeTarget(reopened);

            Path familyInput=output.resolve("NameFamilyCollision.dex"); Files.copy(Path.of(args[2]),familyInput);
            String familyTarget=String.valueOf(workspace.openTarget(familyInput.toString(),"jeb").get("id"));ready(workspace,familyTarget);
            String childName=String.valueOf(workspace.engineFor(familyTarget).resolve("Lcollision/Names$Child;").get("name"));
            collision(renameResult(dispatcher,familyTarget,"Lcollision/Names$Base;",childName,"llm:inner-worker"),"Lcollision/Names$Child;",childName);
            applied(dispatcher,familyTarget,"Lcollision/other/Placeholder;","Names","llm:other-package");
            String peer="Lcollision/Names$Peer;->a(I)I";
            Map<String,Object> familyConflict=collision(renameResult(dispatcher,familyTarget,peer,"occupied","llm:family-worker"),
                    "Lcollision/Names$Child;->occupied(I)I","occupied");
            check("Lcollision/Names$Child;->a(I)I".equals(familyConflict.get("affectedOriginalSymbol")),"family diagnostic identifies the affected sibling method");
            for(String owner:List.of("Base","Child","Peer"))
                check("a".equals(workspace.engineFor(familyTarget).resolve("Lcollision/Names$"+owner+";->a(I)I").get("name")),"refused family rename leaves "+owner+" unchanged");
            workspace.closeTarget(familyTarget);
            System.out.println("RenameCollisionIT: "+checks+" checks passed");
        } catch (Exception | AssertionError failure) {
            failure.printStackTrace(System.out); throw failure;
        } finally { workspace.closeAll(); JebCore.shutdown(); }
    }
}
