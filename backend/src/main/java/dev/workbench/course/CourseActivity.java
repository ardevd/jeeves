package dev.workbench.course;

import dev.workbench.server.Log;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The classroom's current activity, read-only in a room (A10 contract): the classroom service owns
 * it and answers POST classroom:/v1/room/context {} with its identity — {id, revision, sha256} of the
 * revealed activity's definition — plus the teaching state revision and the canonical browser
 * handoff URLs for the student's own actions (help, readiness, findings).
 *
 * The room asks every 15 s and on demand. An official event carries the identity as the classroom
 * gave it; a label the student supplies is a claim, never authority (course.activity is read-only).
 * When the classroom has not answered for a minute the last identity is carried with
 * `activity_stale: true`; when it never answered, the operator's fallback label
 * (WORKBENCH_COURSE_ACTIVITY) is carried with source `operator-default` and stale — a room without
 * a classroom context says so rather than inventing a revision.
 */
public final class CourseActivity {

    /** What an event carries about the activity it belongs to. */
    public record Identity(String id, Integer revision, String sha256, String source, boolean stale, String fetchedAt) {
        public Map<String, Object> describe() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", id); m.put("revision", revision); m.put("sha256", sha256); m.put("source", source); m.put("stale", stale);
            if (fetchedAt != null) m.put("fetchedAt", fetchedAt);
            return m;
        }
    }

    static final long POLL_MS = 15_000, STALE_MS = 60_000;

    private final CourseClient client;
    private volatile CourseClient.ClassroomContext ctx;
    private volatile long fetchedAt;
    private volatile String lastError;
    private volatile boolean closed;
    private final Thread poller;

    public CourseActivity(CourseClient client) {
        this.client = client;
        this.poller = new Thread(this::loop, "course-activity");
        this.poller.setDaemon(true);
    }

    public void start() { poller.start(); }
    public void close() { closed = true; poller.interrupt(); }

    private void loop() {
        while (!closed) {
            refresh();
            try { Thread.sleep(POLL_MS); } catch (InterruptedException e) { return; }
        }
    }

    /** One ask of the classroom; failures are kept as the reason, never thrown at a caller. */
    public synchronized void refresh() {
        if (Course.classroomUrl().isEmpty()) { lastError = "no classroom URL configured (WORKBENCH_COURSE_CLASSROOM_URL)"; return; }
        try {
            ctx = client.classroomContext();
            fetchedAt = System.currentTimeMillis();
            lastError = null;
        } catch (CourseClient.Refused e) {
            lastError = "the classroom refused the room's credential (HTTP " + e.status + ")";
        } catch (CourseClient.Unavailable e) {
            lastError = e.getMessage();
        } catch (Exception e) {
            lastError = String.valueOf(e);
            Log.warn("course activity", "classroom context not read: " + e, null);
        }
    }

    /** The activity an event records now. */
    public Identity current() {
        CourseClient.ClassroomContext c = ctx;
        long age = System.currentTimeMillis() - fetchedAt;
        if (c == null) return new Identity(Course.activity(), null, null, "operator-default", true, null);
        boolean stale = age > STALE_MS;
        String at = java.time.Instant.ofEpochMilli(fetchedAt).toString();
        if (c.activity() == null) return new Identity(null, null, null, "classroom", stale, at);   // the classroom has revealed no activity yet
        return new Identity(c.activity().id(), c.activity().revision(), c.activity().sha256(), "classroom", stale, at);
    }

    /** The classroom's browser handoff for a student action (its `actions` block), or null. */
    public Map<String, Object> action(String name) {
        CourseClient.ClassroomContext c = ctx;
        if (c == null || c.actions() == null) return null;
        Object a = c.actions().get(name);
        if (!(a instanceof Map<?, ?> am)) return null;
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : am.entrySet()) out.put(String.valueOf(e.getKey()), e.getValue());
        return out;
    }

    public int teachingRevision() { CourseClient.ClassroomContext c = ctx; return c == null ? 0 : c.teachingRevision(); }
    public String lastError() { return lastError; }
    public boolean reachable() { return ctx != null && System.currentTimeMillis() - fetchedAt <= STALE_MS; }

    public Map<String, Object> describe() {
        Map<String, Object> m = new LinkedHashMap<>(current().describe());
        CourseClient.ClassroomContext c = ctx;
        if (c != null) { m.put("teachingRevision", c.teachingRevision()); m.put("actions", c.actions() == null ? java.util.List.of() : new java.util.ArrayList<>(c.actions().keySet())); }
        if (lastError != null) m.put("lastError", lastError);
        m.put("pollSeconds", POLL_MS / 1000); m.put("staleAfterSeconds", STALE_MS / 1000);
        return m;
    }
}
