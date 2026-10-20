package dev.workbench.engine.jadx;

import dev.workbench.registry.OpException;
import java.util.*;
import jadx.core.dex.nodes.*;

/** Original DEX identities indexed without calling JavaClass.load(). */
final class JadxSymbols {
    final class Symbol {
        private final String addr, kind, originalName;
        private final ClassNode owner;
        private final Object node;

        Symbol(String addr, String kind, String originalName, ClassNode owner, Object node) {
            this.addr = addr; this.kind = kind; this.originalName = originalName;
            this.owner = owner; this.node = node;
        }
        String addr() { return addr; }
        String kind() { return kind; }
        String originalName() { return originalName; }
        ClassNode owner() { return owner; }
        Object node() { return node; }

        String name() {
            if (node instanceof ClassNode c) return c.getAlias();
            if (node instanceof MethodNode m) return m.getAlias();
            return ((FieldNode) node).getAlias();
        }
        boolean isRenamed() { return !originalName.equals(name()); }

        String currentAddr() {
            if (node instanceof ClassNode c) return currentAddress(c);
            if (node instanceof MethodNode m) {
                StringBuilder sb = new StringBuilder(currentAddress(owner)).append("->").append(m.getAlias()).append('(');
                for (var arg : m.getMethodInfo().getArgumentsTypes()) sb.append(currentTypeSig(arg));
                return sb.append(')').append(currentTypeSig(m.getMethodInfo().getReturnType())).toString();
            }
            FieldNode f = (FieldNode) node;
            return currentAddress(owner) + "->" + f.getAlias() + ":" + currentTypeSig(f.getFieldInfo().getType());
        }

        Map<String, Object> row() {
            Map<String, Object> row = new LinkedHashMap<>();
            String cur = currentAddr();
            row.put("addr", cur); row.put("origSig", addr); row.put("sig", cur);
            row.put("kind", kind); row.put("name", name());
            row.put("owner", owner.getFullName());
            if (node instanceof ClassNode) row.put("fqcn", owner.getFullName());
            if (node instanceof MethodNode m) {
                row.put("static", m.getAccessFlags().isStatic());
                row.put("native", m.getAccessFlags().isNative());
                row.put("access", m.getAccessFlags().makeString(false).trim());
            }
            if (node instanceof FieldNode f) {
                row.put("static", f.getAccessFlags().isStatic());
                row.put("final", f.getAccessFlags().isFinal());
                row.put("access", f.getAccessFlags().makeString(false).trim());
                row.put("type", currentTypeSig(f.getFieldInfo().getType()));
                row.put("declaredIn", currentAddress(owner));
            }
            if (isRenamed()) {
                row.put("renamed", true);
                row.put("origName", originalName);
            }
            return row;
        }
    }

    record ResolvedLookup(Symbol symbol, boolean exact, String by, String lenient,
                          List<String> differs, boolean contradiction, String asked) {}

    final Map<String, Symbol> byAddress = new LinkedHashMap<>();
    final List<ClassNode> classes;

    JadxSymbols(RootNode root) {
        classes = List.copyOf(root.getClasses(true));
        for (ClassNode c : classes) {
            put(new Symbol(address(c), "class", c.getName(), c, c));
            for (MethodNode m : c.getMethods())
                put(new Symbol(address(c) + "->" + m.getMethodInfo().getShortId(), "method", m.getName(), c, m));
            for (FieldNode f : c.getFields())
                put(new Symbol(address(c) + "->" + f.getFieldInfo().getShortId(), "field", f.getName(), c, f));
        }
    }

    private void put(Symbol symbol) {
        byAddress.putIfAbsent(symbol.addr(), symbol);
    }

    static String address(ClassNode cls) {
        return "L" + cls.getClassInfo().getRawName().replace('.', '/') + ";";
    }

    static String currentAddress(ClassNode cls) {
        return "L" + cls.getClassInfo().makeAliasRawFullName().replace('.', '/') + ";";
    }

    String currentTypeSig(jadx.core.dex.instructions.args.ArgType type) {
        if (type == null) return "";
        if (type.isArray()) return "[" + currentTypeSig(type.getArrayElement());
        if (type.isObject()) {
            Symbol cls = byAddress.get("L" + type.getObject().replace('.', '/') + ";");
            if (cls != null) return currentAddress(cls.owner());
        }
        return jadx.core.codegen.TypeGen.signature(type);
    }

    Symbol lookup(String query) throws OpException {
        return lookupWithMode(query).symbol();
    }

    ResolvedLookup lookupWithMode(String query) throws OpException {
        if (query == null || query.isBlank()) throw OpException.syntax("empty symbol", "jadx");
        String q = query.trim();
        java.util.regex.Matcher offm = java.util.regex.Pattern.compile("^(.*->[^+]+)\\+(0x[0-9a-fA-F]+|[0-9a-fA-F]+h)$").matcher(q);
        if (offm.matches()) q = offm.group(1);

        // 1. Exact current address match
        for (Symbol s : byAddress.values()) {
            if (s.currentAddr().equals(q)) return new ResolvedLookup(s, true, null, null, List.of(), false, q);
        }
        // 2. Exact original DEX signature match
        Symbol exactOrig = byAddress.get(q);
        if (exactOrig != null) {
            return new ResolvedLookup(exactOrig, true, exactOrig.currentAddr().equals(q) ? null : "original signature",
                    null, List.of(), false, q);
        }
        // 3. Exact dotted FQCN class match
        if (!q.contains("->") && !q.startsWith("L")) {
            List<Symbol> clsMatches = new ArrayList<>();
            for (Symbol s : byAddress.values()) {
                if (s.kind().equals("class") && (q.equals(s.owner().getFullName())
                        || q.equals(s.owner().getClassInfo().getRawName())
                        || q.equals(s.owner().getClassInfo().makeAliasRawFullName()))) {
                    clsMatches.add(s);
                }
            }
            if (clsMatches.size() == 1) return new ResolvedLookup(clsMatches.getFirst(), true, null, null, List.of(), false, q);
        }

        // 4. Member reference with '->'
        if (q.contains("->")) {
            int arrow = q.indexOf("->");
            String owner = q.substring(0, arrow), member = q.substring(arrow + 2);
            List<Symbol> inOwner = new ArrayList<>();
            for (Symbol s : byAddress.values()) {
                if (s.kind().equals("class")) continue;
                if (owner.equals(address(s.owner())) || owner.equals(currentAddress(s.owner()))) inOwner.add(s);
            }
            List<Symbol> exactDesc = new ArrayList<>();
            List<Symbol> prefixMatches = new ArrayList<>();
            List<Symbol> fieldNameMatches = new ArrayList<>();
            List<Symbol> methodNameMatches = new ArrayList<>();
            for (Symbol s : inOwner) {
                String origTail = s.addr().substring(s.addr().indexOf("->") + 2);
                String curTail = s.currentAddr().substring(s.currentAddr().indexOf("->") + 2);
                int origSplit = s.kind().equals("method") ? origTail.indexOf('(') : origTail.indexOf(':');
                int curSplit = s.kind().equals("method") ? curTail.indexOf('(') : curTail.indexOf(':');
                String aliasOrigTail = s.name() + origTail.substring(origSplit);
                String origCurTail = s.originalName() + curTail.substring(curSplit);
                if (member.equals(origTail) || member.equals(curTail) || member.equals(aliasOrigTail) || member.equals(origCurTail)) {
                    exactDesc.add(s);
                } else if (s.kind().equals("method") && member.endsWith(")")
                        && (origTail.startsWith(member) || curTail.startsWith(member)
                            || aliasOrigTail.startsWith(member) || origCurTail.startsWith(member))) {
                    prefixMatches.add(s);
                } else if (!member.contains("(") && !member.contains(":")
                        && (member.equals(s.name()) || member.equals(s.originalName()))) {
                    if (s.kind().equals("field")) fieldNameMatches.add(s);
                    else methodNameMatches.add(s);
                }
            }
            if (exactDesc.size() == 1)
                return new ResolvedLookup(exactDesc.getFirst(), false, null, "address", List.of(), false, q);
            if (exactDesc.size() > 1) throw ambiguous(q, exactDesc);
            if (prefixMatches.size() == 1)
                return new ResolvedLookup(prefixMatches.getFirst(), false, null, "return type", List.of(), false, q);
            if (prefixMatches.size() > 1) throw ambiguous(q, prefixMatches);
            if (fieldNameMatches.size() == 1)
                return new ResolvedLookup(fieldNameMatches.getFirst(), false, null, "name only", List.of(), false, q);
            if (fieldNameMatches.size() > 1) throw ambiguous(q, fieldNameMatches);
            if (methodNameMatches.size() == 1)
                return new ResolvedLookup(methodNameMatches.getFirst(), false, null, "name only", List.of(), false, q);
            if (methodNameMatches.size() > 1) throw ambiguous(q, methodNameMatches);

            // Check lenient method match where parameter or return types differ (contradiction / rename history)
            int lp = member.indexOf('('), rp = member.indexOf(')', Math.max(0, lp));
            if (lp > 0 && rp > lp) {
                String askedName = member.substring(0, lp);
                List<Symbol> sameNameMethods = new ArrayList<>();
                for (Symbol s : inOwner) {
                    if (s.kind().equals("method") && (askedName.equals(s.name()) || askedName.equals(s.originalName())))
                        sameNameMethods.add(s);
                }
                if (sameNameMethods.size() == 1) {
                    Symbol s = sameNameMethods.getFirst();
                    String curTail = s.currentAddr().substring(s.currentAddr().indexOf("->") + 2);
                    String origTail = s.addr().substring(s.addr().indexOf("->") + 2);
                    int clp = curTail.indexOf('('), crp = curTail.indexOf(')', clp);
                    int olp = origTail.indexOf('('), orp = origTail.indexOf(')', olp);
                    String askedParams = member.substring(lp, rp + 1);
                    String askedRet = rp + 1 < member.length() ? member.substring(rp + 1) : "";
                    List<String> differs = new ArrayList<>();
                    if (!askedParams.equals(curTail.substring(clp, crp + 1)) && !askedParams.equals(origTail.substring(olp, orp + 1)))
                        differs.add("parameter types");
                    if (!askedRet.isEmpty() && !askedRet.equals(curTail.substring(crp + 1)) && !askedRet.equals(origTail.substring(orp + 1)))
                        differs.add("return type");
                    if (differs.isEmpty()) differs.add("parameter types");
                    return new ResolvedLookup(s, false, null, differs.getFirst(), differs, true, q);
                }
                if (sameNameMethods.size() > 1) throw ambiguous(q, sameNameMethods);
            }
            throw OpException.notFound("symbol not found: " + q, "jadx");
        }

        // 5. Dotted Owner.member or simple name
        List<Symbol> matches = new ArrayList<>();
        for (Symbol s : byAddress.values()) {
            boolean match = false;
            if (q.contains(".")) {
                if (!s.kind().equals("class")) {
                    match = q.equals(s.owner().getFullName() + "." + s.name())
                            || q.equals(s.owner().getClassInfo().getRawName() + "." + s.originalName())
                            || q.equals(s.owner().getClassInfo().makeAliasRawFullName() + "." + s.name());
                }
            } else if (!q.startsWith("L")) {
                match = q.equals(s.name()) || q.equals(s.originalName());
            }
            if (match) matches.add(s);
        }
        if (matches.size() == 1) {
            Symbol s = matches.getFirst();
            return new ResolvedLookup(s, false, null, s.kind().equals("class") ? "simple name" : "name only", List.of(), false, q);
        }
        if (matches.isEmpty()) throw OpException.notFound("symbol not found: " + q, "jadx");
        throw ambiguous(q, matches);
    }

    private static OpException ambiguous(String q, List<Symbol> matches) {
        OpException failure = OpException.notFound("ambiguous symbol: " + q + " — use an exact DEX descriptor", "jadx");
        failure.extra.put("candidates", matches.stream().limit(25).map(Symbol::row).toList());
        return failure;
    }

    Map<String, Object> resolve(String query) throws OpException {
        ResolvedLookup rl = lookupWithMode(query);
        Symbol s = rl.symbol();
        Map<String, Object> row = s.row();
        row.put("exact", rl.exact());
        if (rl.by() != null) row.put("by", rl.by());
        if (!rl.exact()) {
            row.put("asked", rl.asked());
            row.put("lenient", rl.lenient());
            if (!rl.differs().isEmpty()) row.put("differs", rl.differs());
            if (rl.contradiction()) {
                row.put("contradiction", true);
                String orig = s.addr(), cur = s.currentAddr();
                row.put("note", "the reference's " + String.join(" and ", rl.differs())
                        + " differ from the method found — a reading convenience, not evidence: exports, findings and hooks refuse it; the exact descriptor is "
                        + orig + (orig.equals(cur) ? "" : " (displayed here as " + cur + ")"));
            }
        }
        return row;
    }

    Map<String, Object> search(String query, int limit, String kind) throws OpException {
        if (kind != null && !Set.of("class", "method", "field").contains(kind))
            throw OpException.syntax("kind must be class, method or field", "jadx");
        String q = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        List<Symbol> matches = byAddress.values().stream()
                .filter(s -> kind == null || kind.equals(s.kind()))
                .filter(s -> !q.isEmpty() && (s.name().toLowerCase(Locale.ROOT).contains(q)
                        || s.originalName().toLowerCase(Locale.ROOT).contains(q)
                        || s.currentAddr().toLowerCase(Locale.ROOT).contains(q)
                        || s.addr().toLowerCase(Locale.ROOT).contains(q)))
                .sorted(Comparator.comparingInt((Symbol s) -> s.name().equals(query) ? 0
                        : s.name().equalsIgnoreCase(query) ? 1
                        : s.name().toLowerCase(Locale.ROOT).startsWith(q) ? 2 : 3)
                        .thenComparing(Symbol::kind).thenComparing(Symbol::addr)).toList();
        int count = limit > 0 ? Math.min(limit, matches.size()) : matches.size();
        return Map.of("rows", matches.subList(0, count).stream().map(Symbol::row).toList(),
                "total", matches.size(), "truncated", count < matches.size());
    }
}
