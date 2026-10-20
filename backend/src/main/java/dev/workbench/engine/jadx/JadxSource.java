package dev.workbench.engine.jadx;

import dev.workbench.registry.OpException;
import java.util.*;
import jadx.api.ICodeInfo;
import jadx.api.metadata.ICodeAnnotation;
import jadx.api.metadata.annotations.*;
import jadx.api.utils.CodeUtils;
import jadx.core.dex.nodes.*;

/** Maps JADX metadata to UTF-16, half-open source spans used by the browser. */
final class JadxSource {
    private JadxSource() {}

    static Map<String, Object> render(JadxSymbols.Symbol symbol, Map<String, Object> options) throws OpException {
        Map<String, Object> opts = options == null ? Map.of() : options;
        String scope = String.valueOf(opts.getOrDefault("scope", symbol.kind().equals("method") ? "method" : "class"));
        if (!scope.equals("method") && !scope.equals("class")) throw OpException.syntax("scope must be class or method", "jadx");
        if (scope.equals("method") && !(symbol.node() instanceof MethodNode))
            throw OpException.syntax("method scope needs a method symbol", "jadx");
        ClassNode top = symbol.owner().getTopParentClass();
        boolean fresh = top.getCodeFromCache() == null;
        long started = System.nanoTime();
        ICodeInfo code = top.getCode();
        String full = code.getCodeStr();
        int start = 0, end = full.length();
        if (scope.equals("method")) {
            MethodNode method = (MethodNode) symbol.node();
            // No heuristic brace parsing: absent declarations cannot safely be sliced.
            boolean declared = code.getCodeMetadata().getAsMap().values().stream()
                    .anyMatch(a -> a instanceof NodeDeclareRef d && d.getNode() == method);
            if (!declared) throw OpException.unsupported("JADX did not render this method separately (possibly inlined)", symbol.addr());
            end = CodeUtils.getMethodEnd(method, code);
            start = CodeUtils.getLineStartForPos(full, method.getDefPosition());
            if (end < start) throw OpException.unsupported("JADX has no method end metadata", symbol.addr());
        }
        String text = full.substring(start, end);
        int total = text.split("\n", -1).length;
        List<Object> tokens = tokens(code, start, end);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("symbol", scope.equals("method") ? symbol.row() : Map.of("addr", JadxSymbols.address(top),
                "origSig", JadxSymbols.address(top), "kind", "class", "name", top.getAlias(), "fqcn", top.getFullName()));
        out.put("scope", scope); out.put("text", text); out.put("language", "java"); out.put("engine", "jadx");
        out.put("decompiled", !text.isEmpty()); out.put("fresh", fresh); out.put("lineCount", total);
        out.put("ms", (System.nanoTime() - started) / 1_000_000);
        List<Object> methods = new ArrayList<>();
        for (var entry : new TreeMap<>(code.getCodeMetadata().getAsMap()).entrySet()) {
            if (entry.getKey() < start || entry.getKey() >= end) continue;
            if (!(entry.getValue() instanceof NodeDeclareRef declaration) || !(declaration.getNode() instanceof MethodNode m)) continue;
            int finish = CodeUtils.getMethodEnd(m, code);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("addr", address(m)); row.put("name", m.getAlias());
            row.put("line", line(full, start, entry.getKey()));
            if (finish >= start) row.put("endLine", line(full, start, Math.min(end, finish)));
            methods.add(row);
        }
        out.put("methods", methods);
        if (Boolean.TRUE.equals(opts.get("tokens"))) out.put("tokens", tokens);
        if (opts.get("window") != null) {
            String[] parts = String.valueOf(opts.get("window")).split(",", -1);
            try {
                int from = Integer.parseInt(parts[0]), to = parts.length == 2 ? Integer.parseInt(parts[1]) : total;
                if (parts.length > 2 || from < 1 || to < from) throw new NumberFormatException();
                String[] lines = text.split("\n", -1);
                out.put("text", from > total ? "" : String.join("\n", Arrays.copyOfRange(lines, from - 1, Math.min(to, total))));
                out.put("window", Map.of("from", from, "to", Math.min(to, total), "totalLines", total));
            } catch (NumberFormatException e) { throw OpException.syntax("window must be positive from,to lines", "jadx"); }
        }
        return out;
    }

    private static String address(MethodNode m) { return JadxSymbols.address(m.getParentClass()) + "->" + m.getMethodInfo().getShortId(); }
    private static int line(String text, int start, int pos) {
        int line = 1;
        for (int i = start; i < pos; i++) if (text.charAt(i) == '\n') line++;
        return line;
    }

    static List<Object> tokens(ICodeInfo code, int start, int end) {
        String text = code.getCodeStr();
        List<Object> result = new ArrayList<>();
        for (var entry : new TreeMap<>(code.getCodeMetadata().getAsMap()).entrySet()) {
            int pos = entry.getKey();
            if (pos < start || pos >= end) continue;
            ICodeAnnotation ann = entry.getValue();
            if (ann instanceof VarRef ref) ann = code.getCodeMetadata().getAt(ref.getRefPos());
            if (ann instanceof NodeDeclareRef ref) ann = ref.getNode();
            String kind, addr = null, type = null;
            if (ann instanceof ClassNode c) { kind = "class"; addr = JadxSymbols.address(c); }
            else if (ann instanceof MethodNode m) {
                int finish = pos;
                while (finish < end && Character.isJavaIdentifierPart(text.charAt(finish))) finish++;
                String word = text.substring(pos, finish);
                if (word.equals("this") || word.equals("super")) continue;
                boolean isCtorType = m.isConstructor() && pos >= 4 && text.startsWith("new ", pos - 4);
                kind = isCtorType ? "class" : "method";
                addr = address(m);
            }
            else if (ann instanceof FieldNode f) { kind = "field"; addr = JadxSymbols.address(f.getParentClass()) + "->" + f.getFieldInfo().getShortId(); }
            else if (ann instanceof VarNode v) { kind = "local"; type = String.valueOf(v.getType()); }
            else continue;
            int finish = pos;
            while (finish < end && Character.isJavaIdentifierPart(text.charAt(finish))) finish++;
            if (finish == pos) continue;
            int lineStart = text.lastIndexOf('\n', pos - 1) + 1;
            Map<String, Object> token = new LinkedHashMap<>();
            token.put("line", line(text, start, pos)); token.put("cs", pos - lineStart); token.put("ce", finish - lineStart);
            token.put("kind", kind);
            if (addr != null) token.put("addr", addr);
            if (type != null) token.put("type", type);
            result.add(token);
        }
        return result;
    }
}
