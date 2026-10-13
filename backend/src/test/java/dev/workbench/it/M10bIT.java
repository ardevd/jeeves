package dev.workbench.it;

import dev.workbench.registry.Json;
import dev.workbench.server.WorkbenchServer;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * M10b integration test (plain main-class, no JUnit): named user layouts —
 * layout.save/list/apply round-trip (apply returns the identical snapshot),
 * same-name upsert (one entry, new ts), E_NOTFOUND on unknown apply/delete,
 * layouts.json persistence across a server restart (M4IT stop+new pattern),
 * and the four ops on the MCP surface (ops.list) with correct mutating flags.
 * Prints PASS/FAIL; exit 1 on failure. Run: gradle m10bIT.
 */
public final class M10bIT {

    private static int failures = 0, checks = 0;
    private static final HttpClient http = HttpClient.newHttpClient();
    private static final String BASE = "http://127.0.0.1:" + WorkbenchServer.PORT;
    private static final String SIDECAR = sidecarDir();

    public static void main(String[] args) throws Exception {
        try {
            run();
        } catch (Throwable t) {
            System.out.println("M10b IT FAILED with exception: " + t);
            t.printStackTrace(System.out);
            System.exit(1);
        }
    }

    private static String sidecarDir() {
        for (String p : List.of("backend/.workbench", ".workbench"))
            if (Files.isDirectory(Path.of(p))) return p;
        return "backend/.workbench";
    }

    private static void run() throws Exception {
        // back up any pre-existing user layouts; restored in finally
        Path store = Path.of(SIDECAR, "layouts.json");
        String storeBak = Files.isRegularFile(store) ? Files.readString(store) : null;
        Files.deleteIfExists(store);
        try {
            WorkbenchServer server = new WorkbenchServer(Path.of("web"));
            server.start();
            System.out.println("server up on " + BASE);

            // ---- (e) MCP surface: the four ops in ops.list with correct M flags
            @SuppressWarnings("unchecked")
            List<Object> ops = (List<Object>) data(post("ops.list", Map.of()));
            Map<String, Map<?, ?>> byName = new LinkedHashMap<>();
            for (Object o : ops) {
                Map<?, ?> row = (Map<?, ?>) o;
                byName.put(String.valueOf(row.get("name")), row);
            }
            for (String name : List.of("layout.list", "layout.save", "layout.apply", "layout.delete"))
                check(byName.containsKey(name), "ops.list has " + name);
            check(byName.get("layout.list") != null
                            && Boolean.FALSE.equals(byName.get("layout.list").get("mutating")),
                    "layout.list is read-only");
            check(byName.get("layout.apply") != null
                            && Boolean.FALSE.equals(byName.get("layout.apply").get("mutating")),
                    "layout.apply is read-only (returns the snapshot; DOM apply is client-side)");
            check(byName.get("layout.save") != null
                            && Boolean.TRUE.equals(byName.get("layout.save").get("mutating")),
                    "layout.save is mutating");
            check(byName.get("layout.delete") != null
                            && Boolean.TRUE.equals(byName.get("layout.delete").get("mutating")),
                    "layout.delete is mutating");

            // ---- (a) save → list → apply returns the identical snapshot
            Map<String, Object> snap = snapshotFixture(false);
            Map<String, Object> saved = data(post("layout.save",
                    Map.of("name", "re-session", "layout", snap)));
            check("re-session".equals(saved.get("name")) && saved.get("ts") != null,
                    "layout.save → {name, ts} (" + saved.get("ts") + ")");
            @SuppressWarnings("unchecked")
            List<Object> ls = (List<Object>) data(post("layout.list", Map.of()));
            check(ls.size() == 1 && "re-session".equals(((Map<?, ?>) ls.get(0)).get("name"))
                            && saved.get("ts").equals(((Map<?, ?>) ls.get(0)).get("ts")),
                    "layout.list → 1 row: re-session @ its save ts");
            Map<String, Object> applied = data(post("layout.apply", Map.of("name", "re-session")));
            check(Json.write(snap).equals(Json.write(applied.get("layout"))),
                    "layout.apply returns the identical snapshot (deep-equal)");

            // ---- (b) same-name save → upsert: one entry, new ts, new snapshot
            Thread.sleep(10);   // ts strings must differ
            Map<String, Object> snap2 = snapshotFixture(true);
            Map<String, Object> saved2 = data(post("layout.save",
                    Map.of("name", "re-session", "layout", snap2)));
            check(!String.valueOf(saved.get("ts")).equals(String.valueOf(saved2.get("ts"))),
                    "upsert stamps a new ts (" + saved.get("ts") + " → " + saved2.get("ts") + ")");
            @SuppressWarnings("unchecked")
            List<Object> ls2 = (List<Object>) data(post("layout.list", Map.of()));
            check(ls2.size() == 1, "upsert keeps ONE entry (got " + ls2.size() + ")");
            Map<String, Object> applied2 = data(post("layout.apply", Map.of("name", "re-session")));
            check(Json.write(snap2).equals(Json.write(applied2.get("layout"))),
                    "apply after upsert returns the NEW snapshot");

            // ---- (c) unknown names → E_NOTFOUND; layout.save without a layout → E_SYNTAX
            check("E_NOTFOUND".equals(errorCode(post("layout.apply", Map.of("name", "no-such")))),
                    "layout.apply unknown → E_NOTFOUND");
            check("E_NOTFOUND".equals(errorCode(post("layout.delete", Map.of("name", "no-such")))),
                    "layout.delete unknown → E_NOTFOUND");
            check("E_SYNTAX".equals(errorCode(post("layout.save", Map.of("name", "broken")))),
                    "layout.save without a layout object → E_SYNTAX");

            // ---- (d) persistence across a server restart (M4IT stop + new pattern)
            data(post("layout.save", Map.of("name", "triage", "layout", snapshotFixture(false))));
            check(Files.isRegularFile(store), "layouts.json written under " + SIDECAR);
            server.stop();
            server = new WorkbenchServer(Path.of("web"));
            server.start();
            System.out.println("  server restarted on " + BASE);
            @SuppressWarnings("unchecked")
            List<Object> ls3 = (List<Object>) data(post("layout.list", Map.of()));
            check(ls3.size() == 2, "after restart: both layouts listed (got " + ls3.size() + ")");
            Map<String, Object> applied3 = data(post("layout.apply", Map.of("name", "re-session")));
            check(Json.write(snap2).equals(Json.write(applied3.get("layout"))),
                    "after restart: apply returns the identical snapshot");

            // ---- delete: gone from list, then E_NOTFOUND on re-apply
            Map<String, Object> del = data(post("layout.delete", Map.of("name", "triage")));
            check(Boolean.TRUE.equals(del.get("deleted")), "layout.delete triage → deleted");
            @SuppressWarnings("unchecked")
            List<Object> ls4 = (List<Object>) data(post("layout.list", Map.of()));
            check(ls4.size() == 1 && "re-session".equals(((Map<?, ?>) ls4.get(0)).get("name")),
                    "deleted layout gone from layout.list");
            check("E_NOTFOUND".equals(errorCode(post("layout.apply", Map.of("name", "triage")))),
                    "apply after delete → E_NOTFOUND");
            data(post("layout.delete", Map.of("name", "re-session")));   // self-clean
        } finally {
            // NOTE: System.exit skips finally — the verdict/exit lives OUTSIDE
            // this block (M10aIT precedent) so the user's layouts.json is restored.
            try {
                if (storeBak != null) Files.writeString(store, storeBak);
                else Files.deleteIfExists(store);
            } catch (Exception ignored) {}
        }

        System.out.println();
        if (failures > 0) {
            System.out.println("M10b IT FAILED: " + failures + "/" + checks + " checks failed");
            System.exit(1);
        }
        System.out.println("M10b IT PASSED: " + checks + " checks green");
        System.exit(0);
    }

    /** A realistic frontend snapshot (web/js/ui/layouts.js shape); ints only (Json round-trip). */
    private static Map<String, Object> snapshotFixture(boolean variant) {
        Map<String, Object> ui = new LinkedHashMap<>();
        ui.put("side", true);
        ui.put("dock", !variant);
        Map<String, Object> dock = new LinkedHashMap<>();
        dock.put("panels", variant ? List.of("runtime", "history") : List.of("runtime", "tags", "flows"));
        dock.put("active", variant ? "history" : "tags");
        Map<String, Object> cdock = new LinkedHashMap<>();
        cdock.put("panels", List.of());
        cdock.put("active", null);
        Map<String, Object> fl = new LinkedHashMap<>();
        fl.put("id", "bookmarks");
        fl.put("left", 320);
        fl.put("top", 140);
        Map<String, Object> zones = new LinkedHashMap<>();
        zones.put("side", List.of());
        zones.put("right", List.of("inspector"));
        zones.put("dock", dock);
        zones.put("cdock", cdock);
        zones.put("float", variant ? List.of(fl) : List.of());
        Map<String, Object> snap = new LinkedHashMap<>();
        snap.put("ui", ui);
        snap.put("zones", zones);
        return snap;
    }

    // ------------------------------------------------------------ helpers

    private static void check(boolean ok, String label) {
        checks++;
        System.out.println((ok ? "PASS " : "FAIL ") + label);
        if (!ok) failures++;
    }

    private static Map<String, Object> post(String op, Map<String, Object> params) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("params", params);
        body.put("actor", "human");
        HttpRequest req = HttpRequest.newBuilder(URI.create(BASE + "/api/" + op))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(Json.write(body)))
                .build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        return Json.parseObject(resp.body());
    }

    @SuppressWarnings("unchecked")
    private static <T> T data(Map<String, Object> resp) {
        if (!Boolean.TRUE.equals(resp.get("ok")))
            throw new IllegalStateException("op failed: " + resp.get("error"));
        return (T) resp.get("data");
    }

    private static String errorCode(Map<String, Object> resp) {
        Object e = resp.get("error");
        if (e instanceof Map) return String.valueOf(((Map<?, ?>) e).get("code"));
        return "<none>";
    }
}
