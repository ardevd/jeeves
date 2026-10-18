package dev.workbench.server;

import dev.workbench.registry.Json;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

/** Real status HTTP requests, 208 archived missions and 24,000 edits; no JEB or paid model calls. */
public final class MissionProgressTest {
    private static int checks;
    private static void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
        checks++; System.out.println("PASS " + message);
    }
    private static final class History extends ArrayList<Map<String,Object>> {
        final AtomicInteger scans = new AtomicInteger();
        @Override public Iterator<Map<String,Object>> iterator() { scans.incrementAndGet(); return super.iterator(); }
    }
    @SuppressWarnings("unchecked") private static <T> T field(Object obj, String name) throws Exception {
        var f=obj.getClass().getDeclaredField(name);f.setAccessible(true);return (T)f.get(obj);
    }
    @SuppressWarnings("unchecked") private static Map<String,Object> map(Object obj) { return (Map<String,Object>)obj; }
    private static Map<String,Integer> counts(List<?> rows) {
        Map<String,Integer> out=new LinkedHashMap<>();
        for(Object obj:rows) { var row=map(obj);out.put(String.valueOf(row.get("agentId")),((Number)row.get("writes")).intValue()); }
        return out;
    }
    private static Map<String,Object> entry(String actor) {
        return new LinkedHashMap<>(Map.of("actor",actor,"op","rename","kind","local"));
    }
    public static void main(String[] args) throws Exception {
        if (!"1".equals(System.getenv("WORKBENCH_MOCK"))) throw new AssertionError("Mock must be explicit");
        WorkbenchServer server=new WorkbenchServer(Path.of("web"));
        Workspace ws=field(server,"workspace");AgentManager manager=ws.agents();
        Map<String,List<Map<String,Object>>> histories=field(ws,"history");
        Map<String,AgentManager.Mission> missions=field(manager,"missions");
        History first=new History(),second=new History();histories.put("t1",first);histories.put("t2",second);
        for(int i=0;i<208;i++) {
            var m=AgentManager.Mission.fromMap(Map.of("id","m"+i,"agentId","agent-"+i,"state","done","goal","fixture","model","synthetic"));
            m.target=i==1?"closed-target":i==2?null:i==3?"t2":"t1";missions.put(m.id,m);
        }
        String[] prefix={"llm:","agent:","agent:llm:","llm:agent:"};
        String[] suffix={""," auto-applied"," accepted by human"," (follows class rename) auto-applied"};
        for(int i=0;i<24000;i++) {
            var row=entry(prefix[i%4]+"agent-"+(i%208)+suffix[i%4]);
            if(i%19==0)row.put("undone",true);
            if(i%23==0)row.put("discarded",true);
            first.add(row);
        }
        first.add(entry("human:analyst"));first.add(entry("user:analyst"));first.add(entry("agent:"));
        check(!ws.writeCountsByAgent("t1").containsKey(""),"blank agent identities keep zero writes");
        second.add(entry("agent:agent-1 auto-applied"));second.add(entry("llm:agent-2"));second.add(entry("agent:agent-3"));
        // The existing per-agent path is an independent compatibility oracle and benchmark.
        Map<String,Integer> expected=new LinkedHashMap<>();long start=System.nanoTime();
        for(var m:missions.values()) {
            int n=0;for(String target:(m.target!=null&&ws.openTargetIds().contains(m.target))?List.of(m.target):ws.openTargetIds())n+=ws.writesBy(target,m.agentId);
            expected.put(m.agentId,n);
        }
        double legacyMs=(System.nanoTime()-start)/1e6;
        first.scans.set(0);second.scans.set(0);start=System.nanoTime();
        check(counts(manager.missionList()).equals(expected),"all 208 counts preserve aliases, exclusions and target fallback");
        double bulkMs=(System.nanoTime()-start)/1e6;
        check(first.scans.get()==1&&second.scans.get()==1,"one history pass per target, independent of historical mission count");
        var changed=entry("agent:agent-0 accepted by human");
        synchronized(ws){first.add(changed);}expected.merge("agent-0",1,Integer::sum);
        check(counts(manager.missionList()).equals(expected),"new writes appear in the next poll");
        synchronized(ws){changed.put("undone",true);}expected.merge("agent-0",-1,Integer::sum);
        check(counts(manager.missionList()).equals(expected),"undo appears immediately");
        synchronized(ws){changed.put("undone",false);}expected.merge("agent-0",1,Integer::sum);
        check(counts(manager.missionList()).equals(expected),"redo appears immediately");
        synchronized(ws){changed.put("discarded",true);}expected.merge("agent-0",-1,Integer::sum);
        check(counts(manager.missionList()).equals(expected),"discard appears immediately");
        server.start();
        try(HttpClient client=HttpClient.newHttpClient()) {
            first.scans.set(0);second.scans.set(0);start=System.nanoTime();
            var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+WorkbenchServer.PORT+"/api/agent.missions"))
                    .header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString("{\"params\":{}}" )).build();
            List<CompletableFuture<HttpResponse<String>>> pending=new ArrayList<>();
            for(int i=0;i<16;i++)pending.add(client.sendAsync(request,HttpResponse.BodyHandlers.ofString()));
            for(var future:pending) {
                var response=future.join();var body=Json.parseObject(response.body());
                check(response.statusCode()==200&&Boolean.TRUE.equals(body.get("ok"))&&counts((List<?>)body.get("data")).equals(expected),
                        "concurrent HTTP poll returns complete current counts");
            }
            double concurrentMs=(System.nanoTime()-start)/1e6;
            check(first.scans.get()==16&&second.scans.get()==16,"16 concurrent polls perform 16 passes, not 208 passes each");
            System.out.println(Json.write(Map.of("legacy_ms",legacyMs,"bulk_ms",bulkMs,"sixteen_http_ms",concurrentMs,
                    "missions",208,"history_entries",first.size(),"checks",checks)));
        } finally {server.stop();}
    }
}
