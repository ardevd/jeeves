package dev.workbench.it;

import com.pnfsoftware.jeb.core.IEnginesContext;
import com.pnfsoftware.jeb.core.IRuntimeProject;
import dev.workbench.engine.jeb.JebCore;
import dev.workbench.registry.Json;
import dev.workbench.server.WorkbenchServer;
import java.io.File;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipFile;

/**
 * Project-key integration test (plain main-class, like the other suites; hermetic home, port 7817).
 *
 * JEB identifies a project by the key it was created under; the key is stored in the .jdb2, and
 * one context keeps one project per key (JebEngine, "PROJECT KEYS"). Until 2026-10-04 the
 * workbench keyed projects wb-t1, wb-t2 … per server run. The test makes databases the way that
 * workbench did — two under wb-t2, through the published API — and then, over the wire:
 *   1. an old database loads alone and its key is written beside it
 *   2. a FRESH open beside it is minted t2 — the reported collision — and gets a key of its own;
 *      while it analyses, another target.open still answers at once (no wait behind the load)
 *   3. an old database with no key on record is refused while an old-style target is open
 *   4. a database opened by itself with no key on record is refused beside any open target;
 *      with its key on record it opens
 *   5. two databases with the same key: an AGENT's open is refused, and the open one stays registered
 *   6. a WRONG record: the load displaces the open target — it is undone, the target is told,
 *      its save is refused, its file is not written, and it closes and reopens (its lock is gone)
 *   7. re-key: a HUMAN's open of a same-key database is not refused — the database is set aside
 *      and the artifact analysed again under a key of its own, the edit history re-applied —
 *      class, comment and locals — BEFORE the new database is written; an open interrupted in
 *      the replay writes nothing; an entry stranded by an out-of-order undo is counted; the two
 *      former same-key targets are open together; without the marker a missing database is a
 *      clean analysis; backend/jdb2-rekey.sh does the same from a shell
 * Needs the engine and the small fixture apk (~/jeb/test_visionocr.apk).
 *
 * Run: gradle keysIT
 */
public final class KeysIT {

    private static int failures = 0, checks = 0;
    private static final HttpClient http = HttpClient.newHttpClient();
    private static final String BASE = "http://127.0.0.1:" + WorkbenchServer.PORT;

    public static void main(String[] args) {
        try {
            run(args);
        } catch (Throwable t) {
            t.printStackTrace(System.out);
            System.out.println("KEYS IT ABORTED after " + checks + " checks: " + t);
            System.exit(1);
        }
    }

    private static void run(String[] args) throws Exception {
        if (System.getenv("WORKBENCH_HOME") == null)
            throw new IllegalStateException("KeysIT needs a hermetic WORKBENCH_HOME — run it with: gradle keysIT");
        File home = new File(System.getenv("WORKBENCH_HOME")).getCanonicalFile();
        File fixture = new File(args.length > 0 ? args[0] : System.getProperty("user.home") + "/jeb/test_visionocr.apk");
        if (!fixture.isFile()) throw new IllegalStateException("no fixture apk at " + fixture);

        // ---- artifacts out of the fixture, and LEGACY databases beside them: A and B created under "wb-t2"
        File art = new File(home, "art");
        art.mkdirs();
        File dexA = new File(art, "classes3.dex"), dexB = new File(art, "classes2.dex"), dexC = new File(art, "classes.dex");
        try (ZipFile z = new ZipFile(fixture)) {
            for (File d : List.of(dexA, dexB, dexC))
                try (InputStream in = z.getInputStream(z.getEntry(d.getName()))) { Files.copy(in, d.toPath()); }
        }
        File legacyA = new File(art, "classes3.jdb2"), legacyB = new File(art, "classes2.jdb2"), bareC = new File(art, "c-by-itself.jdb2");
        IEnginesContext eng = JebCore.engines();
        for (Object[] row : new Object[][] {{dexA, legacyA, "wb-t2"}, {dexB, legacyB, "wb-t2"}, {dexC, bareC, "somebody-elses-project"}}) {
            IRuntimeProject p = eng.createProject((String) row[2]);
            p.processArtifact((File) row[0]);
            if (!eng.saveProject(p.getKey(), ((File) row[1]).getAbsolutePath(), null, null)) throw new IllegalStateException("could not save " + row[1]);
            eng.unloadProject(p.getKey(), true);
        }
        eng.clearUnloadedProject();
        System.out.println("legacy databases made beside their dex: " + legacyA.getName() + " and " + legacyB.getName() + " (both keyed wb-t2), " + bareC.getName());

        WorkbenchServer server = new WorkbenchServer(Path.of("web"));
        server.start();
        System.out.println("server up on " + BASE + " · home " + home);
        data(post("project.open", Map.of("name", "keys")));

        // ---- 1. an old database, alone
        Map<String, Object> a = data(post("target.open", Map.of("path", dexA.getPath())));
        String ta = String.valueOf(a.get("id"));
        check("t1".equals(ta) && waitReady(ta), "the old database A (key wb-t2) loads alone as " + ta);
        int classesA = classes(ta);
        File jdbA = jdb2Of(ta);
        check("wb-t2".equals(record(jdbA)), "its key is written beside it: " + jdbA.getName() + ".key = " + record(jdbA));
        IRuntimeProject regA = eng.getProject("wb-t2");

        // ---- 2. the reported collision: a fresh open is minted t2 while a project keyed wb-t2 is loaded
        Map<String, Object> fx = post("target.open", Map.of("path", fixture.getPath()));
        String tf = Boolean.TRUE.equals(fx.get("ok")) ? String.valueOf(data(fx).get("id")) : null;
        // ---- 3. (inside 2's analysis) an old database with no key on record, while an old-style target is open
        long t0 = System.currentTimeMillis();
        Map<String, Object> b1 = post("target.open", Map.of("path", dexB.getPath()));
        long refusedIn = System.currentTimeMillis() - t0;
        String tfState = tf == null ? "?" : String.valueOf(data(post("target.info", Map.of("target", tf))).get("state"));
        check("E_BUSY".equals(code(b1)) && String.valueOf(message(b1)).contains("not on record") && String.valueOf(message(b1)).contains(ta + " (")
                        && String.valueOf(message(b1)).contains("backend/jdb2-keys.sh '"),
                "old database B, key unknown, while " + ta + " (old-style key) is open → E_BUSY naming it and the script");
        check(refusedIn < 1500, "that answer came in " + refusedIn + " ms while the fresh target was " + tfState + " — target.open does not wait behind another target's analysis");
        check("t2".equals(tf) && waitReady(tf), "a fresh open beside A is minted t2 and analyses to ready (was: The project already exists: key=wb-t2)");
        File jdbF = jdb2Of(tf);
        check(String.valueOf(record(jdbF)).matches("wb-t2-[0-9a-f]{16}"), "the new database's key is its own and on record: " + record(jdbF));
        check(classes(ta) == classesA && classes(tf) > 0 && classes(tf) != classesA, "both targets answer with their own classes (" + classesA + " / " + classes(tf) + ")");
        check(targetIds().equals(List.of(ta, tf)) && eng.getProject("wb-t2") == regA, "the refused open minted nothing and A is still the project JEB has under wb-t2");

        // ---- with no old-style target open, B loads and its key goes on record
        data(post("target.close", Map.of("target", ta)));

        // ---- 4. a database opened by itself, no key on record
        Map<String, Object> c1 = post("target.open", Map.of("path", bareC.getPath()));
        check("E_BUSY".equals(code(c1)) && String.valueOf(message(c1)).contains("opened by itself") && String.valueOf(message(c1)).contains("backend/jdb2-keys.sh '" + bareC.getPath() + "'")
                        && String.valueOf(message(c1)).contains("open now: " + tf + " ("),
                "a bare .jdb2 with no key on record, beside an open target (unique key) → E_BUSY with the command that records it and the targets that are open");
        Files.writeString(new File(bareC.getPath() + ".key").toPath(), "somebody-elses-project\n");   // what backend/jdb2-keys.sh <file> writes
        Map<String, Object> c2 = data(post("target.open", Map.of("path", bareC.getPath())));
        String tc = String.valueOf(c2.get("id"));
        check(waitReady(tc), "with its key on record it opens beside the others (" + tc + ")");
        data(post("target.close", Map.of("target", tc)));

        Map<String, Object> b2 = data(post("target.open", Map.of("path", dexB.getPath())));
        String tb = String.valueOf(b2.get("id"));
        check(waitReady(tb), "with A closed (only a unique-key target open), B loads as " + tb);
        int classesB = classes(tb);
        File jdbB = jdb2Of(tb);
        check(classesB > 0 && classesB != classesA && "wb-t2".equals(record(jdbB)), "B answers with its own classes (" + classesB + ") and its key is on record");
        IRuntimeProject regB = eng.getProject("wb-t2");

        // ---- 5. the same key twice
        Map<String, Object> a2 = postAs("llm:it", "target.open", Map.of("path", dexA.getPath()));
        check("E_BUSY".equals(code(a2)) && String.valueOf(message(a2)).contains("same internal JEB project key") && String.valueOf(message(a2)).contains(tb + " (")
                        && String.valueOf(message(a2)).contains("If YOU opened " + tb) && String.valueOf(message(a2)).contains("takes the operator")
                        && !String.valueOf(message(a2)).contains("jdb2-rekey") && jdbA.isFile(),
                "an agent opens A while B (same key wb-t2) is open → E_BUSY naming " + tb + " and the key, not sending it to close the operator's target or to a script");
        check(targetIds().equals(List.of(tf, tb)) && eng.getProject("wb-t2") == regB && regB != regA, "nothing was minted and B is still the project JEB has under wb-t2");
        // the user closes B and opens A at once: B is on its way out — A waits for it and LOADS, no re-key
        String hashA0 = sha1(jdbA);
        data(post("target.close", Map.of("target", tb, "async", true)));
        Map<String, Object> aRace = data(post("target.open", Map.of("path", dexA.getPath(), "rekey", true)));
        String taRace = String.valueOf(aRace.get("id"));
        String[] asideA0 = jdbA.getParentFile().list((d, n) -> n.contains(".rekeyed-"));
        check(aRace.get("rekeyed") == null && waitReady(taRace) && classes(taRace) == classesA && "wb-t2".equals(record(jdbA)) && (asideA0 == null || asideA0.length == 0),
                "B closed and A opened at once (the workbench's gesture): A waits for the close and loads its own database — no re-key");
        data(post("target.close", Map.of("target", taRace)));
        check(hashA0.equals(sha1(jdbA)), "A's database is byte-identical after that");
        tb = String.valueOf(data(post("target.open", Map.of("path", dexB.getPath()))).get("id"));
        check(waitReady(tb) && classes(tb) == classesB, "B is open again (" + tb + ") for what follows");

        // ---- 6. a WRONG record lets the load through: found after the load, undone, and the displaced target can recover
        Files.writeString(new File(jdbA.getPath() + ".key").toPath(), "wb-stale\n");
        String hashB = sha1(jdbB);
        Map<String, Object> a3 = post("target.open", Map.of("path", dexA.getPath()));
        String tx = Boolean.TRUE.equals(a3.get("ok")) ? String.valueOf(data(a3).get("id")) : null;
        Map<String, Object> xInfo = tx == null ? Map.of() : waitSettled(tx);
        check("error".equals(xInfo.get("state")) && String.valueOf(xInfo.get("error")).contains("The open was undone") && String.valueOf(xInfo.get("error")).contains(tb + " ("),
                "A with a wrong key on record, while B is open: the load is undone and the target errors naming " + tb + " — " + xInfo.get("error"));
        check("wb-t2".equals(record(jdbA)), "the record is corrected: " + record(jdbA));
        check(eng.getProject("wb-t2") == null && eng.getProjects().size() == 1, "JEB holds nothing under wb-t2 now (the newcomer is out; B is displaced), one project left: the fresh one");
        Map<String, Object> bInfo = data(post("target.info", Map.of("target", tb)));
        check(String.valueOf(bInfo.get("warning")).contains("cannot be saved") && classes(tb) == classesB, "B still reads and says so itself: " + bInfo.get("warning"));
        Map<String, Object> svB = post("target.save", Map.of("target", tb));
        check("E_BUSY".equals(code(svB)) && String.valueOf(message(svB)).contains("no longer the one JEB has under its key") && hashB.equals(sha1(jdbB)),
                "target.save of B is refused and its file is byte-identical");
        if (tx != null && targetIds().contains(tx)) post("target.close", Map.of("target", tx));
        Map<String, Object> a4 = postAs("llm:it", "target.open", Map.of("path", dexA.getPath()));
        check("E_BUSY".equals(code(a4)) && String.valueOf(message(a4)).contains(tb + " ("), "A again (an agent): refused up front now (B's handle still counts, though JEB no longer lists it)");
        data(post("target.close", Map.of("target", tb)));
        check(!new File(jdbB.getPath() + ".lock").exists() && hashB.equals(sha1(jdbB)), "B closes: its lock file is gone and its file is still byte-identical");
        Map<String, Object> b3 = data(post("target.open", Map.of("path", dexB.getPath())));
        tb = String.valueOf(b3.get("id"));
        check(waitReady(tb) && classes(tb) == classesB && data(post("target.info", Map.of("target", tb))).get("warning") == null,
                "B reopens (" + tb + ") with its classes and no warning");

        // ---- 7. re-key: edits on B, saved; set aside; analysed again under its own key with the history re-applied
        List<String> two = firstClasses(tb, 2);
        String cls = two.get(0), cls2 = two.get(1);
        String method = null, localName = null;
        int localRenames = 0;
        for (Object mo : (List<?>) data(post("code.outline", Map.of("target", tb, "class", cls))).getOrDefault("methods", List.of())) {
            String m = String.valueOf(((Map<?, ?>) mo).get("addr"));
            Map<String, Object> la = post("locals.auto", Map.of("target", tb, "symbol", m, "all", true));
            List<?> rn = Boolean.TRUE.equals(la.get("ok")) ? (List<?>) data(la).getOrDefault("renames", List.of()) : List.of();
            if (rn.isEmpty()) continue;
            method = m; localRenames = rn.size(); localName = String.valueOf(((Map<?, ?>) rn.get(0)).get("name"));
            break;
        }
        data(post("comment", Map.of("target", tb, "symbol", cls, "text", "keys IT: survives a re-key")));
        String renamed = String.valueOf(data(post("rename", Map.of("target", tb, "symbol", cls, "name", "KeysItRenamed"))).get("newAddr"));
        String methodAfter = method == null ? null : renamed + method.substring(method.indexOf("->"));
        check(renamed.contains("KeysItRenamed"), "edits on B: class " + cls + " renamed (" + renamed + "), commented" + (method == null ? ", no method with generated locals found" : ", " + localRenames + " local(s) of " + method + " named (" + localName + ")"));
        // an edit stranded by an out-of-order undo: rename a class, comment on it under the NEW name, undo the rename only
        Map<String, Object> r2 = data(post("rename", Map.of("target", tb, "symbol", cls2, "name", "KeysItUndone")));
        data(post("comment", Map.of("target", tb, "symbol", String.valueOf(r2.get("newAddr")), "text", "keys IT: recorded under a name that is undone next")));
        Map<String, Object> un = post("history.undo", Map.of("target", tb, "id", r2.get("historyId")));
        check(Boolean.TRUE.equals(un.get("ok")) && Boolean.TRUE.equals(post("symbol.resolve", Map.of("target", tb, "symbol", cls2)).get("ok")),
                "a second class renamed, commented under its new name, and the rename alone undone (" + cls2 + " is back)");
        check(Boolean.TRUE.equals(post("target.save", Map.of("target", tb)).get("ok")) && Boolean.TRUE.equals(post("target.save", Map.of("target", tf)).get("ok")),
                "target.save of B and of the fresh target → ok");
        data(post("target.close", Map.of("target", tb)));
        File marker = new File(jdbB.getPath() + ".replay");
        Map<String, Object> a5 = data(post("target.open", Map.of("path", dexA.getPath())));
        String ta2 = String.valueOf(a5.get("id"));
        check(waitReady(ta2) && classes(ta2) == classesA, "A (wb-t2) opens: " + ta2);
        // who may trigger a re-key: only the workbench's own gesture (rekey: true) from a human actor
        String hashB0 = sha1(jdbB);
        Map<String, Object> bAgent = postAs("llm:it", "target.open", Map.of("path", dexB.getPath(), "rekey", true));
        Map<String, Object> bPlain = post("target.open", Map.of("path", dexB.getPath()));
        Map<String, Object> bTool = postAs("human:john", "tool.call", Map.of("tool", "target", "args", Map.of("action", "open", "path", dexB.getPath(), "rekey", true)));
        String[] noAside = jdbB.getParentFile().list((d, n) -> n.contains(".rekeyed-"));
        check("E_BUSY".equals(code(bAgent)) && "E_BUSY".equals(code(bPlain)) && "E_BUSY".equals(code(bTool))
                        && hashB0.equals(sha1(jdbB)) && "wb-t2".equals(record(jdbB)) && !marker.exists() && (noAside == null || noAside.length == 0),
                "B beside A is refused for an agent (even asking for a re-key), for a plain open, and for an MCP client registered as human:john — nothing on disk changed");
        // the workbench's gesture is carried out: B is re-keyed on the spot — this time to the end, in one open
        int toReplay = 3 + localRenames;   // comment + rename of the class, the locals, and the comment stranded by the undone rename
        Map<String, Object> bk = data(post("target.open", Map.of("path", dexB.getPath(), "rekey", true)));
        String tbk = String.valueOf(bk.get("id"));
        Map<?, ?> rk = bk.get("rekeyed") instanceof Map<?, ?> m ? m : Map.of();
        File asideB = new File(String.valueOf(rk.get("asidePath")));
        check("wb-t2".equals(rk.get("key")) && List.of(ta2 + " (classes3.dex)").equals(rk.get("with")) && Integer.valueOf(toReplay).equals(rk.get("edits"))
                        && asideB.isFile() && asideB.getName().equals(rk.get("aside")) && hashB0.equals(sha1(asideB)) && "wb-t2".equals(record(asideB))
                        && !jdbB.exists() && record(jdbB) == null && marker.isFile()
                        && String.valueOf(bk.get("note")).contains("Analysed again instead of loaded") && String.valueOf(bk.get("note")).contains(ta2 + " (classes3.dex)")
                        && String.valueOf(bk.get("note")).contains(asideB.getPath()) && String.valueOf(bk.get("note")).contains("UNDO RE-KEY"),
                "the workbench's open of B beside A is carried out: the old database is set aside whole with its key record, and the answer says what, why and where — " + bk.get("note"));
        Map<String, Object> bkInfo = waitSettled(tbk);
        int applied = bkInfo.get("recoveredEdits") instanceof Number n1 ? n1.intValue() : -1, lost = bkInfo.get("replayFailed") instanceof Number n2 ? n2.intValue() : 0;
        check("ready".equals(bkInfo.get("state")) && applied == toReplay - 1 && lost == 1 && Boolean.FALSE.equals(bkInfo.get("dirty"))
                        && String.valueOf(record(jdbB)).matches("wb-t\\d+-[0-9a-f]{16}") && !marker.exists() && classes(ta2) == classesA && classes(tbk) == classesB,
                "that one open ends ready beside A: " + applied + " of " + toReplay + " edits re-applied, " + lost + " counted as not applied, saved, key " + record(jdbB));
        Map<?, ?> rowRk = null;
        for (Object o : (List<?>) data(post("workspace.state", Map.of())).get("targets"))
            if (o instanceof Map<?, ?> row && tbk.equals(row.get("id")) && row.get("rekeyed") instanceof Map<?, ?> m) rowRk = m;
        check(rowRk != null && asideB.getName().equals(rowRk.get("aside")), "the target's row in workspace.state says the old database is kept (for its chip)");
        // … and the way back: target.rekeyUndo
        Map<String, Object> undoAgent = postAs("llm:it", "target.rekeyUndo", Map.of("target", tbk));
        check("E_SCOPE".equals(code(undoAgent)) && jdbB.isFile() && asideB.isFile(), "an agent cannot undo a re-key");
        Map<String, Object> undo = data(post("target.rekeyUndo", Map.of("target", tbk)));
        check(Boolean.TRUE.equals(undo.get("closed")) && !targetIds().contains(tbk) && hashB0.equals(sha1(jdbB)) && "wb-t2".equals(record(jdbB))
                        && !asideB.exists() && !marker.exists() && !new File(jdbB.getPath() + ".lock").exists(),
                "UNDO RE-KEY: the target is closed and the old database is back, byte-identical, with its key record — " + undo.get("note"));
        // again, to interrupt it this time
        Map<String, Object> bx = data(post("target.open", Map.of("path", dexB.getPath(), "rekey", true)));
        String tbx = String.valueOf(bx.get("id"));
        check(bx.get("rekeyed") instanceof Map<?, ?> && !jdbB.exists() && marker.isFile(), "opened beside A again: re-keyed again (" + tbx + ")");
        // an open INTERRUPTED in the middle of the replay must leave no database at all (the next open starts over)
        boolean inReplay = false;
        for (long end = System.currentTimeMillis() + 120_000; System.currentTimeMillis() < end && !inReplay; Thread.sleep(50)) {
            Map<String, Object> i = data(post("target.info", Map.of("target", tbx)));
            if (!"loading".equals(i.get("state"))) break;
            inReplay = i.get("classes") instanceof Number c && c.intValue() > 0;   // analysed, not yet ready: the replay is running
        }
        Map<String, Object> svx = post("target.save", Map.of("target", tbx));
        data(post("target.close", Map.of("target", tbx)));
        Thread.sleep(1500);
        check(inReplay && "E_BUSY".equals(code(svx)) && String.valueOf(message(svx)).contains("still being re-applied") && !jdbB.exists() && marker.isFile() && !new File(jdbB.getPath() + ".lock").exists(),
                "B closed in the middle of its replay (" + String.valueOf(bx.get("id")) + "): a save in that window is refused, and no database was written — the marker stays for the next open");
        Map<String, Object> b4 = data(post("target.open", Map.of("path", dexB.getPath())));
        String tb2 = String.valueOf(b4.get("id"));
        check(b4.get("rekeyed") instanceof Map<?, ?> m4 && Boolean.TRUE.equals(m4.get("resumed")) && String.valueOf(b4.get("note")).contains("Finishing a re-key"),
                "the next open (a plain one) finishes that re-key and says so — " + b4.get("note"));
        boolean bReady = waitReady(tb2);
        Map<String, Object> b4Info = data(post("target.info", Map.of("target", tb2)));
        check(bReady && classes(tb2) == classesB && classes(ta2) == classesA && String.valueOf(record(jdbB)).matches("wb-t\\d+-[0-9a-f]{16}"),
                "B opens BESIDE A — analysed again under a key of its own (" + record(jdbB) + "); both answer with their own classes");
        int expected = 2 + localRenames;
        check(Integer.valueOf(expected).equals(b4Info.get("recoveredEdits")) && Boolean.FALSE.equals(b4Info.get("dirty")) && jdbB.isFile() && !marker.exists(),
                "its history was re-applied (" + b4Info.get("recoveredEdits") + " of " + (expected + 1) + " entries) before the database was written (dirty: " + b4Info.get("dirty") + ", marker gone)");
        check(Integer.valueOf(1).equals(b4Info.get("replayFailed")),
                "the entry stranded by the undone rename is counted, not dropped in silence: replayFailed " + b4Info.get("replayFailed"));
        Map<String, Object> rs = post("symbol.resolve", Map.of("target", tb2, "symbol", renamed));
        check(Boolean.TRUE.equals(rs.get("ok")), "the class answers under its name again: " + renamed);
        if (method != null) {
            String locals = String.valueOf(post("locals.list", Map.of("target", tb2, "symbol", methodAfter)).get("data"));
            Map<String, Object> rm = post("symbol.resolve", Map.of("target", tb2, "symbol", methodAfter));
            check(locals.contains(localName) && Boolean.TRUE.equals(rm.get("ok")) && !String.valueOf(rm.get("data")).contains(localName + "("),
                    "the local is named " + localName + " again, and the method was not renamed after it");
        }
        Map<String, Object> ch = data(post("target.changes", Map.of("target", tb2)));
        check(Integer.valueOf(0).equals(ch.get("symbols")), "nothing is left unsaved: target.changes " + ch.get("symbols"));
        data(post("target.close", Map.of("target", tb2)));
        Map<String, Object> b5 = data(post("target.open", Map.of("path", dexB.getPath())));
        String tb3 = String.valueOf(b5.get("id"));
        Map<String, Object> b5Info = waitSettled(tb3);
        check("ready".equals(b5Info.get("state")) && b5Info.get("recoveredEdits") == null
                        && Boolean.TRUE.equals(post("symbol.resolve", Map.of("target", tb3, "symbol", renamed)).get("ok")),
                "closed and reopened: the names are IN the new database (nothing replayed this time)");
        // without the marker a missing database is a clean analysis, as it always was
        data(post("target.close", Map.of("target", tb3)));
        Files.delete(jdbB.toPath());
        Files.deleteIfExists(new File(jdbB.getPath() + ".key").toPath());
        Map<String, Object> b6 = data(post("target.open", Map.of("path", dexB.getPath())));
        String tb4 = String.valueOf(b6.get("id"));
        Map<String, Object> b6Info = waitSettled(tb4);
        check("ready".equals(b6Info.get("state")) && b6Info.get("recoveredEdits") == null
                        && !Boolean.TRUE.equals(post("symbol.resolve", Map.of("target", tb4, "symbol", renamed)).get("ok"))
                        && Boolean.TRUE.equals(post("symbol.resolve", Map.of("target", tb4, "symbol", cls)).get("ok")),
                "a database deleted by hand (no marker) is analysed clean: no history is replayed");

        // ---- 8. the same from a shell: backend/jdb2-rekey.sh on A (no history), then a plain open
        data(post("target.close", Map.of("target", ta2)));
        Process sh = new ProcessBuilder("backend/jdb2-rekey.sh", jdbA.getParent()).redirectErrorStream(true).start();
        String shOut = new String(sh.getInputStream().readAllBytes());
        int shExit = sh.waitFor();
        File markerA = new File(jdbA.getPath() + ".replay");
        String[] asideA = jdbA.getParentFile().list((d, n) -> n.startsWith(jdbA.getName() + ".rekeyed-") && !n.endsWith(".key"));
        check(shExit == 0 && !jdbA.exists() && record(jdbA) == null && asideA != null && asideA.length == 1 && markerA.isFile()
                        && "wb-t2".equals(record(new File(jdbA.getParentFile(), asideA[0]))),
                "backend/jdb2-rekey.sh sets A's database aside with its key record and leaves the marker — " + shOut.strip().replace("\n", " | "));
        Map<String, Object> a6 = data(post("target.open", Map.of("path", dexA.getPath())));
        String ta3 = String.valueOf(a6.get("id"));
        check(waitReady(ta3) && classes(ta3) == classesA && String.valueOf(record(jdbA)).matches("wb-t\\d+-[0-9a-f]{16}") && !markerA.exists()
                        && a6.get("rekeyed") instanceof Map<?, ?> m6 && Boolean.TRUE.equals(m6.get("resumed")) && "wb-t2".equals(m6.get("key")),
                "A opens analysed again under a key of its own (" + record(jdbA) + "), and the answer tells of the script's re-key; the marker is gone");

        // ---- 9. the store's .jdb2 opened by itself, then its apk from the original path: the SAME database — go to it
        data(post("target.close", Map.of("target", ta3)));
        Map<String, Object> bare = data(post("target.open", Map.of("path", jdbA.getPath())));
        String tBare = String.valueOf(bare.get("id"));
        String hashA9 = waitReady(tBare) ? sha1(jdbA) : "?";
        Map<String, Object> viaApk = post("target.open", Map.of("path", dexA.getPath(), "rekey", true));
        String[] asideA9 = jdbA.getParentFile().list((d, n) -> n.contains(".rekeyed-") && !n.endsWith(".key"));
        check(Boolean.TRUE.equals(viaApk.get("ok")) && tBare.equals(data(viaApk).get("id")) && Boolean.TRUE.equals(data(viaApk).get("deduped")) && data(viaApk).get("rekeyed") == null
                        && hashA9.equals(sha1(jdbA)) && asideA9 != null && asideA9.length == 1,
                "A's database is open by itself (" + tBare + "); opening A's dex lands on that target — no clash, no second re-key, the file untouched");

        System.out.println();
        if (failures > 0) {
            System.out.println("KEYS IT FAILED: " + failures + "/" + checks + " checks failed");
            System.exit(1);
        }
        System.out.println("KEYS IT PASSED: " + checks + " checks green");
        System.exit(0);
    }

    // ------------------------------------------------------------ helpers

    private static String record(File jdb2) throws Exception {
        File k = new File(jdb2.getPath() + ".key");
        return k.isFile() ? Files.readString(k.toPath()).trim() : null;
    }

    private static File jdb2Of(String tid) throws Exception {
        return new File(String.valueOf(data(post("target.info", Map.of("target", tid))).get("jdb2")));
    }

    private static String sha1(File f) throws Exception {
        java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-1");
        StringBuilder sb = new StringBuilder();
        for (byte b : md.digest(Files.readAllBytes(f.toPath()))) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    /** The first n classes of the target, walking the package tree one level at a time. */
    private static List<String> firstClasses(String tid, int n) throws Exception {
        List<String> out = new ArrayList<>();
        java.util.ArrayDeque<String> todo = new java.util.ArrayDeque<>(List.of(""));
        while (!todo.isEmpty() && out.size() < n) {
            Map<String, Object> level = data(post("symbol.tree", Map.of("target", tid, "prefix", todo.poll(), "limit", 50)));
            for (Object v : level.values()) {
                if (!(v instanceof List<?> rows)) continue;
                for (Object o : rows) {
                    if (!(o instanceof Map<?, ?> r)) continue;
                    if ("package".equals(r.get("kind"))) todo.add(String.valueOf(r.get("prefix")));
                    else if (r.get("addr") != null && String.valueOf(r.get("addr")).startsWith("L") && "class".equals(r.get("kind")) && out.size() < n) out.add(String.valueOf(r.get("addr")));
                }
            }
        }
        if (out.size() < n) throw new IllegalStateException("fewer than " + n + " classes found in " + tid);
        return out;
    }

    /** Poll until the target is ready or failed; the last info. */
    private static Map<String, Object> waitSettled(String tid) throws Exception {
        long deadline = System.currentTimeMillis() + 300_000;
        Map<String, Object> info = Map.of();
        while (System.currentTimeMillis() < deadline) {
            info = data(post("target.info", Map.of("target", tid)));
            String st = String.valueOf(info.get("state"));
            if ("ready".equals(st) || "error".equals(st)) return info;
            Thread.sleep(300);
        }
        return info;
    }

    private static List<String> targetIds() throws Exception {
        List<String> out = new ArrayList<>();
        for (Object o : (List<?>) data(post("workspace.state", Map.of())).get("targets")) out.add(String.valueOf(((Map<?, ?>) o).get("id")));
        return out;
    }

    private static int classes(String tid) throws Exception {
        Object c = data(post("target.info", Map.of("target", tid))).get("classes");
        return c instanceof Number num ? num.intValue() : -1;
    }

    private static boolean waitReady(String tid) throws Exception {
        long deadline = System.currentTimeMillis() + 300_000;
        while (System.currentTimeMillis() < deadline) {
            Map<String, Object> info = data(post("target.info", Map.of("target", tid)));
            String st = String.valueOf(info.get("state"));
            if ("ready".equals(st)) return true;
            if ("error".equals(st)) { System.out.println("  target error: " + info.get("error")); return false; }
            Thread.sleep(300);
        }
        return false;
    }

    private static void check(boolean ok, String label) {
        checks++;
        System.out.println((ok ? "PASS " : "FAIL ") + label);
        if (!ok) failures++;
    }

    private static String code(Map<String, Object> resp) {
        if (Boolean.TRUE.equals(resp.get("ok"))) return "ok";
        return resp.get("error") instanceof Map<?, ?> e ? String.valueOf(e.get("code")) : "?";
    }

    private static Object message(Map<String, Object> resp) {
        return resp.get("error") instanceof Map<?, ?> e ? e.get("message") : null;
    }

    private static Map<String, Object> post(String op, Map<String, Object> params) throws Exception {
        return postAs("human", op, params);
    }

    private static Map<String, Object> postAs(String actor, String op, Map<String, Object> params) throws Exception {
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
        Object d = resp.get("data");
        if (!(d instanceof Map))
            throw new IllegalStateException("expected map data, got: " + (d == null ? "null" : d.getClass().getSimpleName()));
        return (Map<String, Object>) d;
    }
}
