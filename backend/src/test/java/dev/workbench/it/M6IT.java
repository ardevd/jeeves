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
 * M6 integration test (plain main-class, no JUnit): maps — seeded fcm map,
 * orientation ops (summary/outline/paths/find), type registry, CRUD, verdict
 * proposals (llm pending vs human settle), attachevidence, flow.promote,
 * export, persistence across restart. Prints PASS/FAIL; exit 1 on failure.
 * Run: gradle m6IT.
 */
public final class M6IT {

    private static int failures = 0, checks = 0;
    private static final HttpClient http = HttpClient.newHttpClient();
    private static final String BASE = "http://127.0.0.1:" + WorkbenchServer.PORT;
    private static final String MAP = "fcm-attack-surface";

    public static void main(String[] args) throws Exception {
        try {
            run();
        } catch (Throwable t) {
            System.out.println("M6 IT FAILED with exception: " + t);
            t.printStackTrace(System.out);
            System.exit(1);
        }
    }

    private static void run() throws Exception {
        // self-clean: scratch artifacts from prior runs (the test is idempotent).
        // Under WORKBENCH_HOME (hermetic gradle run) the home is wiped pre-run —
        // the direct paths only matter for a hand-run against the live sidecar.
        String home = System.getenv().getOrDefault("WORKBENCH_HOME", "").trim();
        java.util.List<String> bases = home.isEmpty()
                ? java.util.List.of("backend/.workbench/projects/privacy-invite-flow/maps",
                        ".workbench/projects/privacy-invite-flow/maps",
                        "backend/.workbench/projects/p1/maps", ".workbench/projects/p1/maps",
                        "backend/.workbench/maps", ".workbench/maps")
                : java.util.List.of(home + "/projects/privacy-invite-flow/maps");
        for (String f : List.of("m6-scratch.map.json", "m6-flow.map.json"))
            for (String base : bases)
                java.nio.file.Files.deleteIfExists(java.nio.file.Path.of(base, f));

        WorkbenchServer server = new WorkbenchServer(Path.of("web"));
        server.start();
        System.out.println("server up on " + BASE);

        // ---- seeded map + orientation ops
        @SuppressWarnings("unchecked")
        List<Object> maps = dataList(post("map.list", Map.of()));
        check(maps.stream().anyMatch(m -> MAP.equals(((Map<?, ?>) m).get("id"))),
                "map.list includes the seeded " + MAP);
        /* the v7-mockup activeMap seed is GONE (map-lifecycle step 1): a boot
           must not claim a map is open that nobody opened */
        check(data(post("workspace.state", Map.of())).get("activeMap") == null,
                "boot: activeMap null (no hardcoded fcm seed)");
        Map<String, Object> sum = data(post("map.summary", Map.of("id", MAP)));
        check(((Number) sum.get("nodes")).intValue() == 15 && ((Number) sum.get("edges")).intValue() == 15,
                "map.summary: 15 nodes / 15 edges");
        check(((Number) sum.get("proven")).intValue() == 2,
                "map.summary: 2 proven nodes");
        Map<String, Object> ol = data(post("map.outline", Map.of("id", MAP)));
        String text = String.valueOf(ol.get("text"));
        check(text.contains("mtalk") && text.contains("mdm-svc") && text.contains("⚡"),
                "map.outline: layers + nodes + ⚡ fields");
        System.out.println("  outline head: " + text.split("\n")[2]);

        @SuppressWarnings("unchecked")
        List<Object> paths = dataList(post("map.paths",
                Map.of("id", MAP, "from", "mtalk", "to", "natdec")));
        check(!paths.isEmpty() && String.valueOf(paths.get(0)).contains("lockscreen"),
                "map.paths mtalk→natdec: " + paths.get(0));
        @SuppressWarnings("unchecked")
        List<Object> found = dataList(post("map.find", Map.of("id", MAP, "query", "new_password")));
        check(found.size() == 1 && "mdm-svc".equals(((Map<?, ?>) found.get(0)).get("id")),
                "map.find 'new_password' → mdm-svc");
        Map<String, Object> node = data(post("map.node", Map.of("id", MAP, "node", "mdm-svc")));
        check(((List<?>) node.get("fields")).size() == 3 && node.get("chain") != null,
                "map.node mdm-svc: 3 fields + chain");
        Map<String, Object> neigh = data(post("map.neighbors",
                Map.of("id", MAP, "node", "allowlist", "depth", 1)));
        check(((List<?>) neigh.get("nodes")).size() == 11,
                "map.neighbors allowlist d1 → 11 nodes");

        // ---- type registry
        Map<String, Object> types = data(post("map.type.list", Map.of()));
        check(types.get("nodeKinds") != null && types.get("verdicts") != null,
                "map.type.list → nodeKinds/edgeKinds/verdicts/tags");
        Map<String, Object> tadd = data(post("map.type.add",
                Map.of("kind", "tags", "name", "m6probe", "color", "#009E73")));
        check("#009E73".equals(tadd.get("color")), "map.type.add tag m6probe");
        Map<String, Object> tset = data(post("map.type.set",
                Map.of("kind", "verdicts", "name", "authored", "visible", false)));
        check(Boolean.FALSE.equals(tset.get("visible")), "map.type.set authored.visible=false");
        data(post("map.type.set", Map.of("kind", "verdicts", "name", "authored", "visible", true)));

        // ---- CRUD round-trip on a scratch map
        data(post("map.create", Map.of("title", "M6 Scratch", "build", "b1")));
        Map<String, Object> an = data(post("map.addnode",
                Map.of("id", "m6-scratch", "node", Map.of("id", "n1", "kind", "handler",
                        "label", "alpha", "layer", 0))));
        check("handler".equals(an.get("kind")) && "alpha".equals(an.get("label")),
                "map.addnode n1");
        Map<String, Object> ae = data(post("map.addedge",
                Map.of("id", "m6-scratch", "from", "n1", "to", "n1", "kind", "call")));
        check("call".equals(ae.get("kind")), "map.addedge n1→n1 call");
        Map<String, Object> an2 = data(post("map.addnode",
                Map.of("id", "m6-scratch", "node", Map.of("id", "n1", "kind", "sink",
                        "label", "alpha2", "layer", 0))));
        check("sink".equals(an2.get("kind")) && "alpha2".equals(an2.get("label")),
                "map.addnode idempotent upsert");
        Map<String, Object> bad = post("map.addnode",
                Map.of("id", "m6-scratch", "node", Map.of("id", "n9", "kind", "nope")));
        check(Boolean.FALSE.equals(bad.get("ok"))
                        && String.valueOf(bad.get("error")).contains("E_SYNTAX"),
                "unknown kind → E_SYNTAX");

        // ---- verdict: llm pending vs human settle
        Map<String, Object> pv = data(post("map.setverdict",
                Map.of("id", "m6-scratch", "node", "n1", "verdict", "authored",
                        "rationale", "agent probe"), "llm:test"));
        // M7 contract: the op returns the review-queue proposal shape
        check(Boolean.TRUE.equals(pv.get("proposal")) && "pending".equals(pv.get("status"))
                        && pv.get("id") != null,
                "llm setverdict → pending proposal (" + pv.get("id") + ")");
        Map<String, Object> n1 = data(post("map.node", Map.of("id", "m6-scratch", "node", "n1")));
        check(!"authored".equals(n1.get("verdict")) && n1.get("pending") != null,
                "pending not applied (verdict=" + n1.get("verdict") + ")");
        // an agent can NOT reject a pending verdict (app-review-opus P1-5: the
        // reject branch used to delete it outright, pre-review, then file junk)
        Map<String, Object> agentRej = post("map.setverdict",
                Map.of("id", "m6-scratch", "node", "n1", "reject", true), "llm:test");
        check(Boolean.FALSE.equals(agentRej.get("ok"))
                        && String.valueOf(agentRej.get("error")).contains("E_SCOPE"),
                "llm setverdict reject:true → E_SCOPE (humans dispose)");
        Map<String, Object> n1b = data(post("map.node", Map.of("id", "m6-scratch", "node", "n1")));
        check(n1b.get("pending") != null, "pending verdict survives an agent reject attempt");
        // nor can an agent APPLY a verdict around the gate (fix-5 review F7):
        // updatenode/addnode carried verdict straight into the node, unreviewed
        Map<String, Object> viaUpdate = post("map.updatenode",
                Map.of("id", "m6-scratch", "node", "n1", "verdict", "authored"), "llm:test");
        check(Boolean.FALSE.equals(viaUpdate.get("ok"))
                        && String.valueOf(viaUpdate.get("error")).contains("E_SCOPE"),
                "llm updatenode verdict → E_SCOPE (use map.setverdict)");
        Map<String, Object> viaAdd = post("map.addnode",
                Map.of("id", "m6-scratch", "node", Map.of("id", "n2", "kind", "handler",
                        "verdict", "authored")), "llm:test");
        check(Boolean.FALSE.equals(viaAdd.get("ok"))
                        && String.valueOf(viaAdd.get("error")).contains("E_SCOPE"),
                "llm addnode with verdict → E_SCOPE (use map.setverdict)");
        // humans and neutral verdicts are unaffected
        Map<String, Object> okUpd = data(post("map.updatenode",
                Map.of("id", "m6-scratch", "node", "n1", "verdict", "authored")));
        check("authored".equals(okUpd.get("verdict")), "human updatenode verdict applies");
        Map<String, Object> okNone = data(post("map.addnode",
                Map.of("id", "m6-scratch", "node", Map.of("id", "n2", "kind", "handler",
                        "verdict", "none")), "llm:test"));
        check(okNone.get("id") != null, "llm addnode with neutral verdict allowed");
        Map<String, Object> sv = data(post("map.setverdict",
                Map.of("id", "m6-scratch", "node", "n1", "verdict", "authored")));
        check("authored".equals(sv.get("verdict")) && sv.get("pending") == null,
                "human setverdict settles (applied, pending cleared)");
        // agent UX: the vocabulary rides in the message — the tool-result feedback
        // an LLM sees keeps code+message and drops the detail hint
        Map<String, Object> badV = post("map.setverdict",
                Map.of("id", "m6-scratch", "node", "n1", "verdict", "confirmed"));
        String badVerr = String.valueOf(badV.get("error"));
        check(Boolean.FALSE.equals(badV.get("ok")) && badVerr.contains("E_SYNTAX")
                        && badVerr.contains("node authored|mixed|identity|gap|dead|none")
                        && badVerr.contains("field verbatim|constrained|server_regenerated|unknown"),
                "unknown verdict → E_SYNTAX listing node + field vocabulary");
        // field verdict + evidence → proven
        data(post("map.setverdict", Map.of("id", "m6-scratch", "node", "n1",
                "field", "payload", "verdict", "verbatim")));
        Map<String, Object> ev = data(post("map.attachevidence",
                Map.of("id", "m6-scratch", "node", "n1", "field", "payload", "run", "ex1")));
        check(Boolean.TRUE.equals(ev.get("proven")), "attachevidence → proven");
        Map<String, Object> sum2 = data(post("map.summary", Map.of("id", "m6-scratch")));
        check(((Number) sum2.get("proven")).intValue() == 1, "summary proven=1");

        // ---- slug-collision honesty (map-lifecycle step 6): the second create
        // returns the FIRST map's summary, and must SAY it did
        Map<String, Object> again = data(post("map.create",
                Map.of("title", "M6 Scratch", "build", "b1")));           // idempotent
        check(Boolean.TRUE.equals(again.get("existing")),
                "map.create on an existing slug → existing:true");
        Map<String, Object> twin = data(post("map.create", Map.of("title", "M6: Scratch")));
        check("m6-scratch".equals(twin.get("id")) && Boolean.TRUE.equals(twin.get("existing")),
                "a DIFFERENT title slugging to the same id → existing:true (no silent adoption)");

        // ---- diff
        Map<String, Object> dup = data(post("map.addnode",
                Map.of("id", "m6-scratch", "node", Map.of("id", "n2", "kind", "sink",
                        "label", "beta", "layer", 1))));
        check("n2".equals(dup.get("id")), "map.addnode n2");
        Map<String, Object> diff = data(post("map.diff", Map.of("a", MAP, "b", "m6-scratch")));
        check(!((List<?>) diff.get("added")).isEmpty() && !((List<?>) diff.get("removed")).isEmpty(),
                "map.diff fcm vs scratch → added+removed");

        // ---- flow.promote from an M4-style flow
        Map<String, Object> flow = data(post("flow.add",
                Map.of("target", "t1", "name", "m6-flow", "source", "MailSyncService.handleIntent",
                        "sink", "MessageSyncHandler.onMessage", "hops", "InviteCipher.open")));
        Map<String, Object> promoted = data(post("flow.promote",
                Map.of("target", "t1", "id", flow.get("id"))));
        check("m6-flow".equals(promoted.get("map"))
                        && ((Number) promoted.get("nodes")).intValue() == 3
                        && ((Number) promoted.get("edges")).intValue() == 2,
                "flow.promote → map m6-flow (3 nodes, 2 edges)");

        // ---- export
        Map<String, Object> md = data(post("map.export", Map.of("id", MAP, "fmt", "md")));
        check(String.valueOf(md.get("text")).contains("# FCM push attack surface")
                        && String.valueOf(md.get("text")).contains("```"),
                "map.export md → poster markdown");
        Map<String, Object> js = data(post("map.export", Map.of("id", MAP, "fmt", "json")));
        check(String.valueOf(js.get("text")).contains("\"fcm-attack-surface\""),
                "map.export json → map file content");

        // ---- persistence: sidecar files re-read from disk
        Map<String, Object> sum3 = data(post("map.summary", Map.of("id", "m6-scratch")));
        check(((Number) sum3.get("nodes")).intValue() == 2,
                "sidecar persisted (2 nodes in scratch)");
        MapStoreProbe probe = new MapStoreProbe();
        check(probe.scratchNodes() == 2 && probe.fcmNodes() == 15,
                "sidecar files re-read: scratch=2, fcm=15 nodes");

        // ---- project scoping (map-r4 fix): maps are per-project artifacts
        // (GRAPHS_SPEC §3) — a fresh project starts mapless, its maps stay out
        // of p1, and closing it lands back on p1's own store + activeMap
        /* p1's activation is no longer seeded at boot — open it explicitly, or
           the per-project invariant below would assert on nothing */
        check(MAP.equals(data(post("map.open", Map.of("id", MAP))).get("activeMap")),
                "map.open sets the project's activeMap");

        // ---- map.close (map-lifecycle step 2): ACTIVATION, not existence
        check(MAP.equals(data(post("map.close", Map.of("id", "no-such-map"))).get("activeMap")),
                "map.close {other id} leaves the open map alone");
        check(data(post("map.close", Map.of())).get("activeMap") == null,
                "map.close (bare) clears activeMap");
        Map<String, Object> reclose = post("map.close", Map.of());
        check(Boolean.TRUE.equals(reclose.get("ok"))
                        && data(reclose).get("activeMap") == null,
                "map.close is idempotent (nothing open is a success, not E_NOTFOUND)");
        check(dataList(post("map.list", Map.of())).stream()
                        .anyMatch(m -> MAP.equals(((Map<?, ?>) m).get("id"))),
                "map.close touches no map file (still in map.list)");
        data(post("map.open", Map.of("id", MAP)));      // re-open for the invariants below

        /* the persisted activation (map-lifecycle step 3): session.json under
           the project's dirFor sidecar, written off the Workspace monitor */
        if (!home.isEmpty()) {
            String sess = java.nio.file.Files.readString(java.nio.file.Path.of(
                    home, "projects", "privacy-invite-flow", "session.json"));
            check(sess.contains(MAP), "map.open persists activeMap to session.json");
        }
        Map<String, Object> opened = data(post("project.open", Map.of("name", "m6-isolation")));
        check(opened.get("activeMap") == null, "fresh project: activeMap null (no inherited map)");
        List<Object> none = dataList(post("map.list", Map.of()));
        check(none.isEmpty(), "fresh project: map.list empty (no global-store leak)");
        Map<String, Object> pm = data(post("map.create", Map.of("title", "M6 Isolated", "build", "b1")));
        String pmId = String.valueOf(pm.get("id"));
        data(post("map.addnode", Map.of("id", pmId, "node",
                Map.of("id", "x1", "kind", "handler", "label", "iso", "layer", 0))));
        Map<String, Object> backState = data(post("project.switch", Map.of("project", "p1")));
        check(MAP.equals(backState.get("activeMap")), "p1 keeps its own activeMap across switches");
        List<Object> back = dataList(post("map.list", Map.of()));
        check(back.stream().anyMatch(m -> MAP.equals(((Map<?, ?>) m).get("id")))
                        && back.stream().noneMatch(m -> pmId.equals(((Map<?, ?>) m).get("id"))),
                "p1 list: fcm present, scratch project's map absent");
        data(post("project.switch", Map.of("project", "m6-isolation")));
        List<Object> iso = dataList(post("map.list", Map.of()));
        check(iso.size() == 1 && pmId.equals(((Map<?, ?>) iso.get(0)).get("id")),
                "scratch project's map persists in its own store");
        data(post("project.close", Map.of("project", "m6-isolation")));
        List<Object> fin = dataList(post("map.list", Map.of()));
        check(fin.stream().anyMatch(m -> MAP.equals(((Map<?, ?>) m).get("id"))),
                "closing the scratch project lands on p1's store");
        // slug-keyed sidecar (map-r5 F1): project ids remint per boot, so the
        // sidecar keys on the NAME — reopening a same-name project recovers
        // its maps instead of adopting whatever a dead pid left behind
        data(post("project.open", Map.of("name", "m6-isolation")));
        List<Object> reopened = dataList(post("map.list", Map.of()));
        check(reopened.stream().anyMatch(m -> pmId.equals(((Map<?, ?>) m).get("id"))),
                "reopened same-name project recovers its maps (slug-keyed store)");
        data(post("project.close", Map.of("project", "m6-isolation")));

        // ---- map-r6 regression coverage (review findings 1-3)
        Map<String, Object> p1named = data(post("project.open", Map.of("name", "p1")));
        check(dataList(post("map.list", Map.of())).isEmpty(),
                "a project NAMED 'p1' starts mapless (no id-namespace adoption)");
        String p1namedId = null;
        for (Object o : (List<?>) p1named.get("projects")) {
            Map<?, ?> row = (Map<?, ?>) o;
            if (Boolean.TRUE.equals(row.get("active"))) p1namedId = String.valueOf(row.get("id"));
        }
        data(post("project.close", Map.of("project", p1namedId)));   // by ID — the NAME 'p1' matches the seeded project first
        Map<String, Object> nonAscii = post("map.create", Map.of("title", "מפה"));
        check(Boolean.FALSE.equals(nonAscii.get("ok"))
                        && String.valueOf(nonAscii.get("error")).contains("E_SYNTAX"),
                "non-ASCII map title → E_SYNTAX (no empty-id dotfile)");
        // close + reopen the SEEDED project: name keying must recover its maps
        // (id-keying stranded them in projects/p1 on reopen — map-r6 #2)
        data(post("project.open", Map.of("name", "m6-holder")));
        data(post("project.close", Map.of("project", "privacy-invite-flow")));
        data(post("project.open", Map.of("name", "privacy-invite-flow")));
        List<Object> rec = dataList(post("map.list", Map.of()));
        check(rec.stream().anyMatch(m -> MAP.equals(((Map<?, ?>) m).get("id"))),
                "close+reopen of the seeded project recovers its maps (name-keyed)");
        data(post("project.close", Map.of("project", "m6-holder")));

        // slug-twin split (map-r6 #4 — map-r7 F11: the r6 'p1'-name check was
        // vacuous under a hermetic home; THIS is the adoption-trap coverage)
        data(post("project.open", Map.of("name", "M6 Iso")));
        String twinA = String.valueOf(data(post("map.create", Map.of("title", "M6 Twin A"))).get("id"));
        data(post("project.open", Map.of("name", "m6-iso")));
        check(dataList(post("map.list", Map.of())).isEmpty(), "slug twin starts mapless");
        String twinB = String.valueOf(data(post("map.create", Map.of("title", "M6 Twin B"))).get("id"));
        data(post("project.switch", Map.of("project", "M6 Iso")));
        List<Object> tl = dataList(post("map.list", Map.of()));
        check(tl.size() == 1 && twinA.equals(((Map<?, ?>) tl.get(0)).get("id")),
                "slug twins have disjoint stores");
        if (!home.isEmpty()){
            String marker = java.nio.file.Files.readString(
                    java.nio.file.Path.of(home, "projects", "m6-iso", ".project-name"));
            check("M6 Iso".equals(marker), "slug dir carries the first opener's exact name");
        }
        data(post("project.switch", Map.of("project", "m6-iso")));
        List<Object> tl2 = dataList(post("map.list", Map.of()));
        check(tl2.size() == 1 && twinB.equals(((Map<?, ?>) tl2.get(0)).get("id")),
                "slug twin keeps its OWN map (not just disjoint)");
        data(post("project.close", Map.of("project", "m6-iso")));
        data(post("project.close", Map.of("project", "M6 Iso")));

        /* ---- close survives a project round trip (map-lifecycle step 3)
           A closed map must STAY closed: the old map.list[0] fallback and an
           unpersisted close both re-opened something on the next look. */
        data(post("map.open", Map.of("id", MAP)));
        check(data(post("map.close", Map.of())).get("activeMap") == null, "closed → activeMap null");
        data(post("project.open", Map.of("name", "m6-session")));
        data(post("project.switch", Map.of("project", "privacy-invite-flow")));
        check(data(post("workspace.state", Map.of())).get("activeMap") == null,
                "still closed after switching project away and back");
        if (!home.isEmpty()) {
            String sess = java.nio.file.Files.readString(java.nio.file.Path.of(
                    home, "projects", "privacy-invite-flow", "session.json"));
            check(sess.contains("\"activeMap\":null"),
                    "map.close persists the CLOSE (session.json activeMap null)");
        }
        data(post("project.close", Map.of("project", "m6-session")));

        /* ---- map.delete (map-lifecycle step 4): confirm gate, file PARKED,
           delete of the open map implies close */
        Map<String, Object> doomed = data(post("map.create", Map.of("title", "M6 Doomed")));
        check(Boolean.FALSE.equals(doomed.get("existing")), "a genuinely new map → existing:false");
        String delId = String.valueOf(doomed.get("id"));
        data(post("map.open", Map.of("id", delId)));
        Map<String, Object> unconfirmed = post("map.delete", Map.of("id", delId));
        check(Boolean.FALSE.equals(unconfirmed.get("ok"))
                        && String.valueOf(unconfirmed.get("error")).contains("E_SYNTAX"),
                "map.delete without confirm → E_SYNTAX (MCP clients reach the full registry)");
        check(dataList(post("map.list", Map.of())).stream()
                        .anyMatch(m -> delId.equals(((Map<?, ?>) m).get("id"))),
                "unconfirmed delete changed nothing");
        Map<String, Object> del = data(post("map.delete", Map.of("id", delId, "confirm", true)));
        check(delId.equals(del.get("id")) && del.get("parked") != null,
                "map.delete {confirm} → {id, parked}");
        check(java.nio.file.Files.isRegularFile(java.nio.file.Path.of(String.valueOf(del.get("parked"))))
                        && String.valueOf(del.get("parked")).contains(".map.json.deleted-"),
                "the map file is PARKED, not destroyed: " + del.get("parked"));
        check(!java.nio.file.Files.exists(java.nio.file.Path.of(
                        String.valueOf(del.get("parked")).replaceFirst("\\.deleted-.*$", ""))),
                "the live sidecar name is gone (loadMaps' *.map.json filter ignores the parked one)");
        check(dataList(post("map.list", Map.of())).stream()
                        .noneMatch(m -> delId.equals(((Map<?, ?>) m).get("id"))),
                "deleted map drops out of map.list");
        check(data(post("workspace.state", Map.of())).get("activeMap") == null,
                "deleting the OPEN map implies close");
        Map<String, Object> gone = post("map.delete", Map.of("id", delId, "confirm", true));
        check(Boolean.FALSE.equals(gone.get("ok"))
                        && String.valueOf(gone.get("error")).contains("E_NOTFOUND"),
                "re-delete → E_NOTFOUND (delete is existence, not activation)");

        /* ---- restart persistence (M4IT/M10bIT stop + new pattern): the
           activation is restored from session.json, not re-seeded */
        data(post("map.open", Map.of("id", MAP)));
        server.stop();
        server = new WorkbenchServer(Path.of("web"));
        server.start();
        System.out.println("  server restarted on " + BASE);
        check(MAP.equals(data(post("workspace.state", Map.of())).get("activeMap")),
                "after restart: activeMap restored from session.json");
        data(post("map.close", Map.of()));
        server.stop();
        server = new WorkbenchServer(Path.of("web"));
        server.start();
        check(data(post("workspace.state", Map.of())).get("activeMap") == null,
                "after restart: a closed map stays closed");

        /* ---- sidecar read discipline (fix-1-r3 #11): the three read-failure
           arms, end to end. HERMETIC ONLY — a hand-run has no WORKBENCH_HOME
           and must never corrupt the live sidecar. */
        if (!home.isEmpty()) {
            java.nio.file.Path mapsDir = java.nio.file.Path.of(home,
                    "projects/privacy-invite-flow/maps");
            java.nio.file.Path projDir = mapsDir.getParent();
            java.nio.file.Path homeDir = java.nio.file.Path.of(home);

            /* (a) torn map JSON (parse failure) → parked, map absent */
            java.nio.file.Files.writeString(mapsDir.resolve("m6-torn.map.json"),
                    "{\"id\":\"m6-torn\",\"nodes\":[");
            /* (b) wrong-shape maptypes.json (valid JSON, not an object) →
               parked, defaults served */
            java.nio.file.Files.writeString(projDir.resolve("maptypes.json"), "[1,2,3]");
            /* (c) torn layouts.json → parked, empty list */
            java.nio.file.Files.writeString(homeDir.resolve("layouts.json"), "{\"x\":");
            /* (d) UNREADABLE map file → the transient arm: loud, NOT parked,
               absent from map.list; the store remembers the filename as
               degraded so the next persist of that id parks the original
               BEFORE overwriting (fix-1-r3 #1) */
            java.nio.file.Files.writeString(mapsDir.resolve("m6-unreadable.map.json"),
                    "{\"id\":\"m6-unreadable\",\"name\":\"m6-unreadable\",\"nodes\":[],\"edges\":[]}");
            java.io.File unreadable = mapsDir.resolve("m6-unreadable.map.json").toFile();
            /* gate the arm on the permission actually applying — running as
               root reads straight through chmod-000 and the checks would pass
               vacuously or fail confusingly (fix-1-r4, review #7) */
            boolean unreadableArmed = unreadable.setReadable(false);
            if (!unreadableArmed)
                System.out.println("  SKIP unreadable-map arm: setReadable(false) refused (root?)");

            server.stop();
            server = new WorkbenchServer(Path.of("web"));
            server.start();

            check(!java.nio.file.Files.isRegularFile(mapsDir.resolve("m6-torn.map.json"))
                            && parkedCopy(mapsDir, "m6-torn.map.json"),
                    "torn map file parked aside, live name gone");
            check(dataList(post("map.list", Map.of())).stream()
                            .noneMatch(m -> "m6-torn".equals(((Map<?, ?>) m).get("id"))),
                    "torn map absent from map.list");
            check(parkedCopy(projDir, "maptypes.json"),
                    "wrong-shape maptypes.json parked");
            Map<String, Object> typeRows = data(post("map.type.list", Map.of()));
            check(typeRows.get("nodeKinds") instanceof List
                            && ((List<?>) typeRows.get("nodeKinds")).stream()
                                .anyMatch(k -> "endpoint".equals(((Map<?, ?>) k).get("name"))),
                    "maptypes serve defaults after parking the wrong-shape file");
            check(parkedCopy(homeDir, "layouts.json"), "torn layouts.json parked");
            check(dataList(post("layout.list", Map.of())).isEmpty(),
                    "layout.list empty after the torn file was parked");

            check(java.nio.file.Files.isRegularFile(mapsDir.resolve("m6-unreadable.map.json"))
                            && !parkedCopy(mapsDir, "m6-unreadable.map.json"),
                    "unreadable (transient) map left in place, NOT parked");
            if (unreadableArmed)
                check(dataList(post("map.list", Map.of())).stream()
                                .noneMatch(m -> "m6-unreadable".equals(((Map<?, ?>) m).get("id"))),
                        "unreadable map absent from map.list (loud degrade)");
            /* recreate the map under the SAME id: the degraded persist parks the
               on-disk original as evidence, then writes the fresh map */
            unreadable.setReadable(true);
            Map<String, Object> recreated = data(post("map.create", Map.of("title", "M6 Unreadable")));
            check("m6-unreadable".equals(String.valueOf(recreated.get("id"))),
                    "map.create re-creates m6-unreadable over the degraded slot");
            if (unreadableArmed) {
                check(parkedCopy(mapsDir, "m6-unreadable.map.json"),
                        "degraded persist parked the pre-existing file as evidence");
                check(java.nio.file.Files.isRegularFile(mapsDir.resolve("m6-unreadable.map.json")),
                        "fresh m6-unreadable.map.json written after the evidence park");
            }
            data(post("map.delete", Map.of("id", "m6-unreadable", "confirm", true)));

            /* (e) the charset arm (review #7): invalid UTF-8 is PERMANENT damage
               — parked at load, NOT the transient left-in-place arm, and no
               degraded flag follows (the evidence is already aside) */
            java.nio.file.Files.write(mapsDir.resolve("m6-charset.map.json"),
                    new byte[] { (byte) 0xFF, (byte) 0xFE, 0x41, 0x42 });
            /* (f) LayoutStore's degraded arm (review #7: only MapStore.persist
               was covered): an unreadable layouts.json is NOT parked; the first
               layout.save parks the original as evidence, then writes. Park
               COUNT because case (c) already parked one layouts.json above. */
            int layoutParksBefore = parkedCount(homeDir, "layouts.json");
            java.nio.file.Files.writeString(homeDir.resolve("layouts.json"),
                    "{\"demo\":{\"ts\":\"t0\",\"layout\":{}}}");
            java.io.File layoutRo = homeDir.resolve("layouts.json").toFile();
            boolean layoutArmed = layoutRo.setReadable(false);
            if (!layoutArmed)
                System.out.println("  SKIP unreadable-layouts arm: setReadable(false) refused (root?)");

            server.stop();
            server = new WorkbenchServer(Path.of("web"));
            server.start();

            check(parkedCopy(mapsDir, "m6-charset.map.json")
                            && !java.nio.file.Files.isRegularFile(mapsDir.resolve("m6-charset.map.json")),
                    "invalid-UTF-8 map file parked (charset = permanent, not the transient arm)");
            check(dataList(post("map.list", Map.of())).stream()
                            .noneMatch(m -> "m6-charset".equals(((Map<?, ?>) m).get("id"))),
                    "charset-damaged map absent from map.list");
            if (layoutArmed) {
                check(java.nio.file.Files.isRegularFile(homeDir.resolve("layouts.json"))
                                && parkedCount(homeDir, "layouts.json") == layoutParksBefore,
                        "unreadable layouts.json left in place, NOT parked (transient arm)");
                check(dataList(post("layout.list", Map.of())).isEmpty(),
                        "layout.list empty under the degraded load");
                layoutRo.setReadable(true);
                data(post("layout.save", Map.of("name", "m6-demo", "layout", Map.of())));
                check(parkedCount(homeDir, "layouts.json") == layoutParksBefore + 1,
                        "degraded layout.save parked the pre-existing file as evidence");
                check(dataList(post("layout.list", Map.of())).size() == 1,
                        "fresh layouts.json written after the evidence park");
            }

            /* (g) a torn runs jsonl line must not kill the BOOT (fix-1-r5 #5:
               Json.parse's RuntimeException propagated out of new RunStore →
               new RunsManager → the server never started) */
            java.nio.file.Path runsDir = homeDir.resolve("runs");
            java.nio.file.Files.createDirectories(runsDir);
            java.nio.file.Files.writeString(runsDir.resolve("stacks.jsonl"),
                    "{\"stackId\":\"st1\",\"frames\":[]}\n{\"stackId\":\"st2\",\"fram\n");
            server.stop();
            server = new WorkbenchServer(Path.of("web"));
            server.start();
            check(data(post("workspace.state", Map.of())).get("targets") != null,
                    "server boots over a torn runs jsonl (torn line skipped, not fatal)");

            /* (g2) HIGH-1: a hand-edited bps.json ("hits": null) plus a hit row
               referencing it hit an unguarded (Number) cast in load() — the same
               boot crash by a different trigger */
            java.nio.file.Files.writeString(runsDir.resolve("bps.json"),
                    "[{\"id\":\"bp1\",\"target\":\"t1\",\"origSig\":\"Lx;->a()V\",\"hits\":null}]");
            java.nio.file.Files.writeString(runsDir.resolve("index.json"),
                    "[{\"execId\":\"ex1\"}]");
            java.nio.file.Files.writeString(runsDir.resolve("ex1.jsonl"),
                    "{\"hitId\":\"h1\",\"bpId\":\"bp1\"}\n");
            server.stop();
            server = new WorkbenchServer(Path.of("web"));
            server.start();
            check(data(post("workspace.state", Map.of())).get("targets") != null,
                    "server boots over a hand-edited bps.json (hits:null) + hit row");
        }

        System.out.println();
        if (failures > 0) {
            System.out.println("M6 IT FAILED: " + failures + "/" + checks + " checks failed");
            System.exit(1);
        }
        System.out.println("M6 IT PASSED: " + checks + " checks green");
        System.exit(0);
    }

    /** Direct file-level persistence probe (avoids a second bind on the port). */
    private static final class MapStoreProbe {
        int count(String file, String key) throws Exception {
            String home = System.getenv().getOrDefault("WORKBENCH_HOME", "").trim();
            java.nio.file.Path p;
            if (!home.isEmpty()){
                /* hermetic run: the home path or NOTHING — falling back to the
                   live sidecar would assert against the user's real maps
                   (opus map-r5 F9) */
                p = java.nio.file.Path.of(home, "projects/privacy-invite-flow/maps", file);
            } else {
                p = java.nio.file.Path.of("backend/.workbench/projects/privacy-invite-flow/maps", file);
                if (!java.nio.file.Files.isRegularFile(p))
                    p = java.nio.file.Path.of(".workbench/projects/privacy-invite-flow/maps", file);
                if (!java.nio.file.Files.isRegularFile(p))
                    p = java.nio.file.Path.of("backend/.workbench/projects/p1/maps", file);
                if (!java.nio.file.Files.isRegularFile(p))
                    p = java.nio.file.Path.of(".workbench/projects/p1/maps", file);
                if (!java.nio.file.Files.isRegularFile(p))
                    p = java.nio.file.Path.of("backend/.workbench/maps", file);   // pre-migration layout
                if (!java.nio.file.Files.isRegularFile(p))
                    p = java.nio.file.Path.of(".workbench/maps", file);
            }
            Object parsed = Json.parse(java.nio.file.Files.readString(p));
            @SuppressWarnings("unchecked")
            Map<String, Object> m = (Map<String, Object>) parsed;
            return ((List<?>) m.get(key)).size();
        }
        int scratchNodes() throws Exception { return count("m6-scratch.map.json", "nodes"); }
        int fcmNodes() throws Exception { return count("fcm-attack-surface.map.json", "nodes"); }
    }

    private static void check(boolean ok, String label) {
        checks++;
        System.out.println((ok ? "PASS " : "FAIL ") + label);
        if (!ok) failures++;
    }

    /** A SidecarIO.parkCorrupt evidence copy of `name` exists in dir (fix-1-r3 #11). */
    private static boolean parkedCopy(java.nio.file.Path dir, String name) throws Exception {
        return parkedCount(dir, name) > 0;
    }

    /** How many evidence copies of `name` are parked in dir. */
    private static int parkedCount(java.nio.file.Path dir, String name) throws Exception {
        try (java.util.stream.Stream<java.nio.file.Path> s = java.nio.file.Files.list(dir)) {
            return (int) s.filter(p -> p.getFileName().toString().startsWith(name + ".corrupt-"))
                    .count();
        }
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
