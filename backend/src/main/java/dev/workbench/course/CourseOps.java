package dev.workbench.course;

import dev.workbench.registry.Json;
import dev.workbench.registry.Op;
import dev.workbench.registry.Op.Cost;
import dev.workbench.registry.OpException;
import dev.workbench.registry.Registry;
import dev.workbench.server.EventBus;
import dev.workbench.server.Workspace;
import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The course ops (course integration P0.3–P0.6, P1; A10 contract 2026-10-09). Registered always;
 * off the switch each one answers E_UNSUPPORTED naming WORKBENCH_COURSE. HTTP ops for the room's
 * own UI and MCP bridge — none is an MCP tool of the facade, and none advertises more than the
 * server offers:
 *
 *   course.status                  the health contract (CourseRuntime.status): access, analysis, model
 *   course.manifest                the course target manifest and the open targets' resolution
 *   course.activity                the classroom's current activity identity — READ-ONLY: the
 *                                  classroom owns it; a student's label is a claim, not authority
 *   course.mission {goal?, symbol?, question?}
 *                                  the Day 1 preset: the course provider/model, bounded turns,
 *                                  output and budget, pinned to the open target, refused on a
 *                                  manifest mismatch; records the initial snapshot
 *   course.mission.report {id}     snapshot, final annotation diff, receipts, stop reason, capture
 *   course.reset {target}          the experimental EDIT reset: every history entry since a snapshot
 *                                  undone (renames, comments, locals); says what it does NOT reset —
 *                                  it is not an account reset (that is the operator's lifecycle)
 *   course.share {…}               compose a finding with its provenance and hand it to the
 *                                  classroom's own Findings page (preview, then the handoff); a room
 *                                  credential never publishes as the student; a private draft event
 *   course.help / course.progress / course.thread
 *                                  the student's classroom actions: the honest browser handoff
 *                                  {sent:false, browser, copy, reason: human_action_required} —
 *                                  no room-facing write endpoint exists, and none is implied
 *   course.events {mission}        the private capture's journal of one run
 *   course.receipt                 the cleanup receipt (what this room holds that a reset must keep)
 *   course.export.reconstruction {symbol, scope?, familyId?}
 *                                  the P1 source-reconstruction export seam
 *
 * In a room the ops that reach the HOST (the laptop's phones, adb, the device bridge) are replaced
 * by a refusal that says so: the room runs on the class server, not on the student's machine.
 */
public final class CourseOps {

    private CourseOps() {}

    /** The classroom's public-text bound per finding (title + summary + body; classroom-contract: 20 KB). */
    static final int BOARD_TEXT_BYTES = 20_000;

    /** Ops that control the machine the process runs on — a student's phone over adb, the device
     *  bridge: meaningful on the owner's laptop, a lie on the class server. */
    static final List<String> HOST_ONLY = List.of("device.list", "device.catalog", "device.browse", "device.pull", "device.annotate",
            "device.spawn-mission", "device.services", "device.components", "device.proc",
            "runs.spawn", "runs.attach", "runs.detach", "runs.bp.add", "runs.bp.rm", "runs.bp.toggle");   // Frida on a phone over adb: the laptop's, never the room's

    /** The classroom's Findings form: its card types and its fields (the classroom page, read 2026-10-09). */
    static final List<String> FINDING_KINDS = List.of("finding", "tool", "comparison", "workflow", "image");

    /** An exported artifact's bound (the classroom's submission blobs are bounded; a model never round-trips this). */
    static final int ARTIFACT_MAX_BYTES = 8 << 20;
    /** All of one room's exports together (content and records): eight artifacts of the largest size. */
    static final long EXPORT_QUOTA_BYTES = 64L << 20;

    /** tmp beside the file, fsync, atomic rename (replacing a half-written predecessor), the tmp gone on any failure. */
    private static void writeAtomic(File f, byte[] bytes) throws java.io.IOException {
        File tmp = new File(f.getParentFile(), f.getName() + ".tmp-" + Thread.currentThread().getId());
        try {
            try (java.io.FileOutputStream out = new java.io.FileOutputStream(tmp)) { out.write(bytes); out.getFD().sync(); }
            java.nio.file.Files.move(tmp.toPath(), f.toPath(), java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } finally { if (tmp.exists()) tmp.delete(); }
    }

    /** The directory entry made durable too (an acknowledged artifact survives a crash); a filesystem
     *  that refuses a directory fsync is reported by Capture's probe, not here. */
    private static void fsyncDir(File dir) {
        try (java.nio.channels.FileChannel ch = java.nio.channels.FileChannel.open(dir.toPath(), java.nio.file.StandardOpenOption.READ)) { ch.force(true); }
        catch (java.io.IOException ignored) {}
    }

    private static CourseRuntime rt() throws OpException {
        CourseRuntime rt = CourseRuntime.current();
        if (rt == null) throw OpException.unsupported("course integration is off — this is a standalone workbench (WORKBENCH_COURSE unset)", "course=off");
        return rt;
    }

    /** The student's own actions (share, reset, help, progress): a browser session the class server
     *  bound — never a machine client, whatever actor it names (the MCP bridge or a script on the
     *  bearer path says human:<name> for attribution, not for consent), and never an agent. */
    private static void humanOnly(Op.Request req, String what) throws OpException {
        CourseIdentity.Principal p = CourseIdentity.CURRENT.get();
        if (p != null && p.machine())
            throw OpException.scope(what + " is the student's own action, taken from the room's page — a machine client (the MCP bridge, a script) cannot take it for them", "principal=machine");
        if (!"human".equals(EventBus.actorKind(req.actor())))
            throw OpException.scope(what + " is the student's own action — an agent does not " + what.replaceAll("^course\\.", ""), "actor=" + req.actor());
    }

    public static void register(Registry r, Workspace ws) {
        if (Course.on()) {
            // General device APIs do not speak the restricted phone extension contract. In rooms
            // they stay closed; Message Lab is the explicitly authorized Runs capability.
            if (r.has("runs.devices")) r.replace(new Op("runs.devices", "", false, Cost.cheap,
                    req -> Map.of("devices", List.of(), "note", "Use Runs → Message Lab for this room's authorized phone binding")));
            /* the host-side ops are not hidden (a client that asks learns why) — they refuse */
            for (String name : HOST_ONLY) if (r.has(name)) {
                Op old = r.get(name);
                r.replace(new Op(name, old.capability(), old.mutating(), old.cost(), req -> {
                    throw OpException.unsupported(name + " is not part of the room's bounded phone capability. Use Runs → Message Lab after the instructor releases and enrolls this room's phone", "course=host-only");
                }));
            }
            /* a model key never enters a room: the room has no egress, and the server holds the key
               of the student's own route (enrolled on the classroom's Provider page) */
            if (r.has("llm.setkey")) {
                Op old = r.get("llm.setkey");
                r.replace(new Op("llm.setkey", old.capability(), old.mutating(), old.cost(), req -> {
                    throw OpException.unsupported("a model key is enrolled on the classroom's Provider page (Today → Provider), never typed into the room: the room has no egress of its own and the class server holds the key for your route", "course=no-keys");
                }));
            }
        }
        r.register(new Op("course.status", "", false, Cost.scan, req -> {
            CourseRuntime rt = CourseRuntime.current();
            if (rt == null) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("state", "off"); m.put("summary", "standalone workbench — course integration is not configured (WORKBENCH_COURSE)");
                return m;
            }
            return rt.status();
        }));
        r.register(new Op("course.manifest", "", false, Cost.cheap, req -> {
            CourseRuntime rt = rt();
            rt.manifest().reload();
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("file", rt.manifest().file() == null ? null : rt.manifest().file().getPath());
            m.put("present", rt.manifest().present());
            if (rt.manifest().error() != null) m.put("error", rt.manifest().error());
            m.put("target_id", rt.manifest().targetId());
            m.put("label", rt.manifest().label());
            m.put("sha256", rt.manifest().manifestSha256());
            List<Object> arts = new ArrayList<>();
            for (CourseManifest.Artifact a : rt.manifest().artifacts()) arts.add(Map.of("name", String.valueOf(a.name()), "sha256", a.sha256(), "size", a.size()));
            m.put("artifacts", arts);
            List<Object> open = new ArrayList<>();
            for (String tid : ws.openTargetIds()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("target", tid); row.put("name", ws.targetNameOf(tid));
                row.put("resolution", rt.manifest().resolveDigest(ws.executableSha(tid), ws.targetNameOf(tid)));
                open.add(row);
            }
            m.put("openTargets", open);
            return m;
        }));
        r.register(new Op("course.activity", "", false, Cost.cheap, req -> {
            CourseRuntime rt = rt();
            if (req.str("id") != null) {
                CourseActivity.Identity cur = rt.activity().current();
                throw OpException.scope("The classroom sets the activity (the instructor moves the class on from Today); Jeeves cannot change it. Current: "
                        + (cur.id() == null ? "none revealed yet" : cur.id() + (cur.revision() == null ? "" : ", revision " + cur.revision())) + " — your work is filed under it",
                        "activity=read-only");
            }
            if (req.bool("refresh", false)) rt.activity().refresh();
            Map<String, Object> m = new LinkedHashMap<>(rt.activity().describe());
            m.put("note", "read-only: the classroom's revealed activity, with the revision and digest of its definition — what every event of this room carries; `stale` says the classroom has not confirmed it for a minute");
            return m;
        }));

        // ---- the Day 1 mission preset (P0.5)
        r.register(new Op("course.mission", "", true, Cost.heavy, req -> {
            CourseRuntime rt = rt();
            String model = Course.presetModel();
            if (model.isBlank()) throw OpException.unsupported("the Day 1 preset has no provider — WORKBENCH_COURSE_PROVIDER names provider:model (the class gateway, or a student's own route)", "preset=unconfigured");
            String tid = ws.resolveTargetIdOrActive(req.str("target"));
            if (tid == null) throw OpException.syntax("course.mission needs an open target", "");
            File art = ws.targetArtifactFile(tid);
            Map<String, Object> res = rt.manifest().resolveDigest(ws.executableSha(tid), ws.targetNameOf(tid));
            if ("mismatch".equals(res.get("match")))
                throw OpException.scope("comparison run refused: " + res.get("reason") + " — open the course artifact", "target-mismatch");
            String symbol = req.str("symbol");
            String question = req.str("question");
            String goal = req.str("goal");
            if (goal == null || goal.isBlank()) {
                if (symbol == null || symbol.isBlank()) throw OpException.syntax("course.mission needs a goal or a symbol (the method to explain)", "");
                /* the course's own brief (slide 067): roles, guards, local effects, cited references,
                   what needs another caller or callee, provisional names kept visible */
                goal = "reverse method " + symbol.trim() + " — explain this selected method: identify roles, guards and local effects;"
                        + " cite the supplied original references; state what requires another caller or callee; keep speculative names"
                        + " visibly provisional" + (question == null || question.isBlank() ? "" : ". Question: " + question.trim());
            }
            int revision = ws.historyRevision(tid);
            Map<String, Object> spawned = ws.agents().spawn(goal, null, model, Course.presetBudget(), null, "quick", tid, null, Course.presetTurns(), Course.presetOutput());
            String mid = String.valueOf(spawned.get("id"));
            Map<String, Object> snap = new LinkedHashMap<>();
            snap.put("mission", mid);
            snap.put("target", tid); snap.put("targetName", ws.targetNameOf(tid));
            snap.put("artifactSha256", ws.executableSha(tid));   // the executable's measured digest, never the file beside a database
            snap.put("executable", ws.executableIdentity(tid));
            snap.put("manifestSha256", rt.manifest().manifestSha256());
            snap.put("manifestResolution", res);
            snap.put("historyRevision", revision);
            snap.put("snapshot_id", tid + "@h" + revision);
            snap.put("activity", rt.activity().current().describe());
            snap.put("owner", rt.ownerFields());
            snap.put("actor", req.actor());
            snap.put("preset", Map.of("model", model, "turns", Course.presetTurns(), "maxOutput", Course.presetOutput(), "budget", Course.presetBudget(), "depth", "quick"));
            snap.put("question", question == null ? "" : question);
            snap.put("symbol", symbol == null ? "" : symbol);
            snap.put("startedAt", java.time.Instant.now().toString());
            rt.snapshot(mid, snap);
            Map<String, Object> out = new LinkedHashMap<>(spawned);
            out.put("snapshot", snap);
            if ("unknown".equals(res.get("match"))) out.put("warning", "the open target's bytes could not be checked against the course manifest (" + res.get("reason") + ") — this run's events carry no artifact digest");
            out.put("note", "the Day 1 preset: " + model + ", " + Course.presetTurns() + " turns, " + Course.presetOutput() + " output tokens per answer, "
                    + Course.presetBudget() + " TOKEN budget (the workbench's ceiling, not provider credit); names and comments the agent files apply at once as hypotheses (provenance shown) and revert through agent.revert / course.reset");
            return out;
        }));
        r.register(new Op("course.mission.report", "", false, Cost.scan, req -> {
            CourseRuntime rt = rt();
            String mid = req.require("id");
            Map<String, Object> mission = null;
            for (Object o : ws.agents().missionList()) if (o instanceof Map<?, ?> row && mid.equals(row.get("id"))) { mission = new LinkedHashMap<>(); for (Map.Entry<?, ?> e : row.entrySet()) mission.put(String.valueOf(e.getKey()), e.getValue()); }
            if (mission == null) throw OpException.notFound("no such mission: " + mid, "");
            Map<String, Object> snap = rt.snapshot(mid);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("mission", mission);
            out.put("snapshot", snap);
            String tid = mission.get("target") == null ? null : String.valueOf(mission.get("target"));
            int from = snap != null && snap.get("historyRevision") instanceof Number n ? n.intValue() : 0;
            List<Map<String, Object>> diff = tid == null ? List.of() : ws.historySince(tid, from);
            List<Object> byMission = new ArrayList<>(), byOthers = new ArrayList<>();
            /* the entry's actor is "agent:agent-1 auto-applied" (or "llm:agent-1"): the agent id, exactly —
               agent-1 is not agent-10 */
            String agentId = String.valueOf(mission.get("agentId"));
            for (Map<String, Object> e : diff) {
                Map<String, Object> row = new LinkedHashMap<>();
                /* a parameter mapping (kind parameters) travels with its per-index changes and its provenance
                   (source, argumentMapping): the export says WHICH retained name went WHERE and that an analyst
                   chose it, not just the joined before/after names */
                for (String k : List.of("id", "op", "kind", "addr", "newAddr", "oldName", "newName", "newText", "actor", "ts", "undone", "confidence", "changes", "source", "argumentMapping")) if (e.get(k) != null) row.put(k, e.get(k));
                String a = String.valueOf(e.get("actor")).replaceAll("[\\s(].*$", "");
                String aid = a.indexOf(':') >= 0 ? a.substring(a.indexOf(':') + 1) : a;
                if (agentId.equals(aid)) byMission.add(row); else byOthers.add(row);
            }
            out.put("annotationDiff", Map.of("sinceRevision", from, "byMission", byMission, "byOthers", byOthers, "note", "every entry is an analyst hypothesis with its actor; undone:true = reverted"));
            List<Map<String, Object>> events = rt.capture().events(mid);
            int requests = 0, responses = 0, failures = 0, tools = 0;
            List<Object> receipts = new ArrayList<>();
            for (Map<String, Object> ev : events) {
                String t = String.valueOf(ev.get("event_type"));
                if (t.equals("model.request")) requests++;
                else if (t.equals("model.response")) { responses++; Map<String, Object> rc = new LinkedHashMap<>(); for (String k : List.of("request_id", "attempt", "model_requested", "model_returned", "providerRequestId", "replay", "usage", "ms", "exported")) if (ev.get(k) != null) rc.put(k, ev.get(k)); receipts.add(rc); }
                else if (t.equals("model.failure")) failures++;
                else if (t.equals("tool.result")) tools++;
            }
            out.put("capture", Map.of("events", events.size(), "requests", requests, "responses", responses, "failures", failures, "toolResults", tools, "receipts", receipts, "mode", rt.capture().mode(), "run_id", rt.capture().runIdOf(mid)));
            Object stop = mission.get("stopReason") != null ? mission.get("stopReason") : mission.get("reason");
            out.put("stopReason", stop);
            out.put("displayState", mission.get("displayState"));
            /* one line for the student: what happened, why it stopped, what it changed */
            String why = String.valueOf(stop);
            out.put("summary", mission.get("displayState") + " after " + mission.get("turnsDone") + " turn(s)" + (stop == null ? "" : " — " + (why.startsWith("capture refused") ? "not dispatched: the class store did not admit the request; run it again once the store answers" : why))
                    + "; " + byMission.size() + " name/comment change(s) by this run since the snapshot" + (byOthers.isEmpty() ? "" : ", " + byOthers.size() + " by others"));
            return out;
        }));
        r.register(new Op("course.reset", "", true, Cost.heavy, req -> {
            CourseRuntime rt = rt();
            humanOnly(req, "course.reset");
            String tid = ws.resolveTargetIdOrActive(req.str("target"));
            int to = req.num("revision", -1);
            List<String> runs = new ArrayList<>();
            if (to < 0) {
                /* no revision named: the FIRST Day 1 snapshot taken on this target — the clean baseline;
                   a later snapshot already holds the earlier runs' names, and a comparison that keeps
                   them is not a comparison */
                for (Object o : ws.agents().missionList()) if (o instanceof Map<?, ?> row && tid.equals(row.get("target"))) {
                    Map<String, Object> s = rt.snapshot(String.valueOf(row.get("id")));
                    if (s != null && s.get("historyRevision") instanceof Number n) { runs.add(String.valueOf(row.get("id"))); to = to < 0 ? n.intValue() : Math.min(to, n.intValue()); }
                }
                if (to < 0) throw OpException.syntax("course.reset needs a revision (the snapshot to go back to) — none of this target's course missions recorded one", "target=" + tid);
            }
            /* a mission still working on this target would write over the restored baseline: it is
               stopped and named — the student asked for the reset, no confirmation step */
            List<Object> stopped = new ArrayList<>(), failed = new ArrayList<>();
            for (Object o : ws.agents().missionList()) if (o instanceof Map<?, ?> row && tid.equals(row.get("target")) && List.of("running", "paused", "queued").contains(String.valueOf(row.get("state")))) {
                try { ws.agents().stop(String.valueOf(row.get("id"))); stopped.add(row.get("id")); }
                catch (Exception ex) { failed.add(Map.of("mission", String.valueOf(row.get("id")), "reason", "still running: " + ex.getMessage())); }
            }
            List<Map<String, Object>> since = ws.historySince(tid, to);
            int undone = 0; List<Object> entries = new ArrayList<>();
            for (int i = since.size() - 1; i >= 0; i--) {
                Map<String, Object> e = since.get(i);
                if (Boolean.TRUE.equals(e.get("undone"))) continue;
                try {
                    ws.historyUndo(tid, String.valueOf(e.get("id"))); undone++;
                    if (entries.size() < 200) { Map<String, Object> row = new LinkedHashMap<>(); for (String k : List.of("id", "op", "kind", "addr", "oldName", "newName", "actor")) if (e.get(k) != null) row.put(k, e.get(k)); entries.add(row); }
                } catch (Exception ex) { failed.add(Map.of("id", String.valueOf(e.get("id")), "reason", String.valueOf(ex.getMessage()))); }
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("target", tid); out.put("toRevision", to);
            out.put("baseline", runs.isEmpty() ? "revision " + to + " (named by the caller)" : "the first course snapshot on this target — before run " + runs.get(0) + (runs.size() > 1 ? " (covers " + runs + ")" : ""));
            out.put("stopped", stopped);
            out.put("undone", undone); out.put("entries", entries); if (undone > entries.size()) out.put("entriesTruncated", undone - entries.size());
            out.put("failed", failed);
            out.put("resets", List.of("renames of classes, methods, fields and locals", "comments"));
            out.put("doesNotReset", List.of("tags and tag families", "map nodes, edges and verdicts", "bookmarks and flows", "decompilations cached in the database (redone on demand; a saved database keeps them)",
                    "the agent's own context (a new mission starts fresh anyway)", "anything edited outside the workbench",
                    "the capture (conversations, trajectories, drafts): never — a reset is an EDIT rollback, not an account reset; the operator's lifecycle (course.receipt) handles that"));
            out.put("note", "a second attempt after the student has learned the answer is a workflow demonstration with order effects, not a controlled base-versus-adapter result");
            return out;
        }));
        /* the operator's cleanup receipt: what this room holds that a reset or a reassignment must
           not lose — also written to <capture>/receipt.json at shutdown (CourseRuntime.close) */
        r.register(new Op("course.receipt", "", false, Cost.scan, req -> {
            CourseRuntime rt = rt();
            Map<String, Object> m = rt.capture().receipt(req.str("why", "requested"));
            if (req.bool("write", false)) {
                try { m.put("written", rt.capture().writeReceipt(req.str("why", "requested")).getPath()); }
                catch (java.io.IOException e) { throw OpException.io("receipt not written: " + e.getMessage(), "capture"); }
            }
            m.put("note", "jeeves-cleanup-receipt-v1: the server's cleanup job matches account_id/workspace_generation, checks the process is gone and keeps the paths named here; a payload above the inline limit exists only under capture_root when capture_home_durable is false");
            return m;
        }));

        // ---- classroom contribution and context handoff (P0.6)
        /* the student's finding, composed for the classroom's Findings form — a machine client (the
           student's assistant) may compose it too: nothing is sent from here, the student publishes
           on the page (persona pass 2026-10-09 L3) */
        r.register(new Op("course.share", "", true, Cost.heavy, req -> {
            CourseRuntime rt = rt();
            String kind = req.str("kind", "finding");
            if (!FINDING_KINDS.contains(kind))
                throw OpException.syntax("kind must be one of " + String.join(", ", FINDING_KINDS) + " (the Findings page's card types)", "kind=" + kind);
            String title = req.str("title");
            if (title == null || title.isBlank()) throw OpException.syntax("course.share needs a title (the finding's claim)", "");
            String symbol = req.str("symbol");
            Map<String, Object> refs = new LinkedHashMap<>();
            String original = null, originalName = null;
            String tid = null;
            try { tid = ws.resolveTargetIdOrActive(req.str("target")); } catch (OpException noTarget) { if (symbol != null && !symbol.isBlank()) throw noTarget; }
            if (symbol != null && !symbol.isBlank()) {
                Map<String, Object> res = ws.resolveForEvidence(tid, symbol);   // a finding cites the exact method, never a lenient re-aim (a history address is the same method)
                /* the ORIGINAL identity survives display renames: the dex's descriptor where the engine
                   has one (JEB: origSig), else the address / class name it resolves by; an engine that
                   knows only an original NAME (origName) has it said beside it */
                original = String.valueOf(res.get("origSig") != null ? res.get("origSig") : engineKey(res));
                if (res.get("origSig") == null && res.get("origName") != null && !String.valueOf(res.get("origName")).equals(String.valueOf(res.get("name")))) originalName = String.valueOf(res.get("origName"));
                refs.put("symbol_addr", original);
            }
            if (req.str("parent") != null) refs.put("item_id", req.str("parent"));
            /* deliberate visibility: the body is what the student typed plus what they chose to attach —
               never the raw capture; a packet attaches as JSON text in a fenced block */
            StringBuilder body = new StringBuilder();
            if (req.str("body") != null) body.append(req.str("body").strip()).append("\n");
            if (original != null && originalName != null) body.append("\nsymbol: ").append(original).append(" (original name: ").append(originalName).append(")\n");
            /* the identity a peer needs to reopen the finding against their own copy — the board's refs
               cannot carry it: the course target, the bytes, this room's revision */
            String identity = null, sha = null, snapshotId = null;
            if (tid != null) {
                Map<String, Object> exe = ws.executableIdentity(tid);
                sha = ws.executableSha(tid);
                snapshotId = tid + "@h" + ws.historyRevision(tid);
                identity = "target: " + (rt.manifest().targetId() != null ? rt.manifest().targetId() + " · " : "") + ws.targetNameOf(tid)
                        + (sha != null ? " · executable sha256 " + sha + ("conflict".equals(exe.get("state")) ? " (the database input's; the file beside it differs)" : "") : " · executable identity " + exe.get("state") + " (" + exe.get("basis") + ")")
                        + " · snapshot " + snapshotId + " · room " + Course.room();
                body.append('\n').append(identity).append('\n');
            }
            if (req.params().get("packet") instanceof Map<?, ?> pk) body.append("\n```json\n").append(Json.write(pk)).append("\n```\n");
            if (req.str("mission") != null) {
                Map<String, Object> rep = r.get("course.mission.report").handler().handle(new Op.Request("course.mission.report", Map.of("id", req.str("mission")), req.actor())) instanceof Map<?, ?> rm ? asMap(rm) : Map.of();
                Object cap = rep.get("capture");
                body.append("\nRun ").append(req.str("mission")).append(": ").append(rep.get("displayState")).append(" — ").append(rep.get("stopReason")).append("\nReceipts: ").append(Json.write(cap instanceof Map<?, ?> cm ? cm.get("receipts") : cap)).append('\n');
            }
            String requestId = req.str("requestId");
            if (requestId == null || requestId.isBlank()) requestId = "share-" + CourseIdentity.digest(req.actor() + "|" + title + "|" + refs + "|" + body).substring(0, 40);
            if (!requestId.matches("[A-Za-z0-9_-]{1,64}")) throw OpException.syntax("requestId must match [A-Za-z0-9_-]{1,64}", "requestId=" + requestId);
            /* the classroom's bound: title + summary + body ≤ BOARD_TEXT_BYTES (its public text
               policy) — refused here with the size, not by a 413 on the page */
            String summary = req.str("summary");
            int bytes = title.getBytes(java.nio.charset.StandardCharsets.UTF_8).length + (summary == null ? 0 : summary.getBytes(java.nio.charset.StandardCharsets.UTF_8).length) + body.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
            if (title.length() > 200) throw OpException.syntax("a finding takes a title of at most 200 characters (this one: " + title.length() + ")", "title");
            if (summary != null && (summary.length() > 500 || summary.contains("\n"))) throw OpException.syntax("a finding takes a one-line summary of at most 500 characters", "summary");
            if (bytes > BOARD_TEXT_BYTES) throw OpException.syntax("the classroom's findings take " + BOARD_TEXT_BYTES + " bytes of text; this one is " + bytes + " — shorten the body, or attach the packet by its blocks of interest rather than whole", "bytes=" + bytes);
            /* the provenance the Findings store binds a post to (A10): the classroom's activity identity,
               the artifact's bytes, the original symbol, this room's revision, the owner — composed
               HERE, carried by the student to the page; the author is the student's own session there */
            CourseActivity.Identity act = rt.activity().current();
            Map<String, Object> prov = new LinkedHashMap<>();
            prov.put("activity", act.describe());
            if (tid != null) { prov.put("target_id", rt.manifest().targetId()); prov.put("target", ws.targetNameOf(tid)); prov.put("artifact_sha256", sha); prov.put("snapshot_id", snapshotId); prov.put("project_revision", ws.historyRevision(tid)); }
            if (original != null) prov.put("original_symbol", original);
            prov.put("room", Course.room()); prov.putAll(rt.ownerFields()); prov.put("request_id", requestId);
            /* the Findings form's FIELDS, each on its own (card type, claim/title, summary, details,
               original_symbol, artifact_sha256, project_revision, symbol): what the page takes, not one
               blob — the structured provenance stays structured on the page (persona pass H2) */
            Map<String, Object> fields = new LinkedHashMap<>();
            fields.put("type", kind); fields.put("title", title); fields.put("summary", summary == null ? "" : summary);
            fields.put("details", body.toString().strip());
            fields.put("original_symbol", original == null ? "" : original);
            fields.put("symbol", symbol == null ? "" : symbol.trim());
            fields.put("artifact_sha256", sha == null ? "" : sha);
            fields.put("project_revision", tid == null ? "" : String.valueOf(ws.historyRevision(tid)));
            fields.put("activity_id", act.id() == null ? "" : act.id()); fields.put("activity_revision", act.revision() == null ? "" : String.valueOf(act.revision()));
            fields.put("request_id", requestId);
            String copy = body.toString().strip();   // what goes into Details: the student's own words (and the original name when the engine knows only that)
            Map<String, Object> handoff = rt.activity().action("finding");   // the classroom's key (classroom_service.py: help, ready, withdraw, finding)
            if (handoff == null) handoff = rt.activity().action("findings");
            String portal = Course.portalUrl().replaceAll("/+$", "");
            String browserPlain = handoff != null && handoff.get("browser") != null ? String.valueOf(handoff.get("browser")) : (portal.isEmpty() ? "/classroom/findings" : portal + "/classroom/findings");
            /* a prefilled draft for the page, in the URL fragment (never sent to a server: fragments stay
               in the browser) — the small fields only; the page may read `#jeeves=` or ignore it */
            Map<String, Object> draft = new LinkedHashMap<>(fields); draft.remove("details"); draft.remove("summary");
            String fragment = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(Json.write(draft).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            String browser = browserPlain + (browserPlain.contains("#") ? "" : "#jeeves=" + fragment);
            Map<String, Object> preview = new LinkedHashMap<>();
            preview.put("destination", browserPlain + " — the classroom's Findings page, under your own account; visible at once under the classroom's moderation");
            preview.put("as", "you — the classroom session on that page is the author; this room never posts for you");
            preview.put("kind", kind); preview.put("title", title); preview.put("summary", summary); preview.put("refs", refs);
            preview.put("bodyMd", body.toString()); preview.put("bytes", bytes); preview.put("limitBytes", BOARD_TEXT_BYTES);
            preview.put("provenance", prov);
            preview.put("fields", fields);
            preview.put("copy", copy);
            preview.put("browser", browser); preview.put("browserPlain", browserPlain);
            if (identity != null) preview.put("identity", identity);
            if (original != null) preview.put("originalSymbol", original);
            preview.put("requestId", requestId);
            if (req.bool("preview", false)) { preview.put("note", "nothing was sent — the same fields are what TO FINDINGS PAGE carries"); return preview; }
            /* the handoff: nothing leaves this process — the student takes the fields to the page; the
               room records the DRAFT privately (a draft is not a publication) */
            Map<String, Object> out = new LinkedHashMap<>(preview);
            out.remove("bodyMd");
            out.put("sent", false);
            out.put("reason", "human_action_required");
            out.put("note", "not published from here: the Findings page opens with the small fields prefilled where it reads them; paste Details, press Publish finding there — it shows under your name at once");
            String run = req.str("mission") == null ? "human-share" : req.str("mission");
            Map<String, Object> env = new LinkedHashMap<>();
            env.put("event_id", rt.capture().instance() + ":share:" + requestId); env.put("room", Course.room()); env.put("actor", req.actor());
            env.put("run_id", rt.capture().runIdOf(run)); env.put("mission", run); env.put("conversation_id", rt.capture().runIdOf(run) + "-c1");
            env.put("activity_id", act.id()); env.put("activity_revision", act.revision()); env.put("activity_sha256", act.sha256()); env.put("activity_stale", act.stale());
            env.putAll(rt.ownerFields());
            env.put("event_type", "contribution.draft"); env.put("kind", kind); env.put("request_id", requestId); env.put("sent", false);
            if (tid != null) { env.put("target", ws.targetNameOf(tid)); env.put("target_sha256", sha); env.put("target_manifest_sha256", rt.manifest().manifestSha256()); env.put("snapshot_id", snapshotId); }
            if (original != null) env.put("original_method", original);
            try { rt.capture().record(run, env, Json.write(Map.of("title", title, "refs", refs, "kind", kind, "bytes", bytes, "copy", copy))); }
            catch (java.io.IOException e) { out.put("draftNotJournaled", e.getMessage()); }
            return out;
        }));
        /* the student's classroom actions — help, readiness, a thread: the classroom is a browser
           surface and the contract says a room credential plus an actor string is not proof of human
           intent; the room hands the student the page and the text, and says nothing was sent */
        for (String action : List.of("help", "progress", "thread")) {
            r.register(new Op("course." + action, "", false, Cost.cheap, req -> {
                CourseRuntime rt = rt();
                CourseActivity.Identity act = rt.activity().current();
                Map<String, Object> args = new LinkedHashMap<>();
                Map<String, Object> context = new LinkedHashMap<>();
                context.put("activity", act.describe());
                String tid = ws.activeTarget();
                if (tid != null) { context.put("target", ws.targetNameOf(tid)); String exeSha = ws.executableSha(tid); if (exeSha != null) context.put("artifact_sha256", exeSha); context.put("executable_identity", ws.executableIdentity(tid).get("state")); context.put("snapshot_id", tid + "@h" + ws.historyRevision(tid)); }
                if (req.str("symbol") != null) context.put("symbol", req.str("symbol"));
                if (req.str("mission") != null) context.put("run_id", req.str("mission"));
                if (req.str("finding") != null) context.put("finding_id", req.str("finding"));
                /* the one line a reader wants beside a question: activity, target, symbol — the digests
                   stay in the structured `preview.context` */
                String contextLine = "context: activity " + (act.id() == null ? "—" : act.id()) + (tid != null ? " · " + ws.targetNameOf(tid) : "") + (req.str("symbol") != null ? " · " + req.str("symbol") : "") + (req.str("mission") != null ? " · run " + req.str("mission") : "");
                String page;
                switch (action) {
                    case "help" -> { args.put("kind", "help"); args.put("body", req.require("text")); args.put("context", context); page = "help"; }
                    case "progress" -> {
                        /* the Today page's three states: I'm ready, I need help, Not ready yet (its value
                           for the last is `working`); the room reports none of them — it hands the student
                           the page */
                        String status = req.require("status");
                        if ("working".equals(status) || "withdraw".equals(status)) status = "not_ready";
                        if (!List.of("ready", "help", "not_ready").contains(status)) throw OpException.syntax("status must be ready, help or not_ready (the Today page's three states: I'm ready, I need help, Not ready yet)", "status=" + status);
                        args.put("activity", act.describe()); args.put("status", status); page = "ready";
                    }
                    default -> { if (req.str("finding") != null) args.put("finding_id", req.str("finding")); if (req.str("thread") != null) args.put("thread_id", req.str("thread")); page = "thread"; }
                }
                String requestId = "course-" + action + "-" + CourseIdentity.digest(req.actor() + "|" + Json.write(args) + "|" + System.currentTimeMillis() / 60_000).substring(0, 32);
                Map<String, Object> handoff = rt.activity().action(page);
                String portal = Course.portalUrl().replaceAll("/+$", "");
                String dflt = "/classroom/" + (action.equals("progress") ? "today" : "messages");
                String browser = handoff != null && handoff.get("browser") != null ? String.valueOf(handoff.get("browser")) : (portal.isEmpty() ? dflt : portal + dflt);
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("action", action);
                out.put("preview", args);
                out.put("requestId", requestId);
                out.put("sent", false);
                out.put("reason", "human_action_required");
                out.put("why", "the classroom takes " + (action.equals("progress") ? "readiness" : action.equals("help") ? "a word to the instructor" : "a thread reply") + " from your own classroom session only — this room cannot send it for you");
                out.put("browser", browser);
                String st = String.valueOf(args.get("status"));
                out.put("copy", action.equals("progress")
                        ? "Press '" + ("ready".equals(st) ? "I'm ready" : "help".equals(st) ? "I need help" : "Not ready yet") + "' on the Today page" + (act.id() == null ? "" : " (activity " + act.id() + ")")
                        : (action.equals("help") ? String.valueOf(args.get("body")) + "\n\n" : "") + contextLine);
                if (req.bool("preview", false)) out.put("note", "nothing is ever sent from here; this is what to carry");
                return out;
            }));
        }

        // ---- the capture's journal
        r.register(new Op("course.events", "", false, Cost.scan, req -> {
            CourseRuntime rt = rt();
            if (req.str("mission") == null) {
                /* no run named: the runs this home journaled — missions, the student's own edits per
                   target (human-<target>), their shares (human-share) */
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("runs", rt.capture().runs()); m.put("instance", rt.capture().instance()); m.put("capture", rt.capture().status());
                m.put("note", "course.events {mission: <run>} lists one run's events; run ids as exported are <instance>-<run>");
                return m;
            }
            List<Map<String, Object>> events = rt.capture().events(req.require("mission"));
            String type = req.str("type");
            List<Object> out = new ArrayList<>();
            for (Map<String, Object> ev : events) if (type == null || type.equals(ev.get("event_type"))) out.add(ev);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("mission", req.require("mission")); m.put("events", out); m.put("capture", rt.capture().status());
            if (req.bool("payloads", false)) {
                for (Object o : out) {
                    Map<String, Object> ev = asMap((Map<?, ?>) o);
                    try { String p = rt.capture().payload(String.valueOf(ev.get("payload_blob_sha256"))); if (p != null) ((Map<String, Object>) o).put("payload", p.length() > 200_000 ? p.substring(0, 200_000) + "…[+" + (p.length() - 200_000) + " chars]" : p); } catch (Exception ignored) {}
                }
            }
            return m;
        }));

        // ---- the bounded immutable artifact export (student MCP addendum): server-resident evidence
        //      too large for a model round-trip, written once under <home>/exports with its identity;
        //      the classroom service copies or references it by id after the student's selection
        r.register(new Op("course.export.artifact", "", true, Cost.heavy, req -> {
            CourseRuntime rt = rt();
            File dir = new File(rt.home(), "exports");
            Map<String, Object> owner = rt.ownerFields();
            String ownerKey = owner.get("account_id") + "|" + owner.get("workspace_generation");
            try {
                if ("get".equals(req.str("action"))) {   // one of the owner's own artifacts back, by its id — the content with its identity
                    String id = req.require("artifact_id");
                    if (!id.matches("[0-9a-f]{32}")) throw OpException.syntax("artifact_id is the 32-hex id the export answered", "artifact_id");
                    File meta = new File(dir, id + ".meta.json"), f = new File(dir, id + ".json");
                    if (!meta.isFile() || !f.isFile()) throw OpException.notFound("no artifact " + id + " in this room", "artifact_id");
                    Map<String, Object> m = asMap((Map<?, ?>) Json.parse(java.nio.file.Files.readString(meta.toPath())));
                    if (!ownerKey.equals(m.get("owner_key"))) throw OpException.scope("that artifact is another account's or an earlier generation's", "course=artifact-owner");
                    Map<String, Object> out = new LinkedHashMap<>(m);
                    out.put("content", Json.parse(java.nio.file.Files.readString(f.toPath())));
                    return out;
                }
                String tid = ws.resolveTargetIdOrActive(req.str("target"));
                String symbol = req.require("symbol");
                if (symbol.length() > 400) throw OpException.syntax("symbol is too long for an export (400 chars)", "symbol");
                String scope = req.str("scope", "method");
                if (!scope.matches("method|class")) throw OpException.syntax("scope is method or class", "scope");
                /* corpus output names the bytes it is about: a MEASURED executable identity, or — standalone, on request —
                   an artifact marked unverified (handoff 2026-10-09 §A: never silently, never a claim not performed) */
                Map<String, Object> identity = ws.targetIdentity(tid);
                @SuppressWarnings("unchecked") Map<String, Object> exe = identity != null && identity.get("executable") instanceof Map<?, ?> ex0 ? (Map<String, Object>) ex0 : Map.of("state", "missing", "basis", "no identity record");
                boolean verified = "measured".equals(exe.get("state"));
                if (req.bool("requireVerified", Course.on()) && !verified)
                    throw OpException.scope("this target has no measured executable identity (" + exe.get("state") + ": " + exe.get("basis") + ") — an evidence artifact needs one: open the target from its APK, or pass requireVerified:false to export it marked unverified", "course=artifact-unverified");
                Map<String, Object> content = asMap((Map<?, ?>) r.get("course.export.reconstruction").handler().handle(
                        new Op.Request("course.export.reconstruction", Map.of("target", tid, "symbol", symbol, "scope", scope), req.actor())));
                byte[] bytes = Json.write(content).getBytes(java.nio.charset.StandardCharsets.UTF_8);
                if (bytes.length > ARTIFACT_MAX_BYTES) throw OpException.syntax("the export is " + bytes.length + " bytes; an artifact is bounded at " + ARTIFACT_MAX_BYTES + " — narrow the scope (method) or the symbol", "bytes=" + bytes.length);
                String sha = Capture.sha256(bytes);
                /* the id is the owner's: content-addressed WITHIN an account and generation (the same export
                   twice is one artifact; the same bytes in another room are another id) — never a bare hash a
                   stranger could look up (fix-review addendum M8, the server's A11 rule) */
                String id = Capture.sha256(("jeeves-artifact-v1|" + ownerKey + "|" + sha).getBytes(java.nio.charset.StandardCharsets.UTF_8)).substring(0, 32);
                java.nio.file.Files.createDirectories(dir.toPath());
                File f = new File(dir, id + ".json"), meta = new File(dir, id + ".meta.json");
                if (f.isFile() && meta.isFile()) {   // immutable: the same content is the same artifact — its first record answers
                    Map<String, Object> m = asMap((Map<?, ?>) Json.parse(java.nio.file.Files.readString(meta.toPath())));
                    m.put("existing", true);
                    return m;
                }
                long used = 0; File[] kids = dir.listFiles();
                if (kids != null) for (File k : kids) if (k.isFile()) used += k.length();
                if (used + bytes.length > EXPORT_QUOTA_BYTES)
                    throw OpException.busy("this room's exports are at their bound (" + (used >> 20) + " MiB) — the classroom copies what you selected; ask the instructor if more is needed", "course=export-quota");
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("schema", "jeeves-artifact-export-v1");
                out.put("artifact_id", id); out.put("sha256", sha); out.put("size", bytes.length);
                out.put("path", f.getPath()); out.put("content_type", "application/json"); out.put("content_schema", content.get("schema"));
                out.putAll(owner); out.put("owner_key", ownerKey); out.put("room", Course.room());
                Map<String, Object> prov = new LinkedHashMap<>();
                String match = String.valueOf(rt.manifest().resolveDigest(ws.executableSha(tid), ws.targetNameOf(tid)).get("match"));
                prov.put("target", tid); prov.put("target_name", ws.targetNameOf(tid)); prov.put("artifact_sha256", ws.executableSha(tid));   // the executable's, as the identity record measured it
                prov.put("manifest_match", match); if ("match".equals(match)) prov.put("course_target_id", rt.manifest().targetId());
                prov.put("project_revision", ws.historyRevision(tid)); prov.put("snapshot_id", tid + "@h" + ws.historyRevision(tid));
                prov.put("symbol", symbol); prov.put("original_symbol", content.get("subject") instanceof Map<?, ?> sm && sm.get("origSig") != null ? sm.get("origSig") : null); prov.put("scope", scope);
                prov.put("artifact_verified", verified); prov.put("executable", exe);
                prov.put("activity", rt.activity().current().describe());
                out.put("provenance", prov);
                out.put("created_at", java.time.Instant.now().toString());
                out.put("note", "immutable: the same content is the same artifact id for this account and generation; the record beside it (<id>.meta.json) carries the owner and the provenance; the classroom copies or references it by id after the student's explicit selection — no remote path or URL is fetched by anyone");
                writeAtomic(f, bytes);                                              // the content, then its record, then the directory
                writeAtomic(meta, Json.write(out).getBytes(java.nio.charset.StandardCharsets.UTF_8));
                fsyncDir(dir);
                return out;
            } catch (java.io.IOException e) { throw OpException.io("artifact not written: " + e.getMessage(), "exports"); }
        }));

        // ---- P1: the source-reconstruction export seam
        r.register(new Op("course.export.reconstruction", "", false, Cost.scan, req -> {
            CourseRuntime rt = rt();
            String tid = ws.resolveTargetIdOrActive(req.str("target"));
            dev.workbench.engine.Engine e = ws.engineFor(tid);
            String symbol = req.require("symbol");
            String scope = req.str("scope", "method");
            Map<String, Object> res = ws.resolveForEvidence(tid, symbol);   // the corpus rule: a complete descriptor matches exactly (or is a history address) or the export refuses
            String kind = String.valueOf(res.get("kind"));
            if (!List.of("method", "class", "cluster").contains(scope)) throw OpException.syntax("scope must be method, class or cluster", "scope=" + scope);
            if ("method".equals(scope) && !"method".equals(kind)) scope = "class";
            String scopeNote = "cluster".equals(scope) ? "cluster is answered as the CLASS for now — captured outer-class state, lambdas and the callers' classes are listed under omitted, not included" : null;
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("schema", "reconstruction-export-v1");
            File art = ws.targetArtifactFile(tid);
            Map<String, Object> dex = new LinkedHashMap<>();
            Map<String, Object> identity = ws.targetIdentity(tid);
            @SuppressWarnings("unchecked") Map<String, Object> exe = identity != null && identity.get("executable") instanceof Map<?, ?> ex0 ? (Map<String, Object>) ex0 : Map.of("state", "missing");
            dex.put("target", tid); dex.put("name", ws.targetNameOf(tid));
            dex.put("artifactSha256", "measured".equals(exe.get("state")) ? exe.get("sha256") : null);   // only a MEASURED identity sits under the old name
            dex.put("executable", exe); dex.put("database", identity == null ? null : identity.get("database"));
            dex.put("artifact_verified", "measured".equals(exe.get("state")));
            out.put("runtime", identity == null ? dev.workbench.server.Workspace.runtimeIdentity() : identity.get("runtime"));
            dex.put("manifestSha256", rt.manifest().manifestSha256()); dex.put("manifestTarget", rt.manifest().targetId());
            out.put("dex", dex);
            out.put("snapshot", Map.of("historyRevision", ws.historyRevision(tid), "snapshot_id", tid + "@h" + ws.historyRevision(tid)));
            out.put("scope", scope);
            if (scopeNote != null) out.put("scopeNote", scopeNote);
            out.put("subject", res);
            String subjectKey = engineKey(res);
            String classSym = "class".equals(kind) ? subjectKey : classKey(res);
            /* the dex text (ground truth) and the fixed decompiler view, as the engine gives them —
               an engine without one says so instead of failing the export */
            try { out.put("smali", e.smali("method".equals(scope) ? subjectKey : classSym)); }
            catch (OpException ex) { out.put("smali", Map.of("unavailable", ex.getMessage())); }
            try { out.put("decompiled", e.decompile("method".equals(scope) ? subjectKey : classSym, Map.of("scope", "method".equals(scope) ? "method" : "class"))); }
            catch (OpException ex) { out.put("decompiled", Map.of("unavailable", ex.getMessage())); }
            /* names: display versus original, member by member */
            Map<String, Object> outline = e.outline(classSym);
            List<Object> names = new ArrayList<>();
            for (String k : List.of("methods", "fields")) if (outline.get(k) instanceof List<?> l) for (Object o : l) if (o instanceof Map<?, ?> m) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("kind", k.substring(0, k.length() - 1)); row.put("addr", m.get("addr")); row.put("name", m.get("name")); row.put("sig", m.get("sig"));
                if (m.get("origName") != null) { row.put("origName", m.get("origName")); row.put("analystNamed", true); }
                names.add(row);
            }
            out.put("names", names);
            /* the retained parameter metadata, raw and apart from every name above: the method's, or
               for a class scope the methods that carry the annotation (bounded by that count) */
            try {
                if ("method".equals(scope)) {
                    Map<String, Object> mp1 = e.methodParameters(subjectKey);
                    /* the analyst's mappings beside the raw metadata — provenance the export promises */
                    try { mp1.put("mappings", ws.parameterMappings(ws.resolveTargetIdOrActive(req.str("target")), String.valueOf(mp1.get("method")), String.valueOf(mp1.get("original_method")))); } catch (Exception ignored) {}
                    out.put("methodParameters", mp1);
                } else if (outline.get("methods") instanceof List<?> ml) {
                    /* the raw read per method (no class render — the current names are the method scope's) */
                    List<Object> mp = new ArrayList<>();
                    for (Object o : ml) if (o instanceof Map<?, ?> mm && mm.get("addr") != null) {
                        try {
                            Map<String, Object> one = e.methodParameters(String.valueOf(mm.get("addr")), false);
                            Map<?, ?> ann = (Map<?, ?>) one.get("annotation");
                            if (ann != null && ("present".equals(ann.get("status")) || "malformed".equals(ann.get("status")))) mp.add(one);
                        } catch (OpException ignored) {}
                    }
                    out.put("methodParametersIndex", mp);
                }
            } catch (OpException ex) { out.put("methodParameters", Map.of("status", "unsupported", "why", ex.getMessage())); }
            if (!"method".equals(scope)) {
                List<Object> ctors = new ArrayList<>();
                if (outline.get("methods") instanceof List<?> l) for (Object o : l) if (o instanceof Map<?, ?> m && String.valueOf(m.get("name")).startsWith("<")) ctors.add(m.get("addr"));
                out.put("constructors", ctors);
            }
            List<Object> callers = new ArrayList<>();
            try { for (Object o : e.callers(subjectKey)) { callers.add(o); if (callers.size() >= 50) break; } } catch (OpException ignored) {}
            out.put("callers", Map.of("rows", callers, "truncated", callers.size() >= 50));
            out.put("compiler", Map.of("attribution", "unknown", "note", "the dex carries no compiler manifest; attribution (D8/R8/Redex revision, pass order) comes from the course's source-pair factory, not from the artifact"));
            out.put("uncertainty", "analyst names are hypotheses (analystNamed:true); the decompiler view is one representation; cluster membership (captures, callers) is bounded as listed");
            out.put("limits", Map.of("callers", 50, "scope", scope));
            out.put("omitted", List.of("captured outer-class state and lambdas not in this class", "callers beyond 50", "constructors of referenced classes", "resources and native code"));
            out.put("outputContract", Map.of("deliverable", "compilable source files for the scope, with a reconstruction receipt {subject, snapshot_id, model, provider, candidate_sha256}",
                    "candidate", "a generated source is a CANDIDATE — never an accepted target because an agent liked it; promotion needs a verifier result attached (verifier.revision, result, provenance)"));
            out.put("verifier", null);
            out.put("promotion", Map.of("state", "not-eligible", "requires", List.of("verifier result/provenance attached", "explicit promotion by the operator", "eval taint cleared by the server, never here")));
            out.put("eval_taint", Course.evalTaint());
            if (req.str("familyId") != null) out.put("family_id", req.str("familyId"));
            out.put("privateHoldout", "protected source, maps and test answers are never part of this export's model input");
            return out;
        }));
    }

    /** The key an engine resolves a symbol by, from its own resolution record: the address (JEB:
     *  the descriptor), else the class name, else the display name (the mock scene). */
    static String engineKey(Map<String, Object> res) {
        for (String k : List.of("addr", "fqcn", "name")) if (res.get(k) != null) return String.valueOf(res.get(k));
        return null;
    }

    /** The owning class's key for a member's resolution record. */
    static String classKey(Map<String, Object> res) {
        String addr = res.get("addr") == null ? null : String.valueOf(res.get("addr"));
        if (addr != null && addr.contains("->")) return addr.substring(0, addr.indexOf("->"));
        if (res.get("owner") != null) return String.valueOf(res.get("owner"));
        return engineKey(res);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Map<?, ?> m) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : m.entrySet()) out.put(String.valueOf(e.getKey()), e.getValue());
        return out;
    }
}
