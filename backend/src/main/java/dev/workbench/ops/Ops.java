package dev.workbench.ops;

import dev.workbench.engine.Engine;
import dev.workbench.registry.Op;
import dev.workbench.registry.Op.Cost;
import dev.workbench.registry.OpException;
import dev.workbench.registry.Registry;
import dev.workbench.server.Doctor;
import dev.workbench.server.EventBus;
import dev.workbench.server.Workspace;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Registers the full op surface (MCP_SPEC §4) into the one registry.
 * M1: caps/workspace/project/target/symbol/code/xref/strings/search/rename/comment ops
 * route to the ACTIVE TARGET's engine — MockEngine for the 4 fixture targets, JebEngine
 * (JEB 5.42) for real artifacts opened via target.open. map/agent/runs/device/tag/
 * bookmark/history/flow ops remain registered stubs returning E_UNSUPPORTED (parity test).
 */
public final class Ops {

    private Ops() {}

    public static Registry registerAll(Workspace ws) {
        Registry r = new Registry();
        r.register(new Op("research.status", "", false, Cost.cheap, req -> ws.localCaptureStatus()));

        // ---- §2 capability discovery
        r.register(new Op("caps", "", false, Cost.cheap, req -> {
            if (req.str("target") != null) return engine(ws, req).caps();
            Map<String, Object> m = new LinkedHashMap<>();
            for (Object o : ws.targetList()) {
                @SuppressWarnings("unchecked")
                Map<String, Object> t = (Map<String, Object>) o;
                m.put((String) t.get("id"), t.get("caps"));
            }
            return m;
        }));
        // meta: registry dump for MCP tool generation (mcp/workbench_mcp.py)
        r.register(new Op("ops.list", "", false, Cost.cheap, req -> {
            List<Object> out = new ArrayList<>();
            for (Op o : r.all()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("name", o.name());
                row.put("mutating", o.mutating());
                row.put("cost", o.cost().name());
                if (UI_ONLY.contains(o.name())) row.put("mcp", false);   // the browser's plumbing: never an MCP tool, in any catalogue
                if (!o.capability().isEmpty()) row.put("capability", o.capability());
                out.add(row);
            }
            return out;
        }));
        // meta: the tool facade (dev.workbench.llm.ToolFacade) — the 24 tools an LLM sees,
        // each with a strict schema, the ops it routes to and whether the mission loop may call it
        r.register(new Op("ops.tools", "", false, Cost.cheap,
                req -> dev.workbench.llm.AgentLoop.curatedTools()));
        /* meta: run one facade tool as the caller — the MCP bridge's tools/call. Registered as
           NON-mutating on purpose: the scope check runs inside, per routed op (annotate's three
           batches each check per item), so the outer check would only see {tool, args} and refuse
           every scoped mission; the routed ops carry their own mutating flags, actor rules and
           review policy. */
        r.register(new Op("tool.call", "", false, Cost.cheap, req -> {
            String tool = req.require("tool");
            Object a = req.params().get("args");
            Map<String, Object> args = new LinkedHashMap<>();
            if (a instanceof Map<?, ?> am) for (Map.Entry<?, ?> e : am.entrySet()) args.put(String.valueOf(e.getKey()), e.getValue());
            dev.workbench.registry.Dispatcher d = ws.agents().dispatcher();
            if (d == null) throw OpException.busy("the dispatcher is not up yet", "tool.call");
            Map<String, Object> res;
            String client = req.str("client");              // the bridge names the client it serves (the audit trail)
            if (client != null && client.length() > 80) client = client.substring(0, 80);
            /* a course room: the call runs on the COURSE's target (the one open target the manifest names —
               pinned before dispatch, so the provenance names the target the call ran on, not the page's
               active one read afterwards), an external assistant's tool traffic is captured (the call, not
               its private model transcript — that is the gateway's, where it goes through the gateway at
               all), and the answer carries the evidence's provenance when the bridge asks (student MCP addendum) */
            dev.workbench.course.CourseRuntime crt = dev.workbench.course.CourseRuntime.current();
            dev.workbench.course.CourseIdentity.Principal p = dev.workbench.course.CourseIdentity.CURRENT.get();
            String pinned = crt == null ? null : crt.pinTarget(args, p != null && p.machine());
            if (client != null) dev.workbench.registry.Dispatcher.CLIENT.set(client);
            long t0 = System.nanoTime();
            try { res = dev.workbench.llm.ToolFacade.call(d, tool, args, req.actor(), true); }
            finally { dev.workbench.registry.Dispatcher.CLIENT.remove(); }
            if (crt != null) {
                Map<String, Object> prov = crt.provenanceFor(tool, args, res, client, req.str("callId"), pinned);
                if (p != null && p.machine()) crt.captureAssistantCall(p, client, tool, args, res, prov, (System.nanoTime() - t0) / 1_000_000);
                if (req.bool("provenance", false) && Boolean.TRUE.equals(res.get("ok"))) {
                    Map<String, Object> wrapped = new LinkedHashMap<>();
                    wrapped.put("result", res.get("data"));
                    wrapped.put("provenance", prov);
                    return wrapped;
                }
            }
            if (Boolean.TRUE.equals(res.get("ok"))) return res.get("data");
            Object err = res.get("error");
            if (err instanceof Map<?, ?> em) {
                String code = String.valueOf(em.get("code"));
                String msg = String.valueOf(em.get("message"));
                String detail = em.get("detail") != null ? String.valueOf(em.get("detail")) : "";
                OpException ex = new OpException(code, msg, detail);
                for (Map.Entry<?, ?> e : em.entrySet()) {   // hint, candidates … ride along
                    String k = String.valueOf(e.getKey());
                    if (!k.equals("code") && !k.equals("message") && !k.equals("detail")) ex.extra.put(k, e.getValue());
                }
                throw ex;
            }
            throw OpException.io("tool failed: " + tool, String.valueOf(err));
        }));

        /* the log: system.logs {tail, level?, grep?} reads <home>/logs/workbench.log back (newest last);
           log.client {level, message, stack?, url?, context?} lets the browser file its own errors
           (window.onerror, unhandled rejections) beside the server's */
        r.register(new Op("system.logs", "", false, Cost.cheap, req -> {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("file", dev.workbench.server.Log.file() == null ? null : dev.workbench.server.Log.file().getAbsolutePath());
            out.put("lines", dev.workbench.server.Log.tail(req.num("tail", 200), req.str("level"), req.str("grep")));
            return out;
        }));
        /* the audit trail (Log.audit): audit {tail, actor?, op?, since?} lists calls; audit {summary: true,
           since?} answers "who used Jeeves and how" per actor — calls, writes, ops, targets, clients, first/last */
        r.register(new Op("audit", "", false, Cost.cheap, req -> {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("file", dev.workbench.server.Log.auditFile() == null ? null : dev.workbench.server.Log.auditFile().getAbsolutePath());
            if (req.bool("summary", false)) out.put("actors", dev.workbench.server.Log.auditSummary(req.str("since")));
            else out.put("calls", dev.workbench.server.Log.auditTail(req.num("tail", 200), req.str("actor"), req.str("op"), req.str("since")));
            return out;
        }));
        r.register(new Op("log.client", "", false, Cost.cheap, req -> {
            String level = req.str("level", "error");
            Map<String, Object> ctx = new LinkedHashMap<>();
            if (req.str("url") != null) ctx.put("url", req.str("url"));
            if (req.params().get("context") != null) ctx.put("context", req.params().get("context"));
            String msg = req.require("message") + (req.str("stack") != null ? "\n    " + req.str("stack").replace("\n", "\n    ") : "");
            if ("warn".equalsIgnoreCase(level)) dev.workbench.server.Log.warn("browser " + req.actor(), msg, ctx);
            else if ("info".equalsIgnoreCase(level)) dev.workbench.server.Log.info("browser " + req.actor(), msg);
            else dev.workbench.server.Log.error("browser " + req.actor(), msg, ctx);
            return Map.of("logged", true);
        }));
        // ---- §4.1 projects & targets
        r.register(new Op("workspace.state", "", false, Cost.cheap, req -> ws.state()));
        // ---- §4.16 system (prerequisites) — the rows ./provision.sh --json reports, from inside
        r.register(new Op("system.doctor", "", false, Cost.scan, req -> new Doctor(ws).run(req.str("rows"))));   // [--rows a,b] = only those
        // host memory for the Σ ribbon item — the workbench's own footprint on the
        // machine, not a target's heap (that is target.info.heap)
        r.register(new Op("workspace.sysmem", "", false, Cost.cheap, req -> sysmem()));
        r.register(new Op("project.list", "", false, Cost.cheap, req -> ws.projectList()));
        r.register(new Op("project.known", "", false, Cost.cheap, req -> ws.knownProjects()));
        r.register(new Op("project.forget", "", true, Cost.cheap, req -> {
            /* the confirm gate is not GUI ceremony (map.delete's rule): the op
               parks a WHOLE project sidecar, and MCP agents have no inline
               confirm — the param IS the gate (r3 #4) */
            if (!req.bool("confirm", false))
                throw OpException.syntax("project.forget needs confirm:true",
                        "the sidecar is parked as <slug>.forgotten-<ts>");
            return ws.forgetProject(req.require("name"));
        }));
        /* project.delete is forget's DESTRUCTIVE sibling: the dir is wiped, not
           parked — the confirm gate carries the whole weight of that (2026-09-08) */
        r.register(new Op("project.delete", "", true, Cost.cheap, req -> {
            if (!req.bool("confirm", false))
                throw OpException.syntax("project.delete needs confirm:true",
                        "the project dir is WIPED — maps, session, membership; store entries survive");
            return ws.deleteProject(req.require("name"));
        }));
        /* the project editor's membership view (target store, 2026-09-08) */
        r.register(new Op("project.targets", "", false, Cost.cheap,
                req -> ws.projectTargets(req.str("project"))));
        /* membership removal — the store entry (jdb2) survives; closes the live
           session when it is open in that project */
        r.register(new Op("target.remove", "", true, Cost.cheap,
                req -> ws.removeTarget(req.str("project"), req.require("target"))));
        r.register(new Op("project.switch", "", true, Cost.cheap, req -> {
            ws.switchProject(req.require("project"));
            return ws.state();
        }));
        r.register(new Op("project.open", "", true, Cost.cheap,
                req -> ws.openProject(req.require("name"))));
        r.register(new Op("project.close", "", true, Cost.cheap,
                req -> ws.closeProject(req.require("project"))));
        r.register(new Op("target.list", "", false, Cost.cheap, req -> ws.targetList()));
        /* rekey: the workbench UI's open gestures send it — when the saved database shares its JEB
           key with an open target, the artifact is analysed again under a key of its own instead
           of the open being refused (Workspace.rekeyForOpen). Human actors only; the MCP tool
           surface does not forward it. target.rekeyUndo is the way back. */
        r.register(new Op("target.open", "", true, Cost.heavy, req ->
                ws.openTarget(req.require("path"), req.str("engine"), req.actor(), req.bool("rekey", false))));
        r.register(new Op("target.rekeyUndo", "", true, Cost.heavy, req ->
                ws.rekeyUndo(req.str("target"), req.actor())));
        r.register(new Op("target.close", "", true, Cost.cheap, req -> {
            /* save: absent = today's rule (write when dirty); true = the same; false = DISCARD —
               close without writing AND mark the unsaved history so it does not replay on
               the next open (the close dialog's "no" must mean no) */
            Object sv = req.params().get("save");
            Boolean save;
            if (sv == null) save = null;
            else if (sv instanceof Boolean b) save = b;
            else throw OpException.syntax("save must be a JSON boolean (true = write when dirty, false = DISCARD the unsaved edits)"
                    + " — a string like \"1\" would have parsed to false and silently discarded", "save=" + sv);
            if (req.bool("async", false)) return ws.closeTargetAsync(req.require("target"), save);
            Engine closingEngine = ws.engineFor(req.require("target"));
            ws.closeTarget(req.require("target"), save);
            Map<String, Object> closed = new LinkedHashMap<>(ws.state());
            closed.put("closeCapabilities", closingEngine.closeCapabilities());
            return closed;
        }));
        /* the human's last position on the artifact (view.json in its sidecar): the UI saves it on
           class open / switch / close / unload and restores it on every landing — a project close
           no longer loses where you were */
        r.register(new Op("target.view", "", false, Cost.cheap, req -> ws.targetView(req.str("target"))));
        r.register(new Op("target.view.set", "", true, Cost.cheap, req -> {
            Object v = req.params().get("view");
            if (!(v instanceof Map<?, ?>)) throw OpException.syntax("view must be an object {class, caret, scroll, tabs}", "");
            @SuppressWarnings("unchecked") Map<String, Object> vm = (Map<String, Object>) v;
            return ws.setTargetView(req.str("target"), vm);
        }));
        r.register(new Op("target.changes", "", false, Cost.cheap,
                req -> ws.targetChanges(req.str("target"))));   // what a close would write: distinct symbols renamed / commented since the last save
        r.register(new Op("target.switch", "", true, Cost.cheap, req -> {
            ws.switchTarget(req.require("target"));
            return ws.state();
        }));
        r.register(new Op("target.save", "", true, Cost.heavy,
                req -> req.bool("async", false) ? ws.saveTargetAsync(req.str("target")) : ws.saveTarget(req.str("target"))));
        r.register(new Op("target.saveStatus", "", false, Cost.cheap,
                req -> ws.saveStatus(req.str("target"))));   // the saving box polls it: bytes written vs the size before the write
        r.register(new Op("target.recent", "", false, Cost.cheap,
                req -> ws.recentTargets(req.num("limit", 9))));   // the launcher's RECENT TARGETS zone (audit decision 18)
        r.register(new Op("target.info", "", false, Cost.cheap, req -> {
            Map<String, Object> info = new LinkedHashMap<>(engine(ws, req).info());
            info.put("identity", ws.targetIdentity(ws.resolveTargetIdOrActive(req.str("target"))));   // executable · database · revision · runtime, apart
            return info;
        }));
        /* host-fs listing for the launcher's BROWSE pane: server and browser share
           the machine (loopback-only), so a pick is a PATH, not an upload — no
           150MB APK round-trips through HTTP, no staged copies */
        r.register(new Op("fs.list", "", false, Cost.cheap,
                req -> fsList(req.str("dir"))));

        // ---- §4.2 symbols & navigation
        r.register(new Op("symbol.resolve", "", false, Cost.cheap,
                req -> ws.resolveSymbol(ws.resolveTargetIdOrActive(req.str("target")), req.require("symbol"))));   // history-aware; a lenient hit is said in the answer and in meta.resolution
        r.register(new Op("symbol.tree", "", false, Cost.scan,   // name search counts matches beyond the cap — a full-classlist walk (fix-7 F4), not cheap
                req -> req.params().containsKey("prefix")    // a place in the package tree: its sub-packages and classes, shallow (Engine.treeUnder)
                        ? engine(ws, req).treeUnder(req.str("prefix"), req.num("limit", 0))
                        : engine(ws, req).tree(req.str("filter"), req.num("limit", 0))));   // limit: the caller's cut (0 = every row)
        r.register(new Op("symbol.search", "", false, Cost.scan, req -> {
            int limit = req.num("limit", 0);
            Map<String, Object> page = engine(ws, req).symbolSearchPage(req.require("query"), limit, req.str("kind"));   // kind: class | method | field
            return limit > 0 ? page : page.get("rows");   // the universal shape: every row, or {rows, total, truncated} with a limit
        }));
        r.register(mockStub("ui.open", true));

        // ---- §4.3 code
        r.register(new Op("code.decompile", "decompile", false, Cost.cheap, req -> {
            Map<String, Object> opts = new LinkedHashMap<>();
            if (req.str("window") != null) opts.put("window", req.str("window"));
            if (req.str("scope") != null) opts.put("scope", req.str("scope"));      // method | class (absent: by the symbol)
            if (req.params().get("tokens") != null) opts.put("tokens", req.bool("tokens", false));   // the UI's token map, on request only
            Map<String, Object> res = engine(ws, req).decompile(req.require("symbol"), opts);
            /* a LENIENT resolution of the asked descriptor is said in the code answer too (resolution: asked,
               what was overlooked, contradiction) — the reading agent learns its reference was off before it
               cites it; the corpus paths refuse a contradiction outright (Engine.resolveStrict) */
            if (res != null) {
                try {
                    Map<String, Object> rs = ws.resolveSymbol(ws.resolveTargetIdOrActive(req.str("target")), req.require("symbol"));
                    if (Boolean.FALSE.equals(rs.get("exact"))) {
                        Map<String, Object> resolution = new LinkedHashMap<>();
                        for (String k : List.of("asked", "addr", "origSig", "lenient", "contradiction", "note")) if (rs.get(k) != null) resolution.put(k, rs.get(k));
                        resolution.put("requested", rs.get("asked")); resolution.put("resolvedOriginal", rs.get("origSig") != null ? rs.get("origSig") : rs.get("addr"));
                        res.put("resolution", resolution);
                    }
                } catch (Exception ignored) {}
            }
            /* the UI's read (tokens:true) also learns which comments an agent wrote, so the code view
               can set them apart and wrap them (user 2026-09-19); an agent's own read gets agentNames
               from the decorator instead */
            if (req.bool("tokens", false) && res != null) {
                List<Object> ac = ws.agentComments(req.str("target"));
                if (!ac.isEmpty()) res.put("agentComments", ac);
            }
            return res;
        }));
        /* §C of the path-research handoff: one call site's bounded context — the symbol may carry the offset
           (an xref row's `from`: "Lcls;->m()V+634h") or `at` names it ("+634h", "634h", "0x634", hex) */
        r.register(new Op("code.site", "decompile", false, Cost.scan, req -> {
            String symbol = req.require("symbol").trim();
            String at = req.str("at");
            int plus = symbol.lastIndexOf('+');
            if (at == null && plus > 0 && plus > symbol.indexOf("->")) { at = symbol.substring(plus); symbol = symbol.substring(0, plus); }
            if (at == null || at.isBlank()) throw OpException.syntax("code.site needs the offset: pass the xref's `from` (\"Lcls;->m()V+634h\") as the symbol, or `at` (\"+634h\")", "at");
            String s = at.trim(); if (s.startsWith("+")) s = s.substring(1);
            long offset;
            try {
                if (s.toLowerCase(java.util.Locale.ROOT).endsWith("h")) offset = Long.parseLong(s.substring(0, s.length() - 1), 16);
                else if (s.toLowerCase(java.util.Locale.ROOT).startsWith("0x")) offset = Long.parseLong(s.substring(2), 16);
                else offset = Long.parseLong(s, 16);   // DEX offsets are hex throughout this API (the packet's refs, the smali rows)
            } catch (NumberFormatException e) { throw OpException.syntax("at must be an instruction offset in hex: +634h, 634h or 0x634", "at=" + at); }
            Map<String, Object> opts = new LinkedHashMap<>();
            if (req.params().get("context") != null) opts.put("context", req.num("context", 6));
            if (req.params().get("lines") != null) opts.put("lines", req.num("lines", 8));
            if (req.params().get("java") != null) opts.put("java", req.bool("java", true));
            String tid = ws.resolveTargetIdOrActive(req.str("target"));
            ws.resolveSymbol(tid, symbol);   // a lenient caller reference is said in meta.resolution
            return ws.engineFor(tid).callSite(symbol, offset, opts);
        }));
        r.register(new Op("code.smali", "smali", false, Cost.scan,
                req -> engine(ws, req).smali(strictSymbol(ws, req))));   // the byte-check source: a contradicting descriptor is refused
        r.register(new Op("code.hover", "", false, Cost.cheap,
                req -> engine(ws, req).hover(noteResolution(ws, req), req.num("offset", 0))));
        // outline {class, inherited?}: members with ORIGINAL names/descriptors, field type/kind, same-name collisions; the superclass chain on request
        r.register(new Op("code.outline", "", false, Cost.cheap,
                req -> engine(ws, req).outline(req.require("class"), req.bool("inherited", false))));
        r.register(new Op("code.classinfo", "", false, Cost.cheap,
                req -> engine(ws, req).classinfo(req.require("class"))));
        /* evidence.packet {symbol, question?, entry?, maxInstructions?, java?, student?} — a bounded, read-only
           packet for one method (Engine.evidencePacket; the MCP `code` tool's view "packet"; course
           integration P0.4): original identity, complete code, stable instruction references, the
           calls/fields/types/strings touched, annotation provenance, explicit omissions; a CFG
           branch selection when the engine has a CFG and the caller names an entry */
        r.register(new Op("evidence.packet", "decompile", false, Cost.scan, req -> {
            Map<String, Object> opts = new LinkedHashMap<>();
            for (String k : List.of("question", "entry", "maxInstructions", "java", "student")) if (req.params().get(k) != null) opts.put(k, req.params().get(k));
            Map<String, Object> packet = engine(ws, req).evidencePacket(strictSymbol(ws, req), opts);   // evidence: a contradicting descriptor is refused, a history address passes
            /* the workspace's snapshot identity rides along: a peer reopening the packet checks it
               against the target they hold (P0.4: references resolve against the captured snapshot) */
            String tid = ws.resolveTargetIdOrActive(req.str("target"));
            /* the analyst's argument mappings beside the raw metadata — the packet's provenance promise */
            if (packet.get("methodParameters") instanceof Map<?, ?> mp0) {
                @SuppressWarnings("unchecked") Map<String, Object> mp = (Map<String, Object>) mp0;
                mp.put("mappings", ws.parameterMappings(tid, String.valueOf(mp.get("method")), String.valueOf(mp.get("original_method"))));
            }
            /* the three identities, APART (2026-10-09): the executable's bytes with the STATE of that knowledge, the
               analysis database, this room's revision, the running build — and `artifactSha256` only when the
               executable was MEASURED (a packet from a reopened .jdb2 used to omit it while the note still said it
               identified the bytes) */
            Map<String, Object> identity = ws.targetIdentity(tid);
            @SuppressWarnings("unchecked") Map<String, Object> exe = identity != null && identity.get("executable") instanceof Map<?, ?> ex0 ? (Map<String, Object>) ex0 : Map.of("state", "missing", "basis", "no identity record");
            String idState = String.valueOf(exe.get("state"));
            Map<String, Object> snap = new LinkedHashMap<>();
            snap.put("target", tid);
            snap.put("targetName", ws.targetNameOf(tid));
            snap.put("storeKey", ws.targetStoreKey(tid));
            if ("measured".equals(idState)) snap.put("artifactSha256", exe.get("sha256"));
            snap.put("historyRevision", ws.historyRevision(tid));
            if (identity != null) { snap.put("executable", identity.get("executable")); snap.put("database", identity.get("database")); snap.put("revision", identity.get("revision")); snap.put("runtime", identity.get("runtime")); }
            /* in a course room: the course's target id and manifest digest — what a peer in another
               room compares; the target id and revision above are THIS room's */
            dev.workbench.course.CourseRuntime crt = dev.workbench.course.CourseRuntime.current();
            if (crt != null) {
                snap.put("room", dev.workbench.course.Course.room());
                Map<String, Object> mr = crt.manifest().resolveDigest(ws.executableSha(tid), ws.targetNameOf(tid));
                snap.put("manifestMatch", mr.get("match"));
                if ("match".equals(mr.get("match")) && crt.manifest().targetId() != null) snap.put("courseTargetId", crt.manifest().targetId());   // the course's id only when these ARE its bytes
                if (crt.manifest().manifestSha256() != null) snap.put("manifestSha256", crt.manifest().manifestSha256());
            }
            snap.put("note", switch (idState) {
                case "measured" -> "artifactSha256 identifies the executable's bytes for anyone (" + exe.get("basis") + "); target, storeKey and historyRevision are this room's own (the revision moves with every edit here)";
                case "recorded" -> "NO measured executable identity: executable.sha256 was RECORDED by a device pull and not re-measured here — cite it as recorded, never as verified; target, storeKey and historyRevision are this room's own";
                case "conflict" -> "the file beside the database is NOT the database's input: executable.sha256 is the database input's (measured), sibling_sha256 the file's — say which bytes a claim is about; target, storeKey and historyRevision are this room's own";
                default -> "NO executable identity: this database was opened without its APK/DEX and nothing measured or recorded the bytes — a claim from this packet is about the database (" + (identity != null && identity.get("database") instanceof Map<?, ?> dbm ? dbm.get("file") : "?") + "), not about identified executable bytes; target, storeKey and historyRevision are this room's own";
            });
            packet.put("snapshot", snap);
            return packet;
        }));

        // ---- §4.4 relations
        /* xrefs never cut silently (user rule): every row unless the caller passes limit — and
           then the result is {rows, total, truncated} so the cut is visible */
        /* the relation ops say a lenient resolution of their symbol in the envelope's meta.resolution (the
           Workspace records it; the rows keep their shape) — the wrong-return-type xref of 2026-10-09 answered
           as if the descriptor had been right */
        r.register(new Op("xref.to", "xrefs", false, Cost.scan,
                req -> limited(engine(ws, req).xrefsTo(noteResolution(ws, req)), req)));
        r.register(new Op("xref.from", "xrefs", false, Cost.scan,
                req -> limited(engine(ws, req).xrefsFrom(noteResolution(ws, req)), req)));
        r.register(new Op("callees", "xrefs", false, Cost.scan,
                req -> limited(engine(ws, req).callees(noteResolution(ws, req)), req)));
        r.register(new Op("callers", "xrefs", false, Cost.scan,
                req -> limited(engine(ws, req).callers(noteResolution(ws, req)), req)));
        r.register(new Op("impls", "", false, Cost.scan,
                req -> engine(ws, req).implementations(noteResolution(ws, req))));

        // ---- §4.5 strings & search
        r.register(new Op("strings", "strings", false, Cost.scan, req -> {
            List<String> types = null;
            String t = req.str("type");
            if (t != null && !t.isBlank()) types = List.of(t.split(","));
            return engine(ws, req).strings(req.str("filter"), types,
                    req.bool("unreachable", false), req.num("offset", 0), req.num("count", 200),
                    req.str("sort"));   // priority (default) | xrefs | order — audit decision 5
        }));
        /* §I of the path-research handoff (2026-10-09): find a location across builds by SEVERAL fallible anchors —
           a diagnostic string, a table name (a string), an API call (xrefs to it), a class or method name, a prototype
           shape — ranked candidates with the anchors each matched and missed, and an honest status: NOT LOCATED means
           "not found by these queries", never "absent". Anchor presence, structural correspondence, a connected
           static path and a runtime observation are separate statuses the caller records. */
        r.register(new Op("symbol.locate", "", false, Cost.heavy, req -> locate(ws, req)));
        r.register(new Op("string.xrefs", "strings", false, Cost.scan,
                req -> engine(ws, req).stringXrefs(req.require("id"))));
        r.register(new Op("search", "", false, Cost.heavy, req -> {
            Map<String, Object> opts = new LinkedHashMap<>();
            for (String k : List.of("scope", "kind", "regex", "case", "limit"))
                if (req.str(k) != null) opts.put(k, req.str(k));
            if (req.bool("allTargets", false)) {
                // QA M4: fan out over every open target; groups already carry
                // target + engine badges. A target that can't search (not ready)
                // is skipped, not fatal to the rest.
                List<Object> all = new ArrayList<>();
                for (String tid : ws.openTargetIds()) {
                    try { all.addAll(ws.engineFor(tid).search(req.require("query"), opts)); }
                    catch (OpException ignored) {}
                }
                return all;
            }
            return engine(ws, req).search(req.require("query"), opts);
        }));
        r.register(new Op("search.index", "", false, Cost.cheap,
                req -> engine(ws, req).searchIndexStatus()));
        r.register(new Op("search.reindex", "", true, Cost.heavy, req -> {
            engine(ws, req).reindex();
            return engine(ws, req).searchIndexStatus();
        }));

        // ---- §4.6 edit ops (rename/comment live; llm actors get proposals per MCP_SPEC §3)
        // rename / comment / tag.add also take items:[{…}] — see enableBatch below
        /* The analyst's own decisions (a parameter mapping, a restored original name): a browser session —
           never a machine client whatever actor it names (in a room the bearer path's MCP bridge or a
           script says human:<room> for attribution, not for consent; CourseIdentity marks it machine), and
           never an agent (fix-review 2026-10-08 M4). */
        interface AnalystGate { void check(Op.Request req, String what, String how) throws OpException; }
        AnalystGate analystOnly = (req, what, how) -> {
            dev.workbench.course.CourseIdentity.Principal p = dev.workbench.course.CourseIdentity.CURRENT.get();
            if (p != null && p.machine()) throw OpException.scope(what + " is the analyst's own decision, taken from the page — a machine client (the MCP bridge, a script) cannot take it for them", "principal=machine");
            if (!"human".equals(EventBus.actorKind(req.actor()))) throw OpException.scope(what + " is the analyst's decision — " + how, "actor=" + req.actor());
        };
        r.register(new Op("rename", "rename", true, Cost.cheap, req -> {
            String tp = req.str("target");
            String symbol = req.require("symbol");
            String name = req.require("name");
            /* a LOCAL variable or parameter: {symbol: the method, local: its current name, name}.
               Applied at once for every actor — locals are the method's private vocabulary, not a
               symbol the review policy or the human-name protection speaks about; History records
               it (kind local) so it undoes and redoes like any rename. */
            if (req.str("local") != null && !req.str("local").isBlank()) {
                /* an agent naming a FORMAL ARGUMENT: never over the analyst's mapping (that argument is theirs),
                   and a name equal to a retained MethodParameters entry is recorded as such — the annotation
                   is metadata, not a verified name (fix-review 2026-10-08 M5) */
                Object retained = null;
                Map<String, Object> argHit = null;
                if (!"human".equals(EventBus.actorKind(req.actor()))) {
                    try {
                        Map<String, Object> raw = engine(ws, req).methodParameters(symbol, false);   // the cheap read: annotation + debug info
                        List<?> an = raw.get("annotation") instanceof Map<?, ?> am && am.get("names") instanceof List<?> nl ? nl : List.of();
                        if (an.contains(name)) retained = an.indexOf(name);
                        List<Object> maps = ws.parameterMappings(ws.resolveTargetIdOrActive(tp), String.valueOf(raw.get("method")), String.valueOf(raw.get("original_method")));
                        if (!maps.isEmpty()) {
                            Map<String, Object> full = engine(ws, req).methodParameters(symbol, true);
                            if (full.get("parameters") instanceof List<?> pl) for (Object o : pl) if (o instanceof Map<?, ?> pm && req.str("local").equals(pm.get("name"))) {
                                for (Object mo : maps) if (mo instanceof Map<?, ?> mm && mm.get("changes") instanceof List<?> cl) for (Object co : cl)
                                    if (co instanceof Map<?, ?> cm && String.valueOf(cm.get("index")).equals(String.valueOf(pm.get("index"))))
                                        throw OpException.scope("argument " + pm.get("index") + " of " + raw.get("method") + " carries the analyst's mapping '" + pm.get("name") + "' (History " + mm.get("id") + ") — not an agent's to rename", "actor=" + req.actor());
                                @SuppressWarnings("unchecked") Map<String, Object> hit = (Map<String, Object>) pm;
                                argHit = hit;
                            }
                        }
                    } catch (OpException ex) { if ("E_SCOPE".equals(ex.code)) throw ex; }   // metadata this engine cannot read: the rename proceeds as before
                }
                Map<String, Object> res = engine(ws, req).renameLocal(symbol, req.str("local"), name, req.actor());
                Map<String, Object> h = new LinkedHashMap<>();
                h.put("op", "rename");
                h.put("kind", "local");
                h.put("actor", req.actor());
                h.put("addr", res.get("addr"));
                h.put("newAddr", res.get("addr"));
                h.put("oldName", res.get("oldName"));
                h.put("newName", res.get("newName"));
                h.put("owner", historyOwner(res));
                if (argHit != null) h.put("argument", argHit.get("index"));
                if (retained != null) {
                    h.put("matchesRetainedName", true); h.put("retainedEntry", retained);
                    h.put("note", "equals entry " + retained + " of the method's retained MethodParameters — metadata, an unverified mapping");
                    res.put("matchesRetainedName", true);
                }
                if (req.params().get("confidence") != null) h.put("confidence", req.params().get("confidence"));
                ws.recordHistory(tp, h);
                res.put("historyId", h.get("id"));
                return res;
            }
            if ("agent".equals(EventBus.actorKind(req.actor()))) {                 // agents propose, humans dispose
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("target", tp);
                body.put("symbol", symbol);
                body.put("newName", name);
                try {
                    Map<String, Object> sym = engine(ws, req).resolve(symbol);
                    body.put("oldName", sym.get("name"));
                    body.put("owner", sym.getOrDefault("owner", sym.getOrDefault("fqcn", "")));
                } catch (Exception ignored) {}
                /* the agent's reason and confidence ride the proposal: the review gate (ACCEPT ALL, the ✓
                   second-click arm) reads them — without them every agent write was "no rationale" */
                if (req.str("rationale") != null) body.put("rationale", req.str("rationale"));
                if (req.params().get("confidence") != null) body.put("confidence", req.params().get("confidence"));
                return ws.agents().propose("rename", body, req.actor());
            }
            Map<String, Object> res = engine(ws, req).rename(symbol, name, req.actor());
            if (Boolean.TRUE.equals(res.get("applied"))) {
                Map<String, Object> h = new LinkedHashMap<>();
                h.put("op", "rename");
                h.put("actor", req.actor());
                h.put("kind", res.get("kind"));
                h.put("addr", res.get("addr"));
                h.put("newAddr", res.get("newAddr"));
                h.put("oldName", res.get("oldName"));
                h.put("newName", res.get("newName"));
                h.put("owner", historyOwner(res));
                ws.recordHistory(tp, h);
                res.put("historyId", h.get("id"));
            }
            return res;
        }));
        /* rename.revert {symbol}: back to the symbol's ORIGINAL dex name — the one restoration the
           Java-identifier rule must not block (an obfuscated "1cq" is a fine original and a refused
           new name). Validated against the dex, recorded in History like any rename (undo puts the
           analyst's name back). Humans only, like a rename. */
        r.register(new Op("rename.revert", "rename", true, Cost.cheap, req -> {
            String tp = req.str("target");
            analystOnly.check(req, "rename.revert", "an agent proposes a name through rename");
            Map<String, Object> res = engine(ws, req).restoreOriginalName(req.require("symbol"), null, req.actor());
            if (Boolean.TRUE.equals(res.get("applied"))) {
                Map<String, Object> h = new LinkedHashMap<>();
                h.put("op", "rename"); h.put("actor", req.actor()); h.put("kind", res.get("kind"));
                h.put("addr", res.get("addr")); h.put("newAddr", res.get("newAddr"));
                h.put("oldName", res.get("oldName")); h.put("newName", res.get("newName"));
                /* the owner is the class as it is NOW: a reverted class answers to its original name, not to
                   the analyst's addr the request named (the HISTORY row's click goes there) */
                Map<String, Object> ownerSrc = new LinkedHashMap<>(res);
                if ("class".equals(res.get("kind")) && res.get("newAddr") != null) { ownerSrc.put("addr", res.get("newAddr")); ownerSrc.remove("symbol"); }
                h.put("owner", historyOwner(ownerSrc)); h.put("revert", true);
                ws.recordHistory(tp, h);
                res.put("historyId", h.get("id"));
            }
            return res;
        }));
        /* code.params {symbol}: the retained parameter metadata of a METHOD — the dex's MethodParameters
           annotation verbatim and the debug_info names as separate sources, the current prototype and
           the decompiler's current argument names with where they come from, the analyst's mappings
           from History. A read: nothing is renamed, nothing dirtied (2026-10-08). */
        r.register(new Op("code.params", "", false, Cost.scan, req -> {   // one class render on a cold method; cached while no name moves
            Map<String, Object> meta = engine(ws, req).methodParameters(noteResolution(ws, req));
            String tid = ws.resolveTargetIdOrActive(req.str("target"));
            meta.put("mappings", ws.parameterMappings(tid, String.valueOf(meta.get("method")), String.valueOf(meta.get("original_method"))));
            return meta;
        }));
        /* param.rename {symbol, index, name, annotationIndex?} — ONE argument, by position, the
           analyst's explicit decision (a retained name applied to a chosen argument, cross-position
           allowed); param.rename.bulk {symbol, mapping:[{index, name, annotationIndex?}], preview?} —
           the whole list, previewed first (every current → proposed with the destination argument),
           applied atomically as ONE undoable History entry. Humans only: an agent does not impersonate
           the analyst's approval. Null/empty names are no-ops. */
        r.register(new Op("param.rename", "rename", true, Cost.cheap, req -> {
            analystOnly.check(req, "mapping a retained name onto an argument", "an agent does not take it; it may name a LOCAL through rename {local}, never an argument the analyst mapped");
            int index = req.num("index", -1);
            if (index < 0) throw OpException.syntax("index names the formal argument (0-based, this excluded)", "");
            Map<String, Object> change = new LinkedHashMap<>();
            change.put("index", index); change.put("name", req.require("name"));
            if (req.params().get("annotationIndex") != null) change.put("annotationIndex", req.params().get("annotationIndex"));
            return ws.renameParameters(req.str("target"), req.require("symbol"), List.of(change), req.str("source", "dex_method_parameters_annotation"), req.actor(), expectCurrent(req));
        }));
        r.register(new Op("param.rename.bulk", "rename", true, Cost.cheap, req -> {
            if (!req.bool("preview", false)) analystOnly.check(req, "mapping retained names onto arguments", "an agent does not take it");   // the preview is a read anyone may ask for
            if (!(req.params().get("mapping") instanceof List<?> ml)) throw OpException.syntax("mapping is a list of {index, name, annotationIndex?}", "");
            List<Map<String, Object>> changes = new ArrayList<>();
            for (Object o : ml) if (o instanceof Map<?, ?> m) { Map<String, Object> c = new LinkedHashMap<>(); for (Map.Entry<?, ?> e : m.entrySet()) c.put(String.valueOf(e.getKey()), e.getValue()); changes.add(c); }
            if (req.bool("preview", false)) return ws.previewParameterMapping(req.str("target"), req.require("symbol"), changes);
            return ws.renameParameters(req.str("target"), req.require("symbol"), changes, req.str("source", "dex_method_parameters_annotation"), req.actor(), expectCurrent(req));
        }));
        r.register(new Op("comment", "comment", true, Cost.cheap, req -> {
            String tp = req.str("target");
            String symbol = req.require("symbol");
            String text = req.str("text", "");
            if ("agent".equals(EventBus.actorKind(req.actor()))) {
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("target", tp);
                body.put("symbol", symbol);
                body.put("newText", text);
                /* the agent's reason and confidence ride the proposal: the review gate (ACCEPT ALL, the ✓
                   second-click arm) reads them — without them every agent write was "no rationale" */
                if (req.str("rationale") != null) body.put("rationale", req.str("rationale"));
                if (req.params().get("confidence") != null) body.put("confidence", req.params().get("confidence"));
                return ws.agents().propose("comment", body, req.actor());
            }
            Map<String, Object> res = engine(ws, req).comment(symbol, text, req.actor());
            if (Boolean.TRUE.equals(res.get("applied"))) {
                Map<String, Object> h = new LinkedHashMap<>();
                h.put("op", "comment");
                h.put("actor", req.actor());
                h.put("addr", res.get("addr"));
                h.put("newAddr", res.get("addr"));
                h.put("oldText", res.get("oldText"));
                h.put("newText", text);
                h.put("owner", historyOwner(res));
                ws.recordHistory(tp, h);
                res.put("historyId", h.get("id"));
            }
            return res;
        }));
        /* locals.auto {symbol: method, all?}: name the method's generated locals after their declared
           types (DownloadHelper v5 → downloadHelper, a second one downloadHelper_2); each rename is a
           History entry (undoable) and a binding that follows a later rename of the class. */
        r.register(new Op("locals.auto", "rename", true, Cost.cheap, req ->
                ws.autoNameLocals(req.str("target"), req.require("symbol"), req.bool("all", false), req.actor())));
        r.register(new Op("locals.list", "", false, Cost.cheap, req -> engine(ws, req).locals(noteResolution(ws, req))));
        // ---- tags (VIEWS_SPEC §4 — workspace-level, sidecar-persisted)
        r.register(new Op("tag.add", "", true, Cost.cheap, req -> {
            Integer line = req.str("line") == null ? null : req.num("line", -1);
            /* resolved ONCE: proposals stamp the resolved target id, so every
               downstream scope guard must compare resolved-to-resolved */
            String tid = ws.resolveTargetIdOrActive(req.str("target"));
            if ("agent".equals(EventBus.actorKind(req.actor()))) {
                // pending tag entry recorded by Annotations AND a reviewable proposal —
                // UNLESS tagAdd deduped into an existing row (nothing new to review)
                Map<String, Object> added = ws.annotations().tagAdd(req.str("target"), req.require("symbol"),
                        req.require("name"), req.str("note"), req.actor(), line);
                if (Boolean.TRUE.equals(added.get("deduped"))) {
                    if (Boolean.TRUE.equals(added.get("pending"))) {
                        /* the agent's OWN pending row: a newer note must reach the
                           open proposal or the reviewer settles text they never saw
                           (review F3) — and the answer keeps the proposal envelope,
                           `id` naming the PROPOSAL, never the tag row (review F10).
                           Matched by the row's resolved KEY + line + TARGET: two
                           targets can hold the same class (the diff workflow). */
                        String pp = ws.agents().syncTagProposal(req.actor(), tid,
                                String.valueOf(added.get("symbol")), req.require("name"),
                                added.get("line") instanceof Number al ? al.intValue() : null,
                                req.str("note"));
                        if (pp != null) {
                            Map<String, Object> out = new LinkedHashMap<>();
                            out.put("applied", false);
                            out.put("proposal", true);
                            out.put("status", "pending");
                            out.put("id", pp);
                            out.put("deduped", true);
                            return out;
                        }
                        /* pending row but NO open proposal (queue was trimmed) —
                           still the shared vocabulary, proposal:false says so */
                        added.put("applied", false);
                        added.put("proposal", false);
                        added.put("status", "pending");
                    } else {
                        /* already LIVE: nothing to review — say so in the same
                           applied/proposal/status vocabulary as the propose path */
                        added.put("applied", true);
                        added.put("proposal", false);
                        added.put("status", "applied");
                    }
                    return added;
                }
                Map<String, Object> body = new LinkedHashMap<>();
                /* the proposal carries the RESOLVED target/key/line from the row,
                   not raw spellings — settle/sync/supersede all match on these,
                   so an equivalent spelling can never fork the queue (review) */
                body.put("target", tid);
                body.put("symbol", req.str("symbol"));
                body.put("key", added.get("symbol"));
                body.put("line", added.get("line"));
                body.put("name", req.str("name"));
                body.put("note", req.str("note"));
                /* the agent's reason and confidence ride the proposal: the review gate (ACCEPT ALL, the ✓
                   second-click arm) reads them — without them every agent write was "no rationale" */
                if (req.str("rationale") != null) body.put("rationale", req.str("rationale"));
                if (req.params().get("confidence") != null) body.put("confidence", req.params().get("confidence"));
                return ws.agents().propose("tag", body, req.actor());
            }
            Map<String, Object> added = ws.annotations().tagAdd(req.str("target"), req.require("symbol"),
                    req.require("name"), req.str("note"), req.actor(), line);
            /* the human disposed: pending rows for the key were superseded inside
               tagAdd — settle their proposals so the queue stops offering reviews
               whose accept could only go stale (review F6). Key + line from the
               ROW (canonical), target resolved — spelling-proof and per-line. */
            if (added.get("superseded") instanceof Number ns && ns.intValue() > 0)
                ws.agents().supersedeTagProposals(tid,
                        String.valueOf(added.get("symbol")), req.require("name"),
                        added.get("line") instanceof Number hl ? hl.intValue() : null, null);
            return added;
        }));
        r.register(new Op("tag.rm", "", true, Cost.cheap,
                req -> ws.annotations().tagRm(req.str("target"), req.require("id"))));
        r.register(new Op("tag.list", "", false, Cost.cheap,
                req -> ws.annotations().tagList(req.str("target"), req.str("family"))));
        r.register(new Op("tag.family.add", "", true, Cost.cheap,
                req -> ws.annotations().tagFamilyAdd(req.str("target"),
                        req.require("name"), req.str("color"))));
        // ---- bookmarks
        r.register(new Op("bookmark.add", "", true, Cost.cheap, req ->
                ws.annotations().bookmarkAdd(req.str("target"), req.require("symbol"),
                        req.str("line") == null ? null : req.num("line", -1),
                        req.str("note"), req.actor())));
        r.register(new Op("bookmark.rm", "", true, Cost.cheap,
                req -> ws.annotations().bookmarkRm(req.str("target"), req.require("id"))));
        r.register(new Op("bookmark.list", "", false, Cost.cheap,
                req -> ws.annotations().bookmarkList(req.str("target"))));
        r.register(new Op("history.list", "", false, Cost.cheap,
                req -> ws.historyList(req.str("target"), req.num("limit", 200))));
        r.register(new Op("history.undo", "", true, Cost.cheap,
                req -> ws.historyUndo(req.str("target"), req.str("id"), req.actor())));
        r.register(new Op("history.redo", "", true, Cost.cheap,
                req -> ws.historyRedo(req.str("target"), req.str("id"), req.actor())));

        // ---- §4.7 flows (single-target, sidecar-persisted; promote is M6)
        r.register(new Op("flow.list", "", false, Cost.cheap,
                req -> ws.annotations().flowList(req.str("target"))));
        r.register(new Op("flow.get", "", false, Cost.cheap,
                req -> ws.annotations().flowGet(req.str("target"), req.require("id"))));
        r.register(new Op("flow.add", "", true, Cost.cheap, req -> {
            List<String> hops = null;
            String h = req.str("hops");
            if (h != null && !h.isBlank()) hops = List.of(h.split(","));
            return ws.annotations().flowAdd(req.str("target"), req.require("name"),
                    req.require("source"), req.require("sink"), hops, req.actor());
        }));
        r.register(new Op("flow.rm", "", true, Cost.cheap,
                req -> ws.annotations().flowRm(req.str("target"), req.require("id"))));

        // ---- §4.8 runs (VIEWS_SPEC §2 — live via the frida sidecar)
        r.register(new Op("runs.import", "", true, Cost.heavy,
                req -> ws.runs().importBundle(req.require("target"), req.require("directory"))));
        r.register(new Op("runs.export", "", true, Cost.heavy,
                req -> ws.runs().exportBundle(req.str("target"), req.require("run"), req.require("destination"))));
        r.register(new Op("runs.devices", "", false, Cost.scan,
                req -> ws.runs().devices(req.bool("probe", true))));
        r.register(new Op("runs.attach", "", true, Cost.heavy, req ->
                ws.runs().attach(req.require("device"), req.require("pkg"), req.str("target"),
                        false, req.str("coldAction"), req.str("coldComponent"), req.require("serverPath"), req.bool("ownershipConfirmed", false), req.num("serverPort", 27183))));
        r.register(new Op("runs.spawn", "", true, Cost.heavy, req ->
                ws.runs().attach(req.require("device"), req.require("pkg"), req.str("target"),
                        true, req.str("coldAction"), req.str("coldComponent"), req.require("serverPath"), req.bool("ownershipConfirmed", false), req.num("serverPort", 27183))));
        r.register(new Op("runs.rm", "", true, Cost.cheap, req ->
                ws.runs().removeRuns(req.str("target"), req.require("run"))));   // <run> or "all" (ended runs); human-only
        r.register(new Op("runs.detach", "", true, Cost.cheap,
                req -> ws.runs().detach(req.str("session"))));
        r.register(new Op("runs.bp.list", "", false, Cost.cheap,
                req -> ws.runs().bpsFor(req.str("target"))));   // the active project's, or one target's (batch 2)
        r.register(new Op("runs.bp.add", "", true, Cost.cheap, req -> {
            /* native BP: module + export|offset|function (no JEB symbol) — a .so hook via frida
               (2026-09-10). `function` resolves to an offset via idalib against a local `so` copy. */
            if (req.str("module") != null && !req.str("module").isBlank())
                return ws.runs().bpAddNative(req.str("target"), req.str("module"),
                        req.str("export"), req.str("offset"), req.str("function"), req.str("so"),
                        req.str("cond"));
            return ws.runs().bpAdd(req.str("target"), req.require("symbol"), req.str("cond"));
        }));
        r.register(new Op("runs.bp.rm", "", true, Cost.cheap,
                req -> ws.runs().bpRm(req.require("id"))));
        r.register(new Op("runs.bp.toggle", "", true, Cost.cheap,
                req -> ws.runs().bpToggle(req.require("id"))));
        r.register(new Op("runs.hits", "", false, Cost.scan, req ->
                ws.runs().hits(req.str("target"), req.str("run"), req.str("method"),
                        req.num("offset", 0), req.num("limit", 200))));   // [target] picks the store (b2-r1 M1)
        r.register(new Op("runs.runs", "", false, Cost.cheap,
                req -> ws.runs().runsFor(req.str("target"))));   // project-scoped; [target] = the dock's subset
        r.register(new Op("runs.diff", "", false, Cost.cheap, req ->
                ws.runs().diff(req.str("target"), req.require("a"), req.require("b"))));
        r.register(new Op("runs.coverage", "", false, Cost.scan, req ->
                ws.runs().coverage(req.str("target"), req.str("run"), req.str("class"))));

        // ---- §4.9 maps (GRAPHS_SPEC §5 — project-level, MapStore)
        r.register(new Op("map.list", "", false, Cost.cheap, req -> ws.maps().list()));
        r.register(new Op("map.summary", "", false, Cost.cheap,
                req -> ws.maps().summary(req.require("id"))));
        r.register(new Op("map.outline", "", false, Cost.cheap,
                req -> ws.maps().outline(req.require("id"))));
        r.register(new Op("map.get", "", false, Cost.cheap,
                req -> ws.maps().get(req.require("id"), req.num("offset", 0), req.num("count", 0))));
        r.register(new Op("map.node", "", false, Cost.cheap,
                req -> ws.maps().node(req.require("id"), req.require("node"))));
        r.register(new Op("map.neighbors", "", false, Cost.cheap,
                req -> ws.maps().neighbors(req.require("id"), req.require("node"),
                        req.num("depth", 1))));
        r.register(new Op("map.paths", "", false, Cost.scan,
                req -> ws.maps().paths(req.require("id"), req.require("from"), req.require("to"),
                        req.num("max", 5))));
        r.register(new Op("map.find", "", false, Cost.scan,
                req -> ws.maps().find(req.require("id"), req.require("query"))));
        r.register(new Op("map.type.list", "", false, Cost.cheap, req -> ws.maps().typeList()));
        r.register(new Op("map.diff", "", false, Cost.cheap,
                req -> ws.maps().diff(req.require("a"), req.require("b"))));
        r.register(new Op("map.create", "", true, Cost.cheap,
                req -> ws.maps().create(req.require("title"), req.str("build"))));
        r.register(new Op("map.addnode", "", true, Cost.cheap, req -> {
            Object node = req.params().get("node");
            if (!(node instanceof Map)) throw OpException.syntax("map.addnode needs a node object", "");
            @SuppressWarnings("unchecked")
            Map<String, Object> n = (Map<String, Object>) node;
            /* verdict writes are proposal-gated through map.setverdict — an agent
               setting one here would apply it directly (fix-5 review F7) */
            if ("agent".equals(EventBus.actorKind(req.actor())) && n.get("verdict") != null
                    && !String.valueOf(n.get("verdict")).isBlank()
                    && !"none".equals(String.valueOf(n.get("verdict"))))
                throw OpException.scope("agents propose verdicts via map.setverdict — addnode "
                        + "applies them directly", "actor=" + req.actor());
            return ws.maps().addNode(req.require("id"), n);
        }));
        r.register(new Op("map.updatenode", "", true, Cost.cheap, req -> {
            Map<String, Object> patch = new LinkedHashMap<>(req.params());
            patch.remove("id");
            patch.remove("node");
            if ("agent".equals(EventBus.actorKind(req.actor())) && patch.get("verdict") != null
                    && !String.valueOf(patch.get("verdict")).isBlank()
                    && !"none".equals(String.valueOf(patch.get("verdict"))))
                throw OpException.scope("agents propose verdicts via map.setverdict — updatenode "
                        + "applies them directly", "actor=" + req.actor());
            return ws.maps().updateNode(req.require("id"), req.require("node"), patch);
        }));
        r.register(new Op("map.rmnode", "", true, Cost.cheap,
                req -> ws.maps().rmNode(req.require("id"), req.require("node"))));
        r.register(new Op("map.addedge", "", true, Cost.cheap,
                req -> ws.maps().addEdge(req.require("id"), req.require("from"), req.require("to"),
                        req.str("kind", "call"), req.str("label"), req.str("gate"),
                        req.str("confidence"))));
        r.register(new Op("map.rmedge", "", true, Cost.cheap,
                req -> ws.maps().rmEdge(req.require("id"), req.require("from"), req.require("to"),
                        req.str("kind"))));
        r.register(new Op("map.setverdict", "", true, Cost.cheap, req -> {
            if ("agent".equals(EventBus.actorKind(req.actor()))) {
                /* agents PROPOSE verdicts; they never reject one — the reject
                   branch of setVerdict deletes a pending verdict outright, which
                   bypassed review AND left a junk proposal behind
                   (app-review-opus P1-5) */
                if (req.bool("reject", false))
                    throw OpException.scope(
                            "agents propose, humans dispose — map.setverdict reject needs a human actor",
                            "actor=" + req.actor());
                Map<String, Object> res = ws.maps().setVerdict(req.require("id"), req.require("node"),
                        req.str("verdict"), req.str("rationale"), req.str("field"), req.actor(),
                        req.bool("reject", false));
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("map", req.require("id"));
                body.put("node", req.require("node"));
                body.put("field", req.str("field"));
                body.put("verdict", req.str("verdict"));
                body.put("rationale", req.str("rationale"));
                Map<String, Object> p = ws.agents().propose("verdict", body, req.actor());
                p.put("mapPending", res);
                return p;
            }
            return ws.maps().setVerdict(req.require("id"), req.require("node"),
                    req.str("verdict"), req.str("rationale"), req.str("field"), req.actor(),
                    req.bool("reject", false));
        }));
        r.register(new Op("map.setchain", "", true, Cost.cheap, req -> {
            Object steps = req.params().get("steps");
            if (!(steps instanceof List)) throw OpException.syntax("map.setchain needs steps[]", "");
            @SuppressWarnings("unchecked")
            List<Object> st = (List<Object>) steps;
            return ws.maps().setChain(req.require("id"), req.require("node"), st);
        }));
        r.register(new Op("map.attachevidence", "", true, Cost.cheap,
                req -> ws.maps().attachEvidence(req.require("id"), req.require("node"),
                        req.str("field"), req.str("run"), req.str("note"))));
        r.register(new Op("map.type.add", "", true, Cost.cheap,
                req -> ws.maps().typeAdd(req.require("kind"), req.require("name"), req.str("color"))));
        r.register(new Op("map.type.set", "", true, Cost.cheap, req -> {
            Boolean vis = req.str("visible") == null ? null : req.bool("visible", true);
            return ws.maps().typeSet(req.require("kind"), req.require("name"), req.str("color"), vis);
        }));
        r.register(new Op("map.export", "", false, Cost.cheap,
                req -> ws.maps().export(req.require("id"), req.str("fmt", "md"))));
        r.register(new Op("map.open", "", true, Cost.cheap, req -> ws.openMap(req.require("id"))));
        /* map.close {id?} — clears the ACTIVE project's activeMap. Idempotent
           (nothing open is a success) and file-neutral; the bare form closes
           whatever is active. Like every map op it acts on ws.maps(), the
           active project's store: it cannot reach another project's map. */
        r.register(new Op("map.close", "", true, Cost.cheap, req -> ws.closeMap(req.str("id"))));
        /* map.delete {id, confirm:true} — the confirm gate is not GUI ceremony:
           mcp/workbench_mcp.py generates one MCP tool per REGISTERED op (the
           full registry, not the curated 19), so any external client can reach
           this. The map file is parked, not unlinked, and the parked path comes
           back in the result. Deliberately NOT in AgentLoop.TOOL_TO_OP. */
        r.register(new Op("map.delete", "", true, Cost.cheap, req -> {
            if (!req.bool("confirm", false))
                throw OpException.syntax("map.delete needs confirm:true",
                        "map.delete {id, confirm:true} — the file is parked as "
                        + "<id>.map.json.deleted-<ts>, not destroyed");
            return ws.deleteMap(req.require("id"));
        }));
        r.register(new Op("flow.promote", "", true, Cost.cheap, req -> {
            Map<String, Object> flow = ws.annotations().flowResolve(req.str("target"), req.require("id"));
            return ws.maps().promoteFlow(flow, req.str("map"), req.actor());
        }));

        // ---- §4.10 agents (AGENTS_SPEC §5 — live)
        r.register(new Op("agent.missions", "", false, Cost.cheap,
                req -> ws.agents().missionList()));
        r.register(new Op("agent.spawn", "", true, Cost.heavy,
                req -> ws.agents().spawn(req.require("goal"), req.str("scope"), req.str("model"),
                        req.num("budget", 0), req.str("mode"), req.str("depth"), req.str("target"),
                        req.str("effort"), Math.max(0, numOrAbsent(req, "turns")),   // junk → E_SYNTAX, absent → 0 (settings r3 L2)
                        Math.max(0, numOrAbsent(req, "maxOutput")))));                 // the cap on one answer's tokens (Day 1 preset: bounded output)
        // ---- review gate or direct apply: the application-side switch (ReviewPolicy)
        r.register(new Op("review.policy", "", false, Cost.cheap, req -> ws.agents().reviewPolicy().toMap()));
        r.register(new Op("review.configure", "", true, Cost.cheap, req ->
                ws.agents().reviewPolicy().configure(req.str("mode"), req.params().get("kinds"), req.params().get("minConfidence"))));
        // ---- built-in tasks (the reversing agent's model/effort/caps — TaskSettings)
        r.register(new Op("task.list", "", false, Cost.cheap,
                req -> ws.agents().tasks().list(ws.agents().llm())));
        r.register(new Op("task.configure", "", true, Cost.cheap,
                req -> ws.agents().tasks().configure(req.require("task"), req.require("depth"),
                        req.str("model"), req.str("effort"),                     // absent = keep, "" = reset
                        numOrAbsent(req, "turns"), numOrAbsent(req, "budget"),   // absent = keep (-1), 0 = reset, junk = E_SYNTAX
                        ws.agents().llm())));
        r.register(new Op("agent.pause", "", true, Cost.cheap,
                req -> ws.agents().pause(req.require("id"))));
        r.register(new Op("agent.resume", "", true, Cost.cheap, req -> {
            String id = req.require("id");
            int budget = req.num("budget", 0);
            if (budget > 0) ws.agents().setBudget(id, budget);   // resume-with-raise in one call
            int turns = req.num("turns", 0);
            if (turns > 0) ws.agents().setTurns(id, turns);      // …and the turn cap, the same way
            return ws.agents().resume(id);
        }));
        r.register(new Op("agent.stop", "", true, Cost.cheap,
                req -> ws.agents().stop(req.require("id"))));
        r.register(new Op("agent.revert", "", true, Cost.cheap,
                req -> ws.agents().revertMission(req.require("id"))));   // undo everything the mission wrote, newest first
        r.register(new Op("agent.dismiss", "", true, Cost.cheap,
                req -> ws.agents().dismiss(req.require("id"), req.bool("undo", false))));   // × on a finished card: out of the view, record kept
        r.register(new Op("agent.setturns", "", true, Cost.cheap,
                req -> ws.agents().setTurns(req.require("id"), req.num("turns", 0))));
        r.register(new Op("agent.setbudget", "", true, Cost.cheap,
                req -> ws.agents().setBudget(req.require("id"), req.num("budget", 0))));
        r.register(new Op("agent.setscope", "", true, Cost.cheap,
                req -> ws.agents().setScope(req.require("id"), req.str("scope"),
                        req.bool("confirmed", false), req.actor())));
        r.register(new Op("agent.activity", "", false, Cost.cheap,
                req -> ws.agents().activityList(req.str("agent"), req.str("kinds"),
                        req.num("offset", 0), req.num("count", 100))));
        /* the turn drawer (views/agents.ts): one mission's recorded turns, and one turn's full
           transcript — reasoning, assistant text, tool calls with args+results. Read-only; not on
           the MCP surface (the operator's assistant has agent_activity) */
        r.register(new Op("agent.turns", "", false, Cost.cheap,
                req -> ws.agents().turnList(req.require("mission"))));
        r.register(new Op("agent.turn", "", false, Cost.cheap,
                req -> ws.agents().turnGet(req.require("mission"), req.num("turn", 0))));
        r.register(new Op("agent.proposals", "", false, Cost.cheap,
                req -> ws.agents().proposalList(req.str("mission"), req.str("kind"), req.str("target"), req.str("state"))));
        r.register(new Op("agent.purge", "", true, Cost.cheap, req ->
                ws.agents().purge(req.str("before"), req.str("mission"), req.bool("noRationale", false), req.bool("targetGone", false))));
        r.register(new Op("agent.review", "", true, Cost.cheap,
                req -> ws.agents().reviewTolerant(req.require("id"), req.require("action"), req.str("newName"))));   // newName: the reviewer's edit of a rename (2026-09-13)
        r.register(new Op("agent.follow", "", false, Cost.cheap,
                req -> ws.agents().follow(req.require("id"))));
        r.register(new Op("agent.unfollow", "", false, Cost.cheap,
                req -> ws.agents().unfollow()));

        // ---- §4.11 llm providers (multi-LLM: registry, write-only keys, configure)
        r.register(new Op("llm.providers", "", false, Cost.cheap,
                req -> ws.agents().llm().list()));
        r.register(new Op("llm.models", "", false, Cost.cheap,
                req -> ws.agents().discoverProviderModels(req.require("id"))));   // discover a provider's models from its endpoint (read-only; key used, never returned)
        r.register(new Op("llm.probe", "", true, Cost.scan,   // mutating=true on purpose: it SPENDS credit and spawns a process, so it is scope-gated (a mission actor gets E_SCOPE) and POST-only — not a state change (review A8)
                req -> dev.workbench.llm.LlmProbe.probe(ws.agents().llm(), ws.agents().llmKeys(), req.require("id"), req.str("model"))));   // WET test: one tiny completion — key valid? credit? replies? (key used, never returned)
        /* llm-config design (2026-09-11): configure goes through AgentManager so it
           serializes with a concurrent rename; `create:true` is the gate that stops a
           stale client from resurrecting a renamed-away id (§6.1) */
        r.register(new Op("llm.configure", "", true, Cost.cheap, req -> {
            /* the wire contract (reasoningField / reasoningReplay / streamOnly / maxOutputTokens) travels
               alone or with the rest: applied after the general configuration so one call can do both */
            boolean wireOnly = req.str("baseUrl") == null && req.str("model") == null && req.str("label") == null && req.params().get("models") == null
                    && req.params().get("local") == null && req.params().get("extraParams") == null && req.params().get("create") == null
                    && req.str("auth") == null && req.params().get("contextTokens") == null;
            Map<String, Object> out = wireOnly ? null : ws.agents().configureProvider(req.require("id"),
                        req.str("baseUrl"), req.str("model"), req.str("label"), modelsParam(req),
                        req.params().get("local") == null ? null : req.bool("local", false),
                        extraParamsParam(req),
                        req.params().get("create") == null ? null : req.bool("create", false),
                        req.str("auth"),   // how the provider connects (anthropic: "api" | "cli") — travels ALONE, never with models/model/baseUrl/local (llm-anthropic-vendor A1)
                        req.params().get("contextTokens") == null ? null : req.num("contextTokens", 0));   // the model's context window, the loop's only ceiling
            if (req.str("reasoningField") != null || req.str("reasoningReplay") != null || req.params().get("streamOnly") != null || req.params().get("maxOutputTokens") != null)
                out = ws.agents().configureProviderWire(req.require("id"), req.str("reasoningField"), req.str("reasoningReplay"),
                        req.params().get("streamOnly") == null ? null : req.bool("streamOnly", false),
                        req.params().get("maxOutputTokens") == null ? null : req.num("maxOutputTokens", 0));
            if (out == null) throw OpException.syntax("llm.configure: nothing to change — pass baseUrl, model, models, label, local, extraParams, auth, contextTokens, reasoningField, reasoningReplay, streamOnly or maxOutputTokens", "id=" + req.require("id"));
            return out;
        }));
        r.register(new Op("llm.rename", "", true, Cost.cheap,
                req -> ws.agents().renameProvider(req.require("id"), req.require("newId"))));   // keys + config + task pins, atomically or not at all (§6.2)
        r.register(new Op("llm.remove", "", true, Cost.cheap,
                req -> ws.agents().removeProvider(req.require("id"))));   // + the task pins naming it
        r.register(new Op("llm.setkey", "", true, Cost.cheap, req -> {
            /* the key must be PRESENT: "" clears (the dialog's CLEAR KEY), an
               absent param is a mistake, never a wipe (settings r1 H2) */
            if (!req.params().containsKey("key"))
                throw OpException.syntax("llm.setkey needs a key (\"\" clears the stored one)", "op=llm.setkey");
            return ws.agents().llmKeys().set(req.require("id"), req.str("key"));
        }));

        // ---- §4.12 layouts (named user layouts — WORKBENCH_SPEC §4 roadmap)
        r.register(new Op("layout.list", "", false, Cost.cheap,
                req -> ws.layouts().list()));
        r.register(new Op("layout.save", "", true, Cost.cheap, req -> {
            Object layout = req.params().get("layout");
            if (!(layout instanceof Map))
                throw OpException.syntax("layout.save needs a layout object", "");
            @SuppressWarnings("unchecked")
            Map<String, Object> l = (Map<String, Object>) layout;
            return ws.layouts().save(req.require("name"), l);
        }));
        r.register(new Op("layout.apply", "", false, Cost.cheap,
                req -> ws.layouts().get(req.require("name"))));
        r.register(new Op("layout.delete", "", true, Cost.cheap,
                req -> ws.layouts().delete(req.require("name"))));
        // ---- the operator's chrome (audit decision 17): theme, side/dock, splitters, dock height
        r.register(new Op("ui.prefs", "", false, Cost.cheap, req -> ws.uiPrefs().get()));
        r.register(new Op("ui.prefs.set", "", true, Cost.cheap, req -> {
            Object p = req.params().get("prefs");
            if (!(p instanceof Map)) throw OpException.syntax("ui.prefs.set needs a prefs object", "");
            @SuppressWarnings("unchecked")
            Map<String, Object> m = (Map<String, Object>) p;
            return ws.uiPrefs().set(m);
        }));
        r.register(new Op("ui.prefs.reset", "", true, Cost.cheap, req -> ws.uiPrefs().reset()));

        // ---- device ops (DEVICE_SPEC §ops — live adb catalog + pull-to-target)
        r.register(new Op("device.list", "", false, Cost.scan,
                req -> ws.devices().list(req.bool("probe", true))));   // probe:false = a poller (cached root or "?")
        r.register(new Op("device.catalog", "", false, Cost.scan,
                req -> ws.devices().catalog(req.require("serial"), req.bool("refresh", false),
                        req.str("kind"), req.str("filter"), req.num("offset", 0), req.num("count", 0))));
        r.register(new Op("device.browse", "", false, Cost.scan,
                req -> ws.devices().browse(req.require("serial"), req.require("path"))));
        r.register(new Op("device.pull", "", true, Cost.heavy, req -> {
            String serial = req.require("serial");
            String path = req.require("path");
            String baseName = new java.io.File(path).getName();
            /* the .jdb2 branch BEFORE the shared namespace guard (round-22 F1:
               the backstop fires first and the live-target message became dead
               code): refused unconditionally, but a LIVE target on the slot is
               named with the close hatch — never 'remove the existing file'
               for a database that is open right now, and never advice that
               leads into the twin-identity trap */
            if (baseName.toLowerCase(java.util.Locale.ROOT).endsWith(".jdb2")) {
                /* the slot's CANONICAL name for display (round-23 F4: the
                   caller's spelling printed a name no directory contains) */
                java.io.File slot;
                try {
                    slot = ws.devices().pulledFileFor(path).getCanonicalFile();
                } catch (java.io.IOException ioe) {
                    slot = ws.devices().pulledFileFor(path);
                }
                Map<String, Object> liveJdb2 = ws.liveTargetForJdb2Key(slot.getAbsolutePath());
                if (liveJdb2 != null)
                    throw OpException.busy(slot.getName() + " is open as " + liveJdb2.get("name")
                            + " (" + liveJdb2.get("id") + ") — close it before re-pulling",
                            "target=" + liveJdb2.get("id"));
                throw OpException.syntax("pulled/" + slot.getName() + " is the workbench's"
                        + " database slot — `adb pull` the device's .jdb2 by hand to a path"
                        + " outside pulled/ and open it with target.open", "jdb2=" + slot.getAbsolutePath());
            }
            /* the namespace refusal BEFORE the device round-trip (round-19 F8) */
            OpException collision = ws.devices().namespaceCollision(baseName);
            if (collision != null) throw collision;
            /* the short-circuit is SHA-GATED (review R1: the basename check
               answered vision-OCR for an eitaa request and 'ok' for a path that
               wasn't on the device): prove + hash the SOURCE first, and only
               identical destination bytes under a live target may dedupe. The
               live check is GLOBAL (review R7: a cross-project pull would
               overwrite bytes under a live engine and only then fail E_BUSY);
               the dedupe return stays project-scoped. */
            String srcSha = ws.devices().deviceSha256(serial, path);
            java.io.File dst = ws.devices().pulledFileFor(path);   // staging path — the refuse + fresh pull below
            String dp = dst.getAbsolutePath();
            int dot = dp.lastIndexOf('.');
            int slash = Math.max(dp.lastIndexOf('/'), dp.lastIndexOf(java.io.File.separatorChar));
            String jbase = dot > slash ? dp.substring(0, dot) : dp;

            /* decision 16 dedupe — keyed on the catalog's recorded {sha256, store path}, NOT the
               staging file (Step 3 reaps it). The catalog is the sha256→store index; the sha1 store
               key is never derived from the sha256. Skip the adb transfer only when the device bytes
               still match the recorded sha AND the adopted store artifact is on disk. */
            Map<String, Object> rec = ws.devices().catalogPullInfo(serial, path);
            String storeArt = null;
            if (rec != null && srcSha.equals(String.valueOf(rec.get("sha256"))) && rec.get("pulled") != null) {
                String cand = String.valueOf(rec.get("pulled"));
                if (cand.contains(java.io.File.separator + "targets" + java.io.File.separator)
                        && new java.io.File(cand).isFile()) storeArt = cand;
            }
            if (storeArt != null) {
                Map<String, Object> liveAny = ws.liveTargetForJdb2Key(storeArt);
                if (liveAny != null) {
                    /* already open on this store entry → NAVIGATE to it (another project) or activate
                       (same) — the 2026-09-08 directive, anchored on the store key now so it survives
                       the staging reap. navigateToTarget takes the whole gesture under one hold. */
                    boolean navigated = !Boolean.TRUE.equals(liveAny.get("currentProject"));
                    if (navigated) liveAny = ws.navigateToTarget(String.valueOf(liveAny.get("id")), "pull");
                    else if (!ws.activateTarget(String.valueOf(liveAny.get("id"))))
                        throw OpException.busy("the target's state just changed — retry the pull",
                                "target=" + liveAny.get("id"));
                    liveAny.put("deduped", true);
                    liveAny.remove("currentProject");   // caller's decision signal, never wire data (round-28 F5)
                    Map<String, Object> out = new LinkedHashMap<>();
                    out.put("file", storeArt);
                    out.put("sha256", srcSha);
                    out.put("skippedPull", true);
                    out.put("note", "already open as " + liveAny.get("name")
                            + (navigated ? " — switched to its project" : "")
                            + " — close the target to re-pull fresh bytes");
                    out.put("target", liveAny);
                    return out;
                }
                /* adopted before, closed → reopen from the store, skip the device transfer */
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("file", storeArt);
                out.put("sha256", srcSha);
                out.put("skippedPull", true);
                try {
                    Map<String, Object> target = ws.openTarget(storeArt, null, req.actor(), req.bool("rekey", false));
                    out.put("target", target);
                    out.put("note", "re-opened from the target store (bytes unchanged) — no device transfer");
                } catch (OpException e) {
                    out.put("opened", false);
                    out.put("openError", e.code + ": " + e.getMessage()
                            + (e.detail.isEmpty() ? "" : " (" + e.detail + ")"));
                } catch (Exception e) {
                    out.put("opened", false);
                    out.put("openError", "E_INTERNAL: " + e.getClass().getSimpleName() + ": " + e.getMessage());
                } catch (Error e) {
                    /* mirror the fresh-open path (round-18 F2 / round-22 F2): keep the
                       {file, sha256, skippedPull} payload instead of degrading to a dispatcher 500;
                       OutOfMemoryError still escapes (round-21 N3) */
                    if (e instanceof OutOfMemoryError) throw e;
                    out.put("opened", false);
                    out.put("openError", "E_INTERNAL: " + e.getClass().getSimpleName() + ": " + e.getMessage());
                }
                return out;
            }
            /* a target opened directly on the .jdb2 is invisible to the .apk
               destination check — refuse ONLY when the pull would invalidate
               under it, with the invalidation's own predicate: provSha decides,
               and NO record means "don't know" → refuse (round-17 F2/F3: the
               dst-bytes proxy was the evidence F1 rejected, and a sha-less
               record diverged the two mirrors) */
            String provSha = ws.devices().provShaFor(jbase);
            boolean wouldInvalidate = provSha == null || !srcSha.equals(provSha);
            if (wouldInvalidate) {
                /* keyed on the jdb2 KEY, not the literal sibling path (round-14
                   F3: pulling base.odex moved base.jdb2 from under a live
                   base.apk target — path-exact matching missed it) */
                Map<String, Object> liveJdb2 = ws.liveTargetForJdb2Key(jbase + ".jdb2");
                if (liveJdb2 != null)
                    throw OpException.busy(jbase + ".jdb2 is open as " + liveJdb2.get("name")
                            + " (" + liveJdb2.get("id") + ") — close it before re-pulling "
                            + dst.getName(), "target=" + liveJdb2.get("id"));
            }
            Map<String, Object> pulled = ws.devices().pull(serial, path, srcSha);
            /* the pull COMMITTED (bytes, invalidation, prov) — a throwing
               openTarget must not discard that report (round-18 F2: every
               un-recorded family now invalidates on first pull, and non-apk
               members are exactly what openTarget rejects — the whole report
               vanished into ok:false). The caller gets the full pull payload
               with the open failure attached */
            Map<String, Object> out = new LinkedHashMap<>(pulled);
            try {
                Map<String, Object> target = ws.openTarget(String.valueOf(pulled.get("file")), null, req.actor(), req.bool("rekey", false));
                out.put("target", target);
                /* decision 16: the target store is where the bytes now live — repoint the catalog
                   entry (pulled → store path, + durable storeKey) and report the store path as
                   `file`, so re-pull dedupe keys off the store and callers see the real home. */
                String tid = String.valueOf(target.get("id"));
                Object storePath = target.get("path");
                if (storePath != null) {
                    ws.devices().recordPulledStore(serial, path, ws.targetStoreKey(tid), String.valueOf(storePath));
                    out.put("file", String.valueOf(storePath));
                    /* decision 16 step 3: the store has adopted the bytes (and its prov was carried
                       in prepareStoreEntry) — reap the staging copy. ONLY on a successful open: an
                       openError keeps the pulled/ bytes so the report stays recoverable (INV-6). */
                    ws.devices().discardPulled(path);
                }
            } catch (OpException e) {
                out.put("opened", false);
                out.put("openError", e.code + ": " + e.getMessage()
                        + (e.detail.isEmpty() ? "" : " (" + e.detail + ")"));
            } catch (Exception e) {
                out.put("opened", false);
                out.put("openError", "E_INTERNAL: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            } catch (Error e) {
                /* Errors from Engines.openJeb (NoClassDefFoundError on an
                   engine-less build) escape Workspace BEFORE registration —
                   the report survives as E_INTERNAL (round-22 F2: narrowing
                   the catch to Exception re-opened the round-20 F9 loss).
                   OutOfMemoryError is the exception: JVM state unknown, it
                   escapes to the dispatcher instead (round-21 N3) */
                if (e instanceof OutOfMemoryError) throw e;
                out.put("opened", false);
                out.put("openError", "E_INTERNAL: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            }
            return out;
        }));
        r.register(new Op("device.annotate", "", true, Cost.cheap, req -> {
            if ("agent".equals(EventBus.actorKind(req.actor()))) {
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("serial", req.require("serial"));
                body.put("entryKind", req.str("kind"));
                body.put("name", req.require("name"));
                body.put("note", req.require("note"));
                return ws.agents().propose("catalog", body, req.actor());
            }
            return ws.devices().annotate(req.require("serial"), req.str("kind"),
                    req.require("name"), req.require("note"), req.actor());
        }));
        r.register(new Op("device.spawn-mission", "", true, Cost.heavy,
                req -> ws.agents().spawnCatalog(req.require("serial"), req.require("goal"))));
        r.register(new Op("device.services", "", false, Cost.scan,
                req -> ws.devices().services(req.require("serial"))));
        r.register(new Op("device.components", "", false, Cost.scan,
                req -> ws.devices().components(req.require("serial"), req.require("pkg"))));
        r.register(new Op("device.proc", "", false, Cost.scan,
                req -> ws.devices().proc(req.require("serial"), req.require("pid"))));

        // ---- §5 co-presence (who is looking at what)
        r.register(new Op("presence.ping", "", false, Cost.cheap,
                req -> ws.presence().ping(req.actor(), req.str("addr"), req.str("member"))));
        r.register(new Op("presence.whereis", "", false, Cost.cheap,
                req -> ws.presence().whereis(req.str("who", "agent"))));

        // ---- §5 misc
        r.register(new Op("op.cancel", "", true, Cost.cheap,
                req -> ws.cancelTask(req.require("ticket"), req.str("target"))));

        // ---- course integration (dev.workbench.course): present whether or not the switch is on —
        //      off, every op answers E_UNSUPPORTED with the switch's name, nothing else changes
        // Application-specific reference execution was retired by the local architecture.
        dev.workbench.course.CourseOps.register(r, ws);

        enableBatch(r, ws);   // rename / comment / tag.add also take items:[…]
        return r;
    }

    /**
     * Host memory (Σ): total/used from the OS bean when the JVM exposes it, plus this
     * JVM's own heap so the HEAP gauge has an honest denominator (WORKBENCH_XMX is a
     * launch flag the browser cannot see; Runtime.maxMemory is what it actually became).
     */
    private static Map<String, Object> sysmem() {
        Map<String, Object> m = new LinkedHashMap<>();
        java.lang.management.OperatingSystemMXBean os =
                java.lang.management.ManagementFactory.getOperatingSystemMXBean();
        if (os instanceof com.sun.management.OperatingSystemMXBean sun) {
            long total = sun.getTotalMemorySize();
            long free = sun.getFreeMemorySize();
            m.put("totalBytes", total);
            m.put("freeBytes", free);
            m.put("usedBytes", total - free);
        }
        Runtime rt = Runtime.getRuntime();
        long heapMax = rt.maxMemory();
        long heapUsed = rt.totalMemory() - rt.freeMemory();
        m.put("heapMaxBytes", heapMax);
        m.put("heapUsedBytes", heapUsed);
        m.put("heapCommittedBytes", rt.totalMemory());
        return m;
    }

    /**
     * One directory of the host filesystem, dirs first then files (case-insensitive).
     * Blank dir → the launch-time caller's cwd (-Dworkbench.cwd, then WORKBENCH_CWD
     * exported by workbench.sh before its internal cd; user.dir when neither is set —
     * ITs). The launcher's BROWSE pane starts there (2026-09-08: was user.home; the
     * tool's artifacts live under the project, and the pane's session last-dir memory
     * takes precedence before this default is ever reached). A default that has since
     * vanished or was spelled relative/~-style falls back to user.home — a dead launch
     * dir must not dead-end the pane (review 1d71d0f r1). Entry paths are absolute,
     * directly usable by target.open. Capped at 2000 entries with truncated:true.
     */
    private static Map<String, Object> fsList(String dir) throws OpException {
        if (dev.workbench.course.Course.on()) return fsListCourse(dir);
        String defDir = System.getProperty("workbench.cwd");
        if (defDir == null || defDir.isBlank()) defDir = System.getenv("WORKBENCH_CWD");
        if (defDir == null || defDir.isBlank()) defDir = System.getProperty("user.dir");
        File d = (dir == null || dir.isBlank())
                ? new File(Workspace.expandHome(defDir)).getAbsoluteFile()
                : new File(Workspace.expandHome(dir)).getAbsoluteFile();
        if ((dir == null || dir.isBlank()) && !d.isDirectory()) {
            File home = new File(System.getProperty("user.home"));
            if (home.isDirectory()) d = home;   // the launch cwd died under us — land somewhere real
        }
        if (!d.exists())
            throw OpException.notFound("no such directory: " + d.getPath(), "");
        if (!d.isDirectory())
            throw OpException.syntax("not a directory: " + d.getPath(),
                    "a FILE path goes straight into the launcher input");
        File[] kids = d.listFiles();
        if (kids == null)
            throw OpException.io("cannot list " + d.getPath(), "denied or vanished mid-list");
        Arrays.sort(kids, (a, b) -> a.isDirectory() != b.isDirectory()
                ? (a.isDirectory() ? -1 : 1)
                : a.getName().compareToIgnoreCase(b.getName()));
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("dir", d.getPath());
        File parent = d.getParentFile();
        m.put("parent", parent == null ? "" : parent.getPath());
        List<Object> entries = new ArrayList<>();
        for (File k : kids) {
            if (entries.size() >= 2000) { m.put("truncated", true); break; }
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("name", k.getName());
            e.put("path", k.getAbsolutePath());
            e.put("dir", k.isDirectory());
            e.put("size", k.isFile() ? k.length() : 0);
            entries.add(e);
        }
        m.put("entries", entries);
        return m;
    }

    /** fs.list in a course room: the room's own workspace and the approved read-only course roots, and
     *  nothing above or beside them (CourseFiles). No dir = the workspace; a root's parent is "" (no
     *  way up); an entry whose canonical path leaves the roots (a symlink out) is not listed, counted. */
    private static Map<String, Object> fsListCourse(String dir) throws OpException {
        File d = dev.workbench.course.CourseFiles.browsable(dir == null || dir.isBlank() ? dev.workbench.course.CourseFiles.workspace() : new File(Workspace.expandHome(dir)));
        if (!d.exists()) throw OpException.notFound("no such directory: " + d.getPath(), "");
        if (!d.isDirectory()) throw OpException.syntax("not a directory: " + d.getPath(), "a FILE path goes straight into the launcher input");
        File[] kids = d.listFiles();
        if (kids == null) throw OpException.io("cannot list " + d.getPath(), "denied or vanished mid-list");
        Arrays.sort(kids, (a, b) -> a.isDirectory() != b.isDirectory() ? (a.isDirectory() ? -1 : 1) : a.getName().compareToIgnoreCase(b.getName()));
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("dir", d.getPath());
        m.put("parent", dev.workbench.course.CourseFiles.isRoot(d) ? "" : d.getParentFile().getPath());
        List<Object> roots = new ArrayList<>();
        for (File r : dev.workbench.course.CourseFiles.browsableRoots()) roots.add(r.getPath());
        m.put("roots", roots);   // the places the browser may start from: the workspace first, then the course folders
        m.put("confined", true);
        List<Object> entries = new ArrayList<>();
        int hidden = 0;
        for (File k : kids) {
            if (entries.size() >= 2000) { m.put("truncated", true); break; }
            if (!dev.workbench.course.CourseFiles.listable(k)) { hidden++; continue; }
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("name", k.getName());
            e.put("path", k.getAbsolutePath());
            e.put("dir", k.isDirectory());
            e.put("size", k.isFile() ? k.length() : 0);
            entries.add(e);
        }
        if (hidden > 0) m.put("hidden", hidden);
        m.put("entries", entries);
        return m;
    }

    /** param.rename's expectCurrent: [{index, name}] — what the analyst saw when choosing (a stale
     *  preview must not rename a different argument); null when absent. */
    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> expectCurrent(Op.Request req) {
        if (!(req.params().get("expectCurrent") instanceof List<?> l)) return null;
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object o : l) if (o instanceof Map<?, ?> m) out.add((Map<String, Object>) m);
        return out;
    }

    /** The artifact's sha256, cached on path + mtime + size: a packet or a share asks for it per
     *  call, and a 130 MB apk is not re-read for each (fix-review 2026-10-07). */
    static String artifactSha256(File f) {
        return dev.workbench.server.Workspace.sha256Of(f);
    }

    private static Engine engine(Workspace ws, Op.Request req) throws OpException {
        return ws.engineFor(req.str("target"));
    }

    /** a numeric param that is absent (→ -1, "keep") or a number; a non-numeric
     *  value is E_SYNTAX, not a silent no-op (settings r2 L5) */
    /** A list result cut ONLY when the caller asked (limit > 0), and then visibly: {rows, total,
     *  truncated}. Without limit the plain list comes back whole. */
    /** The op's symbol, its resolution recorded for the envelope (meta.resolution) when lenient; the symbol itself
     *  is handed on unchanged — the engine resolves it the same way. A symbol the engine does not know falls through
     *  to the op's own error. */
    private static String noteResolution(Workspace ws, Op.Request req) throws OpException {
        String symbol = req.require("symbol");
        try { ws.resolveSymbol(ws.resolveTargetIdOrActive(req.str("target")), symbol); } catch (OpException ignored) {}
        return symbol;
    }

    /** The op's symbol under the corpus rule (Workspace.resolveForEvidence): a complete descriptor that contradicts
     *  the method found is refused here, before the engine runs — evidence packets, DEX listings, hooks. */
    private static String strictSymbol(Workspace ws, Op.Request req) throws OpException {
        String symbol = req.require("symbol");
        ws.resolveForEvidence(ws.resolveTargetIdOrActive(req.str("target")), symbol);
        return symbol;
    }

    /** symbol.locate: see the registration. Candidates are METHODS (string and api anchors name the referencing
     *  methods; a name anchor finds methods and classes by name); a class anchor contributes the classes it finds and
     *  marks candidates inside them; a prototype anchor only marks (it cannot search 100k classes). */
    private static Map<String, Object> locate(Workspace ws, Op.Request req) throws OpException {
        String tid = ws.resolveTargetIdOrActive(req.str("target"));
        dev.workbench.engine.Engine e = ws.engineFor(tid);
        Object an = req.params().get("anchors");
        if (!(an instanceof List<?> alist) || alist.isEmpty()) throw OpException.syntax("anchors: a list of {kind: string|api|class|name|prototype, value}", "anchors");
        if (alist.size() > 12) throw OpException.syntax("at most 12 anchors per lookup", "anchors=" + alist.size());
        int limit = Math.max(1, Math.min(50, req.num("limit", 10)));
        List<Map<String, Object>> anchors = new ArrayList<>();
        Map<String, Map<String, Object>> cands = new LinkedHashMap<>();   // key = method addr or class addr
        java.util.function.BiConsumer<String, Integer> hit = (addr, ai) -> {
            Map<String, Object> c = cands.computeIfAbsent(addr, k -> { Map<String, Object> m = new LinkedHashMap<>(); m.put("addr", k); m.put("matched", new ArrayList<Integer>()); return m; });
            @SuppressWarnings("unchecked") List<Integer> matched = (List<Integer>) c.get("matched");
            if (!matched.contains(ai)) matched.add(ai);
        };
        List<Integer> markers = new ArrayList<>();
        for (int i = 0; i < alist.size(); i++) {
            Object ao = alist.get(i);
            if (!(ao instanceof Map<?, ?> am) || am.get("value") == null) throw OpException.syntax("anchor " + i + " needs {kind, value}", "anchors[" + i + "]");
            String kind = am.get("kind") == null ? "string" : String.valueOf(am.get("kind")), value = String.valueOf(am.get("value")).trim();
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("i", i); row.put("kind", kind); row.put("value", value);
            try {
                switch (kind) {
                    case "string", "table" -> {
                        Map<String, Object> found = e.strings(value, null, false, 0, 50, null);
                        List<?> rows = found.get("rows") instanceof List<?> l ? l : List.of();
                        int refs = 0;
                        for (Object ro : rows) {
                            if (!(ro instanceof Map<?, ?> sr) || sr.get("id") == null) continue;
                            if (!String.valueOf(sr.get("value")).contains(value)) continue;
                            for (Object xo : e.stringXrefs(String.valueOf(sr.get("id")))) if (xo instanceof Map<?, ?> x && x.get("method") != null) { hit.accept(String.valueOf(x.get("method")), i); refs++; }
                        }
                        row.put("status", refs > 0 ? "located" : rows.isEmpty() ? "not_located" : "string present, no referencing method");
                        row.put("strings", rows.size()); row.put("references", refs);
                        if (Boolean.TRUE.equals(found.get("truncated"))) row.put("truncated", true);
                        row.put("searched", "strings {filter} (substring, first 50) → string.xrefs for each");
                    }
                    case "api", "call" -> {
                        int refs = 0;
                        for (Object xo : e.xrefsTo(value)) if (xo instanceof Map<?, ?> x && x.get("method") != null) { hit.accept(String.valueOf(x.get("method")), i); refs++; }
                        row.put("status", refs > 0 ? "located" : "not_located"); row.put("references", refs);
                        row.put("searched", "xref.to {symbol} (callers of the API/method; the symbol must resolve — a lenient resolution is said in meta.resolution)");
                    }
                    case "class" -> {
                        Map<String, Object> page = e.symbolSearchPage(value, 20, "class");
                        List<?> rows = page.get("rows") instanceof List<?> l ? l : List.of();
                        for (Object ro : rows) if (ro instanceof Map<?, ?> sr && sr.get("addr") != null) {
                            String caddr = String.valueOf(sr.get("addr"));
                            hit.accept(caddr, i);
                            cands.get(caddr).put("kind", "class");
                        }
                        row.put("status", rows.isEmpty() ? "not_located" : "located"); row.put("classes", rows.size());
                        if (Boolean.TRUE.equals(page.get("truncated"))) row.put("truncated", true);
                        row.put("searched", "symbol.search {query, kind: class} (name substring; an obfuscated name may differ across builds)");
                        markers.add(i);
                    }
                    case "name", "method" -> {
                        Map<String, Object> page = e.symbolSearchPage(value, 30, "method");
                        List<?> rows = page.get("rows") instanceof List<?> l ? l : List.of();
                        for (Object ro : rows) if (ro instanceof Map<?, ?> sr && sr.get("addr") != null) hit.accept(String.valueOf(sr.get("addr")), i);
                        row.put("status", rows.isEmpty() ? "not_located" : "located"); row.put("methods", rows.size());
                        if (Boolean.TRUE.equals(page.get("truncated"))) row.put("truncated", true);
                        row.put("searched", "symbol.search {query, kind: method}");
                    }
                    case "prototype" -> {
                        row.put("status", "marker"); row.put("searched", "not a search: marks candidates whose parameter list equals the value (e.g. (Ljava/io/File;ZJJ))");
                        markers.add(i);
                    }
                    default -> throw OpException.syntax("anchor kind must be string|table|api|class|name|prototype: " + kind, "anchors[" + i + "]");
                }
            } catch (OpException ex) {
                row.put("status", "error"); row.put("error", ex.getMessage());
            }
            anchors.add(row);
        }
        /* the markers: a class anchor marks candidates INSIDE the classes it found; a prototype anchor marks by shape */
        for (int ai : markers) {
            Map<?, ?> a = (Map<?, ?>) alist.get(ai);
            String kind = a.get("kind") == null ? "string" : String.valueOf(a.get("kind")), value = String.valueOf(a.get("value")).trim();
            for (Map.Entry<String, Map<String, Object>> ce : new ArrayList<>(cands.entrySet())) {
                String addr = ce.getKey();
                if ("class".equals(kind)) {
                    if (addr.contains("->")) {
                        String owner = addr.substring(0, addr.indexOf("->"));
                        for (Map.Entry<String, Map<String, Object>> cl : cands.entrySet())
                            if ("class".equals(cl.getValue().get("kind")) && cl.getKey().equals(owner)) hit.accept(addr, ai);
                        if (owner.contains(value)) hit.accept(addr, ai);
                    }
                } else if ("prototype".equals(kind) && addr.contains("(")) {
                    String params = addr.substring(addr.indexOf('('), addr.indexOf(')') + 1);
                    if (params.equals(value) || params.equals("(" + value.replaceAll("^\\(|\\)$", "") + ")")) hit.accept(addr, ai);
                }
            }
        }
        /* rank: anchors matched (more is better), then methods before classes */
        List<Map<String, Object>> ranked = new ArrayList<>();
        for (Map<String, Object> c : cands.values()) {
            @SuppressWarnings("unchecked") List<Integer> matched = (List<Integer>) c.get("matched");
            List<Integer> unmatched = new ArrayList<>();
            for (int i = 0; i < alist.size(); i++) if (!matched.contains(i)) unmatched.add(i);
            c.put("unmatched", unmatched); c.put("score", matched.size());
            c.putIfAbsent("kind", String.valueOf(c.get("addr")).contains("->") ? "method" : "class");
            try {
                Map<String, Object> rs = e.resolve(String.valueOf(c.get("addr")));
                if (rs.get("origSig") != null) c.put("origSig", rs.get("origSig"));
                if (rs.get("name") != null) c.put("name", rs.get("name"));
                if (rs.get("owner") != null) c.put("owner", rs.get("owner")); else if (rs.get("fqcn") != null) c.put("owner", rs.get("fqcn"));
            } catch (OpException ignored) {}
            ranked.add(c);
        }
        ranked.sort((x, y) -> {
            int d = Integer.compare((Integer) y.get("score"), (Integer) x.get("score"));
            if (d != 0) return d;
            d = Boolean.compare("class".equals(x.get("kind")), "class".equals(y.get("kind")));
            return d != 0 ? d : String.valueOf(x.get("addr")).compareTo(String.valueOf(y.get("addr")));
        });
        int searching = 0; for (Map<String, Object> a : anchors) if (!"marker".equals(a.get("status"))) searching++;
        String status;
        if (ranked.isEmpty()) status = searching == 0 ? "no searching anchor (string, api, class or name) — nothing was looked up" : "not_located";
        else {
            int best = (Integer) ranked.get(0).get("score");
            long atBest = ranked.stream().filter(c -> (Integer) c.get("score") == best).count();
            status = best == alist.size() ? (atBest == 1 ? "located" : "ambiguous") : "partial";
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("schema", "anchor-locate-v1");
        out.put("target", tid);
        out.put("status", status);
        out.put("anchors", anchors);
        out.put("candidates", ranked.size() > limit ? new ArrayList<>(ranked.subList(0, limit)) : ranked);
        out.put("total", ranked.size()); out.put("truncated", ranked.size() > limit);
        out.put("note", "anchors are fallible across builds (strings move or vanish, names re-obfuscate, APIs get wrapped): `located` = one candidate matched every anchor by THESE queries; `ambiguous` = several did; `partial` = the best matched some; `not_located` = none did — never \"absent\". Anchor presence here is not structural correspondence, not a connected static path and not a runtime observation: record those separately, with the searched scope (each anchor's `searched`).");
        return out;
    }

    private static Object limited(List<Object> rows, Op.Request req) {
        int limit = req.num("limit", 0);
        if (limit <= 0) return rows;
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("rows", rows.size() > limit ? new ArrayList<>(rows.subList(0, limit)) : rows);
        out.put("total", rows.size());
        out.put("truncated", rows.size() > limit);
        return out;
    }

    private static int numOrAbsent(Op.Request req, String key) throws OpException {
        Object v = req.params().get(key);
        if (v == null || (v instanceof String s && s.isBlank())) return -1;
        if (v instanceof Number n) return n.intValue();
        try { return Integer.parseInt(String.valueOf(v).trim()); }
        catch (NumberFormatException e) { throw OpException.syntax(key + " must be a number (0 = default)", key + "=" + v); }
    }

    /** llm.configure's `extraParams`: a JSON object merged into every chat request; null when absent */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> extraParamsParam(Op.Request req) {
        Object v = req.params().get("extraParams");
        return v instanceof Map ? (Map<String, Object>) v : null;
    }

    /** llm.configure's `models`: a JSON list or a comma-separated string; null when absent */
    private static List<String> modelsParam(Op.Request req) {
        Object v = req.params().get("models");
        if (v == null) return null;
        List<String> out = new ArrayList<>();
        if (v instanceof List<?> l) {
            for (Object o : l) { String s = String.valueOf(o).trim(); if (!s.isEmpty()) out.add(s); }
        } else {
            for (String s : String.valueOf(v).split(",")) { String t = s.trim(); if (!t.isEmpty()) out.add(t); }
        }
        return out;
    }

    /** Display owner for a history row: the symbol's owner/fqcn, else the class part of its addr. */
    @SuppressWarnings("unchecked")
    private static String historyOwner(Map<String, Object> res) {
        Object sym = res.get("symbol");
        if (sym instanceof Map) {
            Map<String, Object> s = (Map<String, Object>) sym;
            Object owner = s.get("owner");
            if (owner != null) return String.valueOf(owner);
            Object fqcn = s.get("fqcn");
            if (fqcn != null) return String.valueOf(fqcn);
        }
        String addr = String.valueOf(res.get("addr"));
        int arrow = addr.indexOf("->");
        String cls = arrow < 0 ? addr : addr.substring(0, arrow);
        if (cls.startsWith("L") && cls.endsWith(";"))
            cls = cls.substring(1, cls.length() - 1).replace('/', '.').replace('$', '.');
        return cls;
    }

    /** Registered name that answers E_UNSUPPORTED with a "mock" note (parity-test visible). */
    private static Op mockStub(String name, boolean mutating) {
        return mockStub(name, mutating, "");
    }

    private static Op mockStub(String name, boolean mutating, String capability) {
        return new Op(name, capability, mutating, Cost.cheap,
                req -> { throw OpException.unsupported(
                        "op '" + name + "' is not implemented in the M0a mock engine",
                        "mock"); });
    }

    /** Ops that exist for the browser UI alone — chrome prefs and layouts, the saved view
     *  position, the launcher's file browser, the bridge's own catalogue ops, hover/class-info
     *  panels, the doctor, the provider probe. Never advertised over MCP, in any catalogue
     *  (John, 2026-09-18: "remove the 22 UI plumbing calls completely"). They stay HTTP ops. */
    public static final java.util.Set<String> UI_ONLY = java.util.Set.of(
            "ui.open", "ui.prefs", "ui.prefs.reset", "ui.prefs.set",
            "layout.apply", "layout.delete", "layout.list", "layout.save",
            "target.view", "target.view.set", "target.recent", "fs.list", "workspace.sysmem",
            "ops.list", "ops.tools", "caps", "code.hover", "code.classinfo", "op.cancel",
            "system.doctor", "llm.probe", "rename.revert", "system.logs", "log.client");

    /** The write ops' batch form (JEB's rename_code_items shape; 2026-09-18): rename, comment and
     *  tag.add accept items:[{…}] — each item is the op's single-form params — and answer
     *  {applied, pending, failed, results[]}, compact for successes, the reason for failures. A
     *  deep mission files a class's names in one call instead of one model turn per name.
     *  The write-scope is checked PER ITEM here: the dispatcher's one check sees only the outer
     *  params (AgentManager.checkScope lets an items call through for exactly these ops). */
    public static final java.util.Set<String> BATCH_OPS = java.util.Set.of("rename", "comment", "tag.add");

    public static void enableBatch(Registry r, Workspace ws) {
        for (String name : BATCH_OPS) {
            Op one = r.get(name);
            if (one == null) continue;
            r.replace(new Op(one.name(), one.capability(), one.mutating(), one.cost(), req -> {
                Object items = req.params().get("items");
                if (!(items instanceof List<?> l)) return one.handler().handle(req);
                if (l.isEmpty() || l.size() > 50)
                    throw OpException.syntax("items must hold 1 to 50 entries", "items=" + l.size());
                int applied = 0, pending = 0, failed = 0;
                List<Object> results = new ArrayList<>();
                for (Object o : l) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    if (!(o instanceof Map<?, ?> im)) { failed++; row.put("error", "an item must be an object"); results.add(row); continue; }
                    Map<String, Object> ip = new LinkedHashMap<>();
                    for (Map.Entry<?, ?> e : im.entrySet()) ip.put(String.valueOf(e.getKey()), e.getValue());
                    if (ip.get("target") == null && req.params().get("target") != null) ip.put("target", req.params().get("target"));
                    ip.remove("items");
                    row.put("symbol", ip.get("symbol"));
                    try {
                        ws.agents().checkScope(req.actor(), name, ip);
                        Object res = one.handler().handle(new Op.Request(name, ip, req.actor()));
                        boolean isApplied = res instanceof Map<?, ?> rm && Boolean.TRUE.equals(rm.get("applied"));
                        if (isApplied) { applied++; row.put("status", "applied"); }
                        else { pending++; row.put("status", "pending"); }
                        if (res instanceof Map<?, ?> rm2 && rm2.get("id") != null) row.put("id", rm2.get("id"));
                    } catch (OpException e) {
                        failed++;
                        row.put("status", "failed");
                        row.put("error", e.getMessage());
                    }
                    results.add(row);
                }
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("applied", applied);
                out.put("pending", pending);
                out.put("failed", failed);
                out.put("results", results);
                return out;
            }));
        }
    }
}
