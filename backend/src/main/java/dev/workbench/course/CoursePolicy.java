package dev.workbench.course;

import dev.workbench.server.Log;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The instructor's release of Jeeves, as this room is allowed to know it (A10 contract, 2026-10-09):
 * the auth service owns one durable policy (jeeves-policy-v1: enabled, revision, course session);
 * a room reads only its own context — POST auth:/internal/jeeves/context {} with the room's
 * generation-bound credential — which says whose room this is (stable account id, workspace
 * generation), the policy, the effective `access_allowed` (true for the instructor's own room
 * while students are locked out) and the observation sequence the server expects next.
 *
 * The room asks ONCE A SECOND (a fixed-rate scheduler: a slow answer does not stretch the
 * interval), with the contract's 1.5 s total timeout, and holds the answer for the policy's
 * enforcement window (5 s). Access is ENABLED only while a fresh answer says so; everything else
 * fails closed:
 *
 *   DISABLED  a fresh answer says the instructor has not released access (or has withdrawn it)
 *   FENCED    the auth service refuses the credential itself (403: the generation is fenced, the
 *             owner revoked or suspended, the slot reassigned), or the launcher's identity for this
 *             room is not the credential's owner. A fenced GENERATION or a mismatched launch identity
 *             is permanent for this process; a refusal that may lift (a suspension) is re-asked and
 *             lifts with the next good answer
 *   UNKNOWN   no good answer for longer than the enforcement window: an auth outage is not a
 *             release — new work waits, streams close, missions are fenced
 *   STARTING  no answer yet and the window has not elapsed since boot
 *
 * Leaving ENABLED fences the room within the window: new requests are refused (CourseIdentity /
 * WorkbenchServer), open streams close on their next check, running missions are asked to stop
 * (AgentManager.fence — an unconfirmed upstream stop stays `cancellation_unconfirmed`, never
 * "stopped"). The fence runs on its own thread, so a busy agent manager never stalls the poll.
 * Re-enabling restarts nothing by itself. Nothing here resets a workspace.
 */
public final class CoursePolicy {

    public enum Access { STARTING, ENABLED, DISABLED, FENCED, UNKNOWN }

    /** What fences the room's own work when access closes (AgentManager.fence). */
    public interface Fencer { List<Map<String, Object>> fence(String reason); }

    private final CourseClient client;
    private final long startedAt = System.currentTimeMillis();
    private volatile CourseClient.RoomContext ctx;        // the last good answer
    private volatile long lastOkAt;
    private volatile String lastError;                     // the last failure to ask, in plain words
    private volatile boolean fencedForGood;                // the generation is fenced / the launch identity differs: permanent
    private volatile String fencedWhy;
    private volatile String refusedWhy;                    // a refusal that may lift (a suspension): FENCED until a good answer
    private volatile Access lastAccess = Access.STARTING;
    private volatile long sinceAt = startedAt;
    private volatile Fencer fencer;
    private volatile int fences;                           // how many times the room was fenced (course.status)
    private final java.util.concurrent.ScheduledExecutorService ticker =
            java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> { Thread t = new Thread(r, "course-policy"); t.setDaemon(true); return t; });
    private final java.util.concurrent.ExecutorService fencing =
            java.util.concurrent.Executors.newSingleThreadExecutor(r -> { Thread t = new Thread(r, "course-fence"); t.setDaemon(true); return t; });
    private volatile boolean polling;

    public CoursePolicy(CourseClient client) { this.client = client; }

    /** Start the poll (every second, fixed rate; an answer slower than that is not waited for twice)
     *  and the transition tick (every 250 ms: the window elapsing is noticed without an HTTP round trip). */
    public void start() {
        ticker.scheduleAtFixedRate(() -> { if (!polling) { polling = true; try { poll(); } finally { polling = false; } } }, 1000, 1000, java.util.concurrent.TimeUnit.MILLISECONDS);
        ticker.scheduleAtFixedRate(() -> { try { transition(access()); } catch (Throwable t) { Log.warn("course policy", "tick failed: " + t, null); } }, 250, 250, java.util.concurrent.TimeUnit.MILLISECONDS);
    }

    public void close() { ticker.shutdownNow(); fencing.shutdownNow(); }
    public void fencer(Fencer f) { this.fencer = f; }

    /** The enforcement window: the policy's enforcement_seconds (5) — the bound within which a
     *  withdrawal or an outage must take effect here. */
    public long enforcementMs() {
        CourseClient.RoomContext c = ctx;
        return (c != null && c.policy() != null ? c.policy().enforcementSeconds() : 5) * 1000L;
    }

    /** One ask of the auth service, then the transition it implies (the scheduler's step; tests call it too). */
    public synchronized void poll() {
        try {
            CourseClient.RoomContext c = client.context();
            /* the launcher told this process whose room it is (WORKBENCH_COURSE_ACCOUNT_ID / _GENERATION /
               _SESSION_ID): the credential's owner must be the same — a home prepared for one
               generation served under another's credential is fenced, not served */
            String mismatch = expectedMismatch(c);
            if (mismatch != null) { fencedForGood = true; fencedWhy = mismatch; }
            else { ctx = c; lastOkAt = System.currentTimeMillis(); lastError = null; refusedWhy = null; }
        } catch (CourseClient.Refused e) {
            String why = e.reason();
            if (e.status == 403 && why.toLowerCase(java.util.Locale.ROOT).contains("fenced")) {
                /* the credential's generation is fenced: the slot was reset or reassigned — this
                   process serves nobody from here on */
                fencedForGood = true;
                fencedWhy = (why.isEmpty() ? "the auth service refuses this room's credential" : why) + " (HTTP " + e.status + ")";
            } else if (e.status == 403 || e.status == 404) {
                /* the owner revoked or suspended, the room unknown: closed now, re-asked every second —
                   a suspension lifts, and so does this (fix-review 2026-10-09 M2) */
                refusedWhy = (why.isEmpty() ? "the auth service refuses this room's credential" : why) + " (HTTP " + e.status + ")";
            } else lastError = "the auth service answered HTTP " + e.status;
        } catch (CourseClient.Unavailable e) {
            lastError = e.getMessage();
        }
        transition(access());
    }

    /** Why the context's owner is not the one the launcher named, or null. */
    static String expectedMismatch(CourseClient.RoomContext c) {
        String a = Course.expectedAccountId(), g = Course.expectedGeneration(), s = Course.expectedSessionId();
        if (!a.isEmpty() && !a.equals(c.accountId())) return "the launcher named account " + a + " but the credential's owner is " + c.accountId();
        if (!g.isEmpty() && !g.equals(c.workspaceGeneration())) return "the launcher named workspace generation " + g + " but the credential's is " + c.workspaceGeneration();
        if (!s.isEmpty() && c.courseSessionId() != null && !s.equals(c.courseSessionId())) return "the launcher named course session " + s + " but the auth service runs " + c.courseSessionId();
        return null;
    }

    private synchronized void transition(Access now) {
        Access prev = lastAccess;
        if (prev == now) return;
        lastAccess = now;
        sinceAt = System.currentTimeMillis();
        Log.info("course policy", "access " + prev + " → " + now + (now == Access.ENABLED ? "" : ": " + closedReason(now)));
        if (now != Access.ENABLED && fencer != null) {
            /* leaving ENABLED (or never having reached it while missions exist): the room's own work
               is fenced — on the fence's own thread (the agent manager's monitor may be busy; the
               poll must not wait on it), missions asked to stop and said to be draining until their
               thread confirms */
            fences++;
            final String reason = "fenced: " + closedReason(now);
            fencing.submit(() -> {
                try {
                    List<Map<String, Object>> fencedMissions = fencer.fence(reason);
                    if (!fencedMissions.isEmpty()) Log.info("course policy", fencedMissions.size() + " mission(s) fenced");
                } catch (Throwable e) { Log.error("course policy", "fence failed: " + e, null); }
            });
        }
    }

    /** The room's effective access right now — computed from the last answer and its age. */
    public Access access() {
        if (fencedForGood || refusedWhy != null) return Access.FENCED;
        CourseClient.RoomContext c = ctx;
        long now = System.currentTimeMillis();
        if (c == null) return now - startedAt > enforcementMs() ? Access.UNKNOWN : Access.STARTING;
        if (now - lastOkAt > enforcementMs()) return Access.UNKNOWN;
        if (c.disabled()) return Access.FENCED;
        return c.accessAllowed() ? Access.ENABLED : Access.DISABLED;
    }

    /** The last good context: whose room this is. Null before the first answer. */
    public CourseClient.RoomContext owner() { return ctx; }

    public int policyRevision() { CourseClient.RoomContext c = ctx; return c == null || c.policy() == null ? 0 : c.policy().revision(); }

    /** The observation sequence the server expects next (0 when unknown) — a restarted room starts
     *  from at least this (fix-review 2026-10-09 H1). */
    public int nextObservationSequence() { CourseClient.RoomContext c = ctx; return c == null ? 0 : c.nextObservationSequence(); }

    /** Why access is closed, in the student's words (the operator's details are in describe()):
     *  not open yet vs closed at a time; reset/reassigned; the sign-in service away. These lines reach
     *  a page through the stream's `closed` event and the instructor's own room — the class edge
     *  answers a locked student itself (persona pass 2026-10-09). */
    public String closedReason(Access a) {
        CourseClient.RoomContext c = ctx;
        return switch (a) {
            case ENABLED -> "";
            case DISABLED -> {
                CourseClient.Policy p = c == null ? null : c.policy();
                String when = p == null ? null : whenChanged(p.changedAt());
                yield (p != null && p.revision() > 0 && when != null
                        ? "The instructor closed Jeeves at " + when + " — your names and comments are kept, and nothing restarts by itself"
                        : "Jeeves is not open yet — the instructor opens it from the classroom")
                        + "; the classroom (slides, materials, messages) stays available";
            }
            case FENCED -> fencedForGood
                    ? "This room was reset or given to another account — open your course home for your room" + (fencedWhy == null ? "" : " (" + fencedWhy + ")")
                    : refusedWhy != null
                    ? "Your account is suspended or unknown to the class server right now — open your course home; the room reopens by itself when the server allows it (" + refusedWhy + ")"
                    : "This room's owner is disabled — open your course home for your room";
            case UNKNOWN -> "The class sign-in service has not answered for " + (enforcementMs() / 1000) + " s — Jeeves paused: running agent runs were stopped (their work is kept) and new work waits; it resumes when the service answers"
                    + (lastError == null ? "" : " (" + lastError + ")");
            case STARTING -> "The room is starting — waiting for the class sign-in service to confirm its access";
        };
    }

    /** "10:42" (the host's zone) from the policy's changed_at (epoch seconds, possibly fractional, or an ISO string); null when unknown. */
    static String whenChanged(String changedAt) {
        if (changedAt == null || changedAt.isBlank() || changedAt.equals("null")) return null;
        try {
            double secs = Double.parseDouble(changedAt);
            return java.time.Instant.ofEpochMilli((long) (secs * 1000)).atZone(java.time.ZoneId.systemDefault()).toLocalTime().withSecond(0).withNano(0).toString();
        } catch (NumberFormatException e) {
            try { return java.time.Instant.parse(changedAt).atZone(java.time.ZoneId.systemDefault()).toLocalTime().withSecond(0).withNano(0).toString(); }
            catch (Exception e2) { return null; }
        }
    }

    /** Refuse unless access is ENABLED — the status the edge expects: 403 for a closed room, 503 while
     *  the answer is missing. */
    public void requireOpen() throws CourseIdentity.Denied {
        Access a = access();
        switch (a) {
            case ENABLED -> { }
            case DISABLED, FENCED -> throw new CourseIdentity.Denied(403, "E_SCOPE", closedReason(a), "course=closed:" + a.name().toLowerCase());
            default -> throw new CourseIdentity.Denied(503, "E_BUSY", closedReason(a), "course=closed:" + a.name().toLowerCase());
        }
    }

    /** The reason a model dispatch must not leave now, or null when it may (Capture: before the paid request). */
    public String dispatchBlock() {
        Access a = access();
        return a == Access.ENABLED ? null : closedReason(a);
    }

    public boolean fenced() { return fencedForGood; }

    public Map<String, Object> describe() {
        Map<String, Object> m = new LinkedHashMap<>();
        Access a = access();
        m.put("state", a.name().toLowerCase(java.util.Locale.ROOT));
        if (a != Access.ENABLED) m.put("reason", closedReason(a));
        CourseClient.RoomContext c = ctx;
        if (c != null) {
            m.put("accountId", c.accountId());
            m.put("workspaceGeneration", c.workspaceGeneration());
            m.put("handle", c.handle()); m.put("realm", c.realm()); m.put("roomState", c.roomState());
            m.put("courseSessionId", c.courseSessionId());
            if (c.policy() != null) {
                m.put("policyRevision", c.policy().revision());
                m.put("policyEnabled", c.policy().enabled());
                m.put("policyChangedAt", c.policy().changedAt());
                m.put("enforcementSeconds", c.policy().enforcementSeconds());
            }
        }
        m.put("since", java.time.Instant.ofEpochMilli(sinceAt).toString());
        if (lastOkAt > 0) m.put("lastConfirmedAt", java.time.Instant.ofEpochMilli(lastOkAt).toString());
        if (lastError != null) m.put("lastError", lastError);
        if (fencedForGood) m.put("fencedWhy", fencedWhy);
        if (refusedWhy != null) m.put("refusedWhy", refusedWhy);
        if (fences > 0) m.put("fences", fences);
        m.put("pollSeconds", 1); m.put("rpcTimeoutMs", CourseClient.AUTH_TIMEOUT.toMillis());
        return m;
    }
}
