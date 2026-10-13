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
 * M4 integration test (plain main-class, no JUnit): tags (+families, pending
 * proposals, rename survival), bookmarks, flows — sidecar persistence across
 * close/reopen on the real test APK. Prints PASS/FAIL; exit 1 on failure.
 * Run: gradle m4IT.
 */
public final class M4IT {

    private static int failures = 0, checks = 0;
    private static final HttpClient http = HttpClient.newHttpClient();
    private static final String BASE = "http://127.0.0.1:" + WorkbenchServer.PORT;

    public static void main(String[] args) throws Exception {
        try {
            run(args);
        } catch (Throwable t) {
            System.out.println("M4 IT FAILED with exception: " + t);
            t.printStackTrace(System.out);
            System.exit(1);
        }
    }

    private static void run(String[] args) throws Exception {
        String apk = args.length > 0 ? args[0]
                : System.getProperty("user.home") + "/jeb/test_visionocr.apk";
        // self-clean: maps promoted by the flow-id regression below (idempotent test).
        // Under WORKBENCH_HOME (hermetic gradle run) the home is wiped pre-run —
        // the direct paths only matter for a hand-run against the live sidecar.
        String home = System.getenv().getOrDefault("WORKBENCH_HOME", "").trim();
        List<String> bases = home.isEmpty()
                ? List.of("backend/.workbench/projects/privacy-invite-flow/maps",
                        ".workbench/projects/privacy-invite-flow/maps",
                        "backend/.workbench/projects/p1/maps", ".workbench/projects/p1/maps",
                        "backend/.workbench/maps", ".workbench/maps")
                : List.of(home + "/projects/privacy-invite-flow/maps");
        for (String f : List.of("m4-flx.map.json", "m4-flr.map.json"))
            for (String base : bases)
                java.nio.file.Files.deleteIfExists(java.nio.file.Path.of(base, f));
        /* the ARTIFACT sidecar (not home-rooted) accumulates this test's pending
           review:agent tag rows across runs; a leftover dedupe-matches the llm
           tag.add below, and the proposal envelope then depends on the
           home-rooted proposal store surviving — the lockstep a hermetic
           WORKBENCH_HOME deliberately breaks (map-r7 F3 follow-up) */
        try {
            java.nio.file.Path tags = java.nio.file.Path.of(apk + ".workbench", "tags.json");
            if (java.nio.file.Files.isRegularFile(tags)) {
                @SuppressWarnings("unchecked")
                List<Object> rows = (List<Object>) Json.parse(java.nio.file.Files.readString(tags));
                rows.removeIf(row -> {
                    Map<?, ?> r2 = (Map<?, ?>) row;
                    /* strictly this test's own rows — a human/real-agent
                       review:agent tag must survive (map-r8 #6) */
                    return "review".equals(r2.get("family")) && "agent".equals(r2.get("name"))
                            && "llm:test".equals(r2.get("actor"));
                });
                java.nio.file.Files.writeString(tags, Json.write(rows));
            }
        } catch (Exception e) {
            System.out.println("tag sidecar self-clean failed (continuing): " + e);
        }
        WorkbenchServer server = new WorkbenchServer(Path.of("web"));
        server.start();
        System.out.println("server up on " + BASE + " · apk=" + apk);

        Map<String, Object> opened = data(post("target.open", Map.of("path", apk)));
        String tid = String.valueOf(opened.get("id"));
        try {
            awaitReady(tid);
            System.out.println("  target " + tid + " ready");

            // find a class with two usable methods
            String classAddr = null, m1 = null, m2 = null, m1Name = null;
            @SuppressWarnings("unchecked")
            List<Object> syms = dataList(post("symbol.search", Map.of("target", tid, "query", "a")));
            for (Object o : syms) {
                @SuppressWarnings("unchecked")
                Map<String, Object> cand = (Map<String, Object>) o;
                if (!"class".equals(cand.get("kind"))) continue;
                try {
                    Map<String, Object> ol = data(post("code.outline",
                            Map.of("target", tid, "class", cand.get("addr"))));
                    List<String> methods = new java.util.ArrayList<>();
                    for (Object mo : (List<?>) ol.get("methods")) {
                        String nm = String.valueOf(((Map<?, ?>) mo).get("name"));
                        if (!nm.startsWith("<")) methods.add(String.valueOf(((Map<?, ?>) mo).get("addr")));
                    }
                    if (methods.size() >= 2) {
                        classAddr = String.valueOf(cand.get("addr"));
                        m1 = methods.get(0);
                        m2 = methods.get(1);
                        m1Name = m1.substring(m1.indexOf("->") + 2, m1.indexOf('('));
                        break;
                    }
                } catch (IllegalStateException ignored) {}
            }
            check(classAddr != null, "found class with 2 methods (" + classAddr + ")");
            System.out.println("  probe class " + classAddr + " methods " + m1Name + ", " + m2);

            // ---- tags
            Map<String, Object> fam = data(post("tag.family.add",
                    Map.of("target", tid, "name", "crypto", "color", "#0072B2")));
            check("#0072B2".equals(fam.get("color")), "tag.family.add crypto → #0072B2");
            Map<String, Object> tag = data(post("tag.add",
                    Map.of("target", tid, "symbol", m1, "name", "crypto:aes", "note", "m4 probe")));
            check(tag.get("id") != null && !Boolean.TRUE.equals(tag.get("pending")),
                    "tag.add crypto:aes on " + m1Name + " → " + tag.get("id"));
            Map<String, Object> tl = data(post("tag.list", Map.of("target", tid)));
            @SuppressWarnings("unchecked")
            List<Object> tagRows = (List<Object>) tl.get("tags");
            check(tagRows.size() == 1 && String.valueOf(((Map<?, ?>) tagRows.get(0)).get("label"))
                            .contains(m1Name),
                    "tag.list → 1 row labeled " + ((Map<?, ?>) tagRows.get(0)).get("label"));
            check("#0072B2".equals(((Map<?, ?>) tagRows.get(0)).get("color")), "row carries family color");
            Map<String, Object> tlf = data(post("tag.list", Map.of("target", tid, "family", "pii")));
            check(((List<?>) tlf.get("tags")).isEmpty(), "family filter pii → 0 rows");
            // llm actor → pending proposal, not applied
            Map<String, Object> ltag = data(post("tag.add",
                    Map.of("target", tid, "symbol", m2, "name", "review:agent"), "llm:test"));
            // M7 contract: the op returns the review-queue proposal shape
            check(Boolean.TRUE.equals(ltag.get("proposal")) && "pending".equals(ltag.get("status"))
                            && ltag.get("id") != null,
                    "llm tag.add → pending proposal (" + ltag.get("id") + ")");
            Map<String, Object> tl2 = data(post("tag.list", Map.of("target", tid)));
            check(((List<?>) tl2.get("tags")).size() == 1
                            && ((Number) tl2.get("pendingCount")).intValue() >= 1,
                    "pending not applied: 1 accepted row, pendingCount >= 1 ("
                            + tl2.get("pendingCount") + ")");

            // ---- tag survives rename
            String probeName = "m4Probe";
            Map<String, Object> ren = data(post("rename",
                    Map.of("target", tid, "symbol", m1, "name", probeName)));
            check(probeName.equals(ren.get("newName")), "rename " + m1Name + " → " + probeName);
            Map<String, Object> tl3 = data(post("tag.list", Map.of("target", tid)));
            @SuppressWarnings("unchecked")
            Map<String, Object> row3 = (Map<String, Object>) ((List<Object>) tl3.get("tags")).get(0);
            check(String.valueOf(row3.get("label")).contains(probeName)
                            && Boolean.TRUE.equals(row3.get("renamed")),
                    "tag survives rename: label " + row3.get("label") + " renamed=" + row3.get("renamed"));

            // ---- bookmarks
            Map<String, Object> bm = data(post("bookmark.add",
                    Map.of("target", tid, "symbol", classAddr + ":17", "note", "m4 bookmark")));
            check(bm.get("id") != null && ((Number) bm.get("line")).intValue() == 17,
                    "bookmark.add class:17 → " + bm.get("id"));
            Map<String, Object> bm2 = data(post("bookmark.add",
                    Map.of("target", tid, "symbol", classAddr, "line", 17)));
            check(bm.get("id").equals(bm2.get("id")), "bookmark.add idempotent (same id)");
            @SuppressWarnings("unchecked")
            List<Object> bml = dataList(post("bookmark.list", Map.of("target", tid)));
            check(bml.size() == 1 && String.valueOf(((Map<?, ?>) bml.get(0)).get("label"))
                            .contains("m76"),
                    "bookmark.list → 1 row, label " + ((Map<?, ?>) bml.get(0)).get("label"));

            // ---- flows
            Map<String, Object> fl = data(post("flow.add",
                    Map.of("target", tid, "name", "m4-flow", "source", m1, "sink", m2,
                            "hops", m1 + "," + m2)));
            Map<String, Object> flg = data(post("flow.get", Map.of("target", tid, "id", fl.get("id"))));
            @SuppressWarnings("unchecked")
            List<Object> hops = (List<Object>) flg.get("hopsResolved");
            check(hops.size() == 2 && hops.get(0) instanceof Map
                            && ((Map<?, ?>) hops.get(0)).get("label") != null,
                    "flow.get → " + hops.size() + " resolved hops");
            @SuppressWarnings("unchecked")
            List<Object> fll = dataList(post("flow.list", Map.of("target", tid)));
            check(fll.size() == 1 && ((Map<?, ?>) fll.get(0)).get("sourceLabel") != null,
                    "flow.list → 1 row: " + ((Map<?, ?>) fll.get(0)).get("sourceLabel")
                            + " → " + ((Map<?, ?>) fll.get(0)).get("sinkLabel"));
            // cross-target detection: sink resolves only on the mock target
            Map<String, Object> xfl = data(post("flow.add",
                    Map.of("target", tid, "name", "m4-cross", "source", m1, "sink", "MessageSyncHandler")));
            Map<String, Object> xflg = data(post("flow.get", Map.of("target", tid, "id", xfl.get("id"))));
            check(Boolean.TRUE.equals(xflg.get("crossTarget")) && xflg.get("otherTarget") != null,
                    "cross-target flow detected (otherTarget=" + xflg.get("otherTarget") + ")");
            data(post("flow.rm", Map.of("target", tid, "id", xfl.get("id"))));

            // ---- concurrent open (fix-8-r2 F9): openingPaths serialization +
            // the membership currency check under real parallelism. Every outcome
            // must be ok or a SPOKEN E_BUSY (retried here) — never a 500, never
            // two sessions on one path. Two byte-copies of the APK give two
            // DIFFERENT jdb2 keys so the groups race each other's registration
            // through the currency check. Copies live in the scratch home (the
            // gradle run wipes it; nothing lands next to the real APK).
            java.nio.file.Path scratch = home.isEmpty()
                    ? java.nio.file.Files.createTempDirectory("m4concurrent")
                    : java.nio.file.Path.of(home);
            java.nio.file.Path copy1 = scratch.resolve("m4-copy1.apk");
            java.nio.file.Path copy2 = scratch.resolve("m4-copy2.apk");
            java.nio.file.Files.copy(java.nio.file.Path.of(apk), copy1,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            java.nio.file.Files.copy(java.nio.file.Path.of(apk), copy2,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            java.util.concurrent.atomic.AtomicInteger cBad = new java.util.concurrent.atomic.AtomicInteger();
            java.util.Map<String, java.util.Set<String>> cIds = new java.util.concurrent.ConcurrentHashMap<>();
            Runnable opener = new Runnable() {
                private void openOne(String path) {
                    for (int attempt = 0; attempt < 6; attempt++) {
                        try {
                            Map<String, Object> r = post("target.open", Map.of("path", path));
                            if (Boolean.TRUE.equals(r.get("ok"))) {
                                cIds.computeIfAbsent(path, k -> java.util.concurrent.ConcurrentHashMap.newKeySet())
                                        .add(String.valueOf(((Map<?, ?>) r.get("data")).get("id")));
                                return;
                            }
                            Object err = r.get("error");
                            String code = err instanceof Map<?, ?> em
                                    ? String.valueOf(em.get("code")) : String.valueOf(err);
                            if ("E_BUSY".equals(code) || "E_NOTFOUND".equals(code)) {
                                Thread.sleep(400);           // spoken mid-open churn: retry
                                continue;
                            }
                            cBad.incrementAndGet();
                            System.out.println("  concurrent open unexpected error: " + err);
                            return;
                        } catch (InterruptedException ignored) {
                            return;
                        } catch (Exception e) {
                            cBad.incrementAndGet();
                            System.out.println("  concurrent open threw: " + e);
                            return;
                        }
                    }
                    cBad.incrementAndGet();                  // retries exhausted
                    System.out.println("  concurrent open never settled for " + path);
                }
                @Override public void run() {
                    openOne(copy1.toString());
                    openOne(copy2.toString());
                }
            };
            Thread[] cts = new Thread[4];
            for (int i = 0; i < cts.length; i++) { cts[i] = new Thread(opener); cts[i].start(); }
            for (Thread t : cts) t.join();
            check(cBad.get() == 0, "4 threads × 2 fresh byte-copy opens: no unexpected errors");
            check(cIds.getOrDefault(copy1.toString(), java.util.Set.of()).size() == 1,
                    "concurrent opens of copy1 landed on ONE session: "
                            + cIds.get(copy1.toString()));
            check(cIds.getOrDefault(copy2.toString(), java.util.Set.of()).size() == 1,
                    "concurrent opens of copy2 landed on ONE session: "
                            + cIds.get(copy2.toString()));
            /* phase B (fix-8-r3, review #6): the fresh byte-copy opens above never
               reached hashOf/pathHashes (a fresh path has no jdb2 → mineHash
               null). With copy1's analysis complete its jdb2 exists — a
               concurrent open of the JDB2 PATH drives the own-database history
               gate for copy1 AND the conflict predicate's cache/live-hash
               machinery for the other open targets (tid, copy2). */
            String copy1Tid = cIds.getOrDefault(copy1.toString(), java.util.Set.of())
                    .stream().findFirst().orElse(null);
            if (copy1Tid == null) {
                check(false, "copy1 session id captured for the jdb2-dedupe phase");
            } else {
                awaitReady(copy1Tid);
                /* post-store (2026-09-08): the jdb2 lives in the content-keyed
                   store entry, never artifact-adjacent — read its path from the
                   target itself (target.info.jdb2) */
                java.nio.file.Path copy1Jdb2 = java.nio.file.Path.of(String.valueOf(
                        data(post("target.info", Map.of("target", copy1Tid))).get("jdb2")));
                java.util.Set<String> jIds = java.util.concurrent.ConcurrentHashMap.newKeySet();
                java.util.concurrent.atomic.AtomicInteger jBad = new java.util.concurrent.atomic.AtomicInteger();
                Runnable jdb2Opener = () -> {
                    for (int attempt = 0; attempt < 4; attempt++) {
                        try {
                            Map<String, Object> r = post("target.open", Map.of("path", copy1Jdb2.toString()));
                            if (Boolean.TRUE.equals(r.get("ok"))) {
                                jIds.add(String.valueOf(((Map<?, ?>) r.get("data")).get("id")));
                                return;
                            }
                            Object err = r.get("error");
                            String code = err instanceof Map<?, ?> em
                                    ? String.valueOf(em.get("code")) : String.valueOf(err);
                            if ("E_BUSY".equals(code)) { Thread.sleep(400); continue; }
                            jBad.incrementAndGet();
                            System.out.println("  concurrent jdb2 open unexpected error: " + err);
                            return;
                        } catch (InterruptedException ignored) {
                            return;
                        } catch (Exception e) {
                            jBad.incrementAndGet();
                            System.out.println("  concurrent jdb2 open threw: " + e);
                            return;
                        }
                    }
                    jBad.incrementAndGet();
                    System.out.println("  concurrent jdb2 open never settled");
                };
                Thread[] jts = new Thread[4];
                for (int i = 0; i < jts.length; i++) { jts[i] = new Thread(jdb2Opener); jts[i].start(); }
                for (Thread t : jts) t.join();
                check(jBad.get() == 0, "4 concurrent opens of the live jdb2: no unexpected errors");
                check(jIds.size() == 1 && jIds.contains(copy1Tid),
                        "every concurrent jdb2 open deduped onto the one existing session: " + jIds);
            }
            for (String p : List.of(copy1.toString(), copy2.toString())) {
                java.util.Set<String> got = cIds.get(p);
                if (got != null && got.size() == 1)
                    try { data(post("target.close", Map.of("target", got.iterator().next()))); }
                    catch (Exception e) { System.out.println("  copy close failed (continuing): " + e.getMessage()); }
            }
            java.nio.file.Files.deleteIfExists(copy1);
            java.nio.file.Files.deleteIfExists(copy2);
            java.nio.file.Files.deleteIfExists(
                    java.nio.file.Path.of(copy1.toString().replaceAll("\\.apk$", ".jdb2")));
            java.nio.file.Files.deleteIfExists(
                    java.nio.file.Path.of(copy2.toString().replaceAll("\\.apk$", ".jdb2")));
            data(post("target.switch", Map.of("target", tid)));   // the opens re-pointed active

            // ---- persistence: close + reopen

            data(post("target.save", Map.of("target", tid)));   // persist the rename too
            data(post("target.close", Map.of("target", tid)));
            Map<String, Object> reopened = data(post("target.open", Map.of("path", apk)));
            tid = String.valueOf(reopened.get("id"));
            awaitReady(tid);
            Map<String, Object> tl4 = data(post("tag.list", Map.of("target", tid)));
            @SuppressWarnings("unchecked")
            List<Object> tags4 = (List<Object>) tl4.get("tags");
            @SuppressWarnings("unchecked")
            List<Object> bms4 = dataList(post("bookmark.list", Map.of("target", tid)));
            @SuppressWarnings("unchecked")
            List<Object> fls4 = dataList(post("flow.list", Map.of("target", tid)));
            check(tags4.size() == 1 && bms4.size() == 1 && fls4.size() == 1,
                    "after close+reopen: 1 tag + 1 bookmark + 1 flow persisted");
            check(String.valueOf(((Map<?, ?>) tags4.get(0)).get("label")).contains(probeName),
                    "tag still resolves after reopen (rename persisted): "
                            + ((Map<?, ?>) tags4.get(0)).get("label"));

            // cleanup: rename back to the ORIGINAL name, drop probe annotations
            Map<String, Object> resolved = data(post("symbol.resolve",
                    Map.of("target", tid, "symbol", probeName)));
            if (resolved.get("origName") != null)
                data(post("rename", Map.of("target", tid, "symbol", probeName,
                        "name", String.valueOf(resolved.get("origName")))));
            for (Object t : (List<?>) data(post("tag.list", Map.of("target", tid))).get("tags"))
                data(post("tag.rm", Map.of("target", tid, "id", ((Map<?, ?>) t).get("id"))));
            for (Object b : dataList(post("bookmark.list", Map.of("target", tid))))
                data(post("bookmark.rm", Map.of("target", tid, "id", ((Map<?, ?>) b).get("id"))));
            for (Object f : dataList(post("flow.list", Map.of("target", tid))))
                data(post("flow.rm", Map.of("target", tid, "id", ((Map<?, ?>) f).get("id"))));
            data(post("target.save", Map.of("target", tid)));

            // ---- flow id durability regression (QA re-verify: "flow not found: fl-1")
            // (1) add → rm → add → promote in ONE session: ids monotonic, promote resolves
            Map<String, Object> fx1 = data(post("flow.add",
                    Map.of("target", tid, "name", "m4-flx", "source", m1, "sink", m2)));
            int fxSeq1 = Integer.parseInt(String.valueOf(fx1.get("id")).substring(3));
            data(post("flow.rm", Map.of("target", tid, "id", fx1.get("id"))));
            Map<String, Object> fx2 = data(post("flow.add",
                    Map.of("target", tid, "name", "m4-flx", "source", m1, "sink", m2)));
            int fxSeq2 = Integer.parseInt(String.valueOf(fx2.get("id")).substring(3));
            check(fxSeq2 > fxSeq1,
                    "flow ids monotonic in-session after rm (" + fx1.get("id") + " → " + fx2.get("id") + ")");
            Map<String, Object> px = data(post("flow.promote", Map.of("target", tid, "id", fx2.get("id"))));
            check("m4-flx".equals(px.get("map")), "promote re-added flow by id → map m4-flx");
            Map<String, Object> pxn = data(post("flow.promote", Map.of("target", tid, "id", "m4-flx")));
            check("m4-flx".equals(pxn.get("map")), "flow.promote resolves by NAME (stale-id defense)");
            data(post("flow.rm", Map.of("target", tid, "id", fx2.get("id"))));

            // (2) add → rm → RESTART → add → promote: the counter must survive a
            // server restart (persisted seq.json), not fall back to max(existing ids)
            Map<String, Object> fr1 = data(post("flow.add",
                    Map.of("target", tid, "name", "m4-flr", "source", m1, "sink", m2)));
            int frSeq1 = Integer.parseInt(String.valueOf(fr1.get("id")).substring(3));
            data(post("flow.rm", Map.of("target", tid, "id", fr1.get("id"))));
            data(post("target.close", Map.of("target", tid)));   // release the .jdb2 lock before restart
            server.stop();
            server = new WorkbenchServer(Path.of("web"));
            server.start();
            System.out.println("  server restarted on " + BASE);
            Map<String, Object> ro = data(post("target.open", Map.of("path", apk)));
            tid = String.valueOf(ro.get("id"));
            awaitReady(tid);
            Map<String, Object> fr2 = data(post("flow.add",
                    Map.of("target", tid, "name", "m4-flr", "source", m1, "sink", m2)));
            int frSeq2 = Integer.parseInt(String.valueOf(fr2.get("id")).substring(3));
            check(frSeq2 > frSeq1,
                    "flow ids monotonic across rm + restart (fl-" + frSeq1 + " → fl-" + frSeq2 + ")");
            Map<String, Object> pr = data(post("flow.promote", Map.of("target", tid, "id", fr2.get("id"))));
            check("m4-flr".equals(pr.get("map")), "add → restart → promote works");
            data(post("flow.rm", Map.of("target", tid, "id", fr2.get("id"))));
        } finally {
            try { post("target.close", Map.of("target", tid)); } catch (Exception ignored) {}
        }

        // ---- mock side
        data(post("target.switch", Map.of("target", "t1")));
        Map<String, Object> mtag = data(post("tag.add",
                Map.of("target", "t1", "symbol", "onMessage", "name", "entry:fcm", "note", "mock")));
        check(mtag.get("id") != null, "mock tag.add works");
        Map<String, Object> mtl = data(post("tag.list", Map.of("target", "t1")));
        check(((List<?>) mtl.get("tags")).size() == 1, "mock tag.list → 1 row");

        System.out.println();
        if (failures > 0) {
            System.out.println("M4 IT FAILED: " + failures + "/" + checks + " checks failed");
            System.exit(1);
        }
        System.out.println("M4 IT PASSED: " + checks + " checks green");
        System.exit(0);
    }

    // ------------------------------------------------------------ helpers

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
        return post(op, params, "human");
    }

    private static Map<String, Object> post(String op, Map<String, Object> params, String actor)
            throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("params", params);
        body.put("actor", actor);
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
