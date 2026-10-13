package dev.workbench.it;

import dev.workbench.course.Course;
import dev.workbench.registry.Json;
import dev.workbench.server.WorkbenchServer;
import java.io.File;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Evidence-packet integration test on the REAL engine (plain main-class like the other suites;
 * hermetic home, port 7820). The mock scene has no CFG, so CourseIT can only check the route; this
 * suite opens the small fixture apk (~/jeb/test_visionocr.apk) under JEB, FINDS the methods it needs
 * in it (several blocks; an entry fork whose branches share a tail; an exception edge; a back edge;
 * a bodiless method) and checks evidence.packet against the course exercise's requirements
 * (jeeves-dev-handoff P0.4):
 *
 *   1. course mode against CourseIT's fixture class server: the manifest names the apk's sha256 →
 *      MATCH on real bytes, course.status ready
 *   2. whole method: mode method, every block selected, nothing omitted, references keyed on the
 *      ORIGINAL descriptor (+offset), java whole
 *   3. a branch entry: CFG reachability — the shared tail is kept, the selector block and the other
 *      branch are omitted, the caller's choice is named missing; the same entry as an instruction
 *      offset selects the same blocks; java is never cut to a branch; a bad entry → E_NOTFOUND
 *   4. an exception edge keeps its handler · 5. a loop is traversed once (bounded)
 *   6. truncation: the cut is declared, references counted as for the whole method
 *   7. a neutral rename keeps original_method, instructions, selection and the student block; the
 *      display name is declared a hypothesis; the snapshot revision moves (a stale packet is visible)
 *   8. a class → E_SYNTAX; a bodiless method → mode no-code
 *   9. the student block through the course's own starter (exercises/packet_builder.py, copied to
 *      .qa/day1/) and a complete builder (successors + exceptional, bounded) equals the packet's selection
 *  10. course.share after the rename posts the ORIGINAL descriptor · 11. another room → 404
 *  12. the capture on real tool results: a Day 1 mission's events carry the apk's sha256 and the
 *      subject's original descriptor; a result the loop capped is recorded with both sizes
 * Run: gradle packetIT
 */
public final class PacketIT {

    private static int failures = 0, checks = 0;
    private static final HttpClient http = HttpClient.newHttpClient();
    private static final String BASE = "http://127.0.0.1:" + WorkbenchServer.PORT;

    public static void main(String[] args) {
        try { run(args); }
        catch (Throwable t) { t.printStackTrace(System.out); System.out.println("PACKET IT ABORTED after " + checks + " checks: " + t); System.exit(1); }
    }

    private static void run(String[] args) throws Exception {
        String homeEnv = System.getenv("WORKBENCH_HOME");
        if (homeEnv == null) throw new IllegalStateException("PacketIT needs a hermetic WORKBENCH_HOME — run it with: gradle packetIT");
        File home = new File(homeEnv).getCanonicalFile();
        File fixture = new File(args.length > 0 ? args[0] : System.getProperty("user.home") + "/jeb/test_visionocr.apk");
        if (!fixture.isFile()) throw new IllegalStateException("no fixture apk at " + fixture);
        String apkSha = sha256(Files.readAllBytes(fixture.toPath()));

        // ---- course mode against the fixture class server; the manifest names the fixture's own bytes
        CourseIT.ClassServer cs = new CourseIT.ClassServer();
        cs.enabled = true;   // the instructor has released access for this rehearsal (CourseIT covers the locked room)
        File creds = new File(home, "creds"); creds.mkdirs();
        Files.writeString(new File(creds, "room-7--auth").toPath(), cs.authTok + "\n");
        Files.writeString(new File(creds, "room-7--store").toPath(), cs.storeTok + "\n");
        Files.writeString(new File(creds, "room-7--classroom").toPath(), cs.classroomTok + "\n");
        File manifest = new File(home, "manifest.json");
        Files.writeString(manifest.toPath(), Json.write(Map.of("schema", Course.MANIFEST_SCHEMA, "target_id", "course-fixture-v1", "label", "Course fixture (test_visionocr.apk)",
                "artifacts", List.of(Map.of("name", fixture.getName(), "sha256", apkSha, "size", fixture.length())))));
        Map<String, String> ov = new LinkedHashMap<>();
        ov.put("", "1"); ov.put("room", "room-7"); ov.put("auth_url", cs.url()); ov.put("store_url", cs.url()); ov.put("gateway_url", cs.url()); ov.put("board_url", cs.url()); ov.put("classroom_url", cs.url());
        ov.put("credentials", creds.getAbsolutePath()); ov.put("capture", "strict"); ov.put("manifest", manifest.getAbsolutePath()); ov.put("activity", "E3");
        ov.put("roots", fixture.getParentFile().getAbsolutePath());   // the fixture's directory is the course root this room may open from
        ov.put("provider", "fixture:ds-tools"); ov.put("preset_turns", "4"); ov.put("preset_output", "2048"); ov.put("preset_budget", "60000");
        Course.overrides(ov);
        WorkbenchServer server = new WorkbenchServer(Path.of("web"));
        server.start();
        System.out.println("server up on " + BASE + " · home " + home + " · class server " + cs.url());
        data(post("project.open", Map.of("name", "packet")));
        String tid = String.valueOf(data(post("target.open", Map.of("path", fixture.getPath()))).get("id"));
        check(waitReady(tid), "the fixture apk is open and analysed as " + tid);

        // ---- 1. the manifest match and the health contract on real bytes
        Map<String, Object> st = data(post("course.status", Map.of()));
        Map<?, ?> res = (Map<?, ?>) ((Map<?, ?>) st.get("target")).get("resolution");
        check("match".equals(res.get("match")) && apkSha.equals(res.get("sha256")) && "ready".equals(st.get("state")),
                "course.status on the fixture: " + st.get("state") + " · manifest " + res.get("match") + " (" + st.get("summary") + ")");

        // ---- the methods the checks use, found in the apk (pinned by its sha256 in the manifest)
        Found f = discover(tid);
        check(f.whole != null, "a method with several blocks: " + f.whole);
        check(f.branch != null, "a method whose entry forks into branches sharing a tail: " + f.branch + " · entry " + f.branchEntry + " · other " + f.branchOther);
        check(f.exc != null, "a method with an exception edge: " + f.exc + " at " + f.excBlock);
        check(f.loop != null, "a method with a back edge: " + f.loop + " · header " + f.loopHeader + " ← " + f.loopBack);
        if (f.noCode == null) System.out.println("SKIP no bodiless (abstract/native) method among the scanned classes");
        if (f.whole == null || f.branch == null || f.exc == null || f.loop == null) throw new IllegalStateException("the fixture apk did not yield the methods these checks need");

        // ---- 2. the whole method
        Map<String, Object> w = data(post("evidence.packet", Map.of("target", tid, "symbol", f.whole, "question", "what does it do?")));
        List<Map<String, Object>> wb = blocksOf(w);
        List<String> wsel = strings(w.get("selected")), wom = strings(w.get("omitted"));
        List<?> wins = (List<?>) w.get("instructions");
        String orig = String.valueOf(w.get("original_method"));
        boolean refsOk = !wins.isEmpty();
        for (Object o : wins) { String ref = String.valueOf(((Map<?, ?>) o).get("ref")); if (!ref.startsWith(orig + "+") || !ref.endsWith("h")) refsOk = false; }
        Map<?, ?> refs = (Map<?, ?>) w.get("references");
        Map<?, ?> student = (Map<?, ?>) w.get("student");
        check("method".equals(w.get("mode")) && wsel.size() == wb.size() && wom.isEmpty() && Boolean.TRUE.equals(((Map<?, ?>) w.get("cfg")).get("available")) && refsOk && orig.startsWith("L") && orig.contains("->")
                        && w.get("java") instanceof Map<?, ?> jm && jm.get("text") != null && w.get("truncated") == null && "what does it do?".equals(w.get("question"))
                        && refs.keySet().containsAll(List.of("calls", "fields", "types", "strings")) && ((List<?>) w.get("missing")).size() >= 2
                        && ((List<?>) student.get("blocks")).size() == wb.size() && w.get("entryBlock").equals(student.get("entry")) && orig.equals(student.get("original_method")),
                "whole method " + orig + ": mode method, " + wsel.size() + "/" + wb.size() + " blocks selected, nothing omitted, " + wins.size()
                        + " instructions referenced as <original descriptor>+<offset>h, references and omissions listed, java whole, the student block holds every block");

        // ---- 2b. a complete descriptor with the WRONG return type: resolved leniently but SAID, refused by the corpus paths
        //      (a model working on Jeeves, 2026-10-09: "it can accept a fully specified method descriptor with the wrong
        //      return type and resolve it to another signature" — true by design for a reading agent; never silent now)
        String wrongRet = orig.endsWith("V") ? orig.substring(0, orig.length() - 1) + "I" : orig.substring(0, orig.lastIndexOf(')') + 1) + "V";
        Map<String, Object> lenient = post("symbol.resolve", Map.of("target", tid, "symbol", wrongRet));
        Map<String, Object> ld = Boolean.TRUE.equals(lenient.get("ok")) ? data(lenient) : Map.of();
        check(Boolean.TRUE.equals(lenient.get("ok")) && Boolean.FALSE.equals(ld.get("exact")) && "return type".equals(ld.get("lenient")) && Boolean.TRUE.equals(ld.get("contradiction"))
                        && wrongRet.equals(ld.get("asked")) && orig.equals(ld.get("origSig")) && String.valueOf(ld.get("note")).contains("not evidence"),
                "a complete descriptor with the wrong return type resolves to the method found but SAYS so: exact=false, lenient='return type', contradiction, asked kept (" + wrongRet + " → " + ld.get("addr") + ")");
        Map<String, Object> noRet = data(post("symbol.resolve", Map.of("target", tid, "symbol", orig.substring(0, orig.lastIndexOf(')') + 1))));
        check(Boolean.FALSE.equals(noRet.get("exact")) && "return type omitted".equals(noRet.get("lenient")) && noRet.get("contradiction") == null, "a descriptor without a return type is a partial reference: lenient 'return type omitted', no contradiction");
        Map<String, Object> exactRes = data(post("symbol.resolve", Map.of("target", tid, "symbol", orig)));
        check(Boolean.TRUE.equals(exactRes.get("exact")) && exactRes.get("lenient") == null, "the exact descriptor answers exact=true");
        Map<String, Object> codeWrong = data(post("code.decompile", Map.of("target", tid, "symbol", wrongRet)));
        check(codeWrong.get("resolution") instanceof Map<?, ?> cr && Boolean.TRUE.equals(cr.get("contradiction")) && wrongRet.equals(cr.get("asked")) && orig.equals(cr.get("origSig")),
                "the code answer for the contradicting descriptor carries the warning (resolution: asked, origSig, contradiction) — the reading agent is told");
        Map<String, Object> exportWrong = post("course.export.reconstruction", Map.of("target", tid, "symbol", wrongRet, "scope", "method"));
        String ewMsg = exportWrong.get("error") instanceof Map<?, ?> em ? String.valueOf(em.get("message")) : "";
        check(!Boolean.TRUE.equals(exportWrong.get("ok")) && "E_NOTFOUND".equals(code(exportWrong)) && ewMsg.contains("exact descriptor") && ewMsg.contains(orig),
                "the reconstruction export REFUSES the contradicting descriptor and names the method it found: " + ewMsg);
        Map<String, Object> shareWrong = raw("course.share", Map.of("kind", "finding", "title", "wrong ret", "symbol", wrongRet, "body", "x", "target", tid), Map.of("X-WB-Principal", "7", "X-WB-Grant", "g-alice"), null);
        check(!Boolean.TRUE.equals(shareWrong.get("ok")) && "E_NOTFOUND".equals(code(shareWrong)), "a finding citing the contradicting descriptor is refused the same way (" + code(shareWrong) + ")");
        Map<String, Object> exportOk = post("course.export.reconstruction", Map.of("target", tid, "symbol", orig.substring(0, orig.lastIndexOf(')') + 1), "scope", "method"));
        check(Boolean.TRUE.equals(exportOk.get("ok")) && data(exportOk).get("subject") instanceof Map<?, ?> sj && "return type omitted".equals(sj.get("lenient")),
                "a partial reference exports, and the export's subject says how it was resolved");

        // ---- 3. a branch entry
        Map<String, Object> b = data(post("evidence.packet", Map.of("target", tid, "symbol", f.branch, "entry", f.branchEntry)));
        Set<String> bsel = new HashSet<>(strings(b.get("selected")));
        List<String> bom = strings(b.get("omitted"));
        Map<String, Map<String, Object>> bById = byId(blocksOf(b));
        Set<String> fromOther = reach(bById, f.branchOther);
        Set<String> shared = new HashSet<>(fromOther); shared.retainAll(bsel);
        String bEntryBlock = String.valueOf(b.get("entryBlock"));
        String bOrig = String.valueOf(b.get("original_method"));
        check("branch".equals(b.get("mode")) && f.branchEntry.equals(b.get("entry")) && bsel.contains(f.branchEntry) && !bsel.contains(bEntryBlock) && !bsel.contains(f.branchOther)
                        && bom.contains(bEntryBlock) && bom.contains(f.branchOther) && !shared.isEmpty() && String.valueOf(b.get("missing")).contains("caller's choice")
                        && ((List<?>) ((Map<?, ?>) b.get("student")).get("blocks")).size() == bById.size(),
                "branch entry " + f.branchEntry + " of " + bOrig + ": " + bsel.size() + " reachable block(s) selected, the shared tail kept " + shared + ", the selector " + bEntryBlock
                        + " and the other branch " + f.branchOther + " omitted, the caller's choice named missing; the student block still holds all " + bById.size() + " blocks");
        String off = f.branchEntry.substring(1);
        Map<String, Object> b2 = data(post("evidence.packet", Map.of("target", tid, "symbol", f.branch, "entry", off, "java", false)));
        check(f.branchEntry.equals(b2.get("entry")) && b2.get("selected").equals(b.get("selected")), "the same entry as an instruction offset (" + off + ") selects the same blocks");
        check(b.get("java") instanceof Map<?, ?> bj && String.valueOf(bj.get("scope")).contains("complete method") && bj.get("text") != null,
                "the java view stays the complete method — never a text cut between labels");
        Map<String, Object> bad = post("evidence.packet", Map.of("target", tid, "symbol", f.branch, "entry", "bffff"));
        check("E_NOTFOUND".equals(code(bad)) && String.valueOf(message(bad)).contains("not an instruction offset or a block"), "an entry that is no block or offset → E_NOTFOUND (" + message(bad) + ")");

        // ---- 4. an exception edge
        Map<String, Object> x = data(post("evidence.packet", Map.of("target", tid, "symbol", f.exc, "entry", f.excBlock, "java", false)));
        Set<String> xsel = new HashSet<>(strings(x.get("selected")));
        List<String> handlers = excOf(byId(blocksOf(x)).get(f.excBlock));
        check(!handlers.isEmpty() && xsel.containsAll(handlers), "exception edge: selecting " + f.excBlock + " of " + x.get("original_method") + " keeps its handler block(s) " + handlers);

        // ---- 5. a loop
        Map<String, Object> lp = data(post("evidence.packet", Map.of("target", tid, "symbol", f.loop, "entry", f.loopHeader, "java", false)));
        Set<String> lsel = new HashSet<>(strings(lp.get("selected")));
        check(lsel.contains(f.loopHeader) && lsel.contains(f.loopBack) && lsel.size() <= blocksOf(lp).size() && strings(lp.get("selected")).size() == lsel.size(),
                "loop: from the header " + f.loopHeader + " the body and the back-edge block " + f.loopBack + " are selected once — bounded (" + lsel.size() + " of " + blocksOf(lp).size() + " blocks)");

        // ---- 6. truncation — the cap as a number and as the facade's string
        Map<String, Object> tr = data(post("evidence.packet", Map.of("target", tid, "symbol", f.whole, "maxInstructions", 5, "java", false)));
        Map<?, ?> trunc = (Map<?, ?>) tr.get("truncated");
        check(trunc != null && ((List<?>) tr.get("instructions")).size() == 5 && ((Number) trunc.get("instructions")).intValue() == wins.size() - 5 && tr.get("references").equals(refs)
                        && strings(tr.get("selected")).equals(wsel),
                "truncation: 5 of " + wins.size() + " instructions listed, " + trunc.get("instructions") + " declared cut, the selection and the references as for the whole method");
        Map<String, Object> trS = data(post("evidence.packet", Map.of("target", tid, "symbol", f.whole, "maxInstructions", "5", "java", "false", "student", "false")));
        List<String> keys = new ArrayList<>(trS.keySet());
        check(((List<?>) trS.get("instructions")).size() == 5 && trS.get("student") == null && trS.get("studentOmitted") != null && trS.get("java") == null
                        && "schema".equals(keys.get(0)) && keys.indexOf("missing") < keys.indexOf("instructions") && keys.indexOf("references") < keys.indexOf("instructions"),
                "the facade's spellings work (maxInstructions \"5\", java \"false\", student \"false\": no student block, said so) and the small parts come before the listings: " + keys);

        // ---- 7. a neutral rename keeps the original identity; the snapshot revision moves
        Map<String, Object> before = data(post("evidence.packet", Map.of("target", tid, "symbol", f.whole, "java", false)));
        String origName = String.valueOf(((Map<?, ?>) before.get("method")).get("name"));
        String newAddr = String.valueOf(data(post("rename", Map.of("target", tid, "symbol", f.whole, "name", "packetItNeutral"))).get("newAddr"));
        Map<String, Object> after = data(post("evidence.packet", Map.of("target", tid, "symbol", newAddr, "java", false)));
        Map<?, ?> am = (Map<?, ?>) after.get("method");
        List<?> prov = (List<?>) after.get("provenance");
        check(orig.equals(after.get("original_method")) && before.get("instructions").equals(after.get("instructions")) && before.get("selected").equals(after.get("selected"))
                        && Boolean.TRUE.equals(am.get("renamed")) && origName.equals(am.get("origName")) && "packetItNeutral".equals(am.get("name"))
                        && !prov.isEmpty() && origName.equals(((Map<?, ?>) prov.get(0)).get("origName")) && String.valueOf(after.get("provenanceNote")).contains("hypotheses")
                        && ((Map<?, ?>) after.get("student")).get("blocks").equals(((Map<?, ?>) before.get("student")).get("blocks")) && orig.equals(((Map<?, ?>) after.get("student")).get("original_method"))
                        && "packetItNeutral".equals(((Map<?, ?>) after.get("student")).get("display_name")),
                "neutral rename " + origName + " → packetItNeutral: original_method, instructions, selection and the student block unchanged; the display name is declared a hypothesis");
        int rev0 = ((Number) ((Map<?, ?>) before.get("snapshot")).get("historyRevision")).intValue(), rev1 = ((Number) ((Map<?, ?>) after.get("snapshot")).get("historyRevision")).intValue();
        check(rev1 > rev0 && apkSha.equals(((Map<?, ?>) after.get("snapshot")).get("artifactSha256")) && tid.equals(((Map<?, ?>) after.get("snapshot")).get("target"))
                        && "course-fixture-v1".equals(((Map<?, ?>) after.get("snapshot")).get("courseTargetId")) && "room-7".equals(((Map<?, ?>) after.get("snapshot")).get("room")),
                "the packet's snapshot: this room's revision moved from h" + rev0 + " to h" + rev1 + " (an older packet of this room is stale); the apk's sha256 and the course target id identify the bytes for a peer anywhere");
        /* a referenced symbol renamed: the evidence does not change — the lines keep the dex's names,
           the rename shows in provenance (the course's "display names must not change evidence") */
        String typeToRename = null;
        for (String t : strings(refs.get("types"))) {
            if (t.equals(orig.substring(0, orig.indexOf("->")))) continue;
            Map<String, Object> rs = post("symbol.resolve", Map.of("target", tid, "symbol", t));
            if (Boolean.TRUE.equals(rs.get("ok")) && "class".equals(data(rs).get("kind")) && t.startsWith("Lm76/")) { typeToRename = t; break; }
        }
        if (typeToRename == null) System.out.println("SKIP no referenced class of the apk's own to rename in " + orig);
        else {
            data(post("rename", Map.of("target", tid, "symbol", typeToRename, "name", "PacketItType")));
            Map<String, Object> after2 = data(post("evidence.packet", Map.of("target", tid, "symbol", newAddr, "java", false)));
            boolean provHasType = false;
            for (Object o : (List<?>) after2.get("provenance")) if (o instanceof Map<?, ?> pm && "class".equals(pm.get("kind")) && typeToRename.equals(pm.get("origSig")) && "PacketItType".equals(pm.get("name"))) provHasType = true;
            check(after.get("instructions").equals(after2.get("instructions")) && ((Map<?, ?>) after2.get("student")).get("blocks").equals(((Map<?, ?>) after.get("student")).get("blocks"))
                            && strings(((Map<?, ?>) after2.get("references")).get("types")).contains(typeToRename) && provHasType && "original".equals(after2.get("names")),
                    "a referenced class renamed (" + typeToRename + " → PacketItType): the evidence and the student lines are unchanged, the type is still referenced by its descriptor, the display name is in provenance only");
        }

        // ---- 8. a class is not a bounded question; a bodiless method says so
        Map<String, Object> clsPk = post("evidence.packet", Map.of("target", tid, "symbol", orig.substring(0, orig.indexOf("->"))));
        check("E_SYNTAX".equals(code(clsPk)), "a class → E_SYNTAX: the packet takes a METHOD (" + message(clsPk) + ")");
        if (f.noCode != null) {
            Map<String, Object> nc = data(post("evidence.packet", Map.of("target", tid, "symbol", f.noCode)));
            check("no-code".equals(nc.get("mode")) && nc.get("missing") != null && nc.get("original_method") != null, "a bodiless method (" + f.noCode + ") answers mode no-code with the gap named");
        }

        // ---- 9. the student block through the course's starter and a complete builder
        File builder = new File(System.getenv().getOrDefault("WORKBENCH_QUALIFY_PACKET_BUILDER", "backend/src/test/fixtures/course/packet_builder.py"));
        if (!builder.isFile()) System.out.println("SKIP explicit WORKBENCH_QUALIFY_PACKET_BUILDER missing — the student block was not run through the starter");
        else {
            File studentJson = new File(home, "student-branch.json");
            Files.writeString(studentJson.toPath(), Json.write(b.get("student")));
            Map<String, Object> built = Json.parseObject(exec("python3", builder.getPath(), studentJson.getPath()));
            List<?> bb = (List<?>) built.get("blocks");
            List<String> expectOmitted = new ArrayList<>(bById.keySet()); expectOmitted.remove(f.branchEntry); Collections.sort(expectOmitted);
            check(bb.size() == 1 && f.branchEntry.equals(((Map<?, ?>) bb.get(0)).get("id")) && bOrig.equals(built.get("original_method")) && built.get("scope_note") != null
                            && expectOmitted.equals(strings(built.get("omitted"))) && Boolean.FALSE.equals(built.get("fixture")),
                    "the course's starter consumes the real method's student block unchanged: the entry alone selected, " + expectOmitted.size() + " omitted, original_method and scope_note carried, fixture:false");
            String bfs = exec("python3", "-c",
                    "import json,sys\ncfg=json.load(open(sys.argv[1]))\nb={x['id']:x for x in cfg['blocks']}\nseen=[];todo=[cfg['entry']]\n"
                    + "while todo:\n n=todo.pop(0)\n if n in seen or n not in b: continue\n seen.append(n)\n todo+=b[n]['successors']+b[n]['exceptional']\nprint(json.dumps(sorted(seen)))",
                    studentJson.getPath());
            List<String> reached = strings(Json.parse(bfs));
            List<String> expect = new ArrayList<>(bsel); Collections.sort(expect);
            check(expect.equals(reached), "a complete builder (successors + exceptional, bounded) over the student block selects exactly the packet's blocks: " + reached);
        }

        // ---- 10. share on the real engine (the student, from the room's page): the finding composed for the classroom's Findings page carries the dex's descriptor, after the rename
        Map<String, Object> sh = data(raw("course.share", Map.of("kind", "finding", "title", "packet IT", "symbol", newAddr, "body", "the packet's method", "target", tid), Map.of("X-WB-Principal", "7", "X-WB-Grant", "g-alice"), null));
        Map<?, ?> shRefs = (Map<?, ?>) sh.get("refs");
        Map<?, ?> shProv = (Map<?, ?>) sh.get("provenance");
        String copy = String.valueOf(sh.get("copy"));
        Map<?, ?> shFields = (Map<?, ?>) sh.get("fields");
        check(Boolean.FALSE.equals(sh.get("sent")) && "human_action_required".equals(sh.get("reason")) && orig.equals(shRefs.get("symbol_addr")) && orig.equals(sh.get("originalSymbol"))
                        && orig.equals(shProv.get("original_symbol")) && apkSha.equals(shProv.get("artifact_sha256")) && orig.equals(shFields.get("original_symbol")) && apkSha.equals(shFields.get("artifact_sha256")) && !copy.contains("original name:"),
                "course.share after the rename hands over the ORIGINAL descriptor (" + shRefs.get("symbol_addr") + ") and the apk's identity in the provenance — nothing posted from the room");

        // ---- 11. another room cannot read this room's packet
        Map<String, Object> other = raw("evidence.packet", Map.of("target", tid, "symbol", newAddr), Map.of("X-WB-Principal", "room-8"), "human:mallory");
        check(status(other) == 404, "another room's principal asking for the packet → 404, nothing learned");

        // ---- 12. the capture on the real engine (the fixture provider plays a thinking endpoint: its trace must be replayed, as the class gateway's would be)
        data(post("llm.configure", Map.of("id", "fixture", "reasoningField", "reasoning_content", "reasoningReplay", "all")));
        Map<String, Object> run = data(post("course.mission", Map.of("symbol", newAddr, "question", "q", "target", tid)));
        String mid = String.valueOf(run.get("id"));
        Map<String, Object> done = waitSettled(mid, 180_000);
        Thread.sleep(1500);
        List<Map<String, Object>> evs = dev.workbench.course.CourseRuntime.current().capture().events(mid);
        Map<String, Object> first = evs.isEmpty() ? Map.of() : evs.get(0);
        check("done".equals(done.get("state")) && apkSha.equals(first.get("target_sha256")) && orig.equals(first.get("original_method")) && String.valueOf(first.get("snapshot_id")).startsWith(tid + "@h")
                        && String.valueOf(first.get("run_id")).endsWith("-" + mid) && "match".equals(((Map<?, ?>) ((Map<?, ?>) run.get("snapshot")).get("manifestResolution")).get("match")),
                "a Day 1 mission on the real target: " + done.get("displayState") + " (" + done.get("stopReason") + "); its events carry the apk's sha256, the subject's ORIGINAL descriptor and the snapshot " + first.get("snapshot_id"));
        Map<String, Object> big = data(post("agent.spawn", Map.of("goal", "read everything", "model", "fixture:big-results", "target", tid)));
        Map<String, Object> bigDone = waitSettled(String.valueOf(big.get("id")), 180_000);
        Thread.sleep(1000);
        Map<String, Object> capped = null;
        for (Map<String, Object> e : dev.workbench.course.CourseRuntime.current().capture().events(String.valueOf(big.get("id"))))
            if ("tool.result".equals(e.get("event_type")) && Boolean.TRUE.equals(e.get("capped"))) capped = e;
        check("done".equals(bigDone.get("state")) && capped != null && ((Number) capped.get("result_chars_full")).intValue() > ((Number) capped.get("result_chars")).intValue()
                        && ((Number) capped.get("result_chars")).intValue() <= 6_000,
                "a tool result the loop capped before the model saw it is recorded with both sizes: "
                        + (capped == null ? "none capped" : capped.get("result_chars") + " of " + capped.get("result_chars_full") + " chars (" + capped.get("tool") + ")"));

        System.out.println();
        // ---- 12b. resolution, said — the acceptance cases of handoff §B: shorthand, a wrong parameter type, same-arity ambiguity,
        //      the obfuscated case collision, a renamed method by its original name, a history address (a parameter type renamed), the xref envelope
        Map<String, Object> shorthand = data(post("symbol.resolve", Map.of("target", tid, "symbol", orig.substring(0, orig.indexOf('(')))));
        check(Boolean.FALSE.equals(shorthand.get("exact")) && "name only".equals(shorthand.get("lenient")) && shorthand.get("contradiction") == null && orig.equals(shorthand.get("origSig")),
                "shorthand (owner->name) resolves as a partial reference: lenient 'name only', no contradiction, the original descriptor answered");
        Map<String, Object> byOrig = data(post("symbol.resolve", Map.of("target", tid, "symbol", orig)));
        check((Boolean.TRUE.equals(byOrig.get("exact")) && "original signature".equals(byOrig.get("by"))) || "original name".equals(byOrig.get("lenient")),
                "the renamed method asked by its ORIGINAL descriptor is an identity, not a guess: " + (Boolean.TRUE.equals(byOrig.get("exact")) ? "exact by " + byOrig.get("by") : "lenient '" + byOrig.get("lenient") + "'"));
        Map<String, Object> xr = post("xref.to", Map.of("target", tid, "symbol", wrongRet, "limit", 5));
        Map<?, ?> xmeta = (Map<?, ?>) xr.get("meta"); Map<?, ?> xres = xmeta == null ? null : (Map<?, ?>) xmeta.get("resolution");
        check(Boolean.TRUE.equals(xr.get("ok")) && xres != null && Boolean.TRUE.equals(xres.get("contradiction")) && wrongRet.equals(xres.get("requested")) && orig.equals(xres.get("resolvedOriginal")) && "contradiction".equals(xres.get("resolutionMode")),
                "xref.to on the contradicting descriptor answers its rows, and the envelope's meta.resolution says requested / resolvedOriginal / resolutionMode=contradiction");
        Map<String, Object> pkWrong = post("evidence.packet", Map.of("target", tid, "symbol", wrongRet, "java", false));
        check("E_NOTFOUND".equals(code(pkWrong)) && String.valueOf(((Map<?, ?>) pkWrong.get("error")).get("message")).contains(orig), "evidence.packet refuses the contradicting descriptor and names the ORIGINAL one (strict evidence)");
        Map<String, Object> smWrong = post("code.smali", Map.of("target", tid, "symbol", wrongRet));
        check("E_NOTFOUND".equals(code(smWrong)), "code.smali (the byte-check source) refuses it too");
        Map<String, Object> dotted = post("symbol.resolve", Map.of("target", tid, "symbol", orig.substring(1, orig.indexOf(";->")).replace('/', '.') + "." + orig.substring(orig.indexOf("->") + 2, orig.indexOf('('))));
        boolean dottedAmb = !Boolean.TRUE.equals(dotted.get("ok")) && String.valueOf(((Map<?, ?>) dotted.get("error")).get("message")).contains("ambiguous");
        check(dottedAmb || (Boolean.FALSE.equals(data(dotted).get("exact")) && "name only".equals(data(dotted).get("lenient"))), "a dotted Owner.method shorthand is said (lenient 'name only') or, with overloads, an ambiguity naming the candidates — never a silent first pick");
        Map<String, Object> withOff = data(post("symbol.resolve", Map.of("target", tid, "symbol", orig + "+0h")));
        check(orig.equals(withOff.get("origSig")) && "0h".equals(withOff.get("offset")) && Boolean.TRUE.equals(withOff.get("exact")), "an xref `from` with its offset resolves to the method and keeps the offset (" + withOff.get("offset") + ")");
        Map<String, Object> viaFacade = raw("tool.call", Map.of("tool", "xrefs", "args", Map.of("symbol", wrongRet, "direction", "to", "target", tid)), Map.of("X-WB-Principal", "room-7", "X-WB-Grant", "g-kimi"), "llm:kimi");
        Map<String, Object> vfd = Boolean.TRUE.equals(viaFacade.get("ok")) ? data(viaFacade) : Map.of();
        Object vfr = vfd.get("resolution"); if (vfr == null && vfd.get("result") instanceof Map<?, ?> vfres) vfr = vfres.get("resolution");
        check(vfr instanceof Map<?, ?> vrm && Boolean.TRUE.equals(vrm.get("contradiction")) && wrongRet.equals(vrm.get("requested")),
                "through the facade (tool.call → xrefs) the DATA carries the resolution block — what the bridge and the agent loop read: " + (vfr == null ? viaFacade : "contradiction said"));
        Map<String, Object> wrongBoth = data(post("symbol.resolve", Map.of("target", tid, "symbol", wrongRet.replace("()", "(I)"))));
        check(Boolean.TRUE.equals(wrongBoth.get("contradiction")) && wrongBoth.get("differs") instanceof List<?> dl && dl.contains("return type") && (dl.contains("parameter arity") || dl.contains("parameter types")) || !Boolean.TRUE.equals(wrongBoth.get("exact")),
                "every differing component is said, not only the first: " + wrongBoth.get("differs"));
        String owner = orig.substring(0, orig.indexOf("->")); String simple = owner.substring(owner.lastIndexOf('/') + 1, owner.length() - 1);
        String flipped = owner.substring(0, owner.lastIndexOf('/') + 1) + (simple.equals(simple.toUpperCase()) ? simple.toLowerCase() : simple.toUpperCase()) + ";";
        Map<String, Object> cc = post("symbol.resolve", Map.of("target", tid, "symbol", flipped + orig.substring(orig.indexOf("->"))));
        check(!Boolean.TRUE.equals(cc.get("ok")) || !String.valueOf(data(cc).get("addr")).startsWith(owner + "->"),
                "an obfuscated case collision is never folded: " + flipped + " does not resolve into " + owner + " (" + (Boolean.TRUE.equals(cc.get("ok")) ? data(cc).get("addr") : code(cc)) + ")");
        String[] mp = methodWithParam(tid, false);
        if (mp == null) System.out.println("SKIP no method with a parameter among the scanned classes");
        else {
            String swap = mp[1].equals("I") ? "J" : mp[1].equals("J") ? "I" : "Ljava/lang/Runnable;";
            String wrongParam = mp[0].replace("(" + mp[1], "(" + swap);
            Map<String, Object> wp = post("symbol.resolve", Map.of("target", tid, "symbol", wrongParam));
            boolean ambiguous = !Boolean.TRUE.equals(wp.get("ok")) && String.valueOf(((Map<?, ?>) wp.get("error")).get("message")).contains("ambiguous");
            Map<String, Object> wpd = Boolean.TRUE.equals(wp.get("ok")) ? data(wp) : Map.of();
            check(ambiguous || (Boolean.TRUE.equals(wpd.get("contradiction")) && "parameter types".equals(wpd.get("lenient"))),
                    "a complete descriptor with a wrong parameter type (" + wrongParam + ") is a contradiction — or, among same-arity overloads, an ambiguity naming the candidates: " + (ambiguous ? "ambiguous" : wpd.get("lenient")));
            if (!ambiguous) {
                Map<String, Object> exWp = post("course.export.reconstruction", Map.of("target", tid, "symbol", wrongParam, "scope", "method"));
                check("E_NOTFOUND".equals(code(exWp)), "…and the reconstruction export refuses it (" + code(exWp) + ")");
            }
        }
        String[] ovl = overloadPair(tid);
        if (ovl == null) System.out.println("SKIP no same-arity overload pair among the scanned classes");
        else {
            String third = ovl[0].replace("(" + ovl[2], "(Ljava/util/concurrent/Executor;");
            Map<String, Object> amb = post("symbol.resolve", Map.of("target", tid, "symbol", third));
            check(!Boolean.TRUE.equals(amb.get("ok")) && "E_NOTFOUND".equals(code(amb)) && String.valueOf(((Map<?, ?>) amb.get("error")).get("message")).contains("candidates"),
                    "same-arity overloads (" + ovl[0] + " · " + ovl[1] + ") with a wrong type → ambiguity naming the candidates, never a pick");
        }
        String[] ap = methodWithParam(tid, true);
        if (ap == null) System.out.println("SKIP no method with an app-class parameter among the scanned classes");
        else {
            Map<String, Object> rn = post("rename", Map.of("target", tid, "symbol", ap[1], "name", "PktRenamedType"));
            String newCls = Boolean.TRUE.equals(rn.get("ok")) ? String.valueOf(data(rn).get("newAddr")) : null;
            Map<String, Object> hist = Boolean.TRUE.equals(rn.get("ok")) ? post("symbol.resolve", Map.of("target", tid, "symbol", ap[0])) : rn;
            Map<String, Object> hd = Boolean.TRUE.equals(hist.get("ok")) ? data(hist) : Map.of();
            check(newCls != null && !newCls.equals(ap[1]) && "rename history".equals(hd.get("lenient")) && hd.get("contradiction") == null && String.valueOf(hd.get("addr")).contains(newCls),
                    "a HISTORY ADDRESS (parameter type " + ap[1] + " renamed to " + newCls + ") resolves as 'rename history' — the same method, no contradiction (" + hd.get("lenient") + ")");
            if (newCls != null) {
                Map<String, Object> exHist = post("course.export.reconstruction", Map.of("target", tid, "symbol", ap[0], "scope", "method"));
                check(Boolean.TRUE.equals(exHist.get("ok")), "…and the export accepts the history address (" + code(exHist) + ")");
            }
        }

        // ---- 12c. the observer follow-up (2026-10-09): compact class metadata for a consumer generating hooks — ORIGINAL names,
        //      field kind/type/static, same-name field/method collisions said, the superclass chain on request; the build identity in workspace.state
        Map<String, Object> wsState = data(post("workspace.state", Map.of()));
        Map<?, ?> wr = (Map<?, ?>) wsState.get("runtime");
        check(wr != null && wr.get("build_sha") != null && String.valueOf(wr.get("engine")).startsWith("JEB") && wr.get("process_started_at") != null && wr.get("build_file_changed_since_start") instanceof Boolean,
                "workspace.state carries the running build and engine (runtime: " + String.valueOf(wr.get("build_sha")).substring(0, 7) + ", dirty " + wr.get("build_dirty") + ", " + wr.get("engine") + ", changed-since-start " + wr.get("build_file_changed_since_start") + ")");
        String[] col = classWithCollision(tid);
        if (col == null) System.out.println("SKIP no same-name field/method collision among the scanned classes");
        else {
            Map<String, Object> ol = data(post("code.outline", Map.of("target", tid, "class", col[0])));
            List<?> cols = (List<?>) ol.get("collisions");
            Map<?, ?> firstCol = cols == null ? null : cols.stream().map(o -> (Map<?, ?>) o).filter(c -> col[1].equals(c.get("origName"))).findFirst().orElse(null);
            check(firstCol != null && firstCol.get("field") instanceof Map<?, ?> ff && ff.get("type") != null && firstCol.get("methods") instanceof List<?> ml && !ml.isEmpty() && String.valueOf(firstCol.get("note")).contains("getDeclaredField"),
                    "code.outline names the same-name field/method collision " + col[0] + " " + col[1] + " by ORIGINAL name, with the field's type and the methods' original descriptors, and the hook advice");
            Map<?, ?> anyField = (Map<?, ?>) ((List<?>) ol.get("fields")).get(0);
            check(anyField.get("type") != null && anyField.get("static") instanceof Boolean && anyField.get("origSig") != null && anyField.get("declaredIn") != null && anyField.get("access") != null,
                    "outline fields carry type, static, access, origSig, declaredIn (e.g. " + anyField.get("origSig") + " : " + anyField.get("type") + ")");
        }
        Map<String, Object> inh = data(post("code.outline", Map.of("target", tid, "class", orig.substring(0, orig.indexOf("->")), "inherited", true)));
        Map<?, ?> im = (Map<?, ?>) inh.get("inherited");
        check(im != null && im.get("fields") instanceof List<?> && im.get("unknown") instanceof List<?> && im.get("chain") instanceof List<?>,
                "code.outline {inherited: true} walks the superclass chain: in-dex fields with inheritedFrom, framework supers listed as unknown (" + ((List<?>) im.get("chain")).size() + " in-dex super(s), " + ((List<?>) im.get("unknown")).size() + " unknown)");

        // ---- 12d. P1 §C: one call site's bounded context (code.site / code {view: site}); P1 §I: a multi-anchor lookup with an honest status
        String callOff = null, callText = null, callMethod = null;
        for (String cand : new String[] {f.whole, f.branch, f.loop, f.exc}) {
            if (cand == null) continue;
            Map<String, Object> sm = data(post("code.smali", Map.of("target", tid, "symbol", cand)));
            for (Object ro : (List<?>) sm.get("rows")) if (ro instanceof Map<?, ?> row && String.valueOf(row.get("text")).trim().startsWith("invoke-") && row.get("offset") != null && !String.valueOf(row.get("offset")).isEmpty()) { callOff = String.valueOf(row.get("offset")); callText = String.valueOf(row.get("text")).trim(); callMethod = cand; break; }
            if (callOff != null) break;
        }
        if (callOff == null) System.out.println("SKIP no invoke in the discovered methods");
        else {
            String callOrig = String.valueOf(data(post("symbol.resolve", Map.of("target", tid, "symbol", callMethod))).get("origSig"));
            Map<String, Object> site = data(post("code.site", Map.of("target", tid, "symbol", callMethod, "at", "+" + callOff + "h")));
            Map<?, ?> sst = (Map<?, ?>) site.get("site"); Map<?, ?> dx = (Map<?, ?>) site.get("dex"); Map<?, ?> bl = (Map<?, ?>) site.get("block"); Map<?, ?> jv = (Map<?, ?>) site.get("java");
            final String callOffF = callOff;
            boolean atMarked = ((List<?>) dx.get("rows")).stream().anyMatch(r -> r instanceof Map<?, ?> rm && Boolean.TRUE.equals(rm.get("at")) && callOffF.equals(rm.get("offset")));
            check("call-site-v1".equals(site.get("schema")) && "call".equals(sst.get("kind")) && sst.get("callee") instanceof Map<?, ?> && atMarked && bl.get("id") != null && bl.get("predecessors") instanceof List<?>
                            && String.valueOf(bl.get("note")).contains("not a dominance") && (jv.get("line") != null || jv.get("unavailable") != null) && site.get("more") instanceof Map<?, ?> && site.get("extent") instanceof Map<?, ?>,
                    "code.site: the instruction (" + callText.substring(0, Math.min(40, callText.length())) + "…), its DEX neighbours with the site marked, the containing block + predecessors (syntactic, said), the callee, the decompiler's line (" + (jv.get("line") != null ? "line " + jv.get("line") + " of " + jv.get("totalLines") : jv.get("unavailable")) + "), extent and continuation");
            Map<String, Object> viaRef = data(post("code.site", Map.of("target", tid, "symbol", callOrig + "+" + callOff + "h")));
            check(callOff.equals(((Map<?, ?>) viaRef.get("site")).get("offset")), "the same site from an xref-style reference (<origSig>+" + callOff + "h) — the offset rides in the symbol");
            Map<String, Object> badOff = post("code.site", Map.of("target", tid, "symbol", callMethod, "at", "+ffffh"));
            check("E_NOTFOUND".equals(code(badOff)) && String.valueOf(((Map<?, ?>) badOff.get("error")).get("message")).contains("spans"), "an offset outside the body → E_NOTFOUND naming the body's span");
            Map<String, Object> viaTool = raw("tool.call", Map.of("tool", "code", "args", Map.of("symbol", callMethod, "view", "site", "at", "+" + callOff + "h", "target", tid)), Map.of("X-WB-Principal", "room-7", "X-WB-Grant", "g-kimi"), "llm:kimi");
            check(Boolean.TRUE.equals(viaTool.get("ok")), "the facade routes code {view: site} (" + code(viaTool) + ")");
            Map<?, ?> callee = (Map<?, ?>) sst.get("callee");
            String calleeRef = String.valueOf(callee.get("origSig") != null ? callee.get("origSig") : callee.get("ref"));
            String callOwner = callOrig.substring(0, callOrig.indexOf("->"));
            String ownerSimple = callOwner.substring(callOwner.lastIndexOf('/') + 1, callOwner.length() - 1);
            Map<String, Object> loc = data(post("symbol.locate", Map.of("target", tid, "anchors", List.of(Map.of("kind", "api", "value", calleeRef), Map.of("kind", "class", "value", ownerSimple)))));
            List<?> cands = (List<?>) loc.get("candidates");
            final String callMethodF = callMethod;
            boolean hasWhole = cands.stream().anyMatch(c -> c instanceof Map<?, ?> cm && (callOrig.equals(cm.get("origSig")) || callMethodF.equals(cm.get("addr"))));
            check(List.of("located", "ambiguous", "partial").contains(loc.get("status")) && hasWhole && ((List<?>) loc.get("anchors")).size() == 2 && String.valueOf(loc.get("note")).contains("never \"absent\""),
                    "symbol.locate by an API anchor + a class anchor → status " + loc.get("status") + ", " + cands.size() + " candidate(s) incl. the method, each with matched/unmatched anchors; the note says not_located ≠ absent");
            Map<String, Object> none = data(post("symbol.locate", Map.of("target", tid, "anchors", List.of(Map.of("kind", "string", "value", "zz-not-in-this-apk-9f3a")))));
            check("not_located".equals(none.get("status")) && "not_located".equals(((Map<?, ?>) ((List<?>) none.get("anchors")).get(0)).get("status")) && ((List<?>) none.get("candidates")).isEmpty(),
                    "an anchor nothing matches → status not_located (and the anchor says so), no candidate invented");
            Map<String, Object> viaSearch = raw("tool.call", Map.of("tool", "search", "args", Map.of("anchors", List.of(Map.of("kind", "api", "value", calleeRef)), "target", tid)), Map.of("X-WB-Principal", "room-7", "X-WB-Grant", "g-kimi"), "llm:kimi");
            check(Boolean.TRUE.equals(viaSearch.get("ok")) && data(viaSearch).get("status") != null, "the facade routes search {anchors} to the lookup (" + (Boolean.TRUE.equals(viaSearch.get("ok")) ? data(viaSearch).get("status") : code(viaSearch)) + ")");
        }

        // ---- 13. the three identities kept apart (handoff 2026-10-09 §A): measured · the database reopened alone · a conflicting sibling
        Map<String, Object> pkId = data(post("evidence.packet", Map.of("target", tid, "symbol", f.whole, "java", false)));
        Map<?, ?> sn = (Map<?, ?>) pkId.get("snapshot");
        Map<?, ?> exe = (Map<?, ?>) sn.get("executable"); Map<?, ?> dbi = (Map<?, ?>) sn.get("database"); Map<?, ?> rti = (Map<?, ?>) sn.get("runtime");
        check("measured".equals(exe.get("state")) && apkSha.equals(exe.get("sha256")) && apkSha.equals(sn.get("artifactSha256")) && String.valueOf(exe.get("basis")).contains("input")
                        && String.valueOf(dbi.get("file")).endsWith(".jdb2") && sn.get("revision") instanceof Map<?, ?> && rti != null && String.valueOf(rti.get("engine")).startsWith("JEB") && rti.get("build_sha") != null
                        && String.valueOf(sn.get("note")).contains("identifies the executable's bytes"),
                "the packet's snapshot keeps the executable (measured; the file equals the embedded input), the database (" + dbi.get("file") + ") and the revision apart, with the running build (" + String.valueOf(rti.get("build_sha")).substring(0, 7) + ", " + rti.get("built_at_basis") + ") and engine " + rti.get("engine"));
        Map<String, Object> info = data(post("target.info", Map.of("target", tid)));
        check(info.get("identity") instanceof Map<?, ?> idm && "measured".equals(((Map<?, ?>) idm.get("executable")).get("state")), "target.info carries the same identity block");
        // the database reopened ALONE (its APK not beside it): the executable is still MEASURED — from the input JEB embeds in the .jdb2
        data(post("target.save", Map.of("target", tid)));
        File liveJdb2 = null;
        try (java.util.stream.Stream<Path> walk = Files.walk(home.toPath())) {
            liveJdb2 = walk.filter(pth -> pth.toString().endsWith(".jdb2") && pth.toString().contains(String.valueOf(sn.get("storeKey")))).map(Path::toFile).findFirst().orElse(null);
        }
        if (liveJdb2 == null) try (java.util.stream.Stream<Path> walk = Files.walk(home.toPath())) { liveJdb2 = walk.filter(pth -> pth.toString().endsWith(".jdb2")).map(Path::toFile).findFirst().orElse(null); }
        check(liveJdb2 != null && liveJdb2.isFile(), "the room's database for the fixture is on disk: " + liveJdb2);
        data(post("target.close", Map.of("target", tid, "save", true)));
        File reopenDir = new File(home, "files/reopen"); reopenDir.mkdirs();
        File dbCopy = new File(reopenDir, "reopened-copy.jdb2");
        Files.copy(liveJdb2.toPath(), dbCopy.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        /* the store's APK is moved AWAY first: a measurement must come from what the database holds, not from a path JEB re-reads */
        File storeApk = new File(liveJdb2.getParentFile(), liveJdb2.getName().replace(".jdb2", ".apk"));
        File apkAside = new File(home, "apk-aside.bin");
        boolean movedAside = storeApk.isFile() && storeApk.renameTo(apkAside);
        String t2 = String.valueOf(data(post("target.open", Map.of("path", dbCopy.getPath(), "rekey", true))).get("id"));
        check(waitReady(t2), "the database copy opens alone as " + t2 + (movedAside ? " (the store's APK moved aside)" : " (no store APK to move: " + storeApk + ")"));
        Map<?, ?> sn2 = (Map<?, ?>) data(post("evidence.packet", Map.of("target", t2, "symbol", f.whole, "java", false))).get("snapshot");
        Map<?, ?> exe2 = (Map<?, ?>) sn2.get("executable");
        /* measured on 2026-10-09: JEB does NOT keep the APK inside the .jdb2 — its input re-reads the file at the recorded path. With that
           file gone the honest states are `missing`, or `declared` (the course manifest names the bytes for this artifact's name — NOT measured) */
        boolean honest = ("declared".equals(exe2.get("state")) && apkSha.equals(exe2.get("declared_sha256")) && String.valueOf(exe2.get("basis")).startsWith("NOT measured"))
                || ("missing".equals(exe2.get("state")) && String.valueOf(exe2.get("basis")).contains("not readable"));
        check(honest && exe2.get("sha256") == null && sn2.get("artifactSha256") == null && String.valueOf(sn2.get("note")).startsWith("NO ")
                        && "reopened-copy.jdb2".equals(((Map<?, ?>) sn2.get("database")).get("file")) && ((Map<?, ?>) sn2.get("database")).get("jebKey") != null,
                "a database reopened WITHOUT its APK (moved aside: " + movedAside + ") claims no measurement: state " + exe2.get("state") + " (" + exe2.get("inputClass") + "), no artifactSha256, the note says NO — basis: " + exe2.get("basis"));
        Map<?, ?> rev2 = (Map<?, ?>) sn2.get("revision");
        check(rev2 != null && String.valueOf(rev2.get("note")).contains("INSIDE a database"), "the copy's revision block says its history is this workbench's, not the renames the database carries");
        Map<String, Object> exportNoBytes = post("course.export.artifact", Map.of("target", t2, "symbol", f.whole, "scope", "method"));
        check("E_SCOPE".equals(code(exportNoBytes)), "the room refuses an evidence artifact from the database alone (" + code(exportNoBytes) + ")");
        data(post("target.close", Map.of("target", t2, "save", false)));
        if (movedAside) apkAside.renameTo(storeApk);
        /* the APK back at its path: a second copy reopens and JEB's record re-reads the file — MEASURED, with the input class said */
        File dbCopy2 = new File(reopenDir, "reopened-copy-2.jdb2");
        Files.copy(liveJdb2.toPath(), dbCopy2.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        String t2b = String.valueOf(data(post("target.open", Map.of("path", dbCopy2.getPath(), "rekey", true))).get("id"));
        check(waitReady(t2b), "a second database copy opens with the APK back at its recorded path as " + t2b);
        Map<?, ?> sn2b = (Map<?, ?>) data(post("evidence.packet", Map.of("target", t2b, "symbol", f.whole, "java", false))).get("snapshot");
        Map<?, ?> exe2b = (Map<?, ?>) sn2b.get("executable");
        check("measured".equals(exe2b.get("state")) && apkSha.equals(exe2b.get("sha256")) && exe2b.get("inputClass") != null && String.valueOf(exe2b.get("basis")).contains("re-reads") && apkSha.equals(sn2b.get("artifactSha256")),
                "with the file at JEB's recorded path the reopened database is MEASURED by re-reading it (" + exe2b.get("inputClass") + ") and the basis says so — never 'embedded'");
        data(post("target.close", Map.of("target", t2b, "save", false)));
        // a conflicting sibling: the same database copied beside a file of OTHER bytes carrying the input's name
        File conflictDir = new File(home, "files/conflict"); conflictDir.mkdirs();
        Files.copy(liveJdb2.toPath(), new File(conflictDir, "test_visionocr.jdb2").toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        byte[] otherBytes = Files.readAllBytes(fixture.toPath()); otherBytes = java.util.Arrays.copyOf(otherBytes, otherBytes.length + 1); otherBytes[otherBytes.length - 1] = 0x42;
        File sibling = new File(conflictDir, "test_visionocr.apk"); Files.write(sibling.toPath(), otherBytes);
        Map<String, Object> open3 = post("target.open", Map.of("path", sibling.getPath(), "rekey", true));
        String t3 = Boolean.TRUE.equals(open3.get("ok")) ? String.valueOf(data(open3).get("id")) : null;
        check(t3 != null && waitReady(t3), "the sibling apk opens (JEB takes the database beside it) as " + t3 + (t3 == null ? " — " + open3 : ""));
        if (t3 != null) {
            Map<?, ?> sn3 = (Map<?, ?>) data(post("evidence.packet", Map.of("target", t3, "symbol", f.whole, "java", false))).get("snapshot");
            Map<?, ?> exe3 = (Map<?, ?>) sn3.get("executable");
            check("conflict".equals(exe3.get("state")) && apkSha.equals(exe3.get("sha256")) && sha256(otherBytes).equals(exe3.get("sibling_sha256")) && sn3.get("artifactSha256") == null && String.valueOf(sn3.get("note")).contains("NOT the database's input"),
                    "a database beside a file of other bytes: state conflict — the database input's sha (measured) and the sibling's, no bare artifactSha256, the note says which is which: " + exe3.get("state"));
            Map<String, Object> exportConflict = post("course.export.artifact", Map.of("target", t3, "symbol", f.whole, "scope", "method"));
            check(!Boolean.TRUE.equals(exportConflict.get("ok")) && "E_SCOPE".equals(code(exportConflict)) && String.valueOf(((Map<?, ?>) exportConflict.get("error")).get("message")).contains("no measured executable identity"),
                    "an evidence artifact from a conflicting target is refused in a room (requireVerified) — never marked verified");
            Map<String, Object> exportMarked = data(post("course.export.artifact", Map.of("target", t3, "symbol", f.whole, "scope", "method", "requireVerified", false)));
            check(exportMarked.get("provenance") instanceof Map<?, ?> pm3 && Boolean.FALSE.equals(pm3.get("artifact_verified")) && "conflict".equals(((Map<?, ?>) pm3.get("executable")).get("state"))
                            && apkSha.equals(pm3.get("artifact_sha256")) && !sha256(otherBytes).equals(pm3.get("artifact_sha256")),
                    "on request it exports marked artifact_verified:false, and its artifact_sha256 is the database input's — never the sibling file's");
            Map<String, Object> shareConflict = data(raw("course.share", Map.of("kind", "finding", "title", "conflict", "symbol", f.whole, "body", "x", "target", t3), Map.of("X-WB-Principal", "7", "X-WB-Grant", "g-alice"), null));
            Map<?, ?> scp = (Map<?, ?>) shareConflict.get("provenance");
            check(scp != null && String.valueOf(shareConflict.get("copy")).contains("executable sha256 " + apkSha) && String.valueOf(shareConflict.get("copy")).contains("the database input's"),
                    "a finding from the conflicting target cites the database input's digest and says the file beside it differs");
            Map<String, Object> stConf = data(post("course.status", Map.of()));
            check(((Map<?, ?>) stConf.get("target")).get("executable") instanceof Map<?, ?> se && "conflict".equals(se.get("state")), "course.status.target carries the executable identity block (" + ((Map<?, ?>) ((Map<?, ?>) stConf.get("target")).get("executable")).get("state") + ")");
            data(post("target.close", Map.of("target", t3, "save", false)));
        }

        if (failures > 0) { System.out.println("PACKET IT FAILED: " + failures + "/" + checks + " checks failed"); System.exit(1); }
        System.out.println("PACKET IT PASSED: " + checks + " checks green");
        System.exit(0);
    }

    // ------------------------------------------------------------ finding the methods

    record Found(String whole, String branch, String branchEntry, String branchOther, String exc, String excBlock, String loop, String loopHeader, String loopBack, String noCode) {}

    /** Walk the apk's classes and their methods' packets (no java) until every shape is found. */
    private static Found discover(String tid) throws Exception {
        String whole = null, branch = null, branchEntry = null, branchOther = null, exc = null, excBlock = null, loop = null, loopHeader = null, loopBack = null, noCode = null;
        int scanned = 0;
        outer:
        for (String cls : classesOf(tid, 400)) {
            Map<String, Object> outline = post("code.outline", Map.of("target", tid, "class", cls));
            if (!Boolean.TRUE.equals(outline.get("ok")) || !(data(outline).get("methods") instanceof List<?> l)) continue;
            for (Object mo : l) {
                if (!(mo instanceof Map<?, ?> row) || row.get("addr") == null) continue;
                String addr = String.valueOf(row.get("addr"));
                Map<String, Object> pk = post("evidence.packet", Map.of("target", tid, "symbol", addr, "java", false));
                if (!Boolean.TRUE.equals(pk.get("ok"))) continue;
                scanned++;
                Map<String, Object> p = data(pk);
                if ("no-code".equals(p.get("mode"))) { if (noCode == null) noCode = addr; continue; }
                List<Map<String, Object>> blocks = blocksOf(p);
                if (blocks.size() < 3) continue;
                Map<String, Map<String, Object>> byId = byId(blocks);
                if (whole == null && blocks.size() <= 12) whole = addr;
                for (Map<String, Object> blk : blocks) {
                    if (loop == null) for (String s : succ(blk)) if (s.compareTo(id(blk)) <= 0) { loop = addr; loopHeader = s; loopBack = id(blk); break; }
                    if (exc == null && !excOf(blk).isEmpty()) { exc = addr; excBlock = id(blk); }
                }
                if (branch == null) {
                    String entryId = String.valueOf(p.get("entryBlock"));
                    List<String> ss = succ(byId.get(entryId));
                    for (int i = 0; i < ss.size() && branch == null; i++) for (int j = 0; j < ss.size() && branch == null; j++) {
                        if (i == j) continue;
                        String cand = ss.get(i), other = ss.get(j);
                        Set<String> fromCand = reach(byId, cand);
                        if (fromCand.contains(entryId) || fromCand.contains(other)) continue;   // a loop back to the selector, or the "other" branch is not another branch
                        Set<String> sharedTail = reach(byId, other); sharedTail.retainAll(fromCand);
                        if (sharedTail.isEmpty()) continue;
                        branch = addr; branchEntry = cand; branchOther = other;
                    }
                }
                if (whole != null && branch != null && exc != null && loop != null && noCode != null) break outer;
            }
        }
        System.out.println("scanned " + scanned + " methods with code");
        return new Found(whole, branch, branchEntry, branchOther, exc, excBlock, loop, loopHeader, loopBack, noCode);
    }

    /** The first method among the scanned classes with a parameter — any (`appClass` false) or one whose first
     *  parameter is an app class (`L…;` not java/android/kotlin) — as {addr, first parameter type}; null if none. */
    private static String[] methodWithParam(String tid, boolean appClass) throws Exception {
        for (String cls : classesOf(tid, 150)) {
            Map<String, Object> outline = post("code.outline", Map.of("target", tid, "class", cls));
            if (!Boolean.TRUE.equals(outline.get("ok")) || !(data(outline).get("methods") instanceof List<?> l)) continue;
            for (Object mo : l) {
                if (!(mo instanceof Map<?, ?> row) || row.get("addr") == null) continue;
                String addr = String.valueOf(row.get("addr"));
                int lp = addr.indexOf('('), rp = addr.indexOf(')');
                if (lp < 0 || rp <= lp + 1) continue;
                String params = addr.substring(lp + 1, rp);
                String first = params.startsWith("L") ? params.substring(0, params.indexOf(';') + 1) : params.startsWith("[") ? null : params.substring(0, 1);
                if (first == null) continue;
                if (appClass && !(first.startsWith("L") && !first.startsWith("Ljava/") && !first.startsWith("Landroid/") && !first.startsWith("Lkotlin/") && !first.startsWith("Landroidx/"))) continue;
                if (!appClass && !(first.equals("I") || first.equals("J") || first.startsWith("L"))) continue;
                if (addr.contains("<init>") || addr.contains("<clinit>")) continue;
                return new String[] {addr, first};
            }
        }
        return null;
    }

    /** Two methods of one class with the same name and arity but different parameter types, as {addr1, addr2,
     *  addr1's first parameter type}; null if none among the scanned classes. */
    private static String[] overloadPair(String tid) throws Exception {
        for (String cls : classesOf(tid, 200)) {
            Map<String, Object> outline = post("code.outline", Map.of("target", tid, "class", cls));
            if (!Boolean.TRUE.equals(outline.get("ok")) || !(data(outline).get("methods") instanceof List<?> l)) continue;
            Map<String, List<String>> byShape = new LinkedHashMap<>();
            for (Object mo : l) {
                if (!(mo instanceof Map<?, ?> row) || row.get("addr") == null) continue;
                String addr = String.valueOf(row.get("addr"));
                int ar = addr.indexOf("->"), lp = addr.indexOf('('), rp = addr.indexOf(')');
                if (ar < 0 || lp < 0 || rp <= lp + 1) continue;
                String params = addr.substring(lp + 1, rp);
                int arity = 0; for (int i = 0; i < params.length(); i++) { char c = params.charAt(i); if (c == '[') continue; if (c == 'L') { i = params.indexOf(';', i); } arity++; }
                byShape.computeIfAbsent(addr.substring(ar + 2, lp) + "/" + arity, k -> new ArrayList<>()).add(addr);
            }
            for (List<String> group : byShape.values()) {
                if (group.size() < 2) continue;
                String a = group.get(0), b = group.get(1);
                if (a.substring(a.indexOf('('), a.indexOf(')')).equals(b.substring(b.indexOf('('), b.indexOf(')')))) continue;
                String pa = a.substring(a.indexOf('(') + 1, a.indexOf(')'));
                String first = pa.startsWith("L") ? pa.substring(0, pa.indexOf(';') + 1) : pa.startsWith("[") ? null : pa.substring(0, 1);
                if (first == null) continue;
                return new String[] {a, b, first};
            }
        }
        return null;
    }

    /** A class with a field and a method sharing an ORIGINAL name, as {class addr, original name}; null if none among the scanned classes. */
    private static String[] classWithCollision(String tid) throws Exception {
        for (String cls : classesOf(tid, 250)) {
            Map<String, Object> outline = post("code.outline", Map.of("target", tid, "class", cls));
            if (!Boolean.TRUE.equals(outline.get("ok"))) continue;
            Map<String, Object> d = data(outline);
            Set<String> methodNames = new java.util.HashSet<>();
            if (d.get("methods") instanceof List<?> ms) for (Object mo : ms) if (mo instanceof Map<?, ?> m) methodNames.add(String.valueOf(m.get("origName") != null ? m.get("origName") : m.get("name")));
            if (d.get("fields") instanceof List<?> fs) for (Object fo : fs) if (fo instanceof Map<?, ?> fm) {
                String n = String.valueOf(fm.get("origName") != null ? fm.get("origName") : fm.get("name"));
                if (methodNames.contains(n)) return new String[] {cls, n};
            }
        }
        return null;
    }

    /** The first n classes of the target, walking the package tree one level at a time (KeysIT's way). */
    private static List<String> classesOf(String tid, int n) throws Exception {
        List<String> out = new ArrayList<>();
        ArrayDeque<String> todo = new ArrayDeque<>(List.of(""));
        while (!todo.isEmpty() && out.size() < n) {
            Map<String, Object> level = data(post("symbol.tree", Map.of("target", tid, "prefix", todo.poll(), "limit", 100)));
            for (Object v : level.values()) {
                if (!(v instanceof List<?> rows)) continue;
                for (Object o : rows) {
                    if (!(o instanceof Map<?, ?> r)) continue;
                    if ("package".equals(r.get("kind"))) todo.add(String.valueOf(r.get("prefix")));
                    else if (r.get("addr") != null && String.valueOf(r.get("addr")).startsWith("L") && "class".equals(r.get("kind")) && out.size() < n) out.add(String.valueOf(r.get("addr")));
                }
            }
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> blocksOf(Map<String, Object> packet) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (packet.get("cfg") instanceof Map<?, ?> cfg && cfg.get("blocks") instanceof List<?> l) for (Object o : l) if (o instanceof Map<?, ?> m) out.add((Map<String, Object>) m);
        return out;
    }
    private static Map<String, Map<String, Object>> byId(List<Map<String, Object>> blocks) { Map<String, Map<String, Object>> m = new LinkedHashMap<>(); for (Map<String, Object> b : blocks) m.put(id(b), b); return m; }
    private static String id(Map<String, Object> b) { return String.valueOf(b.get("id")); }
    private static List<String> succ(Map<String, Object> b) { return b == null ? List.of() : strings(b.get("successors")); }
    private static List<String> excOf(Map<String, Object> b) { return b == null ? List.of() : strings(b.get("exceptional")); }
    private static List<String> strings(Object o) { List<String> out = new ArrayList<>(); if (o instanceof List<?> l) for (Object x : l) out.add(String.valueOf(x)); return out; }

    /** Reachability over the packet's blocks along regular and exceptional edges — the selection's definition, computed independently. */
    private static Set<String> reach(Map<String, Map<String, Object>> byId, String start) {
        Set<String> seen = new HashSet<>();
        ArrayDeque<String> todo = new ArrayDeque<>(List.of(start));
        while (!todo.isEmpty()) {
            String n = todo.poll();
            if (!byId.containsKey(n) || !seen.add(n)) continue;
            todo.addAll(succ(byId.get(n)));
            todo.addAll(excOf(byId.get(n)));
        }
        return seen;
    }

    // ------------------------------------------------------------ helpers

    private static String exec(String... cmd) throws Exception {
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int rc = p.waitFor();
        if (rc != 0) throw new IllegalStateException(String.join(" ", cmd) + " exited " + rc + ": " + out);
        return out;
    }

    private static String sha256(byte[] b) throws Exception {
        java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
        StringBuilder sb = new StringBuilder();
        for (byte x : md.digest(b)) sb.append(String.format("%02x", x));
        return sb.toString();
    }

    private static boolean waitReady(String tid) throws Exception {
        long deadline = System.currentTimeMillis() + 300_000;
        while (System.currentTimeMillis() < deadline) {
            Map<String, Object> info = data(post("target.info", Map.of("target", tid)));
            String s = String.valueOf(info.get("state"));
            if ("ready".equals(s)) return true;
            if ("error".equals(s)) { System.out.println("  target error: " + info.get("error")); return false; }
            Thread.sleep(300);
        }
        return false;
    }

    private static Map<String, Object> waitSettled(String id, long ms) throws Exception {
        long deadline = System.currentTimeMillis() + ms;
        Map<String, Object> last = null;
        while (System.currentTimeMillis() < deadline) {
            Object d = post("agent.missions", Map.of()).get("data");
            if (d instanceof List<?> l) for (Object o : l) if (o instanceof Map<?, ?> m && id.equals(m.get("id"))) last = asMap(m);
            if (last != null && !"running".equals(last.get("state")) && !"queued".equals(last.get("state"))) return last;
            Thread.sleep(250);
        }
        return last == null ? Map.of("state", "?") : last;
    }

    /** A request as the STUDENT at the room's page (the edge's browser headers: the room number and the
     *  session's grant). The machine grant (g-kimi) reaches only the facade and the status since the MCP
     *  addendum (Course.MACHINE_OPS) — the page's ops are the student's. */
    private static Map<String, Object> post(String op, Map<String, Object> params) throws Exception {
        return raw(op, params, Map.of("X-WB-Principal", "7", "X-WB-Grant", "g-alice"), null);
    }

    private static Map<String, Object> raw(String op, Map<String, Object> params, Map<String, String> headers, String actor) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("params", params);
        if (actor != null) body.put("actor", actor);
        HttpRequest.Builder rb = HttpRequest.newBuilder(URI.create(BASE + "/api/" + op)).header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(Json.write(body)));
        for (Map.Entry<String, String> h : headers.entrySet()) rb.header(h.getKey(), h.getValue());
        HttpResponse<String> resp = http.send(rb.build(), HttpResponse.BodyHandlers.ofString());
        Map<String, Object> out = new LinkedHashMap<>(Json.parseObject(resp.body()));
        out.put("_status", resp.statusCode());
        return out;
    }

    private static int status(Map<String, Object> r) { return r.get("_status") instanceof Number n ? n.intValue() : -1; }
    private static String code(Map<String, Object> r) { return Boolean.TRUE.equals(r.get("ok")) ? "ok" : r.get("error") instanceof Map<?, ?> e ? String.valueOf(e.get("code")) : "?"; }
    private static Object message(Map<String, Object> r) { return r.get("error") instanceof Map<?, ?> e ? e.get("message") : null; }
    private static Map<String, Object> asMap(Map<?, ?> m) { Map<String, Object> out = new LinkedHashMap<>(); for (Map.Entry<?, ?> e : m.entrySet()) out.put(String.valueOf(e.getKey()), e.getValue()); return out; }
    private static void check(boolean ok, String label) { checks++; System.out.println((ok ? "PASS " : "FAIL ") + label); if (!ok) failures++; }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> data(Map<String, Object> resp) {
        if (!Boolean.TRUE.equals(resp.get("ok"))) throw new IllegalStateException("op failed: " + resp.get("error"));
        Object d = resp.get("data");
        if (!(d instanceof Map)) throw new IllegalStateException("expected map data, got: " + (d == null ? "null" : d.getClass().getSimpleName()));
        return (Map<String, Object>) d;
    }
}
