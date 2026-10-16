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
    private static void check(boolean value,String message) {
        if(!value) throw new AssertionError(message);
        checks++;System.out.println("PASS "+message);
    }
    private static void run(String action,boolean succeeds,int expectedTurns) {
        Registry registry=new Registry();
        registry.register(new Op("naming.coverage.record","",true,Op.Cost.cheap,r->{
            if(!succeeds) throw OpException.syntax("fixture rejected coverage","retry the audit");
            check("llm:completion-test".equals(r.actor()),"coverage goes through the actual agent actor");
            return Map.of("status","complete","counts",Map.of("retained",2,"renamed",0));
        }));
        registry.register(new Op("naming.coverage","",false,Op.Cost.cheap,r->Map.of("status","missing")));
        AgentManager.Mission mission=new AgentManager.Mission();
        mission.id="fixture";mission.agentId="completion-test";mission.goal="Audit Lfixture/Named;";
        mission.state="running";mission.target="fixture";mission.budgetUnlimited=true;mission.turnsUnlimited=true;
        AtomicInteger requests=new AtomicInteger();List<Map<String,Object>> records=new ArrayList<>();
        AgentLoop.ChatClient provider=request->{
            int turn=requests.incrementAndGet();
            if(turn>3) throw new AssertionError("loop did not finish");
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
        new AgentLoop(new Dispatcher(registry),mission,provider,"fixture",activity,0).run();
        check("done".equals(mission.state),"audit mission completes: "+action+"/"+succeeds);
        check(requests.get()==expectedTurns,"only successful recorded coverage avoids the extra rename nudge");
        check(records.stream().filter(r->"nudged".equals(r.get("outcome"))).count()==expectedTurns-2,
                "transcript reports the actual completion behavior");
    }
    public static void main(String[] args) {
        run("record",true,2);run("record",false,3);run("get",true,3);
        System.out.println("Agent completion checks passed: "+checks);
    }
}
