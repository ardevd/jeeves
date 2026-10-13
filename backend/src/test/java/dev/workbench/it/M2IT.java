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
 * M2 integration test (plain main-class, no JUnit — same style as M1IT/ParityTest).
 * Starts the real HTTP server in-process, opens the small test APK, then over the wire:
 *   1. code.smali on a real class + a real method (offsets + opcode bytes rows)
 *   2. rename a method (actor human) → history.list shows it tagged human
 *   3. history.undo restores the original name; history.redo re-applies
 *   4. rename + target.save → close → reopen the .jdb2 → the rename SURVIVED
 *   5. mock target: code.smali fixture rows + rename/history round-trip still work
 * Prints PASS/FAIL per step; exit 1 on any failure.
 *
 * Run: gradle m2IT (adds the engine jars + JVM flags when backend/engine is present).
 */
public final class M2IT {

    private static int failures = 0, checks = 0;
    private static final HttpClient http = HttpClient.newHttpClient();
    private static final String BASE = "http://127.0.0.1:" + WorkbenchServer.PORT;
    /** the in-process server — a field, so the one `finally` can stop whichever instance is current
     *  (step 4b replaces it) when a check throws: the HTTP dispatcher is a non-daemon thread, and an
     *  exception that escaped main left the JVM alive for gradle to wait on (2026-10-07: 13 minutes) */
    private static WorkbenchServer server;

    public static void main(String[] args) {
        int code = 1;
        Throwable abort = null;
        try {
            run(args);
            code = failures > 0 ? 1 : 0;
        } catch (Throwable t) {
            abort = t;
            System.out.println("ABORT " + t);
            t.printStackTrace(System.out);
        } finally {
            stopServer();
        }
        System.out.println();
        if (abort != null) System.out.println("M2 IT ABORTED after " + checks + " checks (" + failures + " failed): " + abort);
        else System.out.println(code == 0 ? "M2 IT PASSED: " + checks + " checks green" : "M2 IT FAILED: " + failures + "/" + checks + " checks failed");
        System.exit(code);   // whatever thread is still up: the test is over
    }

    private static void stopServer() {
        WorkbenchServer s = server;
        server = null;
        if (s != null) try { s.stop(); } catch (Throwable t) { System.out.println("  (server stop: " + t + ")"); }
    }

    private static void run(String[] args) throws Exception {
        /* the apk: an argument, else M2_APK (the harness check for the failure path points it at a
           missing file), else the IT fixture */
        String apk = args.length > 0 ? args[0]
                : System.getenv("M2_APK") != null && !System.getenv("M2_APK").isBlank() ? System.getenv("M2_APK")
                : System.getProperty("user.home") + "/jeb/test_visionocr.apk";
        server = new WorkbenchServer(Path.of("web"));
        server.start();
        System.out.println("server up on " + BASE + " · apk=" + apk);

        Map<String, Object> state = data(post("workspace.state", Map.of()));
        @SuppressWarnings("unchecked")
        List<Object> targets = (List<Object>) state.get("targets");
        check(targets.size() == 4, "workspace.state → 4 mock targets (got " + targets.size() + ")");

        // ---- open + await analysis
        Map<String, Object> opened = data(post("target.open", Map.of("path", apk)));
        String tid = String.valueOf(opened.get("id"));
        String classAddr = null, classFqcn = null, methodAddr = null, methodName = null;
        Map<String, Object> outline = null;
        try {
            awaitReady(tid);
            System.out.println("  target " + tid + " ready");

            // ---- pick a real class with a non-constructor method
            @SuppressWarnings("unchecked")
            List<Object> syms = dataList(post("symbol.search", Map.of("target", tid, "query", "a")));
            for (int i = 0; i < Math.min(15, syms.size()) && classAddr == null; i++) {
                @SuppressWarnings("unchecked")
                Map<String, Object> cand = (Map<String, Object>) syms.get(i);
                if (!"class".equals(cand.get("kind"))) continue;
                Map<String, Object> o;
                try {
                    o = data(post("code.outline", Map.of("target", tid, "class", cand.get("addr"))));
                } catch (IllegalStateException e) { continue; }
                @SuppressWarnings("unchecked")
                List<Object> ms = (List<Object>) o.get("methods");
                for (Object mo : ms) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> mm = (Map<String, Object>) mo;
                    String nm = String.valueOf(mm.get("name"));
                    if (nm.startsWith("<") || !nm.matches("[A-Za-z_$][A-Za-z0-9_$]*")) continue;
                    // require a real body: the method's smali block must contain instructions
                    try {
                        Map<String, Object> probe = data(post("code.smali",
                                Map.of("target", tid, "symbol", mm.get("addr"))));
                        boolean hasInsn = false;
                        for (Object ro : (List<?>) probe.get("rows"))
                            if ("insn".equals(((Map<?, ?>) ro).get("kind"))) { hasInsn = true; break; }
                        if (!hasInsn) continue;
                    } catch (IllegalStateException e) { continue; }
                    classAddr = String.valueOf(cand.get("addr"));
                    classFqcn = String.valueOf(cand.get("fqcn"));
                    methodAddr = String.valueOf(mm.get("addr"));
                    methodName = nm;
                    outline = o;
                    break;
                }
            }
            check(classAddr != null, "found class + renamable method (" + classFqcn + "." + methodName + ")");

            // ---- 1. code.smali: class listing
            Map<String, Object> sm = data(post("code.smali", Map.of("target", tid, "symbol", classAddr)));
            @SuppressWarnings("unchecked")
            List<Object> rows = (List<Object>) sm.get("rows");
            check(rows.size() > 5, "code.smali class → " + rows.size() + " rows");
            int insnRows = 0;
            String firstInsn = null;
            boolean sawDirective = false, sawLabelOk = true;
            for (Object ro : rows) {
                @SuppressWarnings("unchecked")
                Map<String, Object> row = (Map<String, Object>) ro;
                String kind = String.valueOf(row.get("kind"));
                if ("directive".equals(kind) && String.valueOf(row.get("text")).startsWith(".method"))
                    sawDirective = true;
                if ("insn".equals(kind)) {
                    insnRows++;
                    if (firstInsn == null) firstInsn = row.get("offset") + "  ["
                            + row.get("bytes") + "]  " + String.valueOf(row.get("text")).trim();
                    if (!String.valueOf(row.get("offset")).matches("[0-9a-f]{4}")) sawLabelOk = false;
                    if (String.valueOf(row.get("bytes")).isEmpty()) sawLabelOk = false;
                }
            }
            check(insnRows > 0 && sawDirective, "smali has .method directives + " + insnRows + " insn rows");
            check(sawLabelOk, "every insn row carries 4-hex offset + opcode bytes");
            System.out.println("  first insn: " + firstInsn);

            // ---- code.smali: single method
            Map<String, Object> smm = data(post("code.smali", Map.of("target", tid, "symbol", methodAddr)));
            @SuppressWarnings("unchecked")
            List<Object> mrows = (List<Object>) smm.get("rows");
            check(!mrows.isEmpty()
                    && String.valueOf(((Map<?, ?>) mrows.get(0)).get("text")).startsWith(".method"),
                    "code.smali method → " + mrows.size() + " rows (starts with .method)");

            // ---- 2. rename (human) → history tagged human
            String probe = "m2itProbe";
            Map<String, Object> ren = data(post("rename",
                    Map.of("target", tid, "symbol", methodAddr, "name", probe)));
            check(probe.equals(ren.get("newName")) && ren.get("newAddr") != null,
                    "rename " + methodName + " → " + probe + " (newAddr=" + ren.get("newAddr") + ")");
            /* the renamed method's OWN identity, for every later step: a bare name is ambiguous once JEB
               carries the rename across the override set (47 createFromParcel implementations answered to
               m2itProbe on the reopened database) */
            String probeAddr = String.valueOf(ren.get("newAddr"));
            @SuppressWarnings("unchecked")
            List<Object> hist = dataList(post("history.list", Map.of("target", tid)));
            check(!hist.isEmpty(), "history.list → " + hist.size() + " entries");
            @SuppressWarnings("unchecked")
            Map<String, Object> h0 = (Map<String, Object>) hist.get(0);
            check("rename".equals(h0.get("op")) && "human".equals(h0.get("actor"))
                    && probe.equals(h0.get("newName")) && methodName.equals(h0.get("oldName")),
                    "history entry: rename " + methodName + " → " + probe + " actor=human");

            // ---- 3. undo / redo
            Map<String, Object> undo = data(post("history.undo", Map.of("target", tid)));
            @SuppressWarnings("unchecked")
            Map<String, Object> uentry = (Map<String, Object>) undo.get("undone");
            check(Boolean.TRUE.equals(uentry.get("undone")), "history.undo → entry marked undone");
            Map<String, Object> out2 = data(post("code.outline", Map.of("target", tid, "class", classAddr)));
            check(outlineHas(out2, methodName) && !outlineHas(out2, probe),
                    "after undo: outline shows " + methodName + " again");
            Map<String, Object> redo = data(post("history.redo", Map.of("target", tid)));
            @SuppressWarnings("unchecked")
            Map<String, Object> rentry = (Map<String, Object>) redo.get("redone");
            check(Boolean.FALSE.equals(rentry.get("undone")), "history.redo → entry restored");
            Map<String, Object> out3 = data(post("code.outline", Map.of("target", tid, "class", classAddr)));
            check(outlineHas(out3, probe), "after redo: outline shows " + probe + " again");

            // leave renamed, then save → close → reopen: persistence proof
            Map<String, Object> saved = data(post("target.save", Map.of("target", tid)));
            check(Boolean.TRUE.equals(saved.get("saved")), "target.save → .jdb2 persisted");
            String jdb2 = String.valueOf(saved.get("jdb2"));   // the database this target owns — its History sidecar lives beside it
            data(post("target.close", Map.of("target", tid)));

            Map<String, Object> reopened = data(post("target.open", Map.of("path", apk)));
            tid = String.valueOf(reopened.get("id"));
            awaitReady(tid);
            Map<String, Object> out4 = data(post("code.outline", Map.of("target", tid, "class", classAddr)));
            check(outlineHas(out4, probe),
                    "reopen .jdb2: rename SURVIVED (" + classFqcn + "." + probe + ")");
            @SuppressWarnings("unchecked")
            List<Object> hist2 = dataList(post("history.list", Map.of("target", tid)));
            check(!hist2.isEmpty(), "history reloaded from sidecar (" + hist2.size() + " entries)");

            // cleanup: rename back (by the method's own identity, never a bare name) + save — the
            // hermetic home's database carries the test's edits, never the fixture apk's own
            Map<String, Object> back = data(post("rename",
                    Map.of("target", tid, "symbol", probeAddr, "name", methodName)));
            check(methodName.equals(back.get("newName")), "cleanup rename back → " + methodName + " (by " + probeAddr + ")");
            data(post("target.save", Map.of("target", tid)));

            // ---- 4b. dirty replay (crash-window recovery): a history entry
            // newer than the jdb2's last save is re-applied on open. The entry
            // is appended to history.json directly (simulating an edit made
            // just before a crash — the jdb2 never saw it).
            data(post("target.close", Map.of("target", tid)));
            /* the History sidecar of THIS target: beside its database in the hermetic home (the
               pre-2026-09 location next to the original apk no longer exists) */
            java.nio.file.Path hfile = sidecarHistory(jdb2, apk);
            check(hfile != null, "the target's History sidecar found beside its database: " + hfile);
            if (hfile != null) {
                @SuppressWarnings("unchecked")
                java.util.List<Object> hjson = (java.util.List<Object>) Json.parse(
                        java.nio.file.Files.readString(hfile));
                Map<String, Object> fake = new LinkedHashMap<>();
                fake.put("id", tid + "-hrecovery");
                fake.put("op", "rename");
                fake.put("actor", "recovery-test");
                fake.put("kind", "method");
                fake.put("addr", methodAddr);
                fake.put("newAddr", methodAddr);
                fake.put("oldName", methodName);
                fake.put("newName", "zzRecover");
                fake.put("target", tid);
                fake.put("ts", java.time.Instant.now().plusSeconds(2).toString());   // newer than the save, whatever the file system's mtime resolution
                fake.put("undone", false);
                hjson.add(fake);
                Thread.sleep(1_200);
                java.nio.file.Files.writeString(hfile, Json.write(hjson));
                stopServer();
                server = new WorkbenchServer(Path.of("web"));
                server.start();
                Map<String, Object> ro = data(post("target.open", Map.of("path", apk)));
                tid = String.valueOf(ro.get("id"));
                awaitReady(tid);
                Map<String, Object> out5 = data(post("code.outline",
                        Map.of("target", tid, "class", classAddr)));
                check(outlineHas(out5, "zzRecover"),
                        "dirty replay: unsaved rename recovered on open");
                Map<String, Object> info5 = data(post("target.info", Map.of("target", tid)));
                check(info5.get("recoveredEdits") != null
                                && ((Number) info5.get("recoveredEdits")).intValue() >= 1,
                        "target.info reports recoveredEdits=" + info5.get("recoveredEdits"));
                // cleanup: rename back by the recovered method's identity + save (the hermetic home's database)
                String recAddr = methodAddr.replace("->" + methodName + "(", "->zzRecover(");
                Map<String, Object> back2 = data(post("rename", Map.of("target", tid, "symbol", recAddr, "name", methodName)));
                check(methodName.equals(back2.get("newName")), "cleanup rename back → " + methodName + " (by " + recAddr + ")");
                data(post("target.save", Map.of("target", tid)));
            }
        } finally {
            try { post("target.close", Map.of("target", tid)); } catch (Exception ignored) {}
        }

        // ---- 5. mock target: smali fixture + history round-trip
        data(post("target.switch", Map.of("target", "t1")));
        Map<String, Object> mockSm = data(post("code.smali",
                Map.of("target", "t1", "symbol", "MessageSyncHandler")));
        @SuppressWarnings("unchecked")
        List<Object> mrows2 = (List<Object>) mockSm.get("rows");
        boolean hasInvoke = false;
        for (Object ro : mrows2)
            if (String.valueOf(((Map<?, ?>) ro).get("text")).contains("invoke-virtual")) hasInvoke = true;
        check(mrows2.size() > 40 && hasInvoke,
                "mock code.smali → " + mrows2.size() + " fixture rows (invoke-virtual present)");
        Map<String, Object> mren = data(post("rename",
                Map.of("target", "t1", "symbol", "onMessage", "name", "onEnvelope2")));
        check(Boolean.TRUE.equals(mren.get("applied")), "mock rename applied (human)");
        @SuppressWarnings("unchecked")
        List<Object> mhist = dataList(post("history.list", Map.of("target", "t1")));
        check(!mhist.isEmpty() && "onEnvelope2".equals(((Map<?, ?>) mhist.get(0)).get("newName")),
                "mock history.list shows the rename");
        Map<String, Object> mund = data(post("history.undo", Map.of("target", "t1")));
        check(((Map<?, ?>) mund.get("undone")).get("id") != null, "mock history.undo works");
    }

    // ------------------------------------------------------------ helpers

    /** The target's History sidecar: `<jdb2>.workbench/history.json`, or the artifact copy's
     *  `<apk>.workbench/` beside the database, or any `*.workbench/history.json` in the database's
     *  folder (the layout the server uses today — a hermetic home per IT). Null when none. */
    private static java.nio.file.Path sidecarHistory(String jdb2, String apk) {
        if (jdb2 == null || "null".equals(jdb2)) return null;
        java.io.File db = new java.io.File(jdb2);
        java.util.List<java.io.File> cands = new java.util.ArrayList<>();
        cands.add(new java.io.File(db.getAbsolutePath() + ".workbench"));
        cands.add(new java.io.File(db.getParentFile(), new java.io.File(apk).getName() + ".workbench"));
        java.io.File[] siblings = db.getParentFile() == null ? null : db.getParentFile().listFiles();
        if (siblings != null) for (java.io.File d : siblings) if (d.isDirectory() && d.getName().endsWith(".workbench")) cands.add(d);
        for (java.io.File c : cands) { java.io.File h = new java.io.File(c, "history.json"); if (h.isFile()) return h.toPath(); }
        return null;
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

    private static boolean outlineHas(Map<String, Object> outline, String methodName) {
        Object ms = outline.get("methods");
        if (ms instanceof List<?> l)
            for (Object o : l)
                if (o instanceof Map<?, ?> m && methodName.equals(m.get("name"))) return true;
        return false;
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
                .timeout(java.time.Duration.ofSeconds(180))   // a stalled op fails the check instead of hanging the chain (the gradle task is bounded too)
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
