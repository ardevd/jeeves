package dev.workbench.it;

import com.pnfsoftware.jeb.core.RuntimeProjectUtil;
import com.pnfsoftware.jeb.core.units.code.android.IDexUnit;
import com.pnfsoftware.jeb.core.units.code.android.IDexDecompilerUnit;
import com.pnfsoftware.jeb.core.units.code.java.*;
import com.pnfsoftware.jeb.core.util.DecompilerHelper;
import com.pnfsoftware.jeb.core.actions.*;
import dev.workbench.device.DeviceManager;
import dev.workbench.engine.jeb.JebCore;
import dev.workbench.ops.Ops;
import dev.workbench.registry.Dispatcher;
import dev.workbench.server.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.Executors;

/** Agent-facing local/argument reads and writes on a collapsed native method. */
public final class LocalNameProvenanceIT {
    private static int checks;
    private static final String METHOD="Lfixture/Branches;->loop(I)I";
    private static synchronized void check(boolean ok,String message) {
        if(!ok)throw new AssertionError(message);checks++;System.out.println("PASS "+message);
    }
    @SuppressWarnings("unchecked") private static Map<String,Object> map(Object value){return (Map<String,Object>)value;}
    private static Object call(Dispatcher d,String op,Map<String,Object> params,String actor) {
        var result=d.dispatch(op,params,actor);
        check(Boolean.TRUE.equals(result.get("ok")),op+" succeeds: "+(result.get("error")));
        return result.get("data");
    }
    private static void ready(Workspace ws,String target)throws Exception {
        for(;;){var info=ws.engineFor(target).info();if("ready".equals(info.get("state")))return;
            if("error".equals(info.get("state")))throw new AssertionError(info);Thread.sleep(100);}
    }
    private static Map<String,Object> rename(Dispatcher d,String target,String method,String local,String name) {
        var data=map(call(d,"tool.call",Map.of("tool","annotate","args",Map.of("target",target,"items",List.of(
                Map.of("symbol",method,"local",local,"name",name,"confidence",0.9,"rationale","Native fixture evidence")))),"llm:owner"));
        var result=map(map(((List<?>)data.get("results")).get(0)).get("rename"));
        check("applied".equals(result.get("status")),"agent rename applies: "+name+" "+result);return result;
    }
    private static List<Map<String,Object>> names(Object data,String key) {
        return ((List<?>)map(data).getOrDefault(key,List.of())).stream().map(LocalNameProvenanceIT::map).toList();
    }
    private static Map<String,Object> lookup(Dispatcher d,String target,String symbol) {
        return map(call(d,"tool.call",Map.of("tool","symbols","args",Map.of("target",target,"symbol",symbol)),"llm:reader"));
    }
    private static void verifySymbols(Dispatcher d,String target) {
        for(var entry:Map.of("Lfixture/Branches;","class-owner",METHOD,"owner",
                "Lfixture/Params;->flagsMask:I","field-owner","Lfixture/Params;->flagsValues:I","human").entrySet()) {
            var row=lookup(d,target,entry.getKey());
            check(entry.getValue().equals(row.get("namedBy")) && entry.getKey().equals(map(row.get("nameProvenance")).get("originalSymbol")),"native symbols facade identifies the current author of "+entry.getKey());
            var authors=names(row,"human".equals(entry.getValue())?"humanNames":"agentNames");
            check(authors.size()==1 && entry.getKey().equals(authors.get(0).get("originalSymbol")),"lookup labels exactly its symbol, without inheriting its owner or sibling");
        }
    }
    private static void verify(Dispatcher d,String target,String identity,String originalName,String actor) {
        String wanted="human".equals(actor)?"humanLimit":"limit";
        Map<String,Object> params=Map.of("target",target,"symbol",METHOD);
        var rows=(List<?>)call(d,"locals.list",params,"llm:reader");
        check(rows.stream().map(LocalNameProvenanceIT::map).filter(row->!List.of(wanted,"total").contains(row.get("name"))).noneMatch(row->row.containsKey("namedBy")),"untouched locals do not inherit their method's author");
        var arg=rows.stream().map(LocalNameProvenanceIT::map).filter(row->identity.equals(row.get("originalSymbol"))).findFirst().orElseThrow();
        check(wanted.equals(arg.get("name")) && originalName.equals(arg.get("was")) && actor.equals(arg.get("namedBy")),"local row carries current/original argument names and correct author");
        var meta=map(call(d,"code.params",params,"llm:reader"));
        var parameter=map(((List<?>)meta.get("parameters")).get(0));
        check(wanted.equals(parameter.get("name")) && actor.equals(parameter.get("namedBy")),"code.params agrees with local argument attribution");
        var code=call(d,"tool.call",Map.of("tool","code","args",Map.of("target",target,"symbol",METHOD)),"llm:reader");
        // The facade returns the decorated read's data directly.
        var hypotheses=names(code,"human".equals(actor)?"humanNames":"agentNames");
        check(hypotheses.stream().anyMatch(row->identity.equals(row.get("originalSymbol")) && wanted.equals(row.get("name"))),"the agent code tool carries argument attribution");
        check(String.valueOf(map(code).get("text")).contains(wanted),"agent source uses the fresh argument name");
    }
    public static void main(String[] args)throws Exception {
        Path output=Path.of(args[1]);Path input=output.resolve("LocalNameFixture.dex");Files.copy(Path.of(args[0]),input);
        Workspace ws=new Workspace();ws.setWorkbenchRoot(output.resolve("home").toFile());
        AgentManager manager=new AgentManager(ws,new EventBus(),output.resolve("home").toFile());
        ws.setAgents(manager);ws.setDevices(new DeviceManager(output.resolve("home").toFile()));manager.reviewPolicy().configure("auto",null,0);
        Dispatcher d=new Dispatcher(Ops.registerAll(ws));manager.setDispatcher(d);d.setScopeChecker(manager::checkScope);d.setDecorator(ws::decorateForAgent);
        try {
            ws.openProject("LocalNameFixture");String target=String.valueOf(ws.openTarget(input.toString(),"jeb").get("id"));ready(ws,target);
            IJavaSourceUnit source=null;
            for(var project:JebCore.engines().getProjects())for(var dex:RuntimeProjectUtil.findUnits(project,IDexUnit.class)) {
                var cls=dex.getClass("Lfixture/Branches;");if(cls==null||cls.getMethods().isEmpty())continue;
                source=(IJavaSourceUnit)((IDexDecompilerUnit)DecompilerHelper.getDecompiler(dex)).decompileToUnit(cls.getAddress());
            }
            check(source!=null,"public SDK locates the authored method");
            var pm=source.getPropertyManager();pm.setBoolean(JavaDocument.propnameDisregardCollapse,false);
            var collapseContext=new ActionContext(source,Actions.COLLAPSE,0,METHOD);
            var collapse=new ActionCollapseData(true);collapse.setRecords(Map.of(METHOD,true));
            check(source.executeAction(collapseContext,collapse),"public SDK collapses the fixture method");
            JavaDocument document=new JavaDocument(source);
            try{String rendered=document.format();check(rendered.contains("[...]"),"fixture actually has a collapsed method before the read: "+rendered);}finally{document.dispose();}
            var locals=ws.engineFor(target).locals(METHOD);
            var argument=locals.stream().map(LocalNameProvenanceIT::map).filter(row->Integer.valueOf(0).equals(row.get("parameterIndex"))).findFirst().orElseThrow();
            var body=locals.stream().map(LocalNameProvenanceIT::map).filter(row->!row.containsKey("parameterIndex")&&!"this".equals(row.get("name"))).findFirst().orElseThrow();
            check(!pm.getBoolean(JavaDocument.propnameDisregardCollapse,true),"headless inventory restores the saved collapse display setting");
            String originalName=String.valueOf(argument.get("originalName")),identity=String.valueOf(argument.get("originalSymbol"));
            rename(d,target,METHOD,String.valueOf(argument.get("name")),"limit");
            rename(d,target,METHOD,String.valueOf(body.get("name")),"total");
            var history=ws.historyList(target,0);String argumentHistory=history.stream().map(LocalNameProvenanceIT::map).filter(row->identity.equals(row.get("originalSymbol"))).map(row->String.valueOf(row.get("id"))).findFirst().orElseThrow();
            verify(d,target,identity,originalName,"owner");
            ws.renameSymbol(target,METHOD,"accumulate","llm:owner",null,null);ws.renameSymbol(target,"Lfixture/Branches;","Accumulation","llm:class-owner",null,null);
            ws.renameSymbol(target,"Lfixture/Params;","NamedParams","llm:class-owner",null,null);
            check(!lookup(d,target,"Lfixture/Params;->flagsValues:I").containsKey("namedBy"),"an untouched native field does not inherit its renamed class's author");
            ws.renameSymbol(target,"Lfixture/Params;->flagsMask:I","maskBits","llm:field-owner",null,null);
            ws.renameSymbol(target,"Lfixture/Params;->flagsValues:I","humanFlags","human",null,null);
            verifySymbols(d,target);
            verify(d,target,identity,originalName,"owner");
            ws.historyUndo(target,argumentHistory);
            var undone=map(call(d,"code.decompile",Map.of("target",target,"symbol",METHOD),"llm:reader"));
            check(names(undone,"agentNames").stream().noneMatch(row->identity.equals(row.get("originalSymbol"))),"undo removes only that local identity's attribution");
            ws.historyRedo(target,argumentHistory);verify(d,target,identity,originalName,"owner");
            check(Boolean.TRUE.equals(ws.saveTarget(target).get("saved")),"local and argument changes saved natively");
            ws.closeTarget(target);target=String.valueOf(ws.openTarget(input.toString(),"jeb").get("id"));ready(ws,target);
            verify(d,target,identity,originalName,"owner");
            verifySymbols(d,target);
            String reopened=target;var pool=Executors.newFixedThreadPool(8);
            try{List<java.util.concurrent.Callable<Boolean>> readers=new ArrayList<>();
                for(int i=0;i<8;i++)readers.add(()->{verify(d,reopened,identity,originalName,"owner");verifySymbols(d,reopened);return true;});
                for(var result:pool.invokeAll(readers))check(result.get(),"parallel native read completed");
            }finally{pool.shutdown();}
            ws.renameParameters(target,METHOD,List.of(new LinkedHashMap<>(Map.of("index",0,"name","humanLimit"))),"analyst","human");
            verify(d,target,identity,originalName,"human");
            String overloadA="Lfixture/Params;->annotOnly(ILjava/lang/String;)I",overloadB="Lfixture/Params;->agree(JI)J";
            ws.renameSymbol(target,overloadA,"processValue","llm:owner",null,null);
            ws.renameSymbol(target,overloadB,"processValue","llm:owner",null,null);
            var a=ws.engineFor(target).locals(overloadA).stream().map(LocalNameProvenanceIT::map).filter(row->Integer.valueOf(0).equals(row.get("parameterIndex"))).findFirst().orElseThrow();
            var b=ws.engineFor(target).locals(overloadB).stream().map(LocalNameProvenanceIT::map).filter(row->Integer.valueOf(0).equals(row.get("parameterIndex"))).findFirst().orElseThrow();
            check("I".equals(a.get("type"))&&"J".equals(b.get("type")),"same-name same-arity overloads inventory their own AST arguments");
            rename(d,target,overloadA,String.valueOf(a.get("name")),"firstCount");
            rename(d,target,overloadB,String.valueOf(b.get("name")),"timeTicks");
            var overloadRead=call(d,"code.decompile",Map.of("target",target,"symbol",overloadA),"llm:reader");
            check(names(overloadRead,"agentNames").stream().noneMatch(row->overloadB.equals(row.get("originalMethod"))),"native method reads do not attribute a sibling overload's locals");
            System.out.println("LocalNameProvenanceIT: "+checks+" checks passed");
        } catch(Exception | AssertionError failure) {failure.printStackTrace(System.out);throw failure;}
        finally {ws.closeAll();JebCore.shutdown();}
    }
}
