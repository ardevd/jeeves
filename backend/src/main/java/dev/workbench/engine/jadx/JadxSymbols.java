package dev.workbench.engine.jadx;

import dev.workbench.registry.OpException;
import java.util.*;
import jadx.core.dex.nodes.*;

/** Original DEX identities indexed without calling JavaClass.load(). */
final class JadxSymbols {
    record Symbol(String addr, String kind, String originalName, ClassNode owner, Object node) {
        String name() {
            if (node instanceof ClassNode c) return c.getAlias();
            if (node instanceof MethodNode m) return m.getAlias();
            return ((FieldNode) node).getAlias();
        }
        Map<String, Object> row() {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("addr", addr); row.put("origSig", addr); row.put("sig", addr);
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
                row.put("type", jadx.core.codegen.TypeGen.signature(f.getFieldInfo().getType()));
                row.put("declaredIn", address(owner));
            }
            if (!originalName.equals(name())) row.put("origName", originalName);
            return row;
        }
    }

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

    Symbol lookup(String query) throws OpException {
        if (query == null || query.isBlank()) throw OpException.syntax("empty symbol", "jadx");
        String q = query.trim();
        java.util.regex.Matcher offm = java.util.regex.Pattern.compile("^(.*->[^+]+)\\+(0x[0-9a-fA-F]+|[0-9a-fA-F]+h)$").matcher(q);
        if (offm.matches()) q = offm.group(1);
        Symbol exact = byAddress.get(q);
        if (exact != null) return exact;
        List<Symbol> matches = new ArrayList<>();
        for (Symbol s : byAddress.values()) {
            boolean match;
            if (q.contains("->")) {
                int arrow = q.indexOf("->");
                String owner = q.substring(0, arrow), member = q.substring(arrow + 2);
                String aliasOwner = "L" + s.owner().getClassInfo().makeAliasRawFullName().replace('.', '/') + ";";
                if (!owner.equals(address(s.owner())) && !owner.equals(aliasOwner)) continue;
                String tail = s.addr().substring(s.addr().indexOf("->") + 2);
                if (s.kind().equals("class")) continue;
                int split = s.kind().equals("method") ? tail.indexOf('(') : tail.indexOf(':');
                String aliasTail = s.name() + tail.substring(split);
                match = member.equals(tail) || member.equals(aliasTail)
                        || member.equals(s.name()) || member.equals(s.originalName())
                        || (s.kind().equals("method") && member.endsWith(")")
                            && (tail.startsWith(member) || aliasTail.startsWith(member)));
            } else {
                match = q.equals(s.name()) || q.equals(s.originalName());
                if (s.kind().equals("class")) match |= q.equals(s.owner().getFullName())
                        || q.equals(s.owner().getClassInfo().getRawName());
                else match |= q.equals(s.owner().getFullName() + "." + s.name());
                // A qualified query never falls back to an unrelated simple name.
            }
            if (match) matches.add(s);
        }
        if (matches.size() == 1) return matches.getFirst();
        OpException failure = OpException.notFound(matches.isEmpty() ? "symbol not found: " + q
                : "ambiguous symbol: " + q + " — use an exact DEX descriptor", "jadx");
        if (!matches.isEmpty()) failure.extra.put("candidates", matches.stream().limit(25).map(Symbol::row).toList());
        throw failure;
    }

    Map<String, Object> resolve(String query) throws OpException {
        Symbol s = lookup(query);
        Map<String, Object> row = s.row();
        row.put("exact", s.addr().equals(query.trim()));
        if (!s.addr().equals(query.trim())) {
            row.put("asked", query.trim()); row.put("lenient", "unambiguous name or partial descriptor");
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
