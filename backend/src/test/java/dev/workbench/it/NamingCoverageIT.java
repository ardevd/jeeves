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
            check("8b99377f669a4997f0748acf1825476262bda3967ebaabb64f847cab70663996".equals(abs.get("inventoryFingerprint")),
                    "previously verified no-body fingerprint remains valid when native capability is enriched");
            check(variables(abs).stream().allMatch(r->Boolean.TRUE.equals(r.get("renameableLocal"))),
                    "abstract arguments expose their real native rename targets: "+abs);
            var abstractArgument=variables(abs).get(0);
            var abstractRename=d.dispatch("tool.call",Map.of("tool","annotate","args",Map.of("target",tid,"items",List.of(
                    Map.of("symbol",abs.get("originalMethod"),"local",abstractArgument.get("name"),"name","entryKey",
                            "rationale","Authored abstract fixture key; native argument provenance test.")))),"llm:abstract-owner");
            check(Boolean.TRUE.equals(abstractRename.get("ok")) && "applied".equals(map(map(((List<?>)map(abstractRename.get("data")).get("results")).get(0)).get("rename")).get("status")),
                    "abstract argument accepts a real native annotate rename");
            var abstractCurrent=get(d,tid,String.valueOf(abs.get("originalMethod")),"llm:abstract-owner");
            var abstractRenamed=variables(abstractCurrent).stream().filter(r->"entryKey".equals(r.get("name"))).findFirst().orElseThrow();
            check(variables(abstractCurrent).stream().filter(r->"entryKey".equals(r.get("name"))).count()==1
                            && "arg2".equals(variables(abstractCurrent).get(1).get("name")),
                    "declaration argument rename does not appear on its unchanged neighbor");
            check(abstractRenamed.get("nameProvenance") instanceof Map<?,?>,"abstract argument shows the native rename author");
            var abstractDecisions=variables(abstractCurrent).stream().map(r->item(r,r.containsKey("nameProvenance")?"renamed":"retained")).toList();
            var abstractReceipt=call(d,args(tid,abstractCurrent,abstractDecisions),"llm:abstract-owner");
            check("complete".equals(abstractReceipt.get("status")),
                    "abstract argument rename records as renamed instead of retained: "+abstractReceipt);
            String abstractFingerprint=String.valueOf(abstractCurrent.get("inventoryFingerprint"));
            check(((List<?>)abs.get("inventoryErrors")).isEmpty(),"no-code body is not a collection error");
            var nativeMethod=get(d,tid,"Lfixture/Params;->nativeNamed(J)J","llm:abstract-owner");
            check(Boolean.FALSE.equals(nativeMethod.get("hasCode")) && variables(nativeMethod).size()==1
                            && Boolean.TRUE.equals(variables(nativeMethod).get(0).get("renameableLocal")),
                    "native static declaration exposes its one wide formal argument");
            var nativeRename=d.dispatch("tool.call",Map.of("tool","annotate","args",Map.of("target",tid,"items",List.of(
                    Map.of("symbol",nativeMethod.get("originalMethod"),"local",variables(nativeMethod).get(0).get("name"),"name","nativeHandle")))),"llm:abstract-owner");
            check(Boolean.TRUE.equals(nativeRename.get("ok")) && "applied".equals(map(map(((List<?>)map(nativeRename.get("data")).get("results")).get(0)).get("rename")).get("status")),
                    "native static argument accepts the public native rename action");
            var nativeCurrent=get(d,tid,String.valueOf(nativeMethod.get("originalMethod")),"llm:abstract-owner");
            check("nativeHandle".equals(variables(nativeCurrent).get(0).get("name"))
                            && variables(nativeCurrent).get(0).get("nameProvenance") instanceof Map<?,?>,
                    "native static argument retains its name and exact author");
            String nativeFingerprint=String.valueOf(nativeCurrent.get("inventoryFingerprint"));
            String fingerprint=String.valueOf(current.get("inventoryFingerprint"));
            var pool=Executors.newFixedThreadPool(8);String target=tid;Dispatcher active=d;
            try {
                List<Callable<Boolean>> tasks=new ArrayList<>();
                for(int i=0;i<8;i++){String actor="llm:parallel-"+i;tasks.add(()->{
                    var view=get(active,target,METHOD,actor);var all=variables(view).stream().map(r->item(r,"retained")).toList();
                    var declaration=get(active,target,String.valueOf(abs.get("originalMethod")),actor);
                    var declarationItems=variables(declaration).stream().map(r->item(r,"retained")).toList();
                    return "complete".equals(call(active,args(target,view,all),actor).get("status"))
                            && "complete".equals(call(active,args(target,declaration,declarationItems),actor).get("status"));});}
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
                check(abstractFingerprint.equals(NamingCoverage.inventory(independent,String.valueOf(abs.get("originalMethod"))).get("inventoryFingerprint")),
                        "abstract argument inventory persists natively without history or coverage sidecars");
                check(nativeFingerprint.equals(NamingCoverage.inventory(independent,String.valueOf(nativeMethod.get("originalMethod"))).get("inventoryFingerprint")),
                        "native declaration argument persists without history or coverage sidecars");
            }finally{independent.discardUnsaved();independent.close();}
            ws=new Workspace();d=dispatcher(ws,home);ws.openProject("CoverageFixture");
            tid=String.valueOf(ws.openTarget(input.toString(),"jeb").get("id"));ready(ws.engineFor(tid));
            check("complete".equals(get(d,tid,METHOD,ACTOR).get("status")),"coverage survives a fresh Workspace and native reopen");
            var abstractReopened=get(d,tid,String.valueOf(abs.get("originalMethod")),"llm:abstract-owner");
            check("complete".equals(abstractReopened.get("status")) && variables(abstractReopened).stream().anyMatch(r->"entryKey".equals(r.get("name")) && r.get("nameProvenance") instanceof Map<?,?>),
                    "abstract argument rename receipt and authorship survive native reopen");
            Path receipt;
            try(var paths=Files.walk(output)){receipt=paths.filter(p->p.getParent().getFileName().toString().equals("naming-coverage")&&p.toString().endsWith(".json"))
                    .filter(p->{try{return METHOD.equals(Json.parseObject(Files.readString(p)).get("originalMethod"));}catch(Exception e){return false;}}).findFirst().orElseThrow();}
            String saved=Files.readString(receipt);Files.writeString(receipt,"{broken");
            var corrupted=d.dispatch("naming.coverage",Map.of("target",tid,"symbol",METHOD),ACTOR);
            check(!Boolean.TRUE.equals(corrupted.get("ok"))&&"{broken".equals(Files.readString(receipt)),"corrupt coverage fails loudly and preserves evidence");
            Files.writeString(receipt,saved);
            check(ws.writesBy(tid,"coverage-owner")==4,"agent write count includes direct class/method and argument/local renames after reopen");
            check(ws.writesBy(tid,"coverage")==0,"agent attribution uses exact identity, not an agent-ID prefix");
            var recorded=ws.historyList(tid,100).stream().map(NamingCoverageIT::map).filter(r->"local".equals(r.get("kind")) && ACTOR.equals(r.get("actor"))).toList();
            check(recorded.size()==2 && recorded.stream().allMatch(r->Set.of("loop bound","accumulation").contains(r.get("rationale"))),
                    "local/argument rationale persists in durable history across reopen");
            ws.renameSymbol(tid,"Lfixture/Params;","OtherAgentFixture","agent:coverage-owner-more auto-applied",null,null);
            var reverted=ws.revertBy(tid,"coverage-owner");
            check(((Number)reverted.get("reverted")).intValue()==4 && ((List<?>)reverted.get("failed")).isEmpty(),
                    "agent revert includes direct local/argument writes and applies newest first");
            check(ws.writesBy(tid,"coverage-owner")==0 && ws.writesBy(tid,"coverage-owner-more")==1,
                    "revert clears this agent's writes and preserves the other agent");
            check("loop".equals(ws.engineFor(tid).resolve(METHOD).get("name")),"mission revert restores the method's native name");
            check(initial.get("inventoryFingerprint").equals(get(d,tid,METHOD,ACTOR).get("inventoryFingerprint")),
                    "mission revert restores exact original argument/local names");
            check(Boolean.TRUE.equals(ws.saveTarget(tid).get("saved")),"reverted names save natively");
            System.out.println("NamingCoverageIT: "+checks+" checks passed");
        }catch(Exception|AssertionError error){error.printStackTrace(System.out);throw error;}
        finally{ws.closeAll();JebCore.shutdown();}
    }
}
