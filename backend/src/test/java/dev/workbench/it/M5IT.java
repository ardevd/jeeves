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
 * M5 integration test (plain main-class, no JUnit): the Runs ops live against
 * emulator-5554 — provision frida-server, attach the GMS test module, hook
 * AccountRemovedBroadcastReceiver.onReceive, trigger it by broadcast, assert
 * recorded hits (args + backtrace), run history, diff, coverage, SSE relay.
 * Prints PASS/FAIL; exit 1 on failure. Run: gradle m5IT (needs the emulator).
 */
public final class M5IT {

    private static final String SERIAL = "emulator-5554";
    private static final String PKG = "com.google.android.gms.optional_vision_ocr";
    private static final String RECEIVER =
            "com.google.android.gms.optional_vision_ocr/com.google.android.libraries.phenotype.client.stable.AccountRemovedBroadcastReceiver";
    private static final String ACTION = "android.accounts.action.ACCOUNT_REMOVED";
    private static final String ONRECEIVE =
            "Lcom/google/android/libraries/phenotype/client/stable/AccountRemovedBroadcastReceiver;->onReceive(Landroid/content/Context;Landroid/content/Intent;)V";

    private static int failures = 0, checks = 0;
    private static final HttpClient http = HttpClient.newHttpClient();
    private static final String BASE = "http://127.0.0.1:" + WorkbenchServer.PORT;

    public static void main(String[] args) throws Exception {
        try {
            run();
        } catch (Throwable t) {
            System.out.println("M5 IT FAILED with exception: " + t);
            t.printStackTrace(System.out);
            System.exit(1);
        }
    }

    private static void run() throws Exception {
        WorkbenchServer server = new WorkbenchServer(Path.of("web"));
        server.start();
        System.out.println("server up on " + BASE);

        // ---- devices
        Map<String, Object> devs = data(post("runs.devices", Map.of()));
        @SuppressWarnings("unchecked")
        List<Object> dl = (List<Object>) devs.get("devices");
        Map<String, Object> emu = null;
        for (Object o : dl) {
            @SuppressWarnings("unchecked")
            Map<String, Object> d = (Map<String, Object>) o;
            if (SERIAL.equals(d.get("serial"))) emu = d;
        }
        check(emu != null, "runs.devices lists " + SERIAL);
        check(emu != null && Boolean.TRUE.equals(emu.get("adbdRoot")) && Boolean.TRUE.equals(emu.get("arm64")),
                "emulator is root + arm64 (android " + (emu == null ? "?" : emu.get("android")) + ")");

        // ---- real target for the origSig binding
        Map<String, Object> opened = data(post("target.open",
                Map.of("path", System.getProperty("user.home") + "/jeb/test_visionocr.apk")));
        String tid = String.valueOf(opened.get("id"));
        try {
            awaitReady(tid);

            // ---- mock bp.add → E_UNSUPPORTED
            Map<String, Object> mockBp = post("runs.bp.add",
                    Map.of("target", "t1", "symbol", "onMessage"));
            check(Boolean.FALSE.equals(mockBp.get("ok"))
                            && String.valueOf(mockBp.get("error")).contains("E_UNSUPPORTED"),
                    "mock-target runs.bp.add → E_UNSUPPORTED");

            // ---- bp.add on the receiver's onReceive via the JEB target
            Map<String, Object> bp = data(post("runs.bp.add",
                    Map.of("target", tid, "symbol", ONRECEIVE)));
            check(bp.get("id") != null && ONRECEIVE.equals(bp.get("origSig")),
                    "runs.bp.add onReceive → " + bp.get("id"));
            @SuppressWarnings("unchecked")
            List<Object> bpl = dataList(post("runs.bp.list", Map.of("target", tid)));
            check(bpl.size() == 1 && Boolean.TRUE.equals(((Map<?, ?>) bpl.get(0)).get("enabled")),
                    "runs.bp.list → 1 enabled bp");

            // ---- native breakpoint: a libc export, resolved by frida (no engine), installed at attach
            Map<String, Object> nbp = data(post("runs.bp.add",
                    Map.of("target", tid, "module", "libc.so", "export", "read")));
            check("native".equals(nbp.get("kind")) && "native:libc.so!read".equals(nbp.get("origSig"))
                            && "libc.so".equals(nbp.get("module")) && "read".equals(nbp.get("export")),
                    "runs.bp.add native libc.so!read → " + nbp.get("id"));

            // ---- SSE subscription (verify live relay later)
            StringBuilder sse = new StringBuilder();
            Thread sseThread = new Thread(() -> {
                try {
                    HttpResponse<java.io.InputStream> resp = http.send(
                            HttpRequest.newBuilder(URI.create(BASE + "/api/events")).build(),
                            HttpResponse.BodyHandlers.ofInputStream());
                    try (java.io.BufferedReader r = new java.io.BufferedReader(
                            new java.io.InputStreamReader(resp.body()))) {
                        String line;
                        int n = 0;
                        while ((line = r.readLine()) != null && n++ < 400) sse.append(line).append('\n');
                    }
                } catch (Exception ignored) {}
            });
            sseThread.setDaemon(true);
            sseThread.start();

            // ---- attach run #1
            Map<String, Object> at = data(post("runs.attach",
                    Map.of("device", SERIAL, "pkg", PKG, "target", tid,
                            "coldAction", ACTION, "coldComponent", RECEIVER)));
            String sid = String.valueOf(at.get("session"));
            String ex1 = String.valueOf(at.get("execId"));
            check(at.get("pid") != null, "runs.attach → session " + sid + " pid " + at.get("pid")
                    + " run " + ex1);

            // ---- trigger the receiver
            trigger();
            Thread.sleep(3_000);

            // ---- hits
            Map<String, Object> hits = data(post("runs.hits", Map.of("run", ex1)));
            check(((Number) hits.get("total")).intValue() >= 1,
                    "runs.hits → " + hits.get("total") + " hit(s)");
            @SuppressWarnings("unchecked")
            List<Object> hrows = (List<Object>) hits.get("hits");
            // the native BP fired too (libc.so read is called during the receiver's startup I/O)
            boolean nativeHit = false;
            for (Object o : hrows)
                if (o instanceof Map<?, ?> m && "native:libc.so!read".equals(m.get("addr"))) { nativeHit = true; break; }
            check(nativeHit, "native hit captured (libc.so!read)");
            // find the Java onReceive hit specifically — the native BP fires too, so it is no longer
            // guaranteed to be hrows.get(0) (native read fires during the receiver's startup)
            Map<String, Object> h0 = null;
            for (Object o : hrows)
                if (o instanceof Map<?, ?> m && ONRECEIVE.equals(m.get("addr"))) {
                    @SuppressWarnings("unchecked") Map<String, Object> mm = (Map<String, Object>) m;
                    h0 = mm; break;
                }
            if (h0 != null) {
                @SuppressWarnings("unchecked")
                List<Object> frames = (List<Object>) h0.get("frames");
                check(frames != null && !frames.isEmpty(),
                        "onReceive hit has addr + backtrace (" + (frames == null ? 0 : frames.size()) + " frames)");
                System.out.println("  hit: " + Json.write(h0.get("args")).substring(0,
                        Math.min(160, Json.write(h0.get("args")).length())));
                if (frames != null && !frames.isEmpty())
                    System.out.println("  frames: " + frames.get(Math.min(2, frames.size() - 1)));
            }

            // ---- run #2 + diff
            Map<String, Object> at2 = data(post("runs.attach",
                    Map.of("device", SERIAL, "pkg", PKG, "target", tid,
                            "coldAction", ACTION, "coldComponent", RECEIVER)));
            String ex2 = String.valueOf(at2.get("execId"));
            trigger();
            Thread.sleep(3_000);
            Map<String, Object> diff = data(post("runs.diff", Map.of("a", ex1, "b", ex2)));
            @SuppressWarnings("unchecked")
            List<Object> both = (List<Object>) diff.get("both");
            check(!both.isEmpty(), "runs.diff " + ex1 + " vs " + ex2 + " → both covered "
                    + ((Map<?, ?>) both.get(0)).get("label"));

            // ---- runs + coverage
            @SuppressWarnings("unchecked")
            List<Object> runs = dataList(post("runs.runs", Map.of()));
            check(runs.size() >= 2, "runs.runs → " + runs.size() + " runs recorded");
            Map<String, Object> cov = data(post("runs.coverage", Map.of("run", ex1)));
            check(((Number) cov.get("count")).intValue() >= 1,
                    "runs.coverage " + ex1 + " → " + cov.get("count") + " method(s)");

            // ---- SSE relay saw a runs-channel hit event
            Thread.sleep(2_000);
            check(sse.toString().contains("event: runs") && sse.toString().contains("\"type\":\"hit\""),
                    "SSE relayed a live hit on the runs channel");

            // ---- bp toggle + detach
            Map<String, Object> tog = data(post("runs.bp.toggle", Map.of("id", bp.get("id"))));
            check(Boolean.FALSE.equals(tog.get("enabled")), "runs.bp.toggle → disabled");
            Map<String, Object> det = data(post("runs.detach", Map.of("session", sid)));
            check(!((List<?>) det.get("detached")).isEmpty(), "runs.detach → " + det.get("detached"));
        } finally {
            try { post("runs.detach", Map.of()); } catch (Exception ignored) {}
            try { post("target.close", Map.of("target", tid)); } catch (Exception ignored) {}
        }

        System.out.println();
        if (failures > 0) {
            System.out.println("M5 IT FAILED: " + failures + "/" + checks + " checks failed");
            System.exit(1);
        }
        System.out.println("M5 IT PASSED: " + checks + " checks green");
        System.exit(0);
    }

    private static void trigger() throws Exception {
        new ProcessBuilder("adb", "-s", SERIAL, "shell", "am", "broadcast",
                "-a", ACTION, "-n", RECEIVER).start().waitFor();
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
