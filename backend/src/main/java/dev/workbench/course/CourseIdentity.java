package dev.workbench.course;

import com.sun.net.httpserver.HttpExchange;
import java.net.InetSocketAddress;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Who is behind a request, as the class server binds them (course integration P0.3; A10 contract
 * 2026-10-09).
 *
 * The class's nginx authenticates the browser (cookie session) OR the room's machine client (the
 * room's API bearer) at the auth service and forwards to this process with X-WB-Principal (the
 * room) and X-WB-Grant — an opaque grant on BOTH paths; no cookie and no bearer reaches this
 * process. Nothing else does either: it binds to loopback and believes those headers only from the
 * peers named in WORKBENCH_COURSE_TRUST_PROXY (the loopback addresses by default).
 *
 *   - a request without the headers, or from an untrusted peer, or for another room: 404 — the
 *     same answer the edge gives for a room that is not yours; a copied room URL lands here
 *   - a request WITHOUT a grant is not served (404): the old bearer path — trusted on the proxy
 *     peer alone — is gone with the contract; the edge issues a grant to machine clients too
 *   - every grant is validated against the auth service (POST /internal/grant {audience, grant},
 *     1.5 s bound); the answer binds the student (stable account id, workspace generation, handle)
 *     or the room's machine client (machine:true), is given under a policy revision, and is good
 *     for valid_for_seconds (2 s): re-validated on the next request after that, and every second on
 *     a long-lived stream — a logout, a revocation or the instructor closing access ends the stream
 *     within the server's 5 s. While the auth service is away a stream lives on only until its last
 *     validation is older than that window: an outage is not a release
 *   - the grant's owner must be THIS room's owner (the room credential's own context, CoursePolicy):
 *     a session of the previous occupant — a stale tab after the slot was reassigned — is refused
 *     before it can write into the replacement's workspace
 *   - the actor the ops run under is DERIVED: a browser session is human:&lt;handle&gt;; the room's
 *     machine client may name its kind in the body — llm:&lt;name&gt; for an assistant acting through the
 *     student's MCP bridge, human:&lt;name&gt; for a client acting as the student — but never another
 *     room, never a mission's own actor, and never an identity the server did not bind
 *
 * A grant value never appears in a log line or an answer.
 */
public final class CourseIdentity {

    /** The student (or machine client) behind a request: the actor the ops run under and the facts an event carries. */
    public record Principal(String room, String actor, String accountId, String handle, String realm, boolean machine, String grantDigest,
                            String workspaceGeneration, String courseSessionId, int policyRevision) {
        public Map<String, Object> describe() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("room", room); m.put("actor", actor); m.put("handle", handle); m.put("realm", realm); m.put("machine", machine);
            m.put("accountId", accountId); m.put("workspaceGeneration", workspaceGeneration); m.put("policyRevision", policyRevision);
            return m;
        }
    }

    /** A request this process will not serve, with the status the edge expects. */
    public static final class Denied extends Exception {
        public final int status; public final String code;
        /** The machine-readable reason (`course=closed:disabled`, `course=fenced`, `course=machine-ops` …):
         *  what a client classifies on — the message is for the person. */
        public final String detail;
        public Denied(int status, String code, String message) { this(status, code, message, ""); }
        public Denied(int status, String code, String message, String detail) { super(message); this.status = status; this.code = code; this.detail = detail == null ? "" : detail; }
    }

    /** The principal of the request being dispatched on this thread (set by the HTTP handler
     *  around dispatch) — what a capture started from inside an op attributes the run to. */
    public static final ThreadLocal<Principal> CURRENT = new ThreadLocal<>();

    private record Cached(CourseClient.Grant grant, long validatedAt, long until) {}

    private final CourseClient client;
    private final CoursePolicy policy;
    private final Map<String, Cached> grants = new ConcurrentHashMap<>();   // sha256(grant) → validated, until
    private volatile String lastAuthResult = "none";
    private volatile long lastAuthAt;

    public CourseIdentity(CourseClient client, CoursePolicy policy) { this.client = client; this.policy = policy; }

    public String lastAuthResult() { return lastAuthResult; }
    public long lastAuthAt() { return lastAuthAt; }

    /** The principal of an exchange, or Denied. `bodyActor` is the actor string the request body
     *  carried (null for GET / none). */
    public Principal authorize(HttpExchange ex, String bodyActor) throws Denied {
        InetSocketAddress peer = ex.getRemoteAddress();
        String peerAddr = peer == null || peer.getAddress() == null ? "" : peer.getAddress().getHostAddress();
        if (!Course.trustedProxies().contains(peerAddr))
            throw new Denied(404, "E_NOTFOUND", "not reached through the class server (peer " + peerAddr + ")");
        /* the edge's own credential (the nginx→room service bearer, a systemd credential file): when
           the deployment issues one, every request carries it — a peer on the loopback that does not
           hold it is not the edge, whatever headers it sets */
        java.io.File tokFile = Course.proxyTokenFile();
        if (tokFile != null) {
            String expected = CourseClient.readToken(tokFile);
            if (expected == null) throw new Denied(503, "E_BUSY", "the room's proxy credential file is unreadable (" + tokFile.getPath() + ") — nothing can be verified as the edge's");
            String auth = ex.getRequestHeaders().getFirst("Authorization");
            String got = auth != null && auth.startsWith("Bearer ") ? auth.substring(7).trim() : "";
            if (!java.security.MessageDigest.isEqual(got.getBytes(java.nio.charset.StandardCharsets.UTF_8), expected.getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                throw new Denied(404, "E_NOTFOUND", "not reached through the class server (no edge credential on the request)");
        }
        String principal = ex.getRequestHeaders().getFirst("X-WB-Principal");
        if (principal == null || principal.isBlank())
            throw new Denied(404, "E_NOTFOUND", "no classroom identity on the request (X-WB-Principal) — open the room through the class portal");
        String room = Course.room();
        /* the edge sends the room NUMBER ("7": portal.py `str(source['room'])`, test_nginx asserts '1');
           the grant audience and the credential files say "room-7" — both spellings name this room */
        if (!roomName(principal).equals(room))
            throw new Denied(404, "E_NOTFOUND", "this instance is not that room");   // the edge's own answer for a room that is not yours: nothing to learn here
        String grant = ex.getRequestHeaders().getFirst("X-WB-Grant");
        if (grant == null || grant.isBlank())
            /* no grant: the contract issues one to browsers AND machine clients; a request carrying
               only the principal header is not the edge's — refused like any other stranger */
            throw new Denied(404, "E_NOTFOUND", "no session grant on the request (X-WB-Grant) — open the room through the class portal, or point the MCP client at the room's API URL");
        CourseClient.Grant g = validate(room, grant.trim());
        String handle = g.handle() == null || g.handle().isBlank() ? room : g.handle();
        if (g.machine()) {
            /* the room's machine client (the student's MCP bridge, a script): the auth service bound
               it to the room's account; the body may say which KIND of client it is, within this room */
            String actor = bodyActor == null || bodyActor.isBlank() ? "human:" + handle : bodyActor.trim();
            if (actor.matches("human|llm|agent|user")) actor = actor + ":" + handle;   // a bare kind (the bridge's default "human") names the account
            if (!actor.matches("(human|llm|agent|user):[A-Za-z0-9_.:-]{1,64}"))
                throw new Denied(400, "E_SYNTAX", "actor must be human:<name> or llm:<name>");
            /* "llm:agent-3" is a MISSION's actor: a client claiming it would file its edits under that
               mission's capture and have agent.revert undo them — the room's own missions are not for hire */
            if (actor.matches("(llm|agent):agent-\\d+.*"))
                throw new Denied(400, "E_SYNTAX", "actor names agent-<n> — reserved for the room's own missions; a client names itself (llm:<name>)");
            /* machine:true — whatever kind it claims, this principal is not a student at the room's
               page: the student's own actions (share, reset, help) are refused to it (CourseOps.humanOnly) */
            return new Principal(room, actor, g.accountId(), handle, g.realm(), true, digest(grant.trim()), g.workspaceGeneration(), g.courseSessionId(), g.policyRevision());
        }
        /* a browser session IS the student: its own actor string cannot promote it to an agent or
           to someone else — the kind is human, the name the server's. The principal's room is the
           configured audience ("room-7"): the auth answer's `room` is the number, and a revalidation
           must ask for the audience the grant was issued to */
        return new Principal(room, "human:" + handle, g.accountId(), handle, g.realm(), false, digest(grant.trim()), g.workspaceGeneration(), g.courseSessionId(), g.policyRevision());
    }

    /** "7" → "room-7"; "room-7" → "room-7". */
    static String roomName(String principal) {
        String p = principal.trim();
        return p.matches("\\d+") ? "room-" + p : p;
    }

    /** A validated grant, from the cache while it is good, else from the auth service. */
    private CourseClient.Grant validate(String room, String grant) throws Denied {
        String key = digest(grant);
        Cached c = grants.get(key);
        long now = System.currentTimeMillis();
        if (c != null && c.until() > now) { requireOwner(c.grant()); return c.grant(); }
        try {
            CourseClient.Grant g = client.grant(room, grant);
            /* the answer names the room as a number and the audience as "room-N" — either must be ours */
            if (g.audience() != null && !roomName(g.audience()).equals(room))
                throw new Denied(404, "E_NOTFOUND", "this instance is not that room");
            if (g.room() != null && !roomName(g.room()).equals(room))
                throw new Denied(404, "E_NOTFOUND", "this instance is not that room");
            requireOwner(g);
            grants.put(key, new Cached(g, now, now + Math.min(2, g.validForSeconds()) * 1000L));
            if (grants.size() > 256) grants.entrySet().removeIf(e -> e.getValue().until() <= now);
            lastAuthResult = "ok"; lastAuthAt = now;
            return g;
        } catch (CourseClient.Refused e) {
            grants.remove(key);
            lastAuthResult = "refused HTTP " + e.status; lastAuthAt = now;
            /* 401 is what the edge turns into the portal's sign-in; 403 is the auth service's own
               refusal — the instructor has not released access, or the owner is gone; everything
               else is a 404 like the edge's own denial */
            if (e.status == 401) throw new Denied(401, "E_SCOPE", "the classroom session is not valid — sign in again");
            if (e.status == 403) {
                String why = e.reason().toLowerCase(java.util.Locale.ROOT);
                if (why.contains("released")) throw new Denied(403, "E_SCOPE", "Jeeves is not open yet — the instructor opens it from the classroom; the classroom (slides, materials, messages) stays available", "course=closed:unreleased");
                if (why.contains("fenced")) throw new Denied(403, "E_SCOPE", "this room was reset or given to another account — open your course home for your room", "course=fenced");
                if (why.contains("revoked") || why.contains("disabled") || why.contains("unavailable"))
                    throw new Denied(403, "E_SCOPE", "this account is suspended, revoked or unknown to the class server right now — open your course home", "course=suspended");
                throw new Denied(403, "E_SCOPE", "the class server refused this session for this room" + (why.isEmpty() ? "" : " (" + e.reason() + ")"), "course=refused");
            }
            if (e.status >= 500) throw new Denied(503, "E_BUSY", "the class server's auth service answered HTTP " + e.status + " — the room cannot tell who you are right now");
            throw new Denied(404, "E_NOTFOUND", "this instance is not that room");
        } catch (CourseClient.Unavailable e) {
            lastAuthResult = "auth unavailable"; lastAuthAt = now;
            throw new Denied(503, "E_BUSY", "the class server's auth service is unavailable — the room cannot tell who you are");
        }
    }

    /** The grant's owner must be this room's current owner (the room credential's context). A
     *  session of a previous occupant — or of a stale generation of the same account — is refused;
     *  before the room knows its owner nothing can be compared, and the policy gate (STARTING /
     *  UNKNOWN) keeps new work out meanwhile. */
    private void requireOwner(CourseClient.Grant g) throws Denied {
        CourseClient.RoomContext owner = policy == null ? null : policy.owner();
        if (owner == null) return;
        /* fail closed: a grant that names no account or generation while the room knows its owner is
           not the contract's grant (the server always sends both) */
        if (owner.accountId() != null && (g.accountId() == null || !Objects.equals(g.accountId(), owner.accountId())))
            throw new Denied(403, "E_SCOPE", g.accountId() == null ? "this session's grant names no account — the class server's grant does; sign in again from the classroom"
                    : "this session belongs to another account than this room's owner — the room was reassigned; nothing is served to it");
        if (owner.workspaceGeneration() != null && (g.workspaceGeneration() == null || !Objects.equals(g.workspaceGeneration(), owner.workspaceGeneration())))
            throw new Denied(403, "E_SCOPE", g.workspaceGeneration() == null ? "this session's grant names no workspace generation — the class server's grant does; sign in again from the classroom"
                    : "this session belongs to an earlier generation of this room's workspace — it was reset; sign in again from the classroom");
    }

    /** Re-validate from the exchange's header (streams): true while the server still vouches — for
     *  browser AND machine streams. The grant value itself is never kept — only its digest — so a
     *  stream presents its header again. While the auth service is away the stream stays only until
     *  its last good validation is older than the policy's enforcement window (5 s): the outage is
     *  not a revocation, and not a release either. */
    public boolean revalidate(HttpExchange ex, Principal p) {
        if (p == null) return true;
        String grant = ex.getRequestHeaders().getFirst("X-WB-Grant");
        if (grant == null || grant.isBlank()) return false;
        try { validate(p.room(), grant.trim()); return true; }
        catch (Denied d) {
            if (d.status != 503) return false;
            Cached c = grants.get(digest(grant.trim()));
            long window = policy == null ? 5000 : policy.enforcementMs();
            return c != null && System.currentTimeMillis() - c.validatedAt() <= window;
        }
    }

    static String digest(String s) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            StringBuilder sb = new StringBuilder();
            for (byte b : md.digest(s.getBytes(java.nio.charset.StandardCharsets.UTF_8))) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) { return Integer.toHexString(s.hashCode()); }
    }

    public Map<String, Object> describe() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("mode", "proxy");
        m.put("trustedProxies", Course.trustedProxies());
        m.put("grantRequired", true);
        m.put("proxyCredential", Course.proxyTokenFile() != null);
        m.put("lastAuth", lastAuthResult);
        if (lastAuthAt > 0) m.put("lastAuthAt", java.time.Instant.ofEpochMilli(lastAuthAt).toString());
        m.put("cachedGrants", grants.size());
        return m;
    }
}
