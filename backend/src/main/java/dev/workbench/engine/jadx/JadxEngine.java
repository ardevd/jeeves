package dev.workbench.engine.jadx;

import dev.workbench.engine.Engine;
import dev.workbench.registry.OpException;
import java.io.File;
import java.util.*;
import jadx.core.dex.nodes.*;

/** Read-only adapter. Unsupported operations fail explicitly rather than fabricating results. */
public final class JadxEngine implements Engine, dev.workbench.engine.index.SearchIndex.Host {
    private final String id, name, path;
    private final JadxSession session;
    private JadxRelations relations;
    private final dev.workbench.engine.index.SearchIndex index;
    private boolean indexInitialized;

    public JadxEngine(String id, String name, String path, File artifact, File sidecar) {
        this.id = id; this.name = name; this.path = path;
        session = new JadxSession(artifact, this::onSessionReady);
        index = new dev.workbench.engine.index.SearchIndex(this, "search-index:" + id,
                new File(sidecar, "jadx/search-index"), dev.workbench.engine.Engines.jadxVersion());
    }
    private void onSessionReady() {
        synchronized (session) {
            if (indexInitialized) return;
            indexInitialized = true;
            if (index.tryWarmLoad() < 0 && !"on-demand".equalsIgnoreCase(System.getenv("WORKBENCH_INDEX_MODE"))) {
                index.start();
            }
        }
    }
    public void openAsync() { session.openAsync(); }
    @Override public Map<String, Object> caps() {
        Map<String, Object> caps = new LinkedHashMap<>();
        for (String key : List.of("decompile", "xrefs", "callgraph", "strings")) caps.put(key, true);
        for (String key : List.of("rename", "comment", "smali", "runs")) caps.put(key, false);
        caps.put("persist", "none"); return caps;
    }
    @Override public Map<String, Object> info() {
        Map<String, Object> info = session.status();
        info.put("id", id); info.put("name", name); info.put("path", path); info.put("engine", "JADX");
        info.put("caps", caps()); info.put("dirty", false); info.put("index", index.status());
        info.put("engineVersion", dev.workbench.engine.Engines.jadxVersion()); return info;
    }
    @Override public Map<String, Object> inputIdentity() { return session.identity(); }
    @Override public Map<String, Object> resolve(String symbol) throws OpException {
        synchronized (session) {
            Map<String, Object> r;
            try { r = session.symbols().resolve(symbol); }
            catch (OpException missing) {
                String clean = symbol == null ? "" : symbol.trim();
                java.util.regex.Matcher offm = java.util.regex.Pattern.compile("^(.*->[^+]+)\\+(0x[0-9a-fA-F]+|[0-9a-fA-F]+h)$").matcher(clean);
                if (offm.matches()) clean = offm.group(1);
                if (!"E_NOTFOUND".equals(missing.code) || clean.isEmpty() || !relations().references(clean)) throw missing;
                String addr = clean; int arrow = addr.indexOf("->");
                String kind = arrow < 0 ? "class" : addr.indexOf('(', arrow) >= 0 ? "method" : "field";
                r = new LinkedHashMap<>(); r.put("addr", addr); r.put("origSig", addr); r.put("sig", addr);
                r.put("kind", kind); r.put("external", true); r.put("exact", true);
                r.put("name", arrow < 0 ? addr : addr.substring(arrow + 2).split("[(:]", 2)[0]);
            }
            r.put("target", id); return r;
        }
    }
    @Override public List<Object> symbolSearch(String query) throws OpException {
        synchronized (session) {
            @SuppressWarnings("unchecked") List<Object> rows = (List<Object>) session.symbols().search(query, 0, null).get("rows");
            return rows;
        }
    }
    @Override public Map<String, Object> symbolSearchPage(String q, int limit, String kind) throws OpException {
        synchronized (session) { return session.symbols().search(q, limit, kind); }
    }
    @Override public Map<String, Object> decompile(String symbol, Map<String, Object> opts) throws OpException {
        synchronized (session) { return JadxSource.render(session.symbols().lookup(symbol), opts); }
    }
    @Override public Map<String, Object> tree(String filter) throws OpException { return tree(filter, 0); }
    @Override public Map<String, Object> tree(String filter, int limit) throws OpException {
        synchronized (session) {
            if (filter == null || filter.isBlank() || filter.startsWith("L")) return treeUnder(filter, limit);
            return Map.of("items", symbolSearchPage(filter, limit, "class").get("rows"),
                    "total", symbolSearchPage(filter, limit, "class").get("total"),
                    "truncated", symbolSearchPage(filter, limit, "class").get("truncated"));
        }
    }
    @Override public Map<String, Object> treeUnder(String prefix, int limit) throws OpException {
        synchronized (session) {
            JadxSymbols symbols = session.symbols();
            String p = prefix == null ? "" : prefix.trim();
            if (p.endsWith(";")) {
                JadxSymbols.Symbol s = symbols.lookup(p);
                Map<String, Object> row = s.row();
                row.put("children", symbols.byAddress.values().stream().filter(x -> x.addr().startsWith(s.addr() + "->")).map(JadxSymbols.Symbol::row).toList());
                return Map.of("items", List.of(row), "total", 1, "truncated", false);
            }
            if (!p.startsWith("L")) p = "L" + p.replace('.', '/');
            if (!p.equals("L") && !p.endsWith("/")) p += "/";
            Map<String, Integer> packages = new TreeMap<>(); List<Object> classes = new ArrayList<>();
            for (ClassNode cls : symbols.classes) {
                String addr = JadxSymbols.address(cls);
                if (!addr.startsWith(p)) continue;
                String rest = addr.substring(p.length()); int slash = rest.indexOf('/');
                if (slash >= 0) packages.merge(p + rest.substring(0, slash + 1), 1, Integer::sum);
                else classes.add(symbols.byAddress.get(addr).row());
            }
            List<Object> rows = new ArrayList<>();
            packages.forEach((pkg, count) -> rows.add(Map.of("kind", "package", "name", pkg.substring(1, pkg.length() - 1), "prefix", pkg, "count", count)));
            int cap = limit > 0 ? limit : 500;
            rows.addAll(classes.subList(0, Math.min(cap, classes.size())));
            return Map.of("items", rows, "total", packages.size() + classes.size(), "truncated", classes.size() > cap);
        }
    }
    @Override public Map<String, Object> outline(String symbol) throws OpException {
        synchronized (session) {
            JadxSymbols symbols = session.symbols(); ClassNode c = symbols.lookup(symbol).owner();
            String addr = JadxSymbols.address(c);
            return Map.of("symbol", symbols.byAddress.get(addr).row(),
                    "methods", symbols.byAddress.values().stream().filter(s -> s.owner() == c && s.kind().equals("method")).map(JadxSymbols.Symbol::row).toList(),
                    "fields", symbols.byAddress.values().stream().filter(s -> s.owner() == c && s.kind().equals("field")).map(JadxSymbols.Symbol::row).toList());
        }
    }
    @Override public Map<String, Object> outline(String symbol, boolean inherited) throws OpException {
        synchronized (session) {
            Map<String, Object> result = new LinkedHashMap<>(outline(symbol));
            if (!inherited) return result;
            JadxSymbols symbols = session.symbols();
            ClassNode current = symbols.lookup(symbol).owner();
            List<Object> fields = new ArrayList<>(); List<String> chain = new ArrayList<>(), unknown = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            while (current.getSuperClass() != null) {
                String parent = jadx.core.codegen.TypeGen.signature(current.getSuperClass());
                if (!seen.add(parent) || parent.equals("Ljava/lang/Object;")) break;
                JadxSymbols.Symbol found = symbols.byAddress.get(parent);
                if (found == null) { unknown.add(parent); break; }
                current = found.owner(); chain.add(parent);
                for (FieldNode f : current.getFields()) {
                    Map<String, Object> row = symbols.byAddress.get(parent + "->" + f.getFieldInfo().getShortId()).row();
                    row.put("inheritedFrom", parent); fields.add(row);
                }
            }
            result.put("inherited", Map.of("chain", chain, "fields", fields, "unknown", unknown));
            return result;
        }
    }
    @Override public Map<String, Object> hover(String symbol, int offset) throws OpException {
        synchronized (session) {
            Map<String, Object> row = resolve(symbol);
            // The UI supplies a resolved token and offset=0. Positive offsets are
            // UTF-16 positions in the enclosing top-level class document.
            if (offset > 0) {
                var selected = session.symbols().lookup(symbol);
                var code = selected.owner().getTopParentClass().getCode();
                String text = code.getCodeStr();
                if (offset >= text.length()) throw OpException.syntax("hover offset outside source", "jadx");
                int start = offset;
                while (start > 0 && Character.isJavaIdentifierPart(text.charAt(start - 1))) start--;
                var node = session.decompiler().getJavaNodeAtPosition(code, start);
                if (node != null) {
                    row = new LinkedHashMap<>();
                    row.put("name", node.getName()); row.put("sig", node.getFullName());
                    row.put("kind", node instanceof jadx.api.JavaMethod ? "method" : node instanceof jadx.api.JavaField ? "field"
                            : node instanceof jadx.api.JavaVariable ? "local" : "class");
                } else throw OpException.notFound("no semantic symbol at source offset", "jadx");
            }
            row.put("offset", offset); row.put("comment", ""); return row;
        }
    }
    private JadxRelations relations() throws OpException {
        session.requireReady(); if (relations == null) relations = new JadxRelations(session.symbols()); return relations;
    }
    @Override public List<Object> xrefsTo(String symbol) throws OpException {
        synchronized (session) { return relations().to(String.valueOf(resolve(symbol).get("addr"))); }
    }
    @Override public List<Object> xrefsFrom(String symbol) throws OpException {
        synchronized (session) { return relations().from(session.symbols().lookup(symbol).addr(), false); }
    }
    @Override public List<Object> callees(String symbol) throws OpException {
        synchronized (session) { return relations().from(session.symbols().lookup(symbol).addr(), true); }
    }
    @Override public List<Object> callers(String symbol) throws OpException {
        synchronized (session) { return relations().callers(String.valueOf(resolve(symbol).get("addr"))); }
    }
    @Override public List<Object> implementations(String symbol) throws OpException {
        synchronized (session) {
            JadxSymbols symbols = session.symbols();
            JadxSymbols.Symbol selected = symbols.lookup(symbol);
            if (!(selected.node() instanceof MethodNode method)) throw OpException.syntax("implementations needs a method", "jadx");
            if (method.getAccessFlags().isStatic() || method.getAccessFlags().isPrivate() || method.getName().startsWith("<")) return List.of();
            Set<String> descendants = new HashSet<>();
            descendants.add(selected.owner().getClassInfo().getRawName());
            boolean changed;
            do {
                changed = false;
                for (ClassNode c : symbols.classes) {
                    boolean child = c.getSuperClass() != null && descendants.contains(c.getSuperClass().getObject());
                    child |= c.getInterfaces().stream().anyMatch(t -> descendants.contains(t.getObject()));
                    if (child) changed |= descendants.add(c.getClassInfo().getRawName());
                }
            } while (changed);
            List<Object> result = new ArrayList<>();
            for (JadxSymbols.Symbol s : symbols.byAddress.values()) {
                if (!(s.node() instanceof MethodNode candidate) || candidate == method || candidate.getAccessFlags().isAbstract()
                        || candidate.getAccessFlags().isStatic() || candidate.getAccessFlags().isPrivate()) continue;
                if (!descendants.contains(s.owner().getClassInfo().getRawName())) continue;
                if (!candidate.getMethodInfo().getShortId().equals(method.getMethodInfo().getShortId())) continue;
                Map<String, Object> row = s.row(); row.put("kind", "override"); result.add(row);
            }
            return result;
        }
    }
    @Override public Map<String, Object> classinfo(String symbol) throws OpException {
        synchronized (session) {
            JadxSymbols symbols = session.symbols(); ClassNode c = symbols.lookup(symbol).owner();
            Map<String, Object> result = symbols.byAddress.get(JadxSymbols.address(c)).row();
            result.put("super", c.getSuperClass() == null ? null : jadx.core.codegen.TypeGen.signature(c.getSuperClass()));
            result.put("interfaces", c.getInterfaces().stream().map(jadx.core.codegen.TypeGen::signature).toList());
            result.put("flags", c.getAccessFlags().makeString(false).trim()); result.put("methods", c.getMethods().size());
            result.put("fields", c.getFields().size()); result.put("innerClasses", c.getInnerClasses().stream().map(JadxSymbols::address).toList());
            result.put("renamed", !c.getName().equals(c.getAlias()));
            return result;
        }
    }
    @Override public Map<String, Object> strings(String filter, List<String> types, boolean unreachable, int offset, int count, String sort) throws OpException {
        synchronized (session) {
            if (offset < 0) throw OpException.syntax("negative offset", "jadx");
            List<Map<String, Object>> all = relations().stringRows();
            List<Map<String, Object>> rows = new ArrayList<>(all.stream()
                    .filter(r -> !unreachable || Boolean.TRUE.equals(r.get("unreachable")))
                    .filter(r -> filter == null || filter.isEmpty() || String.valueOf(r.get("value")).toLowerCase(Locale.ROOT).contains(filter.toLowerCase(Locale.ROOT)))
                    .filter(r -> {
                        if (types == null || types.isEmpty()) return true;
                        Object tsObj = r.get("types");
                        if (tsObj instanceof List<?> ts) {
                            for (Object o : ts) {
                                if (o instanceof Map<?, ?> tm && types.contains(String.valueOf(tm.get("name")))) return true;
                            }
                        }
                        return types.contains(String.valueOf(r.get("type")));
                    }).toList());
            String mode;
            try { mode = dev.workbench.engine.StringSort.normalize(sort); }
            catch (IllegalArgumentException e) { throw OpException.syntax(e.getMessage(), "jadx"); }
            dev.workbench.engine.StringSort.sort(rows, mode);
            int from = Math.min(offset, rows.size()), end = from + Math.min(count <= 0 ? 200 : count, rows.size() - from);
            return Map.of("rows", new ArrayList<>(rows.subList(from, end)), "offset", offset, "count", end - from,
                    "total", rows.size(), "totalAll", all.size(), "truncated", end < rows.size(), "sort", mode);
        }
    }
    @Override public List<Object> stringXrefs(String id) throws OpException { synchronized (session) { return relations().stringRefs(id); } }
    @Override public List<Object> search(String query, Map<String, Object> opts) throws OpException {
        session.requireReady();
        synchronized (session) {
            if (!indexInitialized) {
                indexInitialized = true;
                if (index.tryWarmLoad() < 0) index.start();
            }
        }
        if (query == null || query.isBlank()) return List.of();
        Map<String, Object> options = opts == null ? Map.of() : opts;
        java.util.regex.Pattern pattern;
        try {
            pattern = java.util.regex.Pattern.compile(Boolean.parseBoolean(String.valueOf(options.get("regex")))
                    ? query : java.util.regex.Pattern.quote(query),
                    Boolean.parseBoolean(String.valueOf(options.get("case"))) ? 0 : java.util.regex.Pattern.CASE_INSENSITIVE);
        } catch (java.util.regex.PatternSyntaxException e) { throw OpException.syntax("bad regex: " + e.getMessage(), "jadx"); }
        Set<String> kinds = new HashSet<>(Arrays.asList(String.valueOf(options.getOrDefault("kind", "code,strings,comments")).split(",")));
        int limit = 200;
        if (options.get("limit") != null) {
            try { int requested = Integer.parseInt(String.valueOf(options.get("limit"))); if (requested > 0) limit = Math.min(requested, 5000); }
            catch (NumberFormatException e) { throw OpException.syntax("limit must be an integer", "jadx"); }
        }
        int hitCount = 0;
        List<Object> groups = new ArrayList<>();
        if (kinds.contains("code") || kinds.contains("comments")) for (var entry : index.queryEntries()) {
            List<Object> hits = new ArrayList<>();
            for (int i = 0; i < entry.lines().length && hitCount < limit; i++) {
                String text = entry.lines()[i], trimmed = text.trim();
                String kind = trimmed.startsWith("//") || trimmed.startsWith("/*") || trimmed.startsWith("*") ? "comments" : "code";
                if (!kinds.contains(kind) || !pattern.matcher(text).find()) continue;
                List<Object> context = new ArrayList<>();
                for (int j = Math.max(0, i - 3); j <= Math.min(entry.lines().length - 1, i + 3); j++)
                    context.add(Map.of("n", j + 1, "t", entry.lines()[j], "hit", i == j));
                hits.add(Map.of("kind", kind, "line", i + 1, "preview", trimmed, "addr", entry.addr(), "context", context));
                hitCount++;
            }
            if (!hits.isEmpty()) groups.add(Map.of("symbol", entry.fqcn(), "addr", entry.addr(), "engine", "jadx", "target", name, "hits", hits));
            if (hitCount >= limit) break;
        }
        if (kinds.contains("strings") && hitCount < limit) synchronized (session) {
            List<Object> hits = new ArrayList<>();
            for (Map<String, Object> row : relations().stringRows()) {
                if (!pattern.matcher(String.valueOf(row.get("value"))).find()) continue;
                String id = String.valueOf(row.get("id"));
                Map<String, Object> hit = new LinkedHashMap<>();
                hit.put("kind", "strings");
                hit.put("line", "s" + id);
                hit.put("preview", "\"" + row.get("value") + "\"");
                hit.put("stringId", id);
                List<Object> refs = relations().stringRefs(id);
                if (!refs.isEmpty() && refs.getFirst() instanceof Map<?, ?> ref0) {
                    Object refSym = ref0.get("method") != null ? ref0.get("method") : ref0.get("from");
                    if (refSym != null) hit.put("addr", String.valueOf(refSym).split("\\+", 2)[0]);
                    List<Object> ctx = new ArrayList<>();
                    for (int ri = 0; ri < Math.min(refs.size(), 10); ri++) {
                        if (refs.get(ri) instanceof Map<?, ?> r) {
                            String label = r.get("owner") != null && r.get("name") != null
                                    ? r.get("owner") + "." + r.get("name")
                                    : String.valueOf(r.get("from"));
                            ctx.add(Map.of("n", ri + 1, "t", label + " (" + r.get("from") + ")", "hit", ri == 0));
                        }
                    }
                    if (!ctx.isEmpty()) hit.put("context", ctx);
                }
                hits.add(hit);
                if (++hitCount >= limit) break;
            }
            if (!hits.isEmpty()) groups.add(Map.of("symbol", "(string literals)", "addr", "", "engine", "jadx", "target", name, "hits", hits));
        }
        return groups;
    }
    @Override public Map<String, Object> searchIndexStatus() { return index.status(); }
    @Override public void reindex() throws OpException {
        synchronized (session) { session.requireReady(); indexInitialized = true; }
        index.start();
    }
    @Override public boolean cancelTask(String ticket) {
        if (!index.ticket().equals(ticket)) return false;
        index.cancel(); return true;
    }
    @Override public List<String[]> classSnapshot() {
        synchronized (session) {
            try { return session.symbols().classes.stream().filter(ClassNode::isTopClass)
                    .map(c -> new String[] { JadxSymbols.address(c), c.getFullName() }).toList(); }
            catch (OpException e) { throw new IllegalStateException(e); }
        }
    }
    @Override public String decompileText(String addr) {
        synchronized (session) {
            try { return session.symbols().lookup(addr).owner().getTopParentClass().getCode().getCodeStr(); }
            catch (OpException e) { throw new IllegalStateException(e); }
        }
    }
    @Override public String classLabel(String addr) {
        synchronized (session) {
            try { return session.symbols().lookup(addr).owner().getFullName(); }
            catch (OpException e) { throw new IllegalStateException(e); }
        }
    }
    @Override public void evictDecompiled(String addr) {
        synchronized (session) {
            try { session.symbols().lookup(addr).owner().getTopParentClass().unloadFromCache(); }
            catch (OpException e) { /* close has already released the model */ }
        }
    }
    @Override public Map<String, Object> indexSourceIdentity() {
        Map<String, Object> identity = inputIdentity();
        if (identity == null) return Map.of("measured", false);
        return Map.of("measured", true, "artifact_sha256", identity.get("sha256"),
                "input_class", "File", "analysis_classes_at_open", classSnapshot().size());
    }
    @Override public Map<String, Object> rename(String symbol, String name, String actor) throws OpException { throw unsupported("rename"); }
    @Override public Map<String, Object> comment(String symbol, String text, String actor) throws OpException { throw unsupported("comment"); }
    private static OpException unsupported(String operation) { return OpException.unsupported(operation + " is not implemented in the read-only JADX adapter", "jadx"); }
    @Override public void close() { index.kill(); session.close(); synchronized (session) { relations = null; } }
}
