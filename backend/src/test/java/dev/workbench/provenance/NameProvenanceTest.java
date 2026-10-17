package dev.workbench.provenance;

import dev.workbench.engine.Engine;
import dev.workbench.registry.Dispatcher;
import dev.workbench.registry.OpException;
import dev.workbench.server.Workspace;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Current-name provenance, using only the application's own Engine seam; no SDK or database. */
public final class NameProvenanceTest {
    private static int checks;
    private static final String METHOD = "Lfixture/Child;->a(I)V";
    private static final String PARENT = "Lfixture/Base;->a(I)V";
    private static void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
        checks++; System.out.println("PASS " + message);
    }
    @SuppressWarnings("unchecked") private static Map<String,Object> map(Object value) { return (Map<String,Object>)value; }
    private static Map<String,Object> rename(String id, String original, String from, String to, String name, String actor, int second) {
        Map<String,Object> row = new LinkedHashMap<>();
        row.put("id", id); row.put("op", "rename"); row.put("kind", "method"); row.put("actor", actor);
        row.put("addr", from); row.put("newAddr", to); row.put("oldName", "a"); row.put("newName", name);
        if (original != null) row.put("originalSymbol", original);
        row.put("ts", "2026-01-01T00:00:" + String.format("%02d", second) + "Z"); row.put("undone", false);
        return row;
    }
    private static final class Fixture {
        final Workspace ws = new Workspace();
        final Map<String,Map<String,Object>> symbols = new LinkedHashMap<>();
        final Map<String,List<Map<String,Object>>> locals = new LinkedHashMap<>();
        final Map<String,Map<String,Object>> parameters = new LinkedHashMap<>();
        final List<Map<String,Object>> history = new ArrayList<>();
        final java.util.concurrent.atomic.AtomicInteger resolveCalls = new java.util.concurrent.atomic.AtomicInteger();
        final java.util.concurrent.atomic.AtomicInteger localCalls = new java.util.concurrent.atomic.AtomicInteger();
        @SuppressWarnings("unchecked") Fixture() throws Exception {
            if (!"1".equals(System.getenv("WORKBENCH_MOCK"))) throw new AssertionError("Run with the mock explicitly enabled");
            var hf = Workspace.class.getDeclaredField("history"); hf.setAccessible(true);
            ((Map<String,List<Map<String,Object>>>)hf.get(ws)).put("t1", history);
            var ef = Workspace.class.getDeclaredField("engines"); ef.setAccessible(true);
            ((Map<String,Engine>)ef.get(ws)).put("t1", (Engine)Proxy.newProxyInstance(Engine.class.getClassLoader(), new Class<?>[]{Engine.class}, (proxy,method,args) -> {
                if ("resolve".equals(method.getName())) {
                    resolveCalls.incrementAndGet();
                    Map<String,Object> row = symbols.get(String.valueOf(args[0]));
                    if (row == null) throw OpException.notFound("fixture symbol missing", String.valueOf(args[0]));
                    return new LinkedHashMap<>(row);
                }
                if ("locals".equals(method.getName())) { localCalls.incrementAndGet(); return locals.getOrDefault(String.valueOf(args[0]),List.of()); }
                if ("methodParameters".equals(method.getName())) return parameters.getOrDefault(String.valueOf(args[0]),Map.of());
                throw new UnsupportedOperationException(method.getName());
            }));
        }
        void current(String original, String address, String name) {
            Map<String,Object> row = new LinkedHashMap<>(Map.of("origSig",original,"addr",address,"name",name,"origName","a","kind","method","exact",true));
            symbols.put(original,row); symbols.put(address,row);
        }
        Map<String,Object> read(String original, String text) {
            Map<String,Object> data = new LinkedHashMap<>(); data.put("symbol",symbols.get(original)); data.put("text",text);
            Object result = ws.decorateForAgent("code.decompile",Map.of("target","t1"),"llm:reader",data);
            return result == null ? data : map(result);
        }
        List<Map<String,Object>> names(Map<String,Object> data, String key) {
            return ((List<?>)data.getOrDefault(key,List.of())).stream().map(NameProvenanceTest::map).toList();
        }
        Map<String,Object> local(String method, String id, String name, Integer index) {
            Map<String,Object> row=new LinkedHashMap<>(Map.of("originalMethod",method,"originalSymbol",method+"#local:"+id,
                    "itemId",id,"name",name,"originalName","v"+id));
            if(index!=null) row.put("parameterIndex",index);
            locals.computeIfAbsent(method,key->new ArrayList<>()).add(row);return row;
        }
        Map<String,Object> localHistory(String id, Map<String,Object> local, String actor, int second) {
            String method=String.valueOf(local.get("originalMethod"));
            Map<String,Object> entry=rename(id,String.valueOf(local.get("originalSymbol")),method,method,String.valueOf(local.get("name")),actor,second);
            entry.put("kind","local"); entry.put("originalMethod",method);entry.put("confidence",0.85);
            history.add(entry);return entry;
        }
    }
    public static void main(String[] args) throws Exception {
        Fixture legacy = new Fixture();
        legacy.current(METHOD,"Lfixture/NamedChild;->currentParser(I)V","currentParser");
        legacy.history.add(rename("h1",null,METHOD,"Lfixture/NamedChild;->parse(I)V","parse","agent:first auto-applied",1));
        check(legacy.names(legacy.read(METHOD,"int parse = 1; currentParser(parse);"),"agentNames").isEmpty(),
                "obsolete legacy name mentioned in text is not presented as current");

        Fixture f = new Fixture();
        String childAddr="Lfixture/NamedChild;->currentParser(I)V", parentAddr="Lfixture/NamedBase;->currentParser(I)V";
        f.current(METHOD,childAddr,"currentParser"); f.current(PARENT,parentAddr,"currentParser");
        f.history.add(rename("h1",METHOD,METHOD,"Lfixture/Child;->parse(I)V","parse","agent:first auto-applied",1));
        Map<String,Object> family=rename("h2",PARENT,PARENT,"Lfixture/Base;->currentParser(I)V","currentParser","agent:second auto-applied",2);
        family.put("renameTargets",List.of(Map.of("originalSymbol",PARENT),Map.of("originalSymbol",METHOD)));
        f.history.add(family);
        Map<String,Object> decorated=f.read(METHOD,"int parse = 1; currentParser(parse);");
        List<Map<String,Object>> names=f.names(decorated,"agentNames");
        check(names.size()==2,"one current provenance row per affected method-family identity");
        Map<String,Object> child=names.stream().filter(row->METHOD.equals(row.get("originalSymbol"))).findFirst().orElseThrow();
        check(childAddr.equals(child.get("addr")) && "currentParser".equals(child.get("name")),"owner rename and family propagation use current address and name");
        check("a".equals(child.get("was")) && "second".equals(child.get("by")),"family member keeps original name and actual assigning agent");
        check(names.stream().noneMatch(row->"parse".equals(row.get("name"))),"stale label cannot coexist with current family name");
        Map<String,Object> rowData=map(f.ws.decorateForAgent("code.outline",Map.of("target","t1"),"llm:reader",Map.of("methods",List.of(Map.of("addr",childAddr)))));
        check("second".equals(map(((List<?>)rowData.get("methods")).get(0)).get("namedBy")),"row-shaped output uses current family provenance");

        Fixture human=new Fixture(); human.current(METHOD,childAddr,"currentParser");
        human.history.add(rename("h1",METHOD,METHOD,"Lfixture/OldChild;->oldName(I)V","oldName","human:analyst",1));
        human.history.add(rename("h2",METHOD,METHOD,"Lfixture/Child;->currentParser(I)V","currentParser","agent:second auto-applied",2));
        Map<String,Object> humanRead=human.read(METHOD,"oldName(); currentParser();");
        check(human.names(humanRead,"humanNames").isEmpty(),"stale human label is not advertised as current or protected");
        check(human.names(humanRead,"agentNames").size()==1,"later current agent assignment has one identity");
        human.history.add(rename("h3",METHOD,METHOD,"Lfixture/OtherOwner;->currentParser(I)V","currentParser","human:analyst",3));
        humanRead=human.read(METHOD,"currentParser();");
        check(human.names(humanRead,"agentNames").isEmpty() && human.names(humanRead,"humanNames").size()==1,"latest matching human assignment replaces agent provenance across owner aliases");

        Fixture redo=new Fixture(); redo.current(METHOD,childAddr,"currentParser");
        Map<String,Object> first=rename("h1",METHOD,METHOD,childAddr,"currentParser","agent:earlier auto-applied",1);
        first.put("reversals",List.of(Map.of("op","redo","ts","2026-01-01T00:00:04Z")));
        redo.history.add(first); redo.history.add(rename("h2",METHOD,METHOD,childAddr,"currentParser","human:analyst",2));
        List<Map<String,Object>> redoNames=redo.names(redo.read(METHOD,"currentParser();"),"agentNames");
        check(redoNames.size()==1 && "earlier".equals(redoNames.get(0).get("by")),"effective redo order controls latest current attribution");
        redo.history.get(0).put("undone",true);
        check(redo.names(redo.read(METHOD,"currentParser();"),"humanNames").size()==1,"undone entry does not supersede a current assignment");

        Fixture contradiction=new Fixture(); contradiction.current(METHOD,childAddr,"currentParser");
        Map<String,Object> wrong=rename("h1",null,METHOD,"Lfixture/Child;->currentParser(J)V","currentParser","agent:wrong auto-applied",1);
        contradiction.history.add(wrong);
        contradiction.symbols.put(String.valueOf(wrong.get("newAddr")),new LinkedHashMap<>(Map.of("origSig",METHOD,"addr",childAddr,"name","currentParser","contradiction",true,"asked",wrong.get("newAddr"),"lenient","parameter types")));
        check(contradiction.names(contradiction.read(METHOD,"currentParser();"),"agentNames").isEmpty(),"contradicting legacy descriptors cannot assign provenance to another method");
        Map<String,Object> marker=Map.of("requested","main operation"); Dispatcher.RESOLUTION.set(marker);
        f.read(METHOD,"currentParser();");
        check(Dispatcher.RESOLUTION.get()==marker,"provenance lookups do not replace the operation resolution metadata"); Dispatcher.RESOLUTION.remove();
        check(f.ws.decorateForAgent("code.decompile",Map.of("target","t1"),"human",Map.of("text","parse();"))==null,"human reads retain their existing undecorated contract");

        // A full campaign history must not turn each of eight reads into thousands
        // of serialized native lookups. All relevant family identities still resolve.
        for (int i=0;i<2500;i++) {
            String original="Lfixture/Other"+i+";->a()V", name="unrelatedName"+i;
            f.current(original,"Lfixture/Other"+i+";->"+name+"()V",name);
            f.history.add(rename("large"+i,original,original,original,name,"agent:other auto-applied",3));
        }
        check(Boolean.FALSE.equals(f.ws.historyUndone("t1","h1")),"exact history lookup finds an applied entry older than the UI page");
        f.history.get(0).put("undone",true);
        check(Boolean.TRUE.equals(f.ws.historyUndone("t1","h1")),"exact history lookup finds an old undone entry");
        f.history.get(0).put("undone",false);
        check(f.ws.historyUndone("t1","absent")==null,"an unknown history id remains distinct from an applied entry");
        f.resolveCalls.set(0);
        var pool=java.util.concurrent.Executors.newFixedThreadPool(8);
        try {
            var jobs=new ArrayList<java.util.concurrent.Callable<Boolean>>();
            for(int i=0;i<8;i++) jobs.add(()->f.names(f.read(METHOD,"currentParser();"),"agentNames").size()==2);
            for(var result:pool.invokeAll(jobs)) check(result.get(),"concurrent reader keeps complete current family attribution");
        } finally {pool.shutdownNow();}
        check(f.resolveCalls.get()==16,"eight reads resolve only their two relevant identities, not 2500 unrelated names each");
        f.current(METHOD,"Lfixture/NamedChild;->freshParser(I)V","freshParser");
        f.history.add(rename("fresh",METHOD,METHOD,"Lfixture/NamedChild;->freshParser(I)V","freshParser","agent:fresh auto-applied",5));
        var fresh=f.names(f.read(METHOD,"freshParser();"),"agentNames");
        check(fresh.size()==1 && "fresh".equals(fresh.get(0).get("by")),"next read uses a newly renamed name without an attribution cache delay");

        Fixture local=new Fixture(); local.current(METHOD,childAddr,"currentParser"); local.current(PARENT,parentAddr,"currentParser");
        Map<String,Object> arg=local.local(METHOD,"1","limit",0), body=local.local(METHOD,"2","total",null);
        var argumentEntry=local.localHistory("argument",arg,"agent:arg-owner auto-applied",1);
        local.localHistory("body",body,"llm:body-owner",2);
        local.localHistory("other",local.local(PARENT,"1","limit",0),"llm:unrelated",3);
        String sibling="Lfixture/Child;->b(I)V";
        local.current(sibling,"Lfixture/NamedChild;->other(I)V","other");
        local.current("Lfixture/Child;","Lfixture/NamedChild;","NamedChild");
        local.symbols.get(METHOD).put("owner",Map.of("addr","Lfixture/NamedChild;"));
        local.localHistory("sibling",local.local(sibling,"1","limit",0),"llm:sibling",3);
        var localNames=local.names(local.read(METHOD,"return total + limit;"),"agentNames");
        check(localNames.size()==2,"locals and arguments retain separate identities under one method address");
        check(localNames.stream().allMatch(row->METHOD.equals(row.get("originalMethod")) && childAddr.equals(row.get("addr"))),"same variable name in sibling/other-class methods is not attributed through navigation ownership");
        var argName=localNames.stream().filter(row->"limit".equals(row.get("name"))).findFirst().orElseThrow();
        check("v1".equals(argName.get("was")) && "arg-owner".equals(argName.get("by")) && argName.get("confidence").equals(0.85),"local attribution carries original name, actor and confidence");
        var localRows=(List<?>)local.ws.decorateForAgent("locals.list",Map.of("target","t1","symbol",METHOD),"llm:reader",local.locals.get(METHOD));
        check("arg-owner".equals(map(localRows.get(0)).get("namedBy")) && map(localRows.get(0)).containsKey("nameProvenance"),"locals.list exposes argument authorship on the exact identity row");
        Map<String,Object> paramData=Map.of("method",childAddr,"original_method",METHOD,"parameters",List.of(Map.of("index",0,"name","limit")));
        var paramRead=map(local.ws.decorateForAgent("code.params",Map.of("target","t1","symbol",METHOD),"llm:reader",paramData));
        check("arg-owner".equals(map(((List<?>)paramRead.get("parameters")).get(0)).get("namedBy")),"code.params matches attribution by formal index");
        argumentEntry.put("undone",true); arg.put("name","v1");
        check(local.names(local.read(METHOD,"return total + v1; // old limit"),"agentNames").size()==1,"undo removes local attribution even if the old name remains in a comment");
        argumentEntry.put("undone",false);arg.put("name","limit");
        argumentEntry.put("reversals",List.of(Map.of("op","redo","ts","2026-01-01T00:00:05Z")));
        check(local.names(local.read(METHOD,"return total + limit;"),"agentNames").size()==2,"redo restores local attribution immediately");
        arg.put("name","boundary");local.localHistory("refined",arg,"llm:refiner",6);
        check(local.names(local.read(METHOD,"return total + boundary; // limit"),"agentNames").stream().anyMatch(row->"boundary".equals(row.get("name")) && "refiner".equals(row.get("by"))),"a refinement replaces previous local authorship without a cache delay");
        Map<String,Object> mapping=rename("mapping",null,childAddr,childAddr,"boundary","human:analyst",7);
        mapping.put("kind","parameters");mapping.put("original_method",METHOD);
        mapping.put("changes",List.of(Map.of("index",0,"oldName","boundary","newName","boundary")));local.history.add(mapping);
        var adopted=local.read(METHOD,"return total + boundary;");
        check(local.names(adopted,"humanNames").size()==1 && local.names(adopted,"agentNames").size()==1,"explicit human parameter mapping supersedes local agent history on the same identity");
        mapping.put("undone",true);
        check(local.names(local.read(METHOD,"return total + boundary;"),"agentNames").size()==2,"undoing a human mapping restores prior argument attribution");
        for(int i=0;i<2500;i++) {
            String method="Lfixture/Other"+i+";->a(I)V";
            local.localHistory("unrelated"+i,local.local(method,"1","boundary",0),"llm:unrelated",8);
        }
        local.localCalls.set(0);
        var readers=java.util.concurrent.Executors.newFixedThreadPool(8);
        try {
            var jobs=new ArrayList<java.util.concurrent.Callable<Boolean>>();
            for(int i=0;i<8;i++) jobs.add(()->local.names(local.read(METHOD,"return total + boundary;"),"agentNames").size()==2);
            for(var result:readers.invokeAll(jobs)) check(result.get(),"parallel local read has complete method-specific attribution");
        } finally {readers.shutdown();}
        check(local.localCalls.get()==8,"eight readers inventory only their one relevant method, despite 2500 identical historical local names");
        Fixture unrendered=new Fixture();unrendered.current(METHOD,childAddr,"currentParser");
        Map<String,Object> unmapped=Map.of("method",childAddr,"original_method",METHOD,"parameters",List.of(Map.of("index",0,"name","count")));
        unrendered.parameters.put(METHOD,unmapped);
        var invisibleMapping=rename("abstract-mapping",null,childAddr,childAddr,"count","human:analyst",1);
        invisibleMapping.put("kind","parameters");invisibleMapping.put("original_method",METHOD);
        invisibleMapping.put("changes",List.of(Map.of("index",0,"oldName","arg0","newName","count")));unrendered.history.add(invisibleMapping);
        var invisible=map(unrendered.ws.decorateForAgent("code.params",Map.of("target","t1","symbol",METHOD),"llm:reader",unmapped));
        var invisibleName=unrendered.names(invisible,"humanNames").get(0);
        check((METHOD+"#parameter:0").equals(invisibleName.get("originalSymbol")) && "arg0".equals(invisibleName.get("previousName")) && !invisibleName.containsKey("was"),"a non-rendered formal argument keeps its index identity without inventing an original name");
        var clear=rename("clear",null,childAddr,childAddr,"(engine name)","human:analyst",2);
        clear.put("kind","parameters");clear.put("original_method",METHOD);clear.put("changes",List.of(Map.of("index",0,"cleared",true)));unrendered.history.add(clear);
        check(unrendered.ws.decorateForAgent("code.params",Map.of("target","t1","symbol",METHOD),"llm:reader",unmapped)==null,"a cleared mapping does not keep historical authorship just because the rendered name matches");
        System.out.println("Name provenance: " + checks + " checks passed");
    }
}
