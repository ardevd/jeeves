package dev.workbench.server;

import dev.workbench.engine.Engine;
import dev.workbench.registry.Json;
import dev.workbench.registry.OpException;
import java.io.File;
import java.nio.file.Files;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Workspace-level annotations (VIEWS_SPEC §4): tags (+user-defined families),
 * bookmarks, flows — one store per target, persisted as JSON in the target's
 * sidecar dir (<artifact>.workbench/). Mock targets have no real artifact →
 * in-memory only (same as history).
 *
 * Keying: every entry stores the symbol's ORIGINAL signature (origSig), which
 * survives renames (JEB resolves original handles post-rename; the mock engine's
 * names are stable). Reads resolve entries back to current addrs/names for
 * display — a tag/bookmark/flow on a renamed symbol still lands on it.
 *
 * Write model (MCP_SPEC §3): llm-actor tag.add records a PENDING entry
 * (proposal), never an applied tag; pending entries are listed separately.
 */
public final class Annotations {

    /** Okabe-Ito rotation for auto-created families (WORKBENCH_SPEC §14 palette). */
    private static final String[] FAMILY_PALETTE = {
            "#0072B2", "#D55E00", "#009E73", "#E69F00", "#CC79A7", "#56B4E9", "#F0E442" };

    private final Workspace ws;

    /** Per-target store (lazy-loaded from sidecar). */
    private static final class Store {
        List<Map<String, Object>> families;
        List<Map<String, Object>> tags;
        List<Map<String, Object>> bookmarks;
        List<Map<String, Object>> flows;
        int tagSeq, bmSeq, flowSeq;
        /* files whose load hit a TRANSIENT read failure (fix-1-r3 #1): the
           on-disk original may be healthy — the first write to that file parks
           it aside as evidence instead of silently overwriting unseen rows */
        final java.util.Set<String> degradedFiles = new java.util.HashSet<>();
    }

    private final Map<String, Store> stores = new LinkedHashMap<>();

    Annotations(Workspace ws) { this.ws = ws; }

    // ------------------------------------------------------------ plumbing

    private Store store(String tid) {
        Store s = stores.get(tid);
        if (s == null) {
            s = new Store();
            s.families = readList(tid, "families.json", s);
            s.tags = readList(tid, "tags.json", s);
            s.bookmarks = readList(tid, "bookmarks.json", s);
            s.flows = readList(tid, "flows.json", s);
            Map<String, Integer> seq = savedSeqs(tid, s);
            s.tagSeq = Math.max(maxSeq(s.tags, "tag-"), seq.getOrDefault("tag", 0));
            s.bmSeq = Math.max(maxSeq(s.bookmarks, "bm-"), seq.getOrDefault("bm", 0));
            s.flowSeq = Math.max(maxSeq(s.flows, "fl-"), seq.getOrDefault("fl", 0));
            stores.put(tid, s);
        }
        return s;
    }

    /** Persisted id counters (sidecar seq.json): ids stay monotonic across
     *  rm + restart — a restart must not reissue a deleted row's id to a new
     *  one, or a cached reference (e.g. the UI's flow list) silently resolves
     *  to the wrong row / a stale id misses (QA re-verify, "flow not found").
     *  Read+parsed ONCE per store load (fix-1-r2 F3: the per-key helper read
     *  the file three times and silently swallowed a corrupt seq.json as 0 —
     *  the very id-reissue bug seq.json exists to prevent, undiagnosed). */
    private Map<String, Integer> savedSeqs(String tid, Store s) {
        File dir = ws.sidecarDirFor(tid);
        if (dir == null) return Map.of();
        File f = new File(dir, "seq.json");
        if (!f.isFile()) return Map.of();
        try {
            Object parsed = Json.parse(Files.readString(f.toPath()));
            if (parsed instanceof Map<?, ?> m) {
                Map<String, Integer> out = new LinkedHashMap<>();
                for (String key : List.of("tag", "bm", "fl"))
                    if (m.get(key) instanceof Number n) out.put(key, n.intValue());
                return out;
            }
            /* right file, wrong shape — as corrupt as torn JSON. A FAILED park
               leaves it in place — mark degraded (fix-1-r5 #2) */
            if (!dev.workbench.registry.SidecarIO.parkCorrupt(f, "seq",
                    new IllegalStateException("expected a JSON object")))
                s.degradedFiles.add("seq.json");
        } catch (java.io.IOException e) {
            /* transient read hiccup ≠ corruption (fix-1-r2 F2): routed by
               permanence — a charset-parked file is already aside; a transient
               one marks seq.json degraded so the first writeSeq parks the
               healthy original before overwriting (fix-1-r3 #1) */
            if (!dev.workbench.registry.SidecarIO.readFailedOrPark(f, "seq", e))
                s.degradedFiles.add("seq.json");
        } catch (Exception e) {
            /* corrupt counters park like any sidecar: maxSeq(rows) is the
               fallback either way — here it's LOUD and the evidence survives.
               A FAILED park leaves the file in place — mark degraded
               (fix-1-r5 #2) */
            if (!dev.workbench.registry.SidecarIO.parkCorrupt(f, "seq", e))
                s.degradedFiles.add("seq.json");
        }
        return Map.of();
    }

    private void writeSeq(String tid, Store s) {
        File dir = ws.sidecarDirFor(tid);
        if (dir == null) return;                                  // mock target: in-memory
        File f = new File(dir, "seq.json");
        if (s.degradedFiles.contains("seq.json")) {
            if (!dev.workbench.registry.SidecarIO.parkHealthyBeforeOverwrite(f, "seq"))
                return;                                  // flag kept (fix-1-r4 #5)
            s.degradedFiles.remove("seq.json");
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("tag", s.tagSeq);
        m.put("bm", s.bmSeq);
        m.put("fl", s.flowSeq);
        dev.workbench.registry.SidecarIO.writeAtomic(f, Json.write(m), "seq");
    }

    private static int maxSeq(List<Map<String, Object>> rows, String prefix) {
        int max = 0;
        for (Map<String, Object> r : rows) {
            String id = String.valueOf(r.get("id"));
            if (id.startsWith(prefix))
                try { max = Math.max(max, Integer.parseInt(id.substring(prefix.length()))); }
                catch (NumberFormatException ignored) {}
        }
        return max;
    }

    private void write(String tid, String file, List<Map<String, Object>> rows) {
        File dir = ws.sidecarDirFor(tid);
        if (dir == null) return;                                  // mock target: in-memory
        File f = new File(dir, file);
        Store st = stores.get(tid);
        if (st != null && st.degradedFiles.contains(file)) {
            /* never overwrite un-parked evidence: a failed park keeps the flag
               and SKIPS the write (fix-1-r4 #5) */
            if (!dev.workbench.registry.SidecarIO.parkHealthyBeforeOverwrite(f, file))
                return;
            st.degradedFiles.remove(file);
        }
        dev.workbench.registry.SidecarIO.writeAtomic(f, Json.write(rows), file);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> readList(String tid, String file, Store s) {
        File dir = ws.sidecarDirFor(tid);
        if (dir != null) {
            File f = new File(dir, file);
            if (f.isFile()) {
                try {
                    Object parsed = Json.parse(Files.readString(f.toPath()));
                    if (parsed instanceof List<?> l) {
                        List<Map<String, Object>> out = new ArrayList<>();
                        for (Object o : l)
                            if (o instanceof Map<?, ?>) out.add(new LinkedHashMap<>((Map<String, Object>) o));
                        return out;
                    }
                    /* right file, wrong shape — as corrupt as torn JSON. A FAILED
                       park leaves it in place — mark degraded (fix-1-r5 #2) */
                    if (!dev.workbench.registry.SidecarIO.parkCorrupt(f, file,
                            new IllegalStateException("expected a JSON list")))
                        s.degradedFiles.add(file);
                } catch (java.io.IOException e) {
                    /* transient read hiccup ≠ corruption (fix-1-r2 F2): routed by
                       permanence — a charset-parked file is already aside; a
                       transient one marks the file degraded so the first write
                       parks the healthy original before overwriting (fix-1-r3 #1) */
                    if (!dev.workbench.registry.SidecarIO.readFailedOrPark(f, file, e))
                        s.degradedFiles.add(file);
                } catch (Exception e) {
                    /* LOUD + park (fix-1 review F2): a torn tags/bookmarks/flows
                       file silently read as empty, then the next write made the
                       loss permanent. A FAILED park leaves the file in place —
                       mark degraded so the next write retries the park first
                       (fix-1-r5 #2) */
                    if (!dev.workbench.registry.SidecarIO.parkCorrupt(f, file, e))
                        s.degradedFiles.add(file);
                }
            }
        }
        return new ArrayList<>();
    }

    /** Resolve a symbol on a target's engine; null when it doesn't resolve. */
    private Map<String, Object> resolveSafe(String tid, String symbol) {
        try {
            return ws.engineFor(tid).resolve(symbol);
        } catch (Exception e) {
            return null;
        }
    }

    /** The rename-surviving key for a symbol: its origSig (JEB) else fqcn/addr (mock). */
    private String keyFor(String tid, String symbol) throws OpException {
        Map<String, Object> r = resolveSafe(tid, symbol);
        if (r == null)
            throw OpException.notFound("symbol not found: " + symbol, "target=" + tid);
        for (String k : List.of("origSig", "addr", "fqcn")) {
            Object v = r.get(k);
            if (v != null && !String.valueOf(v).isBlank()) return String.valueOf(v);
        }
        if (r.get("owner") != null) return r.get("owner") + "." + r.get("name");
        return String.valueOf(r.get("name"));
    }

    /** Split "symbol:line" → [symbol, line] (line = -1 when absent). */
    private static Object[] splitLine(String symbol, Integer line) throws OpException {
        if (line != null && line > 0) return new Object[] { symbol, line };
        if (symbol != null && symbol.matches(".*:\\d+$")) {
            int cut = symbol.lastIndexOf(':');
            // a Dalvik addr never ends in :digits (fields end in a type sig), so :N is a line ref
            return new Object[] { symbol.substring(0, cut),
                    Integer.parseInt(symbol.substring(cut + 1)) };
        }
        return new Object[] { symbol, -1 };
    }

    private static boolean isLlm(String actor) {
        return actor != null && "agent".equals(EventBus.actorKind(actor));
    }

    private static void rejectDelimiters(String s, String what) throws OpException {
        if (s != null && (s.indexOf('|') >= 0 || s.indexOf('\n') >= 0 || s.indexOf('\r') >= 0))
            throw OpException.syntax(what + " must not contain '|' or newlines", "");
    }

    /** Display row for a stored entry: resolve origSig → current addr/label (stale flag on failure). */
    private Map<String, Object> present(String tid, Map<String, Object> e) {
        Map<String, Object> row = new LinkedHashMap<>(e);
        Map<String, Object> r = resolveSafe(tid, String.valueOf(e.get("symbol")));
        if (r == null) {
            row.put("stale", true);
            row.put("label", e.get("symbol"));
            return row;
        }
        if (r.get("addr") != null) row.put("addr", r.get("addr"));
        String kind = String.valueOf(r.getOrDefault("kind", ""));
        String label;
        if ("class".equals(kind)) label = String.valueOf(r.getOrDefault("fqcn", r.get("name")));
        else if (r.get("owner") != null) label = r.get("owner") + "." + r.get("name");
        else label = String.valueOf(r.get("name"));
        row.put("label", label);
        if (Boolean.TRUE.equals(r.get("renamed"))) {
            row.put("renamed", true);
            row.put("origName", r.get("origName"));
        }
        return row;
    }

    // ------------------------------------------------------------ tags

    private Map<String, Object> family(String tid, String name) {
        for (Map<String, Object> f : store(tid).families)
            if (name.equals(f.get("name"))) return f;
        return null;
    }

    public synchronized Map<String, Object> tagFamilyAdd(String targetParam, String name, String color)
            throws OpException {
        String tid = ws.resolveTargetIdOrActive(targetParam);
        if (name == null || name.isBlank()) throw OpException.syntax("family name required", "");
        rejectDelimiters(name, "family name");
        if (color != null && !color.matches("#[0-9a-fA-F]{3,8}"))
            throw OpException.syntax("color must be a #hex literal", "color=" + color);
        Map<String, Object> existing = family(tid, name);
        if (existing != null) {
            if (color != null) {
                existing.put("color", color);
                write(tid, "families.json", store(tid).families);
            }
            return present(tid, existing);
        }
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("name", name);
        f.put("color", color != null ? color
                : FAMILY_PALETTE[store(tid).families.size() % FAMILY_PALETTE.length]);
        store(tid).families.add(f);
        write(tid, "families.json", store(tid).families);
        return new LinkedHashMap<>(f);
    }

    /** Resolve any symbol spelling to its canonical tag key (origSig-stable),
     *  or the raw string when it doesn't resolve (or no target is active) —
     *  the same fallback tagSettle uses. Lets proposal matching compare KEYS,
     *  not spellings (review). Never throws: a best-effort canonicalization. */
    public String keyOf(String targetParam, String symbol) {
        try {
            String tid = ws.resolveTargetIdOrActive(targetParam);
            return keyFor(tid, symbol);
        } catch (Exception e) {
            return symbol;
        }
    }

    /** tag.add <symbol-or-symbol:line> <family:tag> [note]. llm actor → pending proposal. */
    public synchronized Map<String, Object> tagAdd(String targetParam, String symbol, String familyTag,
            String note, String actor, Integer line) throws OpException {
        String tid = ws.resolveTargetIdOrActive(targetParam);
        if (familyTag == null || !familyTag.contains(":"))
            throw OpException.syntax("tag name must be family:tag (e.g. crypto:aes)", "name=" + familyTag);
        String family = familyTag.substring(0, familyTag.indexOf(':')).trim();
        String name = familyTag.substring(familyTag.indexOf(':') + 1).trim();
        if (family.isEmpty() || name.isEmpty())
            throw OpException.syntax("tag name must be family:tag", "name=" + familyTag);
        rejectDelimiters(name, "tag name");
        rejectDelimiters(note, "tag note");
        Object[] sl = splitLine(symbol, line);
        String key = keyFor(tid, String.valueOf(sl[0]));
        if (family(tid, family) == null) tagFamilyAdd(tid, family, null);   // auto-create, palette color

        Store st = store(tid);
        /* idempotent within the right class (review findings 2/6/8): an agent
           re-tagging a LIVE row gets it back with no proposal (already applied);
           re-proposing its OWN pending row updates the note; another agent's
           pending row is never clobbered; a human add over a pending proposal
           disposes (creates the live row). `deduped` tells the caller nothing
           new was created — Ops skips the propose() write then. */
        boolean agent = isLlm(actor);
        List<Map<String, Object>> superseded = agent ? null : new ArrayList<>();
        Map<String, Object> liveHit = null;
        for (Map<String, Object> t : st.tags) {
            Object tline = t.get("line");
            if (!(key.equals(t.get("symbol")) && name.equals(t.get("name"))
                    && family.equals(t.get("family"))
                    && tline instanceof Number && (int) sl[1] == ((Number) tline).intValue())) continue;
            boolean pend = Boolean.TRUE.equals(t.get("pending"));
            if (pend) {
                /* a human DISPOSES: pending proposals for the key are superseded by the
                   live row (the question is answered), never left to duplicate it (F6) —
                   collected wherever they sit; a legacy file may hold them AFTER the
                   live row, so the human path never returns mid-loop (review) */
                if (!agent){ superseded.add(t); continue; }
                /* same agentId, any spelling: llm:agent-N and bare agent-N are ONE
                   proposer (review: raw equals() let one agent hold two pendings) */
                if (!AgentManager.agentIdOf(actor)
                        .equals(AgentManager.agentIdOf(String.valueOf(t.get("actor"))))) continue;
                if (note != null) t.put("note", note);          /* own pending: note sync */
                write(tid, "tags.json", st.tags);
                Map<String, Object> out = present(tid, t);
                out.put("deduped", true);
                return out;
            }
            if (agent){ Map<String, Object> out = present(tid, t); out.put("deduped", true); return out; }
            liveHit = t;
        }
        if (!agent && !superseded.isEmpty()) st.tags.removeAll(superseded);
        if (!agent && liveHit != null) {
            if (note != null) liveHit.put("note", note);
            write(tid, "tags.json", st.tags);
            Map<String, Object> out = present(tid, liveHit);
            out.put("deduped", true);
            if (!superseded.isEmpty()) out.put("superseded", superseded.size());
            return out;
        }
        Map<String, Object> t = new LinkedHashMap<>();
        t.put("id", "tag-" + (++st.tagSeq));
        t.put("symbol", key);
        t.put("line", (int) sl[1]);
        t.put("family", family);
        t.put("name", name);
        t.put("note", note == null ? "" : note);
        t.put("actor", actor == null ? "human" : actor);
        t.put("ts", Instant.now().toString());
        if (isLlm(actor)) {
            t.put("pending", true);                     // agents propose, humans dispose
            t.put("status", "pending");
        }
        st.tags.add(t);
        write(tid, "tags.json", st.tags);
        writeSeq(tid, st);
        Map<String, Object> out = present(tid, t);
        if (superseded != null && !superseded.isEmpty()) out.put("superseded", superseded.size());
        return out;
    }

    public synchronized Map<String, Object> tagRm(String targetParam, String id) throws OpException {
        String tid = ws.resolveTargetIdOrActive(targetParam);
        Store st = store(tid);
        boolean removed = st.tags.removeIf(t -> id.equals(t.get("id")));
        if (!removed) throw OpException.notFound("tag not found: " + id, "target=" + tid);
        write(tid, "tags.json", st.tags);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("removed", id);
        return out;
    }

    public synchronized Map<String, Object> tagList(String targetParam, String familyFilter)
            throws OpException {
        String tid = ws.resolveTargetIdOrActive(targetParam);
        Store st = store(tid);
        List<Object> rows = new ArrayList<>();
        int pending = 0;
        for (Map<String, Object> t : st.tags) {
            if (Boolean.TRUE.equals(t.get("pending"))) { pending++; continue; }
            if (familyFilter != null && !familyFilter.isBlank() && !familyFilter.equals(t.get("family")))
                continue;
            Map<String, Object> row = present(tid, t);
            Map<String, Object> f = family(tid, String.valueOf(t.get("family")));
            if (f != null) row.put("color", f.get("color"));
            rows.add(row);
        }
        List<Object> fams = new ArrayList<>();
        for (Map<String, Object> f : st.families) fams.add(new LinkedHashMap<>(f));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("tags", rows);
        out.put("families", fams);
        out.put("pendingCount", pending);
        return out;
    }

    /** Settle a pending tag proposal (AGENTS_SPEC §2): accept keeps the entry
     *  (pending flag cleared), reject removes it. Matches by symbol (origSig or raw),
     *  by LINE (per-line tags are the normal case — two proposals for one symbol
     *  at different lines must not collide, review), and by proposer: N agents can
     *  hold N pending rows for one key, so the row is matched by the proposal's
     *  agentId or accepting pp-B would promote agent-A's row with agent-A's note
     *  (review F1). forAgentId/line null = any actor/line (legacy proposals). */
    public synchronized Map<String, Object> tagSettle(String targetParam, String symbol,
            String familyTag, boolean accept, String forAgentId, Integer line) throws OpException {
        String tid = ws.resolveTargetIdOrActive(targetParam);
        String family = familyTag.substring(0, familyTag.indexOf(':')).trim();
        String name = familyTag.substring(familyTag.indexOf(':') + 1).trim();
        String key;
        try {
            key = keyFor(tid, symbol);
        } catch (OpException e) {
            key = symbol;                                       // unresolved — match raw
        }
        Store st = store(tid);
        Map<String, Object> found = null;
        /* a line-less (legacy) proposal matches a LINE-LESS row — never whichever
           per-line row happens to come first (review). Missing line reads as -1,
           tagAdd's line-less default; the guarded read also kills the NPE class
           for legacy rows with no line field at all. */
        int wantLine = line == null ? -1 : line.intValue();
        for (Map<String, Object> t : st.tags) {
            Object tl = t.get("line");
            int rowLine = tl instanceof Number ? ((Number) tl).intValue() : -1;
            if (name.equals(t.get("name")) && family.equals(t.get("family"))
                    && Boolean.TRUE.equals(t.get("pending"))
                    && (key.equals(t.get("symbol")) || symbol.equals(t.get("symbol")))
                    && rowLine == wantLine
                    && (forAgentId == null
                        || forAgentId.equals(AgentManager.agentIdOf(String.valueOf(t.get("actor")))))) {
                found = t;
                break;
            }
        }
        if (found == null)
            throw OpException.notFound("no pending tag: " + familyTag + " on " + symbol, "target=" + tid);
        int superseded = 0;
        if (accept) {
            found.remove("pending");
            found.remove("status");
            /* the key is LIVE now — every OTHER agent's pending row for it is
               answered and must go, or a second accept promotes a second live
               row for the same key (review F6) */
            final String fkey = String.valueOf(found.get("symbol"));
            final int fline = found.get("line") instanceof Number fl ? fl.intValue() : -1;
            List<Map<String, Object>> dead = new ArrayList<>();
            for (Map<String, Object> t : st.tags) {
                Object dl = t.get("line");
                if (t != found && Boolean.TRUE.equals(t.get("pending"))
                        && name.equals(t.get("name")) && family.equals(t.get("family"))
                        && fkey.equals(t.get("symbol"))
                        && fline == (dl instanceof Number ? ((Number) dl).intValue() : -1))
                    dead.add(t);
            }
            superseded = dead.size();
            st.tags.removeAll(dead);
        } else {
            st.tags.remove(found);
        }
        write(tid, "tags.json", st.tags);
        Map<String, Object> out = new LinkedHashMap<>(found);
        out.put("settled", accept ? "accepted" : "rejected");
        out.put("key", found.get("symbol"));
        if (superseded > 0) out.put("superseded", superseded);
        return out;
    }

    // ------------------------------------------------------------ bookmarks

    public synchronized Map<String, Object> bookmarkAdd(String targetParam, String symbol,
            Integer line, String note, String actor) throws OpException {
        String tid = ws.resolveTargetIdOrActive(targetParam);
        Object[] sl = splitLine(symbol, line);
        if ((int) sl[1] <= 0) throw OpException.syntax("bookmark needs a line (symbol:line)", "symbol=" + symbol);
        rejectDelimiters(note, "bookmark note");
        String key = keyFor(tid, String.valueOf(sl[0]));
        Store st = store(tid);
        for (Map<String, Object> b : st.bookmarks) {                    // idempotent
            if (key.equals(b.get("symbol")) && (int) sl[1] == ((Number) b.get("line")).intValue()) {
                if (note != null) b.put("note", note);
                write(tid, "bookmarks.json", st.bookmarks);
                return present(tid, b);
            }
        }
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("id", "bm-" + (++st.bmSeq));
        b.put("symbol", key);
        b.put("line", (int) sl[1]);
        b.put("note", note == null ? "" : note);
        b.put("actor", actor == null ? "human" : actor);
        b.put("ts", Instant.now().toString());
        st.bookmarks.add(b);
        write(tid, "bookmarks.json", st.bookmarks);
        writeSeq(tid, st);
        return present(tid, b);
    }

    public synchronized Map<String, Object> bookmarkRm(String targetParam, String id) throws OpException {
        String tid = ws.resolveTargetIdOrActive(targetParam);
        Store st = store(tid);
        boolean removed = st.bookmarks.removeIf(b -> id.equals(b.get("id")));
        if (!removed) throw OpException.notFound("bookmark not found: " + id, "target=" + tid);
        write(tid, "bookmarks.json", st.bookmarks);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("removed", id);
        return out;
    }

    public synchronized List<Object> bookmarkList(String targetParam) throws OpException {
        String tid = ws.resolveTargetIdOrActive(targetParam);
        List<Object> rows = new ArrayList<>();
        for (Map<String, Object> b : store(tid).bookmarks) rows.add(present(tid, b));
        return rows;
    }

    // ------------------------------------------------------------ flows (single-target per VIEWS_SPEC §4)

    public synchronized Map<String, Object> flowAdd(String targetParam, String name, String source,
            String sink, List<String> hops, String actor) throws OpException {
        String tid = ws.resolveTargetIdOrActive(targetParam);
        if (name == null || name.isBlank()) throw OpException.syntax("flow name required", "");
        rejectDelimiters(name, "flow name");
        String srcKey = keyFor(tid, source);                       // source must resolve here
        String sinkKey = keyForLenient(tid, sink);                 // sink may live on another target
        List<String> hopKeys = new ArrayList<>();
        if (hops != null)
            for (String h : hops) hopKeys.add(keyForLenient(tid, h));
        Store st = store(tid);
        for (Map<String, Object> f : st.flows)                          // idempotent on name
            if (name.equals(f.get("name"))) return presentFlow(tid, f);
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("id", "fl-" + (++st.flowSeq));
        f.put("name", name);
        f.put("source", srcKey);
        f.put("sink", sinkKey);
        f.put("hops", hopKeys);
        f.put("status", "draft");
        f.put("actor", actor == null ? "human" : actor);
        f.put("ts", Instant.now().toString());
        st.flows.add(f);
        write(tid, "flows.json", st.flows);
        writeSeq(tid, st);
        return presentFlow(tid, f);
    }

    public synchronized Map<String, Object> flowRm(String targetParam, String id) throws OpException {
        String tid = ws.resolveTargetIdOrActive(targetParam);
        Store st = store(tid);
        boolean removed = st.flows.removeIf(f -> id.equals(f.get("id")));
        if (!removed) throw OpException.notFound("flow not found: " + id, "target=" + tid);
        write(tid, "flows.json", st.flows);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("removed", id);
        return out;
    }

    public synchronized List<Object> flowList(String targetParam) throws OpException {
        String tid = ws.resolveTargetIdOrActive(targetParam);
        List<Object> rows = new ArrayList<>();
        for (Map<String, Object> f : store(tid).flows) {
            Map<String, Object> row = presentFlow(tid, f);
            row.remove("hopsResolved");
            rows.add(row);
        }
        return rows;
    }

    public synchronized Map<String, Object> flowGet(String targetParam, String id) throws OpException {
        String tid = ws.resolveTargetIdOrActive(targetParam);
        for (Map<String, Object> f : store(tid).flows)
            if (id.equals(f.get("id"))) return presentFlow(tid, f);
        throw OpException.notFound("flow not found: " + id, "target=" + tid);
    }

    /** flow.promote lookup — defensive: id first, then name. The UI can hold a
     *  stale id from a cached list (rm + re-add reissues ids); the name is what
     *  the user actually means. */
    public synchronized Map<String, Object> flowResolve(String targetParam, String idOrName)
            throws OpException {
        String tid = ws.resolveTargetIdOrActive(targetParam);
        Map<String, Object> byName = null;
        for (Map<String, Object> f : store(tid).flows) {
            if (idOrName.equals(f.get("id"))) return presentFlow(tid, f);
            if (idOrName.equals(f.get("name"))) byName = f;
        }
        if (byName != null) return presentFlow(tid, byName);
        throw OpException.notFound("flow not found: " + idOrName, "target=" + tid);
    }

    /** Flow row with endpoints/hops resolved + cross-target detection: if the sink
     *  doesn't resolve on this target but does on another open one, the flow is
     *  flagged crossTarget (a Map seed — VIEWS_SPEC §4, GRAPHS_SPEC §2). */
    private Map<String, Object> presentFlow(String tid, Map<String, Object> f) {
        Map<String, Object> row = new LinkedHashMap<>(f);
        Map<String, Object> src = resolveSafe(tid, String.valueOf(f.get("source")));
        Map<String, Object> dst = resolveSafe(tid, String.valueOf(f.get("sink")));
        row.put("sourceLabel", src != null ? labelOf(src) : f.get("source"));
        row.put("sinkLabel", dst != null ? labelOf(dst) : f.get("sink"));
        if (src != null && src.get("addr") != null) row.put("sourceAddr", src.get("addr"));
        if (dst != null && dst.get("addr") != null) row.put("sinkAddr", dst.get("addr"));
        List<Object> hops = new ArrayList<>();
        @SuppressWarnings("unchecked")
        List<String> keys = (List<String>) f.get("hops");
        if (keys != null)
            for (String k : keys) {
                Map<String, Object> h = resolveSafe(tid, k);
                Map<String, Object> step = new LinkedHashMap<>();
                step.put("symbol", k);
                step.put("label", h != null ? labelOf(h) : k);
                if (h != null && h.get("addr") != null) step.put("addr", h.get("addr"));
                if (h == null) step.put("stale", true);
                hops.add(step);
            }
        row.put("hopsResolved", hops);
        row.put("hopCount", hops.size());
        if (dst == null) {
            for (String other : ws.targetIds())
                if (!other.equals(tid) && resolveSafe(other, String.valueOf(f.get("sink"))) != null) {
                    row.put("crossTarget", true);
                    row.put("otherTarget", other);
                    break;
                }
        }
        return row;
    }

    /** keyFor that tolerates unresolvable symbols (cross-target flow endpoints):
     *  the raw symbol is stored and resolution happens on read (stale/other-target). */
    private String keyForLenient(String tid, String symbol) throws OpException {
        try {
            return keyFor(tid, symbol);
        } catch (OpException e) {
            return symbol;
        }
    }

    private static String labelOf(Map<String, Object> r) {
        String kind = String.valueOf(r.getOrDefault("kind", ""));
        if ("class".equals(kind)) return String.valueOf(r.getOrDefault("fqcn", r.get("name")));
        if (r.get("owner") != null) return r.get("owner") + "." + r.get("name");
        return String.valueOf(r.get("name"));
    }
}
