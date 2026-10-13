package dev.workbench.server;

import dev.workbench.registry.Json;
import java.io.File;
import java.nio.file.Files;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Proposal store (AGENTS_SPEC §2): the reviewable queue for every llm-actor
 * mutation — rename, comment, tag, map verdict. A proposal is {id, kind, target,
 * symbol/map refs, old/new, confidence, rationale, agentId, ts, state}. Nothing
 * an agent writes is final until a human settles it via agent.review (accept
 * applies the underlying op; reject marks rejected). Persisted at
 * backend/.workbench/proposals.json (session persistence, AGENTS_SPEC §8.2
 * decision: proposals survive restart like history does).
 */
public final class ProposalStore {

    private final File file;
    private final List<Map<String, Object>> proposals = new ArrayList<>();
    private int seq;
    /* transient read failure at load: the on-disk queue may be healthy — the
       first persist parks it as evidence before writing (fix-1-r3 #1; a silent
       empty start also reset seq, reissuing live pp-ids) */
    private boolean degraded;

    public ProposalStore(File dir) {
        this.file = new File(dir, "proposals.json");
        load();
    }

    @SuppressWarnings("unchecked")
    private void load() {
        if (!file.isFile()) return;
        try {
            Object p = Json.parse(Files.readString(file.toPath()));
            if (p instanceof List)
                for (Object o : (List<Object>) p)
                    if (o instanceof Map) {
                        Map<String, Object> pr = new LinkedHashMap<>((Map<String, Object>) o);
                        proposals.add(pr);
                        String id = String.valueOf(pr.get("id"));
                        if (id.startsWith("pp"))
                            try { seq = Math.max(seq, Integer.parseInt(id.substring(2))); }
                            catch (NumberFormatException ignored) {}
                    }
        } catch (java.io.IOException e) {
            /* transient read hiccup ≠ corruption (fix-1-r2 F2): routed by
               permanence — a charset-parked file is already aside; a transient
               one marks the store degraded so the first persist parks the
               healthy original before overwriting */
            if (!dev.workbench.registry.SidecarIO.readFailedOrPark(file, "proposals", e))
                degraded = true;
        } catch (Exception e) {
            /* a FAILED park leaves the torn queue in place — mark degraded so
               the next persist retries the park before overwriting (fix-1-r5 #2) */
            if (!dev.workbench.registry.SidecarIO.parkCorrupt(file, "proposals", e))
                degraded = true;
        }
    }

    private void persist() {
        if (degraded) {
            /* never overwrite un-parked evidence: a failed park keeps the flag
               and SKIPS the write (fix-1-r4 #5) */
            if (!dev.workbench.registry.SidecarIO.parkHealthyBeforeOverwrite(file, "proposals"))
                return;
            degraded = false;
        }
        dev.workbench.registry.SidecarIO.writeAtomic(file, Json.write(proposals), "proposals");
    }

    /** Record a pending proposal. Returns it (id/state stamped). */
    /** id minted by the caller — AgentManager's ONE sequence across projects (batch 2: per-store
     *  ids collided, and a review issued under the wrong active project settled a look-alike). */
    public synchronized Map<String, Object> add(String kind, Map<String, Object> body, String id) {
        Map<String, Object> p = new LinkedHashMap<>(body);
        p.put("id", id);
        if (id.startsWith("pp"))
            try { seq = Math.max(seq, Integer.parseInt(id.substring(2))); } catch (NumberFormatException ignored) {}
        p.put("kind", kind);
        p.put("state", "pending");
        p.put("ts", Instant.now().toString());
        proposals.add(p);
        persist();
        return new LinkedHashMap<>(p);
    }

    /** Pending proposals, optionally filtered by mission (agentId) and/or kind. */
    public synchronized List<Object> list(String mission, String kind) { return list(mission, kind, null); }

    /** state: null or "pending" — the review queue; "all" — every row, newest first (the EDITS
     *  pane under the apply-directly policy: applied, pending, refused, rejected, purged);
     *  any other value — rows in exactly that state. */
    public synchronized List<Object> list(String mission, String kind, String state) {
        List<Object> out = new ArrayList<>();
        String want = state == null || state.isBlank() ? "pending" : state;
        for (int i = proposals.size() - 1; i >= 0; i--) {
            Map<String, Object> p = proposals.get(i);
            if (!"all".equals(want) && !want.equals(p.get("state"))) continue;
            if (mission != null && !mission.isBlank() && !mission.equals(p.get("agentId"))) continue;
            if (kind != null && !kind.isBlank() && !kind.equals(p.get("kind"))) continue;
            out.add(new LinkedHashMap<>(p));
        }
        return out;
    }

    public synchronized int pendingCount() {
        int n = 0;
        for (Map<String, Object> p : proposals)
            if ("pending".equals(p.get("state"))) n++;
        return n;
    }

    public synchronized Map<String, Object> get(String id) throws dev.workbench.registry.OpException {
        for (Map<String, Object> p : proposals)
            if (id.equals(p.get("id"))) return p;
        throw dev.workbench.registry.OpException.notFound("proposal not found: " + id, "");
    }

    /** Is this proposal still open? Lets a caller re-validate a list() snapshot
        before answering as if it were live — a review() can settle in the window
        between the snapshot and the answer (review). */
    public synchronized boolean isPending(String id) {
        for (Map<String, Object> p : proposals)
            if (id.equals(p.get("id"))) return "pending".equals(p.get("state"));
        return false;
    }

    /** Update one body field of a PENDING proposal (a re-proposal's newer note,
        synced so the reviewer reads what accept will apply) and persist.
        Returns false when the proposal is missing or already settled — the
        caller must not then answer as if a pending proposal exists (review). */
    public synchronized boolean retouch(String id, String key, Object value) {
        for (Map<String, Object> p : proposals)
            if (id.equals(p.get("id")) && "pending".equals(p.get("state"))) {
                p.put(key, value);
                persist();
                return true;
            }
        return false;
    }

    /** Stamp a row whatever its state (the history id an applied write got, the address it
     *  moved to) — unlike retouch, which is the reviewer's edit of a PENDING row. */
    public synchronized boolean stamp(String id, String key, Object value) {
        for (Map<String, Object> p : proposals)
            if (id.equals(p.get("id"))) {
                p.put(key, value);
                persist();
                return true;
            }
        return false;
    }

    public synchronized Map<String, Object> settle(String id, String state) {
        Map<String, Object> p = null;
        for (Map<String, Object> x : proposals)
            if (id.equals(x.get("id"))) { p = x; break; }
        if (p != null) {
            p.put("state", state);
            p.put("settledTs", Instant.now().toString());
            persist();
        }
        return p == null ? null : new LinkedHashMap<>(p);
    }
}
