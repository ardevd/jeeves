package dev.workbench.llm;

import dev.workbench.registry.Dispatcher;
import dev.workbench.registry.Json;
import dev.workbench.registry.Op;
import dev.workbench.registry.OpException;
import dev.workbench.registry.Registry;
import dev.workbench.server.AgentManager;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/** Real loop/tool routing with a scripted provider; no keys, network or target writes. */
public final class AgentCompletionTest {
    private static int checks;
    private static synchronized void check(boolean value,String message) {
        if(!value) throw new AssertionError(message);
        checks++;System.out.println("PASS "+message);
    }
    private static void run(String action,boolean succeeds,int expectedTurns,boolean unlimited) {
        Registry registry=new Registry();
        registry.register(new Op("naming.coverage.record","",true,Op.Cost.cheap,r->{
            if(!succeeds) {
                var error=OpException.syntax("fixture rejected coverage","Lfixture/Named;->a()V#local:123456789012345678");
                error.extra.put("itemIndex",1);error.extra.put("field","identity");
                error.extra.put("originalMethod","Lfixture/Named;->a()V");
                error.extra.put("identity",error.detail);
                error.extra.put("hint","Read the current inventory and copy its exact identity.");
                throw error;
            }
            check("llm:completion-test".equals(r.actor()),"coverage goes through the actual agent actor");
            return Map.of("status","complete","counts",Map.of("retained",2,"renamed",0));
        }));
        registry.register(new Op("naming.coverage","",false,Op.Cost.cheap,r->Map.of("status","missing")));
        AgentManager.Mission mission=new AgentManager.Mission();
        mission.id="fixture";mission.agentId="completion-test";mission.goal="Audit Lfixture/Named;";
        mission.state="running";mission.target="fixture";mission.budgetUnlimited=unlimited;mission.turnsUnlimited=unlimited;
        mission.budget=1000000;
        AtomicInteger requests=new AtomicInteger();List<Map<String,Object>> records=new ArrayList<>();
        AgentLoop.ChatClient provider=request->{
            int turn=requests.incrementAndGet();
            if(turn==1) {
                String prompt=Json.write(request.get("messages"));
                check(prompt.contains("no cumulative token budget")==unlimited,
                        "prompt reports the actual mission token-budget policy");
                check(prompt.contains("no turn-count limit")==unlimited,
                        "prompt reports the actual mission turn policy");
                check(prompt.contains("items:[]") && prompt.contains("never records your own assessment"),
                        "completion prompt distinguishes prior evidence and requires empty-method receipts");
            }
            if(turn>3) throw new AssertionError("loop did not finish");
            if(turn==2 && "record".equals(action) && !succeeds) {
                var messages=(List<?>)request.get("messages");
                var tool=messages.stream().filter(m->m instanceof Map<?,?> row && "tool".equals(row.get("role")))
                        .map(m->(Map<?,?>)m).findFirst().orElseThrow();
                var error=(Map<?,?>)Json.parse(String.valueOf(tool.get("content")));
                check("Lfixture/Named;->a()V#local:123456789012345678".equals(error.get("detail"))
                                && error.get("detail").equals(error.get("identity")),
                        "actual next model request preserves the exact rejected identity and detail");
                check(((Number)error.get("itemIndex")).intValue()==1 && "identity".equals(error.get("field"))
                                && "Lfixture/Named;->a()V".equals(error.get("originalMethod")),
                        "actual loop preserves structured coverage diagnostics");
                check("Read the current inventory and copy its exact identity.".equals(error.get("hint")),
                        "operation-specific correction hint reaches the next model request");
            }
            Map<String,Object> message;
            if(turn==1) message=Map.of("role","assistant","tool_calls",List.of(Map.of("id","coverage","type","function",
                    "function",Map.of("name","naming_coverage","arguments",Json.write(Map.of("action",action,"symbol","Lfixture/Named;->a()V"))))));
            else {
                if(turn==3) {
                    String prompt=Json.write(request.get("messages"));
                    check(prompt.contains("Preserve suitable existing names") && !prompt.contains("Rename now"),
                            "unrecorded completion nudge preserves supported names");
                }
                message=Map.of("role","assistant","content","Existing names are supported by the audited uses.");
            }
            return Map.of("choices",List.of(Map.of("message",message)),"usage",Map.of("prompt_tokens",1,"completion_tokens",1));
        };
        AgentLoop.Activity activity=new AgentLoop.Activity(){
            public void row(String kind,String text){}
            public void record(Map<String,Object> record){records.add(new java.util.LinkedHashMap<>(record));}
        };
        new AgentLoop(new Dispatcher(registry),mission,provider,"fixture",activity,unlimited?0:4).run();
        check("done".equals(mission.state),"audit mission completes: "+action+"/"+succeeds);
        check(requests.get()==expectedTurns,"only successful recorded coverage avoids the extra rename nudge");
        check(records.stream().filter(r->"nudged".equals(r.get("outcome"))).count()==expectedTurns-2,
                "transcript reports the actual completion behavior");
    }
    public static void main(String[] args) throws Exception {
        run("record",true,2,true);run("record",false,3,true);run("get",true,3,true);run("record",true,2,false);
        var pool=java.util.concurrent.Executors.newFixedThreadPool(8);
        try {
            List<java.util.concurrent.Callable<Void>> tasks=new ArrayList<>();
            for(int i=0;i<8;i++)tasks.add(()->{run("record",false,3,true);return null;});
            for(var result:pool.invokeAll(tasks))result.get();
        } finally { pool.shutdown(); }
        System.out.println("Agent completion checks passed: "+checks);
    }
}
