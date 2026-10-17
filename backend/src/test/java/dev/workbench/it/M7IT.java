package dev.workbench.it;

import dev.workbench.registry.Json;
import dev.workbench.server.WorkbenchServer;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * M7 integration test (plain main-class, no JUnit): agents — scripted mission,
 * proposals review queue, accept/reject, write-scope enforcement (E_SCOPE +
 * BLOCKED), MCP server selftest over stdio. Prints PASS/FAIL; exit 1 on failure.
 * Run: gradle m7IT.
 */
public final class M7IT {

    private static int failures = 0, checks = 0;
    private static final HttpClient http = HttpClient.newHttpClient();
    private static final String BASE = "http://127.0.0.1:" + WorkbenchServer.PORT;

    public static void main(String[] args) throws Exception {
        try {
            run();
        } catch (Throwable t) {
            System.out.println("M7 IT FAILED with exception: " + t);
            t.printStackTrace(System.out);
            System.exit(1);
        }
    }

    private static void run() throws Exception {
        WorkbenchServer server = new WorkbenchServer(Path.of("web"));
        server.start();
        System.out.println("server up on " + BASE);

        // ---- ops.list meta-op
        @SuppressWarnings("unchecked")
        List<Object> ops = dataList(post("ops.list", Map.of()));
        check(ops.size() >= 100, "ops.list → " + ops.size() + " ops for MCP generation");

        // ---- ops.list marks the browser's plumbing mcp:false (22 ops the bridge never advertises)
        List<Object> opRows = dataList(post("ops.list", Map.of()));
        long hiddenN = opRows.stream().filter(o -> Boolean.FALSE.equals(((Map<?, ?>) o).get("mcp"))).count();
        check(hiddenN == 24 && opRows.stream().anyMatch(o -> "ui.prefs".equals(((Map<?, ?>) o).get("name")) && Boolean.FALSE.equals(((Map<?, ?>) o).get("mcp")))
                        && opRows.stream().anyMatch(o -> "code.decompile".equals(((Map<?, ?>) o).get("name")) && ((Map<?, ?>) o).get("mcp") == null),
                "ops.list: 24 UI-only ops carry mcp:false (ui.prefs among them, system.logs and log.client too; code.decompile does not) — got " + hiddenN);
        // ---- the log: an op error lands in <home>/logs/workbench.log with its code, op, actor and params; system.logs reads it back
        post("nope.op", Map.of());
        Map<String, Object> logs = data(post("system.logs", Map.of("tail", 20, "grep", "nope.op")));
        check(logs.get("file") != null && String.valueOf(logs.get("file")).endsWith("logs/workbench.log")
                        && logs.get("lines") instanceof List<?> ll && !ll.isEmpty() && String.valueOf(ll.get(ll.size() - 1)).contains("E_NOTFOUND nope.op actor=human"),
                "system.logs: the unknown-op error is on file with code, op and actor — " + logs.get("file"));
        data(post("log.client", Map.of("level", "error", "message", "it probe from the browser", "stack", "Error: probe\n  at x.js:1:1")));
        Map<String, Object> blog = data(post("system.logs", Map.of("tail", 5, "grep", "it probe from the browser")));
        check(blog.get("lines") instanceof List<?> bl && bl.size() == 1 && String.valueOf(bl.get(0)).contains("ERROR [browser") && String.valueOf(bl.get(0)).contains("at x.js:1:1"),
                "log.client: a browser error is on file with its stack");

        // ---- the audit trail: an agent's call lands as one line with its client; a human's read leaves nothing
        post("tool.call", Map.of("tool", "workspace", "args", Map.of(), "client", "it-probe/1.0"), "agent:auditprobe");
        post("workspace.state", Map.of());
        Map<String, Object> au = data(post("audit", Map.of("tail", 5, "actor", "auditprobe")));
        List<?> auCalls = (List<?>) au.get("calls");
        check(!auCalls.isEmpty() && "workspace.state".equals(((Map<?, ?>) auCalls.get(auCalls.size() - 1)).get("op"))
                        && "it-probe/1.0".equals(((Map<?, ?>) auCalls.get(auCalls.size() - 1)).get("client")),
                "audit: an agent's routed call is on file with op and client — " + auCalls);
        Map<String, Object> sm = data(post("audit", Map.of("summary", true)));
        check(((List<?>) sm.get("actors")).stream().anyMatch(o -> "agent:auditprobe".equals(((Map<?, ?>) o).get("actor"))),
                "audit summary lists the actor with its calls");

        // ---- ops.tools meta-op: the tool facade (ToolFacade) — the 24 tools every LLM client sees
        @SuppressWarnings("unchecked")
        List<Object> tools = dataList(post("ops.tools", Map.of()));
        java.util.Set<String> toolNames = new java.util.HashSet<>();
        Map<String, Map<String, Object>> byTool = new java.util.HashMap<>();
        int loopN = 0;
        for (Object o : tools) {
            @SuppressWarnings("unchecked")
            Map<String, Object> row = (Map<String, Object>) o;
            toolNames.add(String.valueOf(row.get("tool")));
            byTool.put(String.valueOf(row.get("tool")), row);
            if (Boolean.TRUE.equals(row.get("loop"))) loopN++;
        }
        /* PINNED on purpose (r4 L5): an accidental addition to the MCP surface must fail here.
           46 per-op tools → 24 facade tools on 2026-09-18 (one server, merged calls, the approved
           cruft removed). Bump the number WITH the change that adds a tool. */
        check(tools.size() == 26 && toolNames.size() == tools.size(),
                "ops.tools → 26 distinct facade tools (got " + tools.size() + ") — a change to the MCP surface must update this pin (includes naming_coverage)");
        check(loopN == 10 && toolNames.containsAll(List.of("symbols", "code", "xrefs", "strings", "search", "annotate", "map",
                        "map_verdict", "presence", "map_edit", "flow", "devices", "runs", "run", "breakpoints", "project", "target",
                        "mission", "review", "llm", "reversing_agent", "index", "workspace", "history", "audit", "naming_coverage")),
                "ops.tools: the 26 designed tools, 10 of them loop-visible (got " + loopN + ")");
        check(!toolNames.contains("rename") && !toolNames.contains("agent_review") && !toolNames.contains("bookmark")
                        && !toolNames.contains("device_pull") && !toolNames.contains("agent_follow"),
                "ops.tools: no per-op tools and none of the approved removals (bookmark.*, device.pull, agent.follow …)");
        check(tools.stream().allMatch(o -> Boolean.FALSE.equals(((Map<?, ?>) ((Map<?, ?>) o).get("parameters")).get("additionalProperties"))),
                "ops.tools: every schema is closed (additionalProperties:false)");
        // annotate: the ONE write tool — items[], each item requires symbol and takes name / comment / tags
        @SuppressWarnings("unchecked")
        Map<String, Object> ap = (Map<String, Object>) byTool.get("annotate").get("parameters");
        Object anItems = ((Map<?, ?>) ap.get("properties")).get("items");
        Map<?, ?> anItem = anItems instanceof Map<?, ?> aim && aim.get("items") instanceof Map<?, ?> it ? it : null;
        Map<?, ?> anProps = anItem == null ? null : (Map<?, ?>) anItem.get("properties");
        check(((List<?>) ap.get("required")).contains("items") && anItem != null
                        && ((List<?>) anItem.get("required")).equals(List.of("symbol"))
                        && anProps != null && anProps.containsKey("name") && anProps.containsKey("comment") && anProps.containsKey("tags")
                        && Boolean.FALSE.equals(anItem.get("additionalProperties"))
                        && ((List<?>) byTool.get("annotate").get("ops")).containsAll(List.of("rename", "comment", "tag.add")),
                "ops.tools: annotate schema — items[{symbol!, name, comment, tags}] closed, routes to rename+comment+tag.add");
        // action tools carry an enum selector and the union of their ops' params
        @SuppressWarnings("unchecked")
        Map<String, Object> rvp = (Map<String, Object>) ((Map<?, ?>) byTool.get("review").get("parameters")).get("properties");
        check(((Map<?, ?>) rvp.get("action")).get("enum") instanceof List<?> rl && rl.containsAll(List.of("policy", "configure", "proposals", "accept", "reject", "purge"))
                        && rvp.containsKey("newName") && rvp.containsKey("minConfidence"),
                "ops.tools: review {action enum, newName, minConfidence}");
        @SuppressWarnings("unchecked")
        Map<String, Object> llp = (Map<String, Object>) ((Map<?, ?>) byTool.get("llm").get("parameters")).get("properties");
        check(llp.containsKey("create") && llp.containsKey("auth") && llp.containsKey("newId") && llp.containsKey("key"),
                "ops.tools: llm schema carries the create gate, auth, newId and key");
        @SuppressWarnings("unchecked")
        Map<String, Object> dvp = (Map<String, Object>) ((Map<?, ?>) byTool.get("devices").get("parameters")).get("properties");
        check(dvp.containsKey("serial") && dvp.containsKey("pkg") && ((Map<?, ?>) dvp.get("action")).get("enum") instanceof List<?> dl && dl.contains("components"),
                "ops.tools: devices {action components, serial, pkg}");
        // the boundary itself: operator tools are advertised to external clients but
        // NOT dispatchable by the internal loop (regression guard)
        java.util.Set<String> loop = dev.workbench.llm.AgentLoop.loopToolNames();
        check(!loop.contains("mission") && !loop.contains("review") && !loop.contains("llm") && !loop.contains("target")
                        && loop.contains("annotate") && loop.contains("code") && loop.contains("symbols") && loop.size() == 10 && loop.contains("naming_coverage"),
                "loop boundary: mission/review/llm/target NOT loop-callable, annotate/code/symbols are (10 loop tools)");
        // tool.call: the bridge's one entry point — unknown tool, bad action, an agent on an operator tool
        Map<String, Object> unknownTool = post("tool.call", Map.of("tool", "nope", "args", Map.of()));
        check(unknownTool.get("error") != null && String.valueOf(unknownTool.get("error")).contains("E_UNSUPPORTED"),
                "tool.call: unknown tool → E_UNSUPPORTED");
        Map<String, Object> badAction = post("tool.call", Map.of("tool", "project", "args", Map.of("action", "explode")));
        check(badAction.get("error") != null && String.valueOf(badAction.get("error")).contains("E_SYNTAX")
                        && String.valueOf(badAction.get("error")).contains("known"),
                "tool.call: unknown action → E_SYNTAX naming the allowed actions");
        Map<String, Object> agentMission = post("tool.call", Map.of("tool", "mission", "args", Map.of("action", "stop", "id", "m0")), "agent:qa");
        check(agentMission.get("error") != null && String.valueOf(agentMission.get("error")).contains("E_SCOPE"),
                "tool.call: an agent actor on mission stop → E_SCOPE (the routed op's own actor rule: agents propose, humans dispose)");
        Map<String, Object> wsState = data(post("tool.call", Map.of("tool", "workspace", "args", Map.of())));
        check(wsState != null && wsState.containsKey("activeProject"), "tool.call: workspace → workspace.state data");
        // ---- heap headroom rides on the workspace state: a client reads it before opening another apk (2026-09-21)
        Map<?, ?> heap = wsState == null ? null : (Map<?, ?>) wsState.get("heap");
        check(heap != null && ((Number) heap.get("maxBytes")).longValue() > ((Number) heap.get("liveBytes")).longValue()
                        && ((Number) heap.get("headroomBytes")).longValue() > 0,
                "workspace.state.heap {maxBytes, liveBytes, headroomBytes}: " + heap);

        // ---- real target (scripted mission works on real classes)
        Map<String, Object> opened = data(post("target.open",
                Map.of("path", System.getenv().getOrDefault("WORKBENCH_QUALIFY_FIXTURE", System.getProperty("user.home") + "/jeb/test_visionocr.apk"))));
        String tid = String.valueOf(opened.get("id"));
        try {
            awaitReady(tid);

            // ---- partial decompilation (JEB 5.45 MCP parity) + visible-only xref cuts
            // a MEMBER symbol decompiles the method only (decompileMethod — the class is not
            // decompiled); scope=class the whole class; tokens only on request; a window slices
            Map<String, Object> dm = data(post("code.decompile", Map.of("symbol", "Lm76/an;->a(Lm76/aj;)V")));
            Map<String, Object> dc = data(post("code.decompile", Map.of("symbol", "Lm76/an;->a(Lm76/aj;)V", "scope", "class")));
            int mLines = ((Number) dm.get("lineCount")).intValue(), cLines = ((Number) dc.get("lineCount")).intValue();
            check("method".equals(dm.get("scope")) && Boolean.TRUE.equals(dm.get("decompiled")) && mLines > 0 && mLines < cLines
                            && !dm.containsKey("tokens") && "class".equals(dc.get("scope")) && !dc.containsKey("tokens")
                            && "method".equals(((Map<?, ?>) dm.get("symbol")).get("kind")),
                    "code.decompile: a method symbol decompiles the method ONLY (" + mLines + " lines vs " + cLines + " for scope=class); tokens absent unless asked");
            Map<String, Object> dw = data(post("code.decompile", Map.of("symbol", "Lm76/an;", "tokens", true, "window", "1,3")));
            check(dw.containsKey("tokens") && ((Number) ((Map<?, ?>) dw.get("window")).get("to")).intValue() == 3
                            && String.valueOf(dw.get("text")).split("\n").length <= 3,
                    "code.decompile: scope=class with tokens:true and window 1,3 → token map present, 3 lines of text");
            // a first-time decompile is unsaved work (the JEB database stores decompilations): the
            // close dialog must offer to save it — target.changes counts it separately from edits.
            // This IT home may carry a jdb2 with earlier decompilations saved, so find a class JEB has
            // no unit for yet (code.decompile reports fresh:true) and watch the count rise by one.
            int decBefore = ((Number) data(post("target.changes", Map.of("target", tid))).get("decompiled")).intValue();
            String freshAddr = null;
            for (Object o : dataList(post("symbol.search", Map.of("query", "m76")))) {
                String addr = String.valueOf(((Map<?, ?>) o).get("addr"));
                if (!addr.startsWith("L") || !addr.endsWith(";")) continue;
                Map<String, Object> dd = data(post("code.decompile", Map.of("symbol", addr, "scope", "class")));
                if (Boolean.TRUE.equals(dd.get("fresh"))) { freshAddr = addr; break; }
            }
            int decAfter = ((Number) data(post("target.changes", Map.of("target", tid))).get("decompiled")).intValue();
            check(freshAddr != null && decAfter == decBefore + 1,
                    "target.changes: a first-time decompile (" + freshAddr + ", fresh:true) counts as unsaved work (decompiled " + decBefore + " → " + decAfter + ")");
            // the resolver forgives what an agent types from what it read: an inferred return type,
            // a missing descriptor — class + name (+ params) is enough; ambiguity lists the candidates
            Map<String, Object> wrongRet = data(post("code.decompile", Map.of("symbol", "Lm76/an;->a(Lm76/aj;)I")));   // real: …)V
            check("method".equals(wrongRet.get("scope")) && "Lm76/an;->a(Lm76/aj;)V".equals(((Map<?, ?>) wrongRet.get("symbol")).get("addr")),
                    "resolver: a wrong return type still resolves by class+name+params (got " + ((Map<?, ?>) wrongRet.get("symbol")).get("addr") + ")");
            Map<String, Object> nameOnly = post("code.decompile", Map.of("symbol", "Lm76/an;->a"));
            check(Boolean.TRUE.equals(nameOnly.get("ok")) || String.valueOf(nameOnly.get("error")).contains("candidates"),
                    "resolver: name-only resolves when unique or lists the candidates (" + (Boolean.TRUE.equals(nameOnly.get("ok")) ? "resolved" : String.valueOf(nameOnly.get("error"))) + ")");
            // a comment shows up in the NEXT class decompile (the cached class decompilation is evicted)
            data(post("comment", Map.of("target", tid, "symbol", "Lm76/an;->a(Lm76/aj;)V", "text", "qa: visible in the class view")));
            Map<String, Object> afterCmt = data(post("code.decompile", Map.of("symbol", "Lm76/an;", "scope", "class")));
            check(String.valueOf(afterCmt.get("text")).contains("qa: visible in the class view"),
                    "comment: the class-scoped decompile right after a comment contains it (cache evicted)");
            // ---- the review switch: APPLY DIRECTLY is the default (2026-09-18); the queue checks below run under manual
            check("auto".equals(data(post("review.policy", Map.of())).get("mode")), "review.policy: apply-directly (auto) is the default on a fresh home");
            data(post("review.configure", Map.of("mode", "manual")));
            // an AGENT's rename carries its reason: the proposal shows rationale + confidence (the
            // review gate reads them; without them every agent write was "no rationale")
            Map<String, Object> reasoned = data(post("rename", Map.of("target", tid, "symbol", "Lm76/an;->a(Lm76/aj;)V", "name", "qaReasoned",
                    "rationale", "it parses the aj record and emits a triage row", "confidence", 0.8), "agent:qa"));
            String rpid = String.valueOf(reasoned.get("id"));
            Map<?, ?> rrow = null;
            for (Object o : dataList(post("agent.proposals", Map.of()))) if (rpid.equals(((Map<?, ?>) o).get("id"))) rrow = (Map<?, ?>) o;
            check(rrow != null && "it parses the aj record and emits a triage row".equals(rrow.get("rationale")) && rrow.get("confidence") != null,
                    "proposals: an agent rename carries rationale + confidence (" + (rrow == null ? "?" : rrow.get("confidence")) + ")");
            data(post("agent.review", Map.of("id", rpid, "action", "reject")));
            // xrefs: every row by default; limit:N → {rows, total, truncated} — never a silent cut
            String xsym = "Lm76/an;";
            List<Object> allRefs = dataList(post("xref.to", Map.of("symbol", xsym)));
            if (allRefs.isEmpty()) {   // pick a referenced member of the class, if the class itself has no direct refs
                for (Object mo : (List<?>) data(post("code.outline", Map.of("class", "Lm76/an;"))).getOrDefault("methods", List.of())) {
                    String a = String.valueOf(((Map<?, ?>) mo).get("addr"));
                    List<Object> got = dataList(post("xref.to", Map.of("symbol", a)));
                    if (!got.isEmpty()) { xsym = a; allRefs = got; break; }
                }
            }
            Map<String, Object> cut = data(post("xref.to", Map.of("symbol", xsym, "limit", 1)));
            check(!allRefs.isEmpty() && ((Number) cut.get("total")).intValue() == allRefs.size()
                            && ((List<?>) cut.get("rows")).size() == 1 && Boolean.valueOf(allRefs.size() > 1).equals(cut.get("truncated")),
                    "xref.to " + xsym + ": all " + allRefs.size() + " rows by default; limit:1 → {rows:1, total:" + cut.get("total") + ", truncated:" + cut.get("truncated") + "}");

            // ---- the remembered position (target.view / target.view.set → view.json in the sidecar)
            Map<String, Object> vs = data(post("target.view.set", Map.of("target", tid, "view",
                    Map.of("class", "m76.an", "caret", Map.of("line", 7, "col", 3), "scroll", 120, "tabs", List.of(Map.of("symbol", "m76.an", "simple", "an", "active", true))))));
            Map<?, ?> vg = (Map<?, ?>) data(post("target.view", Map.of("target", tid))).get("view");
            check(Boolean.TRUE.equals(vs.get("saved")) && vg != null && "m76.an".equals(vg.get("class"))
                            && ((Number) ((Map<?, ?>) vg.get("caret")).get("line")).intValue() == 7,
                    "target.view: the position round-trips (class m76.an, caret 7:3, 1 tab)");
            Map<String, Object> bad = post("target.view.set", Map.of("target", tid, "view", Map.of("caret", Map.of("line", 1))));
            check(bad.get("error") != null && String.valueOf(bad.get("error")).contains("E_SYNTAX"), "target.view.set: a view without a class is refused");

            // ---- spawn scripted mission
            Map<String, Object> m = data(post("agent.spawn",
                    Map.of("goal", "triage-package m76", "mode", "scripted")));
            String mid = String.valueOf(m.get("id"));
            String agentId = String.valueOf(m.get("agentId"));
            check("running".equals(m.get("state")) || "done".equals(m.get("state")),
                    "agent.spawn scripted → mission " + mid + " agent " + agentId
                            + " scope " + m.get("scope"));

            // wait for done
            String state = "";
            for (int i = 0; i < 60 && !"done".equals(state); i++) {
                Thread.sleep(1_000);
                @SuppressWarnings("unchecked")
                List<Object> ms = dataList(post("agent.missions", Map.of()));
                for (Object o : ms)
                    if (mid.equals(((Map<?, ?>) o).get("id"))) state = String.valueOf(((Map<?, ?>) o).get("state"));
            }
            check("done".equals(state), "scripted mission completed (state=" + state + ")");

            // ---- proposals + activity
            @SuppressWarnings("unchecked")
            List<Object> props = dataList(post("agent.proposals", Map.of("mission", agentId)));
            check(props.size() >= 2, "agent.proposals → " + props.size() + " pending");
            Map<String, Object> renameProp = null, tagProp = null;
            for (Object o : props) {
                @SuppressWarnings("unchecked")
                Map<String, Object> p = (Map<String, Object>) o;
                if ("rename".equals(p.get("kind")) && renameProp == null) renameProp = p;
                if ("tag".equals(p.get("kind")) && tagProp == null) tagProp = p;
            }
            check(renameProp != null && tagProp != null, "queue has a rename and a tag proposal");
            @SuppressWarnings("unchecked")
            List<Object> act = dataList(post("agent.activity", Map.of("agent", agentId)));
            check(!act.isEmpty() && act.stream().anyMatch(a -> "note".equals(((Map<?, ?>) a).get("kind"))),
                    "agent.activity → " + act.size() + " rows");

            // ---- accept the rename → applied + history row
            String newName = String.valueOf(renameProp.get("newName"));
            Map<String, Object> settled = data(post("agent.review",
                    Map.of("id", renameProp.get("id"), "action", "accept")));
            check("accepted".equals(settled.get("state")), "agent.review accept → accepted");
            Map<String, Object> dec = data(post("code.decompile",
                    Map.of("target", tid, "symbol", renameProp.get("symbol"))));
            check(String.valueOf(dec.get("text")).contains(newName),
                    "accepted rename visible in decompile (" + newName + ")");
            @SuppressWarnings("unchecked")
            List<Object> decMethods = (List<Object>) dec.getOrDefault("methods", List.of());
            final String oldNm = String.valueOf(renameProp.get("oldName"));
            check(decMethods.stream().anyMatch(o -> newName.equals(((Map<?, ?>) o).get("name"))
                            && Boolean.TRUE.equals(((Map<?, ?>) o).get("renamed"))
                            && oldNm.equals(((Map<?, ?>) o).get("origName"))),
                    "decompile.methods carries renamed:true + origName for the renamed method (the crumb's was:)");
            Map<String, Object> ss = data(post("target.saveStatus", Map.of("target", tid)));
            check(Boolean.FALSE.equals(ss.get("saving")), "target.saveStatus idle → saving:false");
            @SuppressWarnings("unchecked")
            List<Object> actRows = dataList(post("agent.activity", Map.of("agent", agentId)));
            String wantRow = "ACCEPTED · propose rename " + renameProp.get("symbol") + " → " + newName;
            check(actRows.stream().anyMatch(a -> String.valueOf(((Map<?, ?>) a).get("text")).contains(wantRow)),
                    "the ACCEPTED row names the full symbol (" + wantRow + ")");
            @SuppressWarnings("unchecked")
            List<Object> hist = dataList(post("history.list", Map.of("target", tid)));
            boolean histOk = !hist.isEmpty()
                    && String.valueOf(((Map<?, ?>) hist.get(0)).get("actor")).contains("agent:" + agentId)
                    && String.valueOf(((Map<?, ?>) hist.get(0)).get("actor")).contains("accepted by human");
            check(histOk, "history row actor 'agent:" + agentId + " accepted by human'");

            // ---- reject the tag → not applied
            Map<String, Object> rej = data(post("agent.review",
                    Map.of("id", tagProp.get("id"), "action", "reject")));
            check("rejected".equals(rej.get("state")), "agent.review reject → rejected");
            Map<String, Object> tl = data(post("tag.list", Map.of("target", tid, "family", "triage")));
            check(((List<?>) tl.get("tags")).isEmpty(), "rejected tag not applied");

            // ---- an UNMISSIONED llm actor cannot settle proposals either
            // (app-review-opus P0-2: agent.* mutations are human-only — before this
            // gate the refusal lived inside the mission!=null branch, so a raw
            // llm:mcp client could accept its own proposal)
            Map<String, Object> rogue = post("agent.review",
                    Map.of("id", renameProp.get("id"), "action", "accept"), "llm:mcp");
            check(Boolean.FALSE.equals(rogue.get("ok"))
                            && String.valueOf(rogue.get("error")).contains("E_SCOPE"),
                    "unmissioned llm agent.review → E_SCOPE (agents propose, humans dispose)");

            // ---- write-scope enforcement: agent-1's mission is pkg:m76
            Map<String, Object> outOfScope = post("rename",
                    Map.of("target", "t1", "symbol", "onMessage", "name", "scopeProbe"), "llm:" + agentId);
            check(Boolean.FALSE.equals(outOfScope.get("ok"))
                            && String.valueOf(outOfScope.get("error")).contains("E_SCOPE"),
                    "out-of-scope llm rename → E_SCOPE");
            @SuppressWarnings("unchecked")
            List<Object> act2 = dataList(post("agent.activity", Map.of("agent", agentId, "kinds", "blocked")));
            check(!act2.isEmpty(), "BLOCKED activity row recorded");
            Map<String, Object> inScope = post("rename",
                    Map.of("target", tid, "symbol", "Lm76/a;", "name", "scopeProbe"), "llm:" + agentId);
            check(Boolean.TRUE.equals(inScope.get("ok"))
                            && Boolean.TRUE.equals(((Map<?, ?>) inScope.get("data")).get("proposal")),
                    "in-scope llm rename → proposal (not applied)");
            // clean the in-scope proposal
            @SuppressWarnings("unchecked")
            List<Object> props2 = dataList(post("agent.proposals", Map.of("mission", agentId)));
            for (Object o : props2) {
                @SuppressWarnings("unchecked")
                Map<String, Object> p = (Map<String, Object>) o;
                if ("scopeProbe".equals(p.get("newName")))
                    data(post("agent.review", Map.of("id", p.get("id"), "action", "reject")));
            }

            // ---- bare package-prefix scope (dogfood: bare prefix previously
            // fell through to always-block — spec §2 allows "package/namespace")
            data(post("agent.setscope", Map.of("id", mid, "scope", "m76")));
            Map<String, Object> bareIn = post("rename",
                    Map.of("target", tid, "symbol", "Lm76/a;", "name", "bareScopeProbe"), "llm:" + agentId);
            check(Boolean.TRUE.equals(bareIn.get("ok"))
                            && Boolean.TRUE.equals(((Map<?, ?>) bareIn.get("data")).get("proposal")),
                    "bare-prefix scope: in-scope llm rename → proposal");
            Map<String, Object> bareOut = post("rename",
                    Map.of("target", tid, "symbol", "Landroid/os/Bundle;", "name", "bareScopeProbe"), "llm:" + agentId);
            check(Boolean.FALSE.equals(bareOut.get("ok"))
                            && String.valueOf(bareOut.get("error")).contains("E_SCOPE"),
                    "bare-prefix scope: out-of-scope llm rename → E_SCOPE");
            @SuppressWarnings("unchecked")
            List<Object> props3 = dataList(post("agent.proposals", Map.of("mission", agentId)));
            for (Object o : props3) {
                @SuppressWarnings("unchecked")
                Map<String, Object> p = (Map<String, Object>) o;
                if ("bareScopeProbe".equals(p.get("newName")))
                    data(post("agent.review", Map.of("id", p.get("id"), "action", "reject")));
            }
            data(post("agent.setscope", Map.of("id", mid, "scope", "pkg:m76")));

            // ---- lifecycle: pause / resume / stop
            Map<String, Object> m2 = data(post("agent.spawn",
                    Map.of("goal", "triage-package android", "mode", "scripted")));
            String mid2 = String.valueOf(m2.get("id"));
            Map<String, Object> paused = data(post("agent.pause", Map.of("id", mid2)));
            check("paused".equals(paused.get("state")), "agent.pause → paused");
            Map<String, Object> resumed = data(post("agent.resume", Map.of("id", mid2)));
            check("running".equals(resumed.get("state")) || "done".equals(resumed.get("state")),
                    "agent.resume → running");
            Map<String, Object> stopped = data(post("agent.stop", Map.of("id", mid2)));
            check("stopped".equals(stopped.get("state")), "agent.stop → stopped");

            // ---- budget ceiling (user bug: "topping up and clicking resume does nothing"): a
            // mission paused AT the budget advertises budgetNeeded; a bare resume is refused
            // LOUDLY (it used to silently re-park with the same message); resume{budget} raises
            // and clears the need. A keyless local provider on a dead port: the ceiling guard
            // fires BEFORE any request, so the pause needs no network at all.
            data(post("llm.configure", Map.of("id", "m7it-dead", "create", true, "label", "M7 dead",
                    "baseUrl", "http://127.0.0.1:1/v1", "models", "m1", "model", "m1", "local", true)));
            // the model's context window is per provider: it is the loop's only ceiling (no arbitrary cap)
            Map<?, ?> deadRow = null;
            for (Object o : dataList(post("llm.providers", Map.of()))) if ("m7it-dead".equals(((Map<?, ?>) o).get("id"))) deadRow = (Map<?, ?>) o;
            check(deadRow != null && ((Number) deadRow.get("contextTokens")).intValue() == 1_000_000,
                    "llm.providers: a new provider's window defaults to 1,000,000 tokens (got " + (deadRow == null ? "?" : deadRow.get("contextTokens")) + ")");
            Map<String, Object> ctxBad = post("llm.configure", Map.of("id", "m7it-dead", "contextTokens", 100));
            Map<String, Object> ctxOk = data(post("llm.configure", Map.of("id", "m7it-dead", "contextTokens", 65536)));
            check(ctxBad.get("error") != null && String.valueOf(ctxBad.get("error")).contains("E_SYNTAX") && ((Number) ctxOk.get("contextTokens")).intValue() == 65536,
                    "llm.configure: contextTokens (the model's window) is settable per provider; 100 is refused, 65536 sticks");
            Map<String, Object> mb = data(post("agent.spawn", Map.of("goal", "budget ceiling probe",
                    "model", "m7it-dead:m1", "budget", 300)));   // < turn 1's ~750-token prompt estimate (600 tool schemas + prompt): the ceiling guard fires BEFORE any request
            String midB = String.valueOf(mb.get("id"));
            Map<?, ?> mbRow = null;
            for (int i = 0; i < 40; i++) {
                Thread.sleep(250);
                for (Object o : dataList(post("agent.missions", Map.of())))
                    if (midB.equals(((Map<?, ?>) o).get("id"))) mbRow = (Map<?, ?>) o;
                if (mbRow != null && "paused".equals(mbRow.get("state")) && mbRow.get("budgetNeeded") != null) break;
            }
            int need = mbRow == null || !(mbRow.get("budgetNeeded") instanceof Number nn) ? 0 : nn.intValue();
            check(mbRow != null && "paused".equals(mbRow.get("state")) && need > 300,
                    "budget: a 300-token mission pauses AT the ceiling and advertises budgetNeeded=" + need
                            + " (state=" + (mbRow == null ? "?" : mbRow.get("state")) + ")");
            Map<String, Object> bare = post("agent.resume", Map.of("id", midB));
            check(bare.get("error") != null && String.valueOf(bare.get("error")).contains("paused at its budget"),
                    "budget: resume WITHOUT a raise is refused and names the raise (got: " + bare.get("error") + ")");
            Map<String, Object> raised = data(post("agent.resume", Map.of("id", midB, "budget", need)));
            check(((Number) raised.get("budget")).intValue() == need && !"paused".equals(raised.get("state")),
                    "budget: resume{budget} raises to " + need + " and leaves paused (state=" + raised.get("state") + ")");
            for (int i = 0; i < 40; i++) {   // the dead port blocks the loop fast; the need must be cleared either way
                Thread.sleep(250); mbRow = null;
                for (Object o : dataList(post("agent.missions", Map.of())))
                    if (midB.equals(((Map<?, ?>) o).get("id"))) mbRow = (Map<?, ?>) o;
                if (mbRow != null && !"running".equals(mbRow.get("state"))) break;
            }
            check(mbRow != null && mbRow.get("budgetNeeded") == null,
                    "budget: after the raise the mission no longer advertises a need (state=" + (mbRow == null ? "?" : mbRow.get("state")) + ")");
            post("agent.stop", Map.of("id", midB));
            data(post("llm.remove", Map.of("id", "m7it-dead")));

            // ---- turn cap (user: "same behaviour as the budget limit — the user should be able to
            // raise it"): the canned fixture provider runs real turns without a network. turns:1 →
            // after turn 1 the loop PARKS (not done) and advertises turnsNeeded; a bare resume is
            // refused naming the raise; resume{turns} continues the same context to the final answer.
            Map<String, Object> mt = data(post("agent.spawn", Map.of("goal", "turn cap probe",
                    "model", "fixture:fixture-1", "turns", 1, "target", tid)));
            String midT = String.valueOf(mt.get("id"));
            Map<?, ?> mtRow = null;
            for (int i = 0; i < 80; i++) {
                Thread.sleep(250);
                for (Object o : dataList(post("agent.missions", Map.of())))
                    if (midT.equals(((Map<?, ?>) o).get("id"))) mtRow = (Map<?, ?>) o;
                if (mtRow != null && "paused".equals(mtRow.get("state")) && mtRow.get("turnsNeeded") != null) break;
            }
            int tneed = mtRow == null || !(mtRow.get("turnsNeeded") instanceof Number tn) ? 0 : tn.intValue();
            check(mtRow != null && "paused".equals(mtRow.get("state")) && tneed > 1 && ((Number) mtRow.get("turnsDone")).intValue() == 1,
                    "turn cap: a 1-turn mission PARKS at the cap (not done) with turnsNeeded=" + tneed + " (state=" + (mtRow == null ? "?" : mtRow.get("state")) + ", turnsDone=" + (mtRow == null ? "?" : mtRow.get("turnsDone")) + ")");
            Map<String, Object> bareT = post("agent.resume", Map.of("id", midT));
            check(bareT.get("error") != null && String.valueOf(bareT.get("error")).contains("paused at its turn limit"),
                    "turn cap: resume WITHOUT a raise is refused and names the raise (got: " + bareT.get("error") + ")");
            Map<String, Object> raisedT = data(post("agent.resume", Map.of("id", midT, "turns", 6)));
            check(((Number) raisedT.get("maxTurns")).intValue() == 6 && !"paused".equals(raisedT.get("state")),
                    "turn cap: resume{turns:6} raises the cap and leaves paused (state=" + raisedT.get("state") + ")");
            for (int i = 0; i < 80; i++) {
                Thread.sleep(250); mtRow = null;
                for (Object o : dataList(post("agent.missions", Map.of())))
                    if (midT.equals(((Map<?, ?>) o).get("id"))) mtRow = (Map<?, ?>) o;
                if (mtRow != null && "done".equals(mtRow.get("state"))) break;
            }
            check(mtRow != null && "done".equals(mtRow.get("state")) && ((Number) mtRow.get("turnsDone")).intValue() >= 3 && mtRow.get("turnsNeeded") == null,
                    "turn cap: after the raise the SAME mission continues to its final answer (state=" + (mtRow == null ? "?" : mtRow.get("state")) + ", turnsDone=" + (mtRow == null ? "?" : mtRow.get("turnsDone")) + ")");
            // × on a finished run: dismissed (out of the view, record kept), undo brings it back; a live one is refused
            Map<String, Object> dis = data(post("agent.dismiss", Map.of("id", midT)));
            Map<String, Object> undis = data(post("agent.dismiss", Map.of("id", midT, "undo", true)));
            check(Boolean.TRUE.equals(dis.get("dismissed")) && undis.get("dismissed") == null,
                    "agent.dismiss: a done mission is dismissed (dismissed:true) and undo clears it");
            post("agent.purge", Map.of("mission", midT));
            // the record is saved for good in the project sidecar: mission + turns + rows
            Map<String, Object> tinfo = data(post("target.info", Map.of("target", tid)));
            java.io.File home = new java.io.File(String.valueOf(tinfo.get("jdb2"))).getParentFile().getParentFile().getParentFile();
            Thread.sleep(1500);   // the archive write is debounced (immediate at a terminal state, but the last row may schedule again)
            java.io.File[] projDirs = new java.io.File(home, "projects").listFiles(java.io.File::isDirectory);
            java.io.File recFile = null;
            if (projDirs != null) for (java.io.File d : projDirs) { java.io.File f = new java.io.File(new java.io.File(d, "missions"), midT + ".json"); if (f.isFile()) recFile = f; }
            Map<?, ?> rec = recFile == null ? null : (Map<?, ?>) dev.workbench.registry.Json.parse(java.nio.file.Files.readString(recFile.toPath()));
            check(rec != null && "done".equals(((Map<?, ?>) rec.get("mission")).get("state")) && ((List<?>) rec.get("turns")).size() >= 3 && ((List<?>) rec.get("activity")).size() >= 6,
                    "mission archive: projects/<slug>/missions/" + midT + ".json holds the done mission, " + (rec == null ? "?" : ((List<?>) rec.get("turns")).size()) + " turns and " + (rec == null ? "?" : ((List<?>) rec.get("activity")).size()) + " rows");

            // ---- agent UX: a bare model name blocks, and the block must say how
            // to spell the spec (agents guess --model <model> and burn a turn).
            // v4-flash is a REAL registered model but not deepseek's default, so
            // resolve's bare-default-model fallback cannot rescue it.
            Map<String, Object> m3 = data(post("agent.spawn",
                    Map.of("goal", "probe model spec", "model", "deepseek-flash")));
            check("blocked".equals(m3.get("state")), "bare --model → blocked");
            /* agents P0.3: the mission payload itself carries the reason — the card
               must not have to dredge the activity stream for it */
            check(String.valueOf(m3.get("reason")).contains("unknown provider in --model"),
                    "blocked mission carries reason in its payload (got: " + m3.get("reason") + ")");
            @SuppressWarnings("unchecked")
            List<Object> blk = dataList(post("agent.activity",
                    Map.of("agent", m3.get("agentId"), "kinds", "blocked")));
            String blkText = blk.isEmpty() ? ""
                    : String.valueOf(((Map<?, ?>) blk.get(blk.size() - 1)).get("text"));
            check(blkText.contains("provider:model")
                            && blkText.contains("deepseek:deepseek-flash")
                            && blkText.contains("ollama") && blkText.contains("openrouter"),
                    "block names provider:model + example + registered ids");
            System.out.println("  block: " + blkText);

            // ---- the close dialog's contract (target.changes / target.close {save}):
            // DISCARD must really discard — the unsaved edit must NOT replay on reopen.
            // Proof is target.changes.symbols==0 (reads the SAME dirtyReplayFor the
            // crash-recovery replay uses — index-free DB truth), plus lastSaved (the jdb2
            // mtime) as the write-witness. `renameProp.get("symbol")` is an unambiguous
            // addr; the bare original name ("a") is not a valid rename selector.
            String sym = String.valueOf(renameProp.get("symbol"));
            String oldName = String.valueOf(renameProp.get("oldName"));
            String apkPath = System.getenv().getOrDefault("WORKBENCH_QUALIFY_FIXTURE", System.getProperty("user.home") + "/jeb/test_visionocr.apk");
            Map<String, Object> ch = data(post("target.changes", Map.of("target", tid)));
            String savedBefore = String.valueOf(ch.get("lastSaved"));
            check(((Number) ch.get("renamed")).intValue() >= 1 && ((Number) ch.get("symbols")).intValue() >= 1,
                    "target.changes: the accepted rename is an unsaved symbol (renamed=" + ch.get("renamed") + ")");
            // a comment on the same addr → the commented counter (both ops, one distinct symbol)
            data(post("comment", Map.of("target", tid, "symbol", sym, "text", "qa close-dialog note")));
            Map<String, Object> chC = data(post("target.changes", Map.of("target", tid)));
            check(((Number) chC.get("commented")).intValue() == 1 && ((Number) chC.get("renamed")).intValue() >= 1
                            && ((Number) chC.get("symbols")).intValue() == 1,
                    "target.changes: a comment on the renamed addr counts under commented and stays ONE distinct symbol (commented="
                            + chC.get("commented") + ", renamed=" + chC.get("renamed") + ", symbols=" + chC.get("symbols") + ") · history tail: "
                            + describeHistoryTail(tid, 3));
            // save must be a real boolean: "1" would parse to false = silent DISCARD (review A5) — refused, target untouched
            Map<String, Object> badSave = post("target.close", Map.<String, Object>of("target", tid, "save", "1"));
            check(badSave.get("error") != null && String.valueOf(badSave.get("error")).contains("E_SYNTAX")
                            && ((Number) data(post("target.changes", Map.of("target", tid))).get("symbols")).intValue() >= 1,
                    "target.close: save=\"1\" (string) is refused with E_SYNTAX and the target stays open with its edits");
            // ---- a rename JEB cannot apply (the name is taken) is REFUSED, never recorded (2026-09-13); discarded with the rest below
            Map<String, Object> coll1 = post("rename", Map.of("target", tid, "symbol", "Lm76/ao;", "name", "M7Collide"));
            check(Boolean.TRUE.equals(coll1.get("ok")), "class rename Lm76/ao; → M7Collide applied");
            Map<String, Object> coll2 = post("rename", Map.of("target", tid, "symbol", "Lm76/ap;", "name", "M7Collide"));
            check(!Boolean.TRUE.equals(coll2.get("ok")) && String.valueOf(coll2.get("error")).contains("refused"),
                    "a colliding class rename is refused with a reason: " + String.valueOf(coll2.get("error")));
            Map<String, Object> apNow = data(post("symbol.resolve", Map.of("target", tid, "symbol", "Lm76/ap;")));
            check("ap".equals(apNow.get("name")), "the refused rename left Lm76/ap; untouched (name=" + apNow.get("name") + ")");
            // ---- the reviewer edits a rename before accepting (2026-09-13): the edit is applied, the
            //      agent's original kept as proposedName, the feed says so; a bad edit leaves it pending
            Map<String, Object> edited = data(post("rename", Map.of("target", tid, "symbol", "Lm76/an;->a(Lm76/aj;)I", "name", "agentPick"), "agent:qa"));
            String editId = String.valueOf(edited.get("id"));
            check(Boolean.TRUE.equals(edited.get("proposal")) && editId.startsWith("pp"), "a second rename proposal filed by agent:qa (" + editId + ")");
            Map<String, Object> badEdit = post("agent.review", Map.of("id", editId, "action", "accept", "newName", "9 not an identifier"));
            boolean stillPending = dataList(post("agent.proposals", Map.of())).stream().anyMatch(o -> editId.equals(((Map<?, ?>) o).get("id")));
            check(badEdit.get("error") != null && String.valueOf(badEdit.get("error")).contains("identifier") && stillPending,
                    "an invalid edited name is refused and the proposal stays pending: " + badEdit.get("error"));
            Map<String, Object> okEdit = data(post("agent.review", Map.of("id", editId, "action", "accept", "newName", "humanPick")));
            check("accepted".equals(okEdit.get("state")) && "humanPick".equals(okEdit.get("newName")) && "agentPick".equals(okEdit.get("proposedName")),
                    "accept with newName applies the edit and keeps the original (newName=" + okEdit.get("newName") + ", proposedName=" + okEdit.get("proposedName") + ")");
            Map<String, Object> decEdit = data(post("code.decompile", Map.of("target", tid, "symbol", "Lm76/an;")));
            check(String.valueOf(decEdit.get("text")).contains("humanPick") && !String.valueOf(decEdit.get("text")).contains("agentPick"),
                    "the edited name is what landed in the class");
            @SuppressWarnings("unchecked")
            List<Object> actEdit = dataList(post("agent.activity", Map.of("agent", "agent:qa")));
            check(actEdit.stream().anyMatch(a -> String.valueOf(((Map<?, ?>) a).get("text")).contains("→ humanPick (edited from agentPick)")),
                    "the ACCEPTED row says the name was edited");
            // ---- a declaration's TYPE word is a token (2026-09-18): `aj arg1` in Lm76/an;'s a(Lm76/aj;)… declarations
            Map<String, Object> decTok = data(post("code.decompile", Map.of("target", tid, "symbol", "Lm76/an;", "scope", "class", "tokens", true)));
            String[] tokLines = String.valueOf(decTok.get("text")).split("\n", -1);
            @SuppressWarnings("unchecked")
            List<Object> tokList = (List<Object>) decTok.getOrDefault("tokens", List.of());
            boolean declType = false;
            for (Object o : tokList) {
                Map<?, ?> t = (Map<?, ?>) o;
                if (!"type".equals(t.get("kind")) || !"Lm76/aj;".equals(t.get("addr"))) continue;
                int ln = ((Number) t.get("line")).intValue(), c0 = ((Number) t.get("cs")).intValue(), c1 = ((Number) t.get("ce")).intValue();
                String lt = tokLines[ln - 1];
                if ("aj".equals(lt.substring(c0, c1)) && lt.substring(c1).matches("^ \\w+[,)].*")) declType = true;   // `aj argN,` / `aj argN)`
            }
            check(declType, "token map: a parameter's type word (`aj argN`) is a type token with the class addr");
            // ---- the review switch (2026-09-18): manual by default; auto applies an agent's write at once
            Map<String, Object> pol = data(post("review.policy", Map.of()));
            check("manual".equals(pol.get("mode")), "review.policy: manual as configured above (mode=" + pol.get("mode") + ")");
            Map<String, Object> polAgent = post("review.configure", Map.of("mode", "auto"), "agent:qa");
            check(polAgent.get("error") != null && String.valueOf(polAgent.get("error")).contains("E_SCOPE"), "review.configure is refused to an agent actor");
            Map<String, Object> polAuto = data(post("review.configure", Map.of("mode", "auto", "minConfidence", 0.8)));
            check("auto".equals(polAuto.get("mode")) && ((Number) polAuto.get("minConfidence")).doubleValue() == 0.8, "review.configure → auto with a 0.8 confidence floor");
            Map<String, Object> low = data(post("rename", Map.of("target", tid, "symbol", "Lm76/aq;", "name", "AutoLow", "confidence", 0.5), "agent:qa"));
            check(Boolean.FALSE.equals(low.get("applied")) && "pending".equals(low.get("status")), "auto + a confidence below the floor → still a pending proposal (status=" + low.get("status") + ")");
            data(post("agent.review", Map.of("id", low.get("id"), "action", "reject")));
            Map<String, Object> hi = data(post("rename", Map.of("target", tid, "symbol", "Lm76/aq;", "name", "AutoApplied", "confidence", 0.9, "rationale", "it test"), "agent:qa"));
            check(Boolean.TRUE.equals(hi.get("applied")) && "auto-accepted".equals(hi.get("status")), "auto + confidence above the floor → applied at once (status=" + hi.get("status") + ")");
            Map<String, Object> aqNow = data(post("symbol.resolve", Map.of("target", tid, "symbol", "Lm76/aq;")));
            check("AutoApplied".equals(aqNow.get("name")), "the auto-applied rename is in the engine (name=" + aqNow.get("name") + ")");
            final String hiId = String.valueOf(hi.get("id"));
            check(dataList(post("agent.proposals", Map.of())).stream().noneMatch(o -> hiId.equals(((Map<?, ?>) o).get("id"))), "an auto-applied write leaves nothing pending");
            @SuppressWarnings("unchecked")
            List<Object> histAuto = dataList(post("history.list", Map.of("target", tid)));
            check(histAuto.stream().anyMatch(o -> String.valueOf(((Map<?, ?>) o).get("actor")).contains("auto-applied") && "AutoApplied".equals(((Map<?, ?>) o).get("newName"))),
                    "History records it with the agent as actor (… auto-applied) — undoable like any edit");
            Map<String, Object> clash = post("rename", Map.of("target", tid, "symbol", "Lm76/ar;", "name", "AutoApplied", "confidence", 0.95), "agent:qa");
            check(clash.get("error") != null && String.valueOf(clash.get("error")).contains("refused")
                            && dataList(post("agent.proposals", Map.of())).stream().noneMatch(o -> "Lm76/ar;".equals(((Map<?, ?>) o).get("symbol"))),
                    "auto + a name JEB refuses → the agent gets the reason and nothing is left in the queue: " + clash.get("error"));
            // ---- mission revert: under auto a scripted mission's renames apply at once; agent.revert undoes them, newest first
            data(post("review.configure", Map.of("mode", "auto", "minConfidence", 0)));
            Map<String, Object> mr = data(post("agent.spawn", Map.of("goal", "triage-package m76", "mode", "scripted", "target", tid)));
            String mrId = String.valueOf(mr.get("id")), mrAgent = String.valueOf(mr.get("agentId"));
            for (int i = 0; i < 60; i++) {
                Thread.sleep(1_000);
                boolean fin = false;
                for (Object o : dataList(post("agent.missions", Map.of()))) if (mrId.equals(((Map<?, ?>) o).get("id")) && "done".equals(((Map<?, ?>) o).get("state"))) fin = true;
                if (fin) break;
            }
            int writesBefore = 0;
            for (Object o : dataList(post("agent.missions", Map.of()))) if (mrId.equals(((Map<?, ?>) o).get("id"))) writesBefore = ((Number) ((Map<?, ?>) o).get("writes")).intValue();
            String applied = null;
            for (Object o : dataList(post("agent.activity", Map.of("agent", mrAgent)))) {
                String t = String.valueOf(((Map<?, ?>) o).get("text"));
                int k = t.indexOf("AUTO-APPLIED · propose rename ");
                if (k >= 0) applied = t.substring(k + "AUTO-APPLIED · propose rename ".length(), t.indexOf(" → ", k));
            }
            check(writesBefore >= 1 && applied != null, "auto: the scripted mission's rename applied at once (writes=" + writesBefore + ", symbol=" + applied + ")");
            Map<String, Object> rev = data(post("agent.revert", Map.of("id", mrId)));
            int writesAfter = -1;
            for (Object o : dataList(post("agent.missions", Map.of()))) if (mrId.equals(((Map<?, ?>) o).get("id"))) writesAfter = ((Number) ((Map<?, ?>) o).get("writes")).intValue();
            Map<String, Object> back = applied == null ? Map.of() : data(post("symbol.resolve", Map.of("target", tid, "symbol", applied)));
            check(((Number) rev.get("reverted")).intValue() == writesBefore && writesAfter == 0 && !Boolean.TRUE.equals(back.get("renamed")),
                    "agent.revert undoes the mission's writes (reverted=" + rev.get("reverted") + ", writes now " + writesAfter + ", symbol renamed=" + back.get("renamed") + ")");
            check(post("agent.revert", Map.of("id", mrId), "agent:qa").get("error") != null, "agent.revert is refused to an agent actor");
            // ---- the caller chooses the cut (2026-09-18): symbol.search / symbol.tree take limit, no tool-side cap
            List<Object> allA = dataList(post("symbol.search", Map.of("target", tid, "query", "a")));
            Map<String, Object> pageA = data(post("symbol.search", Map.of("target", tid, "query", "a", "limit", 5)));
            check(allA.size() > 50 && ((List<?>) pageA.get("rows")).size() == 5 && ((Number) pageA.get("total")).intValue() == allA.size()
                            && Boolean.TRUE.equals(pageA.get("truncated")),
                    "symbol.search: every row without a limit (" + allA.size() + ", past the old 50 cap); limit 5 → {rows:5, total:" + pageA.get("total") + ", truncated}");
            Map<String, Object> treeAll = data(post("symbol.tree", Map.of("target", tid, "filter", "Lm76/")));
            Map<String, Object> tree3 = data(post("symbol.tree", Map.of("target", tid, "filter", "Lm76/", "limit", 3)));
            check(((List<?>) treeAll.get("items")).size() > 500 && Boolean.FALSE.equals(treeAll.get("truncated"))
                            && ((List<?>) tree3.get("items")).size() == 3 && Boolean.TRUE.equals(tree3.get("truncated")),
                    "symbol.tree: every class without a limit (" + ((List<?>) treeAll.get("items")).size() + ", past the old 500 cap); limit 3 → 3 rows, truncated");
            // ---- provenance: an AGENT's reads mark agent-assigned names; a human's reads do not
            Map<String, Object> provAgent = data(post("code.decompile", Map.of("target", tid, "symbol", "Lm76/aq;", "scope", "class"), "agent:qa"));
            @SuppressWarnings("unchecked")
            List<Object> provNames = (List<Object>) provAgent.getOrDefault("agentNames", List.of());
            check(provNames.stream().anyMatch(o -> "AutoApplied".equals(((Map<?, ?>) o).get("name")) && "aq".equals(((Map<?, ?>) o).get("was"))
                            && String.valueOf(((Map<?, ?>) o).get("by")).contains("qa") && ((Map<?, ?>) o).get("confidence") != null),
                    "an agent's decompile lists the agent-assigned names it contains (name, was, by, confidence): " + provNames);
            check(data(post("code.decompile", Map.of("target", tid, "symbol", "Lm76/aq;", "scope", "class"))).get("agentNames") == null,
                    "a human's decompile carries no agentNames");
            List<Object> provRows = dataList(post("symbol.search", Map.of("target", tid, "query", "AutoApplied"), "agent:qa"));
            check(provRows.stream().anyMatch(o -> "aq".equals(((Map<?, ?>) o).get("was"))), "row-shaped reads mark an agent-named symbol with was/namedBy: " + provRows);
            // ---- the facade on a real target: reads route to the right op, annotate fans out per item
            Map<String, Object> fsym = data(post("tool.call", Map.of("tool", "symbols", "args", Map.of("target", tid, "query", "aq", "limit", 5))));
            check(fsym.get("rows") instanceof List<?> fr && fr.size() <= 5 && fsym.get("total") != null,
                    "symbols {query, limit} → symbol.search {rows, total, truncated}: " + fsym.keySet());
            // ---- symbols {prefix}: a place in the package tree, SHALLOW (2026-10-03: it used to be a substring search
            //      that expanded every match's methods and fields — 779 KB for one 157-class package)
            Map<String, Object> pfxRaw = post("tool.call", Map.of("tool", "symbols", "args", Map.of("target", tid, "prefix", "m76", "limit", 5000)));
            Map<String, Object> pfxAll = data(pfxRaw);
            List<?> pfxItems = (List<?>) pfxAll.get("items");
            boolean shallow = pfxItems.stream().noneMatch(o -> ((Map<?, ?>) o).containsKey("children"));
            boolean rowsOk = pfxItems.stream().allMatch(o -> { Map<?, ?> r = (Map<?, ?>) o;
                return "package".equals(r.get("kind")) ? r.get("prefix") != null && r.get("count") != null
                        : r.get("addr") != null && String.valueOf(r.get("fqcn")).startsWith("m76.") && r.get("name") != null; });
            int pfxChars = Json.write(pfxRaw).length();
            List<String> pfxAddrs = new java.util.ArrayList<>();
            for (Object o : pfxItems) pfxAddrs.add(String.valueOf(((Map<?, ?>) o).get("addr")));
            List<String> pfxSorted = new java.util.ArrayList<>(pfxAddrs);
            java.util.Collections.sort(pfxSorted);
            check(pfxItems.size() > 1000 && shallow && rowsOk && pfxChars < pfxItems.size() * 200 && Boolean.FALSE.equals(pfxAll.get("truncated"))
                            && pfxAll.get("hint") == null && pfxAll.get("beginnings") == null && pfxAddrs.equals(pfxSorted),
                    "symbols {prefix: m76, limit: 5000} → " + pfxItems.size() + " rows, one per class {name, kind, addr, fqcn}, no children, address order — "
                            + pfxChars + " characters, whole (no hint)");
            Map<String, Object> pfxDal = data(post("tool.call", Map.of("tool", "symbols", "args", Map.of("target", tid, "prefix", "Lm76/", "limit", 5000))));
            Map<String, Object> pfxSl = data(post("tool.call", Map.of("tool", "symbols", "args", Map.of("target", tid, "prefix", "m76/", "limit", 5000))));
            check(((List<?>) pfxDal.get("items")).size() == pfxItems.size() && ((List<?>) pfxSl.get("items")).size() == pfxItems.size(),
                    "the same listing for the dotted, the slashed and the Dalvik spelling of the package");
            // no limit: the default cut, announced — truncated, total, beginnings, hint
            Map<String, Object> pfxDef = data(post("tool.call", Map.of("tool", "symbols", "args", Map.of("target", tid, "prefix", "m76"))));
            Map<?, ?> begin = pfxDef.get("beginnings") instanceof Map<?, ?> bm ? bm : Map.of();
            int beginSum = 0;
            for (Object v : begin.values()) beginSum += ((Number) v).intValue();
            String firstKey0 = null;
            for (Object k : begin.keySet()) if (!String.valueOf(k).isEmpty()) { firstKey0 = String.valueOf(k); break; }
            final String firstKey = firstKey0;
            check(((List<?>) pfxDef.get("items")).size() == 500 && Boolean.TRUE.equals(pfxDef.get("truncated"))
                            && ((Number) pfxDef.get("total")).intValue() == pfxItems.size() && beginSum == pfxItems.size() && firstKey != null
                            && String.valueOf(pfxDef.get("hint")).contains("default cut") && String.valueOf(pfxDef.get("hint")).contains("\"m76." + firstKey + "\""),
                    "symbols {prefix: m76} without a limit → the first 500 of " + pfxDef.get("total") + ", truncated, beginnings over all of them (" + begin.size()
                            + " next characters), and a hint naming a real beginning: " + pfxDef.get("hint"));
            Map<String, Object> pfxKey = data(post("tool.call", Map.of("tool", "symbols", "args", Map.of("target", tid, "prefix", "m76." + firstKey, "limit", 5000))));
            check(((List<?>) pfxKey.get("items")).size() == ((Number) begin.get(firstKey)).intValue() && firstKey.equals(pfxKey.get("startingWith")) && "m76".equals(pfxKey.get("under"))
                            && ((List<?>) pfxKey.get("items")).stream().allMatch(o -> String.valueOf(((Map<?, ?>) o).get("addr")).startsWith("Lm76/" + firstKey)),
                    "a beginning from `beginnings` lists exactly the classes it counted: m76." + firstKey + " → " + ((List<?>) pfxKey.get("items")).size());
            Map<String, Object> pfx5 = data(post("tool.call", Map.of("tool", "symbols", "args", Map.of("target", tid, "prefix", "m76", "limit", 5))));
            check(((List<?>) pfx5.get("items")).size() == 5 && Boolean.TRUE.equals(pfx5.get("truncated")) && ((Number) pfx5.get("total")).intValue() == pfxItems.size()
                            && String.valueOf(pfx5.get("hint")).contains("larger limit") && !String.valueOf(pfx5.get("hint")).contains("default cut")
                            && pfxAddrs.subList(0, 5).equals(((List<?>) pfx5.get("items")).stream().map(o -> String.valueOf(((Map<?, ?>) o).get("addr"))).toList()),
                    "symbols {prefix, limit: 5} → the first 5 in address order, truncated, total " + pfx5.get("total") + ", hint");
            Map<String, Object> pfxPart = data(post("tool.call", Map.of("tool", "symbols", "args", Map.of("target", tid, "prefix", "m76.aa"))));
            List<?> partItems = (List<?>) pfxPart.get("items");
            check(!partItems.isEmpty() && partItems.size() < pfxItems.size() && "aa".equals(pfxPart.get("startingWith")) && "m76".equals(pfxPart.get("under"))
                            && partItems.stream().allMatch(o -> String.valueOf(((Map<?, ?>) o).get("addr")).startsWith("Lm76/aa") && !((Map<?, ?>) o).containsKey("children")),
                    "a last segment that is not a package is the beginning of a name: m76.aa → " + partItems.size() + " rows starting with aa, shallow");
            Map<String, Object> pfxTop = data(post("tool.call", Map.of("tool", "symbols", "args", Map.of("target", tid))));
            Map<String, Object> pfxCom = data(post("tool.call", Map.of("tool", "symbols", "args", Map.of("target", tid, "prefix", "com"))));
            check(((List<?>) pfxTop.get("items")).stream().anyMatch(o -> "package".equals(((Map<?, ?>) o).get("kind")) && "m76".equals(((Map<?, ?>) o).get("name")))
                            && ((List<?>) pfxCom.get("items")).stream().allMatch(o -> !((Map<?, ?>) o).containsKey("children")),
                    "symbols {} → the top-level packages; symbols {prefix: com} → its sub-packages and classes, one level");
            Map<String, Object> pfxBlank = data(post("tool.call", Map.of("tool", "symbols", "args", Map.of("target", tid, "prefix", "com", "query", "", "symbol", ""))));
            check(pfxBlank.get("items") instanceof List<?> bl && bl.size() == ((List<?>) pfxCom.get("items")).size(),
                    "a blank query / symbol beside prefix is an absent one: still the listing");
            // an inner class's fqcn, as the tool's own rows spell it (Outer.Inner), comes back as that class
            String innerAddr = null;
            for (Object o : (List<?>) data(post("symbol.search", Map.of("target", tid, "query", "$", "kind", "class", "limit", 40))).get("rows")) {
                String a = String.valueOf(((Map<?, ?>) o).get("addr"));
                if (a.startsWith("L") && a.endsWith(";") && a.lastIndexOf('$') > a.lastIndexOf('/') && a.indexOf('$') == a.lastIndexOf('$') && a.indexOf('/') > 0
                        && a.substring(1, a.indexOf('/')).indexOf('$') < 0) { innerAddr = a; break; }
            }
            if (innerAddr == null) System.out.println("  (skip inner-class round trip: no single-$ inner class among the first hits)");
            else {
                final String ia = innerAddr;
                String innerFq = ia.substring(1, ia.length() - 1).replace('/', '.').replace('$', '.');
                Map<String, Object> pfxInner = post("tool.call", Map.of("tool", "symbols", "args", Map.of("target", tid, "prefix", innerFq)));
                check(Boolean.TRUE.equals(pfxInner.get("ok")) && ((List<?>) data(pfxInner).get("items")).stream().anyMatch(o -> ia.equals(((Map<?, ?>) o).get("addr"))),
                        "an inner class's dotted fqcn (" + innerFq + ") as the prefix finds " + ia);
            }
            Map<String, Object> pfxMiss = post("tool.call", Map.of("tool", "symbols", "args", Map.of("target", tid, "prefix", "m76x.nothing.here")));
            Map<?, ?> missErr = (Map<?, ?>) pfxMiss.get("error");
            check(missErr != null && "E_NOTFOUND".equals(missErr.get("code")) && missErr.get("candidates") instanceof List<?> mc && !mc.isEmpty()
                            && ((Map<?, ?>) mc.get(0)).get("prefix") != null && String.valueOf(missErr.get("detail")).contains("top level")
                            && String.valueOf(missErr.get("hint")).contains("symbols {prefix"),
                    "a prefix with nothing under it → E_NOTFOUND with the nearest existing level's packages as candidates and a hint: " + (missErr == null ? null : missErr.get("detail")));
            check("E_SYNTAX".equals(((Map<?, ?>) post("tool.call", Map.of("tool", "symbols", "args", Map.of("target", tid, "prefix", "a.".repeat(400)))).get("error")).get("code")),
                    "an absurd prefix (400 segments) is refused, not walked");
            Map<String, Object> pfxCls = data(post("tool.call", Map.of("tool", "symbols", "args", Map.of("target", tid, "prefix", "Lm76/at;"))));
            check(((List<?>) pfxCls.get("items")).size() == 1 && ((Map<?, ?>) ((List<?>) pfxCls.get("items")).get(0)).containsKey("children"),
                    "a class address as the prefix → that one class with its members (bounded)");
            // symbol.tree {filter} is untouched: a Dalvik filter lists (the tree pane), a name searches with members (the scripted mission)
            Map<String, Object> uiName = data(post("symbol.tree", Map.of("target", tid, "filter", "m76.at", "limit", 3)));
            check(((List<?>) uiName.get("items")).stream().allMatch(o -> ((Map<?, ?>) o).containsKey("children")),
                    "symbol.tree {filter: <name>} still answers the name search: classes with members");
            Map<String, Object> fres = data(post("tool.call", Map.of("tool", "symbols", "args", Map.of("target", tid, "symbol", "Lm76/at;"))));
            check("Lm76/at;".equals(fres.get("addr")) || String.valueOf(fres.get("fqcn")).endsWith("at"), "symbols {symbol} → symbol.resolve: " + fres);
            Map<String, Object> fout = data(post("tool.call", Map.of("tool", "code", "args", Map.of("target", tid, "symbol", "Lm76/at;", "view", "outline"))));
            check(fout.get("methods") instanceof List<?>, "code {view outline} → code.outline (symbol → class): " + fout.keySet());
            Map<String, Object> fcode = data(post("tool.call", Map.of("tool", "code", "args", Map.of("target", tid, "symbol", "Lm76/at;"))));
            check(fcode.get("text") != null, "code {symbol} → code.decompile: " + fcode.keySet());
            String fmeth = null;
            for (Object mo : (List<?>) fout.get("methods")) {   // a real method, not <init>/<clinit>
                String an = String.valueOf(((Map<?, ?>) mo).get("name"));
                if (!an.startsWith("<")) { fmeth = String.valueOf(((Map<?, ?>) mo).get("addr")); break; }
            }
            Map<String, Object> fx = data(post("tool.call", Map.of("tool", "xrefs", "args", Map.of("target", tid, "symbol", "Lm76/at;", "limit", 3))));
            check(fx.get("rows") instanceof List<?> && fx.get("total") != null, "xrefs {symbol, limit} → xref.to {rows, total}: " + fx.keySet());
            Map<String, Object> fan = data(post("tool.call", Map.of("tool", "annotate", "args", Map.of("target", tid, "items", List.of(
                    Map.of("symbol", fmeth, "name", "describeFacade", "comment", "facade probe. second sentence", "tags", List.of("qa:facade", Map.of("name", "qa:noted", "note", "the note rides the tag")), "confidence", 0.9),
                    Map.of("symbol", "Lm76/at;", "comment", "class-level facade comment"),
                    Map.of("symbol", "Lm76/at;", "name", "9bad")))), "agent:qa"));
            List<?> fanRows = (List<?>) fan.get("results");
            Map<?, ?> fan0 = (Map<?, ?>) fanRows.get(0);
            check(fanRows.size() == 3
                            && "applied".equals(((Map<?, ?>) fan0.get("rename")).get("status"))
                            && "applied".equals(((Map<?, ?>) fan0.get("comment")).get("status"))
                            && fan0.get("tags") instanceof List<?> ft && "applied".equals(((Map<?, ?>) ft.get(0)).get("status"))
                            && "applied".equals(((Map<?, ?>) ((Map<?, ?>) fanRows.get(1)).get("comment")).get("status"))
                            && "failed".equals(((Map<?, ?>) ((Map<?, ?>) fanRows.get(2)).get("rename")).get("status"))
                            && ((Number) fan.get("applied")).intValue() == 5 && ((Number) fan.get("failed")).intValue() == 1,
                    "annotate: rename+comment+2 tags on one item, comment on a second, a bad name on a third → per-item outcomes: " + fan);
            List<?> tagRows = (List<?>) data(post("tag.list", Map.of("target", tid))).get("tags");
            String qaFacadeNote = null, qaNotedNote = null;
            for (Object o : tagRows) { Map<?, ?> tr = (Map<?, ?>) o; if ("facade".equals(tr.get("name"))) qaFacadeNote = String.valueOf(tr.get("note")); if ("noted".equals(tr.get("name"))) qaNotedNote = String.valueOf(tr.get("note")); }
            check("the note rides the tag".equals(qaNotedNote) && "facade probe.".equals(qaFacadeNote),
                    "annotate tags: {name, note} keeps its note; a bare tag takes the comment's first sentence — got " + qaNotedNote + " / " + qaFacadeNote);
            check("describeFacade".equals(data(post("symbol.resolve", Map.of("target", tid, "symbol", fmeth.replace("->", "->").replaceAll("->\\w+\\(", "->describeFacade(")))).get("name"))
                            || String.valueOf(data(post("code.outline", Map.of("target", tid, "class", "Lm76/at;"))).get("methods")).contains("describeFacade"),
                    "annotate's rename applied (the auto policy) — visible to symbol.resolve at once");
            Map<String, Object> fanEmpty = data(post("tool.call", Map.of("tool", "annotate", "args", Map.of("target", tid, "items", List.of(Map.of("symbol", "Lm76/at;")))), "agent:qa"));
            check(((Number) fanEmpty.get("failed")).intValue() == 1 && String.valueOf(fanEmpty.get("results")).contains("nothing to do"),
                    "annotate: an item with neither name, comment nor tags fails with a reason");
            // a not-found symbol answers with the nearest names (m5: three guessed class names, then a search)
            Map<String, Object> nf = post("tool.call", Map.of("tool", "code", "args", Map.of("target", tid, "symbol", "Lav;")));
            Map<?, ?> nfErr = nf.get("error") instanceof Map<?, ?> ne ? ne : Map.of();
            check("E_NOTFOUND".equals(nfErr.get("code")) && nfErr.get("candidates") instanceof List<?> cl && !cl.isEmpty()
                            && "Lm76/av;".equals(((Map<?, ?>) cl.get(0)).get("addr")),
                    "code {symbol Lav;} → E_NOTFOUND with candidates, the exact simple-name match first: " + nfErr.get("candidates"));
            // ---- the EDITS pane's source: agent.proposals {state: all} lists applied rows with their history id,
            //      and a reverted one says so (2026-09-19: the pane was empty under APPLY DIRECTLY)
            List<Object> allRows = dataList(post("agent.proposals", Map.of("state", "all", "target", tid)));
            Map<?, ?> appliedRow = null;
            for (Object o : allRows) { Map<?, ?> pr = (Map<?, ?>) o; if ("accepted".equals(pr.get("state")) && Boolean.TRUE.equals(pr.get("auto")) && pr.get("historyId") != null && "rename".equals(pr.get("kind"))) { appliedRow = pr; break; } }
            check(allRows.size() > dataList(post("agent.proposals", Map.of("target", tid))).size() && appliedRow != null && appliedRow.get("newAddr") != null
                            && Boolean.FALSE.equals(appliedRow.get("reverted")),
                    "agent.proposals {state: all}: applied rows come with historyId + newAddr, reverted=false — " + (appliedRow == null ? "none" : appliedRow.get("id")));
            if (appliedRow != null) {
                data(post("history.undo", Map.of("target", tid, "id", appliedRow.get("historyId"))));
                Map<?, ?> again = null;
                for (Object o : dataList(post("agent.proposals", Map.of("state", "all", "target", tid)))) { Map<?, ?> pr = (Map<?, ?>) o; if (appliedRow.get("id").equals(pr.get("id"))) { again = pr; break; } }
                check(again != null && Boolean.TRUE.equals(again.get("reverted")), "after history.undo of its entry the row says reverted=true");
                data(post("history.redo", Map.of("target", tid, "id", appliedRow.get("historyId"))));
            }
            // ---- locals: rename {symbol: method, local, name} renames a local variable through JEB's Java unit; undo restores it
            Map<String, Object> lcDec = data(post("code.decompile", Map.of("target", tid, "symbol", "Lm76/at;", "scope", "class")));
            String lcMethod = null, lcName = null;
            // Native inventories identify body locals without assuming the renderer
            // starts at v0: JEB 5.46 renders this fixture's integer simply as v.
            for (Object mo : (List<?>) lcDec.get("methods")) {
                String method = String.valueOf(((Map<?, ?>) mo).get("addr"));
                for (Object value : dataList(post("locals.list", Map.of("target", tid, "symbol", method)))) {
                    Map<?, ?> local = (Map<?, ?>) value;
                    if (Boolean.TRUE.equals(local.get("renameableLocal")) && !local.containsKey("parameterIndex")) {
                        lcMethod = method; lcName = String.valueOf(local.get("name")); break;
                    }
                }
                if (lcMethod != null) break;
            }
            if (lcMethod != null) {
                Map<String, Object> lr = post("rename", Map.of("target", tid, "symbol", lcMethod, "local", lcName, "name", "probeLocal"));
                String lcAfter = String.valueOf(data(post("code.decompile", Map.of("target", tid, "symbol", "Lm76/at;", "scope", "class"))).get("text"));
                check(Boolean.TRUE.equals(lr.get("ok")) && "local".equals(data(lr).get("kind")) && lcAfter.contains("probeLocal") && data(lr).get("historyId") != null,
                        "rename {local}: " + lcName + " → probeLocal in " + lcMethod + " shows in the decompiled text, recorded in History");
                data(post("history.undo", Map.of("target", tid, "id", data(lr).get("historyId"))));
                String lcUndone = String.valueOf(data(post("code.decompile", Map.of("target", tid, "symbol", "Lm76/at;", "scope", "class"))).get("text"));
                check(!lcUndone.contains("probeLocal"), "history.undo of a local rename restores the old name");
                Map<String, Object> viaAnnotate = data(post("tool.call", Map.of("tool", "annotate", "args", Map.of("target", tid, "items", List.of(Map.of("symbol", lcMethod, "local", lcName, "name", "agentLocal")))), "agent:qa"));
                check(((Number) viaAnnotate.get("applied")).intValue() == 1
                                && String.valueOf(data(post("code.decompile", Map.of("target", tid, "symbol", "Lm76/at;", "scope", "class"))).get("text")).contains("agentLocal"),
                        "annotate {symbol: method, local, name} renames the local for an agent too: " + viaAnnotate.get("results"));
            } else check(false, "no local variable found in Lm76/at; to probe the local rename");
            // ---- locals.auto: generated locals named after their types, History per local, and the binding follows a class rename
            {
                String laMethod = null;   // aaf's 4-arg constructor (String, UUID, String, abf): class-typed parameters
                for (Object mo : (List<?>) data(post("code.outline", Map.of("target", tid, "class", "Lm76/aaf;"))).get("methods")) {
                    String a = String.valueOf(((Map<?, ?>) mo).get("addr"));
                    if (a.contains("<init>") && a.contains("Lm76/")) { laMethod = a; break; }
                }
                check(laMethod != null, "the fixture's Lm76/aaf; has a constructor with a class-typed parameter: " + laMethod);
                // Set up the generic name this behavior tests. The vendor now
                // supplies type-based names (e.g. abf0) instead of old argN names.
                Map<?, ?> genericCandidate = dataList(post("locals.list", Map.of("target", tid, "symbol", laMethod))).stream()
                        .map(o -> (Map<?, ?>) o).filter(local -> Boolean.TRUE.equals(local.get("renameableLocal"))
                                && String.valueOf(local.get("type")).startsWith("Lm76/")).findFirst().orElseThrow();
                data(post("rename", Map.of("target", tid, "symbol", laMethod, "local", genericCandidate.get("name"), "name", "v9001")));
                Map<String, Object> la = data(post("locals.auto", Map.of("target", tid, "symbol", laMethod)));
                List<?> lren = (List<?>) la.get("renames");
                check(lren != null && !lren.isEmpty() && la.get("historyIds") instanceof List<?> hl && hl.size() == lren.size(),
                        "locals.auto: " + (lren == null ? 0 : lren.size()) + " locals named after their types, one History entry each — " + lren);
                Map<?, ?> bound = null;
                for (Object o : lren) { Map<?, ?> rn = (Map<?, ?>) o; if (String.valueOf(rn.get("type")).startsWith("Lm76/")) { bound = rn; break; } }
                if (bound != null) {
                    String typeAddr = String.valueOf(bound.get("type"));
                    Map<String, Object> cr = data(post("rename", Map.of("target", tid, "symbol", typeAddr, "name", "FollowedType")));
                    String txt = String.valueOf(data(post("code.decompile", Map.of("target", tid, "symbol", "Lm76/aaf;", "scope", "class"))).get("text"));
                    check(Boolean.TRUE.equals(cr.get("applied")) && txt.contains("followedType") && !txt.contains(String.valueOf(bound.get("name")) + " "),
                            "a class rename renames the local bound to it: " + bound.get("name") + " → followedType (History says 'follows class rename')");
                    List<Object> fhist = dataList(post("history.list", Map.of("target", tid, "limit", 5)));
                    check(fhist.stream().anyMatch(o -> String.valueOf(((Map<?, ?>) o).get("actor")).contains("follows class rename")), "History records the followed rename with its reason");
                    String classHid = null;
                    for (Object o : fhist) { Map<?, ?> h = (Map<?, ?>) o; if ("class".equals(h.get("kind")) && "FollowedType".equals(h.get("newName"))) classHid = String.valueOf(h.get("id")); }
                    data(post("history.undo", Map.of("target", tid, "id", classHid)));
                    String txt2 = String.valueOf(data(post("code.decompile", Map.of("target", tid, "symbol", "Lm76/aaf;", "scope", "class"))).get("text"));
                    check(!txt2.contains("followedType") && txt2.contains(String.valueOf(bound.get("name"))), "undoing the class rename renames the local back: " + bound.get("name"));
                } else check(true, "locals.auto: no class-typed local in the probe method to follow (skipped the follow check)");
            }
            // ---- an EXTERNAL class (referenced, not defined): resolves, has xrefs by its type, an outline of the members the app uses, no code
            Map<String, Object> ext = data(post("symbol.resolve", Map.of("target", tid, "symbol", "Ljava/util/UUID;")));
            Map<String, Object> extX = data(post("xref.to", Map.of("target", tid, "symbol", "Ljava/util/UUID;", "limit", 5)));
            Map<String, Object> extO = data(post("code.outline", Map.of("target", tid, "class", "Ljava/util/UUID;")));
            Map<String, Object> extD = post("code.decompile", Map.of("target", tid, "symbol", "Ljava/util/UUID;"));
            check(Boolean.TRUE.equals(ext.get("external")) && "class".equals(ext.get("kind")) && ((Number) extX.get("total")).intValue() > 0
                            && Boolean.TRUE.equals(extO.get("external")) && !((List<?>) extO.get("methods")).isEmpty()
                            && extD.get("error") != null && String.valueOf(extD.get("error")).contains("external class"),
                    "external class java.util.UUID: resolves (external:true), " + extX.get("total") + " type xrefs, outline of " + ((List<?>) extO.get("methods")).size() + " used members, decompile says external");
            // ---- a field named without its descriptor (`this.name` as an agent reads it) resolves to the field (m6 lost turns on it)
            Map<String, Object> fdOut = data(post("code.outline", Map.of("target", tid, "class", "Lm76/aaf;")));
            List<?> fdFields = (List<?>) fdOut.get("fields");
            if (!fdFields.isEmpty()) {
                String fAddr = String.valueOf(((Map<?, ?>) fdFields.get(0)).get("addr"));
                String bareField = fAddr.substring(0, fAddr.indexOf(":"));                  // Lm76/aaf;->name
                Map<String, Object> fr = data(post("symbol.resolve", Map.of("target", tid, "symbol", bareField)));
                check("field".equals(fr.get("kind")) && fAddr.equals(fr.get("addr")), "symbol.resolve of a bare field ref " + bareField + " → the field " + fAddr);
            }
            // ---- callers carry the call-site address; a call-site comment lands on the statement; annotate comments before it renames (m10, 2026-09-20)
            List<Object> clRows = dataList(post("callers", Map.of("target", tid, "symbol", "Lm76/aaf;->c()Ljava/lang/String;")));
            Map<?, ?> clRow = clRows.isEmpty() ? null : (Map<?, ?>) clRows.get(0);
            check(clRow != null && String.valueOf(clRow.get("from")).matches(".*->.*\\+[0-9A-Fa-f]+h?") && clRow.get("sites") instanceof List<?>,
                    "callers rows carry the call site (`from` … +NNh) — " + (clRow == null ? "no callers" : clRow.get("from")));
            if (clRow != null) {
                String site = String.valueOf(clRow.get("from"));
                Map<String, Object> sc = data(post("comment", Map.of("target", tid, "symbol", site, "text", "IT call-site comment")));
                String callerCls = site.substring(0, site.indexOf("->"));
                String ctext = String.valueOf(data(post("code.decompile", Map.of("target", tid, "symbol", callerCls, "scope", "class"))).get("text"));
                check(Boolean.TRUE.equals(sc.get("applied")) && Boolean.TRUE.equals(sc.get("site")) && ctext.contains("IT call-site comment"),
                        "a comment on a call-site address is printed on that statement in the decompiled caller");
                data(post("history.undo", Map.of("target", tid, "id", sc.get("historyId"))));
            }
            // ---- symbols {query, kind}: ranked (exact first) and filtered by kind; Outer$Inner resolves through the dex's
            //      structural records with the outer's inner classes as candidates on a miss (agent 2026-09-22)
            {
                Map<String, Object> mo = data(post("symbol.search", Map.of("target", tid, "query", "a", "kind", "method", "limit", 5)));
                List<?> mrows = (List<?>) mo.get("rows");
                check(mrows.size() == 5 && mrows.stream().allMatch(r -> "method".equals(((Map<?, ?>) r).get("kind"))),
                        "symbol.search {kind: method}: methods only — " + mrows.size() + " rows, total " + mo.get("total"));
                Map<String, Object> ex = data(post("symbol.search", Map.of("target", tid, "query", "AAF", "kind", "class", "limit", 3)));
                List<?> exRows = (List<?>) ex.get("rows");
                Map<?, ?> first = exRows.isEmpty() ? Map.of() : (Map<?, ?>) exRows.get(0);
                Map<String, Object> sub = data(post("symbol.search", Map.of("target", tid, "query", "A", "kind", "class", "limit", 3)));   // 'a' exists as a class name: exact (rank 1) before the substring crowd
                Map<?, ?> subFirst = (Map<?, ?>) ((List<?>) sub.get("rows")).get(0);
                check("aaf".equals(first.get("name")) && "a".equals(subFirst.get("name")) && ((Number) sub.get("total")).intValue() > 100,
                        "symbol.search ranks the exact name (any case) first, substrings after: " + first + " / " + subFirst + " of " + sub.get("total"));
                Map<String, Object> fo = data(post("symbol.search", Map.of("target", tid, "query", "a", "kind", "field", "limit", 3)));
                check(!((List<?>) fo.get("rows")).isEmpty() && "field".equals(((Map<?, ?>) ((List<?>) fo.get("rows")).get(0)).get("kind")),
                        "symbol.search {kind: field} finds fields: " + fo.get("rows"));
                Map<String, Object> badKind = post("symbol.search", Map.of("target", tid, "query", "a", "kind", "bogus"));
                check(!Boolean.TRUE.equals(badKind.get("ok")) && String.valueOf(badKind.get("error")).contains("E_SYNTAX"), "symbol.search {kind: bogus} → E_SYNTAX");
                Map<String, Object> inner = post("tool.call", Map.of("tool", "symbols", "args", Map.of("target", tid, "symbol", "Lj$/util/Collection$Nope;")));
                Map<?, ?> ierr = (Map<?, ?>) inner.get("error");
                List<?> cands = ierr == null ? List.of() : (List<?>) ierr.get("candidates");
                check(ierr != null && "E_NOTFOUND".equals(ierr.get("code")) && cands != null
                                && cands.stream().anyMatch(c -> "Lj$/util/Collection$-EL;".equals(((Map<?, ?>) c).get("addr")) && "-EL".equals(((Map<?, ?>) c).get("innerName"))),
                        "symbols {symbol: LOuter$Nope;} → E_NOTFOUND with the outer's inner classes as candidates (innerName): " + cands);
                Map<String, Object> viaInner = data(post("symbol.resolve", Map.of("target", tid, "symbol", "Lj$/util/Collection$-EL;")));
                check("Lj$/util/Collection$-EL;".equals(viaInner.get("addr")), "symbol.resolve LOuter$Inner; resolves: " + viaInner.get("addr"));
            }
            // ---- a call whose result is cast and assigned: JEB keys the statement's comment at its LAST instruction (the
            //      check-cast), so the invoke's own address renders nothing and a walk back lands on the previous statement
            //      (WhatsApp 2026-09-20: the comment sat three lines up). GoogleApiActivity.a(): +40h is `v1.get("error_code")`.
            {
                String castSite = "Lcom/google/android/gms/common/api/GoogleApiActivity;->a()V+40h";
                Map<String, Object> cc = data(post("comment", Map.of("target", tid, "symbol", castSite, "text", "IT cast-site comment")));
                String ctext = String.valueOf(data(post("code.decompile", Map.of("target", tid, "symbol", "Lcom/google/android/gms/common/api/GoogleApiActivity;->a()V", "scope", "method"))).get("text"));
                String cline = java.util.Arrays.stream(ctext.split("\n")).filter(l -> l.contains("IT cast-site comment")).findFirst().orElse("");
                check(Boolean.TRUE.equals(cc.get("applied")) && cline.contains("error_code") && cline.contains("get("),
                        "a comment on a cast-assigned call site renders on THAT statement (placed at " + cc.get("addr") + "): " + cline.trim());
                data(post("history.undo", Map.of("target", tid, "id", cc.get("historyId"))));
            }
            Map<String, Object> both = data(post("tool.call", Map.of("tool", "annotate", "args", Map.of("target", tid, "items", List.of(
                    Map.of("symbol", "Lm76/aaf;->c()Ljava/lang/String;", "name", "spanNameProbe", "comment", "renamed and commented in one item")))), "agent:qa"));
            Map<?, ?> bothRow = (Map<?, ?>) ((List<?>) both.get("results")).get(0);
            check("applied".equals(((Map<?, ?>) bothRow.get("rename")).get("status")) && "applied".equals(((Map<?, ?>) bothRow.get("comment")).get("status")),
                    "annotate: rename + comment on one item both apply (the comment goes first, the rename moves it along): " + bothRow);
            // ---- batch writes: one call, per-item outcomes; the write-scope is checked PER ITEM
            Map<String, Object> batch = data(post("rename", Map.of("target", tid, "items", List.of(
                    Map.of("symbol", "Lm76/at;", "name", "BatchOne", "confidence", 0.9),
                    Map.of("symbol", "Lm76/au;", "name", "BatchOne", "confidence", 0.9),          // collides with the first
                    Map.of("symbol", "Lm76/av;", "name", "9bad", "confidence", 0.9))), "agent:qa"));
            check(((Number) batch.get("applied")).intValue() == 1 && ((Number) batch.get("failed")).intValue() == 2
                            && "BatchOne".equals(data(post("symbol.resolve", Map.of("target", tid, "symbol", "Lm76/at;"))).get("name")),
                    "rename {items}: 1 applied, 2 failed with reasons — " + batch.get("results"));
            Map<String, Object> scoped = post("rename", Map.of("target", "t1", "items", List.of(
                    Map.of("symbol", "onMessage", "name", "scopeProbeBatch"))), "llm:" + agentId);
            check(Boolean.TRUE.equals(scoped.get("ok")) && ((Number) data(scoped).get("failed")).intValue() == 1
                            && String.valueOf(data(scoped).get("results")).contains("scope"),
                    "a pkg-scoped mission's batch item outside its scope fails with the scope reason: " + scoped.get("data"));
            // ---- a name a HUMAN assigned is not an agent's to change; its own it may refine
            data(post("rename", Map.of("target", tid, "symbol", "Lm76/as;", "name", "HumanNamed")));
            Map<String, Object> overHuman = post("rename", Map.of("target", tid, "symbol", "Lm76/as;", "name", "AgentOverwrite", "confidence", 0.99), "agent:qa");
            check(overHuman.get("error") != null && String.valueOf(overHuman.get("error")).contains("named by a human")
                            && "HumanNamed".equals(data(post("symbol.resolve", Map.of("target", tid, "symbol", "Lm76/as;"))).get("name")),
                    "an agent cannot rename a symbol a human named: " + overHuman.get("error"));
            Map<String, Object> refine = data(post("rename", Map.of("target", tid, "symbol", "Lm76/aq;", "name", "AutoRefined", "confidence", 0.95), "agent:qa"));
            check(Boolean.TRUE.equals(refine.get("applied")) && "AutoRefined".equals(data(post("symbol.resolve", Map.of("target", tid, "symbol", "Lm76/aq;"))).get("name")),
                    "an agent may refine a name an agent assigned (AutoApplied → AutoRefined)");
            data(post("review.configure", Map.of("mode", "manual", "minConfidence", 0)));
            data(post("target.close", Map.of("target", tid, "save", false)));           // DISCARD (rename + comment)
            String tid2 = reopenAndWait(apkPath);
            Map<String, Object> ch2 = data(post("target.changes", Map.of("target", tid2)));
            check(((Number) ch2.get("symbols")).intValue() == 0,
                    "discard: after close{save:false}+reopen, target.changes.symbols==0 — the edit does NOT replay");
            check(String.valueOf(ch2.get("lastSaved")).equals(savedBefore),
                    "discard: the jdb2 was NOT written (lastSaved unchanged)");
            boolean anyDiscarded = false;
            for (Object o : dataList(post("history.list", Map.of("target", tid2)))) {
                Map<?, ?> e = (Map<?, ?>) o;
                if (Boolean.TRUE.equals(e.get("undone")) && Boolean.TRUE.equals(e.get("discarded"))) { anyDiscarded = true; break; }
            }
            check(anyDiscarded, "discard: the unsaved history entry is marked undone+discarded");
            // ---- SAVE must persist: a fresh rename (by addr), close{save:true}, reopen
            data(post("rename", Map.of("target", tid2, "symbol", sym, "name", "SavedByCloseQa")));
            check(((Number) data(post("target.changes", Map.of("target", tid2))).get("renamed")).intValue() == 1,
                    "target.changes: the fresh rename is 1 unsaved symbol");
            data(post("target.close", Map.of("target", tid2, "save", true)));            // SAVE & CLOSE
            String tid3 = reopenAndWait(apkPath);
            Map<?, ?> vg3 = (Map<?, ?>) data(post("target.view", Map.of("target", tid3))).get("view");
            check(vg3 != null && "m76.an".equals(vg3.get("class")), "target.view: the position survives close + reopen (new id " + tid3 + ")");
            Map<String, Object> ch4 = data(post("target.changes", Map.of("target", tid3)));
            check(((Number) ch4.get("symbols")).intValue() == 0 && !String.valueOf(ch4.get("lastSaved")).equals(savedBefore),
                    "save: after close{save:true}+reopen, nothing unsaved and the jdb2 was written (lastSaved advanced)");
        // ---- the facade's target save answers at once and the outcome is polled (an MCP tool call times out before a 2 GB jdb2 is written, 2026-09-21)
        {
            data(post("comment", Map.of("target", tid3, "symbol", "Lm76/aaf;", "text", "IT async-save probe")));   // dirty, so the save writes
            Map<String, Object> sv = data(post("tool.call", Map.of("tool", "target", "args", Map.of("action", "save", "target", tid3))));
            check(Boolean.TRUE.equals(sv.get("started")) && sv.get("startedAt") instanceof Number, "tool.call target save → {started, startedAt} at once: " + sv);
            long startedAt = ((Number) sv.get("startedAt")).longValue();
            Map<String, Object> st = null;
            for (int i = 0; i < 300; i++) {
                st = data(post("target.saveStatus", Map.of("target", tid3)));
                Map<?, ?> last = (Map<?, ?>) st.get("last");
                if (!Boolean.TRUE.equals(st.get("saving")) && last != null && ((Number) last.get("at")).longValue() >= startedAt) break;
                Thread.sleep(200);
            }
            Map<?, ?> last = st == null ? null : (Map<?, ?>) st.get("last");
            check(last != null && Boolean.TRUE.equals(last.get("ok")) && ((Number) last.get("bytes")).longValue() > 0
                            && ((Number) last.get("at")).longValue() >= startedAt,
                    "target.saveStatus after the async save: last {ok, at ≥ startedAt, bytes}: " + last);
        }
            // cleanup: restore the original name, written to the jdb2
            data(post("rename", Map.of("target", tid3, "symbol", sym, "name", oldName)));
            data(post("target.close", Map.of("target", tid3, "save", true)));
            tid = tid3;   // the finally's close is now a harmless no-op on a closed id
        } finally {
            try { post("target.close", Map.of("target", tid)); } catch (Exception ignored) {}
        }

        // ---- MCP server selftest (against this running backend)
        ProcessBuilder mcpPb = new ProcessBuilder("python3", "mcp/workbench_mcp.py", "--selftest", "--write");
        /* the script reads WORKBENCH_URL (default 7800) — WITHOUT this it tests the
           LIVE interactive server, and is green only while that server has the mock
           scene (latent leak exposed by the empty boot, 2026-09-08) */
        mcpPb.environment().put("WORKBENCH_URL", BASE);
        Process mcp = mcpPb.redirectErrorStream(true).start();
        String out = new String(mcp.getInputStream().readAllBytes());
        int rc = mcp.waitFor();
        System.out.println(out.strip().replaceAll("(?m)^", "  "));
        check(rc == 0 && out.contains("SELFTEST PASSED"), "MCP selftest passed over stdio");

        System.out.println();
        if (failures > 0) {
            System.out.println("M7 IT FAILED: " + failures + "/" + checks + " checks failed");
            System.exit(1);
        }
        System.out.println("M7 IT PASSED: " + checks + " checks green");
        System.exit(0);
    }

    /** target.open on the store's artifact (loads the saved jdb2) and wait for ready → the new id. */
    /** The last n history entries' op · addr · newAddr — what target.changes counts from. */
    private static String describeHistoryTail(String tid, int n) {
        try {
            List<Object> h = dataList(post("history.list", Map.of("target", tid)));
            StringBuilder sb = new StringBuilder();
            for (int i = Math.max(0, h.size() - n); i < h.size(); i++) {
                Map<?, ?> e = (Map<?, ?>) h.get(i);
                sb.append('[').append(e.get("op")).append(" addr=").append(e.get("addr")).append(" newAddr=").append(e.get("newAddr")).append("] ");
            }
            return sb.toString();
        } catch (Exception e) { return "(history.list failed: " + e.getMessage() + ")"; }
    }

    private static String reopenAndWait(String path) throws Exception {
        String tid = String.valueOf(data(post("target.open", Map.of("path", path))).get("id"));
        awaitReady(tid);
        return tid;
    }

    private static void awaitReady(String tid) throws Exception {
        long deadline = System.currentTimeMillis() + 600_000;
        while (System.currentTimeMillis() < deadline) {
            Map<String, Object> info = data(post("target.info", Map.of("target", tid)));
            String st = String.valueOf(info.get("state"));
            if ("ready".equals(st)) return;
            if ("error".equals(st)) throw new IllegalStateException("analysis failed: " + info.get("error"));
            Thread.sleep(2_000);
        }
        throw new IllegalStateException("analysis timeout for " + tid);
    }

    private static void check(boolean ok, String label) {
        checks++;
        System.out.println((ok ? "PASS " : "FAIL ") + label);
        if (!ok) failures++;
    }

    private static Map<String, Object> post(String op, Map<String, Object> params) throws Exception {
        return post(op, params, "human");
    }

    private static Map<String, Object> post(String op, Map<String, Object> params, String actor)
            throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("params", params);
        body.put("actor", actor);
        HttpRequest req = HttpRequest.newBuilder(URI.create(BASE + "/api/" + op))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(Json.write(body)))
                .build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        return Json.parseObject(resp.body());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> data(Map<String, Object> resp) {
        if (!Boolean.TRUE.equals(resp.get("ok")))
            throw new IllegalStateException("op failed: " + resp.get("error"));
        return (Map<String, Object>) resp.get("data");
    }

    @SuppressWarnings("unchecked")
    private static List<Object> dataList(Map<String, Object> resp) {
        if (!Boolean.TRUE.equals(resp.get("ok")))
            throw new IllegalStateException("op failed: " + resp.get("error"));
        return (List<Object>) resp.get("data");
    }
}
