package dev.workbench.it;

import dev.workbench.device.DeviceManager;
import dev.workbench.engine.jeb.JebCore;
import dev.workbench.ops.Ops;
import dev.workbench.registry.*;
import dev.workbench.server.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** Real native/facade regression for retaining names without claiming another rename. */
public final class UnchangedRenameIT {
    private static final String METHOD="Lfixture/Branches;->loop(I)I",OWNER="llm:first-owner";
    private static int checks;
    @SuppressWarnings("unchecked") private static Map<String,Object> map(Object value){return (Map<String,Object>)value;}
    private static synchronized void check(boolean value,String why){if(!value)throw new AssertionError(why);checks++;System.out.println("PASS "+why);}
    private static void ready(Workspace ws,String target)throws Exception {
        for(;;){var info=ws.engineFor(target).info();if("ready".equals(info.get("state")))return;
            if("error".equals(info.get("state")))throw new AssertionError(info);Thread.sleep(100);}
    }
    private static Map<String,Object> annotate(Dispatcher d,String target,List<?> items,String actor) {
        var result=d.dispatch("tool.call",Map.of("tool","annotate","args",Map.of("target",target,"items",items)),actor);
        check(Boolean.TRUE.equals(result.get("ok")),"annotate transport succeeds");return map(result.get("data"));
    }
    private static void counts(Map<String,Object> result,int applied,int unchanged,int pending,int failed) {
        check(Integer.valueOf(applied).equals(result.get("applied"))&&Integer.valueOf(unchanged).equals(result.get("unchanged"))
                &&Integer.valueOf(pending).equals(result.get("pending"))&&Integer.valueOf(failed).equals(result.get("failed")),
                "batch distinguishes actual changes, unchanged names, pending requests and failures: "+result);
    }
    private static void owner(Dispatcher d,String target,String identity) {
        var result=d.dispatch("locals.list",Map.of("target",target,"symbol",METHOD),"llm:reader");
        check(Boolean.TRUE.equals(result.get("ok")),"read decorated variables");
        var row=((List<?>)result.get("data")).stream().map(UnchangedRenameIT::map)
                .filter(x->identity.equals(x.get("originalSymbol"))).findFirst().orElseThrow();
        check("first-owner".equals(row.get("namedBy")),"unchanged request preserves the original argument author: "+row);
    }
    public static void main(String[] args)throws Exception {
        Path output=Path.of(args[1]),input=output.resolve("UnchangedFixture.dex"),home=output.resolve("home");Files.copy(Path.of(args[0]),input);
        Workspace ws=new Workspace();ws.setWorkbenchRoot(home.toFile());var manager=new AgentManager(ws,new EventBus(),home.toFile());
        ws.setAgents(manager);ws.setDevices(new DeviceManager(home.toFile()));manager.reviewPolicy().configure("auto",null,0);
        var d=new Dispatcher(Ops.registerAll(ws));manager.setDispatcher(d);d.setScopeChecker(manager::checkScope);d.setDecorator(ws::decorateForAgent);
        try {
            ws.openProject("UnchangedFixture");String target=String.valueOf(ws.openTarget(input.toString(),"jeb").get("id"));ready(ws,target);
            var variable=ws.engineFor(target).locals(METHOD).stream().map(UnchangedRenameIT::map)
                    .filter(x->Integer.valueOf(0).equals(x.get("parameterIndex"))).findFirst().orElseThrow();
            String identity=String.valueOf(variable.get("originalSymbol"));
            List<Map<String,Object>> symbols=List.of(Map.of("symbol","Lfixture/1cq;","name","MessageKey"),
                    Map.of("symbol","Lfixture/1cq;->b:I","name","rowId"),Map.of("symbol",METHOD,"name","sumBefore"));
            check(Integer.valueOf(3).equals(annotate(d,target,symbols,OWNER).get("applied")),"class, field and method names are first applied");
            check(Integer.valueOf(1).equals(annotate(d,target,List.of(Map.of("symbol",METHOD,"local",variable.get("name"),"name","bound")),OWNER).get("applied")),
                    "argument is first named");
            counts(annotate(d,target,List.of(Map.of("symbol",METHOD,"local","bound","name","bound")),"llm:unsaved-reader"),0,0,0,1);
            check(ws.historyList(target,0).size()==4,"same-name request still respects another agent's unsaved ownership");
            check(Boolean.TRUE.equals(ws.saveTarget(target).get("saved")),"initial assignments save natively");
            String originalHistory=Json.write(ws.historyList(target,0));
            Object indexRevision=ws.engineFor(target).searchIndexStatus().get("revision");
            List<Map<String,Object>> repeated=new ArrayList<>(symbols);repeated.add(Map.of("symbol",METHOD,"local","bound","name","bound"));
            counts(annotate(d,target,repeated,"llm:second-owner"),0,4,0,0);
            check(originalHistory.equals(Json.write(ws.historyList(target,0))),"same-name batch creates no rename history");
            check(ws.writesBy(target,"second-owner")==0,"same-name batch does not inflate mission writes");owner(d,target,identity);
            var engine=ws.engineFor(target);
            check(Boolean.FALSE.equals(engine.rename("Lfixture/1cq;","MessageKey","human").get("applied")),"direct native class rename reports unchanged");
            check(Boolean.FALSE.equals(engine.renameLocal(METHOD,"bound","bound","human").get("applied")),"direct native variable rename reports unchanged");
            check(Boolean.FALSE.equals(ws.targetChanges(target).get("dirty"))&&Integer.valueOf(0).equals(ws.targetChanges(target).get("symbols")),
                    "unchanged renames leave the saved native target clean");
            check(indexRevision.equals(engine.searchIndexStatus().get("revision")),"unchanged names do not invalidate the search cache");
            var constructor=d.dispatch("rename",Map.of("target",target,"symbol","Lfixture/1cq;-><init>()V","name","<init>"),"human");
            check(!Boolean.TRUE.equals(constructor.get("ok"))&&"E_SYNTAX".equals(map(constructor.get("error")).get("code")),"unchanged constructor spelling remains an invalid rename request");
            var stale=d.dispatch("rename",Map.of("target",target,"symbol",METHOD,"local","bound","name","bound","expectedName","oldBound"),"llm:second-owner");
            check(!Boolean.TRUE.equals(stale.get("ok"))&&"RENAME_CONFLICT".equals(map(stale.get("error")).get("code")),"unchanged name does not bypass expectedName");
            counts(annotate(d,target,List.of(Map.of("symbol",METHOD,"local","bound","name","bound","comment","The fixture loop bound is exclusive.")),"llm:second-owner"),1,1,0,0);
            check(ws.historyList(target,0).size()==5&&ws.writesBy(target,"second-owner")==1,"comment applies independently and only it adds a write");owner(d,target,identity);
            manager.reviewPolicy().configure("auto",List.of(),0);
            var pending=d.dispatch("rename",Map.of("target",target,"symbol","Lfixture/1cq;->b:I","name","rowId"),"llm:review-owner");
            check(Boolean.TRUE.equals(pending.get("ok"))&&"pending".equals(map(pending.get("data")).get("status")),"review policy still queues symbol requests");
            var reviewed=manager.review(String.valueOf(map(pending.get("data")).get("id")),"accept",null);
            check("unchanged".equals(reviewed.get("state"))&&!reviewed.containsKey("historyId"),"reviewing an existing name settles without a fictitious edit");
            manager.reviewPolicy().configure("auto",ReviewPolicy.KINDS,0);
            check(ws.historyList(target,0).size()==5&&ws.writesBy(target,"review-owner")==0,"review does not transfer authorship or add a write");
            var pool=Executors.newFixedThreadPool(8);
            try {
                List<Callable<Map<String,Object>>> readers=new ArrayList<>();
                for(int i=0;i<8;i++){String actor="llm:retain-"+i;readers.add(()->annotate(d,target,repeated,actor));}
                for(var result:pool.invokeAll(readers))counts(result.get(),0,4,0,0);
            }finally{pool.shutdown();}
            check(ws.historyList(target,0).size()==5,"eight concurrent repeat batches create no history or ownership conflict");owner(d,target,identity);
            var mixed=annotate(d,target,List.of(Map.of("symbol",METHOD,"local","bound","name","bound"),
                    Map.of("symbol","Lfixture/Params;->flagsMask:I","name","maskBits"),
                    Map.of("symbol","Lfixture/Params;->flagsValues:I","name","maskBits")),"llm:mixed-owner");
            counts(mixed,1,1,0,1);
            check(Boolean.TRUE.equals(ws.saveTarget(target).get("saved")),"mixed outcome saves natively");
            ws.closeTarget(target);String reopened=String.valueOf(ws.openTarget(input.toString(),"jeb").get("id"));ready(ws,reopened);owner(d,reopened,identity);
            check(ws.writesBy(reopened,"second-owner")==1&&ws.writesBy(reopened,"review-owner")==0,"accurate counts survive native reopen");
            var human=d.dispatch("rename",Map.of("target",reopened,"symbol","Lfixture/1cq;","name","HumanMessageKey"),"human");
            check(Boolean.TRUE.equals(human.get("ok")),"analyst assignment applies");
            counts(annotate(d,reopened,List.of(Map.of("symbol","Lfixture/1cq;","name","HumanMessageKey")),"llm:second-owner"),0,0,0,1);
            Path family=output.resolve("UnchangedFamily.dex");Files.copy(Path.of(args[2]),family);
            String ft=String.valueOf(ws.openTarget(family.toString(),"jeb").get("id"));ready(ws,ft);
            String child="Lfixture/RenameFamily$Child;->a(I)I",reader="Lfixture/RenameFamily$Reader;->a(I)I";
            counts(annotate(d,ft,List.of(Map.of("symbol",child,"name","readValue")),OWNER),1,0,0,0);
            check(Boolean.TRUE.equals(ws.saveTarget(ft).get("saved")),"native method family saves");
            counts(annotate(d,ft,List.of(Map.of("symbol",reader,"name","readValue")),"llm:family-reader"),0,1,0,0);
            check(ws.historyList(ft,0).size()==1,"unchanged family does not add a native rename history entry");
            for(var member:ws.engineFor(ft).renameTargets(child))check("readValue".equals(member.get("name")),"all family members retain the supported name");
            System.out.println("UnchangedRenameIT: "+checks+" checks passed");
        }catch(Exception|AssertionError failure){failure.printStackTrace(System.out);throw failure;}
        finally{ws.closeAll();JebCore.shutdown();}
    }
}
