package dev.workbench.it;

import dev.workbench.registry.Json;
import dev.workbench.server.WorkbenchServer;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/** Retired application dispatch must be absent even when invoked directly over HTTP. No phone/engine. */
public final class MessageLabIT {
    public static void main(String[] args) throws Exception {
        if (System.getenv("WORKBENCH_HOME") == null || System.getenv("WORKBENCH_PORT") == null)
            throw new IllegalStateException("Use an isolated home and free port");
        WorkbenchServer server = new WorkbenchServer(Path.of(System.getenv().getOrDefault("WORKBENCH_TEST_WEB", "web")));
        server.start();
        int checks = 0;
        try {
            for (String action : List.of("status","fixtures","events","inspect","trials","trial","artifact","compare","export","start","stop","fixture","retain","execute")) {
                Map<?, ?> result = call("runs.lab." + action, Map.of("experiment","message-lab"));
                if (!Boolean.FALSE.equals(result.get("ok"))) throw new AssertionError("retired route reachable: " + action);
                checks++;
            }
            Map<?, ?> facade = call("tool.call", Map.of("tool","message_lab","args",Map.of("action","start")));
            if (!Boolean.FALSE.equals(facade.get("ok"))) throw new AssertionError("retired facade reachable");
            checks++;
            String catalogue = Json.write(call("ops.tools", Map.of()));
            if (catalogue.contains("message_lab") || catalogue.contains("runs.lab.")) throw new AssertionError("retired tool advertised");
            checks++;
            Map<?, ?> unsafe = call("runs.attach", Map.of("device","fixture-no-phone","pkg","fixture.synthetic","target","t1"));
            if (!Boolean.FALSE.equals(unsafe.get("ok"))) throw new AssertionError("implicit provision accepted");
            checks++;
            for (String action : List.of("runs.attach","runs.spawn")) {
                Map<?,?> denied=call(action,Map.of("device","fixture-no-phone","pkg","fixture.synthetic","target","t1","serverPath","/fixture/server","ownershipConfirmed",true),"llm:fixture");
                if (!Json.write(denied).contains("ownership must be confirmed by the operator")) throw new AssertionError("Agent acquired device ownership: "+denied);
                checks++;
            }
            dev.workbench.runs.FridaBridge closed=new dev.workbench.runs.FridaBridge(new dev.workbench.server.EventBus());
            closed.shutdown();
            boolean noRestart=false;
            try { closed.ping(); } catch (IllegalStateException expected) { noRestart=expected.getMessage().contains("no automatic restart"); }
            if (!noRestart) throw new AssertionError("Closed sidecar restarted");
            checks++;
            System.out.println("Message Lab retirement: " + checks + " checks passed; no device or model called");
        } finally { server.stop(); }
    }
    private static Map<?, ?> call(String op, Map<String,Object> params) throws Exception {
        return call(op,params,"human");
    }
    private static Map<?, ?> call(String op, Map<String,Object> params, String actor) throws Exception {
        HttpRequest request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + System.getenv("WORKBENCH_PORT") + "/api/" + op))
            .header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(Json.write(Map.of("params",params,"actor",actor)))).build();
        return (Map<?, ?>) Json.parse(HttpClient.newHttpClient().send(request,HttpResponse.BodyHandlers.ofString()).body());
    }
}
