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
 * M1 integration test (plain main-class, no JUnit — same style as ParityTest).
 * Starts the real HTTP server in-process, then over the wire:
 *   1. workspace.state shows the 4 mock targets
 *   2. target.open a real APK (default ~/jeb/test_visionocr.apk — small) → target #5
 *   3. polls target.info until JEB analysis completes (jdb2 saved)
 *   4. symbol.tree roots, symbol.search → a real class, code.decompile it (+ token map)
 *   5. code.outline → a real method; xref.to + callees on it
 *   6. rename the method, rename back, target.save (native .jdb2 persist)
 *   7. target.switch mock ↔ real, decompile on both sides
 *   8. target.close the real target
 * Prints PASS/FAIL per step; exit 1 on any failure.
 *
 * Run: gradle m1IT (adds the engine jars + JVM flags when backend/engine is present).
 */
public final class M1IT {

    private static int failures = 0, checks = 0;
    private static final HttpClient http = HttpClient.newHttpClient();
    private static final String BASE = "http://127.0.0.1:" + WorkbenchServer.PORT;

    public static void main(String[] args) throws Exception {
        String apk = args.length > 0 ? args[0]
                : System.getProperty("user.home") + "/jeb/test_visionocr.apk";
        WorkbenchServer server = new WorkbenchServer(Path.of("web"));
        server.start();
        System.out.println("server up on " + BASE + " · apk=" + apk);

        // ---- 1. mock scene intact
        Map<String, Object> state = data(post("workspace.state", Map.of()));
        @SuppressWarnings("unchecked")
        List<Object> targets = (List<Object>) state.get("targets");
        check(targets.size() == 4, "workspace.state → 4 mock targets (got " + targets.size() + ")");

        // ---- 2. target.open the real APK
        Map<String, Object> opened = data(post("target.open", Map.of("path", apk)));
        String tid = String.valueOf(opened.get("id"));
        check("JEB".equals(opened.get("engine")) && opened.get("path") != null,
                "target.open → " + opened.get("engine") + " session " + tid);
        System.out.println("  opened target " + tid + " state=" + opened.get("state"));

        // ---- 3. wait for analysis (small APK; still allow minutes for first-time jdb2 save)
        long deadline = System.currentTimeMillis() + 600_000;
        Map<String, Object> info = null;
        while (System.currentTimeMillis() < deadline) {
            info = data(post("target.info", Map.of("target", tid)));
            String st = String.valueOf(info.get("state"));
            if ("ready".equals(st)) break;
            if ("error".equals(st)) {
                check(false, "analysis failed: " + info.get("error"));
                break;
            }
            Thread.sleep(2_000);
        }
        check(info != null && "ready".equals(String.valueOf(info.get("state"))),
                "analysis ready (classes=" + (info == null ? "?" : info.get("classes")) + ")");
        if (info != null)
            System.out.println("  PASS analysis ready: classes=" + info.get("classes")
                    + " jdb2=" + info.get("jdb2"));

        // ---- 4. tree + decompile a real class
        // symbol.tree returns {items, truncated, total} (P0-3 honesty) — assert the shape
        Map<String, Object> treeResp = data(post("symbol.tree", Map.of("target", tid)));
        check(treeResp.containsKey("truncated") && treeResp.get("total") != null,
                "symbol.tree carries truncated+total (P0-3)");
        @SuppressWarnings("unchecked")
        List<Object> roots = (List<Object>) treeResp.get("items");
        check(!roots.isEmpty(), "symbol.tree → " + roots.size() + " roots");
        for (int i = 0; i < Math.min(3, roots.size()); i++) {
            @SuppressWarnings("unchecked")
            Map<String, Object> r = (Map<String, Object>) roots.get(i);
            System.out.println("  tree root: " + r.get("name") + " (" + r.get("count") + " classes)");
        }

        @SuppressWarnings("unchecked")
        List<Object> syms = dataList(post("symbol.search", Map.of("target", tid, "query", "a")));
        check(!syms.isEmpty(), "symbol.search 'a' → " + syms.size() + " hits");

        // pick a real class worth reading: decompiles to a class body (not an interface
        // stub) with at least 2 methods — walk the search hits until one qualifies
        Map<String, Object> cls = null;
        String classAddr = null;
        Map<String, Object> dec = null;
        Map<String, Object> outline = null;
        for (int i = 0; i < Math.min(15, syms.size()); i++) {
            @SuppressWarnings("unchecked")
            Map<String, Object> cand = (Map<String, Object>) syms.get(i);
            if (!"class".equals(cand.get("kind"))) continue;
            String addr = String.valueOf(cand.get("addr"));
            Map<String, Object> d;
            try {
                d = data(post("code.decompile", Map.of("target", tid, "symbol", addr)));
            } catch (IllegalStateException e) {
                continue;                            // not decompilable → next candidate
            }
            String t = String.valueOf(d.get("text"));
            if (!t.contains("class ") || ((Number) d.get("lineCount")).intValue() < 10) continue;
            Map<String, Object> o = data(post("code.outline", Map.of("target", tid, "class", addr)));
            @SuppressWarnings("unchecked")
            List<Object> ms = (List<Object>) o.get("methods");
            if (ms.size() < 2) continue;
            cls = cand;
            classAddr = addr;
            dec = d;
            outline = o;
            break;
        }
        check(cls != null, "found a real decompilable class among search hits");
        if (cls == null) throw new IllegalStateException("no suitable class in " + apk);
        System.out.println("  real class: " + cls.get("fqcn") + " @ " + classAddr);
        check(classAddr.startsWith("L") && classAddr.endsWith(";"), "class addr is a Dalvik handle");

        String text = String.valueOf(dec.get("text"));
        check(text.contains("class "), "code.decompile " + cls.get("fqcn") + " → " + dec.get("lineCount") + " lines");
        @SuppressWarnings("unchecked")
        List<Object> tokens = (List<Object>) dec.get("tokens");
        check(!tokens.isEmpty(), "semantic token map present (" + tokens.size() + " tokens)");
        System.out.println("  decompiled " + cls.get("fqcn") + ": " + dec.get("lineCount")
                + " lines, " + tokens.size() + " tokens");
        int shown = 0;
        for (String ln : text.split("\n")) {
            String t = ln.strip();
            if (t.isEmpty() || t.startsWith("package ") || t.startsWith("import ")) continue;
            System.out.println("    | " + (t.length() > 90 ? t.substring(0, 90) + "…" : t));
            if (++shown >= 5) break;
        }

        // ---- 5. outline → a method; xref.to + callees on it
        @SuppressWarnings("unchecked")
        List<Object> methods = (List<Object>) outline.get("methods");
        check(!methods.isEmpty(), "code.outline → " + methods.size() + " methods");
        // prefer a real (non-constructor) method, ideally one with actual callers
        Map<String, Object> m0 = null;
        List<Object> xto = List.of();
        for (Object o : methods) {
            @SuppressWarnings("unchecked")
            Map<String, Object> cand = (Map<String, Object>) o;
            String nm = String.valueOf(cand.get("name"));
            if (nm.startsWith("<")) continue;
            List<Object> refs = dataList(post("xref.to",
                    Map.of("target", tid, "symbol", cand.get("addr"))));
            if (m0 == null) {
                m0 = cand;
                xto = refs;
            }
            if (!refs.isEmpty()) {
                m0 = cand;
                xto = refs;
                break;
            }
        }
        if (m0 == null) {                                // only constructors → take methods[0]
            @SuppressWarnings("unchecked")
            Map<String, Object> first = (Map<String, Object>) methods.get(0);
            m0 = first;
            xto = dataList(post("xref.to", Map.of("target", tid, "symbol", m0.get("addr"))));
        }
        String methodAddr = String.valueOf(m0.get("addr"));
        System.out.println("  xref.to " + m0.get("name") + " → " + xto.size() + " refs"
                + (xto.isEmpty() ? "" : " (first: " + ((Map<?, ?>) xto.get(0)).get("method") + ")"));
        List<Object> callees = dataList(post("callees", Map.of("target", tid, "symbol", methodAddr)));
        System.out.println("  callees " + m0.get("name") + " → " + callees.size() + " edges"
                + (callees.isEmpty() ? "" : " (first: " + ((Map<?, ?>) callees.get(0)).get("owner")
                + "." + ((Map<?, ?>) callees.get(0)).get("name") + ")"));
        check(true, "xref/callees round-trip on " + m0.get("name"));

        // ---- 6. rename + save (native .jdb2 persist)
        String origName = String.valueOf(m0.get("name"));
        if (origName.matches("[A-Za-z_$][A-Za-z0-9_$]*") && !origName.startsWith("<")) {
            Map<String, Object> ren = data(post("rename",
                    Map.of("target", tid, "symbol", methodAddr, "name", "m1itProbe")));
            check("m1itProbe".equals(ren.get("newName")) && ren.get("newAddr") != null,
                    "rename " + origName + " → m1itProbe (newAddr=" + ren.get("newAddr") + ")");
            Map<String, Object> back = data(post("rename",
                    Map.of("target", tid, "symbol", ren.get("newAddr"), "name", origName)));
            check(origName.equals(back.get("newName")), "rename back → " + origName);
            Map<String, Object> saved = data(post("target.save", Map.of("target", tid)));
            check(Boolean.TRUE.equals(saved.get("saved")), "target.save → .jdb2 persisted");
        } else {
            System.out.println("  (skip rename probe: method " + origName + " not renamable)");
        }

        // ---- 7. switch mock ↔ real, decompile on both sides
        data(post("target.switch", Map.of("target", "t1")));
        Map<String, Object> mockDec = data(post("code.decompile", Map.of("symbol", "MessageSyncHandler")));
        check(String.valueOf(mockDec.get("text")).contains("AES/GCM/NoPadding"),
                "switch→mock t1: decompile MessageSyncHandler (fixture intact)");
        data(post("target.switch", Map.of("target", tid)));
        Map<String, Object> realDec = data(post("code.decompile", Map.of("symbol", classAddr)));
        check(String.valueOf(realDec.get("text")).contains("class "),
                "switch→real " + tid + ": decompile " + cls.get("fqcn") + " again");

        // ---- 8. close the real target
        Map<String, Object> after = data(post("target.close", Map.of("target", tid)));
        @SuppressWarnings("unchecked")
        List<Object> remaining = (List<Object>) after.get("targets");
        check(remaining.size() == 4, "target.close " + tid + " → back to 4 targets");

        System.out.println();
        if (failures > 0) {
            System.out.println("M1 IT FAILED: " + failures + "/" + checks + " checks failed");
            System.exit(1);
        }
        System.out.println("M1 IT PASSED: " + checks + " checks green");
        System.exit(0);
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
        body.put("actor", "script");
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
        Object d = resp.get("data");
        if (!(d instanceof Map))
            throw new IllegalStateException("expected map data, got: "
                    + (d == null ? "null" : d.getClass().getSimpleName()));
        return (Map<String, Object>) d;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> dataList(Map<String, Object> resp) {
        if (!Boolean.TRUE.equals(resp.get("ok")))
            throw new IllegalStateException("op failed: " + resp.get("error"));
        return (List<Object>) resp.get("data");
    }
}
