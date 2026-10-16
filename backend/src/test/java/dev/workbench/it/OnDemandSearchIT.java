package dev.workbench.it;

import dev.workbench.device.DeviceManager;
import dev.workbench.engine.jeb.JebCore;
import dev.workbench.ops.Ops;
import dev.workbench.registry.Dispatcher;
import dev.workbench.registry.Json;
import dev.workbench.server.AgentManager;
import dev.workbench.server.EventBus;
import dev.workbench.server.Workspace;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;

/** Real JEB: edit-only corpus, propagated method rename, legacy repair and eight readers. */
public final class OnDemandSearchIT {
    private static int checks;
    private static final String CHILD="Lfixture/RenameFamily$Child;->a(I)I";
    private static void check(boolean value,String message) {
        if(!value) throw new AssertionError(message);
        checks++; System.out.println("PASS "+message);
    }
    @SuppressWarnings("unchecked") private static Map<String,Object> map(Object value){return (Map<String,Object>)value;}
    private static Object call(Dispatcher d,String op,Map<String,Object> params) {
        var result=d.dispatch(op,params,"human:cache-fixture");
        if(!Boolean.TRUE.equals(result.get("ok"))) throw new AssertionError(result.toString());
        return result.get("data");
    }
    private static void ready(Workspace ws,String target) throws Exception {
        for(;;) {
            var info=ws.engineFor(target).info();
            if("ready".equals(info.get("state"))) return;
            if("error".equals(info.get("state"))) throw new AssertionError(info.toString());
            Thread.sleep(50);
        }
    }
    private static void drained(Workspace ws,String target) throws Exception {
        long until=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(60);
        while(((Number)ws.engineFor(target).searchIndexStatus().get("pendingReindex")).intValue()>0) {
            if(System.nanoTime()>until) throw new AssertionError(ws.engineFor(target).searchIndexStatus().toString());
            Thread.sleep(50);
        }
    }
    private static Map<String,Object> search(Dispatcher d,String target,String query) {
        return map(call(d,"tool.call",Map.of("tool","search","args",Map.of("target",target,"query",query,"kind","code"))));
    }
    private static void found(Dispatcher d,String target,String name) {
        var result=search(d,target,name);
        var groups=(List<?>)result.get("groups");
        check(!groups.isEmpty(),"agent search sees current saved name "+name);
        check(Json.write(groups).contains("RenameFamily"),"search carries real fixture class text");
        check(Boolean.TRUE.equals(map(result.get("coverage")).get("partialCoverage")),"agent receives partial coverage inside data");
    }
    public static void main(String[] args) throws Exception {
        check("on-demand".equals(System.getenv("WORKBENCH_INDEX_MODE")),"fixture uses on-demand mode");
        Path output=Path.of(args[1]).toAbsolutePath(), input=output.resolve("SearchFixture.dex");
        Files.createDirectories(output); Files.copy(Path.of(args[0]),input);
        Workspace ws=new Workspace(); ws.setWorkbenchRoot(output.resolve("home").toFile());
        AgentManager manager=new AgentManager(ws,new EventBus(),output.resolve("home").toFile());
        ws.setAgents(manager); ws.setDevices(new DeviceManager(output.resolve("home").toFile()));
        manager.reviewPolicy().configure("auto",null,0);
        Dispatcher d=new Dispatcher(Ops.registerAll(ws)); manager.setDispatcher(d); d.setScopeChecker(manager::checkScope);
        try {
            ws.openProject("SearchFixture");
            String target=String.valueOf(ws.openTarget(input.toString(),"jeb").get("id")); ready(ws,target);
            call(d,"rename",Map.of("target",target,"symbol",CHILD,"name","readFirstRound")); drained(ws,target);
            found(d,target,"readFirstRound");
            var groups=(List<?>)search(d,target,"readFirstRound").get("groups");
            check(groups.size()>=5,"propagated rename refreshes four family owners and the caller");
            check(Boolean.TRUE.equals(ws.saveTarget(target).get("saved")),"native changes save before closing");
            ws.closeTarget(target);
            target=String.valueOf(ws.openTarget(input.toString(),"jeb").get("id")); ready(ws,target); drained(ws,target);
            check(Boolean.TRUE.equals(ws.engineFor(target).searchIndexStatus().get("adoptedFromDisk")),"second session adopts edit-only corpus");
            found(d,target,"readFirstRound");
            call(d,"rename",Map.of("target",target,"symbol",CHILD,"name","readNextRound"));
            // The very next query must see accepted writes, with no drain/poll gate.
            found(d,target,"readNextRound");
            check(((List<?>)search(d,target,"readFirstRound").get("groups")).isEmpty(),"old name is absent after replacement");
            call(d,"rename",Map.of("target",target,"symbol","Lfixture/RenameFamily$Unrelated;","name","RenamedClassCacheFixture")); drained(ws,target);
            check(!((List<?>)search(d,target,"RenamedClassCacheFixture").get("groups")).isEmpty(),"class rename enters the on-demand corpus with its current name");
            check(Boolean.TRUE.equals(ws.saveTarget(target).get("saved")),"second session saves");
            ws.closeTarget(target);
            Path header;
            try(var files=Files.walk(output.resolve("home/targets"))) {
                header=files.filter(p->p.endsWith("search-index/header.json")).findFirst().orElseThrow();
            }
            var legacy=map(Json.parse(Files.readString(header))); legacy.put("sourceIdentity",Map.of());
            Files.writeString(header,Json.write(legacy));
            target=String.valueOf(ws.openTarget(input.toString(),"jeb").get("id")); ready(ws,target); drained(ws,target);
            check(!Boolean.TRUE.equals(ws.engineFor(target).searchIndexStatus().get("adoptedFromDisk")),"legacy text is regenerated instead of adopted");
            found(d,target,"readNextRound");
            check(!((List<?>)search(d,target,"RenamedClassCacheFixture").get("groups")).isEmpty(),"legacy recovery finds class-renamed owners by original history identity");
            var pool=Executors.newFixedThreadPool(8); final String active=target;
            try {
                var tasks=new java.util.ArrayList<java.util.concurrent.Callable<Boolean>>();
                for(int i=0;i<8;i++) tasks.add(()->{
                    var result=search(d,active,"readNextRound");
                    return !((List<?>)result.get("groups")).isEmpty() && Boolean.TRUE.equals(map(result.get("coverage")).get("partialCoverage"));
                });
                for(var result:pool.invokeAll(tasks)) check(result.get(),"concurrent agent receives current text and coverage");
            } finally {pool.shutdownNow();}
            var raw=d.dispatch("search",Map.of("target",target,"query","neverPresentFixture","kind","code"),"human");
            check(raw.get("data") instanceof List<?>,"raw API retains its list contract");
            check(Boolean.TRUE.equals(map(map(raw.get("meta")).get("searchCoverage")).get("partialCoverage")),"raw empty search retains its coverage warning");
            check(((List<?>)search(d,target,"neverPresentFixture").get("groups")).isEmpty(),"agent empty search is wrapped with coverage");
            System.out.println("Native on-demand search checks passed: "+checks);
        } catch(Exception|AssertionError failure) {failure.printStackTrace(System.out); throw failure;}
        finally {ws.closeAll(); JebCore.shutdown();}
    }
}
