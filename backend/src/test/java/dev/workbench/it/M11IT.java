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
 * M11 integration test (plain main-class, no JUnit): the EMPTY default
 * workspace — WORKBENCH_MOCK=0, the interactive server's real boot shape
 * (2026-09-07). No seeded "privacy-invite-flow", no targets. Every
 * projectless op must answer honestly (E_NOTFOUND/E_SYNTAX), never NPE/500;
 * closing the LAST project returns to empty; a failed target.open leaves no
 * orphan project. Guards the empty-boot fix-review r1 findings (presence
 * NPE, map-op honesty, orphan rollback). Run: gradle m11IT — the task itself
 * pins WORKBENCH_MOCK=0; a hand-run without it is meaningless (fails check 1).
 */
public final class M11IT {

    private static int failures = 0, checks = 0;
    private static final HttpClient http = HttpClient.newHttpClient();
    private static final String BASE = "http://127.0.0.1:" + WorkbenchServer.PORT;

    public static void main(String[] args) throws Exception {
        try {
            run();
        } catch (Throwable t) {
            System.out.println("M11 IT FAILED with exception: " + t);
            t.printStackTrace(System.out);
            System.exit(1);
        }
    }

    private static void run() throws Exception {
        check("0".equals(System.getenv("WORKBENCH_MOCK")),
                "WORKBENCH_MOCK=0 (the suite is meaningless with the mock scene)");
        WorkbenchServer server = new WorkbenchServer(Path.of("web"));
        server.start();
        System.out.println("server up on " + BASE);

        // ---- 1. the empty boot
        Map<String, Object> st = data(post("workspace.state", Map.of()));
        check(st.get("projects") instanceof List<?> ps && ps.isEmpty(), "boot: no projects");
        check(st.get("activeProject") == null, "boot: activeProject null");
        check(st.get("targets") instanceof List<?> ts && ts.isEmpty(), "boot: no targets");
        check(st.get("activeTarget") == null, "boot: activeTarget null");
        check(st.get("activeMap") == null, "boot: activeMap null");
        check(st.get("sidecarIssues") == null, "boot: no sidecar issues");

        // ---- 2. projectless ops fail HONESTLY (coded errors, never a 500/NPE)
        check(errCode(post("map.list", Map.of()), "E_NOTFOUND"),
                "map.list with no project → E_NOTFOUND");
        check(errCode(post("symbol.tree", Map.of()), "E_NOTFOUND"),
                "symbol.tree with no target → E_NOTFOUND");
        check(errCode(post("project.close", Map.of("project", "ghost")), "E_NOTFOUND"),
                "project.close on the empty workspace → E_NOTFOUND, not a 500");
        check(errCode(post("project.switch", Map.of("project", "ghost")), "E_NOTFOUND"),
                "project.switch on the empty workspace → E_NOTFOUND, not a 500");

        // ---- 3. presence on the empty workspace (r1 #1: whereis NPE'd after
        //         a null-project ping)
        Map<String, Object> ping = data(post("presence.ping", Map.of("addr", "Lcom/foo/Bar;")));
        check(Boolean.FALSE.equals(ping.get("pinged")),
                "presence.ping with no project → pinged:false (nothing recorded)");
        Map<String, Object> w = data(post("presence.whereis", Map.of("who", "agent")));
        check(Boolean.FALSE.equals(w.get("present")),
                "presence.whereis after the no-project ping → 200, present:false (no NPE)");

        // ---- 4. target.open validation precedes any project creation
        check(errCode(post("target.open", Map.of("path", "  ")), "E_SYNTAX"),
                "target.open blank path → E_SYNTAX");
        check(errCode(post("target.open", Map.of("path", "/nonexistent/nothing.apk")), "E_NOTFOUND"),
                "target.open missing artifact → E_NOTFOUND");
        st = data(post("workspace.state", Map.of()));
        check(st.get("projects") instanceof List<?> ps2 && ps2.isEmpty(),
                "failed opens leave NO orphan project behind (r1 #3)");

        // ---- 4b. fs.list — the launcher BROWSE pane's backend (projectless)
        Map<String, Object> cwdLst = data(post("fs.list", Map.of()));
        /* the task pins WORKBENCH_CWD=repoRoot — the production branch (a developer
           exporting the var no longer reds this; the unset user.dir fallback is the
           one-liner beneath it) */
        check(System.getenv("WORKBENCH_CWD").equals(cwdLst.get("dir")),
                "fs.list with no dir → the launch-time caller's cwd (WORKBENCH_CWD)");
        check(cwdLst.get("entries") instanceof List<?> he && !he.isEmpty(),
                "cwd listing is non-empty");
        check(errCode(post("fs.list", Map.of("dir", "/nonexistent/nowhere")), "E_NOTFOUND"),
                "fs.list missing dir → E_NOTFOUND");

        Path fstmp = Files.createTempDirectory("m11fs");
        try {
            Files.createDirectory(fstmp.resolve("zeta"));   // dirs sort before files despite the z-name
            Files.writeString(fstmp.resolve("b.txt"), "bb");
            Files.writeString(fstmp.resolve("a.apk"), "PK");
            Map<String, Object> lst = data(post("fs.list", Map.of("dir", fstmp.toString())));
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> ents = (List<Map<String, Object>>) lst.get("entries");
            check(ents.size() == 3, "fs.list sees all 3 entries (got " + ents.size() + ")");
            check("zeta".equals(ents.get(0).get("name")) && Boolean.TRUE.equals(ents.get(0).get("dir")),
                    "dirs sort first");
            check("a.apk".equals(ents.get(1).get("name")) && "b.txt".equals(ents.get(2).get("name")),
                    "files alphabetical case-insensitive");
            check(((Number) ents.get(1).get("size")).longValue() == 2, "file size reported");
            check(String.valueOf(ents.get(1).get("path")).startsWith("/"),
                    "entry paths absolute → directly usable by target.open");
            check(fstmp.getParent().toString().equals(lst.get("parent")), "parent reported");
            check(errCode(post("fs.list", Map.of("dir", fstmp.resolve("a.apk").toString())), "E_SYNTAX"),
                    "fs.list on a FILE → E_SYNTAX (the path belongs in the launcher input)");
        } finally {
            Files.deleteIfExists(fstmp.resolve("a.apk"));
            Files.deleteIfExists(fstmp.resolve("b.txt"));
            Files.deleteIfExists(fstmp.resolve("zeta"));
            Files.deleteIfExists(fstmp);
        }

        // ---- 4c. '~' expansion (browse B4): fs.list and target.open both expand
        Map<String, Object> tilde = data(post("fs.list", Map.of("dir", "~")));
        check(System.getProperty("user.home").equals(tilde.get("dir")),
                "fs.list '~' expands to user.home");
        Path tildeJunk = Path.of(System.getProperty("user.home"), ".m11-tilde-junk.bin");
        try {
            Files.writeString(tildeJunk, "not an artifact");
            /* an EXISTING junk file must reach type detection (E_UNSUPPORTED);
               an unexpanded '~' would instead report E_NOTFOUND */
            check(errCode(post("target.open", Map.of("path", "~/.m11-tilde-junk.bin")), "E_UNSUPPORTED"),
                    "target.open expands '~' (found-but-unopenable → E_UNSUPPORTED, not E_NOTFOUND)");
        } finally {
            Files.deleteIfExists(tildeJunk);
        }

        // ---- 5. lifecycle: open, pin, close down to EMPTY, ids never recycle
        Map<String, Object> s1 = data(post("project.open", Map.of("name", "m11-alpha")));
        check("p1".equals(String.valueOf(s1.get("activeProject"))),
                "mock-off ids re-base: first project is p1 (got " + s1.get("activeProject") + ")");
        Map<String, Object> ping2 = data(post("presence.ping", Map.of("addr", "Lcom/foo/Bar;")));
        check(Boolean.TRUE.equals(ping2.get("pinged")), "presence.ping with a project → pinged:true");
        data(post("project.open", Map.of("name", "m11-beta")));
        Map<String, Object> s2 = data(post("workspace.state", Map.of()));
        check(((List<?>) s2.get("projects")).size() == 2, "two projects after the second project.open");

        data(post("project.close", Map.of("project", "m11-beta")));
        Map<String, Object> s3 = data(post("project.close", Map.of("project", "m11-alpha")));
        check(((List<?>) s3.get("projects")).isEmpty(),
                "closing the LAST project is legal → empty (the old E_SYNTAX refusal is gone)");
        check(s3.get("activeProject") == null && s3.get("activeTarget") == null,
                "empty state: no active project/target");
        check(errCode(post("map.list", Map.of()), "E_NOTFOUND"),
                "map.list honest again after the last close");
        Map<String, Object> w2 = data(post("presence.whereis", Map.of("who", "agent")));
        check(Boolean.FALSE.equals(w2.get("present")),
                "whereis on the re-emptied workspace → present:false (closed project's pins cleared)");

        Map<String, Object> s4 = data(post("project.open", Map.of("name", "m11-gamma")));
        check("p3".equals(String.valueOf(s4.get("activeProject"))),
                "ids monotonic across the empty transition: p3, never recycled (got "
                        + s4.get("activeProject") + ")");

        // ---- 6. project.known: the on-disk projects stay loadable — the menu's
        //         answer to "where did my projects go" after a restart
        List<Object> known = dataList(post("project.known", Map.of()));
        java.util.Set<String> names = new java.util.HashSet<>();
        for (Object o : known) names.add(String.valueOf(((Map<?, ?>) o).get("name")));
        check(names.contains("m11-alpha") && names.contains("m11-beta") && names.contains("m11-gamma"),
                "project.known lists the closed projects + the open one: " + names);
        boolean gammaOpen = false, alphaClosed = false;
        for (Object o : known) {
            Map<?, ?> k = (Map<?, ?>) o;
            if ("m11-gamma".equals(k.get("name"))) gammaOpen = Boolean.TRUE.equals(k.get("open"));
            if ("m11-alpha".equals(k.get("name"))) alphaClosed = Boolean.FALSE.equals(k.get("open"));
        }
        check(gammaOpen && alphaClosed, "project.known open flags: gamma open, alpha closed");

        // ---- 7. project.known exclusions + name dedupe (r2b #3/#4): parked
        //         dirs keep their marker inside, so the suffix filter is what
        //         keeps them unlisted; a shadowed same-name twin must not row
        String home = System.getenv("WORKBENCH_HOME");
        java.nio.file.Path projs = java.nio.file.Path.of(home, "projects");
        java.nio.file.Files.createDirectories(projs.resolve("markerless"));   // no .project-name
        java.nio.file.Path parked = projs.resolve("old-thing.forgotten-2026-01-01T00-00-00Z");
        java.nio.file.Files.createDirectories(parked);
        java.nio.file.Files.writeString(parked.resolve(".project-name"), "old-thing");
        java.nio.file.Path dupMain = projs.resolve("dup");
        java.nio.file.Files.createDirectories(dupMain);
        java.nio.file.Files.writeString(dupMain.resolve(".project-name"), "dup");
        /* dirFor's hashed-twin candidate for the same name ("dup") — carries a
           matching marker, but the walk resolves "dup" to the slug dir first */
        java.nio.file.Path dupTwin = projs.resolve("dup-"
                + String.format("%08x", "dup".hashCode()));
        java.nio.file.Files.createDirectories(dupTwin);
        java.nio.file.Files.writeString(dupTwin.resolve(".project-name"), "dup");
        /* the TWIN carries the only map (r3 #8): if the surviving row described
           the twin instead of the walk winner, its count would read 1 */
        java.nio.file.Files.createDirectories(dupTwin.resolve("maps"));
        java.nio.file.Files.writeString(dupTwin.resolve("maps").resolve("x.map.json"),
                "{}");
        List<Object> known2 = dataList(post("project.known", Map.of()));
        java.util.Set<String> names2 = new java.util.HashSet<>();
        int dupRows = 0;
        int dupMaps = -1;
        for (Object o : known2) {
            String n = String.valueOf(((Map<?, ?>) o).get("name"));
            names2.add(n);
            if ("dup".equals(n)) { dupRows++; dupMaps = ((Number) ((Map<?, ?>) o).get("maps")).intValue(); }
        }
        check(!names2.contains("old-thing"), "project.known skips parked (.forgotten-) dirs");
        check(!names2.contains("markerless"), "project.known skips marker-less dirs");
        check(dupRows == 1, "project.known dedupes shadowed same-name dirs (rows for 'dup': " + dupRows + ")");
        check(dupMaps == 0, "the surviving 'dup' row is the walk winner, not the twin (maps=" + dupMaps + ")");

        // ---- 7b. project.forget: the confirm gate, the park, the list shrink (r3 #10)
        data(post("project.open", Map.of("name", "m11-forget")));    // creates + claims the dir
        data(post("project.close", Map.of("project", "m11-forget"))); // closed → forgettable
        check(errCode(post("project.forget", Map.of("name", "m11-forget")), "E_SYNTAX"),
                "project.forget without confirm:true → E_SYNTAX (the gate)");
        Map<String, Object> fg = data(post("project.forget",
                Map.of("name", "m11-forget", "confirm", true)));
        check(String.valueOf(fg.get("parked")).contains("m11-forget.forgotten-"),
                "project.forget parks the sidecar: " + fg.get("parked"));
        boolean forgetGone = true;
        for (Object o : dataList(post("project.known", Map.of())))
            if ("m11-forget".equals(((Map<?, ?>) o).get("name"))) forgetGone = false;
        check(forgetGone, "project.known drops the forgotten project");
        check(errCode(post("project.forget", Map.of("name", "m11-gamma", "confirm", true)), "E_BUSY"),
                "project.forget refuses an OPEN project");
        check(errCode(post("project.forget", Map.of("name", "no-such-project", "confirm", true)), "E_NOTFOUND"),
                "project.forget unknown name → E_NOTFOUND");

        // ---- 8. the REBIND is the point: maps + activeMap survive close →
        //         reopen-by-name (the ON DISK gesture's whole value)
        data(post("project.open", Map.of("name", "m11-rebind")));
        Map<String, Object> mc = data(post("map.create", Map.of("title", "M11 Rebind", "build", "b1")));
        String mid = mc.get("id") != null ? String.valueOf(mc.get("id")) : "m11-rebind";
        data(post("map.open", Map.of("id", mid)));
        Map<String, Object> stOpen = data(post("workspace.state", Map.of()));
        check(mid.equals(String.valueOf(stOpen.get("activeMap"))), "map.open sets activeMap pre-close");
        data(post("project.close", Map.of("project", "m11-rebind")));
        Map<String, Object> s5 = data(post("project.open", Map.of("name", "m11-rebind")));
        check(mid.equals(String.valueOf(s5.get("activeMap"))),
                "reopen-by-name restores the persisted activeMap (" + mid + ")");
        boolean mapBack = false;
        for (Object o : dataList(post("map.list", Map.of())))
            if (mid.equals(String.valueOf(((Map<?, ?>) o).get("id")))) mapBack = true;
        check(mapBack, "reopen-by-name rebinds the same map sidecar");
        /* leave EMPTY for the auto-create section: close everything still open */
        data(post("project.close", Map.of("project", "m11-rebind")));
        Map<String, Object> s6 = data(post("project.close", Map.of("project", "m11-gamma")));
        check(((List<?>) s6.get("projects")).isEmpty() && s6.get("activeProject") == null,
                "workspace empty before the auto-create test");

        // ---- 9. the headline: a SUCCESSFUL target.open on an empty workspace
        //         auto-creates its project (needs JEB + the QA fixture apk)
        java.io.File fixture = new java.io.File(System.getenv().getOrDefault("WORKBENCH_QUALIFY_FIXTURE", System.getProperty("user.home") + "/jeb/test_visionocr.apk"));
        boolean jeb = new java.io.File(System.getProperty("workbench.jebHome", "backend/engine"), "bin/app/jeb.jar").isFile();
        if (!jeb || !fixture.isFile()) {
            System.out.println("SKIP auto-create success path (no engine jars or no fixture apk)");
        } else {
            /* the store migration MOVES the artifact-adjacent jdb2 — never run
               these opens on the user's real fixture dir. A hermetic copy,
               seeded with the legacy jdb2 the user's runs left (when present),
               exercises the migration arm without touching the user's fixture directory. */
            java.nio.file.Path fixtDir = java.nio.file.Path.of(home, "fixt");
            java.nio.file.Files.createDirectories(fixtDir);
            java.io.File legacyJdb2 = new java.io.File(fixture.getParentFile(), fixture.getName().replaceFirst("\\.apk$", ".jdb2"));
            boolean legacySeeded = legacyJdb2.isFile();
            if (legacySeeded)
                java.nio.file.Files.copy(legacyJdb2.toPath(), fixtDir.resolve("test_visionocr.jdb2"),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            java.nio.file.Files.copy(fixture.toPath(), fixtDir.resolve("test_visionocr.apk"),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            fixture = fixtDir.resolve("test_visionocr.apk").toFile();
            Map<String, Object> opened = data(post("target.open",
                    Map.of("path", fixture.getAbsolutePath())));
            String tid = String.valueOf(opened.get("id"));
            Map<String, Object> s7 = data(post("workspace.state", Map.of()));
            boolean projOk = false;
            for (Object o : (List<?>) s7.get("projects")) {
                Map<?, ?> p = (Map<?, ?>) o;
                if ("test_visionocr".equals(p.get("name")) && Boolean.TRUE.equals(p.get("active")))
                    projOk = true;
            }
            check(projOk, "empty-workspace target.open auto-creates project 'test_visionocr', active");
            check(tid.equals(String.valueOf(s7.get("activeTarget"))),
                    "auto-create activates the new target (" + tid + ")");
            Map<String, Object> s8 = data(post("project.close", Map.of("project", "test_visionocr")));
            check(((List<?>) s8.get("projects")).isEmpty() && s8.get("activeProject") == null,
                    "closing the auto-created project returns to empty");

            /* r3 #9: the generic-name CHAIN — a base.apk in a real directory
               (no prov) must take the PARENT's name, not "base" */
            java.nio.file.Path sceneDir = java.nio.file.Path.of(home, "scene-x");
            java.nio.file.Files.createDirectories(sceneDir);
            java.nio.file.Path baseApk = sceneDir.resolve("base.apk");
            java.nio.file.Files.copy(fixture.toPath(), baseApk);
            Map<String, Object> opened2 = data(post("target.open",
                    Map.of("path", baseApk.toAbsolutePath().toString())));
            String tid2 = String.valueOf(opened2.get("id"));
            Map<String, Object> s9 = data(post("workspace.state", Map.of()));
            boolean sceneOk = false;
            for (Object o : (List<?>) s9.get("projects")) {
                Map<?, ?> p = (Map<?, ?>) o;
                if ("scene-x".equals(p.get("name")) && Boolean.TRUE.equals(p.get("active"))) sceneOk = true;
            }
            check(sceneOk, "generic base.apk auto-creates project 'scene-x' (parent arm, r3 #9)");
            check(tid2.equals(String.valueOf(s9.get("activeTarget"))),
                    "the second auto-create activates its own target (" + tid2 + ")");
            Map<String, Object> s10 = data(post("project.close", Map.of("project", "scene-x")));
            check(((List<?>) s10.get("projects")).isEmpty() && s10.get("activeProject") == null,
                    "empty again after closing scene-x");

            /* r5 F4: the PROV arm — a pulled base.apk names its project from
               the pull record's package dir, install suffix stripped */
            java.nio.file.Path pulledDir = java.nio.file.Path.of(home, "pulled");
            java.nio.file.Files.createDirectories(pulledDir);
            java.nio.file.Path pullApk = pulledDir.resolve("base.apk");
            java.nio.file.Files.copy(fixture.toPath(), pullApk);
            java.nio.file.Files.writeString(pulledDir.resolve("base.prov.json"),
                    "{\"pulledFrom\":\"emulator-5554:/data/app/com.foo-3sHc9Q==/base.apk\"}");
            data(post("target.open", Map.of("path", pullApk.toAbsolutePath().toString())));
            Map<String, Object> s11 = data(post("workspace.state", Map.of()));
            boolean provOk = false;
            for (Object o : (List<?>) s11.get("projects")) {
                Map<?, ?> p = (Map<?, ?>) o;
                if ("com.foo".equals(p.get("name")) && Boolean.TRUE.equals(p.get("active"))) provOk = true;
            }
            check(provOk, "pulled base.apk auto-creates project 'com.foo' from provenance (r5 F4)");
            Map<String, Object> s12 = data(post("project.close", Map.of("project", "com.foo")));
            check(((List<?>) s12.get("projects")).isEmpty() && s12.get("activeProject") == null,
                    "empty again after closing com.foo");

            /* cross-project dedupe NAVIGATES (user directive 2026-09-08): opening
               an artifact that is already open in ANOTHER project switches to it
               and activates the session — the E_BUSY refusal is retired */
            Map<String, Object> nav1 = data(post("target.open",
                    Map.of("path", fixture.getAbsolutePath())));
            String navTid = String.valueOf(nav1.get("id"));
            String navProj = String.valueOf(data(post("workspace.state", Map.of())).get("activeProject"));
            data(post("project.open", Map.of("name", "m11-nav-b")));
            Map<String, Object> nav2 = data(post("target.open",
                    Map.of("path", fixture.getAbsolutePath())));
            check(Boolean.TRUE.equals(nav2.get("deduped")) && navTid.equals(String.valueOf(nav2.get("id"))),
                    "cross-project same-artifact open dedupes onto the SAME target");
            Map<String, Object> sNav = data(post("workspace.state", Map.of()));
            check(navProj.equals(String.valueOf(sNav.get("activeProject"))),
                    "the open NAVIGATED back to the owning project (" + navProj + ")");
            check(navTid.equals(String.valueOf(sNav.get("activeTarget"))),
                    "the existing target is active after the navigation");
            data(post("project.close", Map.of("project", "test_visionocr")));
            Map<String, Object> s13 = data(post("project.close", Map.of("project", "m11-nav-b")));
            check(((List<?>) s13.get("projects")).isEmpty() && s13.get("activeProject") == null,
                    "empty again after the navigation test");

            /* arm 2 (a811842-r2 F-B): the candidate IS the twin target's own .jdb2
               (its bytes are in the engine's recorded history — round-14 F6), opened
               from another project → navigation with the twin note. NOTE: a
               byte-identical ARTIFACT at a different path is deliberately NOT a
               twin — different jdb2 key, its own database, independent edits; the
               content compare is jdb2-identity-keyed (verified live 2026-09-08) */
            java.nio.file.Path twin = java.nio.file.Path.of(home, "twin.apk");
            java.nio.file.Files.copy(fixture.toPath(), twin);
            Map<String, Object> tw1 = data(post("target.open",
                    Map.of("path", twin.toAbsolutePath().toString())));
            String twTid = String.valueOf(tw1.get("id"));
            String twProj = String.valueOf(data(post("workspace.state", Map.of())).get("activeProject"));
            /* the content-twin compare is declined while the twin is LOADING —
               its hash history is only recorded by doOpen's stages (round-16 F4) */
            for (int i = 0; i < 40; i++) {
                Object st2 = data(post("target.info", Map.of("target", twTid))).get("state");
                if ("ready".equals(st2)) break;
                if ("error".equals(st2)) throw new IllegalStateException("twin failed to analyze");
                Thread.sleep(3000);
            }
            String twinJdb2 = String.valueOf(tw1.get("jdb2"));   // the STORE path, post-2026-09-08
            check(new java.io.File(twinJdb2).isFile(), "the twin's jdb2 was written at open (in the store)");
            data(post("project.open", Map.of("name", "m11-twin-b")));
            Map<String, Object> tw2 = data(post("target.open", Map.of("path", twinJdb2)));
            check(Boolean.TRUE.equals(tw2.get("deduped")) && twTid.equals(String.valueOf(tw2.get("id"))),
                    "the twin's own .jdb2 opened elsewhere dedupes onto the SAME target (arm 2)");
            check(String.valueOf(tw2.get("note")).contains("switched there"),
                    "the twin navigation note is present");
            Map<String, Object> sTw = data(post("workspace.state", Map.of()));
            check(twProj.equals(String.valueOf(sTw.get("activeProject"))),
                    "arm 2 navigated to the twin's owning project");
            data(post("project.close", Map.of("project", String.valueOf(sTw.get("activeProject")))));
            Map<String, Object> s14 = data(post("project.close", Map.of("project", "m11-twin-b")));
            check(((List<?>) s14.get("projects")).isEmpty() && s14.get("activeProject") == null,
                    "empty again after the twin-navigation test");
            java.nio.file.Files.deleteIfExists(twin);

            /* ---- target store + membership + project.delete (2026-09-08) */
            java.io.File storeDir = java.nio.file.Path.of(home, "targets").toFile();
            check(storeDir.isDirectory(), "the target store exists under WORKBENCH_HOME");
            java.io.File[] entries = storeDir.listFiles();
            check(entries != null && entries.length >= 1,
                    "the earlier opens created store entries (got " + (entries == null ? 0 : entries.length) + ")");
            java.io.File entry = null;
            for (java.io.File e2 : entries == null ? new java.io.File[0] : entries)
                if (e2.getName().startsWith("test-visionocr-")) entry = e2;
            check(entry != null, "a test-visionocr-<hash8> store entry exists (slug dashes)");
            if (entry != null) {
                check(new java.io.File(entry, "test_visionocr.apk").isFile(),
                        "the store entry holds the artifact COPY");
                check(new java.io.File(entry, ".content-sha1").isFile(),
                        "the entry pins the full content hash");
            }
            if (legacySeeded) {
                check(!fixtDir.resolve("test_visionocr.jdb2").toFile().isFile(),
                        "the legacy artifact-adjacent jdb2 MOVED out of the artifact's dir");
            }

            // membership: open the fixture, the project lists it
            Map<String, Object> st1 = data(post("target.open",
                    Map.of("path", fixture.getAbsolutePath())));
            String st1Tid = String.valueOf(st1.get("id"));
            /* the jdb2 is written at the analysis's save stage — every earlier
               open was closed mid-analysis, so wait for ready before asserting
               the store entry's contents */
            for (int i = 0; i < 40; i++) {
                Object stq = data(post("target.info", Map.of("target", st1Tid))).get("state");
                if ("ready".equals(stq)) break;
                if ("error".equals(stq)) throw new IllegalStateException("fixture failed to analyze");
                Thread.sleep(3000);
            }
            if (entry != null) {
                check(new java.io.File(entry, "test_visionocr.jdb2").isFile(),
                        "the store entry holds the jdb2 INSIDE the store");
            }
            if (legacySeeded && entry != null) {
                check(new java.io.File(entry, "test_visionocr.jdb2").isFile(),
                        "…and the migrated legacy jdb2 landed in the store entry");
            }
            check(String.valueOf(st1.get("jdb2")).contains("/targets/"),
                    "the opened target's jdb2 lives in the store (got " + st1.get("jdb2") + ")");
            check(st1.get("stored") != null && String.valueOf(st1.get("stored")).startsWith("targets/"),
                    "the open answer names the store entry");
            Map<String, Object> pts = data(post("project.targets", Map.of()));
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> items = (List<Map<String, Object>>) pts.get("items");
            check(items.size() == 1, "project.targets lists the member (items=" + items.size() + ")");
            check(Boolean.TRUE.equals(items.get(0).get("open")),
                    "the member shows open:true while its engine is live");
            String memberKey = String.valueOf(items.get(0).get("key"));

            /* target.remove: membership drops, the store entry SURVIVES */
            data(post("target.remove", Map.of("target", memberKey)));
            Map<String, Object> pts2 = data(post("project.targets", Map.of()));
            check(((List<?>) pts2.get("items")).isEmpty(),
                    "target.remove drops the membership row");
            check(entry != null && entry.isDirectory()
                            && new java.io.File(entry, "test_visionocr.jdb2").isFile(),
                    "the store entry (jdb2) survives a membership removal");
            check(errCode(post("target.remove", Map.of("target", memberKey)), "E_NOTFOUND"),
                    "removing a non-member is an honest E_NOTFOUND");

            /* project.delete: confirm-gated wipe + the orphan report. Re-add the
               member first so the report has something to name. */
            st1 = data(post("target.open", Map.of("path", fixture.getAbsolutePath())));
            check(errCode(post("project.delete", Map.of("name", "test_visionocr", "confirm", true)), "E_BUSY"),
                    "project.delete refuses an OPEN project");
            String curProj = String.valueOf(data(post("workspace.state", Map.of())).get("activeProject"));
            data(post("project.close", Map.of("project", curProj)));
            check(errCode(post("project.delete", Map.of("name", "test_visionocr")), "E_SYNTAX"),
                    "project.delete without confirm → E_SYNTAX (the gate)");
            Map<String, Object> del = data(post("project.delete",
                    Map.of("name", "test_visionocr", "confirm", true)));
            check(Boolean.TRUE.equals(del.get("deleted")), "project.delete wiped the dir");
            check(!new java.io.File(home, "projects/test-visionocr").isDirectory(),
                    "the project dir is GONE (not parked)");
            check(String.valueOf(del.get("orphanedTargets")).contains(memberKey),
                    "the orphan report names the now-unreferenced store entry (got " + del.get("orphanedTargets")
                            + " · wanted " + memberKey + ")");
            Map<String, Object> s15 = data(post("workspace.state", Map.of()));
            check(((List<?>) s15.get("projects")).isEmpty() && s15.get("activeProject") == null,
                    "empty after the delete test");

            /* r3 H1: reopening a member FROM ITS STORE PATH must not park the
               live sidecar — the legacy-migration loop's canonical-identity
               skip is what stands between the editor's reopen click and a
               .migrated rename of the search index */
            java.io.File side = new java.io.File(entry, "test_visionocr.apk.workbench");
            java.nio.file.Files.createDirectories(side.toPath());
            java.nio.file.Files.writeString(side.toPath().resolve("tags.json"), "{\"tags\":[]}");
            Map<String, Object> reo = data(post("target.open", Map.of("path",
                    new java.io.File(entry, "test_visionocr.apk").getAbsolutePath())));
            check(reo.get("id") != null, "reopen from the store path accepted");
            check(new java.io.File(side, "tags.json").isFile(),
                    "the live sidecar survived a store-path reopen (r3 H1)");
            boolean migratedSkeleton = false;
            for (java.io.File e3 : java.util.Objects.requireNonNull(entry.listFiles()))
                if (e3.getName().contains("migrated")) migratedSkeleton = true;   // parks land INSIDE the entry (r4 L1)
            check(!migratedSkeleton, "no .migrated parking of the live sidecar");
            data(post("project.close", Map.of("project",
                    String.valueOf(data(post("workspace.state", Map.of())).get("activeProject")))));
        }

        server.stop();
        System.out.println(failures == 0
                ? "M11 IT PASSED: " + checks + " checks green"
                : "M11 IT FAILED: " + failures + " of " + checks + " checks failed");
        System.exit(failures == 0 ? 0 : 1);
    }

    private static void check(boolean ok, String label) {
        checks++;
        System.out.println((ok ? "PASS " : "FAIL ") + label);
        if (!ok) failures++;
    }

    private static boolean errCode(Map<String, Object> resp, String code) {
        if (Boolean.TRUE.equals(resp.get("ok"))) return false;
        Object e = resp.get("error");
        return e instanceof Map<?, ?> m && String.valueOf(m.get("code")).contains(code);
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
