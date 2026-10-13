package dev.workbench.it;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.workbench.course.Course;
import dev.workbench.course.CourseClient;
import dev.workbench.course.CoursePolicy;
import dev.workbench.registry.Json;
import dev.workbench.server.WorkbenchServer;
import java.io.File;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Course integration test (plain main-class; hermetic home, port 7819). A FIXTURE class server runs
 * in-process and speaks the training server's A10 contract (docs/jeeves-integration-contract.md,
 * the class server's integration contract, read 2026-10-09): auth `/internal/grant` (policy-gated, machine and
 * browser grants with the owner's account and generation), `/internal/jeeves/context` (the room
 * credential's own context: owner, generation, policy, access), `/internal/jeeves/observe` (the
 * exact observation fields), store `/v1/events` (dedup by id, 409 on a body change, `durable` in the
 * response), the classroom's `/v1/room/context` (jeeves-classroom-v1: the activity identity and the
 * browser handoff URLs) and `/health` (the gateway's scheduler state + route, the store's status).
 * The workbench boots in course mode against it, on the mock engine (allow_mock — tests only), and the
 * checks walk the contract:
 *
 *   0. locked: the instructor has not released access — nothing but course.status answers; release
 *      makes the room serve within the poll
 *   1. the identity gate: the edge's credential, a grant on BOTH paths, cached 2 s, cross-room,
 *      stale generation, previous owner
 *   2. health per service (OPEN is ok; CAPTURE_BLOCKED is the word), the model route apart,
 *      the manifest
 *   3. the classroom's activity is read-only and versioned
 *   4. a captured Day 1 mission: envelope with owner/generation/policy/activity identity, exact
 *      payloads, receipts; strict admission, backpressure, lost ack, restart recovery, redaction
 *   5. observations with the exact fields; /healthz
 *   6. cancellation by the user
 *   7. the fence: access withdrawn mid-mission → stopped within 5 s, draining until confirmed,
 *      stream closed, new work refused, no restart on re-enable
 *   8. an auth outage: streams close after the window, requests wait, recovery
 *   9. reset is an edit rollback (never the capture); share/help/progress are handoffs with the
 *      provenance; progress has no WORKING
 *  10. confinement: fs.list, target.open, host-only ops, cli missions
 *  11. the cleanup receipt; the evidence packet route; the export seam
 *  12. last: the credential's generation fenced → nothing served
 */
public final class CourseIT {

    private static int failures = 0, checks = 0;
    private static final HttpClient http = HttpClient.newHttpClient();
    private static final String BASE = "http://127.0.0.1:" + WorkbenchServer.PORT;

    // ------------------------------------------------------------ the fixture class server
    static final class ClassServer {
        static final String OWNER = "a1b2c3d4e5f60718293a4b5c6d7e8f90", GEN = "0f1e2d3c4b5a69788796a5b4c3d2e1f0", SESSION = "fixture-course-1";
        final HttpServer srv;
        final Map<String, Map<String, Object>> grants = new ConcurrentHashMap<>();   // grant value → answer
        final Map<String, String> events = new ConcurrentHashMap<>();                 // event id → request sha
        final List<Map<String, Object>> observations = Collections.synchronizedList(new ArrayList<>());
        final AtomicInteger seq = new AtomicInteger();
        volatile int storeFailNext = 0;      // 503 for the next N events
        volatile boolean storeDown = false;  // connection-level failure
        volatile boolean dropAckOnce = false;
        volatile int grantCalls = 0, eventCalls = 0, contextCalls = 0, observeRejected = 0;
        volatile boolean enabled = false;    // the instructor's release (jeeves-policy-v1.enabled)
        volatile int revision = 0;
        volatile boolean authDown = false;   // the auth service away: connections close without an answer
        volatile boolean fencedContext = false;   // the room credential's generation is fenced (403) — permanent
        volatile boolean suspended = false;       // the owner suspended (403 "Room owner revoked") — lifts
        volatile int lastSequence = -1;           // the server keeps the last observation's sequence per generation
        volatile String gatewayState = "OPEN";
        volatile String activityId = "E3"; volatile int activityRevision = 4;
        final String authTok = "wb_auth_" + "x".repeat(36), storeTok = "wb_store_" + "y".repeat(35), classroomTok = "wb_class_" + "c".repeat(35),
                gatewayTok = "wb_gw_" + "g".repeat(38), nginxTok = "wb_nginx_" + "n".repeat(35);

        ClassServer() throws Exception {
            // the edge's grants: browser (alice), the room's machine client, another room, a stale generation, a previous owner
            grants.put("g-alice", grant(7, "class", OWNER, GEN, "alice", false));
            grants.put("g-kimi", grant(7, "class", OWNER, GEN, "alice", true));
            grants.put("g-mallory", grant(8, "class", "ffffffffffffffffffffffffffffffff", "eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee", "mallory", false));
            grants.put("g-old", grant(7, "class", OWNER, "f".repeat(32), "alice", false));
            grants.put("g-other", grant(7, "class", "9".repeat(32), GEN, "bob", false));
            srv = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 16);
            srv.createContext("/health", ex -> reply(ex, 200, "{\"ok\":true,\"status\":\"ready\",\"state\":\"" + gatewayState + "\",\"student\":{\"running\":0,\"waiting\":0},"
                    + "\"route\":{\"provider\":\"managed\",\"qualification\":\"unconfigured\",\"state\":\"blocked\",\"reason\":\"Tokenizer and cancellation/lifecycle adapter not qualified\"}}"));
            srv.createContext("/internal/grant", ex -> {
                grantCalls++;
                if (authDown) { ex.close(); return; }
                if (!bearer(ex, authTok)) { reply(ex, 401, "{\"error\":\"bad caller\"}"); return; }
                Map<String, Object> body = Json.parseObject(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                Map<String, Object> g = grants.get(String.valueOf(body.get("grant")));
                if (g == null) { reply(ex, 401, "{\"error\":\"unknown or expired grant\"}"); return; }
                if (!g.get("audience").equals(body.get("audience"))) { reply(ex, 403, "{\"error\":\"audience\"}"); return; }
                if (!enabled && !"admin".equals(g.get("realm"))) { reply(ex, 403, "{\"error\":\"Analysis access has not been released\"}"); return; }
                Map<String, Object> out = new LinkedHashMap<>(g); out.put("course_session_id", SESSION); out.put("policy_revision", revision);
                reply(ex, 200, Json.write(out));
            });
            srv.createContext("/internal/jeeves/context", ex -> {
                contextCalls++;
                if (authDown) { ex.close(); return; }
                if (!bearer(ex, authTok)) { reply(ex, 401, "{\"error\":\"bad caller\"}"); return; }
                if (fencedContext) { reply(ex, 403, "{\"error\":\"Room credential generation is fenced\"}"); return; }
                if (suspended) { reply(ex, 403, "{\"error\":\"Room owner revoked\"}"); return; }
                Map<String, Object> policy = new LinkedHashMap<>();
                policy.put("schema", "jeeves-policy-v1"); policy.put("course_session_id", SESSION); policy.put("revision", revision); policy.put("enabled", enabled);
                policy.put("changed_by", null); policy.put("changed_at", null); policy.put("enforcement_seconds", 5);
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("schema", "jeeves-room-v1"); out.put("account_id", OWNER); out.put("workspace_generation", GEN); out.put("room", 7); out.put("handle", "alice"); out.put("realm", "class");
                out.put("disabled", false); out.put("room_state", "assigned"); out.put("course_session_id", SESSION); out.put("policy", policy); out.put("access_allowed", enabled); out.put("valid_for_seconds", 2);
                out.put("next_observation_sequence", lastSequence + 1);
                reply(ex, 200, Json.write(out));
            });
            srv.createContext("/internal/jeeves/observe", ex -> {
                if (authDown) { ex.close(); return; }
                if (!bearer(ex, authTok)) { reply(ex, 401, "{\"error\":\"bad caller\"}"); return; }
                Map<String, Object> body = Json.parseObject(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                Set<String> fields = Set.of("sequence", "runtime_revision", "package_sha256", "target_sha256", "components", "active_missions", "open_streams", "pending_capture", "policy_revision", "fenced", "cancellation_unconfirmed");
                boolean okShape = body.keySet().equals(fields) && body.get("components") instanceof Map<?, ?> c && c.keySet().equals(Set.of("engine", "target", "capture", "model", "classroom"))
                        && String.valueOf(body.get("runtime_revision")).matches("[0-9a-f]{40}") && String.valueOf(body.get("package_sha256")).matches("[0-9a-f]{64}") && String.valueOf(body.get("target_sha256")).matches("[0-9a-f]{64}");
                if (okShape) for (Object v : ((Map<?, ?>) body.get("components")).values())
                    if (!(v instanceof Map<?, ?> cm && cm.keySet().equals(Set.of("state", "detail")) && List.of("ready", "degraded", "blocked", "unavailable", "starting").contains(cm.get("state"))
                            && cm.get("detail") instanceof String ds && ds.getBytes(StandardCharsets.UTF_8).length <= 300)) okShape = false;
                if (!okShape) { observeRejected++; reply(ex, 400, "{\"error\":\"Exact observation fields required\"}"); return; }
                /* the server's rule: the sequence must advance within a generation (409 otherwise) */
                int seq = body.get("sequence") instanceof Number sn ? sn.intValue() : -1;
                if (seq <= lastSequence) { observeRejected++; reply(ex, 409, "{\"error\":\"Observation sequence must advance\"}"); return; }
                lastSequence = seq;
                observations.add(body);
                reply(ex, 200, Json.write(Map.of("received", true, "received_at", System.currentTimeMillis() / 1000.0, "valid_for_seconds", 15)));
            });
            srv.createContext("/v1/events", ex -> {
                eventCalls++;
                if (storeDown) { ex.close(); return; }
                if (!bearer(ex, storeTok)) { reply(ex, 401, "{\"error\":\"bad caller\"}"); return; }
                if (storeFailNext > 0) { storeFailNext--; reply(ex, 503, "{\"error\":\"backlog\"}"); return; }
                String raw = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                Map<String, Object> body = Json.parseObject(raw);
                if (!body.containsKey("eval_tainted")) { reply(ex, 400, "{\"error\":\"eval_tainted required\"}"); return; }
                String id = "room-7:" + body.get("event_id");
                String sha = sha(Json.write(Map.of("kind", body.get("kind"), "payload", body.get("payload"), "eval_tainted", body.get("eval_tainted"))));
                boolean dup = events.containsKey(id);
                if (dup && !events.get(id).equals(sha)) { reply(ex, 409, "{\"error\":\"another body under this id\"}"); return; }
                if (!dup) events.put(id, sha);
                if (dropAckOnce) { dropAckOnce = false; ex.close(); return; }   // accepted, acknowledgement lost
                reply(ex, 200, Json.write(Map.of("event_id", id, "seq", seq.incrementAndGet(), "duplicate", dup, "durable", true)));
            });
            srv.createContext("/v1/lookup", ex -> reply(ex, 200, "{\"event\":null}"));
            srv.createContext("/v1/room/context", ex -> {
                if (!bearer(ex, classroomTok)) { reply(ex, 403, "{\"error\":\"Read-only room context required\"}"); return; }
                String raw = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8).trim();
                if (!raw.isEmpty() && !raw.equals("{}")) { reply(ex, 403, "{\"error\":\"Read-only room context required\"}"); return; }
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("schema", "jeeves-classroom-v1");
                out.put("owner", Map.of("account_id", OWNER, "workspace_generation", GEN, "room", 7, "course_session_id", SESSION));
                out.put("activity", Map.of("id", activityId, "revision", activityRevision, "sha256", sha("activity:" + activityId + ":" + activityRevision)));
                out.put("teaching_revision", 9);
                Map<String, Object> actions = new LinkedHashMap<>();
                for (String[] a : new String[][] { { "help", "messages" }, { "ready", "today" }, { "withdraw", "today" }, { "finding", "findings" } })   // the classroom's keys (classroom_service.py)
                    actions.put(a[0], Map.of("sent", false, "browser", "https://class.example/classroom/" + a[1], "reason", "human_action_required"));
                out.put("actions", actions);
                reply(ex, 200, Json.write(out));
            });
            srv.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
            srv.start();
        }
        static Map<String, Object> grant(int room, String realm, String account, String gen, String handle, boolean machine) {
            Map<String, Object> g = new LinkedHashMap<>();
            g.put("number", room); g.put("room", room); g.put("audience", "room-" + room); g.put("realm", realm); g.put("account_id", account); g.put("handle", handle);
            g.put("valid_for_seconds", 2); g.put("machine", machine); g.put("workspace_generation", gen);
            return g;
        }
        String url() { return "http://127.0.0.1:" + srv.getAddress().getPort(); }
        static boolean bearer(HttpExchange ex, String tok) { return ("Bearer " + tok).equals(ex.getRequestHeaders().getFirst("Authorization")); }
        static void reply(HttpExchange ex, int status, String body) throws java.io.IOException {
            byte[] b = body.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().set("Content-Type", "application/json");
            ex.sendResponseHeaders(status, b.length);
            try (OutputStream o = ex.getResponseBody()) { o.write(b); }
        }
        static String sha(String s) {
            try { java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256"); StringBuilder sb = new StringBuilder(); for (byte x : md.digest(s.getBytes(StandardCharsets.UTF_8))) sb.append(String.format("%02x", x)); return sb.toString(); }
            catch (Exception e) { throw new IllegalStateException(e); }
        }
    }

    public static void main(String[] args) {
        try { run(); }
        catch (Throwable t) { t.printStackTrace(System.out); System.out.println("COURSE IT ABORTED after " + checks + " checks: " + t); System.exit(1); }
    }

    private static ClassServer cs;

    private static void run() throws Exception {
        String homeEnv = System.getenv("WORKBENCH_HOME");
        if (homeEnv == null) throw new IllegalStateException("CourseIT needs a hermetic WORKBENCH_HOME — run it with: gradle courseIT");
        File home = new File(homeEnv).getCanonicalFile();
        cs = new ClassServer();
        File creds = new File(home, "creds"); creds.mkdirs();
        Files.writeString(new File(creds, "room-7--auth").toPath(), cs.authTok + "\n");
        Files.writeString(new File(creds, "room-7--store").toPath(), cs.storeTok + "\n");
        Files.writeString(new File(creds, "room-7--gateway").toPath(), cs.gatewayTok + "\n");
        Files.writeString(new File(creds, "room-7--classroom").toPath(), cs.classroomTok + "\n");
        Files.writeString(new File(creds, "nginx-token").toPath(), cs.nginxTok + "\n");
        File manifest = new File(home, "manifest.json");
        // the manifest names a digest that is NOT the mock target's (the mock has no artifact file): resolution is "unknown" for mocks; a wrong-digest case below uses a real file
        Files.writeString(manifest.toPath(), Json.write(Map.of("schema", Course.MANIFEST_SCHEMA, "target_id", "course-fixture", "label", "Course fixture",
                "artifacts", List.of(Map.of("name", "fixture.apk", "sha256", "0".repeat(64), "size", 1)))));
        File courseRoot = new File(home, "course-root"); courseRoot.mkdirs();
        Files.writeString(new File(courseRoot, "wa-course.apk").toPath(), "not an apk, a course root's file");
        Map<String, String> ov = new LinkedHashMap<>();
        ov.put("", "1"); ov.put("room", "room-7"); ov.put("auth_url", cs.url()); ov.put("store_url", cs.url()); ov.put("gateway_url", cs.url()); ov.put("board_url", cs.url()); ov.put("classroom_url", cs.url());
        ov.put("credentials", creds.getAbsolutePath()); ov.put("capture", "strict"); ov.put("manifest", manifest.getAbsolutePath()); ov.put("activity", "E0-fallback");
        ov.put("provider", "fixture:ds-tools"); ov.put("preset_turns", "6"); ov.put("preset_output", "2048"); ov.put("preset_budget", "60000"); ov.put("portal_url", "https://class.example");
        ov.put("proxy_token_file", new File(creds, "nginx-token").getAbsolutePath());
        ov.put("account_id", ClassServer.OWNER); ov.put("generation", ClassServer.GEN); ov.put("session_id", ClassServer.SESSION);   // what the launcher says this room is for
        ov.put("roots", courseRoot.getAbsolutePath());
        ov.put("capture_durable", "1");
        ov.put("allow_mock", "1");   // tests only: the mock engine
        Course.overrides(ov);
        WorkbenchServer server = new WorkbenchServer(Path.of("web"));
        server.start();
        System.out.println("server up on " + BASE + " · class server at " + cs.url());
        dev.workbench.course.CourseRuntime rt = dev.workbench.course.CourseRuntime.current();

        // ---- 0. locked: the instructor has not released access
        Map<String, Object> locked = student("workspace.state", Map.of());
        check(status(locked) == 403 && msg(locked).contains("not open yet"), "locked room: a valid student session gets 403 — " + msg(locked));
        /* while locked the auth service refuses the student's grant itself (the server's gate: no session
           validates before the release) — the page gets the reason as the 403's message; the room's own
           status (what the instructor's room and /healthz see) says disabled with the reason */
        Map<String, Object> lockedSt = student("course.status", Map.of());
        Map<String, Object> access0 = asMap((Map<?, ?>) rt.status().get("access"));
        check(status(lockedSt) == 403 && msg(lockedSt).contains("not open yet") && "disabled".equals(access0.get("state")) && String.valueOf(access0.get("reason")).contains("instructor"),
                "while locked even course.status is refused to a student by the auth service (" + msg(lockedSt) + "); the room's status says access " + access0.get("state"));
        Sse lockedStream = openStream(studentHeaders(), 3000);
        check(lockedStream.status == 403, "a stream is refused while locked (" + lockedStream.status + ")");
        Map<String, Object> lockedSpawn = call("agent.spawn", Map.of("goal", "x", "model", "fixture:ds-tools"));
        check(status(lockedSpawn) == 403, "a machine client cannot spawn while locked (" + status(lockedSpawn) + ")");
        long t0 = System.currentTimeMillis();
        cs.enabled = true; cs.revision = 1;
        boolean released = waitAccess(rt, CoursePolicy.Access.ENABLED, 4000);
        check(released, "the release is seen within the poll: " + (System.currentTimeMillis() - t0) + " ms → " + rt.policy().access());
        check(status(student("workspace.state", Map.of())) == 200, "the student is served once released");

        // ---- 1. the identity gate
        Map<String, Object> noHeaders = raw("workspace.state", Map.of(), Map.of(), "human");
        check(status(noHeaders) == 404, "no classroom headers → 404 (" + msg(noHeaders) + ")");
        Map<String, Object> noProxy = rawNoProxy("workspace.state", Map.of(), Map.of("X-WB-Principal", "7", "X-WB-Grant", "g-alice"), null);
        check(status(noProxy) == 404, "the edge's headers without the edge's credential (nginx-token) → 404 (" + msg(noProxy) + ")");
        Map<String, Object> otherRoom = raw("workspace.state", Map.of(), Map.of("X-WB-Principal", "room-8", "X-WB-Grant", "g-mallory"), "human");
        check(status(otherRoom) == 404, "another room's principal → 404, nothing learned (" + msg(otherRoom) + ")");
        Map<String, Object> badGrant = raw("workspace.state", Map.of(), Map.of("X-WB-Principal", "room-7", "X-WB-Grant", "g-expired"), "human");
        check(status(badGrant) == 401, "an unknown/expired grant → 401 (" + msg(badGrant) + ")");
        Map<String, Object> bearerOnly = raw("workspace.state", Map.of(), Map.of("X-WB-Principal", "room-7"), "llm:evil");
        check(status(bearerOnly) == 404, "a principal without a grant (the old bearer path) is NOT served → 404 (" + msg(bearerOnly) + ")");
        Map<String, Object> machineKind = raw("course.status", Map.of(), Map.of("X-WB-Principal", "room-7", "X-WB-Grant", "g-kimi"), "llm:kimi");   // an op a machine principal may call (the page's ops are refused: 10b)
        check(status(machineKind) == 200 && "llm:kimi".equals(meta(machineKind).get("actor")), "the room's machine client (a machine grant) may act as an assistant within the room: " + meta(machineKind).get("actor"));
        Map<String, Object> alice = raw("workspace.state", Map.of(), Map.of("X-WB-Principal", "room-7", "X-WB-Grant", "g-alice"), "llm:not-me");
        check(status(alice) == 200 && "human:alice".equals(meta(alice).get("actor")), "a browser session is the student the server binds — the body's actor is ignored: " + meta(alice).get("actor"));
        int before = cs.grantCalls;
        raw("workspace.state", Map.of(), Map.of("X-WB-Principal", "room-7", "X-WB-Grant", "g-alice"), null);
        check(cs.grantCalls == before, "within 2 s the grant is cached, not re-asked");
        Thread.sleep(2200);
        raw("workspace.state", Map.of(), Map.of("X-WB-Principal", "room-7", "X-WB-Grant", "g-alice"), null);
        check(cs.grantCalls == before + 1, "after 2 s the grant is validated again (reconnect / revocation window)");
        Map<String, Object> edge = raw("workspace.state", Map.of(), Map.of("X-WB-Principal", "7", "X-WB-Grant", "g-alice"), null);
        check(status(edge) == 200 && "human:alice".equals(meta(edge).get("actor")), "the edge's principal is the room NUMBER (X-WB-Principal: 7) — accepted as this room: " + meta(edge).get("actor"));
        Map<String, Object> hired = raw("workspace.state", Map.of(), Map.of("X-WB-Principal", "room-7", "X-WB-Grant", "g-kimi"), "llm:agent-3");
        check(status(hired) == 400, "a machine client claiming a mission's actor (llm:agent-3) → 400 — its edits cannot be filed under m3");
        Map<String, Object> bare = raw("course.status", Map.of(), Map.of("X-WB-Principal", "room-7", "X-WB-Grant", "g-kimi"), "human");
        check(status(bare) == 200 && "human:alice".equals(meta(bare).get("actor")), "a bare kind on a machine grant names the account's handle: " + meta(bare).get("actor"));
        Map<String, Object> stale = raw("workspace.state", Map.of(), Map.of("X-WB-Principal", "7", "X-WB-Grant", "g-old"), null);
        check(status(stale) == 403 && msg(stale).contains("generation"), "a session of an earlier workspace generation → 403: " + msg(stale));
        Map<String, Object> prevOwner = raw("workspace.state", Map.of(), Map.of("X-WB-Principal", "7", "X-WB-Grant", "g-other"), null);
        check(status(prevOwner) == 403 && msg(prevOwner).contains("another account"), "a session of another account (a previous occupant) → 403: " + msg(prevOwner));

        // ---- 2. health per service, the model route apart, the manifest
        Map<String, Object> st = data(student("course.status", Map.of()));
        Map<?, ?> deps = (Map<?, ?>) st.get("dependencies");
        Map<?, ?> model = (Map<?, ?>) st.get("model");
        check(List.of("degraded", "ready", "loading").contains(st.get("state")) && "ok".equals(deps.get("auth")) && "ok".equals(deps.get("store")) && "ok".equals(deps.get("gateway")) && "ok".equals(deps.get("classroom")),
                "course.status: " + st.get("state") + " — gateway OPEN reads as ok, store ready as ok, classroom reachable (" + deps + ")");
        check("blocked".equals(model.get("state")) && Boolean.FALSE.equals(model.get("verified")) && "unconfigured".equals(model.get("qualification")),
                "the model route is reported APART: " + model.get("state") + " (" + model.get("summary") + "), never verified from here");
        check("enabled".equals(((Map<?, ?>) st.get("access")).get("state")) && Integer.valueOf(1).equals(((Map<?, ?>) st.get("access")).get("policyRevision")), "access enabled at policy revision 1 in the status");
        // the per-service parse on the server's own fixture bodies
        CourseClient.Health gw = CourseClient.parseHealth("gateway", Json.parseObject("{\"state\":\"OPEN\",\"student\":{\"running\":0,\"waiting\":0},\"route\":{\"provider\":\"managed\",\"qualification\":\"unconfigured\",\"state\":\"blocked\",\"reason\":\"Tokenizer and cancellation/lifecycle adapter not qualified\"}}"));
        check(gw.ok() && gw.route() != null && "unconfigured".equals(gw.route().get("qualification")), "fixtures/jeeves/gateway-health.json: OPEN → ok, the route block kept apart");
        check("capture_blocked".equals(CourseClient.parseHealth("gateway", Json.parseObject("{\"state\":\"CAPTURE_BLOCKED\",\"student\":{}}")).state()), "gateway CAPTURE_BLOCKED → the word, not ok");
        check("unparsed".equals(CourseClient.parseHealth("gateway", Json.parseObject("{\"ok\":true}")).state()), "a gateway 200 without a scheduler state is unparsed, not readiness");
        check("stalled".equals(CourseClient.parseHealth("store", Json.parseObject("{\"status\":\"stalled\",\"events\":3}")).state()) && CourseClient.parseHealth("store", Json.parseObject("{\"status\":\"ready\"}")).ok(), "store stalled → the word; ready → ok");
        check(CourseClient.parseHealth("auth", Json.parseObject("{\"ok\":true}")).ok() && "unparsed".equals(CourseClient.parseHealth("auth", Json.parseObject("{\"foo\":1}")).state()), "auth {ok:true} → ok; a body that states nothing is unparsed");
        String analysisBefore = String.valueOf(st.get("state"));
        cs.gatewayState = "CAPTURE_BLOCKED";
        Map<String, Object> stBlocked = data(student("course.status", Map.of()));
        check("capture_blocked".equals(((Map<?, ?>) stBlocked.get("dependencies")).get("gateway")) && "blocked".equals(((Map<?, ?>) stBlocked.get("model")).get("state")) && analysisBefore.equals(stBlocked.get("state")),
                "gateway CAPTURE_BLOCKED: the model route is blocked (" + ((Map<?, ?>) stBlocked.get("model")).get("summary") + "); the analysis verdict stays " + stBlocked.get("state"));
        cs.gatewayState = "OPEN";
        Map<String, Object> mf = data(student("course.manifest", Map.of()));
        check(Boolean.TRUE.equals(mf.get("present")) && "course-fixture".equals(mf.get("target_id")), "the manifest is read: " + mf.get("target_id"));
        String canonicalSha = ClassServer.sha("{\"artifacts\":[{\"name\":\"fixture.apk\",\"sha256\":\"" + "0".repeat(64) + "\",\"size\":1}],\"label\":\"Course fixture\",\"schema\":\"day1-target-manifest-v1\",\"target_id\":\"course-fixture\"}");
        check(canonicalSha.equals(mf.get("sha256")) && !ClassServer.sha(Files.readString(manifest.toPath())).equals(mf.get("sha256")), "the manifest's identity is the sha256 of its CANONICAL JSON (the server's), not of the file's bytes");
        File wrong = new File(home, "wrong.apk"); Files.writeString(wrong.toPath(), "not the course bytes");
        Map<String, Object> res = rt.manifest().resolve(wrong);
        check("mismatch".equals(res.get("match")) && String.valueOf(res.get("reason")).contains("not in the course manifest"), "a file with other bytes resolves to mismatch: " + res.get("reason"));
        check("unknown".equals(rt.manifest().resolve(null).get("match")), "no artifact file → unknown, said as such");

        // ---- 3. the classroom's activity: read-only, versioned
        Map<String, Object> actv = data(student("course.activity", Map.of()));
        check("E3".equals(actv.get("id")) && Integer.valueOf(4).equals(actv.get("revision")) && "classroom".equals(actv.get("source")) && Boolean.FALSE.equals(actv.get("stale")) && actv.get("sha256") != null,
                "course.activity reads the classroom's identity: " + actv.get("id") + " rev " + actv.get("revision") + " (" + actv.get("source") + ")");
        Map<String, Object> setActv = student("course.activity", Map.of("id", "E4b"));
        check("E_SCOPE".equals(code(setActv)) && msg(setActv).contains("The classroom sets the activity") && msg(setActv).contains("Current: E3, revision 4"), "a student cannot relabel the activity, and is told the current one: " + msg(setActv));
        cs.activityId = "E4b"; cs.activityRevision = 5;
        Map<String, Object> actv2 = data(student("course.activity", Map.of("refresh", true)));
        check("E4b".equals(actv2.get("id")) && Integer.valueOf(5).equals(actv2.get("revision")), "the classroom moved on: E4b rev 5 after a refresh");

        // ---- 4. a captured Day 1 mission
        student("llm.configure", Map.of("id", "fixture", "reasoningField", "reasoning_content", "reasoningReplay", "all"));
        String tid = String.valueOf(((Map<?, ?>) ((List<?>) data(student("workspace.state", Map.of())).get("targets")).get(0)).get("id"));
        Map<String, Object> run = data(student("course.mission", Map.of("symbol", "MessageSyncHandler", "question", "what does onMessage dispatch?", "target", tid)));
        String mid = String.valueOf(run.get("id"));
        Map<String, Object> snap = (Map<String, Object>) run.get("snapshot");
        check("fixture:ds-tools".equals(run.get("model")) && Integer.valueOf(6).equals(run.get("maxTurns")) && Integer.valueOf(2048).equals(run.get("maxOutputTokens")) && snap != null && snap.get("snapshot_id") != null
                        && snap.get("activity") instanceof Map<?, ?> sa && "E4b".equals(sa.get("id")) && snap.get("owner") instanceof Map<?, ?> so && ClassServer.OWNER.equals(so.get("account_id")),
                "the Day 1 preset spawned: " + run.get("model") + ", turns " + run.get("maxTurns") + ", output " + run.get("maxOutputTokens") + ", snapshot " + snap.get("snapshot_id") + ", activity + owner recorded");
        Map<String, Object> done = waitSettled(mid, 60_000);
        check("done".equals(done.get("state")) && "completed".equals(done.get("displayState")), "the mission completes: " + done.get("displayState") + " · " + done.get("stopReason"));
        Thread.sleep(1500);   // the exporter drains
        Map<String, Object> evs = data(student("course.events", Map.of("mission", mid)));
        List<?> events = (List<?>) evs.get("events");
        int req = 0, resp = 0, tool = 0, term = 0, pending = 0;
        for (Object o : events) { Map<?, ?> e = (Map<?, ?>) o; String t = String.valueOf(e.get("event_type")); if (t.equals("model.request")) req++; else if (t.equals("model.response")) resp++; else if (t.equals("tool.result")) tool++; else if (t.equals("mission.terminal")) term++; if ("pending".equals(e.get("exported"))) pending++; }
        check(req == 3 && resp == 3 && tool == 3 && term == 1, "the journal holds every request (" + req + "), answer (" + resp + "), tool result (" + tool + ") and the end (" + term + ")");
        check(pending == 0 && cs.events.size() >= 10, "every event is acknowledged by the store (" + cs.events.size() + " records there, " + pending + " pending)");
        Map<?, ?> first = (Map<?, ?>) events.get(0);
        String instance = String.valueOf(((Map<?, ?>) evs.get("capture")).get("instance"));
        check("room-7".equals(first.get("room")) && (instance + "-" + mid).equals(first.get("run_id")) && mid.equals(first.get("mission")) && String.valueOf(first.get("event_id")).startsWith(instance + ":" + mid + ":")
                        && "E4b".equals(first.get("activity_id")) && Integer.valueOf(5).equals(first.get("activity_revision")) && first.get("activity_sha256") != null && Boolean.FALSE.equals(first.get("activity_stale"))
                        && ClassServer.OWNER.equals(first.get("account_id")) && ClassServer.GEN.equals(first.get("workspace_generation")) && ClassServer.SESSION.equals(first.get("course_session_id")) && Integer.valueOf(1).equals(first.get("policy_revision"))
                        && first.get("snapshot_id") != null && "day1-event-v1".equals(first.get("schema")) && Boolean.TRUE.equals(first.get("eval_taint")),
                "the envelope: room, instance-qualified ids (" + first.get("event_id") + "), the classroom's activity identity (E4b rev 5), the owner (account, generation, session, policy revision), snapshot, schema, taint");
        Map<?, ?> firstResp = null;
        for (Object o : events) if ("model.response".equals(((Map<?, ?>) o).get("event_type"))) { firstResp = (Map<?, ?>) o; break; }
        check(firstResp != null && "fixture-thinking-2026-10".equals(firstResp.get("model_returned")) && "ds-tools".equals(firstResp.get("model_requested")) && firstResp.get("usage") != null && String.valueOf(firstResp.get("request_id")).equals(instance + "-" + mid + "-t1-a1"),
                "receipt in the capture: requested " + firstResp.get("model_requested") + " / returned " + firstResp.get("model_returned") + " / request id " + firstResp.get("request_id"));
        String reqBlob = rt.capture().payload(String.valueOf(((Map<?, ?>) events.get(0)).get("payload_blob_sha256")));
        check(reqBlob != null && reqBlob.contains("\"tools\"") && reqBlob.contains("\"messages\""), "the request payload is whole: tools + messages (" + reqBlob.length() + " chars)");
        Map<String, Object> report = data(student("course.mission.report", Map.of("id", mid)));
        Map<?, ?> diff = (Map<?, ?>) report.get("annotationDiff");
        check(((List<?>) diff.get("byMission")).size() >= 1 && report.get("capture") instanceof Map<?, ?> rc && Integer.valueOf(3).equals(rc.get("responses")) && String.valueOf(report.get("summary")).startsWith("completed after 3 turn"),
                "the report: " + ((List<?>) diff.get("byMission")).size() + " annotation(s) by the mission since the snapshot, receipts for 3 answers: " + report.get("summary"));
        Map<String, Object> big = data(student("agent.spawn", Map.of("goal", "read everything", "model", "fixture:big-results", "target", tid)));
        Map<String, Object> bigDone = waitSettled(String.valueOf(big.get("id")), 60_000);
        Thread.sleep(800);
        long bigBytes = 0; String bigSha = null; Map<String, Object> bigEv = null;
        for (Map<String, Object> e : rt.capture().events(String.valueOf(big.get("id"))))
            if ("tool.result".equals(e.get("event_type")) && e.get("payload_bytes") instanceof Number n && n.longValue() > bigBytes) { bigBytes = n.longValue(); bigSha = String.valueOf(e.get("payload_blob_sha256")); bigEv = e; }
        String bigBlob = bigSha == null ? null : rt.capture().payload(bigSha);
        String exactContent = bigBlob == null ? "" : String.valueOf(Json.parseObject(bigBlob).get("content"));
        int exactChars = bigEv == null ? -1 : ((Number) bigEv.get("result_chars")).intValue(), fullChars = bigEv == null ? -1 : ((Number) bigEv.get("result_chars_full")).intValue();
        Map<String, Object> turn1 = data(student("agent.turn", Map.of("mission", big.get("id"), "turn", 1)));
        String shownResult = String.valueOf(((Map<?, ?>) ((List<?>) turn1.get("calls")).get(0)).get("result"));
        check("done".equals(bigDone.get("state")) && bigBlob != null && ClassServer.sha(bigBlob).equals(bigSha) && bigBlob.getBytes(StandardCharsets.UTF_8).length == bigBytes
                        && exactChars == exactContent.length() && exactChars > 5_000 && fullChars >= exactChars && shownResult.contains("…[+"),
                "the capture holds the tool result exactly as the model consumed it (" + exactChars + " chars, of " + fullChars + " before the loop's cap; blob " + bigBytes + " bytes, digest verified)");
        check(reqBlob.length() > 8_000, "the request record (" + reqBlob.length() + " chars) is beyond the transcript's JSON cap — the capture is the authoritative record");
        Map<?, ?> capSt = (Map<?, ?>) data(student("course.status", Map.of())).get("capture");
        check(capSt.get("homeDurable") != null && capSt.get("eventExportDurable") != null && Boolean.TRUE.equals(capSt.get("eventExportDurable")),
                "capture status says the two facts apart: homeDurable " + capSt.get("homeDurable") + " (" + capSt.get("homeDurableWhy") + ") · eventExportDurable " + capSt.get("eventExportDurable"));
        // strict admission, backpressure, lost ack, restart recovery
        cs.storeDown = true;
        Map<String, Object> blocked = data(student("agent.spawn", Map.of("goal", "explain", "model", "fixture:ds-tools", "target", tid)));
        Map<String, Object> blockedDone = waitSettled(String.valueOf(blocked.get("id")), 30_000);
        check("blocked".equals(blockedDone.get("state")) && String.valueOf(blockedDone.get("stopReason")).startsWith("capture refused"), "strict capture with the store down: not dispatched — " + blockedDone.get("stopReason"));
        check(String.valueOf(blockedDone.get("stopReason")).contains("tries") && !String.valueOf(blockedDone.get("stopReason")).contains("Exception"), "the refusal names the tries and speaks plainly: " + blockedDone.get("stopReason"));
        cs.storeDown = false;
        cs.storeFailNext = 2;
        int callsBefore = cs.eventCalls;
        Map<String, Object> bp = data(student("agent.spawn", Map.of("goal", "explain", "model", "fixture:ds-tools", "target", tid)));
        Map<String, Object> bpDone = waitSettled(String.valueOf(bp.get("id")), 60_000);
        check("done".equals(bpDone.get("state")) && cs.eventCalls >= callsBefore + 3, "strict capture under backpressure (503 ×2): retried with the same id and the mission runs — " + bpDone.get("displayState"));
        Thread.sleep(2000);
        int pend = data(student("course.status", Map.of())).get("capture") instanceof Map<?, ?> cm ? ((Number) cm.get("pending")).intValue() : -1;
        check(pend == 0, "every record is acknowledged once the store answers (pending " + pend + ")");
        cs.dropAckOnce = true;
        Map<String, Object> lost = data(student("agent.spawn", Map.of("goal", "explain", "model", "fixture:ds-tools", "target", tid)));
        Map<String, Object> lostDone = waitSettled(String.valueOf(lost.get("id")), 60_000);
        Thread.sleep(2000);
        Map<?, ?> cap = (Map<?, ?>) data(student("course.status", Map.of())).get("capture");
        check("done".equals(lostDone.get("state")) && ((Number) cap.get("duplicatesAcked")).intValue() >= 1 && ((Number) cap.get("pending")).intValue() == 0,
                "a lost acknowledgement: the retry is acknowledged as a duplicate (" + cap.get("duplicatesAcked") + ") and counts as the admission");
        File captureRoot = new File(home, "capture");
        File queue = new File(captureRoot, "queue");
        Files.writeString(new File(queue, "restart-probe.json").toPath(), Json.write(Map.of("event_id", "restart-probe", "event_type", "annotation", "payload_bytes", 0, "schema", "day1-event-v1")));
        dev.workbench.course.Capture again = new dev.workbench.course.Capture(captureRoot, rt.client(), "best-effort", true, false);
        Thread.sleep(2500);
        check(!new File(queue, "restart-probe.json").exists() && cs.events.containsKey("room-7:restart-probe"), "restart recovery: a record queued before a restart is delivered by the next process");
        again.close();
        String red = (String) invoke("redact", "Authorization: Bearer abcdefghijklmnop1234 and wb_" + "A".repeat(43) + " and WBK-AAAAA-BBBBB-CCCCC-DDDDD-EEEEE-FFFFF and \"api_key\": \"sk-secret\"");
        check(red != null && !red.contains("abcdefghijklmnop1234") && !red.contains("wb_AAAA") && !red.contains("WBK-AAAAA") && !red.contains("sk-secret") && red.contains("[REDACTED]"),
                "redaction: Bearer, wb_ tokens, WBK codes and api_key values never reach a blob");

        // ---- 5. observations and /healthz
        long obsDeadline = System.currentTimeMillis() + 8000;
        while (cs.observations.isEmpty() && System.currentTimeMillis() < obsDeadline) Thread.sleep(200);
        Map<String, Object> ob = cs.observations.isEmpty() ? Map.of() : cs.observations.get(cs.observations.size() - 1);
        check(!cs.observations.isEmpty() && cs.observeRejected == 0 && Integer.valueOf(1).equals(ob.get("policy_revision")) && Boolean.FALSE.equals(ob.get("fenced")) && Boolean.FALSE.equals(ob.get("cancellation_unconfirmed"))
                        && ob.get("components") instanceof Map<?, ?> oc && "ready".equals(((Map<?, ?>) oc.get("classroom")).get("state")) && "degraded".equals(((Map<?, ?>) oc.get("target")).get("state"))
                        && String.valueOf(ob.get("runtime_revision")).equals("0".repeat(40)) && String.valueOf(((Map<?, ?>) oc.get("engine")).get("detail")).contains("unpinned"),
                "the room observes to the auth service with the exact fields (" + cs.observations.size() + " accepted, " + cs.observeRejected + " rejected): the mock target is degraded, the unpinned development runtime is said so — engine: " + (ob.get("components") instanceof Map<?, ?> oc2 ? ((Map<?, ?>) oc2.get("engine")).get("detail") : "?"));
        Map<String, Object> stObs = data(student("course.status", Map.of()));
        check(stObs.get("observation") instanceof Map<?, ?> om && Boolean.TRUE.equals(om.get("ok")), "course.status shows the last observation and its acknowledgement");
        int seqBefore = ((Number) cs.observations.get(cs.observations.size() - 1).get("sequence")).intValue();
        cs.lastSequence = seqBefore + 500;   // another process of this generation posted ahead (or the server remembers more than this room does)
        long seqDeadline = System.currentTimeMillis() + 8000; int seqAfter = -1;
        while (System.currentTimeMillis() < seqDeadline) { Map<String, Object> last = cs.observations.get(cs.observations.size() - 1); int sq = ((Number) last.get("sequence")).intValue(); if (sq > seqBefore + 500) { seqAfter = sq; break; } Thread.sleep(200); }
        check(seqAfter > seqBefore + 500 && new File(captureRoot, "observe.seq").isFile(), "the observation sequence advances past what the server holds after a 409 (re-read next_observation_sequence: " + seqAfter + "), and is persisted for a restart");
        for (String path : List.of("/healthz", "/healthz?full=1")) {
            HttpResponse<String> denied = http.send(HttpRequest.newBuilder(URI.create(BASE + path)).GET().build(), HttpResponse.BodyHandlers.ofString());
            check(denied.statusCode() == 404 && !denied.body().contains("observation"), path + " refuses an unauthenticated loopback caller");
            HttpResponse<String> noGrant = http.send(HttpRequest.newBuilder(URI.create(BASE + path))
                    .header("Authorization", "Bearer " + cs.nginxTok).header("X-WB-Principal", "7").GET().build(), HttpResponse.BodyHandlers.ofString());
            check(noGrant.statusCode() == 404 && !noGrant.body().contains("observation"), path + " refuses the correct edge bearer without a grant");
        }
        HttpResponse<String> hz = http.send(HttpRequest.newBuilder(URI.create(BASE + "/healthz"))
                .header("Authorization", "Bearer " + cs.nginxTok).header("X-WB-Principal", "7").header("X-WB-Grant", "g-alice").GET().build(), HttpResponse.BodyHandlers.ofString());
        Map<String, Object> hzBody = Json.parseObject(hz.body());
        check((hz.statusCode() == 200 || hz.statusCode() == 503) && hzBody.get("ready") instanceof Boolean && "enabled".equals(hzBody.get("access")) && hzBody.get("observation") instanceof Map<?, ?> hzo && hzo.get("components") != null,
                "/healthz answers only the authenticated room probe: HTTP " + hz.statusCode() + ", ready " + hzBody.get("ready") + " (the mock engine is not ready — a deployment's would be)");

        // ---- 6. cancellation by the user
        Map<String, Object> slow = data(student("agent.spawn", Map.of("goal", "slow", "model", "fixture:slow-tools", "target", tid)));
        Thread.sleep(400);
        student("agent.stop", Map.of("id", slow.get("id")));
        Thread.sleep(2500);
        boolean cancelledEv = false;
        for (Map<String, Object> e : rt.capture().events(String.valueOf(slow.get("id")))) if ("mission.terminal".equals(e.get("event_type")) && "cancelled".equals(e.get("outcome"))) cancelledEv = true;
        check(cancelledEv, "a stop mid-request ends the capture with outcome cancelled");

        // ---- 7. the fence: access withdrawn mid-mission
        Map<String, Object> fencedRun = data(student("agent.spawn", Map.of("goal", "slow", "model", "fixture:slow-tools", "target", tid)));
        String fid = String.valueOf(fencedRun.get("id"));
        Thread.sleep(400);
        Sse live = openStream(studentHeaders(), 12_000);   // a student's stream, open before the withdrawal
        Thread.sleep(300);
        long tOff = System.currentTimeMillis();
        cs.enabled = false; cs.revision = 2;
        /* the mission's state is read IN-PROCESS here: while access is off the room refuses the test's
           own HTTP reads too (that refusal is checked below) */
        Map<String, Object> fencedState = null;
        boolean sawDraining = false;
        while (System.currentTimeMillis() - tOff < 7000) {
            Map<String, Object> m = missionInProcess(rt, fid);
            if (m != null && Boolean.TRUE.equals(m.get("cancellationUnconfirmed"))) sawDraining = true;
            if (m != null && "stopped".equals(m.get("state"))) { fencedState = m; break; }
            Thread.sleep(50);
        }
        long tStopped = System.currentTimeMillis() - tOff;
        check(fencedState != null && Boolean.TRUE.equals(fencedState.get("fenced")) && tStopped <= 5500 && String.valueOf(fencedState.get("stopReason")).startsWith("fenced:"),
                "a running mission is fenced within the 5 s bound (" + tStopped + " ms): " + (fencedState == null ? "not stopped" : fencedState.get("displayState") + " — " + fencedState.get("stopReason")));
        live.join();
        check(live.closedAt > 0 && live.closedAt - tOff <= 6000, "the open stream closes within the bound (" + (live.closedAt - tOff) + " ms" + (live.events.contains("closed") ? ", with a `closed` event" : ", on revalidation: the auth service refuses the session while locked") + ")");
        Map<String, Object> whileOff = student("workspace.state", Map.of());
        check(status(whileOff) == 403, "new work is refused while access is off (" + status(whileOff) + ")");
        for (String path : List.of("/healthz", "/healthz?full=1")) {
            HttpResponse<String> closedHealth = http.send(HttpRequest.newBuilder(URI.create(BASE + path))
                    .header("Authorization", "Bearer " + cs.nginxTok).header("X-WB-Principal", "7").header("X-WB-Grant", "g-alice").GET().build(), HttpResponse.BodyHandlers.ofString());
            check(closedHealth.statusCode() == 403 && !closedHealth.body().contains("observation"), path + " refuses a valid grant while release is closed");
        }
        Map<String, Object> spawnOff = student("agent.spawn", Map.of("goal", "x", "model", "fixture:ds-tools", "target", tid));
        check(status(spawnOff) == 403, "a new mission cannot be spawned while access is off (" + status(spawnOff) + ")");
        long confirmDeadline = System.currentTimeMillis() + 10_000;
        Map<String, Object> confirmed = null;
        while (System.currentTimeMillis() < confirmDeadline) { Map<String, Object> m = missionInProcess(rt, fid); if (m != null && !Boolean.TRUE.equals(m.get("cancellationUnconfirmed"))) { confirmed = m; break; } Thread.sleep(100); }
        check(confirmed != null && "cancelled".equals(confirmed.get("displayState")), "the stop is confirmed once the loop's thread returns: " + (confirmed == null ? "still draining" : confirmed.get("displayState")) + (sawDraining ? " (it was `draining` meanwhile)" : " (the thread had already returned)"));
        boolean fencedTerminal = false;
        for (Map<String, Object> e : rt.capture().events(fid)) if ("mission.terminal".equals(e.get("event_type")) && "cancelled".equals(e.get("outcome")) && String.valueOf(e.get("reason")).startsWith("fenced:")) fencedTerminal = true;
        check(fencedTerminal, "the capture records the fenced end: outcome cancelled, reason fenced");
        Map<String, Object> stOff = asMap((Map<?, ?>) rt.status().get("access"));
        check("disabled".equals(stOff.get("state")) && ((Number) stOff.get("fences")).intValue() >= 1, "the room's status while off: access " + stOff.get("state") + ", fences " + stOff.get("fences"));
        cs.enabled = true; cs.revision = 3;
        check(waitAccess(rt, CoursePolicy.Access.ENABLED, 4000) && "stopped".equals(mission(fid).get("state")), "re-enabling serves again and restarts nothing: the fenced mission stays " + mission(fid).get("displayState"));

        // ---- 8. an auth outage
        Sse outageStream = openStream(studentHeaders(), 12_000);
        Thread.sleep(300);
        long tDown = System.currentTimeMillis();
        cs.authDown = true;
        Thread.sleep(2600);   // past the grant cache
        Map<String, Object> duringOutage = student("workspace.state", Map.of());
        check(status(duringOutage) == 503, "during an auth outage a request is not served (503, nobody can be told apart): " + msg(duringOutage));
        boolean unknown = waitAccess(rt, CoursePolicy.Access.UNKNOWN, 6000);
        check(unknown, "after the enforcement window the room's access is UNKNOWN, not enabled (" + (System.currentTimeMillis() - tDown) + " ms)");
        outageStream.join();
        /* the stream lives on while its last GOOD validation is within the 5 s window: that validation
           was up to 2 s before the outage (the grant cache), so the close lands 3–6 s after it */
        check(outageStream.closedAt > 0 && outageStream.closedAt - tDown <= 7500 && outageStream.closedAt - tDown >= 2500, "a stream survives the outage only for the window: closed after " + (outageStream.closedAt - tDown) + " ms");
        cs.authDown = false;
        check(waitAccess(rt, CoursePolicy.Access.ENABLED, 4000) && status(student("workspace.state", Map.of())) == 200, "the auth service back: access confirmed, requests served");

        // ---- 9. reset, share, help, progress — the student's own actions, from the room's page
        Map<String, Object> rstMachine = call("course.reset", Map.of("target", tid));
        check(status(rstMachine) == 403 && "E_SCOPE".equals(code(rstMachine)) && msg(rstMachine).contains("assistant's"), "a machine client cannot reset the student's workspace — the page's ops are not an assistant's: " + msg(rstMachine));
        Map<String, Object> rst = data(student("course.reset", Map.of("target", tid)));
        check(((Number) rst.get("undone")).intValue() >= 1 && rst.get("doesNotReset") instanceof List<?> dn && dn.toString().contains("capture") && String.valueOf(rst.get("baseline")).contains("first course snapshot"),
                "course.reset: back to " + rst.get("baseline") + " — " + rst.get("undone") + " entries undone; the capture is named as never reset");
        Map<String, Object> badKind = student("course.share", Map.of("kind", "snippet", "title", "x", "target", tid));
        check("E_SYNTAX".equals(code(badKind)) && msg(badKind).contains("finding, tool, comparison, workflow, image"), "the finding's kinds are the Findings page's card types (snippet is not one): " + msg(badKind));
        Map<String, Object> prev = data(student("course.share", Map.of("kind", "finding", "title", "onEnvelope dispatches", "symbol", "MessageSyncHandler", "preview", true, "target", tid)));
        Map<?, ?> prov = (Map<?, ?>) prev.get("provenance");
        Map<?, ?> fields = (Map<?, ?>) prev.get("fields");
        check(String.valueOf(prev.get("destination")).contains("class.example/classroom/findings") && prev.get("requestId") != null && prev.get("bodyMd") != null && prov != null && fields != null
                        && "E4b".equals(((Map<?, ?>) prov.get("activity")).get("id")) && ClassServer.OWNER.equals(prov.get("account_id")) && prov.get("snapshot_id") != null && "com.google.android.gm.sync.MessageSyncHandler".equals(prov.get("original_symbol"))
                        && "finding".equals(fields.get("type")) && "onEnvelope dispatches".equals(fields.get("title")) && "com.google.android.gm.sync.MessageSyncHandler".equals(fields.get("original_symbol")) && fields.get("project_revision") != null && "E4b".equals(fields.get("activity_id"))
                        && String.valueOf(prev.get("browser")).startsWith("https://class.example/classroom/findings#jeeves=") && "https://class.example/classroom/findings".equals(prev.get("browserPlain")),
                "share preview: the Findings page as destination (the small fields in its URL fragment), the form's FIELDS one by one (type, title, original symbol, project revision, activity), the provenance — nothing sent");
        Map<String, Object> tooBig = student("course.share", Map.of("kind", "finding", "title", "too big", "body", "x".repeat(21_000), "target", tid));
        check("E_SYNTAX".equals(code(tooBig)) && msg(tooBig).contains("20000 bytes"), "a finding past the classroom's text bound is refused here with the size");
        Map<String, Object> shared = data(student("course.share", Map.of("kind", "finding", "title", "onEnvelope dispatches", "symbol", "MessageSyncHandler", "body", "evidence: …", "target", tid)));
        String copy = String.valueOf(shared.get("copy"));
        Map<?, ?> sf = (Map<?, ?>) shared.get("fields");
        check(Boolean.FALSE.equals(shared.get("sent")) && "human_action_required".equals(shared.get("reason")) && String.valueOf(shared.get("browser")).startsWith("https://class.example/classroom/findings#jeeves=")
                        && copy.startsWith("evidence: …") && copy.contains("original name: c") && !copy.contains("provenance (added by the room)")
                        && sf != null && "com.google.android.gm.sync.MessageSyncHandler".equals(sf.get("original_symbol")) && !String.valueOf(shared.get("destination")).contains("instructor's review") && !String.valueOf(shared.get("note")).contains("review"),
                "share hands the finding's fields to the Findings page (sent:false, human_action_required; Details = the student's words + the original name the engine knows, no blob) — no claim of instructor review: copy=" + copy.replace("\n", "⏎"));
        Map<String, Object> machineShare = call("course.share", Map.of("kind", "finding", "title", "drafted by the assistant", "body", "a draft the student will carry", "target", tid));
        check(status(machineShare) == 403 && "E_SCOPE".equals(code(machineShare)), "a machine client (the student's assistant) does not compose findings through the room — the classroom's own tools and the Findings page do (addendum req. 4)");
        boolean draftEv = false;
        for (Map<String, Object> e : rt.capture().events("human-share")) if ("contribution.draft".equals(e.get("event_type")) && Boolean.FALSE.equals(e.get("sent"))) draftEv = true;
        check(draftEv, "the draft is journaled privately as contribution.draft (sent:false) — never a publication");
        Map<String, Object> help = data(student("course.help", Map.of("text", "stuck on the tag-7 branch", "symbol", "MessageSyncHandler")));
        check(Boolean.FALSE.equals(help.get("sent")) && "human_action_required".equals(help.get("reason")) && "https://class.example/classroom/messages".equals(help.get("browser"))
                        && String.valueOf(help.get("copy")).startsWith("stuck on the tag-7 branch\n\ncontext: activity E4b · gmail.apk · MessageSyncHandler") && !String.valueOf(help.get("copy")).contains("sha256"),
                "help: the classroom's own handoff URL, the words + ONE context line to paste (no digests), sent:false — " + help.get("browser"));
        Map<String, Object> prog = data(student("course.progress", Map.of("status", "ready")));
        check(Boolean.FALSE.equals(prog.get("sent")) && "https://class.example/classroom/today".equals(prog.get("browser")) && String.valueOf(prog.get("copy")).startsWith("Press 'I'm ready' on the Today page"),
                "readiness: the Today page handoff — " + prog.get("copy"));
        Map<String, Object> notReady = data(student("course.progress", Map.of("status", "working")));
        check(String.valueOf(notReady.get("copy")).startsWith("Press 'Not ready yet'"), "Today's third state (its value `working`, shown as Not ready yet) is accepted: " + notReady.get("copy"));
        Map<String, Object> badStatus = student("course.progress", Map.of("status", "done"));
        check("E_SYNTAX".equals(code(badStatus)) && msg(badStatus).contains("I'm ready, I need help, Not ready yet"), "an unknown readiness value is refused naming the Today page's three: " + msg(badStatus));
        Map<String, Object> runsList = data(student("course.events", Map.of()));
        check(runsList.get("runs") instanceof List<?> rl && rl.contains(mid) && rl.contains("human-share"), "course.events lists this home's runs: " + runsList.get("runs"));

        // ---- 10. confinement: the room's files and the course roots only; no host ops; no cli missions
        Map<String, Object> fsRoot = data(student("fs.list", Map.of()));
        check(new File(home, "files").getCanonicalPath().equals(fsRoot.get("dir")) && "".equals(fsRoot.get("parent")) && Boolean.TRUE.equals(fsRoot.get("confined")) && ((List<?>) fsRoot.get("roots")).size() == 2,
                "fs.list starts in the room's workspace with no way up; the roots are the workspace and the course folder");
        Map<String, Object> fsEscape = student("fs.list", Map.of("dir", "/"));
        check("E_SCOPE".equals(code(fsEscape)), "fs.list of / → E_SCOPE: " + msg(fsEscape));
        Map<String, Object> fsHome = student("fs.list", Map.of("dir", home.getPath()));
        check("E_SCOPE".equals(code(fsHome)), "the home itself (the store, the capture) is not browsable: " + msg(fsHome));
        Map<String, Object> fsCourse = data(student("fs.list", Map.of("dir", courseRoot.getPath())));
        check("".equals(fsCourse.get("parent")) && ((List<?>) fsCourse.get("entries")).size() == 1, "a course root lists its files with no way up: " + ((List<?>) fsCourse.get("entries")).size() + " entry");
        Files.createSymbolicLink(new File(new File(home, "files"), "escape").toPath(), new File("/etc").toPath());
        Map<String, Object> fsLink = data(student("fs.list", Map.of()));
        check(Integer.valueOf(1).equals(fsLink.get("hidden")) && ((List<?>) fsLink.get("entries")).stream().noneMatch(e -> "escape".equals(((Map<?, ?>) e).get("name"))), "a symlink out of the workspace is not listed (hidden " + fsLink.get("hidden") + ")");
        Map<String, Object> openOut = student("target.open", Map.of("path", "/etc/hosts"));
        check("E_SCOPE".equals(code(openOut)) && msg(openOut).contains("outside this room"), "target.open outside the room → E_SCOPE before anything is read: " + msg(openOut));
        Map<String, Object> dev = student("device.list", Map.of());
        check("E_UNSUPPORTED".equals(code(dev)) && msg(dev).contains("Message Lab"), "device.* is refused in a room, saying why: " + msg(dev));
        Map<String, Object> cli = data(student("agent.spawn", Map.of("goal", "x", "mode", "cli")));
        check("blocked".equals(cli.get("state")) && String.valueOf(cli.get("stopReason")).contains("cli missions do not run in a course room"), "a cli mission is refused in a room: " + cli.get("stopReason"));

        // ---- 10b. the student assistant's view (MCP addendum): the classroom's tool subset enforced by the room, provenance on request, the assistant's tool traffic captured, the bounded artifact export
        Map<String, Object> cat = call("ops.tools", Map.of());
        List<String> catNames = new ArrayList<>();
        for (Object o : (List<?>) cat.get("data")) catNames.add(String.valueOf(((Map<?, ?>) o).get("tool")));
        check(catNames.contains("code") && catNames.contains("symbols") && catNames.contains("annotate") && !catNames.contains("devices") && !catNames.contains("mission") && !catNames.contains("llm") && !catNames.contains("map_edit"),
                "ops.tools in a room lists the classroom's subset (" + catNames.size() + " tools: no devices, mission, llm, map_edit)");
        Map<String, Object> outside = call("tool.call", Map.of("tool", "mission", "args", Map.of("action", "list")));
        check("E_SCOPE".equals(code(outside)) && msg(outside).contains("not available to your assistant in this classroom"), "a tool outside the subset is refused by the room: " + msg(outside));
        Map<String, Object> openAct = call("tool.call", Map.of("tool", "target", "args", Map.of("action", "open", "path", "/x.apk")));
        check("E_SCOPE".equals(code(openAct)) && msg(openAct).contains("can: list, info"), "target {action: open} is not a student assistant's in a room: " + msg(openAct));
        Map<String, Object> withProv = data(call("tool.call", Map.of("tool", "symbols", "args", Map.of("symbol", "MessageSyncHandler", "target", tid), "provenance", true, "client", "qualify/1", "callId", "c-77")));
        Map<?, ?> pv = (Map<?, ?>) withProv.get("provenance");
        check(withProv.get("result") != null && pv != null && "jeeves-evidence-provenance-v1".equals(pv.get("schema")) && tid.equals(pv.get("target")) && pv.get("project_revision") != null && "symbols".equals(pv.get("tool"))
                        && "qualify/1".equals(pv.get("client")) && "c-77".equals(pv.get("call_id")) && "external-assistant".equals(pv.get("origin")) && ClassServer.GEN.equals(pv.get("workspace_generation")) && pv.get("account_id") == null && Boolean.FALSE.equals(pv.get("truncated")) && "unknown".equals(pv.get("manifest_match")) && pv.get("course_target_id") == null && "E4b".equals(pv.get("activity_id")),
                "tool.call {provenance:true} answers {result, provenance}: the exact target, this room's revision, tool/client/call id, the generation (not the account), the activity, truncated said, origin external-assistant");
        Thread.sleep(600);
        List<Map<String, Object>> asst = rt.capture().events("assistant-qualify_1");
        check(asst.stream().anyMatch(e -> "assistant.tool_call".equals(e.get("event_type")) && "symbols".equals(e.get("tool")) && "qualify/1".equals(e.get("client")) && "external-assistant".equals(e.get("origin")) && Boolean.TRUE.equals(e.get("ok"))),
                "a machine client's tool call is captured as assistant.tool_call under its own run (" + asst.size() + " event(s)) — the call, never a model transcript, and no mission started");
        // the MOCK target has no bytes at hand: in a room the evidence artifact is refused until asked for marked unverified (handoff 2026-10-09 §A)
        Map<String, Object> unverified = student("course.export.artifact", Map.of("symbol", "MessageSyncHandler", "scope", "class", "target", tid));
        check("E_SCOPE".equals(code(unverified)) && msg(unverified).contains("no measured executable identity") && msg(unverified).contains("requireVerified"),
                "course.export.artifact on a target without a measured executable identity is refused in a room, naming the state and the override: " + msg(unverified));
        Map<String, Object> artifact = data(student("course.export.artifact", Map.of("symbol", "MessageSyncHandler", "scope", "class", "target", tid, "requireVerified", false)));
        Map<String, Object> artifact2 = data(student("course.export.artifact", Map.of("symbol", "MessageSyncHandler", "scope", "class", "target", tid, "requireVerified", false)));
        check("jeeves-artifact-export-v1".equals(artifact.get("schema")) && artifact.get("artifact_id") != null && artifact.get("artifact_id").equals(artifact2.get("artifact_id")) && new File(String.valueOf(artifact.get("path"))).isFile()
                        && artifact.get("provenance") instanceof Map<?, ?> apv && Boolean.FALSE.equals(apv.get("artifact_verified")) && "missing".equals(((Map<?, ?>) apv.get("executable")).get("state"))
                        && ClassServer.OWNER.equals(artifact.get("account_id")) && ClassServer.GEN.equals(artifact.get("workspace_generation")) && ((Number) artifact.get("size")).intValue() > 100
                        && String.valueOf(artifact.get("sha256")).length() == 64 && artifact.get("provenance") instanceof Map<?, ?> ap && tid.equals(ap.get("target")),
                "course.export.artifact: an immutable export (" + artifact.get("size") + " bytes, id " + artifact.get("artifact_id") + ") with owner/generation and provenance; the same content is the same artifact");
        Map<String, Object> got = data(student("course.export.artifact", Map.of("action", "get", "artifact_id", artifact.get("artifact_id"))));
        check(got.get("content") instanceof Map<?, ?> gc && "reconstruction-export-v1".equals(gc.get("schema")) && artifact.get("sha256").equals(got.get("sha256")) && ownerKeyed(artifact) && new File(String.valueOf(artifact.get("path")).replace(".json", ".meta.json")).isFile(),
                "course.export.artifact {action: get} answers the owner's artifact with its record (<id>.meta.json beside the content; the id is the owner's, not a bare content hash)");
        // the assistant's connection reaches the facade, the catalogue, the status and the export — the page's ops are refused before the gate (fix-review addendum H1)
        Map<String, Object> rawSpawn = call("agent.spawn", Map.of("goal", "x", "model", "fixture:ds-tools", "target", tid));
        check(status(rawSpawn) == 403 && "E_SCOPE".equals(code(rawSpawn)) && msg(rawSpawn).contains("assistant's"), "a room token cannot spawn a mission through the raw op: " + msg(rawSpawn));
        Map<String, Object> rawUndo = call("history.undo", Map.of("target", tid));
        check(status(rawUndo) == 403 && "E_SCOPE".equals(code(rawUndo)), "nor undo the student's history through the raw op (" + status(rawUndo) + ")");
        Map<String, Object> rawDelete = call("project.delete", Map.of("name", "x", "confirm", true));
        check(status(rawDelete) == 403, "nor delete a project (" + status(rawDelete) + ")");
        Map<String, Object> undoAct = call("tool.call", Map.of("tool", "history", "args", Map.of("action", "undo", "target", tid)));
        check("E_SCOPE".equals(code(undoAct)) && msg(undoAct).contains("can: list"), "history undo through the facade is refused — history is read-only for an assistant: " + msg(undoAct));
        Map<?, ?> targetRow = null;
        for (Object o : (List<?>) cat.get("data")) if ("target".equals(((Map<?, ?>) o).get("tool"))) targetRow = (Map<?, ?>) o;
        Object actionEnum = targetRow == null ? null : ((Map<?, ?>) ((Map<?, ?>) ((Map<?, ?>) targetRow.get("parameters")).get("properties")).get("action")).get("enum");
        check(List.of("list", "info", "changes", "save", "save_status", "switch").equals(actionEnum), "the catalogue's target.action enum is the room's allowed list, not the full one: " + actionEnum);
        Map<String, Object> wrongPin = call("tool.call", Map.of("tool", "symbols", "args", Map.of("symbol", "MessageSyncHandler", "target", "t-not-open")));
        check(!Boolean.TRUE.equals(wrongPin.get("ok")), "a call naming a target that is not open fails (" + code(wrongPin) + ") — the room never falls back to a first match");

        // the identity record on a MOCK target: no file, no engine input → the executable is MISSING and says so (handoff 2026-10-09 §A)
        Map<String, Object> mockInfo = data(student("target.info", Map.of("target", tid)));
        Map<?, ?> idRec = (Map<?, ?>) mockInfo.get("identity"); Map<?, ?> mexe = idRec == null ? null : (Map<?, ?>) idRec.get("executable");
        check(mexe != null && "missing".equals(mexe.get("state")) && mexe.get("sha256") == null && String.valueOf(mexe.get("basis")).contains("input") && ((Map<?, ?>) idRec.get("runtime")).get("build_sha") != null,
                "a target without bytes at hand says so: executable.state missing, no sha256, the basis named — never a bare field that implies identity");

        // ---- 11. the cleanup receipt; the packet route; the export seam
        Map<String, Object> receipt = data(student("course.receipt", Map.of("write", true)));
        check("jeeves-cleanup-receipt-v1".equals(receipt.get("schema")) && ClassServer.OWNER.equals(receipt.get("account_id")) && ClassServer.GEN.equals(receipt.get("workspace_generation")) && receipt.get("runs") instanceof List<?> rr && !rr.isEmpty()
                        && new File(captureRoot, "receipt.json").isFile() && receipt.get("blobs") instanceof Map<?, ?>,
                "the cleanup receipt names the owner, every run's journal and digest, the blobs, what is pending — written to " + receipt.get("written"));
        Map<String, Object> pk = call("tool.call", Map.of("tool", "code", "args", Map.of("target", tid, "symbol", "MessageSyncHandler", "view", "packet", "question", "q")));
        check(!Boolean.TRUE.equals(pk.get("ok")) && "E_UNSUPPORTED".equals(code(pk)) || Boolean.TRUE.equals(pk.get("ok")), "code {view: packet} routes to evidence.packet (" + (Boolean.TRUE.equals(pk.get("ok")) ? "answered" : msg(pk)) + ")");
        Map<String, Object> ex = student("course.export.reconstruction", Map.of("symbol", "MessageSyncHandler", "scope", "class", "target", tid));
        check(Boolean.TRUE.equals(ex.get("ok")) && "reconstruction-export-v1".equals(data(ex).get("schema")) && data(ex).get("promotion") instanceof Map<?, ?> pm && "not-eligible".equals(pm.get("state")) && Boolean.TRUE.equals(data(ex).get("eval_taint")),
                "export seam: schema, promotion not-eligible, taint carried");

        // ---- 11b. a suspension (403 "Room owner revoked") closes the room and LIFTS with the next good answer
        cs.suspended = true;
        boolean susp = waitAccess(rt, CoursePolicy.Access.FENCED, 4000);
        Map<String, Object> whileSusp = student("workspace.state", Map.of());
        cs.suspended = false;
        boolean lifted = waitAccess(rt, CoursePolicy.Access.ENABLED, 4000);
        check(susp && status(whileSusp) == 403 && msg(whileSusp).contains("suspended") && lifted && status(student("workspace.state", Map.of())) == 200,
                "a suspension fences the room while it lasts (" + msg(whileSusp) + ") and lifts by itself when the owner is back");

        // ---- 12. last: the credential's generation fenced — this process serves nobody from here on
        cs.fencedContext = true;
        boolean fenced = waitAccess(rt, CoursePolicy.Access.FENCED, 4000);
        Map<String, Object> afterFence = student("workspace.state", Map.of());
        Map<String, Object> stFenced = status(student("course.status", Map.of())) == 200 ? asMap((Map<?, ?>) data(student("course.status", Map.of())).get("access")) : Map.of();
        check(fenced && status(afterFence) == 403 && msg(afterFence).contains("reset or given to another account") && "fenced".equals(stFenced.get("state")),
                "the room credential fenced (generation/owner gone): nothing is served (" + msg(afterFence) + "); status says fenced");

        System.out.println();
        if (failures > 0) { System.out.println("COURSE IT FAILED: " + failures + "/" + checks + " checks failed"); System.exit(1); }
        System.out.println("COURSE IT PASSED: " + checks + " checks green");
        System.exit(0);
    }

    // ------------------------------------------------------------ helpers

    private static Object invoke(String name, String arg) throws Exception {
        java.lang.reflect.Method m = dev.workbench.course.Capture.class.getDeclaredMethod(name, String.class);
        m.setAccessible(true);
        return m.invoke(null, arg);
    }

    private static boolean waitAccess(dev.workbench.course.CourseRuntime rt, CoursePolicy.Access want, long ms) throws Exception {
        long deadline = System.currentTimeMillis() + ms;
        while (System.currentTimeMillis() < deadline) { if (rt.policy().access() == want) return true; Thread.sleep(100); }
        return rt.policy().access() == want;
    }

    /** A request as the room's machine client acting as the student (the edge's machine grant). */
    private static Map<String, Object> call(String op, Map<String, Object> params) throws Exception {
        return raw(op, params, Map.of("X-WB-Principal", "room-7", "X-WB-Grant", "g-kimi"), "human:alice");
    }

    private static Map<String, String> studentHeaders() { return Map.of("X-WB-Principal", "7", "X-WB-Grant", "g-alice"); }

    /** A request as the STUDENT at the room's page: the edge's headers — the room number as the
     *  principal and the session's grant — the body's actor ignored. */
    private static Map<String, Object> student(String op, Map<String, Object> params) throws Exception {
        return raw(op, params, studentHeaders(), "human");
    }

    /** Every request through the "edge": the nginx→room credential rides as Authorization. */
    private static Map<String, Object> raw(String op, Map<String, Object> params, Map<String, String> headers, String actor) throws Exception {
        Map<String, String> h = new LinkedHashMap<>(headers);
        h.put("Authorization", "Bearer " + cs.nginxTok);
        return rawNoProxy(op, params, h, actor);
    }

    private static Map<String, Object> rawNoProxy(String op, Map<String, Object> params, Map<String, String> headers, String actor) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("params", params);
        if (actor != null) body.put("actor", actor);
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(BASE + "/api/" + op))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(Json.write(body)));
        for (Map.Entry<String, String> h : headers.entrySet()) b.header(h.getKey(), h.getValue());
        HttpResponse<String> resp = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
        Map<String, Object> out = new LinkedHashMap<>(Json.parseObject(resp.body()));
        out.put("_status", resp.statusCode());
        return out;
    }

    /** An SSE stream read on its own thread: the status, the event names seen, when it closed. */
    static final class Sse {
        volatile int status; volatile long closedAt; final List<String> events = Collections.synchronizedList(new ArrayList<>()); Thread t;
        void join() throws InterruptedException { t.join(15_000); }
    }

    private static Sse openStream(Map<String, String> headers, long maxMs) throws Exception {
        Sse s = new Sse();
        java.util.concurrent.CountDownLatch headersIn = new java.util.concurrent.CountDownLatch(1);
        s.t = new Thread(() -> {
            try {
                HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(BASE + "/api/events")).GET().header("Authorization", "Bearer " + cs.nginxTok);
                for (Map.Entry<String, String> h : headers.entrySet()) b.header(h.getKey(), h.getValue());
                HttpResponse<java.io.InputStream> r = http.send(b.build(), HttpResponse.BodyHandlers.ofInputStream());
                s.status = r.statusCode();
                headersIn.countDown();
                if (r.statusCode() != 200) { r.body().close(); s.closedAt = System.currentTimeMillis(); return; }
                long deadline = System.currentTimeMillis() + maxMs;
                try (java.io.BufferedReader br = new java.io.BufferedReader(new java.io.InputStreamReader(r.body(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = br.readLine()) != null && System.currentTimeMillis() < deadline) if (line.startsWith("event: ")) s.events.add(line.substring(7).trim());
                }
                s.closedAt = System.currentTimeMillis();
            } catch (Exception e) { s.closedAt = System.currentTimeMillis(); headersIn.countDown(); }
        }, "it-sse");
        s.t.setDaemon(true);
        s.t.start();
        headersIn.await(5, java.util.concurrent.TimeUnit.SECONDS);
        return s;
    }

    private static int status(Map<String, Object> r) { return r.get("_status") instanceof Number n ? n.intValue() : -1; }
    private static Map<String, Object> meta(Map<String, Object> r) { return r.get("meta") instanceof Map<?, ?> m ? asMap(m) : Map.of(); }
    /** The export's id is sha256("jeeves-artifact-v1|" + account|generation + "|" + content sha)[:32] — the owner's, never the bare content hash. */
    private static boolean ownerKeyed(Map<String, Object> artifact) {
        String expect = ClassServer.sha("jeeves-artifact-v1|" + ClassServer.OWNER + "|" + ClassServer.GEN + "|" + artifact.get("sha256")).substring(0, 32);
        return expect.equals(artifact.get("artifact_id")) && !String.valueOf(artifact.get("sha256")).startsWith(String.valueOf(artifact.get("artifact_id")));
    }

    private static String msg(Map<String, Object> r) { return r.get("error") instanceof Map<?, ?> e ? String.valueOf(e.get("message")) : String.valueOf(r.get("ok")); }
    private static String code(Map<String, Object> r) { return Boolean.TRUE.equals(r.get("ok")) ? "ok" : r.get("error") instanceof Map<?, ?> e ? String.valueOf(e.get("code")) : "?"; }

    private static Map<String, Object> mission(String id) throws Exception {
        Object d = student("agent.missions", Map.of()).get("data");
        if (d instanceof List<?> l) for (Object o : l) if (o instanceof Map<?, ?> m && id.equals(m.get("id"))) return asMap(m);
        return null;
    }

    /** The mission's record read in-process (for the moments the room refuses HTTP: access off). */
    private static Map<String, Object> missionInProcess(dev.workbench.course.CourseRuntime rt, String id) {
        for (Object o : rt.workspace().agents().missionList()) if (o instanceof Map<?, ?> m && id.equals(m.get("id"))) return asMap(m);
        return null;
    }

    private static Map<String, Object> waitSettled(String id, long ms) throws Exception {
        long deadline = System.currentTimeMillis() + ms;
        Map<String, Object> last = null;
        while (System.currentTimeMillis() < deadline) {
            last = mission(id);
            if (last != null && !"running".equals(last.get("state")) && !"queued".equals(last.get("state"))) return last;
            Thread.sleep(200);
        }
        return last == null ? Map.of("state", "?") : last;
    }

    private static Map<String, Object> asMap(Map<?, ?> m) { Map<String, Object> out = new LinkedHashMap<>(); for (Map.Entry<?, ?> e : m.entrySet()) out.put(String.valueOf(e.getKey()), e.getValue()); return out; }

    private static void check(boolean ok, String label) { checks++; System.out.println((ok ? "PASS " : "FAIL ") + label); if (!ok) failures++; }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> data(Map<String, Object> resp) {
        if (!Boolean.TRUE.equals(resp.get("ok"))) throw new IllegalStateException("op failed: " + resp.get("error"));
        Object d = resp.get("data");
        if (!(d instanceof Map)) throw new IllegalStateException("expected map data, got: " + (d == null ? "null" : d.getClass().getSimpleName()));
        return (Map<String, Object>) d;
    }
}
