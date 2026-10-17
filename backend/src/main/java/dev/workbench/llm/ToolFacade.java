package dev.workbench.llm;

import dev.workbench.registry.Dispatcher;
import dev.workbench.registry.OpException;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The tool facade: the ONE table of tools an LLM sees, whether it is the in-process
 * mission loop (the loop-visible subset, dispatched straight into the registry) or an
 * external MCP client (every tool, through the `tool.call` op and the bridge).
 *
 * The registry's ops stay what the browser needs (one op per request); the facade folds
 * them into a small catalogue of verb-shaped tools — 26 in all (decision 2026-09-18:
 * one server, everything in it, merged calls, the cruft removed):
 *
 *   reads        symbols, code, xrefs, strings, search
 *   writes       annotate (rename + comment + tag in one items[] call), map_verdict
 *   maps/flows   map, map_edit, flow
 *   dynamic      devices, runs, run, breakpoints
 *   operator     project, target, mission, review, llm, reversing_agent, index,
 *                workspace, history, presence
 *
 * A tool with several backing ops takes an `action` (or `view`/`direction`) and the
 * union of its ops' parameters; the routing table below maps (tool, action) to the op
 * and translates the few parameter names that differ. Scope, actor and review policy are
 * enforced by the ops themselves (the dispatcher runs AgentManager.checkScope per op), so
 * nothing about permissions lives here.
 *
 * Not on the surface, on purpose (approved removal list): agent.follow/unfollow/dismiss,
 * bookmark.*, device.browse/pull/annotate, map.export/open/close, runs.devices, tag.rm,
 * tag.family.add, tag.list, code.classinfo, rename.revert, and every Ops.UI_ONLY op.
 */
public final class ToolFacade {
    private ToolFacade() {}

    /** One tool: description (ends with a worked Example), strict schema parts, whether the
     *  mission loop may call it, and the registry ops it routes to (documentation only). */
    public record Tool(String name, String description, Map<String, Object> properties,
                       List<String> required, boolean loop, List<String> ops) {}

    private static final Map<String, Tool> TOOLS = new LinkedHashMap<>();

    /** Tools the mission loop advertises and dispatches (AgentLoop.toolDefs). */
    public static Set<String> loopToolNames() {
        Set<String> out = new java.util.LinkedHashSet<>();
        for (Tool t : TOOLS.values()) if (t.loop()) out.add(t.name());
        return Collections.unmodifiableSet(out);
    }

    public static Set<String> toolNames() { return Collections.unmodifiableSet(TOOLS.keySet()); }

    public static Tool get(String name) { return TOOLS.get(name); }

    /** The two tools whose success counts as a write for the loop's rename-early accounting. */
    public static boolean isWrite(String tool) { return "annotate".equals(tool) || "map_verdict".equals(tool); }

    // ------------------------------------------------------------------ schema helpers

    private static Map<String, Object> str(String description, String example) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("type", "string");
        p.put("description", description + " — e.g. " + example);
        return p;
    }
    private static Map<String, Object> num(String description, String example) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("type", "number");
        p.put("description", description + " — e.g. " + example);
        return p;
    }
    private static Map<String, Object> bool(String description, String example) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("type", "boolean");
        p.put("description", description + " — e.g. " + example);
        return p;
    }
    private static Map<String, Object> obj(String description, String example) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("type", "object");
        p.put("description", description + " — e.g. " + example);
        return p;
    }
    private static Map<String, Object> arr(String description, String example, Map<String, Object> item) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("type", "array");
        p.put("items", item);
        p.put("description", description + " — e.g. " + example);
        return p;
    }
    /** A string parameter restricted to listed values (the action/view selectors). */
    private static Map<String, Object> pick(String description, String... values) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("type", "string");
        p.put("enum", List.of(values));
        p.put("description", description);
        return p;
    }
    /** k, v, k, v … → ordered properties map (Map.of stops at ten entries). */
    private static Map<String, Object> props(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) m.put(String.valueOf(kv[i]), kv[i + 1]);
        return m;
    }
    private static void tool(String name, boolean loop, List<String> ops, String description, String example,
                             Map<String, Object> properties, String... required) {
        /* every tool takes `target` (optional): a client working several apks in one session names
           the one it means; absent, the bridge's pin or the workbench's active target applies */
        if (!properties.containsKey("target") && !"workspace".equals(name))
            properties.put("target", str("target id (optional; default: the target this session is pinned to, else the active one)", "\"t2\""));
        TOOLS.put(name, new Tool(name, description + " Example: " + example, properties, List.of(required), loop, ops));
    }

    /** A tag inside an annotate item: {name, note} — the note is what the TAGS panel shows beside
     *  the tag (m5 filed four tags without one: the old tag_add had a note slot, the first annotate
     *  schema had bare strings). A bare string is still accepted by the router. */
    private static Map<String, Object> tagItem() {
        Map<String, Object> t = new LinkedHashMap<>();
        t.put("type", "object");
        t.put("properties", props(
                "name", str("the tag as family:tag — the family groups it in the TAGS panel (feature:, entry:, role:, crypto: …); a bare word is refused", "\"entry:fcm\""),
                "note", str("why this tag, one line — shown beside it (optional but wanted)", "\"registered in the manifest as the FCM receiver\"")));
        t.put("required", List.of("name"));
        t.put("additionalProperties", false);
        return t;
    }

    private static final String LIMIT = "max rows (optional; absent = every row). With it the result is {rows, total, truncated}";
    private static final String LIMIT_PAGED = "page size (optional; the table pages, default 200) — offset pages further";

    // ------------------------------------------------------------------ the catalogue

    static {
        // ---- reads
        tool("symbols", true, List.of("symbol.tree", "symbol.search", "symbol.resolve"),
                "Find symbols. Give ONE of: prefix (what is under a package, one level. Sub-packages first — all of them, each {name,"
                        + " kind: package, prefix, count}; pass a row's prefix back to descend — then the classes in address order, one row"
                        + " each {name, kind, addr, fqcn}; no methods or fields (code {view: outline} gives a class's members; a class address"
                        + " as prefix answers that one class with them). Answers {items, total, truncated}. limit cuts the classes only"
                        + " (absent: the first 500); a cut answer carries total, beginnings — how many classes continue with each next"
                        + " character — and a hint. To walk a flat package of thousands, narrow by the beginning of the class name,"
                        + " case-sensitive: \"X.0A\" lists what starts with 0A under X and says so (under, startingWith); if 0A is itself a"
                        + " sub-package you get that package — lengthen the beginning. kind does not apply. Nothing there: E_NOTFOUND with"
                        + " the nearest existing level's packages as candidates. Absent = the top level),"
                        + " query (classes, methods and fields whose name contains it — ranked: the exact name first, then the same name"
                        + " in another case, then a prefix, then a substring; add kind to keep classes, methods or fields only — obfuscated"
                        + " names recycle across kinds), symbol (resolve one symbol to addr/fqcn/kind; `LOuter$Inner;` resolves through"
                        + " the dex inner-class records even when the inner class was renamed on its own, and a miss lists the outer's"
                        + " inner classes as candidates). query answers every row unless you pass limit — your cut, never the tool's;"
                        + " with limit its result is {rows, total, truncated}.",
                "{\"query\": \"onMessage\", \"kind\": \"method\", \"limit\": 50}",
                props("prefix", str("package to list under, dotted or Dalvik (optional)", "\"com.example.net\" or \"Lcom/example/net/\""),
                        "query", str("name substring to look for (optional)", "\"onMessage\""),
                        "kind", pick("class | method | field — keep one kind (query; optional)", "class", "method", "field"),
                        "symbol", str("symbol to resolve: class or method signature (optional)", "\"Lm76/ak;\""),
                        "limit", num("max rows (optional; absent = every row for query, the first 500 classes for prefix)", "200")));
        tool("code", true, List.of("code.decompile", "code.smali", "code.outline", "evidence.packet", "code.params", "locals.list"),
                "Read code. view java (default) decompiles a method — that method ONLY, cheap — or a whole class;"
                        + " view smali is the disassembly under it (the ground truth); view outline lists a class's methods and fields"
                        + " with signatures; view packet is an evidence packet for ONE method (a class is refused): original descriptor,"
                        + " the code in the dex's ORIGINAL names with stable refs (descriptor+offset), the calls/fields/types/strings touched,"
                        + " analyst names kept apart as provenance, omissions declared; entry (an offset \"0040\" or a block \"b0040\") selects"
                        + " the CFG blocks reachable from there, never a text cut. window \"from,to\" slices lines of a java result."
                        + " view params is a method's retained parameter metadata alone: the dex's MethodParameters annotation (names"
                        + " and flags verbatim, in encoded order, its count checked against the prototype), the debug_info names as a"
                        + " second source, the arguments as the decompiler currently names them and why — a method's java/smali result"
                        + " carries the RAW block as methodParameters (annotation and debug_info only; the decompiler's names, their"
                        + " sources and whether JEB applied the list by position are computed by view params). The annotation's order is"
                        + " NOT a verified mapping to the arguments (argumentMapping: unverified; WhatsApp keeps permuted and stale lists);"
                        + " only the analyst maps names onto arguments — an argument name you give through annotate {local} that equals a"
                        + " retained entry is recorded as such, and an argument the analyst mapped is not yours to rename."
                        + " view locals lists every decompiler local/argument, including collapsed method bodies, with its current/original name, stable originalSymbol and formal parameterIndex when applicable; namedBy/nameProvenance identify prior assignments;"
                        + " use it for exhaustive naming, then annotate {symbol: original method, local: current local name, name: new name}."
                        + " Results carry agentNames[]: current analyst names paired with their originals (was/originalName/originalSymbol).",
                "{\"symbol\": \"Lm76/ak;->a()V\"}",
                props("symbol", str("method or class", "\"Lm76/ak;->a()V\" (that method only) or \"Lm76/ak;\" (the whole class)"),
                        "view", pick("java (default) | smali | outline | packet | params | locals | site (one call site's bounded context: pass an xref's `from` as the symbol, or `at`)", "java", "smali", "outline", "packet", "params", "locals", "site"),
                        "at", str("site: the instruction offset when the symbol does not carry it (hex: +634h)", "\"+634h\""),
                        "context", num("site: DEX instructions each side (default 6)", "6"),
                        "lines", num("site: decompiled lines each side (default 8)", "8"),
                        "inherited", bool("outline: add the superclass chain's fields (in-dex supers; framework supers listed as unknown)", "true"),
                        "window", str("1-based line slice \"from,to\" of a java result (optional)", "\"1,80\""),
                        "question", str("view packet: the research question the packet answers (optional, recorded)", "\"what does the tag-7 branch schedule?\""),
                        "entry", str("view packet: a branch entry — an instruction offset or a CFG block id (optional; absent = the whole method)", "\"0040\""),
                        "maxInstructions", num("view packet: cap on listed instructions (default 400; the cut is declared)", "400")),
                "symbol");
        Map<String,Object> coverageItem = new LinkedHashMap<>();
        coverageItem.put("type", "object");
        coverageItem.put("properties", props("identity", str("exact variable identity returned by naming_coverage", "Lm76/ak;->a(I)V#parameter:0"),
                "disposition", pick("renamed | retained with evidence | unresolved", "renamed", "retained", "unresolved"),
                "evidence", str("specific uses supporting the name, or concrete missing evidence", "used as the upper bound of the copy loop")));
        coverageItem.put("required", List.of("identity", "disposition", "evidence"));
        coverageItem.put("additionalProperties", false);
        tool("naming_coverage", true, List.of("naming.coverage", "naming.coverage.record"),
                "Audit every formal argument and body local of ONE method, including methods whose class/members were already named."
                        + " get returns the fresh inventory, current/original identities, name provenance, inventoryFingerprint, and durable coverage."
                        + " After research and annotate batches, get again, then record each variable as renamed, retained with evidence, or unresolved."
                        + " record requires the fresh fingerprint, method evidence and items; partial lists merge for this actor and fingerprint."
                        + " An empty items list explicitly reviews a method with no variables. Missing variables, failed inventory reads and unresolved entries prevent completion."
                        + " Names/inventory changing makes prior coverage stale; reread and reassess. Records survive transcript truncation and restart."
                        + " renamed requires actual rename provenance. Unnamed/non-rendered arguments must remain unresolved. This tool records evidence; it never renames anything.",
                "{\"action\":\"get\",\"symbol\":\"Lm76/ak;->a(I)V\"}",
                props("action",pick("get (default) | record", "get", "record"),
                        "symbol",str("original method identity", "Lm76/ak;->a(I)V"),
                        "inventoryFingerprint",str("record: exact fingerprint from the latest get", "sha256 from get"),
                        "evidence",str("record: method-level code evidence; also required for an empty method", "returns the received message unchanged"),
                        "items",arr("record: variable assessments; partial lists merge, with no item cap", "[{identity, disposition, evidence}]",coverageItem),
                        "recordedBy",str("get: inspect a particular actor's record (optional; default latest)", "llm:agent-9")), "symbol");
        tool("xrefs", true, List.of("xref.to", "xref.from", "callers", "callees", "impls"),
                "Cross-references of a symbol. direction to (default): who uses it; from: what it uses; impls: the implementations"
                        + " of an interface or abstract METHOD (a method symbol only — for a class, read its outline and follow xrefs to)."
                        + " kind call keeps only call sites (callers / callees) — a caller row carries `from`, the exact call-site address"
                        + " Lcls;->m(...)+NNh that a comment can be placed on (and `sites` when the caller calls it more than once);"
                        + " all (default) also keeps field and class references."
                        + " Every row unless you pass limit; then {rows, total, truncated}.",
                "{\"symbol\": \"Lm76/ak;->a()V\", \"direction\": \"to\", \"kind\": \"call\", \"limit\": 200}",
                props("symbol", str("method, field or class symbol", "\"Lm76/ak;->a()V\""),
                        "direction", pick("to (default) | from | impls (method symbols only)", "to", "from", "impls"),
                        "kind", pick("all (default) | call", "all", "call"),
                        "limit", num(LIMIT, "200")),
                "symbol");
        tool("strings", true, List.of("strings", "string.xrefs"),
                "The string table, or the code that uses one string. Without id: the strings (filter = substring, type = kind csv"
                        + " such as url,crypto), each row with a numeric id. With id: every method that references that string —"
                        + " read it with code {symbol: method}.",
                "{\"filter\": \"http\", \"limit\": 100}",
                props("filter", str("substring filter (optional)", "\"http\""),
                        "type", str("string type filter csv (optional)", "\"url,crypto\""),
                        "id", str("a string id from an earlier strings result: return its code references instead (optional)", "\"1234\""),
                        "limit", num(LIMIT_PAGED, "100"),
                        "offset", num("paging offset (optional)", "200")));
        Map<String, Object> anchorItem = new LinkedHashMap<>();
        anchorItem.put("type", "object");
        anchorItem.put("properties", props("kind", pick("string (a diagnostic/table text) | api (callers of a method) | class (a class name) | name (a method name) | prototype (marks candidates by parameter list)", "string", "api", "class", "name", "prototype"),
                "value", str("the anchor's text", "\"fmessage-protobuf-richresponse-invalid-sender\"")));
        anchorItem.put("required", List.of("value"));
        anchorItem.put("additionalProperties", false);
        Map<String, Object> anchorsProp = new LinkedHashMap<>();
        anchorsProp.put("type", "array"); anchorsProp.put("items", anchorItem);
        anchorsProp.put("description", "cross-build lookup by several fallible anchors (instead of query): ranked candidates with the anchors each matched and missed, status located | ambiguous | partial | not_located (never \"absent\")");
        tool("search", true, List.of("search", "symbol.locate"),
                "Full-text search over decompiled code, strings and comments. kind narrows the hit classes (default: all three);"
                        + " query returns {groups, coverage}; partialCoverage means zero matches do not establish absence."
                        + " limit caps the rows. With `anchors` instead of `query`: locate a method across builds by several fallible anchors"
                        + " (a diagnostic string, a table name, an API it calls, a class/method name, a prototype shape) — ranked candidates"
                        + " with matched/unmatched anchors and an honest status (not_located = not found by these queries, not absent).",
                "{\"query\": \"cipher\", \"kind\": \"code\", \"limit\": 50}",
                props("query", str("text to search for (or pass anchors)", "\"cipher\""),
                        "kind", str("hit kinds csv (optional): code|strings|comments", "\"code\""),
                        "limit", num("max rows (optional)", "50"),
                        "anchors", anchorsProp));

        // ---- writes
        Map<String, Object> annotateItem = new LinkedHashMap<>();
        annotateItem.put("type", "object");
        annotateItem.put("properties", props(
                "symbol", str("method, class or field (must resolve) — a field addr is Lpkg/C;->f:Ljava/lang/String;", "\"Lm76/ak;->a()V\""),
                "name", str("new Java identifier, descriptive of the purpose (optional)", "\"handleLifecycleEvent\""),
                "expectedName", str("optional current name you read: refuse without changing anything if it has changed", "\"a\""),
                "local", str("rename a LOCAL variable (or a formal argument the analyst has not mapped) instead: its current name inside the method that symbol names; name is the new one (optional)", "\"v1_5\""),
                "comment", str("what the code does, in one or two lines (optional)", "\"dispatches lifecycle events to the observers\""),
                "tags", arr("tags to add (optional), each family:tag with the one-line note the human reads beside it",
                        "[{\"name\": \"entry:fcm\", \"note\": \"registered in the manifest as the FCM receiver\"}]", tagItem()),
                "rationale", str("one line of evidence (optional)", "\"calls Lifecycle.handleEvent with the state enum\""),
                "confidence", num("0–1 (optional)", "0.8")));
        annotateItem.put("required", List.of("symbol"));
        annotateItem.put("additionalProperties", false);
        Map<String, Object> annotateItems = new LinkedHashMap<>();
        annotateItems.put("type", "array");
        annotateItems.put("minItems", 1);
        annotateItems.put("maxItems", 50);
        annotateItems.put("items", annotateItem);
        annotateItems.put("description", "one object per symbol (1–50), each with any of name, comment, tags");
        tool("annotate", true, List.of("rename", "comment", "tag.add"),
                "Name, comment or tag symbols — file a whole class's names in batches of up to 50 items. Each item names a symbol and gives any of:"
                        + " name (rename it), comment, tags; with local, name renames that local variable or parameter of the method. Every write applies at once and appears in every later result; the human"
                        + " can revert it. Give a confidence and one line of evidence per item; rename again when you learn more."
                        + " Returns {applied, pending, unchanged, failed, results[]}; unchanged means the name was already assigned and no rename was recorded; a failed write carries its reason (a taken name, a symbol"
                        + " a human named, outside your write-scope). Another agent's unsaved name is protected: RENAME_CONFLICT"
                        + " names its currentName and currentActor; use that name and continue reading. expectedName refuses a stale"
                        + " rename even after a save. A taken-name refusal includes conflicts[] with the other symbols'"
                        + " originalSymbol, currentName and addr; inspect those identities and choose a distinct name."
                        + " Failed writes keep error text and structured errorDetails.",
                "{\"items\": [{\"symbol\": \"Lm76/ak;->a()V\", \"name\": \"handleLifecycleEvent\", \"comment\": \"forwards the state enum to Lifecycle.handleEvent\", \"tags\": [{\"name\": \"entry:lifecycle\", \"note\": \"the Activity's onResume is its only caller\"}], \"confidence\": 0.8}]}",
                props("items", annotateItems),
                "items");
        tool("map_verdict", true, List.of("map.setverdict"),
                "Set a verdict on an attack-surface map node (or one of its fields). Applied at once; the human can revert it.",
                "{\"id\": \"fcm-attack-surface\", \"node\": \"frontdoor\", \"verdict\": \"authored\", \"rationale\": \"demux logic\"}",
                props("id", str("map id", "\"fcm-attack-surface\""),
                        "node", str("node id", "\"frontdoor\""),
                        "verdict", str("node verdict authored|mixed|identity|gap|dead|none, or field verdict verbatim|constrained|server_regenerated|unknown", "\"authored\""),
                        "field", str("field name for a field verdict (optional)", "\"payload\""),
                        "rationale", str("evidence for the verdict (optional)", "\"demux logic\"")),
                "id", "node", "verdict");

        // ---- maps and flows
        tool("map", true, List.of("map.list", "map.summary", "map.outline", "map.node", "map.get", "map.find", "map.neighbors", "map.paths"),
                "Read attack-surface maps. action list: the maps of this project; summary: counts by kind and verdict; outline: layers,"
                        + " nodes and verdict marks as text (orientation in one call); node: one node in full; full: nodes and edges"
                        + " (offset/limit page them); find: nodes matching query; neighbors: nodes within depth hops of node;"
                        + " paths: paths from one node to another (max caps them).",
                "{\"action\": \"outline\", \"id\": \"fcm-attack-surface\"}",
                props("action", pick("list | summary | outline | node | full | find | neighbors | paths",
                                "list", "summary", "outline", "node", "full", "find", "neighbors", "paths"),
                        "id", str("map id from action list (every action but list)", "\"fcm-attack-surface\""),
                        "node", str("node id (node, neighbors)", "\"frontdoor\""),
                        "query", str("text to match nodes against (find)", "\"cipher\""),
                        "from", str("start node (paths)", "\"frontdoor\""),
                        "to", str("end node (paths)", "\"exec\""),
                        "depth", num("hops from node (neighbors; optional, default 1)", "2"),
                        "max", num("max paths (paths; optional)", "10"),
                        "offset", num("paging offset (full; optional)", "0"),
                        "limit", num("page size (full; optional)", "200")),
                "action");
        tool("map_edit", false, List.of("map.create", "map.delete", "map.addnode", "map.updatenode", "map.rmnode", "map.addedge",
                        "map.rmedge", "map.setchain", "map.attachevidence", "map.type.add", "map.type.list", "map.type.set"),
                "Edit attack-surface maps. action create {title, build?}; delete {id, confirm:true}; add_node {id, fields};"
                        + " update_node {id, node, fields}; remove_node {id, node}; add_edge / remove_edge {id, from, to, kind?, label?,"
                        + " gate?, confidence?}; set_chain {id, node, steps[]}; attach_evidence {id, node, field?, run?, note?};"
                        + " type_list; type_add / type_set {kind, name, color?, visible?}.",
                "{\"action\": \"add_edge\", \"id\": \"fcm-attack-surface\", \"from\": \"frontdoor\", \"to\": \"exec\", \"kind\": \"calls\"}",
                props("action", pick("create | delete | add_node | update_node | remove_node | add_edge | remove_edge | set_chain | attach_evidence | type_list | type_add | type_set",
                                "create", "delete", "add_node", "update_node", "remove_node", "add_edge", "remove_edge", "set_chain",
                                "attach_evidence", "type_list", "type_add", "type_set"),
                        "id", str("map id", "\"fcm-attack-surface\""),
                        "title", str("map title (create)", "\"FCM attack surface\""),
                        "build", bool("build the map from the target's components after creating it (create; optional)", "true"),
                        "confirm", bool("required true to delete", "true"),
                        "node", str("node id", "\"frontdoor\""),
                        "fields", obj("node fields to set (add_node: the whole node incl. id, kind, label; update_node: the patch)", "{\"id\": \"frontdoor\", \"kind\": \"receiver\", \"label\": \"FCM front door\"}"),
                        "from", str("edge source node", "\"frontdoor\""),
                        "to", str("edge target node", "\"exec\""),
                        "kind", str("edge kind (edges) or type kind (types)", "\"calls\""),
                        "label", str("edge label (optional)", "\"onMessage → dispatch\""),
                        "gate", str("edge gate (optional)", "\"signature check\""),
                        "confidence", num("edge confidence 0–1 (optional)", "0.7"),
                        "steps", arr("chain steps (set_chain)", "[{\"symbol\": \"Lm76/ak;->a()V\", \"note\": \"parses the payload\"}]", obj("one step", "{\"symbol\": \"…\"}")),
                        "field", str("node field (attach_evidence; optional)", "\"payload\""),
                        "run", str("run id as evidence (attach_evidence; optional)", "\"ex28\""),
                        "note", str("free-text evidence (attach_evidence; optional)", "\"hit 12 times in run ex28\""),
                        "name", str("type name (type_add, type_set)", "\"receiver\""),
                        "color", str("type color (optional)", "\"#c33\""),
                        "visible", bool("type visibility (type_set; optional)", "false")),
                "action");
        tool("flow", false, List.of("flow.list", "flow.get", "flow.add", "flow.rm", "flow.promote"),
                "Source-to-sink flows of a target. action list; get {id}; add {name, source, sink, hops?}; remove {id};"
                        + " promote {id, map} (turn the flow into map nodes and edges).",
                "{\"action\": \"add\", \"name\": \"intent → exec\", \"source\": \"Lm76/ak;->a()V\", \"sink\": \"Ljava/lang/Runtime;->exec\"}",
                props("action", pick("list | get | add | remove | promote", "list", "get", "add", "remove", "promote"),
                        "id", str("flow id (get, remove, promote)", "\"f3\""),
                        "name", str("flow name (add)", "\"intent → exec\""),
                        "source", str("source symbol (add)", "\"Lm76/ak;->a()V\""),
                        "sink", str("sink symbol (add)", "\"Ljava/lang/Runtime;->exec\""),
                        "hops", str("intermediate symbols csv (add; optional)", "\"Lm76/b;->c()V\""),
                        "map", str("map id to promote into (promote)", "\"fcm-attack-surface\""),
                        "target", str("target id (optional; default the active target)", "\"t1\"")),
                "action");

        // ---- dynamic
        tool("devices", false, List.of("device.list", "device.components", "device.services", "device.proc", "device.catalog"),
                "Live devices over adb. action list {probe?}; components {serial, pkg}: the services, receivers, providers and"
                        + " activities a package declares; services {serial}; processes {serial, pid}; catalog {serial, refresh?, kind?,"
                        + " filter?, offset?, limit?}: the device's installed packages and components.",
                "{\"action\": \"components\", \"serial\": \"emulator-5554\", \"pkg\": \"com.android.phone\"}",
                props("action", pick("list | components | services | processes | catalog", "list", "components", "services", "processes", "catalog"),
                        "serial", str("device serial as adb reports it", "\"emulator-5554\""),
                        "pkg", str("package name (components)", "\"com.android.phone\""),
                        "pid", str("process id (processes)", "\"1234\""),
                        "probe", bool("probe each device's state (list; optional)", "true"),
                        "refresh", bool("re-read the device instead of the cached catalog (catalog; optional)", "true"),
                        "kind", str("catalog kind filter (optional)", "\"service\""),
                        "filter", str("catalog substring filter (optional)", "\"phone\""),
                        "offset", num("paging offset (catalog; optional)", "0"),
                        "limit", num("page size (catalog; optional)", "100")),
                "action");
        tool("runs", false, List.of("runs.runs", "runs.hits", "runs.coverage", "runs.diff", "runs.rm", "runs.import", "runs.export"),
                "Recorded runs (breakpoint hit logs) of a target. action list; hits {run, method?, offset?, limit?}; coverage {run,"
                        + " class?}; diff {a, b}; remove {run} — one run by id or every ENDED run with \"all\" (the hit log is parked"
                        + " beside the store, never unlinked; a run with a live session is refused). Humans only for remove. "
                        + "Import {directory,target} validates and freezes a canonical local/external bundle without executing hooks; "
                        + "export {run,destination} copies its selected canonical bytes to a new directory, never uploads. Fixture imports require a mock target.",
                "{\"action\": \"hits\", \"run\": \"ex28\", \"limit\": 50}",
                props("action", pick("list | hits | coverage | diff | remove | import | export", "list", "hits", "coverage", "diff", "remove", "import", "export"),
                        "run", str("run id from action list, or \"all\" for remove", "\"ex28\""),
                        "directory", str("selected canonical bundle directory for import", "\"/absolute/run/bundle\""),
                        "destination", str("new absolute directory for canonical export", "\"/absolute/selected-export\""),
                        "method", str("method filter (hits; optional)", "\"Lm76/ak;->a()V\""),
                        "offset", num("paging offset (hits; optional)", "0"),
                        "limit", num("page size (hits; optional)", "50"),
                        "class", str("class filter (coverage; optional)", "\"Lm76/ak;\""),
                        "a", str("first run id (diff)", "\"ex27\""),
                        "b", str("second run id (diff)", "\"ex28\""),
                        "target", str("target id (optional; default the active target)", "\"t1\"")),
                "action");
        tool("run", false, List.of("runs.spawn", "runs.attach", "runs.detach"),
                "Explicit local instrumentation. Attach requires device, pkg, target, installed serverPath and ownershipConfirmed. "
                        + "The measured client/server versions must match; foreign controllers are never adopted. "
                        + "Keep the app foreground for attach. Spawn is explicit; no force-stop/broadcast/replay. "
                        + "NOT HIBERNATION-SAFE — laptop must remain awake and connected. Detach stops only owned resources.",
                "{\"action\":\"attach\",\"device\":\"selected-serial\",\"pkg\":\"com.example.app\",\"target\":\"t1\",\"serverPath\":\"/data/local/tmp/frida-server\",\"ownershipConfirmed\":true}",
                props("action", pick("spawn | attach | detach", "spawn", "attach", "detach"),
                        "device", str("explicit selected device serial", "\"selected-serial\""),
                        "pkg", str("package name", "\"com.example.app\""),
                        "serverPath", str("already installed matching server; never pushed by Jeeves", "\"/data/local/tmp/frida-server\""),
                        "serverPort", num("unused device loopback port (default 27183)", "27183"),
                        "ownershipConfirmed", bool("explicit declaration: no independent controller owns this device; otherwise import its bundle", "true"),
                        "session", str("session id (detach)", "\"s2\""),
                        "target", str("required exact open target id for capture", "\"t1\"")),
                "action");
        tool("breakpoints", false, List.of("runs.bp.add", "runs.bp.rm", "runs.bp.toggle", "runs.bp.list"),
                "Breakpoints of a target. action list; add {symbol, cond?} for a dex method, or add {module, export | offset | function,"
                        + " so?, cond?} for native code; remove {id}; toggle {id}.",
                "{\"action\": \"add\", \"symbol\": \"Lm76/ak;->a()V\"}",
                props("action", pick("list | add | remove | toggle", "list", "add", "remove", "toggle"),
                        "id", str("breakpoint id (remove, toggle)", "\"bp4\""),
                        "symbol", str("dex method to break on (add)", "\"Lm76/ak;->a()V\""),
                        "cond", str("hit condition (add; optional)", "\"args[0] != null\""),
                        "module", str("native module name (add, native)", "\"libfoo.so\""),
                        "export", str("exported function (add, native; optional)", "\"decrypt\""),
                        "offset", str("offset in the module (add, native; optional)", "\"0x1234\""),
                        "function", str("function name (add, native; optional)", "\"sub_1234\""),
                        "so", str("shared object path (add, native; optional)", "\"/data/app/…/libfoo.so\""),
                        "target", str("target id (optional; default the active target)", "\"t1\"")),
                "action");

        // ---- operator
        tool("project", false, List.of("project.list", "project.known", "project.open", "project.switch", "project.close",
                        "project.forget", "project.delete", "project.targets"),
                "Projects. action list: open projects; known: every project on disk; open {name}; switch {name}; close {name};"
                        + " targets {name}; forget {name, confirm:true} (drop from the known list); delete {name, confirm:true}"
                        + " (remove its store from disk).",
                "{\"action\": \"open\", \"name\": \"WhatsApp\"}",
                props("action", pick("list | known | open | switch | close | targets | forget | delete",
                                "list", "known", "open", "switch", "close", "targets", "forget", "delete"),
                        "name", str("project name", "\"WhatsApp\""),
                        "confirm", bool("required true for forget and delete", "true")),
                "action");
        tool("target", false, List.of("target.list", "target.open", "target.close", "target.save", "target.saveStatus",
                        "target.switch", "target.remove", "target.info", "target.changes"),
                "Targets (analysed binaries) of the active project. action list; open {path, engine?}; info; changes (unsaved edits);"
                        + " save; save_status; switch; close {save?}; remove (drop it from the project)."
                        + " save and close answer AT ONCE and write in the background (a 2 GB jdb2 takes minutes, longer than a tool call may"
                        + " wait): save returns {started, startedAt, pollTool}; call pollTool (target {action:save_status,target}) until saving is false and read last {ok, at, ms, bytes,"
                        + " error?} with at >= startedAt — that is the outcome. close returns {closing, closed, activeTarget, targets}; the target"
                        + " is out of the list at once, and workspace lists its jdb2 under `closing` until a dirty target's write is done"
                        + " (a close-save that fails is not a loss: the edits replay from history on the next open)."
                        + " close exposes closeCapabilities: cache cleanup limitations, not proof of heap reclamation."
                        + " target names the target for every action but list and open; absent = the active one."
                        + " open, switch and close answer with the target now active ({id, name, state, …}); through the MCP bridge"
                        + " that target becomes the one your later calls go to unless a call names its own target.",
                "{\"action\": \"save\", \"target\": \"t1\"}",
                props("action", pick("list | open | info | changes | save | save_status | switch | close | remove",
                                "list", "open", "info", "changes", "save", "save_status", "switch", "close", "remove"),
                        "target", str("target id (optional; default the active target)", "\"t1\""),
                        "path", str("file to open (open)", "\"/Users/me/app.apk\""),
                        "engine", str("engine id (open; optional)", "\"jeb\""),
                        "save", bool("save before closing (close; optional)", "true"),
                        "async", bool("save, close: false waits for the write and answers its result — only for a client whose calls may take minutes (default true)", "false"),
                        "project", str("project name (remove; optional)", "\"WhatsApp\"")),
                "action");
        tool("mission", false, List.of("agent.spawn", "agent.stop", "agent.pause", "agent.resume", "agent.revert", "agent.missions",
                        "agent.turns", "agent.turn", "agent.activity", "agent.setbudget", "agent.setturns", "agent.setscope"),
                "Agent missions. action spawn {goal, model?, depth?, scope?, target?, budget?, turns?, effort?, mode?} — model is"
                        + " provider:model (llm providers lists them), or set depth quick|deep to use the configured reversing agent;"
                        + " list; stop {id}; pause {id}; resume {id, budget?, turns?}; revert {id}: undo every rename and comment the"
                        + " mission wrote (refused while it runs; undone entries can be redone from history); turns {id}; turn {id, turn}:"
                        + " one turn's full transcript; activity {agent?, kinds?, offset?, count?}: the tail of the activity stream;"
                        + " set_budget {id, budget}; set_turns {id, turns}; set_scope {id, scope, confirmed?}. Humans only.",
                "{\"action\": \"spawn\", \"goal\": \"understand X.O8E.A02\", \"depth\": \"deep\", \"scope\": \"pkg:X\"}",
                props("action", pick("spawn | list | stop | pause | resume | revert | turns | turn | activity | set_budget | set_turns | set_scope",
                                "spawn", "list", "stop", "pause", "resume", "revert", "turns", "turn", "activity",
                                "set_budget", "set_turns", "set_scope"),
                        "id", str("mission id from action list", "\"m3\""),
                        "goal", str("mission goal in one sentence (spawn)", "\"understand X.O8E.A02\""),
                        "model", str("provider:model (spawn; optional with depth)", "\"deepseek:deepseek-v4-pro\""),
                        "depth", str("quick | deep (spawn; optional): the configured reversing agent", "\"deep\""),
                        "scope", str("write scope (spawn, set_scope; optional): pkg:<prefix>, target:<id>, map:<id>, project", "\"pkg:X\""),
                        "target", str("target id to pin the mission to (spawn; optional; default the active target)", "\"t1\""),
                        "budget", num("token budget (spawn, resume, set_budget; optional)", "1500000"),
                        "turns", num("turn cap (spawn, resume, set_turns; optional)", "100"),
                        "effort", str("low|medium|high|xhigh|max (spawn; optional)", "\"high\""),
                        "mode", str("driver mode (spawn; optional): cli|http|scripted, inferred from the provider", "\"http\""),
                        "turn", num("turn number (turn)", "12"),
                        "agent", str("agent id (activity; optional): only that mission's rows", "\"agent-3\""),
                        "kinds", str("row kinds csv (activity; optional): tool,result,note,blocked", "\"tool,result\""),
                        "offset", num("paging offset (activity; optional)", "0"),
                        "count", num("page size (activity; optional, default 100)", "50"),
                        "confirmed", bool("confirm a scope widening (set_scope; optional)", "true")),
                "action");
        tool("review", false, List.of("review.policy", "review.configure", "agent.proposals", "agent.review", "agent.purge"),
                "The agent-write policy and the review queue. action policy: whether agent writes apply at once (auto, the default)"
                        + " or queue for a human (manual), the kinds auto covers and the confidence floor; configure {mode?, kinds?,"
                        + " minConfidence?} (a merge); proposals {mission?, kind?, target?, state?}: the queue (pending) or every agent write with state all; accept / reject {id,"
                        + " newName?} (accept with newName applies an edited name); purge {before?, mission?, noRationale?, targetGone?}:"
                        + " settle matching pending proposals as purged. Humans only.",
                "{\"action\": \"configure\", \"mode\": \"auto\"}",
                props("action", pick("policy | configure | proposals | accept | reject | purge",
                                "policy", "configure", "proposals", "accept", "reject", "purge"),
                        "mode", str("auto or manual (configure)", "\"auto\""),
                        "kinds", str("comma-separated kinds auto applies to (configure; optional): rename, comment, tag, verdict", "\"rename,comment\""),
                        "minConfidence", num("0 to 1; writes below it queue (configure; optional)", "0.8"),
                        "mission", str("mission id filter (proposals, purge; optional)", "\"m3\""),
                        "kind", str("proposal kind filter (proposals; optional): rename|comment|tag|verdict", "\"rename\""),
                        "target", str("target id filter (proposals; optional)", "\"t1\""),
                        "state", str("proposals: pending (default) | all | accepted | rejected | refused | purged (optional)", "\"all\""),
                        "id", str("proposal id (accept, reject)", "\"pp42\""),
                        "newName", str("edited name to apply instead of the proposed one (accept; optional)", "\"MediaDownloadManager\""),
                        "before", str("ISO instant: only proposals filed before it (purge; optional)", "\"2026-09-10T00:00:00Z\""),
                        "noRationale", bool("only proposals without a rationale (purge; optional)", "true"),
                        "targetGone", bool("only proposals whose target is not open (purge; optional)", "true")),
                "action");
        tool("llm", false, List.of("llm.providers", "llm.models", "llm.configure", "llm.setkey", "llm.remove", "llm.rename"),
                "LLM providers. action providers: id, label, driver, baseUrl, models[], default model, needsKey, configured; models {id}:"
                        + " ask the endpoint for its model ids; configure {id, create?, label?, baseUrl?, models?, model?, local?, auth?,"
                        + " extraParams?, contextTokens?}: edit a provider, or ADD an OpenAI-compatible one with create:true — a provider is"
                        + " ONE vendor endpoint with ONE key, model versions go in its list, never in the id; setkey {id, key} (write-only;"
                        + " \"\" clears it; prefer WORKBENCH_LLM_<ID>_KEY in the server's environment); remove {id}; rename {id, newId}."
                        + " The operator's, not a mission's.",
                "{\"action\": \"configure\", \"id\": \"vllm\", \"create\": true, \"baseUrl\": \"http://127.0.0.1:8000/v1\", \"models\": \"qwen3-32b\", \"model\": \"qwen3-32b\", \"local\": true}",
                props("action", pick("providers | models | configure | setkey | remove | rename",
                                "providers", "models", "configure", "setkey", "remove", "rename"),
                        "id", str("provider id — the VENDOR as a lowercase slug", "\"deepseek\""),
                        "newId", str("the new id (rename)", "\"zai\""),
                        "key", str("the API key (setkey); \"\" clears the stored one", "\"sk-...\""),
                        "create", bool("true to add a new provider (configure; optional)", "true"),
                        "label", str("display label (configure; optional)", "\"vLLM (local)\""),
                        "baseUrl", str("endpoint root (configure; optional)", "\"http://127.0.0.1:8000/v1\""),
                        "models", str("comma-separated model ids, your order (configure; optional)", "\"qwen3-32b, qwen2.5-coder\""),
                        "model", str("the default model; must be one of models when given (configure; optional)", "\"qwen3-32b\""),
                        "local", bool("loopback provider needing no key (configure; optional)", "true"),
                        "auth", str("anthropic only: cli = the Claude Code sign-in, api = an API key; send it alone (configure; optional)", "\"api\""),
                        "extraParams", obj("JSON merged into every chat request, e.g. a vendor's thinking switch (configure; optional)", "{\"reasoning_effort\": \"high\"}"),
                        "contextTokens", num("the model's context window (configure; optional; default 128000)", "128000")),
                "action");
        tool("reversing_agent", false, List.of("task.list", "task.configure"),
                "The built-in reversing agent's settings per depth (quick, deep): model, effort, turn cap, budget and what the next spawn"
                        + " resolves to. action show; configure {depth, model?, effort?, turns?, budget?} — a merge; \"\" or 0 resets a"
                        + " field. The operator's, not a mission's.",
                "{\"action\": \"configure\", \"depth\": \"deep\", \"model\": \"deepseek:deepseek-v4-pro\", \"effort\": \"high\"}",
                props("action", pick("show | configure", "show", "configure"),
                        "depth", str("quick or deep (configure)", "\"deep\""),
                        "model", str("provider:model — must resolve (configure; optional)", "\"deepseek:deepseek-v4-pro\""),
                        "effort", str("low|medium|high|xhigh|max (configure; optional)", "\"high\""),
                        "turns", num("turn cap; 0 = the depth's default (configure; optional)", "100"),
                        "budget", num("token budget; 0 = the depth's default (configure; optional)", "1500000")),
                "action");
        tool("index", false, List.of("search.index", "search.reindex"),
                "The full-text search index of a target. action status: state, built/total, pendingReindex; rebuild: re-index now.",
                "{\"action\": \"status\"}",
                props("action", pick("status | rebuild", "status", "rebuild"),
                        "target", str("target id (optional; default the active target)", "\"t1\"")),
                "action");
        tool("workspace", false, List.of("workspace.state"),
                "The workspace at a glance: the active project and target, open targets with their state, the engine, and heap"
                        + " {maxBytes, liveBytes, headroomBytes} — read headroomBytes before opening another apk: a WhatsApp-size"
                        + " apk needs ~4 GB to analyse and ~5.5 GB to reload; an open that will not fit is refused, so close targets"
                        + " you are done with (target close) first.",
                "{}", props());
        tool("history", false, List.of("history.list", "history.undo", "history.redo"),
                "The edit history of a target (renames and comments, by any actor). action list {limit?}; undo {id?} (the newest entry"
                        + " when id is absent); redo {id?}.",
                "{\"action\": \"list\", \"limit\": 50}",
                props("action", pick("list | undo | redo", "list", "undo", "redo"),
                        "id", str("history entry id (undo, redo; optional)", "\"h12\""),
                        "limit", num("max entries (list; optional, default 200)", "50"),
                        "target", str("target id (optional; default the active target)", "\"t1\"")),
                "action");
        tool("audit", false, List.of("audit"),
                "Who used the workbench and how. action calls {tail?, actor?, op?, since?}: the audit trail, one line per"
                        + " call (ts, actor, client, op, target, symbol, write, ok, ms) — every call by an agent or an MCP client,"
                        + " every write by a human; action summary {since?}: per actor — calls, writes, errors, the ops used, the"
                        + " targets touched, the clients seen, first and last call. since is an ISO instant.",
                "{\"action\": \"summary\", \"since\": \"2026-09-20T00:00:00Z\"}",
                props("action", pick("calls | summary", "calls", "summary"),
                        "tail", num("max lines (calls; optional, default 200)", "200"),
                        "actor", str("actor substring filter (calls; optional)", "\"kimi\""),
                        "op", str("op substring filter (calls; optional)", "\"rename\""),
                        "since", str("ISO instant: only calls after it (optional)", "\"2026-09-20T00:00:00Z\"")),
                "action");
        tool("presence", true, List.of("presence.ping", "presence.whereis"),
                "Co-presence with the human at the workbench. action ping {symbol, member?}: announce the symbol you are working on —"
                        + " the human's UI follows this pin (reads move it on their own; ping is for a symbol you reached by reasoning);"
                        + " whereis {who?}: where another actor is right now ('human' is the person at the workbench).",
                "{\"action\": \"ping\", \"symbol\": \"Lm76/ak;->a()V\"}",
                props("action", pick("ping | whereis", "ping", "whereis"),
                        "symbol", str("class or method you are now working on (ping)", "\"Lm76/ak;->a()V\""),
                        "member", str("member addr when symbol is a class (ping; optional)", "\"Lm76/ak;->a()V\""),
                        "who", str("actor kind: human or agent (whereis; optional, default agent)", "\"human\"")),
                "action");
    }

    // ------------------------------------------------------------------ catalogue views

    /** OpenAI-style function definitions for the loop-visible tools (strict schemas: required params,
     *  additionalProperties:false — weak-at-tools models loop on empty-arg calls otherwise). */
    public static List<Object> functionDefs() {
        List<Object> out = new ArrayList<>();
        for (Tool t : TOOLS.values()) {
            if (!t.loop()) continue;
            Map<String, Object> fn = new LinkedHashMap<>();
            fn.put("name", t.name());
            fn.put("description", t.description());
            fn.put("parameters", schema(t));
            Map<String, Object> wrap = new LinkedHashMap<>();
            wrap.put("type", "function");
            wrap.put("function", fn);
            out.add(wrap);
        }
        return out;
    }

    /** The `ops.tools` rows: every tool with its schema, the ops it routes to and whether the
     *  loop may call it — the bridge's tools/list and the descriptor page read this. */
    public static List<Object> catalogue() {
        List<Object> out = new ArrayList<>();
        for (Tool t : TOOLS.values()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("tool", t.name());
            row.put("op", t.ops().isEmpty() ? null : t.ops().get(0));
            row.put("ops", t.ops());
            row.put("loop", t.loop());
            row.put("description", t.description());
            row.put("parameters", schema(t));
            out.add(row);
        }
        return out;
    }

    private static Map<String, Object> schema(Tool t) { return schemaOf(t.properties(), t.required()); }

    private static Map<String, Object> schemaOf(Map<String, Object> properties, List<String> required) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("type", "object");
        params.put("properties", properties);
        if (!required.isEmpty()) params.put("required", required);
        params.put("additionalProperties", false);
        return params;
    }

    /** First missing/blank required param for a tool call, or null (a corrective hint the model
     *  can self-correct from, before the dispatcher sees the call). */
    public static String missingRequired(String tool, Map<String, Object> args) {
        Tool t = TOOLS.get(tool);
        if (t == null) return null;
        for (String req : t.required()) {
            Object v = args.get(req);
            if (v == null || (v instanceof String s && s.isBlank())) return req;
        }
        return null;
    }

    public static String exampleFor(String tool) {
        Tool t = TOOLS.get(tool);
        String d = t == null ? "" : t.description();
        int cut = d.indexOf("Example:");
        return cut >= 0 ? d.substring(cut + "Example:".length()).trim() : "{}";
    }

    // ------------------------------------------------------------------ routing

    /** One registry call: the op and its parameters. */
    public record Route(String op, Map<String, Object> params) {}

    private static String s(Map<String, Object> args, String key) {
        Object v = args.get(key);
        return v == null ? null : String.valueOf(v);
    }

    /** Copy every argument but the selector keys, renaming the given pairs (from, to, from, to …). */
    private static Map<String, Object> pass(Map<String, Object> args, Set<String> drop, String... renames) {
        Map<String, Object> p = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : args.entrySet()) {
            if (drop.contains(e.getKey())) continue;
            String k = e.getKey();
            for (int i = 0; i + 1 < renames.length; i += 2) if (renames[i].equals(k)) { k = renames[i + 1]; break; }
            p.put(k, e.getValue());
        }
        return p;
    }
    private static boolean present(Object v) { return v != null && !(v instanceof String str && str.isBlank()); }
    private static final Set<String> SEL = Set.of("action", "view", "direction", "kind");
    private static final Set<String> ACTION = Set.of("action");

    private static OpException badAction(String tool, String action, String... allowed) {
        return OpException.syntax("unknown action '" + action + "' for " + tool,
                "action must be one of: " + String.join(", ", allowed));
    }

    /** The registry call a (tool, args) pair means; `annotate` is the one tool that fans out
     *  (see {@link #call}) and is not routed here. */
    public static Route route(String tool, Map<String, Object> args) throws OpException {
        Tool t = TOOLS.get(tool);
        if (t == null) throw OpException.unsupported("unknown tool: " + tool, "tools: " + String.join(", ", TOOLS.keySet()));
        String action = s(args, "action");
        switch (tool) {
            case "naming_coverage": {
                if (action == null || "get".equals(action)) return new Route("naming.coverage", pass(args, ACTION));
                if ("record".equals(action)) return new Route("naming.coverage.record", pass(args, Set.of("action", "recordedBy")));
                throw badAction(tool, action, "get", "record");
            }
            case "symbols": {
                /* a blank selector is an absent one: {"prefix": "com.foo", "query": ""} is a prefix call, not a
                   search that then fails on its empty query */
                if (present(args.get("symbol"))) return new Route("symbol.resolve", pass(args, Set.of("prefix", "query", "limit")));
                if (present(args.get("query"))) return new Route("symbol.search", pass(args, Set.of("prefix", "symbol")));   // kind rides along
                /* `prefix` goes through AS a prefix. It used to be renamed to symbol.tree's `filter`,
                   where a dotted name is a substring search that expands every match's members —
                   779 KB for one 157-class package (2026-10-03). Absent = the top level. */
                Map<String, Object> tp = pass(args, Set.of("query", "symbol", "kind"));
                tp.putIfAbsent("prefix", "");
                return new Route("symbol.tree", tp);
            }
            case "code": {
                String view = args.get("view") == null ? "java" : s(args, "view");
                switch (view) {
                    case "java": return new Route("code.decompile", pass(args, SEL));
                    case "smali": return new Route("code.smali", pass(args, Set.of("view", "window", "scope", "at", "context", "lines")));
                    case "outline": return new Route("code.outline", pass(args, Set.of("view", "window", "scope"), "symbol", "class"));
                    case "packet": {
                        /* an agent reads the packet through the loop's result cap: the optional student
                           block (every line again) stays out unless asked for */
                        Map<String, Object> p = pass(args, Set.of("view", "window", "scope"));
                        p.putIfAbsent("student", false);
                        return new Route("evidence.packet", p);
                    }
                    case "params": return new Route("code.params", pass(args, Set.of("view", "window", "scope", "question", "entry", "maxInstructions")));
                    case "locals": return new Route("locals.list", pass(args, Set.of("view", "window", "scope", "question", "entry", "maxInstructions")));
                    case "site": return new Route("code.site", pass(args, Set.of("view", "window", "scope", "question", "entry", "maxInstructions")));
                    default: throw OpException.syntax("unknown view '" + view + "' for code", "view must be java, smali, outline, packet, params, locals or site");
                }
            }
            case "xrefs": {
                String dir = args.get("direction") == null ? "to" : s(args, "direction");
                boolean call = "call".equals(s(args, "kind"));
                Map<String, Object> p = pass(args, SEL);
                switch (dir) {
                    case "to": return new Route(call ? "callers" : "xref.to", p);
                    case "from": return new Route(call ? "callees" : "xref.from", p);
                    case "impls": return new Route("impls", p);
                    default: throw OpException.syntax("unknown direction '" + dir + "' for xrefs", "direction must be to, from or impls");
                }
            }
            case "strings": {
                if (args.get("id") != null) return new Route("string.xrefs", pass(args, Set.of("filter", "type", "limit", "offset")));
                return new Route("strings", pass(args, Set.of("id"), "limit", "count"));
            }
            case "search": {
                if (args.get("anchors") != null) return new Route("symbol.locate", pass(args, Set.of("query", "kind")));
                if (!present(args.get("query"))) throw OpException.syntax("search needs query (full text) or anchors (a cross-build lookup)", "call search with {\"query\": \"cipher\"} or {\"anchors\": [{\"kind\": \"string\", \"value\": \"…\"}]}");
                return new Route("search", pass(args, Set.of("anchors")));
            }
            case "map_verdict": return new Route("map.setverdict", pass(args, Set.of()));
            case "workspace": return new Route("workspace.state", Map.of());
            case "map": {
                Map<String, Object> p = pass(args, ACTION, "limit", "count");
                switch (action == null ? "" : action) {
                    case "list": return new Route("map.list", p);
                    case "summary": return new Route("map.summary", p);
                    case "outline": return new Route("map.outline", p);
                    case "node": return new Route("map.node", p);
                    case "full": return new Route("map.get", p);
                    case "find": return new Route("map.find", p);
                    case "neighbors": return new Route("map.neighbors", p);
                    case "paths": return new Route("map.paths", p);
                    default: throw badAction(tool, action, "list", "summary", "outline", "node", "full", "find", "neighbors", "paths");
                }
            }
            case "map_edit": {
                Map<String, Object> p = pass(args, Set.of("action", "fields"));
                Object fields = args.get("fields");
                switch (action == null ? "" : action) {
                    case "create": return new Route("map.create", p);
                    case "delete": return new Route("map.delete", p);
                    case "add_node": { p.put("node", fields); return new Route("map.addnode", p); }
                    case "update_node": {
                        if (fields instanceof Map<?, ?> fm) for (Map.Entry<?, ?> e : fm.entrySet()) p.put(String.valueOf(e.getKey()), e.getValue());
                        return new Route("map.updatenode", p);
                    }
                    case "remove_node": return new Route("map.rmnode", p);
                    case "add_edge": return new Route("map.addedge", p);
                    case "remove_edge": return new Route("map.rmedge", p);
                    case "set_chain": return new Route("map.setchain", p);
                    case "attach_evidence": return new Route("map.attachevidence", p);
                    case "type_list": return new Route("map.type.list", p);
                    case "type_add": return new Route("map.type.add", p);
                    case "type_set": return new Route("map.type.set", p);
                    default: throw badAction(tool, action, "create", "delete", "add_node", "update_node", "remove_node", "add_edge",
                            "remove_edge", "set_chain", "attach_evidence", "type_list", "type_add", "type_set");
                }
            }
            case "flow": {
                Map<String, Object> p = pass(args, ACTION);
                switch (action == null ? "" : action) {
                    case "list": return new Route("flow.list", p);
                    case "get": return new Route("flow.get", p);
                    case "add": return new Route("flow.add", p);
                    case "remove": return new Route("flow.rm", p);
                    case "promote": return new Route("flow.promote", p);
                    default: throw badAction(tool, action, "list", "get", "add", "remove", "promote");
                }
            }
            case "devices": {
                Map<String, Object> p = pass(args, ACTION, "limit", "count");
                switch (action == null ? "" : action) {
                    case "list": return new Route("device.list", p);
                    case "components": return new Route("device.components", p);
                    case "services": return new Route("device.services", p);
                    case "processes": return new Route("device.proc", p);
                    case "catalog": return new Route("device.catalog", p);
                    default: throw badAction(tool, action, "list", "components", "services", "processes", "catalog");
                }
            }
            case "runs": {
                Map<String, Object> p = pass(args, ACTION);
                switch (action == null ? "" : action) {
                    case "list": return new Route("runs.runs", p);
                    case "hits": return new Route("runs.hits", p);
                    case "coverage": return new Route("runs.coverage", p);
                    case "diff": return new Route("runs.diff", p);
                    case "remove": return new Route("runs.rm", p);
                    case "import": return new Route("runs.import", p);
                    case "export": return new Route("runs.export", p);
                    default: throw badAction(tool, action, "list", "hits", "coverage", "diff", "remove", "import", "export");
                }
            }
            case "run": {
                Map<String, Object> p = pass(args, ACTION);
                switch (action == null ? "" : action) {
                    case "spawn": return new Route("runs.spawn", p);
                    case "attach": return new Route("runs.attach", p);
                    case "detach": return new Route("runs.detach", p);
                    default: throw badAction(tool, action, "spawn", "attach", "detach");
                }
            }
            case "breakpoints": {
                Map<String, Object> p = pass(args, ACTION);
                switch (action == null ? "" : action) {
                    case "list": return new Route("runs.bp.list", p);
                    case "add": return new Route("runs.bp.add", p);
                    case "remove": return new Route("runs.bp.rm", p);
                    case "toggle": return new Route("runs.bp.toggle", p);
                    default: throw badAction(tool, action, "list", "add", "remove", "toggle");
                }
            }
            case "project": {
                switch (action == null ? "" : action) {
                    case "list": return new Route("project.list", Map.of());
                    case "known": return new Route("project.known", Map.of());
                    case "open": return new Route("project.open", pass(args, ACTION));
                    case "switch": return new Route("project.switch", pass(args, ACTION, "name", "project"));
                    case "close": return new Route("project.close", pass(args, ACTION, "name", "project"));
                    case "targets": return new Route("project.targets", pass(args, ACTION, "name", "project"));
                    case "forget": return new Route("project.forget", pass(args, ACTION));
                    case "delete": return new Route("project.delete", pass(args, ACTION));
                    default: throw badAction(tool, action, "list", "known", "open", "switch", "close", "targets", "forget", "delete");
                }
            }
            case "target": {
                Map<String, Object> p = pass(args, ACTION);
                switch (action == null ? "" : action) {
                    case "list": return new Route("target.list", p);
                    case "open":
                        /* the re-key consent (target.open {rekey}) is a gesture of the workbench UI:
                           a tool call cannot carry it, whatever actor the client declares */
                        p.remove("rekey");
                        return new Route("target.open", p);
                    case "info": return new Route("target.info", p);
                    case "changes": return new Route("target.changes", p);
                    case "save": { Map<String, Object> q = new LinkedHashMap<>(p); q.putIfAbsent("async", true); return new Route("target.save", q); }
                    case "save_status": return new Route("target.saveStatus", p);
                    case "switch": return new Route("target.switch", p);
                    case "close": { Map<String, Object> q = new LinkedHashMap<>(p); q.putIfAbsent("async", true); return new Route("target.close", q); }
                    case "remove": return new Route("target.remove", p);
                    default: throw badAction(tool, action, "list", "open", "info", "changes", "save", "save_status", "switch", "close", "remove");
                }
            }
            case "mission": {
                Map<String, Object> p = pass(args, ACTION);
                switch (action == null ? "" : action) {
                    case "spawn": return new Route("agent.spawn", p);
                    case "list": return new Route("agent.missions", p);
                    case "stop": return new Route("agent.stop", p);
                    case "pause": return new Route("agent.pause", p);
                    case "resume": return new Route("agent.resume", p);
                    case "revert": return new Route("agent.revert", p);
                    case "turns": return new Route("agent.turns", pass(args, ACTION, "id", "mission"));
                    case "turn": return new Route("agent.turn", pass(args, ACTION, "id", "mission"));
                    case "activity": return new Route("agent.activity", p);
                    case "set_budget": return new Route("agent.setbudget", p);
                    case "set_turns": return new Route("agent.setturns", p);
                    case "set_scope": return new Route("agent.setscope", p);
                    default: throw badAction(tool, action, "spawn", "list", "stop", "pause", "resume", "revert", "turns", "turn",
                            "activity", "set_budget", "set_turns", "set_scope");
                }
            }
            case "review": {
                Map<String, Object> p = pass(args, ACTION);
                switch (action == null ? "" : action) {
                    case "policy": return new Route("review.policy", p);
                    case "configure": return new Route("review.configure", p);
                    case "proposals": return new Route("agent.proposals", p);
                    case "accept": { p.put("action", "accept"); return new Route("agent.review", p); }
                    case "reject": { p.put("action", "reject"); return new Route("agent.review", p); }
                    case "purge": return new Route("agent.purge", p);
                    default: throw badAction(tool, action, "policy", "configure", "proposals", "accept", "reject", "purge");
                }
            }
            case "llm": {
                Map<String, Object> p = pass(args, ACTION);
                switch (action == null ? "" : action) {
                    case "providers": return new Route("llm.providers", p);
                    case "models": return new Route("llm.models", p);
                    case "configure": return new Route("llm.configure", p);
                    case "setkey": return new Route("llm.setkey", p);
                    case "remove": return new Route("llm.remove", p);
                    case "rename": return new Route("llm.rename", p);
                    default: throw badAction(tool, action, "providers", "models", "configure", "setkey", "remove", "rename");
                }
            }
            case "reversing_agent": {
                Map<String, Object> p = pass(args, ACTION);
                if (p.get("task") == null) p.put("task", "reverse");
                switch (action == null ? "" : action) {
                    case "show": return new Route("task.list", Map.of());
                    case "configure": return new Route("task.configure", p);
                    default: throw badAction(tool, action, "show", "configure");
                }
            }
            case "index": {
                Map<String, Object> p = pass(args, ACTION);
                switch (action == null ? "" : action) {
                    case "status": return new Route("search.index", p);
                    case "rebuild": return new Route("search.reindex", p);
                    default: throw badAction(tool, action, "status", "rebuild");
                }
            }
            case "history": {
                Map<String, Object> p = pass(args, ACTION);
                switch (action == null ? "" : action) {
                    case "list": return new Route("history.list", p);
                    case "undo": return new Route("history.undo", p);
                    case "redo": return new Route("history.redo", p);
                    default: throw badAction(tool, action, "list", "undo", "redo");
                }
            }
            case "audit": {
                Map<String, Object> p = pass(args, ACTION);
                switch (action == null ? "" : action) {
                    case "calls": return new Route("audit", p);
                    case "summary": { p.put("summary", true); return new Route("audit", p); }
                    default: throw badAction(tool, action, "calls", "summary");
                }
            }
            case "presence": {
                switch (action == null ? "" : action) {
                    case "ping": return new Route("presence.ping", pass(args, Set.of("action", "who"), "symbol", "addr"));
                    case "whereis": return new Route("presence.whereis", pass(args, Set.of("action", "symbol", "member")));
                    default: throw badAction(tool, action, "ping", "whereis");
                }
            }
            case "annotate":
                throw OpException.unsupported("annotate fans out to several ops — use ToolFacade.call", "tool=annotate");
            default:
                throw OpException.unsupported("no route for tool " + tool, "tool=" + tool);
        }
    }

    // ------------------------------------------------------------------ execution

    /** Run one tool call as `actor` through the dispatcher — every op-level check (scope, actor
     *  kind, review policy) happens inside. Returns the dispatcher envelope {ok, data | error}. */
    public static Map<String, Object> call(Dispatcher d, String tool, Map<String, Object> args, String actor) { return call(d, tool, args, actor, false); }

    /** `external`: the call came through the `tool.call` op — an assistant outside the workbench's own loop. */
    public static Map<String, Object> call(Dispatcher d, String tool, Map<String, Object> args, String actor, boolean external) {
        if (args == null) args = new LinkedHashMap<>();
        if (!TOOLS.containsKey(tool))
            return err("E_UNSUPPORTED", "unknown tool: " + tool, "call one of: " + String.join(", ", TOOLS.keySet()));
        String missing = missingRequired(tool, args);
        if (missing != null)
            return err("E_SYNTAX", "missing required param '" + missing + "' for " + tool, "call " + tool + " with " + exampleFor(tool));
        if ("annotate".equals(tool)) return annotate(d, args, actor);
        Route r;
        try {
            r = route(tool, args);
        } catch (OpException e) {
            return err(e.code, e.getMessage(), "call " + tool + " with " + exampleFor(tool));
        }
        Map<String, Object> res = d.dispatch(r.op(), r.params(), actor);
        if ("search".equals(r.op()) && Boolean.TRUE.equals(res.get("ok"))
                && res.get("meta") instanceof Map<?, ?> meta && meta.get("searchCoverage") instanceof Map<?, ?> coverage) {
            res = new LinkedHashMap<>(res);
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("coverage", coverage); // precede hits so bridge result truncation retains the warning
            data.put("groups", res.get("data"));
            res.put("data", data);
        }
        if ("target".equals(tool)) res = targetRow(res, args);
        res = withResolution(res);
        return withCandidates(d, res, args, actor);
    }

    /** A lenient resolution the op recorded (meta.resolution) is put INTO the data the model and the bridge
     *  read — tool.call, the bridge and the loop keep only `data` (fix-review 2026-10-09 H3): a map gets a
     *  `resolution` key; a bare list is wrapped as {rows, resolution} so the warning cannot be lost. */
    private static Map<String, Object> withResolution(Map<String, Object> res) {
        if (res == null || !Boolean.TRUE.equals(res.get("ok")) || !(res.get("meta") instanceof Map<?, ?> meta) || !(meta.get("resolution") instanceof Map<?, ?> rs)) return res;
        Object data = res.get("data");
        Map<String, Object> out = new LinkedHashMap<>(res);
        if (data instanceof Map<?, ?> dm) {
            Map<String, Object> copy = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : dm.entrySet()) copy.put(String.valueOf(e.getKey()), e.getValue());
            copy.putIfAbsent("resolution", rs);
            out.put("data", copy);
        } else if (data instanceof List<?> rows) {
            Map<String, Object> wrap = new LinkedHashMap<>();
            wrap.put("rows", rows); wrap.put("total", rows.size()); wrap.put("truncated", false);
            wrap.put("resolution", rs);
            wrap.put("note", "rows wrapped to carry `resolution`: the symbol you asked for was resolved leniently (see resolution.requested vs resolution.resolvedOriginal)");
            out.put("data", wrap);
        }
        return out;
    }

    /** target switch / close answer like target open does — the target that is now active as
     *  {id, name, state, …, activeTarget, targets} — instead of the whole workspace state. The
     *  bridge pins a session to the id these actions return; switch used to return the state (no
     *  id), so a client that opened t2 and switched back to t1 stayed pinned to t2: strings and
     *  search kept answering from the other build (Kimi researcher, 2026-09-21). */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> targetRow(Map<String, Object> res, Map<String, Object> args) {
        String action = String.valueOf(args.get("action"));
        if (!("switch".equals(action) || "close".equals(action)) || !Boolean.TRUE.equals(res.get("ok"))) return res;
        if (!(res.get("data") instanceof Map<?, ?> st) || !(st.get("targets") instanceof List<?> ts)) return res;
        if (st.containsKey("closing")) return res;                 // an async close already answers {closing, closed, activeTarget, targets}
        Object active = st.get("activeTarget");
        Map<String, Object> row = new LinkedHashMap<>();
        for (Object o : ts)
            if (o instanceof Map<?, ?> t && active != null && active.equals(t.get("id"))) { row.putAll((Map<String, Object>) t); break; }
        if ("close".equals(action)) row.put("closed", args.get("target"));
        if ("close".equals(action) && st.containsKey("closeCapabilities")) row.put("closeCapabilities", st.get("closeCapabilities"));
        row.put("activeTarget", active);
        List<Object> brief = new ArrayList<>();
        for (Object o : ts)
            if (o instanceof Map<?, ?> t) brief.add(Map.of("id", String.valueOf(t.get("id")), "name", String.valueOf(t.get("name")), "state", String.valueOf(t.get("state"))));
        row.put("targets", brief);
        Map<String, Object> out = new LinkedHashMap<>(res);
        out.put("data", row);
        return out;
    }

    /** A not-found symbol answers with the nearest names (the m5 transcript: the agent guessed
     *  `Lwr;` for a type the decompile showed as `wr`, three misses, then a search — the search
     *  is folded into the error now): error.candidates = up to five symbol.search rows for the
     *  simple name. */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> withCandidates(Dispatcher d, Map<String, Object> res, Map<String, Object> args, String actor) {
        if (Boolean.TRUE.equals(res.get("ok")) || !(res.get("error") instanceof Map<?, ?> em)) return res;
        if (!"E_NOTFOUND".equals(String.valueOf(em.get("code"))) || args.get("symbol") == null) return res;
        if (em.get("candidates") != null) return res;             // the engine already named them (an inner class's siblings)
        String sym = String.valueOf(args.get("symbol"));
        String simple = sym.replaceAll("^L", "").replaceAll(";.*$", "").replaceAll("->.*$", "");
        int slash = Math.max(simple.lastIndexOf('/'), simple.lastIndexOf('.'));
        if (slash >= 0) simple = simple.substring(slash + 1);
        if (simple.isBlank() || simple.length() > 40) return res;
        Map<String, Object> q = new LinkedHashMap<>();
        q.put("query", simple);
        q.put("limit", 40);                       // substring hits; exact simple-name matches are ranked first below
        if (args.get("target") != null) q.put("target", args.get("target"));
        try {
            Map<String, Object> sr = d.dispatch("symbol.search", q, actor);
            Object rows = sr.get("data") instanceof Map<?, ?> dm ? dm.get("rows") : null;
            if (!(rows instanceof List<?> rl) || rl.isEmpty()) return res;
            boolean classShaped = !sym.contains("->");            // Lpkg/C; — offer classes, not methods that merely contain the text
            List<Map<?, ?>> exact = new ArrayList<>(), rest = new ArrayList<>();
            for (Object o : rl) {
                if (!(o instanceof Map<?, ?> rm)) continue;
                if (classShaped && !"class".equals(String.valueOf(rm.get("kind")))) continue;
                (simple.equals(String.valueOf(rm.get("name"))) ? exact : rest).add(rm);
            }
            List<Object> cands = new ArrayList<>();
            List<Map<?, ?>> ranked = new ArrayList<>(exact);
            ranked.addAll(rest);
            for (Map<?, ?> rm : ranked) {
                if (cands.size() >= 5) break;
                Map<String, Object> c = new LinkedHashMap<>();
                c.put("addr", rm.get("addr"));
                c.put("kind", rm.get("kind"));
                if (rm.get("fqcn") != null) c.put("fqcn", rm.get("fqcn"));
                if (rm.get("owner") != null) c.put("owner", rm.get("owner"));
                cands.add(c);
            }
            Map<String, Object> err = new LinkedHashMap<>((Map<String, Object>) em);
            err.put("candidates", cands);
            err.put("hint", "not found as spelled; the nearest symbols are in candidates — retry with one of their addr values");
            Map<String, Object> out = new LinkedHashMap<>(res);
            out.put("error", err);
            return out;
        } catch (Exception e) {
            return res;
        }
    }

    /** annotate: one items[] call fans out to the batch forms of rename, comment and tag.add and
     *  merges the per-item outcomes back under each symbol. */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> annotate(Dispatcher d, Map<String, Object> args, String actor) {
        Object itemsObj = args.get("items");
        if (!(itemsObj instanceof List<?> items) || items.isEmpty() || items.size() > 50)
            return err("E_SYNTAX", "items must hold 1 to 50 entries", "call annotate with " + exampleFor("annotate"));
        Object target = args.get("target");
        List<Map<String, Object>> renames = new ArrayList<>(), comments = new ArrayList<>(), tags = new ArrayList<>();
        List<Integer> renameIx = new ArrayList<>(), commentIx = new ArrayList<>(), tagIx = new ArrayList<>();
        List<Map<String, Object>> rows = new ArrayList<>();
        int failed = 0;
        for (int i = 0; i < items.size(); i++) {
            Map<String, Object> row = new LinkedHashMap<>();
            rows.add(row);
            if (!(items.get(i) instanceof Map<?, ?> im)) { row.put("error", "an item must be an object"); failed++; continue; }
            Map<String, Object> it = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : im.entrySet()) it.put(String.valueOf(e.getKey()), e.getValue());
            String symbol = s(it, "symbol");
            row.put("symbol", symbol);
            if (symbol == null || symbol.isBlank()) { row.put("error", "symbol is required"); failed++; continue; }
            boolean any = false;
            if (it.get("name") != null && !String.valueOf(it.get("name")).isBlank()) {
                any = true;
                Map<String, Object> p = new LinkedHashMap<>();
                p.put("symbol", symbol);
                p.put("name", it.get("name"));
                if (it.get("expectedName") != null) p.put("expectedName", it.get("expectedName"));
                if (it.get("local") != null && !String.valueOf(it.get("local")).isBlank()) p.put("local", it.get("local"));   // a local of the method `symbol` names
                if (it.get("rationale") != null) p.put("rationale", it.get("rationale"));
                if (it.get("confidence") != null) p.put("confidence", it.get("confidence"));
                renames.add(p);
                renameIx.add(i);
            }
            if (it.get("comment") != null && !String.valueOf(it.get("comment")).isBlank()) {
                any = true;
                Map<String, Object> p = new LinkedHashMap<>();
                p.put("symbol", symbol);
                p.put("text", it.get("comment"));
                if (it.get("rationale") != null) p.put("rationale", it.get("rationale"));
                if (it.get("confidence") != null) p.put("confidence", it.get("confidence"));
                comments.add(p);
                commentIx.add(i);
            }
            if (it.get("tags") instanceof List<?> tl) {
                for (Object tg : tl) {
                    String tagName = null, tagNote = null;
                    if (tg instanceof Map<?, ?> tm) {                       // {name, note}
                        tagName = tm.get("name") == null ? null : String.valueOf(tm.get("name"));
                        tagNote = tm.get("note") == null ? null : String.valueOf(tm.get("note"));
                    } else if (tg != null) tagName = String.valueOf(tg);   // a bare string, still accepted
                    if (tagName == null || tagName.isBlank()) continue;
                    any = true;
                    Map<String, Object> p = new LinkedHashMap<>();
                    p.put("symbol", symbol);
                    p.put("name", tagName);
                    /* the note the TAGS panel shows: the tag's own, else the item's rationale, else the
                       first sentence of its comment — a tag without a word beside it is a puzzle */
                    String note = tagNote != null && !tagNote.isBlank() ? tagNote
                            : it.get("rationale") != null && !String.valueOf(it.get("rationale")).isBlank() ? String.valueOf(it.get("rationale"))
                            : it.get("comment") != null ? firstSentence(String.valueOf(it.get("comment"))) : null;
                    if (note != null && !note.isBlank()) p.put("note", note);
                    if (it.get("rationale") != null) p.put("rationale", it.get("rationale"));
                    if (it.get("confidence") != null) p.put("confidence", it.get("confidence"));
                    tags.add(p);
                    tagIx.add(i);
                }
            }
            if (!any) { row.put("error", "nothing to do: give name, comment or tags"); failed++; }
        }
        int applied = 0, pending = 0, unchanged = 0;
        /* comments and tags FIRST, renames last: a comment keys on the item and follows a rename, but a
           comment filed by the OLD address after the rename finds nothing (m10 turn 8, 2026-09-20) */
        String[] ops = {"comment", "tag.add", "rename"};
        List<List<Map<String, Object>>> batches = List.of(comments, tags, renames);
        List<List<Integer>> indexes = List.of(commentIx, tagIx, renameIx);
        String[] keys = {"comment", "tags", "rename"};
        for (int b = 0; b < 3; b++) {
            List<Map<String, Object>> batch = batches.get(b);
            if (batch.isEmpty()) continue;
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("items", batch);
            if (target != null) params.put("target", target);
            Map<String, Object> res = d.dispatch(ops[b], params, actor);
            if (!Boolean.TRUE.equals(res.get("ok"))) return res;           // a whole-batch refusal: the caller sees the op's error
            Map<String, Object> data = res.get("data") instanceof Map<?, ?> dm ? (Map<String, Object>) dm : Map.of();
            List<Object> results = data.get("results") instanceof List<?> rl ? (List<Object>) rl : List.of();
            for (int k = 0; k < batch.size() && k < results.size(); k++) {
                Map<String, Object> outcome = new LinkedHashMap<>();
                if (results.get(k) instanceof Map<?, ?> rm) {
                    Object status = rm.get("status");
                    outcome.put("status", status);
                    if (rm.get("id") != null) outcome.put("id", rm.get("id"));
                    if (rm.get("error") != null) outcome.put("error", rm.get("error"));
                    if (rm.get("errorDetails") != null) outcome.put("errorDetails", rm.get("errorDetails"));
                    if ("applied".equals(status)) applied++; else if ("pending".equals(status)) pending++;
                    else if ("unchanged".equals(status)) unchanged++; else failed++;
                }
                Map<String, Object> row = rows.get(indexes.get(b).get(k));
                if (b == 1) {
                    outcome.put("tag", batch.get(k).get("name"));
                    List<Object> tagRows = row.get("tags") instanceof List<?> tr ? (List<Object>) tr : new ArrayList<>();
                    tagRows.add(outcome);
                    row.put("tags", tagRows);
                } else row.put(keys[b], outcome);
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("applied", applied);
        out.put("pending", pending);
        out.put("failed", failed);
        out.put("unchanged", unchanged);
        out.put("results", rows);
        Map<String, Object> env = new LinkedHashMap<>();
        env.put("ok", true);
        env.put("data", out);
        return env;
    }

    private static String firstSentence(String text) {
        String t = text.replaceAll("\\s+", " ").trim();
        int cut = t.indexOf(". ");
        if (cut > 8) t = t.substring(0, cut + 1);          // past the "e.g. " / "i.e. " abbreviations
        return t.length() > 140 ? t.substring(0, 137) + "…" : t;
    }

    private static Map<String, Object> err(String code, String message, String hint) {
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("code", code);
        e.put("message", message);
        e.put("hint", hint);
        Map<String, Object> env = new LinkedHashMap<>();
        env.put("ok", false);
        env.put("error", e);
        return env;
    }
}
