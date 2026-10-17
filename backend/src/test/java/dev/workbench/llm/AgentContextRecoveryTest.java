package dev.workbench.llm;

import dev.workbench.registry.*;
import dev.workbench.server.AgentManager;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Whole tool results, finite provider context, no native target or paid model. */
public final class AgentContextRecoveryTest {
    private static int checks;
    private static synchronized void check(boolean ok,String why) {
        if(!ok) throw new AssertionError(why);checks++;
    }
    @SuppressWarnings("unchecked") private static Map<String,Object> map(Object value){return (Map<String,Object>)value;}
    private static Map<String,Object> call(String id,String tool,Map<String,Object> args) {
        return Map.of("id",id,"type","function","function",Map.of("name",tool,"arguments",Json.write(args)));
    }
    private static void run(String view,int size,String refusal,boolean expectDone,boolean expectElision,int expectedRequests) {
        Registry registry=new Registry();String original="x".repeat(size);
        Map<String,Object> payload=Map.of("text",original,"symbol",Map.of("addr","Lfixture/Big;"));
        registry.register(new Op("code.decompile","",false,Op.Cost.cheap,r->payload));
        registry.register(new Op("code.smali","",false,Op.Cost.cheap,r->payload));
        registry.register(new Op("naming.coverage.record","",true,Op.Cost.cheap,r->Map.of("status","complete","receipt","DO_NOT_DROP_THIS_WRITE")));
        AgentManager.Mission mission=new AgentManager.Mission();mission.id="context-"+view;mission.agentId=mission.id;
        mission.goal="Audit Lfixture/Big;";mission.target="fixture";mission.state="running";
        mission.budgetUnlimited=true;mission.turnsUnlimited=true;
        AtomicInteger requests=new AtomicInteger();List<String> captured=new ArrayList<>();List<Map<String,Object>> turns=new ArrayList<>();
        AgentLoop.ChatClient provider=request->{
            int n=requests.incrementAndGet();check(n<=expectedRequests,"no duplicate or unchanged provider attempt");
            if(n==1) return Map.of("choices",List.of(Map.of("message",Map.of("role","assistant","reasoning_content","wire-marker",
                    "tool_calls",List.of(call("read","code",Map.of("symbol","Lfixture/Big;","view",view)),
                            call("receipt","naming_coverage",Map.of("action","record","symbol","Lfixture/Big;->a()V")))))));
            List<?> messages=(List<?>)request.get("messages");String text=Json.write(messages);
            List<Map<String,Object>> replies=messages.stream().filter(v->v instanceof Map<?,?>).map(AgentContextRecoveryTest::map)
                    .filter(v->"tool".equals(v.get("role"))).toList();
            check(replies.size()==2 && replies.stream().map(r->r.get("tool_call_id")).collect(java.util.stream.Collectors.toSet()).equals(Set.of("read","receipt")),
                    "tool/result pairing survives compaction");
            check(text.contains("DO_NOT_DROP_THIS_WRITE") && text.contains("wire-marker"),"write receipt and required reasoning replay remain");
            String read=String.valueOf(replies.stream().filter(r->"read".equals(r.get("tool_call_id"))).findFirst().orElseThrow().get("content"));
            if(n==2 && refusal!=null) {
                check(!read.startsWith("[result elided"),"fixture refusal starts from an unchanged in-window estimate");
                throw new AgentLoop.WireFailure(refusal,Map.of("status",refusal.contains("HTTP 400")?400:200));
            }
            check(read.startsWith("[result elided")==expectElision,"oversized recent/pinned read is explicitly elided only when needed");
            if(expectElision)check(read.contains("window") && read.contains("Lfixture/Big;"),"notice explains an actionable narrower reread");
            else check(read.equals(Json.write(payload)),"below-window payload is byte-for-byte intact");
            check(text.length()/4<32000,"never send a known over-window recent payload");
            return Map.of("choices",List.of(Map.of("message",Map.of("role","assistant","content","Audit recorded."))));
        };
        AgentLoop.Activity activity=new AgentLoop.Activity(){public void row(String kind,String text){}public void record(Map<String,Object> row){turns.add(new LinkedHashMap<>(row));}};
        AgentLoop loop=new AgentLoop(new Dispatcher(registry),mission,provider,"fixture",activity,0,Map.of(),32000,
                new AgentLoop.Wire("reasoning_content","all",false,4096));
        loop.capture(new AgentLoop.Capture(){public void toolResult(String id,int turn,String call,String tool,Map<String,Object> args,String content,boolean ok,int fullChars){
            if("code".equals(tool)){check(content.length()==fullChars,"capture hook sees the full requested read before context compaction");captured.add(content);}
        }});
        loop.run();check(requests.get()==expectedRequests,"expected number of actual provider requests");
        check((expectDone?"done":"blocked").equals(mission.state),"completion/refusal state is honest");
        check(captured.equals(List.of(Json.write(payload))),"original tool result remains unmodified at its capture boundary");
        if(refusal!=null && expectDone)check(turns.stream().anyMatch(r->Integer.valueOf(2).equals(r.get("attempts"))),"corrected refusal records its second attempt");
    }
    public static void main(String[] args)throws Exception {
        run("smali",180000,null,true,true,2);
        run("java",180000,null,true,true,2);
        run("java",4500,null,true,false,2);
        run("smali",80000,"provider HTTP 400: {\"error\":{\"code\":\"1261\",\"message\":\"Prompt exceeds max length\"}}",true,true,3);
        run("smali",2000,"provider HTTP 400: Prompt exceeds max length",false,false,2);
        run("smali",2000,"provider stream interrupted after 3 segments — no finish",false,false,2);
        var pool=Executors.newFixedThreadPool(8);
        try{List<Callable<Void>> tasks=new ArrayList<>();for(int i=0;i<8;i++)tasks.add(()->{run("smali",180000,null,true,true,2);return null;});
            for(var future:pool.invokeAll(tasks))future.get();}
        finally{pool.shutdown();}
        System.out.println("Agent context recovery checks passed: "+checks);
    }
}
