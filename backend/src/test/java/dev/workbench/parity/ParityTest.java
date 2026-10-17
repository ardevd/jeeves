package dev.workbench.parity;

import dev.workbench.ops.Ops;
import dev.workbench.registry.Dispatcher;
import dev.workbench.registry.Op;
import dev.workbench.registry.Registry;
import dev.workbench.server.Workspace;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Registry/spec parity test (plain main-class, no JUnit):
 *  1. every registry op has name + capability + mutating flag + cost hint;
 *  2. every op named in design/MCP_SPEC.md §4 exists in the registry;
 *  3. reverse: every registered op is named in the spec (small allowed-extra
 *     set for ops specced outside §4: caps §2, op.cancel §5, device.* DEVICE_SPEC);
 *  4. dispatcher answers E_NOTFOUND for unknown ops;
 *  5. MockEngine ops round-trip through the dispatcher.
 *
 * Extraction of op names from the markdown is deliberately mechanical but
 * robust: backtick spans inside §4, split on "·" groups and "/" variants,
 * params stripped, bare names in the §4.9/§4.10 prose lists inherit the
 * running namespace (table rows never do).
 */
public final class ParityTest {

    private static int failures = 0;
    private static int checks = 0;

    /** Top-level op namespaces — a dotted token starting with one of these is absolute. */
    private static final Set<String> KNOWN_NS = Set.of(
            "project", "target", "workspace", "symbol", "ui", "code", "xref", "callees",
            "callers", "impls", "strings", "string", "search", "rename", "comment", "tag",
            "bookmark", "history", "flow", "runs", "map", "agent", "device", "op", "caps", "llm", "task",
            "layout", "presence", "fs", "system");

    /** Registered ops specced outside MCP_SPEC §4 (allowed reverse-parity extras). */
    private static boolean allowedExtra(String op) {
        return op.equals("caps")            // MCP_SPEC §2
                || op.equals("op.cancel")   // MCP_SPEC §5
                || op.equals("target.save") // MCP_SPEC §3 (⌘S saves the active target; no §4 row)
                || op.equals("runs.detach") // VIEWS_SPEC §2 (attach/spawn lifecycle; no §4 row)
                || op.equals("ops.list")    // meta: registry dump for MCP tool generation
                || op.equals("ops.tools")   // meta: curated tool schemas for MCP (AgentLoop TOOL_SPECS)
                || op.equals("search.index")    // VIEWS_SPEC §3 (index status/stats line)
                || op.equals("search.reindex")  // VIEWS_SPEC §3 (rebuild link)
                || op.equals("code.classinfo")  // VIEWS_SPEC §shared (CLASS bay data)
                || op.startsWith("device."); // DEVICE_SPEC §ops
    }

    public static void main(String[] args) throws Exception {
        var extracted=extractSpecOps("""
                ## 4. Op registry
                | Op | M | Purpose |
                | `target.saveStatus [target]` | | current `addr` and `confidence` |
                | `naming.coverage <symbol>` | | receipt |
                | `code.site <symbol>` — facade `code {view:site}` | | mapped site |
                | `newnamespace.missing / variant` | | a real missing declaration must remain visible |
                Prose mentioning `code.params.parameters` and `addr` is not a declaration.
                `renamed` is a field, not an operation.
                Orientation: `map.list · map.get`. Mutation: `map.create · addnode · type.add/set`.
                `agent.pause/resume/stop`.
                ## 5. Other
                """);
        check(extracted.equals(Set.of("target.saveStatus","naming.coverage","code.site",
                        "newnamespace.missing","newnamespace.variant","map.list","map.get","map.create",
                        "map.addnode","map.type.add","map.type.set","agent.pause","agent.resume","agent.stop")),
                "extract declarations, relative lists and camel-case names without facade aliases or prose fields: "+extracted);
        Workspace ws = new Workspace();
        /* a workbench ROOT too (2026-09-10): the agent-actor rename below files a proposal, and
           proposals live under <root>/projects/<slug>/ since batch 2 — without a root that
           wrote a fixture sidecar into the repo's cwd */
        ws.setWorkbenchRoot(Files.createTempDirectory("wb-parity-root").toFile());
        ws.setAgents(new dev.workbench.server.AgentManager(ws, new dev.workbench.server.EventBus(),
                Files.createTempDirectory("wb-parity").toFile()));
        ws.agents().reviewPolicy().configure("manual",null,0);
        ws.setDevices(new dev.workbench.device.DeviceManager(
                Files.createTempDirectory("wb-parity-dev").toFile()));
        Registry registry = Ops.registerAll(ws);
        Dispatcher dispatcher = new Dispatcher(registry);
        dispatcher.setScopeChecker(ws.agents()::checkScope);

        // ---- 1. every registry op fully declared
        for (Op op : registry.all()) {
            check(op.name() != null && !op.name().isBlank(), "op has name");
            check(op.capability() != null, "op '" + op.name() + "' declares capability (\"\" = none)");
            check(op.cost() != null, "op '" + op.name() + "' declares cost hint");
            check(op.handler() != null, "op '" + op.name() + "' has handler");
        }
        System.out.println("[1] registry: " + registry.size() + " ops, all declared name/caps/mutating/cost");

        // ---- 2. spec §4 ops ⊆ registry
        Path spec = findSpec();
        Set<String> specOps = extractSpecOps(Files.readString(spec));
        System.out.println("[2] spec §4: extracted " + specOps.size() + " op names from " + spec);
        for (String name : specOps)
            check(registry.has(name), "spec op '" + name + "' registered");

        // ---- 3. reverse: registry ⊆ spec §4 + allowed extras
        for (Op op : registry.all())
            check(specOps.contains(op.name()) || allowedExtra(op.name()),
                    "registered op '" + op.name() + "' appears in MCP_SPEC §4 (or allowed extra)");

        // ---- 4. E_NOTFOUND for unknown op
        Map<String, Object> resp = dispatcher.dispatch("no.such.op", Map.of(), "script");
        check(Boolean.FALSE.equals(resp.get("ok")), "unknown op not ok");
        check(errorCode(resp).equals("E_NOTFOUND"),
                "unknown op → E_NOTFOUND (got " + errorCode(resp) + ")");

        // ---- 5. MockEngine round-trips
        Map<String, Object> state = mapData(dispatcher.dispatch("workspace.state", Map.of(), "human"));
        @SuppressWarnings("unchecked")
        List<Object> targets = (List<Object>) state.get("targets");
        check(targets.size() == 4, "workspace.state → 4 targets (got " + targets.size() + ")");
        check(String.valueOf(data(dispatcher.dispatch("project.list", Map.of(), "human")))
                        .contains("privacy-invite-flow"),
                "project.list contains privacy-invite-flow");

        Map<String, Object> dec = mapData(dispatcher.dispatch("code.decompile",
                Map.of("symbol", "MessageSyncHandler"), "human"));
        check(String.valueOf(dec.get("text")).contains("AES/GCM/NoPadding"),
                "code.decompile MessageSyncHandler contains AES/GCM/NoPadding");
        check(String.valueOf(dec.get("text")).contains("GeminiClient.classify"),
                "code.decompile contains GeminiClient.classify");
        check(((Number) dec.get("lineCount")).intValue() >= 38,
                "decompiled class ~40 lines (got " + dec.get("lineCount") + ")");

        @SuppressWarnings("unchecked")
        Map<String, Object> strings = (Map<String, Object>) data(dispatcher.dispatch("strings", Map.of(), "human"));
        @SuppressWarnings("unchecked")
        List<Object> stringRows = (List<Object>) strings.get("rows");
        check(stringRows.size() == 20, "strings → 20 fixture rows (got " + stringRows.size() + ")");

        @SuppressWarnings("unchecked")
        List<Object> xto = (List<Object>) data(dispatcher.dispatch("xref.to",
                Map.of("symbol", "onMessage"), "human"));
        check(xto.size() == 2 && String.valueOf(xto).contains("MailSyncService.onStartCommand"),
                "xref.to onMessage → MailSyncService.onStartCommand etc.");

        @SuppressWarnings("unchecked")
        List<Object> hits = (List<Object>) data(dispatcher.dispatch("search",
                Map.of("query", "cipher"), "human"));
        check(!hits.isEmpty(), "search 'cipher' → grouped hits");

        @SuppressWarnings("unchecked")
        List<Object> sx = (List<Object>) data(dispatcher.dispatch("string.xrefs",
                Map.of("id", "s8"), "human"));
        check(String.valueOf(sx).contains("decryptPayload"), "string.xrefs s8 (gms-inv) → decryptPayload");

        // mutating op + agent actor → proposal, not applied (agents propose, humans dispose)
        Map<String, Object> ren = mapData(dispatcher.dispatch("rename",
                Map.of("symbol", "onMessage", "name", "onEnvelope"), "llm:test-agent"));
        check(Boolean.TRUE.equals(ren.get("proposal")) && "pending".equals(ren.get("status")),
                "agent rename recorded as pending proposal");

        // stubbed op answers E_UNSUPPORTED with mock note
        Map<String, Object> stub = dispatcher.dispatch("ui.open", Map.of(), "human");
        check(Boolean.FALSE.equals(stub.get("ok")) && errorCode(stub).equals("E_UNSUPPORTED"),
                "ui.open stub → E_UNSUPPORTED");
        check(String.valueOf(stub.get("error")).contains("mock"), "stub carries mock note");

        // ---- verdict
        System.out.println();
        if (failures > 0) {
            System.out.println("PARITY TEST FAILED: " + failures + "/" + checks + " checks failed");
            System.exit(1);
        }
        System.out.println("PARITY TEST PASSED: " + checks + " checks green");
    }

    // ------------------------------------------------------------ helpers

    private static void check(boolean ok, String label) {
        checks++;
        if (!ok) {
            failures++;
            System.out.println("  FAIL: " + label);
        }
    }

    private static Object data(Map<String, Object> resp) {
        if (!Boolean.TRUE.equals(resp.get("ok")))
            throw new IllegalStateException("op failed: " + resp.get("error"));
        return resp.get("data");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> mapData(Map<String, Object> resp) {
        return (Map<String, Object>) data(resp);
    }

    @SuppressWarnings("unchecked")
    private static String errorCode(Map<String, Object> resp) {
        Object e = resp.get("error");
        if (e instanceof Map) return String.valueOf(((Map<String, Object>) e).get("code"));
        return "<none>";
    }

    private static Path findSpec() {
        for (String p : List.of("design/MCP_SPEC.md", "../design/MCP_SPEC.md")) {
            Path path = Path.of(p);
            if (Files.isRegularFile(path)) return path;
        }
        throw new IllegalStateException("design/MCP_SPEC.md not found (cwd=" + Path.of("").toAbsolutePath() + ")");
    }

    /** Extract op names from MCP_SPEC §4 (between "## 4." and "## 5."). */
    static Set<String> extractSpecOps(String md) {
        int start = md.indexOf("## 4. Op registry");
        int end = md.indexOf("## 5.", start);
        if (start < 0 || end < 0)
            throw new IllegalStateException("MCP_SPEC.md §4 section markers not found");
        String section = md.substring(start, end);

        Set<String> ops = new LinkedHashSet<>();
        Pattern spanPat = Pattern.compile("`([^`]+)`");
        Matcher sm = spanPat.matcher(section);
        while (sm.find()) {
            String span = sm.group(1);
            boolean inTable = isTableLine(section, sm.start());
            String prefix=section.substring(section.lastIndexOf('\n',sm.start())+1,sm.start()).strip();
            if (!inTable && !prefix.isEmpty() && !prefix.endsWith("Orientation:") && !prefix.endsWith("Mutation:")) continue;
            String ns = ""; // relative names apply only within an explicit operation list
            // In table rows the first cell names ops; later cells (M / Purpose)
            // mostly mention non-ops (`smali`, `all`) — but a few real ops are
            // named only there (`tag.family.add`), so later cells contribute
            // dotted, known-namespace tokens only.
            boolean laterCell = inTable && !isFirstTableCell(section, sm.start());
            if (inTable && !laterCell && prefix.contains(" — ")) continue; // explanatory facade alias, not an op
            for (String group : span.split("\\s*·\\s*")) {
                String lastOp = null;
                for (String variant : group.split("\\s*/\\s*")) {
                    String tok = variant.trim().split("\\s+")[0];
                    if (!tok.matches("[a-z][A-Za-z0-9.]*") || tok.endsWith(".")) continue;
                    String op;
                    if (tok.contains(".")) {
                        String head = tok.substring(0, tok.indexOf('.'));
                        if ((!laterCell && inTable) || KNOWN_NS.contains(head)) {
                            op = tok;                 // absolute
                            if (!laterCell) ns = head;
                        } else if (laterCell) {
                            continue;                 // later cell, unknown head — not an op name
                        } else if (!ns.isEmpty()) {
                            op = ns + "." + tok;      // relative, e.g. type.add → map.type.add
                        } else {
                            op = tok;
                        }
                    } else if (laterCell) {
                        continue;                     // later cell, bare token — not an op name
                    } else if (lastOp != null && lastOp.contains(".")) {
                        // "/" variant inherits the parent of the previous op: add/set → map.type.set
                        op = lastOp.substring(0, lastOp.lastIndexOf('.')) + "." + tok;
                    } else if (!inTable && !ns.isEmpty()) {
                        op = ns + "." + tok;          // prose-list bare name: addnode → map.addnode
                    } else if (!inTable) {
                        continue;                     // standalone prose field or value
                    } else {
                        op = tok;                     // table bare name: callees, strings, rename…
                    }
                    ops.add(op);
                    lastOp = op;
                }
            }
        }
        return ops;
    }

    private static boolean isTableLine(String text, int offset) {
        int lineStart = text.lastIndexOf('\n', offset) + 1;
        int i = lineStart;
        while (i < text.length() && (text.charAt(i) == ' ' || text.charAt(i) == '\t')) i++;
        return i < text.length() && text.charAt(i) == '|';
    }

    /**
     * True when the offset sits inside the first cell of a markdown table row.
     * A '|' only counts as a cell delimiter when whitespace-adjacent, so
     * param lists like [target|all] don't count.
     */
    private static boolean isFirstTableCell(String text, int offset) {
        int lineStart = text.lastIndexOf('\n', offset) + 1;
        int cells = 0;
        for (int i = lineStart; i < offset; i++) {
            if (text.charAt(i) == '|'
                    && (i == lineStart || Character.isWhitespace(text.charAt(i - 1)))
                    && (i + 1 < text.length() && Character.isWhitespace(text.charAt(i + 1))))
                cells++;
        }
        return cells == 1;
    }
}
