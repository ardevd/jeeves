package dev.workbench.it;

import dev.workbench.registry.Json;
import dev.workbench.server.WorkbenchServer;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * M8 integration test (plain main-class, no JUnit): device view backend on
 * emulator-5554 — device.list, catalog build + refresh diff, browse, pull a
 * real APK to a JEB target, annotations (human + catalog mission + review).
 * Prints PASS/FAIL; exit 1 on failure. Run: gradle m8IT.
 */
public final class M8IT {

    private static final String SERIAL = "emulator-5554";
    private static int failures = 0, checks = 0;
    private static final HttpClient http = HttpClient.newHttpClient();
    private static final String BASE = "http://127.0.0.1:" + WorkbenchServer.PORT;

    public static void main(String[] args) throws Exception {
        try {
            run();
        } catch (Throwable t) {
            System.out.println("M8 IT FAILED with exception: " + t);
            t.printStackTrace(System.out);
            System.exit(1);
        }
    }

    private static void run() throws Exception {
        WorkbenchServer server = new WorkbenchServer(Path.of("web"));
        server.start();
        System.out.println("server up on " + BASE);

        // ---- device.list
        Map<String, Object> devs = data(post("device.list", Map.of()));
        @SuppressWarnings("unchecked")
        List<Object> dl = (List<Object>) devs.get("devices");
        Map<String, Object> emu = null;
        for (Object o : dl) {
            @SuppressWarnings("unchecked")
            Map<String, Object> d = (Map<String, Object>) o;
            if (SERIAL.equals(d.get("serial"))) emu = d;
        }
        check(emu != null, "device.list shows " + SERIAL);
        check(emu != null && Boolean.TRUE.equals(emu.get("adbdRoot")),
                "emulator is rooted (android " + (emu == null ? "?" : emu.get("android")) + ")");
        @SuppressWarnings("unchecked")
        Map<String, Object> fs = emu == null ? null : (Map<String, Object>) emu.get("fridaServer");
        check(fs != null && Boolean.TRUE.equals(fs.get("running")), "frida-server running");

        // ---- catalog build
        Map<String, Object> cat = data(post("device.catalog",
                Map.of("serial", SERIAL, "refresh", true, "count", 5000)));
        int total = ((Number) cat.get("totalAll")).intValue();
        check(total > 200, "catalog built: " + total + " entries");
        @SuppressWarnings("unchecked")
        List<Object> entries = (List<Object>) cat.get("entries");
        boolean hasLib = false, hasProc = false, hasGms = false, svcFramework = false;
        int appCount = 0, sysCount = 0, svcCount = 0, svcHosted = 0;
        for (Object o : entries) {
            @SuppressWarnings("unchecked")
            Map<String, Object> e = (Map<String, Object>) o;
            if ("app".equals(e.get("kind"))) appCount++;
            if ("system-app".equals(e.get("kind"))) sysCount++;
            if ("lib".equals(e.get("kind"))) hasLib = true;
            if ("process".equals(e.get("kind"))) hasProc = true;
            if ("service".equals(e.get("kind"))) {
                svcCount++;
                if (e.get("host") != null) svcHosted++;
                if ("activity".equals(e.get("name")))
                    svcFramework = "system_server".equals(e.get("host"))
                            && String.valueOf(e.get("apkPath")).endsWith("framework.jar");
            }
            if (String.valueOf(e.get("name")).startsWith("com.android.")) hasGms = true;
        }
        check(appCount + sysCount > 50 && sysCount > 50,
                "packages > 50 (user " + appCount + " + system " + sysCount + ")");
        check(sysCount > 0 && hasGms, "system-app entries incl. com.android.*");
        check(hasLib && hasProc, "lib + process entries present");
        check(svcCount > 50 && svcHosted * 2 > svcCount && svcFramework,
                "binder services: " + svcCount + " (" + svcHosted
                        + " host-resolved), activity → system_server/framework.jar");

        // ---- filters + persistence + refresh diff
        Map<String, Object> apps = data(post("device.catalog",
                Map.of("serial", SERIAL, "kind", "app", "count", 5000)));
        boolean allApp = true;
        for (Object o : (List<?>) apps.get("entries"))
            if (!"app".equals(((Map<?, ?>) o).get("kind"))) allApp = false;
        check(allApp, "kind=app filter");
        Map<String, Object> f1 = data(post("device.catalog",
                Map.of("serial", SERIAL, "filter", "gms", "count", 5000)));
        boolean gmsOk = !((List<?>) f1.get("entries")).isEmpty();
        for (Object o : (List<?>) f1.get("entries")) {
            @SuppressWarnings("unchecked")
            Map<String, Object> fe = (Map<String, Object>) o;
            if (!String.valueOf(fe.getOrDefault("name", "")).toLowerCase().contains("gms")
                    && !String.valueOf(fe.getOrDefault("path", "")).toLowerCase().contains("gms"))
                gmsOk = false;
        }
        check(gmsOk, "filter=gms → matching entries");
        Map<String, Object> cat2 = data(post("device.catalog",
                Map.of("serial", SERIAL, "refresh", true)));
        check(cat2.get("diff") != null, "refresh returns diff " + cat2.get("diff"));

        // ---- browse
        @SuppressWarnings("unchecked")
        List<Object> browsed = dataList(post("device.browse",
                Map.of("serial", SERIAL, "path", "/system/lib64")));
        check(browsed.size() > 50 && browsed.stream()
                        .anyMatch(r -> String.valueOf(((Map<?, ?>) r).get("name")).endsWith(".so")),
                "browse /system/lib64 → " + browsed.size() + " entries");
        Map<String, Object> unsafe = post("device.browse",
                Map.of("serial", SERIAL, "path", "/system; rm -rf /"));
        check(Boolean.FALSE.equals(unsafe.get("ok"))
                        && String.valueOf(unsafe.get("error")).contains("E_SYNTAX"),
                "path injection rejected");

        // ---- components: declared manifest components of one package (DEVICE_SPEC §ops)
        Map<String, Object> comp = data(post("device.components",
                Map.of("serial", SERIAL, "pkg", "com.android.phone")));
        @SuppressWarnings("unchecked")
        Map<String, Object> ccounts = (Map<String, Object>) comp.get("counts");
        @SuppressWarnings("unchecked")
        List<Object> clist = (List<Object>) comp.get("components");
        boolean fqcnOk = !clist.isEmpty(), permOk = false;
        for (Object o : clist) {
            @SuppressWarnings("unchecked")
            Map<String, Object> c = (Map<String, Object>) o;
            String cls = String.valueOf(c.get("class"));
            if (cls.startsWith(".") || !cls.contains(".")) fqcnOk = false;   // relative form leaked
            if ("service".equals(c.get("kind")) && c.get("permission") != null) permOk = true;
        }
        check(Boolean.TRUE.equals(comp.get("found")) && ccounts.containsKey("service")
                        && ccounts.containsKey("receiver") && ccounts.containsKey("provider")
                        && ccounts.containsKey("activity"),
                "device.components com.android.phone → " + ccounts);
        check(fqcnOk, "every component class fully qualified (" + clist.size() + " components)");
        check(permOk, "resolver-table permission carried onto a declared service");
        check(comp.get("versionName") != null
                        && String.valueOf(comp.get("apkPath")).endsWith(".apk"),
                "package identity from the same dump: v" + comp.get("versionName")
                        + " " + comp.get("apkPath"));
        // a package that does not exist is an empty result, never an exception
        Map<String, Object> bogus = data(post("device.components",
                Map.of("serial", SERIAL, "pkg", "com.no.such.package.here")));
        check(Boolean.FALSE.equals(bogus.get("found"))
                        && ((List<?>) bogus.get("components")).isEmpty()
                        && Boolean.TRUE.equals(bogus.get("partial")),
                "unknown package → clean empty/partial result");
        Map<String, Object> badPkg = post("device.components",
                Map.of("serial", SERIAL, "pkg", "com.android.phone; id"));
        check(Boolean.FALSE.equals(badPkg.get("ok"))
                        && String.valueOf(badPkg.get("error")).contains("E_SYNTAX"),
                "package-name injection rejected");

        // ---- pull the test app's on-device APK → JEB target
        String apkPath = null;
        for (Object o : entries) {
            @SuppressWarnings("unchecked")
            Map<String, Object> e = (Map<String, Object>) o;
            if ("com.google.android.gms.optional_vision_ocr".equals(e.get("name"))) {
                apkPath = String.valueOf(e.get("path"));
                break;
            }
        }
        if (apkPath == null) {
            for (Object o : (List<?>) data(post("device.catalog",
                    Map.of("serial", SERIAL, "filter", "vision_ocr", "count", 10))).get("entries"))
                apkPath = String.valueOf(((Map<?, ?>) o).get("path"));
        }
        check(apkPath != null && apkPath.endsWith(".apk"), "test app found in catalog: " + apkPath);
        Map<String, Object> pulled = data(post("device.pull",
                Map.of("serial", SERIAL, "path", apkPath)));
        check(pulled.get("file") != null && pulled.get("sha256") != null,
                "device.pull → " + pulled.get("bytes") + " bytes, sha256 "
                        + String.valueOf(pulled.get("sha256")).substring(0, 12) + "…");
        // decision 16 Step 1: the reported file lives in the target store, and its pull provenance
        // is carried beside the store jdb2 (survives the reaping of the staging copy).
        String pf = String.valueOf(pulled.get("file"));
        check(pf.contains("/targets/"), "pull landed in the target store, not pulled/ (decision 16): " + pf);
        java.nio.file.Path storeArt = java.nio.file.Path.of(pf);
        String pbase = storeArt.getFileName().toString();
        String pbaseNoExt = pbase.contains(".") ? pbase.substring(0, pbase.lastIndexOf('.')) : pbase;
        boolean storeProv = storeArt.getParent() != null
                && java.nio.file.Files.isRegularFile(storeArt.getParent().resolve(pbaseNoExt + ".prov.json"));
        check(storeProv, "pull provenance carried into the store (" + pbaseNoExt + ".prov.json)");
        // decision 16 Step 3: the staging copy in pulled/ is reaped once the store adopts the bytes.
        String home = System.getenv("WORKBENCH_HOME");
        if (home != null) {
            java.nio.file.Path staged = java.nio.file.Path.of(home, "pulled",
                    java.nio.file.Path.of(apkPath).getFileName().toString());
            check(!java.nio.file.Files.exists(staged), "staging copy reaped from pulled/ after adopt");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> tgt = (Map<String, Object>) pulled.get("target");
        String tid = String.valueOf(tgt.get("id"));
        try {
            awaitReady(tid);
            @SuppressWarnings("unchecked")
            List<Object> roots = (List<Object>) data(post("symbol.tree", Map.of("target", tid))).get("items");
            check(!roots.isEmpty(), "pulled target browsable via JEB (tree roots: " + roots.size() + ")");
            // decision 16 Step 2: a re-pull of the same path dedupes off the catalog (no second
            // device transfer, no second store dir) even though the staging copy is gone.
            Map<String, Object> repull = data(post("device.pull", Map.of("serial", SERIAL, "path", apkPath)));
            check(Boolean.TRUE.equals(repull.get("skippedPull")),
                    "re-pull dedupes via the store (skippedPull) — no second device transfer");
            check(pf.equals(String.valueOf(repull.get("file"))), "re-pull resolves to the same store artifact");
        } finally {
            try { post("target.close", Map.of("target", tid)); } catch (Exception ignored) {}
        }

        // decision 16 Step 2 (closed branch): a re-pull after the target is closed reopens it FROM
        // the store — the bytes are gone from pulled/, so this proves the catalog dedupe, not a
        // staging-file check, drives it. No device transfer.
        Map<String, Object> reopen = data(post("device.pull", Map.of("serial", SERIAL, "path", apkPath)));
        check(Boolean.TRUE.equals(reopen.get("skippedPull")) && reopen.get("target") != null,
                "re-pull after close reopens from the store (skippedPull, no device transfer)");
        check(pf.equals(String.valueOf(reopen.get("file"))), "reopen resolves to the same store artifact");
        @SuppressWarnings("unchecked")
        Map<String, Object> rtgt = (Map<String, Object>) reopen.get("target");
        try { awaitReady(String.valueOf(rtgt.get("id"))); }
        finally { try { post("target.close", Map.of("target", rtgt.get("id"))); } catch (Exception ignored) {} }

        // decision 16 sub-decision (a): a non-openable pull (no engine for elf) keeps its bytes in
        // pulled/ — it is NOT reaped (nothing adopted it) and reports the honest open error.
        Map<String, Object> lib = data(post("device.pull", Map.of("serial", SERIAL, "path", "/system/lib64/libc.so")));
        check(Boolean.FALSE.equals(lib.get("opened"))
                        && String.valueOf(lib.get("openError")).contains("E_UNSUPPORTED"),
                "non-openable pull → E_UNSUPPORTED, not adopted");
        check(String.valueOf(lib.get("file")).contains("/pulled/")
                        && java.nio.file.Files.exists(java.nio.file.Path.of(String.valueOf(lib.get("file")))),
                "non-openable pull keeps its bytes in pulled/ (sub-decision a)");

        // ---- annotations: human + mission + review
        Map<String, Object> ann = data(post("device.annotate",
                Map.of("serial", SERIAL, "kind", "app", "name", "com.google.android.gms.optional_vision_ocr",
                        "note", "M8 test app — vision OCR module")));
        check(!((List<?>) ann.get("annotations")).isEmpty(), "human annotation applied");
        Map<String, Object> mission = data(post("device.spawn-mission",
                Map.of("serial", SERIAL, "goal", "gms vision")));
        String agentId = String.valueOf(mission.get("agentId"));
        String state = "";
        for (int i = 0; i < 60 && !"done".equals(state); i++) {
            Thread.sleep(1_000);
            @SuppressWarnings("unchecked")
            List<Object> ms = dataList(post("agent.missions", Map.of()));
            for (Object o : ms)
                if (mission.get("id").equals(((Map<?, ?>) o).get("id")))
                    state = String.valueOf(((Map<?, ?>) o).get("state"));
        }
        check("done".equals(state), "catalog mission completed");
        @SuppressWarnings("unchecked")
        List<Object> props = dataList(post("agent.proposals", Map.of("mission", agentId, "kind", "catalog")));
        check(!props.isEmpty(), "catalog annotation proposals: " + props.size());
        @SuppressWarnings("unchecked")
        Map<String, Object> p0 = (Map<String, Object>) props.get(0);
        Map<String, Object> settled = data(post("agent.review",
                Map.of("id", p0.get("id"), "action", "accept")));
        check("accepted".equals(settled.get("state")), "review accept annotation proposal");
        Map<String, Object> cat3 = data(post("device.catalog",
                Map.of("serial", SERIAL, "filter", String.valueOf(p0.get("name")), "count", 5)));
        boolean annotated = false;
        for (Object o : (List<?>) cat3.get("entries")) {
            @SuppressWarnings("unchecked")
            Map<String, Object> e = (Map<String, Object>) o;
            if (p0.get("name").equals(e.get("name")) && !((List<?>) e.get("annotations")).isEmpty())
                annotated = true;
        }
        check(annotated, "accepted annotation persisted on the catalog entry");

        System.out.println();
        if (failures > 0) {
            System.out.println("M8 IT FAILED: " + failures + "/" + checks + " checks failed");
            System.exit(1);
        }
        System.out.println("M8 IT PASSED: " + checks + " checks green");
        System.exit(0);
    }

    private static void awaitReady(String tid) throws Exception {
        long deadline = System.currentTimeMillis() + 600_000;
        while (System.currentTimeMillis() < deadline) {
            Map<String, Object> info = data(post("target.info", Map.of("target", tid)));
            String st = String.valueOf(info.get("state"));
            if ("ready".equals(st)) return;
            if ("error".equals(st)) throw new IllegalStateException("analysis failed: " + info.get("error"));
            Thread.sleep(2_000);
        }
        throw new IllegalStateException("analysis timeout for " + tid);
    }

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
    private static Map<String, Object> data(Map<String, Object> resp) {
        if (!Boolean.TRUE.equals(resp.get("ok")))
            throw new IllegalStateException("op failed: " + resp.get("error"));
        return (Map<String, Object>) resp.get("data");
    }

    @SuppressWarnings("unchecked")
    private static List<Object> dataList(Map<String, Object> resp) {
        if (!Boolean.TRUE.equals(resp.get("ok")))
            throw new IllegalStateException("op failed: " + resp.get("error"));
        return (List<Object>) resp.get("data");
    }
}
