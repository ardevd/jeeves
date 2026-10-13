package dev.workbench.course;

import dev.workbench.registry.Json;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The room's HTTP client to the class server's loopback services: every call is
 * a JSON POST with the room's own bearer for that service, read from its credential file at call
 * time (rotation needs no restart). Shapes are the server's A10 contract
 * (the class server's integration contract, read 2026-10-09):
 *
 *   auth       POST /internal/grant {audience, grant}
 *                → {number, room, audience, realm, handle, machine, account_id, workspace_generation,
 *                   course_session_id, policy_revision, valid_for_seconds ≤ 2}
 *                403 while the instructor has not released access, or for a revoked owner
 *              POST /internal/jeeves/context {}   (the room credential — generation-bound)
 *                → {schema: jeeves-room-v1, account_id, workspace_generation, room, handle, realm,
 *                   disabled, room_state, course_session_id, policy {jeeves-policy-v1 …},
 *                   access_allowed, valid_for_seconds: 2}
 *                403 "generation is fenced" / "owner revoked": this credential's owner is gone
 *              POST /internal/jeeves/observe {…}   the room's health observation (CoursePolicy.observe)
 *   store      POST /v1/events {event_id, kind, payload, eval_tainted} → {event_id, seq, duplicate, durable}
 *                409: another body under that id · 503: backlog, retry with the same id
 *              POST /v1/lookup {event_id} → {event}
 *   classroom  POST /v1/room/context {}   (the room credential)
 *                → {schema: jeeves-classroom-v1, owner, activity {id, revision, sha256} | null,
 *                   teaching_revision, actions {name: {sent:false, browser, …}}}
 *   gateway    POST /v1/chat/completions — the agent loop's streaming client speaks to it directly
 *              (AgentLoop.openAiStreamingClient, X-Request-ID, stream:true), not this class
 *
 * The board's /v1/post is NOT called from a room any more: the contract says a room credential never
 * publishes as a student — findings enter the classroom's author-bound Findings path from the
 * student's own browser (CourseOps course.share hands the composed finding over).
 *
 * A token never appears in a log line, an answer or an exception message: failures say the service
 * and the status. Loopback only: the services bind to 127.0.0.1 and so does the deployment's URL.
 */
public final class CourseClient {

    /** The service answered with a status that is the caller's problem (401/403/404/409/4xx). */
    public static final class Refused extends Exception {
        public final int status; public final String body;
        public Refused(String service, int status, String body) {
            super(service + " answered HTTP " + status + (body == null || body.isBlank() ? "" : ": " + head(body)));
            this.status = status; this.body = body;
        }
        /** The server's own one-line reason, when its body was {"error": "…"}; else "". */
        public String reason() {
            try { Object o = Json.parseObject(body == null ? "{}" : body).get("error"); return o == null ? "" : String.valueOf(o); }
            catch (Exception e) { return ""; }
        }
    }
    /** The service is not reachable, or asks for a retry (503, timeouts, connection failures). */
    public static final class Unavailable extends Exception {
        public final boolean retryable;
        public Unavailable(String service, String why, boolean retryable) { super(service + " unavailable: " + why); this.retryable = retryable; }
    }

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final Duration timeout;
    /** The authorization RPCs' total bound — the contract's 1.5 s on streams and missions. */
    static final Duration AUTH_TIMEOUT = Duration.ofMillis(1500);

    public CourseClient() { this(Duration.ofSeconds(8)); }
    public CourseClient(Duration timeout) { this.timeout = timeout; }

    /** Authenticated generation-bound phone capability; absent on older class servers = disabled. */
    public Map<String, Object> phoneContext() throws Refused, Unavailable {
        return post("auth", Course.authUrl(), "/internal/jeeves/phone", Map.of(), AUTH_TIMEOUT);
    }

    /** The bearer for a service, from its credential file; null when none is configured. */
    static String token(String service) {
        return readToken(Course.credentialFile(service));
    }

    /** A credential file's one token (trimmed); null when absent, empty or unreadable. */
    static String readToken(File f) {
        if (f == null || !f.isFile()) return null;
        try { String t = Files.readString(f.toPath()).trim(); return t.isEmpty() ? null : t; }
        catch (IOException e) { return null; }
    }

    public boolean hasCredential(String service) { return token(service) != null; }

    private Map<String, Object> post(String service, String base, String path, Map<String, Object> body) throws Refused, Unavailable {
        return post(service, base, path, body, timeout);
    }

    private Map<String, Object> post(String service, String base, String path, Map<String, Object> body, Duration bound) throws Refused, Unavailable {
        String tok = token(service);
        if (tok == null) throw new Unavailable(service, "no credential file for the room (" + (Course.credentialFile(service) == null ? "WORKBENCH_COURSE_CREDENTIALS unset" : Course.credentialFile(service).getPath()) + ")", false);
        if (base == null || base.isBlank()) throw new Unavailable(service, "no URL configured for the service", false);
        HttpRequest req = HttpRequest.newBuilder(URI.create(base.replaceAll("/+$", "") + path))
                .timeout(bound)
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + tok)
                .POST(HttpRequest.BodyPublishers.ofString(Json.write(body)))
                .build();
        HttpResponse<String> resp;
        /* the bound is TOTAL (headers and body): the request's own timeout covers the headers only,
           so the send runs asynchronously and is waited for with the bound plus a little */
        java.util.concurrent.CompletableFuture<HttpResponse<String>> fut = http.sendAsync(req, HttpResponse.BodyHandlers.ofString());
        try { resp = fut.get(bound.toMillis() + 300, java.util.concurrent.TimeUnit.MILLISECONDS); }
        catch (java.util.concurrent.TimeoutException e) { fut.cancel(true); throw new Unavailable(service, "timed out after " + bound.toMillis() + " ms", true); }
        catch (java.util.concurrent.ExecutionException e) {
            Throwable c = e.getCause() == null ? e : e.getCause();
            if (c instanceof java.net.http.HttpTimeoutException) throw new Unavailable(service, "timed out after " + bound.toMillis() + " ms", true);
            if (c instanceof IOException io) throw new Unavailable(service, plain(io), true);
            throw new Unavailable(service, "request failed (" + c.getClass().getSimpleName() + ")", true);
        }
        catch (InterruptedException e) { fut.cancel(true); Thread.currentThread().interrupt(); throw new Unavailable(service, "interrupted", true); }
        int st = resp.statusCode();
        if (st == 503 || st == 502 || st == 504) throw new Unavailable(service, "HTTP " + st + " — retry with the same id", true);
        if (st / 100 != 2) throw new Refused(service, st, resp.body());
        Object parsed;
        try { parsed = Json.parse(resp.body()); } catch (Exception e) { throw new Unavailable(service, "answered non-JSON (HTTP " + st + ")", false); }
        if (!(parsed instanceof Map<?, ?> m)) throw new Unavailable(service, "answered a non-object body", false);
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : m.entrySet()) out.put(String.valueOf(e.getKey()), e.getValue());
        return out;
    }

    // ------------------------------------------------------------ auth

    /** One validated grant — the student (or the room's machine client) behind a request, as the
     *  auth service binds them: the stable account, its workspace generation, the course session and
     *  the policy revision the answer was given under. */
    public record Grant(int number, String room, String audience, String realm, String accountId, String handle, int validForSeconds,
                        boolean machine, String workspaceGeneration, String courseSessionId, int policyRevision) {}

    public Grant grant(String audience, String grant) throws Refused, Unavailable {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("audience", audience);
        body.put("grant", grant);
        Map<String, Object> r = post("auth", Course.authUrl(), "/internal/grant", body, AUTH_TIMEOUT);
        return new Grant(num(r.get("number")), str(r.get("room")), str(r.get("audience")), str(r.get("realm")),
                str(r.get("account_id")), str(r.get("handle")), Math.max(1, num(r.get("valid_for_seconds"))),
                Boolean.TRUE.equals(r.get("machine")), str(r.get("workspace_generation")), str(r.get("course_session_id")), num(r.get("policy_revision")));
    }

    /** The release policy as the room sees it (jeeves-policy-v1 inside the room context). */
    public record Policy(String courseSessionId, int revision, boolean enabled, String changedBy, String changedAt, int enforcementSeconds) {}

    /** The room credential's own context: whose room this is, which generation, whether the
     *  instructor has released access, and the observation sequence the server expects next (at
     *  least that one: a restarted room must advance past what the server already holds). */
    public record RoomContext(String accountId, String workspaceGeneration, int room, String handle, String realm, boolean disabled,
                              String roomState, String courseSessionId, Policy policy, boolean accessAllowed, int validForSeconds, int nextObservationSequence) {}

    public RoomContext context() throws Refused, Unavailable {
        Map<String, Object> r = post("auth", Course.authUrl(), "/internal/jeeves/context", Map.of(), AUTH_TIMEOUT);
        if (!"jeeves-room-v1".equals(r.get("schema"))) throw new Unavailable("auth", "the room context has schema " + r.get("schema") + ", expected jeeves-room-v1", false);
        Policy p = null;
        if (r.get("policy") instanceof Map<?, ?> pm)
            p = new Policy(str(pm.get("course_session_id")), num(pm.get("revision")), Boolean.TRUE.equals(pm.get("enabled")), str(pm.get("changed_by")),
                    pm.get("changed_at") == null ? null : String.valueOf(pm.get("changed_at")), Math.max(1, num(pm.get("enforcement_seconds")) == 0 ? 5 : num(pm.get("enforcement_seconds"))));
        if (p == null) throw new Unavailable("auth", "the room context carries no policy block", false);
        return new RoomContext(str(r.get("account_id")), str(r.get("workspace_generation")), num(r.get("room")), str(r.get("handle")), str(r.get("realm")),
                Boolean.TRUE.equals(r.get("disabled")), str(r.get("room_state")), str(r.get("course_session_id")), p,
                Boolean.TRUE.equals(r.get("access_allowed")), Math.max(1, num(r.get("valid_for_seconds"))), Math.max(0, num(r.get("next_observation_sequence"))));
    }

    /** The room's health observation, exactly the server's fields (jeeves.py observe()). */
    public Map<String, Object> observe(Map<String, Object> observation) throws Refused, Unavailable {
        return post("auth", Course.authUrl(), "/internal/jeeves/observe", observation, Duration.ofSeconds(4));
    }

    // ------------------------------------------------------------ store

    /** The store's acknowledgement of one event. */
    public record Ack(String eventId, long seq, boolean duplicate, boolean durable) {}

    public Ack storeEvent(String eventId, String kind, Map<String, Object> payload, boolean evalTainted) throws Refused, Unavailable {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("event_id", eventId);
        body.put("kind", kind);
        body.put("payload", payload);
        body.put("eval_tainted", evalTainted);
        Map<String, Object> r = post("store", Course.storeUrl(), "/v1/events", body);
        return new Ack(str(r.get("event_id")), r.get("seq") instanceof Number n ? n.longValue() : -1,
                Boolean.TRUE.equals(r.get("duplicate")), Boolean.TRUE.equals(r.get("durable")));
    }

    public Map<String, Object> storeLookup(String eventId) throws Refused, Unavailable {
        return post("store", Course.storeUrl(), "/v1/lookup", Map.of("event_id", eventId));
    }

    // ------------------------------------------------------------ classroom

    /** The classroom's current activity, as it names it: id, the revision of its definition and the
     *  digest of that definition — what an official event carries, never a student's label. */
    public record Activity(String id, int revision, String sha256) {}

    /** The classroom's read-only room context (jeeves-classroom-v1). */
    public record ClassroomContext(String ownerAccountId, String ownerGeneration, Activity activity, int teachingRevision, Map<String, Object> actions) {}

    public ClassroomContext classroomContext() throws Refused, Unavailable {
        Map<String, Object> r = post("classroom", Course.classroomUrl(), "/v1/room/context", Map.of(), Duration.ofSeconds(4));
        if (!"jeeves-classroom-v1".equals(r.get("schema"))) throw new Unavailable("classroom", "the room context has schema " + r.get("schema") + ", expected jeeves-classroom-v1", false);
        Activity a = null;
        if (r.get("activity") instanceof Map<?, ?> am && am.get("id") != null)
            a = new Activity(String.valueOf(am.get("id")), num(am.get("revision")), str(am.get("sha256")));
        String owner = null, gen = null;
        if (r.get("owner") instanceof Map<?, ?> om) { owner = str(om.get("account_id")); gen = str(om.get("workspace_generation")); }
        Map<String, Object> actions = new LinkedHashMap<>();
        if (r.get("actions") instanceof Map<?, ?> acts) for (Map.Entry<?, ?> e : acts.entrySet()) actions.put(String.valueOf(e.getKey()), e.getValue());
        return new ClassroomContext(owner, gen, a, num(r.get("teaching_revision")), actions);
    }

    // ------------------------------------------------------------ health

    /** One service's health as the room reads it: `ok` when the service says it takes work, else the
     *  service's own word (lower case) with what it means; never "ok" for a 200 whose body the room
     *  cannot read as readiness. The gateway's `route` block (the model route: provider,
     *  qualification, state, reason) rides along for course.status. */
    public record Health(String service, String state, String detail, Map<String, Object> route) {
        public boolean ok() { return "ok".equals(state); }
        @Override public String toString() { return state; }
    }

    /**
     * GET /health of a service, parsed PER SERVICE (the services speak differently and a generic
     * word list read the gateway's scheduler state "OPEN" as an error):
     *   gateway  {state: OPEN | DRAINING | TRAINING | UPSTREAM_DOWN | CAPTURE_BLOCKED, student, route?}
     *            — OPEN means the scheduler accepts work (not an engine verdict); anything else is
     *            that word; `route` is the model route's own health, reported apart
     *   store    {status: ready | stalled | blocked, …} — ready is ok
     *   auth, board, classroom  {ok: true} or {status|state: ok|ready|healthy|up}; {ok:false} is not ok
     * A 2xx whose body says none of this is "unparsed" — reachable, not known ready. Never a token.
     */
    public Health health(String service, String base) {
        try {
            if (base == null || base.isBlank()) return new Health(service, "off", "no URL configured", null);
            /* a short bound: the probe runs every 5 s inside the observation, and a service that
               hangs must not make the room's observation stale (15 s) */
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base.replaceAll("/+$", "") + "/health")).timeout(Duration.ofMillis(2500)).GET();
            String tok = token(service);
            if (tok != null) b.header("Authorization", "Bearer " + tok);
            HttpResponse<String> r = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() / 100 != 2) return new Health(service, "http " + r.statusCode(), "the service answered HTTP " + r.statusCode(), null);
            Map<String, Object> m;
            try {
                Object parsed = Json.parse(r.body());
                if (!(parsed instanceof Map<?, ?> pm)) return new Health(service, "unparsed", "a 2xx whose body is not a JSON object — reachable, not known ready", null);
                m = new LinkedHashMap<>();
                for (Map.Entry<?, ?> e : pm.entrySet()) m.put(String.valueOf(e.getKey()), e.getValue());
            } catch (Exception e) { return new Health(service, "unparsed", "a 2xx whose body is not JSON — reachable, not known ready", null); }
            return parseHealth(service, m);
        } catch (java.net.http.HttpTimeoutException e) {
            return new Health(service, "unreachable", "timed out", null);
        } catch (IOException e) {
            return new Health(service, "unreachable", plain(e), null);
        } catch (Exception e) {
            return new Health(service, "unreachable", e.getClass().getSimpleName(), null);
        }
    }

    /** The per-service reading of a 2xx /health body (public: the ITs feed it the server's fixtures). */
    public static Health parseHealth(String service, Map<String, Object> m) {
        switch (service) {
            case "gateway" -> {
                Map<String, Object> route = null;
                if (m.get("route") instanceof Map<?, ?> rm) { route = new LinkedHashMap<>(); for (Map.Entry<?, ?> e : rm.entrySet()) route.put(String.valueOf(e.getKey()), e.getValue()); }
                if (!(m.get("state") instanceof String st)) return new Health(service, "unparsed", "the gateway's /health carries no scheduler state", route);
                String s = st.trim().toUpperCase(Locale.ROOT);
                if (s.equals("OPEN")) return new Health(service, "ok", "the scheduler accepts work (OPEN) — not an engine or model verdict", route);
                return new Health(service, s.toLowerCase(Locale.ROOT), switch (s) {
                    case "CAPTURE_BLOCKED" -> "the scheduler refuses work: capture is blocked (nothing is dispatched uncaptured)";
                    case "UPSTREAM_DOWN" -> "the scheduler refuses work: the upstream model is down";
                    case "DRAINING" -> "the scheduler is draining: no new work, running requests finish";
                    case "TRAINING" -> "the scheduler is reserved for training: no classroom work";
                    default -> "the scheduler state is " + s;
                }, route);
            }
            case "store" -> {
                if (!(m.get("status") instanceof String st)) return new Health(service, "unparsed", "the store's /health carries no status", null);
                String s = st.trim().toLowerCase(Locale.ROOT);
                if (s.equals("ready")) return new Health(service, "ok", "the capture store acknowledges", null);
                return new Health(service, s, switch (s) {
                    case "stalled" -> "the store's oldest pending record is ≥ 4 s old — backlog";
                    case "blocked" -> "the store's journal is poisoned — it accepts nothing until recovery";
                    default -> "the store says " + s;
                }, null);
            }
            default -> {
                if (Boolean.TRUE.equals(m.get("ok"))) return new Health(service, "ok", "answers", null);
                if (Boolean.FALSE.equals(m.get("ok"))) return new Health(service, "not ok", "the service says ok:false", null);
                Object st = m.get("status") != null ? m.get("status") : m.get("state");
                if (st instanceof String s) {
                    String w = s.trim().toLowerCase(Locale.ROOT);
                    if (List.of("ok", "ready", "healthy", "up").contains(w)) return new Health(service, "ok", "answers (" + w + ")", null);
                    return new Health(service, w, "the service says " + w, null);
                }
                return new Health(service, "unparsed", "a 2xx that states no readiness — reachable, not known ready", null);
            }
        }
    }

    /** A transport failure in the student's words — no Java class names in a stop reason. */
    static String plain(IOException e) {
        String m = e.getMessage() == null ? "" : e.getMessage();
        if (e instanceof java.net.ConnectException || m.contains("Connection refused")) return "connection refused — the service is not listening";
        if (m.contains("received no bytes") || m.contains("closed") || e instanceof java.io.EOFException) return "the connection closed before an answer came";
        if (e instanceof java.net.UnknownHostException) return "the service's host name does not resolve";
        return "connection failed" + (m.isBlank() ? "" : " (" + m + ")");
    }

    private static String str(Object o) { return o == null ? null : String.valueOf(o); }
    private static int num(Object o) { return o instanceof Number n ? n.intValue() : 0; }
    private static String head(String s) { String t = s.replaceAll("\\s+", " "); return t.length() > 160 ? t.substring(0, 157) + "…" : t; }
}
