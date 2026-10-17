package dev.workbench.it;

import dev.workbench.device.DeviceManager;
import dev.workbench.ops.Ops;
import dev.workbench.registry.*;
import dev.workbench.server.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** Native rows through the same facade used by agents, never a production target. */
public final class SmaliWindowIT {
    private static int checks;
    @SuppressWarnings("unchecked")private static Map<String,Object> map(Object value){return (Map<String,Object>)value;}
    private static synchronized void check(boolean ok,String why){if(!ok)throw new AssertionError(why);checks++;}
    private static Map<String,Object> call(Dispatcher d,String target,String symbol,String window) {
        Map<String,Object> args=new LinkedHashMap<>(Map.of("target",target,"symbol",symbol,"view","smali"));
        if(window!=null)args.put("window",window);
        var result=d.dispatch("tool.call",Map.of("tool","code","args",args),"llm:smali-reader");
        check(Boolean.TRUE.equals(result.get("ok")),"facade native smali succeeds: "+result.get("error"));return map(result.get("data"));
    }
    private static void pages(Dispatcher d,String target,String symbol) {
        var full=call(d,target,symbol,null);List<?> expected=(List<?>)full.get("rows");List<Object> actual=new ArrayList<>();
        check(!expected.isEmpty() && !full.containsKey("window"),"ordinary smali remains whole");
        for(int from=1;from<=expected.size();from+=7) {
            var page=call(d,target,symbol,from+","+(from+6));List<?> rows=(List<?>)page.get("rows");var window=map(page.get("window"));
            check(rows.size()==Math.min(7,expected.size()-from+1),"requested rows only");
            check(Integer.valueOf(expected.size()).equals(page.get("total")) && Integer.valueOf(rows.size()).equals(page.get("count")),"total and returned counts are distinct");
            check(Integer.valueOf(from).equals(window.get("from")),"absolute row offset retained");
            if(Boolean.TRUE.equals(window.get("more")))check(window.containsKey("next"),"continuation returned");
            actual.addAll(rows);
        }
        check(actual.equals(expected),"concatenated pages preserve every instruction, label, byte and directive exactly");
        check(((List<?>)call(d,target,symbol,(expected.size()+1)+","+(expected.size()+10)).get("rows")).isEmpty(),"past-end window is explicitly empty");
    }
    public static void main(String[] args)throws Exception {
        Path root=Path.of(args[1]),home=root.resolve("home");Workspace ws=new Workspace();ws.setWorkbenchRoot(home.toFile());
        var agents=new AgentManager(ws,new EventBus(),home.toFile());ws.setAgents(agents);ws.setDevices(new DeviceManager(home.toFile()));
        var d=new Dispatcher(Ops.registerAll(ws));agents.setDispatcher(d);d.setScopeChecker(agents::checkScope);d.setDecorator(ws::decorateForAgent);
        try {
            ws.openProject("SmaliWindows");String tid=String.valueOf(ws.openTarget(args[0],"jeb").get("id"));
            while(!"ready".equals(ws.engineFor(tid).info().get("state"))) {
                if("error".equals(ws.engineFor(tid).info().get("state")))throw new AssertionError(ws.engineFor(tid).info());Thread.sleep(100);
            }
            String cls="Lfixture/Branches;",method=cls+"->loop(I)I";pages(d,tid,cls);pages(d,tid,method);
            for(String invalid:List.of("0,1","2,1","1","1,2,3","a,3","1,2147483648")) {
                var result=d.dispatch("code.smali",Map.of("target",tid,"symbol",cls,"window",invalid),"human");
                check(!Boolean.TRUE.equals(result.get("ok")) && "E_SYNTAX".equals(map(result.get("error")).get("code")),"invalid window rejected explicitly");
            }
            var wrong=d.dispatch("tool.call",Map.of("tool","code","args",Map.of("target",tid,"symbol",cls,"view","smali","maxInstructions",5)),"llm:smali-reader");
            check(!Boolean.TRUE.equals(wrong.get("ok")) && String.valueOf(wrong.get("error")).contains("window"),"unsupported instruction cap never silently returns the whole class");
            ws.renameSymbol(tid,cls,"RenamedBranches","human:fixture",null,null);
            var first=call(d,tid,cls,"1,1");check(Json.write(first.get("rows")).contains("RenamedBranches"),"paged rows show the fresh native name via original identity");
            var pool=Executors.newFixedThreadPool(8);
            try{List<Callable<Void>> tasks=new ArrayList<>();for(int i=0;i<8;i++)tasks.add(()->{pages(d,tid,method);return null;});
                for(var future:pool.invokeAll(tasks))future.get();}
            finally{pool.shutdown();}
            check(Boolean.TRUE.equals(ws.saveTarget(tid).get("saved")),"native save remains successful");
        }finally{ws.closeAll();}
        System.out.println("Native smali window checks passed: "+checks);
    }
}
