package dev.workbench.server;

import dev.workbench.registry.Json;
import dev.workbench.registry.OpException;
import java.io.File;
import java.nio.file.Files;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Map storage + ops (GRAPHS_SPEC §3 schema, §5 op tables). Maps are project-level
 * artifacts in the project sidecar: {@code backend/.workbench/projects/<name-slug>/maps/<id>.map.json}
 * (a {@code .project-name} marker in the dir resolves slug collisions); the
 * per-project type registry (node kinds, edge kinds, verdicts, tags — §4.2
 * palette) lives in {@code maptypes.json} next to them. One store per project,
 * swapped by the Workspace on switch/open/close (map-r4 fix); the legacy layouts
 * (global {@code maps/}, id-keyed {@code projects/p1/}) fold into the boot
 * project's dir at boot and are parked as {@code *.migrated}.
 *
 * Symbol refs carry {addr, origSig, entry} and are validated through engine
 * resolve when possible — LENIENT on unresolved (maps span targets that may not
 * be open, per the milestone brief). llm-actor verdicts land as pending
 * proposals (not applied); a human setverdict settles (apply or reject).
 * All mutation ops are idempotent on identical params (MCP_SPEC §5).
 */
public final class MapStore {

    /** Per-field verdict layer (GRAPHS_SPEC §3): distinct from node verdicts.
     *  Insertion-ordered so error messages render it deterministically. */
    private static final Set<String> FIELD_VERDICTS = new LinkedHashSet<>(
            List.of("verbatim", "constrained", "server_regenerated", "unknown"));

    private final File dir;
    private final File typesFile;
    private final Workspace ws;
    private final Map<String, Map<String, Object>> maps = new LinkedHashMap<>();
    private Map<String, List<Map<String, Object>>> types;
    /* transient read failures at load (fix-1-r3 #1/#2): the on-disk file may be
       healthy, so the first persist parks it aside as evidence before writing.
       mapFiles holds the FILE names (loadMaps may fail before the id is known;
       persist derives the same name from the map id). */
    private boolean typesDegraded;
    private final java.util.Set<String> degradedMapFiles = new java.util.HashSet<>();

    /** A store is constructed UNPUBLISHED: the Workspace calls isEmpty/has/seed
        before setMaps exposes it — safe only because no other thread can hold
        this instance's lock yet (load-bearing for the map-r5 F14 discipline). */
    public MapStore(Workspace ws, File mapsDir) {
        this.ws = ws;
        this.dir = mapsDir;
        this.typesFile = new File(mapsDir.getParentFile(), "maptypes.json");
        dir.mkdirs();
        loadTypes();
        loadMaps();
    }

    // ------------------------------------------------------------ type registry (GRAPHS_SPEC §4.2)

    private static Map<String, List<Map<String, Object>>> defaultTypes() {
        Map<String, List<Map<String, Object>>> t = new LinkedHashMap<>();
        t.put("nodeKinds", new ArrayList<>(List.of(
                type("endpoint", "#0072B2"), type("frontdoor", "#56B4E9"),
                type("allowlist", "#CC79A7"), type("handler", "#E69F00"),
                type("sink", "#D55E00"), type("source", "#009E73"),
                type("native", "#7d8794"), type("custom", "#F0E442"))));
        t.put("edgeKinds", new ArrayList<>(List.of(
                type("transport", "#0072B2"), type("dispatch", "#7d8794"),
                type("call", "#E69F00"), type("dataflow", "#009E73"), type("gate", "#CC79A7"))));
        t.put("verdicts", new ArrayList<>(List.of(
                type("authored", "#D55E00"), type("mixed", "#E69F00"),
                type("identity", "#009E73"), type("gap", "#7d8794"),
                type("dead", "#2b3138"), type("none", "#4d5765"))));
        t.put("tags", new ArrayList<>(List.of(
                type("push", "#0072B2"), type("mdm", "#D55E00"),
                type("sync", "#E69F00"), type("location", "#009E73"))));
        return t;
    }

    private static Map<String, Object> type(String name, String color) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name);
        m.put("color", color);
        m.put("visible", true);
        return m;
    }

    @SuppressWarnings("unchecked")
    private void loadTypes() {
        if (typesFile.isFile()) {
            try {
                Object p = Json.parse(Files.readString(typesFile.toPath()));
                if (p instanceof Map) {
                    types = new LinkedHashMap<>();
                    ((Map<String, Object>) p).forEach((k, v) -> {
                        List<Map<String, Object>> rows = new ArrayList<>();
                        if (v instanceof List)
                            for (Object o : (List<Object>) v)
                                if (o instanceof Map) rows.add(new LinkedHashMap<>((Map<String, Object>) o));
                        types.put(k, rows);
                    });
                    return;
                }
                /* right file, wrong shape — as corrupt as torn JSON: park the
                   evidence BEFORE the defaults reset + persist overwrite a custom
                   palette (fix-1-r2 F4). A FAILED park leaves the file in place —
                   mark degraded so persistTypes retries the park first
                   (fix-1-r5 #2) */
                if (!dev.workbench.registry.SidecarIO.parkCorrupt(typesFile, "maptypes",
                        new IllegalStateException("expected a JSON object")))
                    typesDegraded = true;
            } catch (java.io.IOException e) {
                /* transient read hiccup ≠ corruption (fix-1-r2 F2): routed by
                   permanence. A charset-parked file is already aside — defaults
                   load in memory and the next mutation writes fresh (fix-1-r3
                   #7). A TRANSIENT failure marks the store degraded: in-memory
                   defaults only, and the first persistTypes parks the healthy
                   on-disk palette as evidence before writing (fix-1-r3 #2 — the
                   F4 commit's "never clobbered on a hiccup" was one mutation
                   away from being false). */
                if (!dev.workbench.registry.SidecarIO.readFailedOrPark(typesFile, "maptypes", e))
                    typesDegraded = true;
                types = defaultTypes();
                return;
            } catch (Exception e) {
                /* parse failure: park BEFORE resetting (fix-1-r2 F4) — the old
                   path overwrote a custom palette with defaults, evidence lost.
                   A FAILED park leaves the file in place — mark degraded; the
                   persistTypes below then retries the park before any write
                   (fix-1-r5 #2) */
                if (!dev.workbench.registry.SidecarIO.parkCorrupt(typesFile, "maptypes", e))
                    typesDegraded = true;
            }
        }
        types = defaultTypes();
        persistTypes();
    }

    private void persistTypes() {
        /* atomic + loud via the shared sidecar discipline (map-r5 F15); a
           degraded load (transient read failure) parks the healthy on-disk
           palette as evidence before defaults+1 overwrite it (fix-1-r3 #2) */
        if (typesDegraded) {
            /* never overwrite un-parked evidence: a failed park keeps the flag
               and SKIPS the write (fix-1-r4 #5) */
            if (!dev.workbench.registry.SidecarIO.parkHealthyBeforeOverwrite(typesFile, "maptypes"))
                return;
            typesDegraded = false;
        }
        dev.workbench.registry.SidecarIO.writeAtomic(typesFile, Json.write(types), "maptypes");
    }

    public synchronized Map<String, Object> typeList() {
        Map<String, Object> out = new LinkedHashMap<>();
        types.forEach((k, v) -> {
            List<Object> rows = new ArrayList<>();
            for (Map<String, Object> t : v) rows.add(new LinkedHashMap<>(t));
            out.put(k, rows);
        });
        return out;
    }

    public synchronized Map<String, Object> typeAdd(String kind, String name, String color)
            throws OpException {
        List<Map<String, Object>> list = types.get(kind);
        if (list == null)
            throw OpException.syntax("unknown type section: " + kind,
                    "sections: " + types.keySet());
        if (name == null || name.isBlank())
            throw OpException.syntax("type name required", "");
        String n = name.trim().toLowerCase();
        for (Map<String, Object> t : list)
            if (n.equals(t.get("name"))) return new LinkedHashMap<>(t);   // idempotent
        Map<String, Object> t = type(n,
                color != null && color.matches("#[0-9a-fA-F]{3,8}") ? color : "#7d8794");
        t.put("custom", true);
        list.add(t);
        persistTypes();
        return new LinkedHashMap<>(t);
    }

    public synchronized Map<String, Object> typeSet(String kind, String name, String color,
            Boolean visible) throws OpException {
        List<Map<String, Object>> list = types.get(kind);
        if (list == null) throw OpException.syntax("unknown type section: " + kind, "");
        for (Map<String, Object> t : list)
            if (name.equals(t.get("name"))) {
                if (color != null) {
                    if (!color.matches("#[0-9a-fA-F]{3,8}"))
                        throw OpException.syntax("color must be a #hex literal", "");
                    t.put("color", color);
                }
                if (visible != null) t.put("visible", visible);
                persistTypes();
                return new LinkedHashMap<>(t);
            }
        throw OpException.notFound("type not found: " + kind + "/" + name, "use map.type.add");
    }

    private boolean knownType(String kind, String name) {
        List<Map<String, Object>> list = types.get(kind);
        if (list == null) return false;
        for (Map<String, Object> t : list) if (name.equals(t.get("name"))) return true;
        return false;
    }

    /** The names registered under one type kind, pipe-joined for error messages.
     *  Rendered live from the registry so it can never drift from what is valid. */
    private String vocabulary(String kind) {
        List<String> names = new ArrayList<>();
        for (Map<String, Object> t : types.getOrDefault(kind, List.of()))
            names.add(String.valueOf(t.get("name")));
        return String.join("|", names);
    }

    /** Both verdict layers, for the ops that accept either (map.setverdict). */
    private String verdictVocabulary() {
        return "node " + vocabulary("verdicts") + " · field " + String.join("|", FIELD_VERDICTS);
    }

    // ------------------------------------------------------------ load/save

    @SuppressWarnings("unchecked")
    private void loadMaps() {
        File[] files = dir.listFiles((d, n) -> n.endsWith(".map.json"));
        if (files == null) return;
        for (File f : files) {
            try {
                Object p = Json.parse(Files.readString(f.toPath()));
                if (p instanceof Map) {
                    Map<String, Object> m = new LinkedHashMap<>((Map<String, Object>) p);
                    /* a file whose internal id differs from its name (a hand
                       restore from a parked dir) persists to a SECOND file and
                       both reload forever, order-decided — log it (map-r8 i) */
                    String stem = f.getName().replaceFirst("\\.map\\.json$", "");
                    if (!stem.equals(String.valueOf(m.get("id"))))
                        System.err.println("map file/id mismatch: " + f.getName()
                                + " holds id '" + m.get("id") + "'");
                    maps.put(String.valueOf(m.get("id")), m);
                } else {
                    /* right file, wrong shape — park the evidence (fix-1-r3 #4:
                       this read was the last catch-all one in the tree). A FAILED
                       park leaves it in place — mark the filename degraded
                       (fix-1-r5 #2) */
                    if (!dev.workbench.registry.SidecarIO.parkCorrupt(f, "map",
                            new IllegalStateException("expected a JSON object")))
                        degradedMapFiles.add(f.getName());
                }
            } catch (java.io.IOException e) {
                /* transient read hiccup ≠ corruption (fix-1-r2 F2): the map drops
                   from map.list LOUDLY and the file name is remembered — a later
                   save of a recreated map with the same id parks the healthy
                   original before overwriting it (fix-1-r3 #1) */
                if (!dev.workbench.registry.SidecarIO.readFailedOrPark(f, "map", e))
                    degradedMapFiles.add(f.getName());
            } catch (Exception e) {
                /* was silently swallowed: a torn/corrupt file dropped the map
                   from map.list with no trace (opus map-r5 F15) — and the next
                   save made the loss permanent; park the evidence (fix-1-r3 #4).
                   A FAILED park leaves the file in place — mark the filename
                   degraded so a recreated map's persist retries the park
                   (fix-1-r5 #2) */
                if (!dev.workbench.registry.SidecarIO.parkCorrupt(f, "map", e))
                    degradedMapFiles.add(f.getName());
            }
        }
    }

    private void persist(Map<String, Object> map) {
        map.put("lastModified", Instant.now().toString());
        /* atomic + loud via the shared sidecar discipline — a plain writeString
           truncates in place, and a store re-read (project switch constructs a
           fresh store) racing the write silently dropped the map from map.list
           (opus map-r5 F15) */
        File f = new File(dir, map.get("id") + ".map.json");
        if (degradedMapFiles.contains(f.getName())) {
            /* never overwrite un-parked evidence: a failed park keeps the flag
               and SKIPS the write (fix-1-r4 #5) */
            if (!dev.workbench.registry.SidecarIO.parkHealthyBeforeOverwrite(f, "map"))
                return;
            degradedMapFiles.remove(f.getName());
        }
        dev.workbench.registry.SidecarIO.writeAtomic(f, Json.write(map), "map");
    }

    private Map<String, Object> require(String id) throws OpException {
        Map<String, Object> m = maps.get(id);
        if (m == null) throw OpException.notFound("map not found: " + id, "maps: " + maps.keySet());
        return m;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> listOf(Map<String, Object> m, String key) {
        return (List<Map<String, Object>>) m.computeIfAbsent(key, k -> new ArrayList<>());
    }

    // ------------------------------------------------------------ orientation ops

    public synchronized List<Object> list() {
        List<Object> out = new ArrayList<>();
        for (Map<String, Object> m : maps.values()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", m.get("id"));
            row.put("title", m.get("title"));
            row.put("build", m.get("build"));
            row.put("nodes", listOf(m, "nodes").size());
            row.put("edges", listOf(m, "edges").size());
            row.put("pending", pendingCount(m));
            out.add(row);
        }
        return out;
    }

    private static int pendingCount(Map<String, Object> m) {
        int n = 0;
        for (Map<String, Object> node : listOf(m, "nodes"))
            if (node.get("pending") != null) n++;
        return n;
    }

    /** map.summary — the orientation op: counts by kind/verdict, proven, gates, pending. */
    public synchronized Map<String, Object> summary(String id) throws OpException {
        Map<String, Object> m = require(id);
        Map<String, Integer> byKind = new LinkedHashMap<>();
        Map<String, Integer> byVerdict = new LinkedHashMap<>();
        int proven = 0;
        int maxLayer = 0;
        for (Map<String, Object> n : listOf(m, "nodes")) {
            byKind.merge(String.valueOf(n.getOrDefault("kind", "custom")), 1, Integer::sum);
            byVerdict.merge(String.valueOf(n.getOrDefault("verdict", "none")), 1, Integer::sum);
            if (Boolean.TRUE.equals(n.get("proven"))) proven++;
            maxLayer = Math.max(maxLayer, ((Number) n.getOrDefault("layer", 0)).intValue());
        }
        Map<String, Integer> edgesByKind = new LinkedHashMap<>();
        for (Map<String, Object> e : listOf(m, "edges"))
            edgesByKind.merge(String.valueOf(e.getOrDefault("kind", "call")), 1, Integer::sum);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", m.get("id"));
        out.put("title", m.get("title"));
        out.put("build", m.get("build"));
        out.put("layers", maxLayer + 1);
        out.put("byKind", byKind);
        out.put("byVerdict", byVerdict);
        out.put("edgesByKind", edgesByKind);
        out.put("nodes", listOf(m, "nodes").size());
        out.put("edges", listOf(m, "edges").size());
        out.put("fields", listOf(m, "fields").size());
        out.put("proven", proven);
        out.put("gates", listOf(m, "gates").size());
        out.put("pending", pendingCount(m));
        out.put("lastModified", m.get("lastModified"));
        return out;
    }

    /** map.outline — compact textual tree: one line per node, edges as indents. */
    public synchronized Map<String, Object> outline(String id) throws OpException {
        Map<String, Object> m = require(id);
        List<Map<String, Object>> nodes = new ArrayList<>(listOf(m, "nodes"));
        nodes.sort((a, b) -> {
            int la = ((Number) a.getOrDefault("layer", 0)).intValue();
            int lb = ((Number) b.getOrDefault("layer", 0)).intValue();
            return la != lb ? Integer.compare(la, lb)
                    : String.valueOf(a.get("id")).compareTo(String.valueOf(b.get("id")));
        });
        Map<String, List<String>> outs = new LinkedHashMap<>();
        for (Map<String, Object> e : listOf(m, "edges"))
            outs.computeIfAbsent(String.valueOf(e.get("from")), k -> new ArrayList<>())
                    .add(String.valueOf(e.get("to")) + (e.get("label") != null ? " (" + e.get("label") + ")" : ""));
        Map<String, Integer> boltFields = new LinkedHashMap<>();
        for (Map<String, Object> f : listOf(m, "fields"))
            if ("verbatim".equals(f.get("verdict")))
                boltFields.merge(String.valueOf(f.get("node")), 1, Integer::sum);
        List<Object> lines = new ArrayList<>();
        for (Map<String, Object> n : nodes) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < ((Number) n.getOrDefault("layer", 0)).intValue(); i++) sb.append("  ");
            sb.append(n.get("id")).append(" · ").append(n.getOrDefault("kind", "?"))
                    .append(" · ").append(n.getOrDefault("verdict", "none"))
                    .append(" · ").append(n.get("label"));
            if (n.get("target") != null) sb.append(" [").append(n.get("target")).append(']');
            if (Boolean.TRUE.equals(n.get("proven"))) sb.append(" ✓proven");
            if (boltFields.containsKey(String.valueOf(n.get("id"))))
                sb.append(" ⚡").append(boltFields.get(String.valueOf(n.get("id"))));
            if (n.get("pending") != null) sb.append(" ⏳pending");
            lines.add(sb.toString());
            for (String o : outs.getOrDefault(String.valueOf(n.get("id")), List.of())) {
                StringBuilder es = new StringBuilder();
                for (int i = 0; i < ((Number) n.getOrDefault("layer", 0)).intValue(); i++) es.append("  ");
                es.append("  ↳ ").append(o);
                lines.add(es.toString());
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", id);
        out.put("lines", lines);
        out.put("text", String.join("\n", lines.stream().map(String::valueOf).toList()));
        return out;
    }

    /** map.get — full map JSON, paged on nodes/edges. */
    public synchronized Map<String, Object> get(String id, int offset, int count) throws OpException {
        Map<String, Object> m = require(id);
        Map<String, Object> out = new LinkedHashMap<>(m);
        List<Map<String, Object>> nodes = listOf(m, "nodes");
        List<Map<String, Object>> edges = listOf(m, "edges");
        int cap = count <= 0 ? 1000 : count;
        out.put("nodes", nodes.subList(Math.min(offset, nodes.size()),
                Math.min(offset + cap, nodes.size())));
        out.put("edges", edges.subList(Math.min(offset, edges.size()),
                Math.min(offset + cap, edges.size())));
        out.put("truncated", offset + cap < nodes.size() || offset + cap < edges.size());
        return out;
    }

    /** map.node — full node detail: verdict (+pending), gates, fields+evidence, chain, symbol. */
    public synchronized Map<String, Object> node(String id, String nodeId) throws OpException {
        Map<String, Object> m = require(id);
        Map<String, Object> n = findNode(m, nodeId);
        Map<String, Object> out = new LinkedHashMap<>(n);
        List<Object> fields = new ArrayList<>();
        for (Map<String, Object> f : listOf(m, "fields"))
            if (nodeId.equals(f.get("node"))) fields.add(new LinkedHashMap<>(f));
        out.put("fields", fields);
        for (Map<String, Object> c : listOf(m, "chains"))
            if (nodeId.equals(c.get("node"))) out.put("chain", c.get("steps"));
        // gates attached to edges touching this node — several edges can share
        // one gate (e.g. a static + a dyn edge frontdoor→allowlist), so the
        // lookup below would emit the same gate twice; dedupe by id+position
        List<Object> gates = new ArrayList<>();
        Set<String> seenGates = new LinkedHashSet<>();
        for (Map<String, Object> e : listOf(m, "edges"))
            if (e.get("gate") != null && (nodeId.equals(e.get("from")) || nodeId.equals(e.get("to"))))
                for (Map<String, Object> g : listOf(m, "gates"))
                    if (e.get("gate").equals(g.get("id"))
                            && seenGates.add(g.get("id") + "|" + g.get("position")))
                        gates.add(new LinkedHashMap<>(g));
        out.put("gates", gates);
        return out;
    }

    private static Map<String, Object> findNode(Map<String, Object> m, String nodeId)
            throws OpException {
        for (Map<String, Object> n : listOf(m, "nodes"))
            if (nodeId.equals(n.get("id"))) return n;
        throw OpException.notFound("node not found: " + nodeId, "map=" + m.get("id"));
    }

    /** map.neighbors [depth=1] — BFS adjacency (undirected). */
    public synchronized Map<String, Object> neighbors(String id, String nodeId, int depth)
            throws OpException {
        Map<String, Object> m = require(id);
        findNode(m, nodeId);
        if (depth <= 0) depth = 1;
        Map<String, Map<String, Object>> byId = new LinkedHashMap<>();
        for (Map<String, Object> n : listOf(m, "nodes")) byId.put(String.valueOf(n.get("id")), n);
        Set<String> seen = new LinkedHashSet<>();
        Deque<String> q = new ArrayDeque<>();
        seen.add(nodeId);
        q.add(nodeId);
        List<Object> outNodes = new ArrayList<>();
        List<Object> outEdges = new ArrayList<>();
        int d = 0;
        while (!q.isEmpty() && d < depth) {
            int level = q.size();
            for (int i = 0; i < level; i++) {
                String cur = q.poll();
                for (Map<String, Object> e : listOf(m, "edges")) {
                    String other = null;
                    if (cur.equals(e.get("from"))) other = String.valueOf(e.get("to"));
                    else if (cur.equals(e.get("to"))) other = String.valueOf(e.get("from"));
                    if (other == null) continue;
                    outEdges.add(new LinkedHashMap<>(e));
                    if (seen.add(other)) {
                        q.add(other);
                        outNodes.add(new LinkedHashMap<>(byId.get(other)));
                    }
                }
            }
            d++;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("node", nodeId);
        out.put("depth", depth);
        out.put("nodes", outNodes);
        out.put("edges", outEdges);
        return out;
    }

    /** map.paths <from> <to> [max=5] — all simple paths (directed BFS), capped. */
    public synchronized List<Object> paths(String id, String from, String to, int max)
            throws OpException {
        Map<String, Object> m = require(id);
        findNode(m, from);
        findNode(m, to);
        Map<String, List<String>> adj = new LinkedHashMap<>();
        for (Map<String, Object> e : listOf(m, "edges"))
            adj.computeIfAbsent(String.valueOf(e.get("from")), k -> new ArrayList<>())
                    .add(String.valueOf(e.get("to")));
        int cap = max <= 0 ? 5 : Math.min(max, 50);
        List<Object> out = new ArrayList<>();
        Deque<List<String>> q = new ArrayDeque<>();
        q.add(List.of(from));
        while (!q.isEmpty() && out.size() < cap) {
            List<String> path = q.poll();
            String tail = path.get(path.size() - 1);
            if (tail.equals(to)) {
                out.add(new ArrayList<>(path));
                continue;
            }
            if (path.size() > 12) continue;                        // sanity cap on depth
            for (String next : adj.getOrDefault(tail, List.of()))
                if (!path.contains(next)) {
                    List<String> p = new ArrayList<>(path);
                    p.add(next);
                    q.add(p);
                }
        }
        return out;
    }

    /** map.find <query> — fuzzy over labels, symbols, sublabels, field names. */
    public synchronized List<Object> find(String id, String query) throws OpException {
        Map<String, Object> m = require(id);
        String q = query == null ? "" : query.toLowerCase();
        List<Object> out = new ArrayList<>();
        if (q.isEmpty()) return out;
        Set<String> fieldHit = new LinkedHashSet<>();
        for (Map<String, Object> f : listOf(m, "fields"))
            if (String.valueOf(f.getOrDefault("field", "")).toLowerCase().contains(q))
                fieldHit.add(String.valueOf(f.get("node")));
        for (Map<String, Object> n : listOf(m, "nodes")) {
            String hay = (String.valueOf(n.getOrDefault("label", "")) + " "
                    + String.valueOf(n.getOrDefault("sublabel", "")) + " "
                    + String.valueOf(n.getOrDefault("symbol", "")) + " "
                    + String.valueOf(n.getOrDefault("id", ""))).toLowerCase();
            if (hay.contains(q) || fieldHit.contains(String.valueOf(n.get("id"))))
                out.add(new LinkedHashMap<>(n));
        }
        return out;
    }

    /** map.diff — added/removed/changed (verdict/kind/label) nodes between two maps. */
    public synchronized Map<String, Object> diff(String idA, String idB) throws OpException {
        Map<String, Object> a = require(idA), b = require(idB);
        Map<String, Map<String, Object>> na = new LinkedHashMap<>(), nb = new LinkedHashMap<>();
        for (Map<String, Object> n : listOf(a, "nodes")) na.put(String.valueOf(n.get("id")), n);
        for (Map<String, Object> n : listOf(b, "nodes")) nb.put(String.valueOf(n.get("id")), n);
        List<Object> added = new ArrayList<>(), removed = new ArrayList<>(), changed = new ArrayList<>();
        for (String k : nb.keySet()) if (!na.containsKey(k)) added.add(k);
        for (String k : na.keySet()) {
            if (!nb.containsKey(k)) { removed.add(k); continue; }
            Map<String, Object> x = na.get(k), y = nb.get(k);
            List<String> deltas = new ArrayList<>();
            for (String f : List.of("verdict", "kind", "label", "layer"))
                if (!String.valueOf(x.get(f)).equals(String.valueOf(y.get(f))))
                    deltas.add(f + ":" + x.get(f) + "→" + y.get(f));
            if (!deltas.isEmpty()) {
                Map<String, Object> c = new LinkedHashMap<>();
                c.put("id", k);
                c.put("changes", deltas);
                changed.add(c);
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("a", idA);
        out.put("b", idB);
        out.put("added", added);
        out.put("removed", removed);
        out.put("changed", changed);
        return out;
    }

    // ------------------------------------------------------------ mutation ops

    /** The canonical map id slug (used by create() and every id lookup). */
    private static String slug(String title) {
        /* Locale.ROOT: a Turkish-locale default lowercases 'I'→'ı' and the id
           diverges from every other slugger in the tree (map-r6 #13) */
        return title == null ? ""
                : title.trim().toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
    }

    /** map.create <title> [build] — slug id, idempotent on existing title. */
    public synchronized Map<String, Object> create(String title, String build) throws OpException {
        if (title == null || title.isBlank()) throw OpException.syntax("map title required", "");
        String id = slug(title);
        /* a non-ASCII title slugs to "" and would persist as a ".map.json"
           dotfile — unopenable, and idempotently shadowing itself on retry
           (map-r6 #3). Same guard promoteFlow has. */
        if (id.isEmpty()) throw OpException.syntax("map title produces an empty map id", title);
        /* HONEST idempotence (map-lifecycle step 6): "FCM attack surface",
           "FCM: attack, surface" and "fcm-attack-surface" all slug to the same
           id, so the second create returns the FIRST map's summary. Silently,
           the caller believes it made a new map — and once map.delete exists
           in the GUI, deleting "my new map" destroys the original. */
        if (maps.containsKey(id)) {
            Map<String, Object> hit = summary(id);
            hit.put("existing", true);
            return hit;
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("schema", 1);
        m.put("id", id);
        m.put("title", title.trim());
        if (build != null) m.put("build", build);
        m.put("nodes", new ArrayList<>());
        m.put("edges", new ArrayList<>());
        m.put("fields", new ArrayList<>());
        m.put("chains", new ArrayList<>());
        m.put("gates", new ArrayList<>());
        maps.put(id, m);
        persist(m);
        Map<String, Object> fresh = summary(id);
        fresh.put("existing", false);        // explicit: absent-means-false is not a contract
        return fresh;
    }

    /** Validate + normalize a symbol ref: {addr, origSig?, entry?, line?}. Lenient on
     *  unresolved (maps span targets not yet open); resolved refs get origSig backfilled.
     *  {@code targetHint} (the node's own target field) resolves FIRST — in the
     *  two-builds-of-one-APK diff workflow the same class exists in both targets and
     *  insertion order used to pick the winner, backfilling origSig from the wrong
     *  build half the time (app-review-opus P1-7). The scan remains as fallback. */
    @SuppressWarnings("unchecked")
    private Map<String, Object> normalizeSymbol(Object symObj, Object targetHint) {
        if (symObj == null) return null;
        Map<String, Object> sym = new LinkedHashMap<>();
        if (symObj instanceof String s) {
            sym.put("addr", s);
        } else if (symObj instanceof Map) {
            sym.putAll((Map<String, Object>) symObj);
        }
        Object addr = sym.get("addr");
        if (addr == null && sym.get("origSig") != null) addr = sym.get("origSig");
        if (addr == null) return null;
        // backfill origSig via the node's own target when it resolves (best-effort)
        // — a BLANK hint must not short-circuit to the active target (fix-6 review F11)
        if (targetHint != null && !String.valueOf(targetHint).isBlank()) {
            try {
                Map<String, Object> r = ws.engineFor(String.valueOf(targetHint)).resolve(String.valueOf(addr));
                if (r != null) {
                    if (r.get("addr") != null) sym.put("addr", r.get("addr"));
                    if (r.get("origSig") != null) sym.put("origSig", r.get("origSig"));
                    sym.put("resolved", true);
                    return sym;
                }
            } catch (Exception ignored) {}
        }
        // fallback: any open target that resolves it
        for (String tid : ws.targetIds()) {
            try {
                Map<String, Object> r = ws.engineFor(tid).resolve(String.valueOf(addr));
                if (r != null) {
                    if (r.get("addr") != null) sym.put("addr", r.get("addr"));
                    if (r.get("origSig") != null) sym.put("origSig", r.get("origSig"));
                    sym.put("resolved", true);
                    break;
                }
            } catch (Exception ignored) {}
        }
        return sym;
    }

    /** map.addnode — upsert by node id (idempotent): kind from the registry, symbol validated. */
    @SuppressWarnings("unchecked")
    public synchronized Map<String, Object> addNode(String id, Map<String, Object> node)
            throws OpException {
        Map<String, Object> m = require(id);
        Object nid = node.get("id");
        if (nid == null || String.valueOf(nid).isBlank())
            throw OpException.syntax("node id required", "");
        List<Map<String, Object>> nodes = listOf(m, "nodes");
        Map<String, Object> existing = null;
        for (Map<String, Object> n : nodes)
            if (nid.equals(n.get("id"))) { existing = n; break; }
        /* kind omitted on an UPDATE keeps the existing kind — defaulting to
           "custom" silently reclassified a node on every label-only patch
           (fix-5 review F9) */
        String kind = node.get("kind") != null ? String.valueOf(node.get("kind"))
                : (existing != null && existing.get("kind") != null
                        ? String.valueOf(existing.get("kind")) : "custom");
        if (!knownType("nodeKinds", kind))
            throw OpException.syntax("unknown node kind: " + kind,
                    "registry: map.type.list / map.type.add");
        Object verdict = node.get("verdict");
        if (verdict != null && !knownType("verdicts", String.valueOf(verdict)))
            throw OpException.syntax("unknown verdict: " + verdict
                    + " — valid: " + vocabulary("verdicts"), "registry: map.type.list");
        Map<String, Object> sym = normalizeSymbol(node.get("symbol"), node.get("target"));
        Map<String, Object> n = existing != null ? existing : new LinkedHashMap<>();
        n.put("id", nid);
        n.put("kind", kind);
        if (node.get("label") != null) n.put("label", node.get("label"));
        if (node.get("sublabel") != null) n.put("sublabel", node.get("sublabel"));
        if (node.get("target") != null) n.put("target", node.get("target"));
        if (sym != null) n.put("symbol", sym);
        if (verdict != null) n.put("verdict", verdict);
        else n.putIfAbsent("verdict", "none");
        if (node.get("layer") != null) n.put("layer", ((Number) node.get("layer")).intValue());
        else n.putIfAbsent("layer", 0);
        if (node.get("tags") instanceof List) n.put("tags", new ArrayList<>((List<Object>) node.get("tags")));
        if (node.get("proven") != null) n.put("proven", node.get("proven"));
        if (existing == null) nodes.add(n);
        persist(m);
        return new LinkedHashMap<>(n);
    }

    public synchronized Map<String, Object> updateNode(String id, String nodeId,
            Map<String, Object> patch) throws OpException {
        patch.put("id", nodeId);
        return addNode(id, patch);                                   // upsert semantics
    }

    public synchronized Map<String, Object> rmNode(String id, String nodeId) throws OpException {
        Map<String, Object> m = require(id);
        findNode(m, nodeId);
        listOf(m, "nodes").removeIf(n -> nodeId.equals(n.get("id")));
        listOf(m, "edges").removeIf(e -> nodeId.equals(e.get("from")) || nodeId.equals(e.get("to")));
        listOf(m, "fields").removeIf(f -> nodeId.equals(f.get("node")));
        listOf(m, "chains").removeIf(c -> nodeId.equals(c.get("node")));
        persist(m);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("removed", nodeId);
        return out;
    }

    /** map.addedge — typed, idempotent on (from,to,kind,label). */
    public synchronized Map<String, Object> addEdge(String id, String from, String to, String kind,
            String label, String gate, String confidence) throws OpException {
        Map<String, Object> m = require(id);
        findNode(m, from);
        findNode(m, to);
        if (!knownType("edgeKinds", kind))
            throw OpException.syntax("unknown edge kind: " + kind, "registry: map.type.list / map.type.add");
        for (Map<String, Object> e : listOf(m, "edges"))
            if (from.equals(e.get("from")) && to.equals(e.get("to")) && kind.equals(e.get("kind"))
                    && java.util.Objects.equals(label, e.get("label")))
                return new LinkedHashMap<>(e);                       // idempotent
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("from", from);
        e.put("to", to);
        e.put("kind", kind);
        if (label != null) e.put("label", label);
        if (gate != null) e.put("gate", gate);
        if (confidence != null) e.put("confidence", confidence);
        listOf(m, "edges").add(e);
        persist(m);
        return new LinkedHashMap<>(e);
    }

    public synchronized Map<String, Object> rmEdge(String id, String from, String to, String kind)
            throws OpException {
        Map<String, Object> m = require(id);
        boolean removed = listOf(m, "edges").removeIf(e -> from.equals(e.get("from"))
                && to.equals(e.get("to")) && (kind == null || kind.equals(e.get("kind"))));
        if (!removed) throw OpException.notFound("edge not found: " + from + "→" + to, "map=" + id);
        persist(m);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("removed", from + "→" + to);
        return out;
    }

    /**
     * map.setverdict — node (or field) verdict + rationale. llm actor → PENDING
     * proposal (recorded on the node, not applied); human → applied and any
     * pending on that node/field is settled. {@code reject:true} drops a pending.
     */
    public synchronized Map<String, Object> setVerdict(String id, String nodeId, String verdict,
            String rationale, String field, String actor, boolean reject) throws OpException {
        Map<String, Object> m = require(id);
        Map<String, Object> n = findNode(m, nodeId);
        boolean llm = actor != null && "agent".equals(EventBus.actorKind(actor));

        if (reject) {
            n.remove("pending");
            persist(m);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("rejected", true);
            out.put("node", nodeId);
            return out;
        }
        if (!knownType("verdicts", verdict) && !FIELD_VERDICTS.contains(verdict))
            throw OpException.syntax("unknown verdict: " + verdict
                    + " — valid: " + verdictVocabulary(), "registry: map.type.list");

        if (llm) {
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("verdict", verdict);
            p.put("rationale", rationale == null ? "" : rationale);
            p.put("field", field);
            p.put("actor", actor);
            p.put("ts", Instant.now().toString());
            n.put("pending", p);
            persist(m);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("pending", true);
            out.put("status", "pending");
            out.put("node", nodeId);
            out.put("proposal", p);
            return out;
        }

        n.remove("pending");                                   // human settle clears the proposal
        if (field != null && !field.isBlank()) {
            Map<String, Object> f = null;
            for (Map<String, Object> x : listOf(m, "fields"))
                if (nodeId.equals(x.get("node")) && field.equals(x.get("field"))) { f = x; break; }
            if (f == null) {
                f = new LinkedHashMap<>();
                f.put("node", nodeId);
                f.put("field", field);
                listOf(m, "fields").add(f);
            }
            f.put("verdict", verdict);
            if (rationale != null) f.put("rationale", rationale);
        } else {
            n.put("verdict", verdict);
            if (rationale != null) n.put("rationale", rationale);
        }
        persist(m);
        Map<String, Object> out = new LinkedHashMap<>(n);
        out.put("applied", true);
        return out;
    }

    /** map.setchain — replace a node's trace chain. */
    @SuppressWarnings("unchecked")
    public synchronized Map<String, Object> setChain(String id, String nodeId, List<Object> steps)
            throws OpException {
        Map<String, Object> m = require(id);
        findNode(m, nodeId);
        List<Map<String, Object>> chains = listOf(m, "chains");
        Map<String, Object> chain = null;
        for (Map<String, Object> c : chains)
            if (nodeId.equals(c.get("node"))) { chain = c; break; }
        if (chain == null) {
            chain = new LinkedHashMap<>();
            chain.put("node", nodeId);
            chains.add(chain);
        }
        List<Object> norm = new ArrayList<>();
        /* a step without its own target inherits the NODE's target — chain steps
           in practice carry {symbol, note}, and resolving them by insertion order
           re-opens the wrong-build backfill this hint exists to close
           (fix-6 review F10) */
        Object nodeTarget = null;
        for (Map<String, Object> nn : listOf(m, "nodes"))
            if (nodeId.equals(nn.get("id"))) { nodeTarget = nn.get("target"); break; }
        if (steps != null)
            for (Object s : steps)
                if (s instanceof Map) {
                    Map<String, Object> step = new LinkedHashMap<>((Map<String, Object>) s);
                    if (step.get("symbol") != null)
                        step.put("symbol", normalizeSymbol(step.get("symbol"),
                                step.get("target") != null ? step.get("target") : nodeTarget));
                    norm.add(step);
                }
        chain.put("steps", norm);
        persist(m);
        return new LinkedHashMap<>(chain);
    }

    /** map.attachevidence — link a run/hit to a field (or the node itself) → proven. */
    @SuppressWarnings("unchecked")
    public synchronized Map<String, Object> attachEvidence(String id, String nodeId, String field,
            String run, String note) throws OpException {
        Map<String, Object> m = require(id);
        Map<String, Object> n = findNode(m, nodeId);
        Map<String, Object> ev = new LinkedHashMap<>();
        if (run != null) ev.put("run", run);
        if (note != null) ev.put("note", note);
        if (field != null && !field.isBlank()) {
            Map<String, Object> f = null;
            for (Map<String, Object> x : listOf(m, "fields"))
                if (nodeId.equals(x.get("node")) && field.equals(x.get("field"))) { f = x; break; }
            if (f == null) {
                f = new LinkedHashMap<>();
                f.put("node", nodeId);
                f.put("field", field);
                f.put("verdict", "unknown");
                listOf(m, "fields").add(f);
            }
            ((List<Object>) f.computeIfAbsent("evidence", k -> new ArrayList<>())).add(ev);
        } else {
            ((List<Object>) n.computeIfAbsent("evidence", k -> new ArrayList<>())).add(ev);
        }
        n.put("proven", true);                                     // evidence flips proven
        persist(m);
        return new LinkedHashMap<>(n);
    }

    /** flow.promote — seed a map from a saved flow (GRAPHS_SPEC §2: one node per
     *  hop + edges source→…→sink). Creates the map when absent; idempotent.
     *  The map id is canonicalized ONCE here (same slug as create()), so a
     *  mixed-case flow name can't orphan an empty map (M5 fix). */
    public synchronized Map<String, Object> promoteFlow(Map<String, Object> flow, String mapId,
            String actor) throws OpException {
        String id = slug(mapId != null && !mapId.isBlank() ? mapId
                : String.valueOf(flow.get("name")));
        if (id.isEmpty()) throw OpException.syntax("flow name produces an empty map id", "");
        if (!maps.containsKey(id)) create(String.valueOf(flow.get("name")), null);
        Map<String, Object> src = addNode(id, node("src-" + flow.get("id"), "source",
                String.valueOf(flow.get("sourceLabel")), null, 0));
        Object prev = src.get("id");
        @SuppressWarnings("unchecked")
        List<Object> hops = (List<Object>) flow.getOrDefault("hopsResolved", List.of());
        int layer = 1;
        for (Object h : hops) {
            if (!(h instanceof Map)) continue;
            Map<?, ?> hm = (Map<?, ?>) h;
            String hid = "hop-" + layer + "-" + flow.get("id");
            Map<String, Object> hn = node(hid, "handler", String.valueOf(hm.get("label")),
                    hm.get("addr") == null ? null : Map.of("addr", hm.get("addr")), layer);
            addNode(id, hn);
            addEdge(id, String.valueOf(prev), hid, "call", null, null, "static");
            prev = hid;
            layer++;
        }
        Map<String, Object> dst = addNode(id, node("dst-" + flow.get("id"), "sink",
                String.valueOf(flow.get("sinkLabel")), null, layer));
        addEdge(id, String.valueOf(prev), String.valueOf(dst.get("id")), "call", null, null, "static");
        Map<String, Object> out = summary(id);
        out.put("promoted", flow.get("id"));
        out.put("map", id);
        return out;
    }

    private static Map<String, Object> node(String id, String kind, String label,
            Map<String, Object> symbol, int layer) {
        Map<String, Object> n = new LinkedHashMap<>();
        n.put("id", id);
        n.put("kind", kind);
        n.put("label", label);
        n.put("layer", layer);
        if (symbol != null) n.put("symbol", symbol);
        return n;
    }

    /** map.export <fmt: json|md> — json: the map file; md: a markdown outline
     *  (the fcm_map render.py successor). */
    public synchronized Map<String, Object> export(String id, String fmt) throws OpException {
        Map<String, Object> m = require(id);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", id);
        out.put("fmt", fmt);
        if ("json".equals(fmt)) {
            out.put("text", Json.write(m));
            return out;
        }
        if ("md".equals(fmt)) {
            Map<String, Object> sum = summary(id);
            Map<String, Object> ol = outline(id);
            StringBuilder md = new StringBuilder();
            md.append("# ").append(m.get("title")).append("\n\n");
            md.append("- build: ").append(m.getOrDefault("build", "—")).append("\n");
            md.append("- nodes: ").append(sum.get("nodes")).append(" · edges: ").append(sum.get("edges"))
                    .append(" · proven: ").append(sum.get("proven")).append("\n\n");
            md.append("## Verdicts\n\n");
            @SuppressWarnings("unchecked")
            Map<String, Integer> bv = (Map<String, Integer>) sum.get("byVerdict");
            bv.forEach((k, v) -> md.append("- ").append(k).append(": ").append(v).append("\n"));
            md.append("\n## Outline\n\n```\n").append(ol.get("text")).append("\n```\n");
            out.put("text", md.toString());
            return out;
        }
        throw OpException.syntax("unknown export fmt: " + fmt, "json|md (svg is post-M6)");
    }

    /** True when no maps exist yet (the fcm seed condition). */
    public synchronized boolean isEmpty() { return maps.isEmpty(); }

    /** id presence — used by the Workspace's phantom-activeMap check on a store
        that is not yet published (its lock is uncontended there). */
    public synchronized boolean has(String id) { return maps.containsKey(id); }

    /** map.delete — drop the map from the store and PARK its file as
        {@code <id>.map.json.deleted-<ts>}. NOT an unlink: a map is hours of
        analyst work with no undo log anywhere, and the repo already parks
        rather than destroys (p1.migrated, maps.migrated, DeviceManager's
        .part/.tmp). loadMaps' *.map.json filter ignores the parked name, so
        the file is inert until a human moves it back — and a hand-restore
        whose stem and internal id disagree is already flagged loudly there.
        Returns the parked absolute path (the recovery story, handed to the
        caller), or null when the map was in memory with no file on disk. */
    public synchronized String delete(String id) throws OpException {
        Map<String, Object> m = require(id);                 // E_NOTFOUND
        maps.remove(id);
        File src = new File(dir, id + ".map.json");
        if (!src.isFile()) return null;
        /* ':' is legal on POSIX but hostile on every other filesystem and in
           shell one-liners — the recovery move is meant to be typed by hand */
        String ts = Instant.now().toString().replace(':', '-');
        File dst = new File(dir, id + ".map.json.deleted-" + ts);
        try {
            Files.move(src.toPath(), dst.toPath(), java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            return dst.getAbsolutePath();
        } catch (Exception e) {
            /* the file survived, so the STORE must too — dropping it here
               would resurrect the map on the next boot with no row in
               map.list until then */
            maps.put(id, m);
            throw OpException.io("could not park map file: " + src + " — " + e, "map: " + id);
        }
    }

    /** Seed hook: install a pre-built map (fcm fixture on first boot). */
    public synchronized void seed(Map<String, Object> map) {
        maps.put(String.valueOf(map.get("id")), map);
        persist(map);
    }
}
