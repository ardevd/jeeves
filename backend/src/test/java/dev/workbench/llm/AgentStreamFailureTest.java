package dev.workbench.llm;

import com.sun.net.httpserver.HttpServer;
import dev.workbench.registry.*;
import dev.workbench.server.AgentManager;
import java.io.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Broken streaming bodies never execute partial tools or lose failure diagnostics. */
public final class AgentStreamFailureTest {
    private static int checks;
    private static synchronized void check(boolean value,String message) {
        if(!value)throw new AssertionError(message);checks++;
    }
    private static final String SEGMENT=Json.write(Map.of("choices",List.of(Map.of("delta",Map.of(
            "content","PRIVATE_PARTIAL_TEXT","reasoning_content","private-reasoning-marker",
            "tool_calls",List.of(Map.of("index",0,"id","partial","type","function","function",Map.of(
                    "name","naming_coverage","arguments",Json.write(Map.of("action","record","symbol","Lfixture/Stream;->a()V"))))))))));
    private static final String EVENT="data: "+SEGMENT+"\n\n";

    private static void parserFailure(String prefix,int segments,int pendingChars) throws Exception {
        BufferedReader reader=new BufferedReader(new StringReader(prefix)) {
            @Override public String readLine() throws IOException {
                String line=super.readLine();
                if(line==null)throw new IOException("closed",new java.net.SocketException("Connection reset"));
                return line;
            }
        };
        try {AgentLoop.parseSse(reader,200,Map.of("x-request-id",List.of("fixture-request")));
            throw new AssertionError("broken stream returned an answer");}
        catch(AgentLoop.WireFailure failure) {
            check(((Number)failure.raw.get("status")).intValue()==200,"response status survives stream failure");
            check("fixture-request".equals(failure.raw.get("providerRequestId")),"provider request identity survives");
            check(((List<?>)failure.raw.get("segments")).size()==segments,"completed SSE segments survive privately");
            check(String.valueOf(failure.raw.getOrDefault("partialEventData","")).length()==pendingChars,"unfinished SSE event survives privately");
            check(failure.getCause() instanceof IOException,"transport cause remains available to server logs");
            check(failure.getMessage().contains("partial answer was not used"),"failure does not imply partial execution");
        }
    }

    @SuppressWarnings("unchecked")
    private static void loopFailure(String endpoint,int number) {
        Registry registry=new Registry();AtomicInteger writes=new AtomicInteger(),captures=new AtomicInteger();
        registry.register(new Op("naming.coverage.record","",true,Op.Cost.cheap,r->{writes.incrementAndGet();return Map.of("status","complete");}));
        AgentManager.Mission mission=new AgentManager.Mission();mission.id="stream-"+number;mission.agentId=mission.id;
        mission.target="fixture";mission.goal="Audit Lfixture/Stream;";mission.state="running";
        mission.budgetUnlimited=true;mission.turnsUnlimited=true;
        List<Map<String,Object>> turns=new ArrayList<>();
        AgentLoop.Activity activity=new AgentLoop.Activity(){public void row(String kind,String text){}
            public void record(Map<String,Object> row){turns.add(new LinkedHashMap<>(row));}};
        AgentLoop loop=new AgentLoop(new Dispatcher(registry),mission,AgentLoop.openAiStreamingClient(endpoint,null),"fixture",activity,0);
        loop.capture(new AgentLoop.Capture(){
            public void failure(String id,int turn,int attempt,String error,long ms,Map<String,Object> raw) {
                captures.incrementAndGet();check(attempt==1,"ambiguous failure is not retried");
                check(((List<?>)raw.get("segments")).equals(List.of(SEGMENT)),"failure capture receives exact complete private segment");
                check(raw.containsKey("transportError"),"capture receives transport diagnostics");
            }
        });
        loop.run();
        check("blocked".equals(mission.state) && mission.turnsDone==1,"broken stream blocks honestly at its first turn");
        check(writes.get()==0 && captures.get()==1,"partial tool is not applied or replayed");
        check(turns.size()==1,"one durable failed-turn receipt");
        Map<String,Object> failure=(Map<String,Object>)turns.get(0).get("failure");
        check(failure!=null && ((Number)failure.get("status")).intValue()==200,"public turn retains HTTP status");
        check("fixture-http-request".equals(failure.get("providerRequestId")),"public turn retains provider request id");
        check(((Number)failure.get("receivedSegments")).intValue()==1,"public turn reports received segment count");
        check(failure.get("transportError") instanceof Map,"public turn retains transport failure type");
        String publicJson=Json.write(turns);
        check(!publicJson.contains("PRIVATE_PARTIAL_TEXT") && !publicJson.contains("private-reasoning-marker"),"public failure metadata excludes private response content");
    }

    public static void main(String[] args)throws Exception {
        parserFailure("",0,0);parserFailure(EVENT,1,0);parserFailure(EVENT+"data: unfinished\n",1,10);
        byte[] bytes=EVENT.getBytes(StandardCharsets.UTF_8);AtomicInteger requests=new AtomicInteger();
        HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        var handlers=Executors.newCachedThreadPool();server.setExecutor(handlers);
        server.createContext("/v1/chat/completions",exchange->{
            requests.incrementAndGet();exchange.getRequestBody().readAllBytes();
            exchange.getResponseHeaders().set("Content-Type","text/event-stream");
            exchange.getResponseHeaders().set("X-Request-ID","fixture-http-request");
            exchange.sendResponseHeaders(200,bytes.length+64);
            try {exchange.getResponseBody().write(bytes);exchange.getResponseBody().flush();}
            finally {try {exchange.close();}catch(Exception ignored){}}
        });
        server.start();var workers=Executors.newFixedThreadPool(8);
        try {
            String endpoint="http://127.0.0.1:"+server.getAddress().getPort();
            List<Callable<Void>> tasks=new ArrayList<>();for(int i=0;i<8;i++){int n=i;tasks.add(()->{loopFailure(endpoint,n);return null;});}
            for(var result:workers.invokeAll(tasks))result.get();
            check(requests.get()==8,"eight concurrent agents make exactly eight requests without retries");
        } finally {workers.shutdown();server.stop(0);handlers.shutdown();}
        System.out.println("Agent stream failure checks passed: "+checks);
    }
}
