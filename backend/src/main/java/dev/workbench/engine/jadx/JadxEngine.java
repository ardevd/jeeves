package dev.workbench.engine.jadx;

import dev.workbench.engine.CheckpointedFileSave;
import dev.workbench.engine.Engine;
import dev.workbench.engine.TypeNames;
import dev.workbench.registry.OpException;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import jadx.api.ICodeInfo;
import jadx.api.data.IJavaCodeRef;
import jadx.api.data.IJavaNodeRef;
import jadx.api.data.impl.JadxCodeRef;
import jadx.api.data.impl.JadxNodeRef;
import jadx.api.metadata.ICodeAnnotation;
import jadx.api.metadata.annotations.NodeDeclareRef;
import jadx.api.metadata.annotations.VarNode;
import jadx.api.metadata.annotations.VarRef;
import jadx.api.plugins.input.data.IDebugInfo;
import jadx.api.plugins.input.data.ILocalVar;
import jadx.api.plugins.input.data.annotations.EncodedType;
import jadx.api.plugins.input.data.annotations.EncodedValue;
import jadx.api.plugins.input.data.annotations.IAnnotation;
import jadx.api.plugins.input.data.attributes.JadxAttrType;
import jadx.api.plugins.input.data.attributes.types.AnnotationsAttr;
import jadx.api.plugins.input.data.attributes.types.MethodParametersAttr;
import jadx.api.utils.CodeUtils;
import jadx.core.dex.attributes.AType;
import jadx.core.codegen.TypeGen;
import jadx.core.dex.attributes.nodes.MethodOverrideAttr;
import jadx.core.dex.instructions.args.RegisterArg;
import jadx.core.dex.nodes.*;

/** Stock JADX 1.5.6 engine adapter with write and sidecar persistence support. */
public final class JadxEngine implements Engine, dev.workbench.engine.index.SearchIndex.Host {
    private final String id, name, path;
    private final File sidecarFile;
    private final JadxSession session;
    private JadxRelations relations;
    private final dev.workbench.engine.index.SearchIndex index;
    private boolean indexInitialized;
    private volatile boolean dirty;
    private volatile boolean discarded;
    private volatile boolean replayPending;
    private volatile int replayFailed;
    private final AtomicLong mutationEpoch = new AtomicLong();
    private final AtomicLong savedEpoch = new AtomicLong();
    private volatile long saveStartedAt;
    private volatile long saveExpectBytes;
    private volatile Map<String, Object> lastSave;
    private volatile List<Map<String, Object>> pendingDirtyReplay = List.of();
    private volatile Map<String, Long> indexReplay = Map.of();
    private volatile boolean primedWithFile;
    private volatile int recoveredEdits;
    private final Set<String> contentHashes = ConcurrentHashMap.newKeySet();
    private final Map<String, String> originalLocalNames = new ConcurrentHashMap<>();

    public JadxEngine(String id, String name, String path, File artifact, File sidecar) throws OpException {
        this.id = id; this.name = name; this.path = path;
        this.sidecarFile = sidecar == null ? null : new File(sidecar, "jadx/code-data.json");
        if (sidecarFile != null && CheckpointedFileSave.marker(sidecarFile.toPath()).toFile().exists()) {
            throw OpException.io("An interrupted sidecar save has a recovery receipt. Check the previous process/lock and restore its verified checkpoint before opening: "
                    + CheckpointedFileSave.marker(sidecarFile.toPath()), "target=" + id);
        }
        session = new JadxSession(artifact, sidecarFile, this::onSessionReady);
        File indexDir = sidecar == null ? null : new File(sidecar, "jadx/search-index");
        index = new dev.workbench.engine.index.SearchIndex(this, "search-index:" + id,
                indexDir, dev.workbench.engine.Engines.jadxVersion());
    }

    private List<String> replaySince(long builtAtMs) {
        if (builtAtMs <= 0) return List.of();
        List<String> out = new ArrayList<>();
        for (Map.Entry<String, Long> entry : indexReplay.entrySet()) {
            if (entry.getValue() == null || entry.getValue() <= 0 || entry.getValue() > builtAtMs) {
                out.add(entry.getKey());
            }
        }
        return out;
    }

    private Set<String> topClassAddrsForReplay(Collection<String> addresses) throws OpException {
        JadxSymbols symbols = session.symbols();
        JadxRelations rels = relations();
        Set<String> out = new LinkedHashSet<>();
        for (String addr : addresses) {
            String base = addr;
            int plus = base.indexOf('+');
            if (plus > 0) base = base.substring(0, plus);
            int hash = base.indexOf('#');
            if (hash > 0) base = base.substring(0, hash);
            try {
                JadxSymbols.Symbol sym = symbols.lookup(base);
                out.add(JadxSymbols.currentAddress(sym.owner().getTopParentClass()));
                for (ClassNode refTop : rels.referrerTopClasses(sym.addr())) {
                    out.add(JadxSymbols.currentAddress(refTop));
                }
            } catch (OpException ignored) {}
        }
        return out;
    }

    private void onSessionReady() {
        synchronized (session) {
            if (session.isClosing()) return;
            if (sidecarFile != null && sidecarFile.isFile()) {
                recordSavedContentHash(sidecarFile);
            }
            Set<String> recovery = index.recoveryCandidates();
            long builtAt = index.tryWarmLoad();
            List<String> replay = replaySince(builtAt);
            boolean foreign = false;
            boolean onDemandIndex = "on-demand".equalsIgnoreCase(System.getenv("WORKBENCH_INDEX_MODE"));
            if (builtAt > 0 && sidecarFile != null && sidecarFile.isFile()) {
                long recorded = index.recordedPersistenceMtime();
                if (recorded > 0) foreign = sidecarFile.lastModified() != recorded;
                else foreign = sidecarFile.lastModified() > builtAt && replay.isEmpty();
                if (System.getenv("WORKBENCH_PARANOID") != null && sidecarFile.lastModified() > builtAt) {
                    foreign = true;
                }
            }
            if (!pendingDirtyReplay.isEmpty()) {
                replayPending = true;
                try {
                    recoveredEdits = applyDirtyReplay();
                } finally {
                    replayPending = false;
                }
                if (replayFailed > 0) {
                    index.kill();
                    throw new IllegalStateException("Recovery could not apply " + replayFailed
                            + " history edit(s); the sidecar was not saved. Preserve the checkpoint and history, correct the failed entries shown in the log, then reopen.");
                }
            }
            if (sidecarFile != null && !sidecarFile.isFile() && !session.isClosing() && !discarded) {
                try { writePersistenceFile(); } catch (OpException ignored) {}
            } else if (recoveredEdits > 0 && primedWithFile) {
                dirty = true;
            }
            if (indexInitialized || session.isClosing()) return;
            indexInitialized = true;
            if (builtAt <= 0 || foreign) {
                if (foreign) {
                    index.rejectWarm("Sidecar mtime witness changed outside this workbench");
                }
                if (!onDemandIndex) {
                    index.start();
                } else {
                    recovery.addAll(indexReplay.keySet());
                    try { index.invalidateAll(topClassAddrsForReplay(recovery)); } catch (OpException ignored) {}
                }
            } else if (!replay.isEmpty()) {
                try { index.invalidateAll(topClassAddrsForReplay(replay)); } catch (OpException ignored) {}
            }
        }
    }

    public void openAsync() { session.openAsync(); }

    @Override public void primeIndexReplay(Map<String, Long> addrTs) {
        indexReplay = addrTs == null ? Map.of() : Map.copyOf(addrTs);
    }

    @Override public long searchIndexBuiltAt() { return index.builtAtMs(); }

    @Override public long persistenceMtime() {
        return sidecarFile != null && sidecarFile.isFile() ? sidecarFile.lastModified() : -1;
    }

    @Override public Map<String, Object> caps() {
        Map<String, Object> caps = new LinkedHashMap<>();
        for (String key : List.of("decompile", "xrefs", "callgraph", "strings", "rename", "comment")) caps.put(key, true);
        for (String key : List.of("smali", "runs")) caps.put(key, false);
        caps.put("persist", "sidecar");
        return caps;
    }

    @Override public Map<String, Object> info() {
        Map<String, Object> info = session.status();
        info.put("id", id); info.put("name", name); info.put("path", path); info.put("engine", "JADX");
        info.put("caps", caps()); info.put("dirty", dirty); info.put("index", index.status());
        info.put("engineVersion", dev.workbench.engine.Engines.jadxVersion());
        if (recoveredEdits > 0) info.put("recoveredEdits", recoveredEdits);
        if (replayFailed > 0) info.put("replayFailed", replayFailed);
        return info;
    }

    private void markDirty() {
        dirty = true;
        mutationEpoch.incrementAndGet();
    }

    private static int parseHexOffset(String value) throws OpException {
        String text = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        try {
            if (text.startsWith("0x")) return Integer.parseUnsignedInt(text.substring(2), 16);
            if (text.endsWith("h")) return Integer.parseUnsignedInt(text.substring(0, text.length() - 1), 16);
            // Instruction suffixes are conventionally hexadecimal even without 0x.
            return Integer.parseUnsignedInt(text, 16);
        } catch (NumberFormatException error) {
            throw OpException.syntax("bad instruction offset: " + value, "jadx");
        }
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
        synchronized (session) {
            JadxSymbols.Symbol resolved = session.symbols().lookup(symbol);
            String comment = session.getComment(JadxSession.nodeRefFor(resolved.node()), null);
            return JadxSource.render(session.symbols(), resolved, opts, comment);
        }
    }
    @Override public Map<String, Object> tree(String filter) throws OpException { return tree(filter, 0); }
    @Override public Map<String, Object> tree(String filter, int limit) throws OpException {
        synchronized (session) {
            if (filter == null || filter.isBlank() || filter.startsWith("L")) return treeUnder(filter, limit);
            Map<String, Object> page = symbolSearchPage(filter, limit, "class");
            return Map.of("items", page.get("rows"), "total", page.get("total"), "truncated", page.get("truncated"));
        }
    }
    @Override public Map<String, Object> treeUnder(String prefix, int limit) throws OpException {
        synchronized (session) {
            JadxSymbols symbols = session.symbols();
            String p = prefix == null ? "" : prefix.trim();
            if (p.endsWith(";")) {
                JadxSymbols.Symbol s = symbols.lookup(p);
                Map<String, Object> row = s.row();
                row.put("children", symbols.byAddress.values().stream()
                        .filter(x -> !x.kind().equals("class") && x.owner() == s.owner())
                        .map(JadxSymbols.Symbol::row).toList());
                return Map.of("items", List.of(row), "total", 1, "truncated", false);
            }
            if (!p.startsWith("L")) p = "L" + p.replace('.', '/');
            if (!p.equals("L") && !p.endsWith("/")) p += "/";
            Map<String, Integer> packages = new TreeMap<>(); List<Object> classes = new ArrayList<>();
            for (ClassNode cls : symbols.classes) {
                String origAddr = JadxSymbols.address(cls);
                String curAddr = JadxSymbols.currentAddress(cls);
                String matchAddr = curAddr.startsWith(p) ? curAddr : origAddr.startsWith(p) ? origAddr : null;
                if (matchAddr == null) continue;
                String rest = matchAddr.substring(p.length()); int slash = rest.indexOf('/');
                if (slash >= 0) packages.merge(p + rest.substring(0, slash + 1), 1, Integer::sum);
                else classes.add(symbols.byAddress.get(origAddr).row());
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
            String raw = symbol == null ? "" : symbol.trim();
            Integer insnOffset = null;
            java.util.regex.Matcher sm = java.util.regex.Pattern.compile("^(.*->[^+]+)\\+(0x[0-9A-Fa-f]+|[0-9A-Fa-f]+h?)$").matcher(raw);
            String baseSym = raw;
            if (sm.matches()) {
                baseSym = sm.group(1);
                insnOffset = parseHexOffset(sm.group(2));
            }
            Map<String, Object> row = new LinkedHashMap<>(resolve(baseSym));
            String comment = "";
            if (offset > 0) {
                var selected = session.symbols().lookup(baseSym);
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
                    if (node instanceof jadx.api.JavaClass jc)
                        comment = session.getComment(JadxSession.nodeRefFor(jc.getClassNode()), null);
                    else if (node instanceof jadx.api.JavaMethod jm)
                        comment = session.getComment(JadxSession.nodeRefFor(jm.getMethodNode()), null);
                    else if (node instanceof jadx.api.JavaField jf)
                        comment = session.getComment(JadxSession.nodeRefFor(jf.getFieldNode()), null);
                } else throw OpException.notFound("no semantic symbol at source offset", "jadx");
            } else if (!Boolean.TRUE.equals(row.get("external"))) {
                JadxSymbols.Symbol selected = session.symbols().lookup(baseSym);
                JadxNodeRef nodeRef = JadxSession.nodeRefFor(selected.node());
                JadxCodeRef codeRef = insnOffset != null ? JadxCodeRef.forInsn(insnOffset) : null;
                comment = session.getComment(nodeRef, codeRef);
            }
            row.put("offset", offset);
            row.put("comment", comment == null ? "" : comment);
            return row;
        }
    }
    private JadxRelations relations() throws OpException {
        session.requireReady(); if (relations == null) relations = new JadxRelations(session.symbols()); return relations;
    }
    @Override public List<Object> xrefsTo(String symbol) throws OpException {
        synchronized (session) { return relations().to( String.valueOf(resolve(symbol).get("origSig")) ); }
    }
    @Override public List<Object> xrefsFrom(String symbol) throws OpException {
        synchronized (session) { return relations().from(session.symbols().lookup(symbol).addr(), false); }
    }
    @Override public List<Object> callees(String symbol) throws OpException {
        synchronized (session) { return relations().from(session.symbols().lookup(symbol).addr(), true); }
    }
    @Override public List<Object> callers(String symbol) throws OpException {
        synchronized (session) { return relations().callers(String.valueOf(resolve(symbol).get("origSig"))); }
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
            result.put("super", c.getSuperClass() == null ? null : symbols.currentTypeSig(c.getSuperClass()));
            result.put("interfaces", c.getInterfaces().stream().map(symbols::currentTypeSig).toList());
            result.put("flags", c.getAccessFlags().makeString(false).trim()); result.put("methods", c.getMethods().size());
            result.put("fields", c.getFields().size()); result.put("innerClasses", c.getInnerClasses().stream().map(JadxSymbols::currentAddress).toList());
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
                    .map(c -> new String[] { JadxSymbols.currentAddress(c), c.getFullName() }).toList(); }
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

    private static void evictClass(ClassNode top) {
        ClassNode t = top.getTopParentClass();
        t.unloadFromCache();
        clearCodeCommentsAttr(t);
        if (t.getState() == ProcessState.PROCESS_COMPLETE || t.getState() == ProcessState.GENERATED_AND_UNLOADED) {
            t.unload();
            t.setState(ProcessState.NOT_LOADED);
        }
    }

    private static void clearCodeCommentsAttr(ClassNode cls) {
        cls.remove(AType.CODE_COMMENTS);
        for (FieldNode f : cls.getFields()) f.remove(AType.CODE_COMMENTS);
        for (MethodNode m : cls.getMethods()) m.remove(AType.CODE_COMMENTS);
        for (ClassNode inner : cls.getInnerClasses()) clearCodeCommentsAttr(inner);
    }

    private static String methodOrigAddr(MethodNode m) {
        return JadxSymbols.address(m.getParentClass()) + "->" + m.getMethodInfo().getShortId();
    }

    private static boolean sameParamTypes(MethodNode a, MethodNode b) {
        return Objects.equals(a.getMethodInfo().getArgumentsTypes(), b.getMethodInfo().getArgumentsTypes());
    }

    private String paramDesc(MethodNode m) throws OpException {
        JadxSymbols symbols = session.symbols();
        StringBuilder sb = new StringBuilder("(");
        for (var arg : m.getMethodInfo().getArgumentsTypes()) sb.append(symbols.currentTypeSig(arg));
        return sb.append(')').toString();
    }

    private List<MethodNode> overrideFamily(MethodNode method) throws OpException {
        if (method.getAccessFlags().isStatic() || method.getAccessFlags().isPrivate()
                || method.isConstructor() || method.getName().startsWith("<")) {
            return List.of(method);
        }
        Set<MethodNode> result = new LinkedHashSet<>();
        result.add(method);
        MethodOverrideAttr attr = method.get(AType.METHOD_OVERRIDE);
        if (attr != null && attr.getRelatedMthNodes() != null) {
            result.addAll(attr.getRelatedMthNodes());
        }
        // Also walk connected hierarchy in symbols.classes in case MethodOverrideAttr was not populated on an unloaded class
        JadxSymbols symbols = session.symbols();
        Map<String, ClassNode> byRaw = new HashMap<>();
        for (ClassNode c : symbols.classes) byRaw.put(c.getClassInfo().getRawName(), c);
        String shortId = method.getMethodInfo().getShortId();
        Set<ClassNode> visited = new LinkedHashSet<>();
        Deque<ClassNode> queue = new ArrayDeque<>();
        for (MethodNode m : result) {
            if (visited.add(m.getParentClass())) queue.add(m.getParentClass());
        }
        while (!queue.isEmpty()) {
            ClassNode cur = queue.removeFirst();
            // Superclass and interfaces of cur
            if (cur.getSuperClass() != null) {
                ClassNode sup = byRaw.get(cur.getSuperClass().getObject());
                if (sup != null && visited.add(sup)) queue.add(sup);
            }
            for (var ifaceType : cur.getInterfaces()) {
                ClassNode iface = byRaw.get(ifaceType.getObject());
                if (iface != null && visited.add(iface)) queue.add(iface);
            }
            // Subclasses/implementors of cur
            String curRaw = cur.getClassInfo().getRawName();
            for (ClassNode sub : symbols.classes) {
                boolean extendsCur = sub.getSuperClass() != null && curRaw.equals(sub.getSuperClass().getObject());
                if (!extendsCur) {
                    for (var it : sub.getInterfaces()) {
                        if (curRaw.equals(it.getObject())) { extendsCur = true; break; }
                    }
                }
                if (extendsCur && visited.add(sub)) queue.add(sub);
            }
        }
        for (ClassNode c : visited) {
            for (MethodNode cand : c.getMethods()) {
                if (cand.getAccessFlags().isStatic() || cand.getAccessFlags().isPrivate()
                        || cand.isConstructor() || cand.getName().startsWith("<")) continue;
                if (cand.getMethodInfo().getShortId().equals(shortId)) result.add(cand);
            }
        }
        return List.copyOf(result);
    }

    private Set<ClassNode> hierarchyClasses(ClassNode root) throws OpException {
        JadxSymbols symbols = session.symbols();
        Map<String, ClassNode> byRaw = new HashMap<>();
        for (ClassNode c : symbols.classes) byRaw.put(c.getClassInfo().getRawName(), c);
        Set<ClassNode> out = new LinkedHashSet<>();
        out.add(root);
        // Walk ancestors
        Deque<ClassNode> up = new ArrayDeque<>();
        up.add(root);
        while (!up.isEmpty()) {
            ClassNode cur = up.removeFirst();
            if (cur.getSuperClass() != null) {
                ClassNode sup = byRaw.get(cur.getSuperClass().getObject());
                if (sup != null && out.add(sup)) up.add(sup);
            }
            for (var it : cur.getInterfaces()) {
                ClassNode iface = byRaw.get(it.getObject());
                if (iface != null && out.add(iface)) up.add(iface);
            }
        }
        // Walk descendants of root
        Set<String> downNames = new HashSet<>();
        downNames.add(root.getClassInfo().getRawName());
        boolean changed;
        do {
            changed = false;
            for (ClassNode c : symbols.classes) {
                boolean child = c.getSuperClass() != null && downNames.contains(c.getSuperClass().getObject());
                if (!child) {
                    for (var it : c.getInterfaces()) {
                        if (downNames.contains(it.getObject())) { child = true; break; }
                    }
                }
                if (child && downNames.add(c.getClassInfo().getRawName())) {
                    out.add(c);
                    changed = true;
                }
            }
        } while (changed);
        return out;
    }

    private static Map<String, Object> nameOccupant(JadxSymbols.Symbol otherSym, String kind, String match, String affectedOriginalSymbol) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("kind", kind);
        row.put("originalSymbol", otherSym.addr());
        row.put("addr", otherSym.currentAddr());
        row.put("currentName", otherSym.name());
        row.put("originalName", otherSym.originalName());
        row.put("match", match);
        if (!"class".equals(kind)) row.put("owner", JadxSymbols.currentAddress(otherSym.owner()));
        if (affectedOriginalSymbol != null) row.put("affectedOriginalSymbol", affectedOriginalSymbol);
        return row;
    }

    private static OpException renameRefused(String old, String wanted, String original, String addr,
                                             List<Map<String, Object>> conflicts) {
        String occupied = conflicts.stream().map(row -> String.valueOf(row.get("originalSymbol"))
                + " (current: " + row.get("addr") + ", name: " + row.get("currentName") + ")")
                .collect(java.util.stream.Collectors.joining("; "));
        OpException error = OpException.busy("rename refused by JADX: '" + old + "' → '" + wanted + "' did not apply — "
                + (conflicts.isEmpty() ? "no occupying symbol could be identified"
                        : "the requested name is used by " + occupied), "symbol=" + addr + " name=" + wanted);
        error.extra.put("originalSymbol", original);
        error.extra.put("currentName", old);
        error.extra.put("requestedName", wanted);
        error.extra.put("conflicts", conflicts);
        error.extra.put("collisionLookup", conflicts.isEmpty() ? "unresolved" : "matched");
        error.extra.put("hint", "Inspect conflicts by originalSymbol; choose a distinct evidence-backed name for your symbol. Do not rename the occupying symbol merely to free its name.");
        return error;
    }

    private static boolean referencesClassType(jadx.core.dex.instructions.args.ArgType type, String rawClassName) {
        if (type == null) return false;
        if (type.isArray()) return referencesClassType(type.getArrayElement(), rawClassName);
        if (type.isObject()) {
            String obj = type.getObject();
            if (rawClassName.equals(obj) || (obj != null && obj.startsWith(rawClassName + "$"))) return true;
            if (type.getGenericTypes() != null) {
                for (var gt : type.getGenericTypes()) {
                    if (referencesClassType(gt, rawClassName)) return true;
                }
            }
        }
        return false;
    }

    private static boolean classSignatureReferences(ClassNode candidate, String rawClassName) {
        if (referencesClassType(candidate.getSuperClass(), rawClassName)) return true;
        for (var iface : candidate.getInterfaces()) {
            if (referencesClassType(iface, rawClassName)) return true;
        }
        for (FieldNode f : candidate.getFields()) {
            if (referencesClassType(f.getType(), rawClassName)) return true;
        }
        for (MethodNode m : candidate.getMethods()) {
            if (referencesClassType(m.getReturnType(), rawClassName)) return true;
            for (var arg : m.getArgTypes()) {
                if (referencesClassType(arg, rawClassName)) return true;
            }
        }
        return false;
    }

    private Set<ClassNode> affectedTopClasses(Collection<JadxSymbols.Symbol> changed) throws OpException {
        JadxSymbols symbols = session.symbols();
        JadxRelations rels = relations();
        Set<ClassNode> tops = new LinkedHashSet<>();
        for (JadxSymbols.Symbol s : changed) {
            ClassNode top = s.owner().getTopParentClass();
            tops.add(top);
            tops.addAll(rels.referrerTopClasses(s.addr()));
            if (s.node() instanceof ClassNode renamedClass) {
                String rawName = renamedClass.getClassInfo().getRawName();
                for (JadxSymbols.Symbol member : symbols.byAddress.values()) {
                    if (member.owner().getTopParentClass() == top) {
                        tops.addAll(rels.referrerTopClasses(member.addr()));
                    }
                }
                for (ClassNode candidate : symbols.classes) {
                    if (classSignatureReferences(candidate, rawName)) {
                        tops.add(candidate.getTopParentClass());
                    }
                }
            }
        }
        return tops;
    }

    @Override public List<Map<String, Object>> renameTargets(String symbol) throws OpException {
        synchronized (session) {
            JadxSymbols symbols = session.symbols();
            JadxSymbols.Symbol sym = symbols.lookup(symbol);
            if (sym.node() instanceof MethodNode m) {
                List<Map<String, Object>> out = new ArrayList<>();
                for (MethodNode fm : overrideFamily(m)) {
                    JadxSymbols.Symbol fs = symbols.byAddress.get(methodOrigAddr(fm));
                    if (fs != null) {
                        Map<String, Object> row = new LinkedHashMap<>();
                        row.put("origSig", fs.addr());
                        row.put("addr", fs.currentAddr());
                        row.put("name", fs.name());
                        row.put("oldName", fs.name());
                        row.put("kind", "method");
                        out.add(row);
                    }
                }
                if (!out.isEmpty()) return out;
            }
            Map<String, Object> row = new LinkedHashMap<>(resolve(symbol));
            row.putIfAbsent("oldName", sym.name());
            return List.of(row);
        }
    }

    private List<Map<String, Object>> collectRenameConflicts(JadxSymbols symbols, JadxSymbols.Symbol sym,
                                                             String newName, List<JadxSymbols.Symbol> changedSymbols) throws OpException {
        String origSig = sym.addr();
        List<Map<String, Object>> conflicts = new ArrayList<>();
        if (sym.node() instanceof FieldNode f) {
            for (FieldNode other : sym.owner().getFields()) {
                if (other != f && newName.equals(other.getAlias())) {
                    JadxSymbols.Symbol otherSym = symbols.byAddress.get(JadxSymbols.address(sym.owner()) + "->" + other.getFieldInfo().getShortId());
                    conflicts.add(nameOccupant(otherSym, "field", "same_class_field", null));
                }
            }
            changedSymbols.add(sym);
        } else if (sym.node() instanceof MethodNode m) {
            List<MethodNode> family = overrideFamily(m);
            Set<MethodNode> familySet = new HashSet<>(family);
            Map<String, Map<String, Object>> foundConflicts = new LinkedHashMap<>();
            for (MethodNode fm : family) {
                String fmOrig = methodOrigAddr(fm);
                for (MethodNode other : fm.getParentClass().getMethods()) {
                    if (familySet.contains(other)) continue;
                    if (newName.equals(other.getAlias()) && sameParamTypes(fm, other)) {
                        JadxSymbols.Symbol otherSym = symbols.byAddress.get(methodOrigAddr(other));
                        foundConflicts.put(otherSym.addr(), nameOccupant(otherSym, "method", "same_class_method_descriptor", fmOrig));
                    }
                }
            }
            if (foundConflicts.isEmpty() && !m.getAccessFlags().isStatic() && !m.getAccessFlags().isPrivate()) {
                Set<ClassNode> scopeClasses = new LinkedHashSet<>();
                for (MethodNode fm : family) scopeClasses.addAll(hierarchyClasses(fm.getParentClass()));
                for (ClassNode hc : scopeClasses) {
                    for (MethodNode other : hc.getMethods()) {
                        if (familySet.contains(other) || other.getAccessFlags().isStatic()
                                || other.getAccessFlags().isPrivate() || other.isConstructor()
                                || other.getName().startsWith("<")) continue;
                        if (newName.equals(other.getAlias()) && sameParamTypes(m, other)) {
                            JadxSymbols.Symbol otherSym = symbols.byAddress.get(methodOrigAddr(other));
                            foundConflicts.put(otherSym.addr(), nameOccupant(otherSym, "method", "override_hierarchy_method_descriptor", origSig));
                        }
                    }
                }
            }
            conflicts.addAll(foundConflicts.values());
            for (MethodNode fm : family) {
                JadxSymbols.Symbol fs = symbols.byAddress.get(methodOrigAddr(fm));
                if (fs != null) changedSymbols.add(fs);
            }
        } else if (sym.node() instanceof ClassNode c) {
            String targetDesc;
            if (c.isTopClass()) {
                String pkg = c.getClassInfo().getAliasPkg();
                String targetRaw = pkg.isEmpty() ? newName : pkg + "." + newName;
                targetDesc = "L" + targetRaw.replace('.', '/') + ";";
            } else {
                String parentRaw = c.getParentClass().getClassInfo().makeAliasRawFullName();
                targetDesc = "L" + parentRaw.replace('.', '/') + "$" + newName + ";";
            }
            for (ClassNode other : symbols.classes) {
                if (other != c && targetDesc.equals(JadxSymbols.currentAddress(other))) {
                    JadxSymbols.Symbol otherSym = symbols.byAddress.get(JadxSymbols.address(other));
                    conflicts.add(nameOccupant(otherSym, "class", "destination_class", null));
                }
            }
            changedSymbols.add(sym);
        }
        return conflicts;
    }

    @Override public Map<String, Object> rename(String symbol, String newName, String actor) throws OpException {
        session.requireReady();
        if (newName == null || !newName.matches("[A-Za-z_$][A-Za-z0-9_$]*"))
            throw OpException.syntax("bad Java identifier: " + newName, "symbol=" + symbol);
        synchronized (session) {
            if (discarded) throw OpException.busy("this target is being closed with its edits discarded — rename refused", "target=" + id);
            JadxSymbols symbols = session.symbols();
            JadxSymbols.Symbol sym = symbols.lookup(symbol);
            String oldAddr = sym.currentAddr();
            String origSig = sym.addr();
            String oldName = sym.name();
            if (sym.kind().equals("method") && (origSig.contains("-><init>(") || origSig.contains("-><clinit>(")))
                throw OpException.syntax("a constructor cannot be renamed (<init> / <clinit> are the class's own) — name the class instead",
                        "symbol=" + symbol);

            List<Map<String, Object>> affected = renameTargets(symbol);
            if (!affected.isEmpty() && affected.stream().allMatch(member -> newName.equals(member.get("name")))) {
                Map<String, Object> noop = new LinkedHashMap<>();
                noop.put("addr", oldAddr); noop.put("origSig", origSig);
                noop.put("newAddr", oldAddr);
                noop.put("kind", sym.kind());
                noop.put("oldName", newName); noop.put("newName", newName);
                noop.put("applied", false); noop.put("unchanged", true);
                noop.put("reason", "Every affected symbol already has this name.");
                return noop;
            }

            List<JadxSymbols.Symbol> changedSymbols = new ArrayList<>();
            List<Map<String, Object>> conflicts = collectRenameConflicts(symbols, sym, newName, changedSymbols);

            if (!conflicts.isEmpty()) {
                throw renameRefused(oldName, newName, origSig, oldAddr, conflicts);
            }

            Set<ClassNode> affectedTops = affectedTopClasses(changedSymbols);
            Map<ClassNode, String> oldTopAddrs = new LinkedHashMap<>();
            for (ClassNode top : affectedTops) oldTopAddrs.put(top, JadxSymbols.currentAddress(top));

            for (JadxSymbols.Symbol cs : changedSymbols) {
                JadxNodeRef ref = JadxSession.nodeRefFor(cs.node());
                if (newName.equals(cs.originalName())) {
                    removeRenameEntry(ref, null);
                    clearNodeAlias(cs.node());
                } else {
                    putRenameEntry(ref, null, newName);
                    setNodeAlias(cs.node(), newName);
                }
            }
            session.decompiler().reloadCodeData();
            for (var entry : oldTopAddrs.entrySet()) {
                evictClass(entry.getKey());
                index.invalidate(entry.getValue(), JadxSymbols.currentAddress(entry.getKey()));
            }
            markDirty();

            String newAddr = sym.currentAddr();
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("symbol", Map.of("addr", oldAddr, "kind", sym.kind(), "name", oldName));
            out.put("addr", oldAddr); out.put("origSig", origSig);
            out.put("newAddr", newAddr); out.put("kind", sym.kind());
            out.put("oldName", oldName); out.put("newName", newName);
            out.put("applied", true);
            out.put("actor", actor == null ? "human" : actor);
            return out;
        }
    }

    @Override public Map<String, Object> restoreOriginalName(String symbol, String expectedOriginal, String actor) throws OpException {
        session.requireReady();
        synchronized (session) {
            if (discarded) throw OpException.busy("this target is being closed with its edits discarded — rename refused", "target=" + id);
            JadxSymbols symbols = session.symbols();
            JadxSymbols.Symbol sym = symbols.lookup(symbol);
            String oldAddr = sym.currentAddr();
            String origSig = sym.addr();
            String oldName = sym.name();
            String origName = sym.originalName();
            if (expectedOriginal != null && !expectedOriginal.equals(origName))
                throw OpException.syntax("the recorded original name '" + expectedOriginal + "' is not the dex's own for "
                        + oldAddr + " (that is '" + origName + "') — not restored", "symbol=" + symbol);

            List<JadxSymbols.Symbol> changedSymbols = new ArrayList<>();
            List<Map<String, Object>> conflicts = collectRenameConflicts(symbols, sym, origName, changedSymbols);

            boolean wasRenamed = changedSymbols.stream().anyMatch(JadxSymbols.Symbol::isRenamed);
            if (wasRenamed) {
                if (!conflicts.isEmpty()) {
                    throw renameRefused(oldName, origName, origSig, oldAddr, conflicts);
                }
                Set<ClassNode> affectedTops = affectedTopClasses(changedSymbols);
                Map<ClassNode, String> oldTopAddrs = new LinkedHashMap<>();
                for (ClassNode top : affectedTops) oldTopAddrs.put(top, JadxSymbols.currentAddress(top));
                for (JadxSymbols.Symbol cs : changedSymbols) {
                    removeRenameEntry(JadxSession.nodeRefFor(cs.node()), null);
                    clearNodeAlias(cs.node());
                }
                session.decompiler().reloadCodeData();
                for (var entry : oldTopAddrs.entrySet()) {
                    evictClass(entry.getKey());
                    index.invalidate(entry.getValue(), JadxSymbols.currentAddress(entry.getKey()));
                }
                markDirty();
            }

            String newAddr = sym.currentAddr();
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("symbol", Map.of("addr", oldAddr, "kind", sym.kind(), "name", oldName));
            out.put("addr", oldAddr); out.put("origSig", origSig);
            out.put("newAddr", newAddr); out.put("kind", sym.kind());
            out.put("oldName", oldName); out.put("newName", origName);
            out.put("applied", wasRenamed);
            out.put("restored", true);
            if (!wasRenamed) out.put("note", "already the original name — nothing changed");
            out.put("actor", actor == null ? "human" : actor);
            return out;
        }
    }

    private void putRenameEntry(IJavaNodeRef nodeRef, IJavaCodeRef codeRef, String newName) {
        List<jadx.api.data.ICodeRename> list = new ArrayList<>(session.codeData().getRenames());
        list.removeIf(r -> Objects.equals(r.getNodeRef(), nodeRef) && Objects.equals(r.getCodeRef(), codeRef));
        if (newName != null && !newName.isEmpty()) {
            list.add(new jadx.api.data.impl.JadxCodeRename(nodeRef, codeRef, newName));
        }
        session.codeData().setRenames(list);
    }

    private void removeRenameEntry(IJavaNodeRef nodeRef, IJavaCodeRef codeRef) {
        List<jadx.api.data.ICodeRename> list = new ArrayList<>(session.codeData().getRenames());
        list.removeIf(r -> Objects.equals(r.getNodeRef(), nodeRef) && Objects.equals(r.getCodeRef(), codeRef));
        session.codeData().setRenames(list);
    }

    private static void setNodeAlias(Object node, String newName) {
        if (node instanceof ClassNode c) c.getClassInfo().changeShortName(newName);
        else if (node instanceof MethodNode m) m.getMethodInfo().setAlias(newName);
        else if (node instanceof FieldNode f) f.getFieldInfo().setAlias(newName);
    }

    private static void clearNodeAlias(Object node) {
        if (node instanceof ClassNode c) c.getClassInfo().removeAlias();
        else if (node instanceof MethodNode m) m.getMethodInfo().removeAlias();
        else if (node instanceof FieldNode f) f.getFieldInfo().removeAlias();
    }

    private record LocalInfo(VarNode node, String name, String type, int line, Integer parameterIndex) {
        String key() { return node.getReg() + ":" + node.getSsa(); }
        JadxCodeRef codeRef() { return parameterIndex == null
                ? JadxCodeRef.forVar(node.getReg(), node.getSsa())
                : JadxCodeRef.forMthArg(parameterIndex); }
    }

    private MethodNode methodNode(String symbol) throws OpException {
        JadxSymbols.Symbol resolved = session.symbols().lookup(symbol);
        if (!(resolved.node() instanceof MethodNode method))
            throw OpException.syntax("this operation takes a METHOD: " + symbol + " is a " + resolved.kind(), "symbol=" + symbol);
        return method;
    }

    /** Process the owning class and read the variable declarations emitted into JADX's code metadata. */
    private List<LocalInfo> localInfos(MethodNode method) throws OpException {
        JadxSymbols symbols = session.symbols();
        ICodeInfo code = method.getTopParentClass().getCode();
        Map<String, Integer> parameterIndices = new HashMap<>();
        List<VarNode> args = method.collectArgNodes();
        for (int i = 0; i < args.size(); i++) parameterIndices.put(args.get(i).getReg() + ":" + args.get(i).getSsa(), i);
        Map<String, LocalInfo> vars = new LinkedHashMap<>();
        for (Map.Entry<Integer, ICodeAnnotation> entry : code.getCodeMetadata().getAsMap().entrySet()) {
            VarNode var = null;
            ICodeAnnotation ann = entry.getValue();
            if (ann instanceof NodeDeclareRef declaration && declaration.getNode() instanceof VarNode node) var = node;
            else if (ann instanceof VarNode node) var = node;
            if (var == null || var.getMth() != method) continue;
            String key = var.getReg() + ":" + var.getSsa();
            Integer parameterIndex = parameterIndices.get(key);
            String type = symbols.currentTypeSig(var.getType());
            int line = CodeUtils.getLineNumForPos(code.getCodeStr(), entry.getKey(), System.lineSeparator());
            vars.putIfAbsent(key, new LocalInfo(var, var.getName(), type, line, parameterIndex));
        }
        // An unused formal argument can have no declaration occurrence in the body metadata.
        for (int i = 0; i < args.size(); i++) {
            VarNode var = args.get(i);
            String key = var.getReg() + ":" + var.getSsa();
            vars.putIfAbsent(key, new LocalInfo(var, var.getName(), symbols.currentTypeSig(var.getType()), 0, i));
        }
        return new ArrayList<>(vars.values());
    }

    private LocalInfo selectedLocal(MethodNode method, String local) throws OpException {
        List<LocalInfo> matches = localInfos(method).stream().filter(v -> Objects.equals(v.name(), local)).toList();
        if (matches.isEmpty()) throw OpException.notFound("no local named '" + local + "' in " + methodOrigAddr(method), "target=" + id);
        if (matches.size() > 1) throw OpException.syntax("local name '" + local + "' is ambiguous in " + methodOrigAddr(method)
                + " — rename one through its stable identity first", "symbol=" + methodOrigAddr(method));
        return matches.get(0);
    }

    private String localOriginalMethod(MethodNode method) { return methodOrigAddr(method); }
    private String localStableId(MethodNode method, LocalInfo local) {
        return localOriginalMethod(method) + "#local:r" + local.node().getReg() + ":s" + local.node().getSsa();
    }

    @Override public List<Object> locals(String methodSymbol) throws OpException {
        session.requireReady();
        synchronized (session) {
            MethodNode method = methodNode(methodSymbol);
            List<Object> out = new ArrayList<>();
            for (LocalInfo local : localInfos(method)) {
                String stable = localStableId(method, local);
                String original = originalLocalNames.computeIfAbsent(stable, ignored -> local.name());
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("name", local.name());
                row.put("type", local.type());
                row.put("line", local.line());
                row.put("originalMethod", localOriginalMethod(method));
                row.put("originalSymbol", stable);
                row.put("originalName", original);
                row.put("register", local.node().getReg());
                row.put("ssa", local.node().getSsa());
                row.put("renameableLocal", true);
                if (local.parameterIndex() != null) row.put("parameterIndex", local.parameterIndex());
                out.add(row);
            }
            return out;
        }
    }

    @Override public Map<String, Object> resolveLocal(String methodSymbol, String local) throws OpException {
        session.requireReady();
        if (local == null || local.isBlank()) throw OpException.syntax("local names the variable to resolve", "symbol=" + methodSymbol);
        synchronized (session) {
            MethodNode method = methodNode(methodSymbol);
            LocalInfo selected = selectedLocal(method, local);
            return Map.of("name", selected.name(), "originalMethod", localOriginalMethod(method),
                    "originalSymbol", localStableId(method, selected));
        }
    }

    @Override public Map<String, Object> renameLocal(String methodSymbol, String local, String newName, String actor) throws OpException {
        session.requireReady();
        if (!Engine.usableName(newName)) throw OpException.syntax("bad Java identifier: " + newName, "symbol=" + methodSymbol);
        if (local == null || local.isBlank()) throw OpException.syntax("local names the variable to rename (its current name)", "symbol=" + methodSymbol);
        synchronized (session) {
            if (discarded) throw OpException.busy("this target is being closed with its edits discarded — rename refused", "target=" + id);
            MethodNode method = methodNode(methodSymbol);
            LocalInfo selected = selectedLocal(method, local);
            if (newName.equals(selected.name())) {
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("addr", session.symbols().byAddress.get(methodOrigAddr(method)).currentAddr());
                out.put("newAddr", out.get("addr")); out.put("kind", "local"); out.put("local", local);
                out.put("oldName", local); out.put("newName", newName); out.put("applied", false);
                out.put("unchanged", true); out.put("reason", "This variable already has this name.");
                return out;
            }
            List<LocalInfo> vars = localInfos(method);
            if (vars.stream().anyMatch(v -> v != selected && newName.equals(v.name())))
                throw OpException.syntax("local rename refused: '" + newName + "' is already used in this method", "symbol=" + methodSymbol);
            String stable = localStableId(method, selected);
            originalLocalNames.putIfAbsent(stable, selected.name());
            JadxNodeRef ref = JadxSession.nodeRefFor(method);
            putRenameEntry(ref, selected.codeRef(), newName);
            session.decompiler().reloadCodeData();
            String topAddr = JadxSymbols.currentAddress(method.getTopParentClass());
            evictClass(method.getTopParentClass());
            index.invalidate(topAddr, topAddr);
            markDirty();
            String addr = session.symbols().byAddress.get(methodOrigAddr(method)).currentAddr();
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("symbol", Map.of("addr", addr, "kind", "method", "name", method.getAlias()));
            out.put("addr", addr); out.put("newAddr", addr); out.put("kind", "local"); out.put("local", local);
            out.put("oldName", selected.name()); out.put("newName", newName); out.put("applied", true);
            out.put("actor", actor == null ? "human" : actor);
            return out;
        }
    }

    @Override public Map<String, Object> autoNameLocals(String methodSymbol, boolean all, String actor) throws OpException {
        session.requireReady();
        synchronized (session) {
            if (discarded) throw OpException.busy("this target is being closed with its edits discarded — rename refused", "target=" + id);
            MethodNode method = methodNode(methodSymbol);
            List<Object> renamed = new ArrayList<>(), skipped = new ArrayList<>();
            Set<String> used = new HashSet<>();
            for (LocalInfo v : localInfos(method)) used.add(v.name());
            // Apply one at a time because each reload constructs new VarNode identities.
            List<String> candidates = localInfos(method).stream().filter(v -> v.parameterIndex() == null)
                    .map(LocalInfo::name).distinct().toList();
            for (String old : candidates) {
                LocalInfo v;
                try { v = selectedLocal(method, old); } catch (OpException missing) { continue; }
                if (!all && !v.name().matches("(?:v|r|var|tmp)\\d+(?:_\\d+)?")) continue;
                String base = TypeNames.nameForType(v.type());
                if (base == null) { skipped.add(Map.of("local", old, "why", v.type() == null ? "no type" : "primitive " + v.type())); continue; }
                String pick = null;
                for (int suffix = 1; suffix <= 20; suffix++) {
                    String proposed = suffix == 1 ? base : base + "_" + suffix;
                    if (!used.contains(proposed)) { pick = proposed; break; }
                }
                if (pick == null) { skipped.add(Map.of("local", old, "why", "no free name for " + base)); continue; }
                renameLocal(methodSymbol, v.name(), pick, actor);
                used.remove(old); used.add(pick);
                Map<String, Object> row = new LinkedHashMap<>(); row.put("local", old); row.put("name", pick); row.put("type", v.type()); renamed.add(row);
            }
            String addr = session.symbols().byAddress.get(methodOrigAddr(method)).currentAddr();
            Map<String, Object> out = new LinkedHashMap<>(); out.put("addr", addr); out.put("renames", renamed); out.put("skipped", skipped);
            out.put("actor", actor == null ? "human" : actor); return out;
        }
    }

    private static List<String> decodeParameterFlags(int flags) {
        List<String> out = new ArrayList<>();
        if ((flags & 0x0010) != 0) out.add("final");
        if ((flags & 0x1000) != 0) out.add("synthetic");
        if ((flags & 0x8000) != 0) out.add("mandated");
        int unknown = flags & ~(0x0010 | 0x1000 | 0x8000);
        if (unknown != 0) out.add("0x" + Integer.toHexString(unknown));
        return out;
    }

    private List<String> parameterNames(MethodNode method) {
        method.getTopParentClass().getCode();
        List<VarNode> args = method.collectArgNodes();
        List<String> names = new ArrayList<>();
        for (int i = 0; i < method.getArgTypes().size(); i++) names.add(i < args.size() ? args.get(i).getName() : "arg" + i);
        return names;
    }

    @Override public Map<String, Object> methodParameters(String methodSymbol) throws OpException { return methodParameters(methodSymbol, true); }

    @Override public Map<String, Object> methodParameters(String methodSymbol, boolean withCurrent) throws OpException {
        session.requireReady();
        synchronized (session) {
            JadxSymbols symbols = session.symbols();
            MethodNode method = methodNode(methodSymbol);
            List<String> types = method.getArgTypes().stream().map(symbols::currentTypeSig).toList();
            Map<String, Object> prototype = new LinkedHashMap<>();
            prototype.put("parameterTypes", types); prototype.put("count", types.size());
            prototype.put("static", method.getAccessFlags().isStatic());
            prototype.put("registerWords", method.getArgTypes().stream().mapToInt(t -> t.getRegCount()).sum() + (method.getAccessFlags().isStatic() ? 0 : 1));
            prototype.put("returnType", symbols.currentTypeSig(method.getReturnType()));

            MethodParametersAttr attr = method.get(JadxAttrType.METHOD_PARAMETERS);
            Map<String, Object> annotation = new LinkedHashMap<>(); annotation.put("source", "dex_method_parameters_annotation");
            List<Object> annotationNames = new ArrayList<>(), flags = new ArrayList<>(), decoded = new ArrayList<>();
            if (attr == null) annotation.put("status", "absent");
            else {
                for (MethodParametersAttr.Info info : attr.getList()) {
                    annotationNames.add(info.getName()); flags.add(info.getAccFlags()); decoded.add(decodeParameterFlags(info.getAccFlags()));
                }
                boolean match = annotationNames.size() == types.size();
                annotation.put("status", match ? "present" : "malformed"); annotation.put("names", annotationNames);
                annotation.put("accessFlags", flags); annotation.put("accessFlagsDecoded", decoded);
                annotation.put("namesCount", annotationNames.size()); annotation.put("flagsCount", flags.size());
                annotation.put("namesCountMatchesPrototype", match); annotation.put("flagsCountMatchesPrototype", match);
                annotation.put("countMatchesPrototype", match); annotation.put("encodedOrder", true); annotation.put("argumentMapping", "unverified");
                if (!match) annotation.put("mismatch", annotationNames.size() + " name(s) for " + types.size() + " formal parameter(s) — the metadata describes an earlier prototype");
            }

            List<Object> debugNames = new ArrayList<>();
            IDebugInfo debug = method.getDebugInfo();
            if (debug != null && debug.getLocalVars() != null) {
                List<ILocalVar> params = debug.getLocalVars().stream().filter(ILocalVar::isMarkedAsParameter)
                        .sorted(Comparator.comparingInt(ILocalVar::getRegNum)).toList();
                for (ILocalVar param : params) debugNames.add(param.getName());
            }
            Map<String, Object> debugInfo = new LinkedHashMap<>(); debugInfo.put("source", "dex_debug_info_parameter_names");
            debugInfo.put("status", debugNames.isEmpty() ? (method.isNoCode() ? "no-code" : "absent") : "present");
            if (!debugNames.isEmpty()) { debugInfo.put("names", debugNames); debugInfo.put("count", debugNames.size()); debugInfo.put("nonEmpty", debugNames.stream().filter(Objects::nonNull).count()); debugInfo.put("countMatchesPrototype", debugNames.size() == types.size()); }

            Map<String, Object> out = new LinkedHashMap<>(); out.put("schema", "method-parameters-v1");
            out.put("method", session.symbols().byAddress.get(methodOrigAddr(method)).currentAddr()); out.put("original_method", methodOrigAddr(method));
            out.put("dex", method.getInputFileName()); out.put("prototype", prototype); out.put("annotation", annotation); out.put("debugInfo", debugInfo);
            out.put("hasCode", !method.isNoCode()); out.put("argumentMapping", "unverified");
            if (withCurrent) {
                List<String> current = parameterNames(method); List<Object> parameters = new ArrayList<>();
                List<VarNode> argNodes = method.collectArgNodes();
                int reg = method.getArgsStartReg();
                for (int i = 0; i < types.size(); i++) {
                    Map<String, Object> p = new LinkedHashMap<>(); p.put("index", i); p.put("type", types.get(i));
                    p.put("register", i < argNodes.size() ? argNodes.get(i).getReg() : reg);
                    String currentName = current.get(i); p.put("name", currentName);
                    JadxCodeRef codeRef = JadxCodeRef.forMthArg(i);
                    String analyst = session.getRename(JadxSession.nodeRefFor(method), codeRef);
                    String source = analyst != null ? "analyst" : i < debugNames.size() && Objects.equals(currentName, debugNames.get(i)) ? "debug_info"
                            : i < annotationNames.size() && Objects.equals(currentName, annotationNames.get(i)) ? "annotation (applied by the engine by position — unverified)"
                            : currentName.matches("arg\\d+") ? "generic" : "engine";
                    p.put("nameSource", source); parameters.add(p); reg += method.getArgTypes().get(i).getRegCount();
                }
                out.put("parameters", parameters);
                Set<String> parameterSet = new HashSet<>(current);
                out.put("locals", localInfos(method).stream().filter(v -> v.parameterIndex() == null).map(LocalInfo::name).filter(n -> !parameterSet.contains(n)).distinct().toList());
                long positional = parameters.stream().map(x -> (Map<?, ?>) x).filter(x -> String.valueOf(x.get("nameSource")).startsWith("annotation")).count();
                Map<String, Object> named = new LinkedHashMap<>(); named.put("status", positional == 0 ? "none" : positional == types.size() ? "all" : "some"); named.put("count", positional); named.put("of", types.size());
                out.put("decompiler", Map.of("engine", "jadx", "annotationNamedArguments", named, "appliesAnnotationByPosition", positional == types.size(), "appliesAnnotationToAny", positional > 0));
            } else {
                out.put("decompiler", Map.of("engine", "jadx", "appliesAnnotationByPosition", false, "appliesAnnotationToAny", false,
                        "note", "not computed in this raw metadata view"));
            }
            out.put("note", "annotation order is encoded order, not a verified mapping to current arguments; reading this changes nothing");
            return out;
        }
    }

    @Override public Map<String, Object> renameParameters(String methodSymbol, List<Map<String, Object>> changes, String actor) throws OpException {
        session.requireReady();
        synchronized (session) {
            if (discarded) throw OpException.busy("this target is being closed with its edits discarded — rename refused", "target=" + id);
            MethodNode method = methodNode(methodSymbol);
            int count = method.getArgTypes().size(); List<String> current = parameterNames(method);
            Map<Integer, String> names = new TreeMap<>(); Set<Integer> clears = new TreeSet<>(); List<String> problems = new ArrayList<>();
            for (Map<String, Object> change : changes == null ? List.<Map<String, Object>>of() : changes) {
                Object rawIndex = change.get("index");
                int index = rawIndex instanceof Number n ? n.intValue() : rawIndex instanceof String s && s.matches("\\d+") ? Integer.parseInt(s) : -1;
                if (index < 0 || index >= count) { problems.add("index " + rawIndex + " is not a formal argument of this method (" + count + ")"); continue; }
                if (names.containsKey(index) || clears.contains(index)) { problems.add("two changes name argument " + index); continue; }
                if (Boolean.TRUE.equals(change.get("clear"))) { clears.add(index); continue; }
                Object value = change.get("name"); if (value == null || String.valueOf(value).isEmpty()) continue;
                String newName = String.valueOf(value);
                if (!Engine.usableName(newName)) { problems.add("'" + newName + "' is not a usable Java identifier"); continue; }
                if (names.containsValue(newName)) { problems.add("two arguments would be named '" + newName + "'"); continue; }
                if (newName.equals(current.get(index)) && session.getRename(JadxSession.nodeRefFor(method), JadxCodeRef.forMthArg(index)) != null) continue;
                names.put(index, newName);
            }
            Set<String> locals = localInfos(method).stream().filter(v -> v.parameterIndex() == null).map(LocalInfo::name).collect(java.util.stream.Collectors.toSet());
            for (Map.Entry<Integer, String> change : names.entrySet()) {
                if (locals.contains(change.getValue())) problems.add("'" + change.getValue() + "' is the name of a local in this method — rename the local first, or choose another name");
                for (int i = 0; i < count; i++) if (i != change.getKey() && !names.containsKey(i) && !clears.contains(i) && change.getValue().equals(current.get(i)))
                    problems.add("'" + change.getValue() + "' is already the name of argument " + i + " — swap them, or choose another name");
            }
            if (!problems.isEmpty()) throw OpException.syntax("parameter mapping refused: " + String.join("; ", problems), "symbol=" + methodSymbol);
            if (names.isEmpty() && clears.isEmpty()) throw OpException.syntax("nothing to change — every argument already has the proposed name", "symbol=" + methodSymbol);

            JadxNodeRef ref = JadxSession.nodeRefFor(method); List<jadx.api.data.ICodeRename> updated = new ArrayList<>(session.codeData().getRenames());
            Set<Integer> touched = new TreeSet<>(); touched.addAll(names.keySet()); touched.addAll(clears);
            updated.removeIf(rename -> Objects.equals(rename.getNodeRef(), ref) && rename.getCodeRef() != null
                    && rename.getCodeRef().getAttachType() == jadx.api.data.CodeRefType.MTH_ARG && touched.contains(rename.getCodeRef().getIndex()));
            for (Map.Entry<Integer, String> change : names.entrySet()) updated.add(new jadx.api.data.impl.JadxCodeRename(ref, JadxCodeRef.forMthArg(change.getKey()), change.getValue()));
            List<Object> applied = new ArrayList<>();
            for (int index : touched) {
                Map<String, Object> row = new LinkedHashMap<>(); row.put("index", index); row.put("oldName", current.get(index));
                String oldBinding = session.getRename(ref, JadxCodeRef.forMthArg(index)); row.put("oldSource", oldBinding == null ? "engine" : "analyst");
                if (clears.contains(index)) row.put("cleared", true); else row.put("newName", names.get(index)); applied.add(row);
            }
            session.codeData().setRenames(updated); session.decompiler().reloadCodeData();
            String topAddr = JadxSymbols.currentAddress(method.getTopParentClass()); evictClass(method.getTopParentClass()); index.invalidate(topAddr, topAddr); markDirty();
            String addr = session.symbols().byAddress.get(methodOrigAddr(method)).currentAddr(); Map<String, Object> out = new LinkedHashMap<>();
            out.put("addr", addr); out.put("newAddr", addr); out.put("kind", "parameters"); out.put("changes", applied); out.put("applied", true);
            out.put("actor", actor == null ? "human" : actor); return out;
        }
    }

    private void recordSavedContentHash(File file) {
        if (file == null || !file.isFile()) return;
        try (var input = Files.newInputStream(file.toPath())) {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            byte[] buffer = new byte[64 * 1024];
            for (int read; (read = input.read(buffer)) >= 0;) if (read > 0) digest.update(buffer, 0, read);
            contentHashes.add(HexFormat.of().formatHex(digest.digest()));
        } catch (Exception ignored) {}
    }

    @Override public Set<String> contentHashHistory() { return Collections.unmodifiableSet(contentHashes); }
    @Override public File persistenceFile() { return sidecarFile; }
    public boolean isDirty() { return dirty; }

    private void recordSave(boolean ok, long started, String error) {
        Map<String, Object> row = new LinkedHashMap<>(); row.put("ok", ok); row.put("at", System.currentTimeMillis());
        row.put("ms", Math.max(0, System.currentTimeMillis() - started));
        row.put("bytes", sidecarFile != null && sidecarFile.isFile() ? sidecarFile.length() : 0L);
        if (error != null) row.put("error", error); lastSave = row;
    }

    private Map<String, Object> writePersistenceFile() throws OpException {
        if (sidecarFile == null) throw OpException.unsupported("no JADX sidecar path configured; nothing to save to", "target=" + id);
        long started = System.currentTimeMillis(); long epoch = mutationEpoch.get();
        saveStartedAt = started; saveExpectBytes = sidecarFile.isFile() ? sidecarFile.length() : 0L;
        try {
            String json = session.serializeCodeData();
            boolean wrote = CheckpointedFileSave.write(sidecarFile.toPath(), destination -> {
                Files.writeString(destination, json, StandardCharsets.UTF_8);
                return true;
            });
            if (!wrote) throw OpException.busy("sidecar writer refused the write — code-data.json was not updated", "target=" + id);
            savedEpoch.set(epoch);
            if (mutationEpoch.get() == epoch) dirty = false;
            boolean witnessOk = index.recordPersistenceMtime(sidecarFile.lastModified());
            recordSavedContentHash(sidecarFile); recordSave(true, started, null);
            Map<String, Object> out = new LinkedHashMap<>(); out.put("saved", true);
            out.put("sidecar", sidecarFile.getAbsolutePath()); out.put("bytes", sidecarFile.length());
            out.put("witnessOk", witnessOk); return out;
        } catch (OpException error) {
            recordSave(false, started, error.getMessage()); throw error;
        } catch (Exception error) {
            recordSave(false, started, String.valueOf(error));
            throw OpException.busy("save failed: " + error, "target=" + id);
        } finally { saveStartedAt = 0; }
    }

    @Override public Map<String, Object> save() throws OpException {
        session.requireReady();
        synchronized (session) {
            if (discarded) throw OpException.busy("this target is being closed with its edits discarded — save refused", "target=" + id);
            if (replayPending) throw OpException.busy("this target's edit history is still being re-applied", "target=" + id);
            return writePersistenceFile();
        }
    }

    @Override public Map<String, Object> saveStatus() {
        long started = saveStartedAt; Map<String, Object> out = new LinkedHashMap<>(); out.put("saving", started > 0);
        if (started > 0) {
            out.put("startedAt", started); out.put("elapsedMs", System.currentTimeMillis() - started);
            out.put("bytesNow", sidecarFile != null && sidecarFile.isFile() ? sidecarFile.length() : 0L);
            out.put("bytesExpected", saveExpectBytes); out.put("sidecar", sidecarFile == null ? null : sidecarFile.getName());
        }
        if (lastSave != null) out.put("last", lastSave); return out;
    }

    @Override public void discardUnsaved() { synchronized (session) { discarded = true; dirty = false; } }

    @Override public void primeDirtyReplay(List<Map<String, Object>> entries) {
        pendingDirtyReplay = entries == null ? List.of() : List.copyOf(entries);
        primedWithFile = sidecarFile != null && sidecarFile.isFile();
    }

    private int applyDirtyReplay() {
        int applied = 0, failed = 0;
        for (Map<String, Object> entry : pendingDirtyReplay) {
            String operation = String.valueOf(entry.get("op")); String addr = String.valueOf(entry.get("addr"));
            if (!"rename".equals(operation) && !"comment".equals(operation)) continue;
            try {
                if ("comment".equals(operation)) {
                    comment(addr, entry.get("newText") == null ? "" : String.valueOf(entry.get("newText")), "recovery");
                } else if ("local".equals(entry.get("kind"))) {
                    renameLocal(addr, String.valueOf(entry.get("oldName")), String.valueOf(entry.get("newName")), "recovery");
                } else if ("parameters".equals(entry.get("kind"))) {
                    List<Map<String, Object>> changes = new ArrayList<>();
                    if (entry.get("parameterMutations") instanceof List<?> mutations) {
                        for (Object item : mutations) if (item instanceof Map<?, ?> source) {
                            Map<String, Object> change = new LinkedHashMap<>();
                            for (Map.Entry<?, ?> field : source.entrySet()) change.put(String.valueOf(field.getKey()), field.getValue());
                            changes.add(change);
                        }
                    } else if (entry.get("changes") instanceof List<?> list) {
                        for (Object item : list) if (item instanceof Map<?, ?> source) {
                            Map<String, Object> change = new LinkedHashMap<>();
                            change.put("index", source.get("index"));
                            if (Boolean.TRUE.equals(source.get("cleared"))) change.put("clear", true);
                            else change.put("name", source.get("newName") != null ? source.get("newName") : source.get("name"));
                            changes.add(change);
                        }
                    }
                    if (changes.isEmpty()) throw new IllegalStateException("a parameters entry without changes cannot be replayed");
                    renameParameters(addr, changes, "recovery");
                } else {
                    String newName = String.valueOf(entry.get("newName"));
                    if (Boolean.TRUE.equals(entry.get("revert")) || !Engine.usableName(newName)) restoreOriginalName(addr, newName, "recovery");
                    else rename(addr, newName, "recovery");
                }
                applied++;
            } catch (Exception failure) {
                failed++;
                Map<String, Object> context = new LinkedHashMap<>(); context.put("target", id); context.put("op", operation); context.put("addr", addr);
                context.put("error", String.valueOf(failure)); dev.workbench.server.Log.warn("jadx replay", "history edit could not be re-applied", context);
            }
        }
        replayFailed = failed; pendingDirtyReplay = List.of(); return applied;
    }

    @Override public Map<String, Object> comment(String symbol, String text, String actor) throws OpException {
        session.requireReady();
        if (symbol == null || symbol.isBlank()) throw OpException.syntax("comment requires a symbol", "jadx");
        synchronized (session) {
            if (discarded) throw OpException.busy("this target is being closed with its edits discarded — comment refused", "target=" + id);
            String requested = symbol.trim(); String base = requested; Integer offset = null; String suffix = null;
            java.util.regex.Matcher site = java.util.regex.Pattern.compile("^(.*->[^+]+)\\+(0x[0-9A-Fa-f]+|[0-9A-Fa-f]+h?)$").matcher(requested);
            if (site.matches()) { base = site.group(1); suffix = site.group(2); offset = parseHexOffset(suffix); }
            JadxSymbols.Symbol resolved = session.symbols().lookup(base);
            if (offset != null && !(resolved.node() instanceof MethodNode))
                throw OpException.syntax("an instruction comment requires a METHOD+offset", "symbol=" + requested);
            JadxNodeRef nodeRef = JadxSession.nodeRefFor(resolved.node());
            JadxCodeRef codeRef = offset == null ? null : JadxCodeRef.forInsn(offset);
            String value = text == null ? "" : text;
            String old = session.getComment(nodeRef, codeRef);
            boolean changed = !Objects.equals(old, value);
            if (changed) {
                session.upsertComment(nodeRef, codeRef, value);
                ClassNode top = resolved.owner().getTopParentClass();
                String topAddr = JadxSymbols.currentAddress(top);
                evictClass(top);
                index.invalidate(topAddr, topAddr);
                markDirty();
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("symbol", Map.of("addr", resolved.currentAddr(), "kind", resolved.kind(), "name", resolved.name()));
            out.put("addr", offset == null ? resolved.currentAddr() : resolved.currentAddr() + "+" + suffix);
            if (offset != null) out.put("site", true);
            out.put("text", value); out.put("oldText", old); out.put("applied", changed);
            if (!changed) { out.put("unchanged", true); out.put("reason", "This comment already has that text."); }
            out.put("actor", actor == null ? "human" : actor);
            return out;
        }
    }

    private static OpException unsupported(String operation) { return OpException.unsupported(operation + " is not implemented by the JADX adapter", "jadx"); }
    @Override public void close() {
        index.kill(); RuntimeException saveFailure = null;
        synchronized (session) {
            if (session.isReady() && dirty && !discarded && !replayPending && sidecarFile != null) {
                try { writePersistenceFile(); }
                catch (OpException error) { saveFailure = new IllegalStateException("close-save failed for " + name + "; accepted edits remain in history", error); }
            }
            session.close(); relations = null;
        }
        if (saveFailure != null) throw saveFailure;
    }
}
