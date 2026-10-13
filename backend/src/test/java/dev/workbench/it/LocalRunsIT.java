package dev.workbench.it;

import dev.workbench.registry.Json;
import dev.workbench.server.WorkbenchServer;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/** Real HTTP and canonical Python adapter; synthetic fixture, no JEB/device/model. */
public final class LocalRunsIT {
    private static int checks;
    private static final HttpClient HTTP=HttpClient.newHttpClient();
    public static void main(String[] args) throws Exception {
        if (System.getenv("WORKBENCH_HOME")==null || System.getenv("WORKBENCH_PORT")==null
                || !"1".equals(System.getenv("WORKBENCH_MOCK"))) throw new IllegalStateException("Isolated mock home/port required");
        Path root=Path.of(System.getenv("WORKBENCH_HOME"));
        Path fixture=Path.of("fixtures/local-run/v1").toAbsolutePath();
        WorkbenchServer server=new WorkbenchServer(Path.of(System.getenv().getOrDefault("WORKBENCH_TEST_WEB","web")));
        server.start();
        String id;
        try {
            check(!ok(call("runs.import",Map.of("directory",fixture.toString()))),"explicit target required");
            Map<?,?> imported=data(call("runs.import",Map.of("directory",fixture.toString(),"target","t1")));
            id=String.valueOf(imported.get("execId"));
            check("fixture".equals(imported.get("producerMode")),"fixture remains a fixture");
            check("complete".equals(imported.get("completion")),"complete capture preserved");
            check("not-applicable".equals(imported.get("cleanup")),"cleanup preserved");
            check(id.equals(data(call("runs.import",Map.of("directory",fixture.toString(),"target","t1"))).get("execId")),"same bytes import idempotent");
            Map<?,?> hits=data(call("runs.hits",Map.of("run",id,"target","t1")));
            String encoded=Json.write(hits);
            check(encoded.contains("Lfixture/Probe;->Ping(I)V") && encoded.contains("Lfixture/Probe;->ping(I)V"),"original case preserved");
            Object listing=call("runs.runs",Map.of("target","t1")).get("data");
            check(Json.write(listing).contains("\"coverage\":[\"Lfixture/Probe;->Ping(I)V\",\"Lfixture/Probe;->ping(I)V\"]"),"imported coverage visible in run listing");
            Path export=root.resolve("selected export");
            Map<?,?> receipt=data(call("tool.call",Map.of("tool","runs","args",Map.of("action","export","target","t1","run",id,"destination",export.toString()))));
            check(Boolean.TRUE.equals(receipt.get("selected_only")),"facade exports selected bytes");
            check(Files.mismatch(fixture.resolve("manifest.json"),export.resolve("manifest.json"))==-1,"manifest bytes unchanged");
            check(!ok(call("runs.export",Map.of("target","t1","run",id,"destination",export.toString()))),"existing destination refused");
            Files.writeString(export.resolve("evidence/fixture-input.txt"),"bad bytes");
            check(!ok(call("runs.import",Map.of("target","t1","directory",export.toString()))),"wrong digest refused before store import");
        } finally { server.stop(); }
        server=new WorkbenchServer(Path.of(System.getenv().getOrDefault("WORKBENCH_TEST_WEB","web")));
        server.start();
        try {
            Map<?,?> exported=data(call("runs.export",Map.of("target","t1","run",id,"destination",root.resolve("after restart").toString())));
            check(Boolean.TRUE.equals(exported.get("valid")),"canonical bytes survive restart");
            check(id.equals(data(call("runs.import",Map.of("directory",fixture.toString(),"target","t1"))).get("execId")),"retry after restart remains idempotent");
            check(ok(call("runs.rm",Map.of("target","t1","run",id))),"imported row can be removed");
            check(!id.equals(data(call("runs.import",Map.of("directory",fixture.toString(),"target","t1"))).get("execId")),"removed bundle can be reimported from newly validated bytes");
        } finally { server.stop(); }
        Path failed=root.resolve("failed-index-store");Files.createDirectories(failed.resolve("index.json"));
        dev.workbench.runs.RunStore failedStore=new dev.workbench.runs.RunStore(failed.toFile());
        Path frozen=root.resolve("failed-index-input");
        Map<String,Object> frozenReceipt=dev.workbench.runs.RunBundles.copy(fixture,frozen);
        String sha=String.valueOf(frozenReceipt.get("manifest_sha256"));
        boolean refused=false;
        try { failedStore.importCanonical(frozen.toFile(),"t1","fixture-key","fixture",sha,true); }
        catch (java.io.IOException expected) { refused=expected.getMessage().contains("index could not be persisted"); }
        check(refused && failedStore.runs().isEmpty(),"failed persistence cannot report successful import or leave an in-memory success");
        check(Files.exists(failed.resolve("bundles").resolve(sha).resolve("manifest.json")),"failed index preserves canonical evidence");
        Files.delete(failed.resolve("index.json"));
        Path retry=root.resolve("failed-index-retry");dev.workbench.runs.RunBundles.copy(fixture,retry);
        check(failedStore.importCanonical(retry.toFile(),"t1","fixture-key","fixture",sha,true).get("execId")!=null,"failed import can be retried without deleting retained evidence");
        System.out.println("Local Runs: "+checks+" checks passed; mock analysis and canonical fixture only");
    }
    private static void check(boolean value,String label) { if (!value) throw new AssertionError(label); checks++; }
    private static boolean ok(Map<?,?> response) { return Boolean.TRUE.equals(response.get("ok")); }
    private static Map<?,?> data(Map<?,?> response) {
        if (!ok(response)) throw new AssertionError(Json.write(response));
        return (Map<?,?>)response.get("data");
    }
    private static Map<?,?> call(String op,Map<String,Object> params) throws Exception {
        HttpRequest request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+System.getenv("WORKBENCH_PORT")+"/api/"+op))
            .header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(Json.write(Map.of("params",params,"actor","human")))).build();
        return (Map<?,?>)Json.parse(HTTP.send(request,HttpResponse.BodyHandlers.ofString()).body());
    }
}
