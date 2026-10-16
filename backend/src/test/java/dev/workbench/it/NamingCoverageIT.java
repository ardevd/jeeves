package dev.workbench.it;

import dev.workbench.device.DeviceManager;
import dev.workbench.engine.jeb.JebCore;
import dev.workbench.engine.jeb.JebEngine;
import dev.workbench.ops.Ops;
import dev.workbench.registry.*;
import dev.workbench.server.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** Real facade and native JDB, no model or shared server. Tests accounting, not name semantics. */
public final class NamingCoverageIT {
    private static int checks;
    private static final String METHOD="Lfixture/Branches;->loop(I)I", ACTOR="llm:coverage-owner";
    @SuppressWarnings("unchecked") private static Map<String,Object> map(Object value){return (Map<String,Object>)value;}
    private static synchronized void check(boolean ok,String why){if(!ok)throw new AssertionError(why);checks++;System.out.println("PASS "+why);}
    private static void ready(dev.workbench.engine.Engine engine)throws Exception {
        for(;;){var info=engine.info();if("ready".equals(info.get("state")))return;
            if("error".equals(info.get("state")))throw new AssertionError(info);Thread.sleep(100);}
    }
    private static Dispatcher dispatcher(Workspace ws,Path home) throws OpException {
        ws.setWorkbenchRoot(home.toFile());var agents=new AgentManager(ws,new EventBus(),home.toFile());
        ws.setAgents(agents);ws.setDevices(new DeviceManager(home.toFile()));agents.reviewPolicy().configure("auto",null,0);
        var d=new Dispatcher(Ops.registerAll(ws));agents.setDispatcher(d);d.setScopeChecker(agents::checkScope);d.setDecorator(ws::decorateForAgent);return d;
    }
    private static Map<String,Object> call(Dispatcher d,Map<String,Object> args,String actor) {
        var result=d.dispatch("tool.call",Map.of("tool","naming_coverage","args",args),actor);
        check(Boolean.TRUE.equals(result.get("ok")),"coverage facade succeeds: "+result.get("error"));return map(result.get("data"));
    }
    private static Map<String,Object> get(Dispatcher d,String tid,String method,String actor) {
        return call(d,Map.of("target",tid,"symbol",method,"recordedBy",actor),actor);
    }
    private static List<Map<String,Object>> variables(Map<String,Object> row){return ((List<?>)row.get("variables")).stream().map(NamingCoverageIT::map).toList();}
    private static Map<String,Object> item(Map<String,Object> row,String disposition) {
        return Map.of("identity",row.get("identity"),"disposition",disposition,"evidence","Fixture loop uses this value in its bound or accumulation; accounting test, not semantic qualification.");
    }
    private static Map<String,Object> args(String target,Map<String,Object> read,List<?> items) {
        return Map.of("target",target,"symbol",read.get("originalMethod"),"action","record",
                "inventoryFingerprint",read.get("inventoryFingerprint"),"evidence","Inspected the authored fixture method.","items",items);
    }
    public static void main(String[] argv)throws Exception {
        Path output=Path.of(argv[1]),input=output.resolve("CoverageFixture.dex"),home=output.resolve("home");Files.copy(Path.of(argv[0]),input);
        Workspace ws=new Workspace();Dispatcher d=dispatcher(ws,home);
        try {
            ws.openProject("CoverageFixture");String tid=String.valueOf(ws.openTarget(input.toString(),"jeb").get("id"));ready(ws.engineFor(tid));
            ws.renameSymbol(tid,"Lfixture/Branches;","AlreadyNamedClass",ACTOR,null,null);
            ws.renameSymbol(tid,METHOD,"alreadyNamedMethod",ACTOR,null,null);
            var initial=get(d,tid,METHOD,ACTOR);
            check("unreviewed".equals(initial.get("status")),"named class/method do not imply variable coverage");
            check(((List<?>)initial.get("inventoryErrors")).isEmpty(),"complete native argument/local inventory: "+initial.get("inventoryErrors"));
            var rows=variables(initial);check(rows.size()>1,"fixture has an argument and body locals");
            var first=rows.get(0);var partial=call(d,args(tid,initial,List.of(item(first,"retained"))),ACTOR);
            check("needs_review".equals(partial.get("status")),"partial receipt cannot complete method");
            var bogus=d.dispatch("tool.call",Map.of("tool","naming_coverage","args",args(tid,initial,List.of(item(first,"renamed")))),ACTOR);
            check(!Boolean.TRUE.equals(bogus.get("ok")),"renamed requires real provenance");
            var argument=rows.stream().filter(r->"argument".equals(r.get("kind"))).findFirst().orElseThrow();
            var local=rows.stream().filter(r->"local".equals(r.get("kind"))).findFirst().orElseThrow();
            var batch=d.dispatch("tool.call",Map.of("tool","annotate","args",Map.of("target",tid,"items",List.of(
                    Map.of("symbol",METHOD,"local",argument.get("name"),"name","limit","rationale","loop bound"),
                    Map.of("symbol",METHOD,"local",local.get("name"),"name","total","rationale","accumulation")))),ACTOR);
            check(Boolean.TRUE.equals(batch.get("ok")),"one facade batch names argument and local");
            for(Object v:(List<?>)map(batch.get("data")).get("results"))check("applied".equals(map(map(v).get("rename")).get("status")),"batch item actually applied");
            check("stale".equals(get(d,tid,METHOD,ACTOR).get("status")),"a rename invalidates the prior coverage fingerprint");
            var stale=d.dispatch("tool.call",Map.of("tool","naming_coverage","args",args(tid,initial,List.of(item(first,"retained")))),ACTOR);
            check(!Boolean.TRUE.equals(stale.get("ok")) && "E_BUSY".equals(map(stale.get("error")).get("code")),"stale assessment cannot overwrite a fresh inventory");
            var current=get(d,tid,METHOD,ACTOR);
            List<Object> decisions=new ArrayList<>();for(var row:variables(current))decisions.add(item(row,row.containsKey("nameProvenance")?"renamed":"retained"));
            var complete=call(d,args(tid,current,decisions),ACTOR);
            check("complete".equals(complete.get("status")),"all current variables with evidence complete accounting");
            check("unreviewed".equals(get(d,tid,METHOD,"llm:different-agent").get("status")),"another agent cannot inherit this receipt as their own work");
            var one=variables(current).get(0);
            check("needs_review".equals(call(d,args(tid,current,List.of(item(one,"unresolved"))),ACTOR).get("status")),"unresolved explicitly prevents completion");
            check("complete".equals(call(d,args(tid,current,decisions),ACTOR).get("status")),"reassessment repairs unresolved receipt");
            var duplicate=d.dispatch("tool.call",Map.of("tool","naming_coverage","args",args(tid,current,List.of(item(one,"retained"),item(one,"retained")))),ACTOR);
            check(!Boolean.TRUE.equals(duplicate.get("ok")),"duplicate variable assessments are rejected without changing receipt");
            String empty="Lfixture/Params;->zeroArgs()V";var zero=get(d,tid,empty,ACTOR);
            check(variables(zero).isEmpty() && "unreviewed".equals(zero.get("status")),"empty method still requires an explicit review");
            check("complete".equals(call(d,args(tid,zero,List.of()),ACTOR).get("status")),"explicit empty receipt completes variable-free method");
            var abs=get(d,tid,"Lfixture/Params;->abstractNamed(Ljava/lang/String;I)V",ACTOR);
            check(variables(abs).size()==2 && Boolean.FALSE.equals(abs.get("hasCode")),"abstract formal arguments are included without inventing a body");
            check(variables(abs).stream().noneMatch(r->Boolean.TRUE.equals(r.get("renameableLocal"))),"abstract arguments disclose unavailable local rename targets");
            check(((List<?>)abs.get("inventoryErrors")).isEmpty(),"no-code body is not a collection error");
            String fingerprint=String.valueOf(current.get("inventoryFingerprint"));
            var pool=Executors.newFixedThreadPool(8);String target=tid;Dispatcher active=d;
            try {
                List<Callable<Boolean>> tasks=new ArrayList<>();
                for(int i=0;i<8;i++){String actor="llm:parallel-"+i;tasks.add(()->{
                    var view=get(active,target,METHOD,actor);var all=variables(view).stream().map(r->item(r,"retained")).toList();
                    return "complete".equals(call(active,args(target,view,all),actor).get("status"));});}
                for(var future:pool.invokeAll(tasks))check(future.get(),"parallel actor's durable record acknowledged");
            }finally{pool.shutdown();}
            for(int i=0;i<8;i++)check("complete".equals(get(d,tid,METHOD,"llm:parallel-"+i).get("status")),"parallel records do not overwrite other actors");
            check(Boolean.TRUE.equals(ws.saveTarget(tid).get("saved")),"native save succeeds");
            Path database=Path.of(String.valueOf(ws.engineFor(tid).info().get("jdb2")));ws.closeAll();
            Path copy=output.resolve("independent");Files.createDirectory(copy);
            Path copied=copy.resolve(database.getFileName());Files.copy(database,copied);
            Files.copy(Path.of(database+".key"),Path.of(copied+".key"));
            var independent=new JebEngine("coverage-independent", "native",copied.toString(),null,copied.toFile());
            try{independent.openAsync();ready(independent);
                check(!independent.info().containsKey("recoveredEdits"),"independent copy has no history replay");
                check(fingerprint.equals(NamingCoverage.inventory(independent,METHOD).get("inventoryFingerprint")),"entire variable inventory persists natively without coverage/history sidecars");
            }finally{independent.discardUnsaved();independent.close();}
            ws=new Workspace();d=dispatcher(ws,home);ws.openProject("CoverageFixture");
            tid=String.valueOf(ws.openTarget(input.toString(),"jeb").get("id"));ready(ws.engineFor(tid));
            check("complete".equals(get(d,tid,METHOD,ACTOR).get("status")),"coverage survives a fresh Workspace and native reopen");
            Path receipt;
            try(var paths=Files.walk(output)){receipt=paths.filter(p->p.getParent().getFileName().toString().equals("naming-coverage")&&p.toString().endsWith(".json"))
                    .filter(p->{try{return METHOD.equals(Json.parseObject(Files.readString(p)).get("originalMethod"));}catch(Exception e){return false;}}).findFirst().orElseThrow();}
            String saved=Files.readString(receipt);Files.writeString(receipt,"{broken");
            var corrupted=d.dispatch("naming.coverage",Map.of("target",tid,"symbol",METHOD),ACTOR);
            check(!Boolean.TRUE.equals(corrupted.get("ok"))&&"{broken".equals(Files.readString(receipt)),"corrupt coverage fails loudly and preserves evidence");
            Files.writeString(receipt,saved);
            System.out.println("NamingCoverageIT: "+checks+" checks passed");
        }catch(Exception|AssertionError error){error.printStackTrace(System.out);throw error;}
        finally{ws.closeAll();JebCore.shutdown();}
    }
}
