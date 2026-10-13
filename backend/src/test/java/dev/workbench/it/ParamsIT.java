package dev.workbench.it;

import dev.workbench.registry.Json;
import dev.workbench.server.WorkbenchServer;
import java.io.File;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Parameter-metadata and DEX-view fidelity IT on the JEB engine (plain main-class like the other
 * suites; hermetic home, port 7821). PROBE STAGE: opens the constructed fixture and prints what the
 * engine answers — the assertions follow once the engine's new surfaces exist.
 * Run: gradle paramsIT
 */
public final class ParamsIT {

    private static int failures = 0, checks = 0;
    private static final HttpClient http = HttpClient.newHttpClient();
    private static final String BASE = "http://127.0.0.1:" + WorkbenchServer.PORT;

    public static void main(String[] args) {
        try {
            if ("api".equals(System.getProperty("params.probe"))) { probeApi(); System.exit(0); }
            run(args);
        }
        catch (Throwable t) { t.printStackTrace(System.out); System.out.println("PARAMS IT ABORTED after " + checks + " checks: " + t); System.exit(1); }
    }

    /** The direct JEB API probe (gradle paramsIT -Pprobe=api): what the engine itself says about
     *  parameter names, original-name restoration and switch payload addressing on the fixture. */
    private static void probeApi() throws Exception {
        File fixture = new File("backend/src/test/fixtures/params/params-fixture.dex");
        com.pnfsoftware.jeb.core.IEnginesContext eng = dev.workbench.engine.jeb.JebCore.engines();
        com.pnfsoftware.jeb.core.IRuntimeProject p = eng.createProject("params-probe");
        p.processArtifact(fixture);
        java.util.List<com.pnfsoftware.jeb.core.units.code.android.IDexUnit> units = com.pnfsoftware.jeb.core.RuntimeProjectUtil.findUnitsByType(p, com.pnfsoftware.jeb.core.units.code.android.IDexUnit.class, false);
        com.pnfsoftware.jeb.core.units.code.android.IDexUnit dex = units.get(0);
        if (!dex.isProcessed()) dex.process();
        com.pnfsoftware.jeb.core.units.code.IDecompilerUnit d0 = com.pnfsoftware.jeb.core.util.DecompilerHelper.getDecompiler(dex);
        com.pnfsoftware.jeb.core.units.code.android.IDexDecompilerUnit dec = (com.pnfsoftware.jeb.core.units.code.android.IDexDecompilerUnit) d0;
        System.out.println("dex unit: " + dex.getName() + " · decompiler: " + (dec == null ? "none" : dec.getName()));
        // 1. properties whose names smell like parameter naming
        for (var pair : java.util.List.of(new Object[] { "dex", dex.getPropertyManager() }, new Object[] { "decompiler", dec == null ? null : dec.getPropertyManager() })) {
            com.pnfsoftware.jeb.core.properties.IPropertyManager pm = (com.pnfsoftware.jeb.core.properties.IPropertyManager) pair[1];
            if (pm == null) continue;
            System.out.println("--- properties of " + pair[0]);
            dumpProps(pm, pm.getPropertyDefinitionManager(), "");
        }
        // 2. parameter names as JEB holds them
        for (String sig : java.util.List.of("Lfixture/Params;->permuted(Lfixture/Intent;Lfixture/IntentSender;II)V", "Lfixture/Params;->debugOnly(ILjava/lang/String;)I", "Lfixture/Params;->conflict(Ljava/lang/String;Z)V", "Lfixture/Params;->abstractNamed(Ljava/lang/String;I)V", "Lfixture/Params;->staleCount(II)I", "Lfixture/Params;->absent(I)I")) {
            com.pnfsoftware.jeb.core.units.code.android.dex.IDexMethod m = dex.getMethod(sig);
            System.out.println("--- " + sig + " index=" + (m == null ? "?" : m.getIndex()));
            if (m == null) continue;
            int n = m.getPrototype().getParameterTypes().length;
            StringBuilder sb = new StringBuilder("  getParameterName: ");
            for (int i = 0; i < n + 1; i++) { try { sb.append(i).append('=').append(m.getParameterName(i)).append(' '); } catch (Exception e) { sb.append(i).append("=EXC(").append(e.getClass().getSimpleName()).append(") "); } }
            System.out.println(sb);
            var md = m.getData(); var ci = md == null ? null : md.getCodeItem(); var di = ci == null ? null : ci.getDebugInfo();
            System.out.println("  debugInfo: " + (di == null ? "none" : java.util.Arrays.toString(di.getParameterNameIndexes())));
            var cls = dex.getClass(sig.substring(0, sig.indexOf("->")));
            var ad = cls == null ? null : cls.getAnnotationsDirectory();
            var items = ad == null ? null : ad.getMethodAnnotations(m.getIndex());
            System.out.println("  method annotations: " + (items == null ? "none" : items.size()));
            if (items != null) for (var it : items) { var a = it.getAnnotation(); var t = dex.getType(a.getTypeIndex()); System.out.println("    " + (t == null ? "?" : t.getSignature(false)) + " vis=" + it.getVisibility()); for (var el : a.getElements()) { var v = el.getValue(); System.out.println("      " + el.getName(dex) + " type=" + v.getType() + " array=" + (v.getType() == com.pnfsoftware.jeb.core.units.code.android.dex.IDexValue.VALUE_ARRAY ? describe(dex, v.getArray()) : "-")); } }
        }
        // 3. setParameterName and its effect on the decompiled text
        var pm = dex.getMethod("Lfixture/Params;->permuted(Lfixture/Intent;Lfixture/IntentSender;II)V");
        String cls = "Lfixture/Params;";
        if (dec != null) {
            boolean ok0 = dec.decompileClass(cls);
            System.out.println("--- decompile before: ok=" + ok0 + "\n" + firstLines(dec.getDecompiledMethodText(pm.getAddress()), 2));
            boolean set = pm.setParameterName(0, "probeFirst", true, true);
            System.out.println("setParameterName(0, probeFirst) → " + set + " · getParameterName(0)=" + pm.getParameterName(0) + " · address=" + pm.getAddress() + " renamed=" + pm.isRenamed());
            boolean ok1 = dec.decompileClass(cls);
            System.out.println("decompile after (no invalidation): ok=" + ok1 + "\n" + firstLines(dec.getDecompiledMethodText(pm.getAddress()), 2));
            try { java.lang.reflect.Method rm = dec.getClass().getMethod("removeDecompilation", String.class); Object r = rm.invoke(dec, cls); System.out.println("removeDecompilation(class) → " + r); } catch (Exception e) { System.out.println("removeDecompilation: " + e); }
            boolean ok2 = dec.decompileClass(cls);
            System.out.println("decompile after removeDecompilation: ok=" + ok2 + "\n" + firstLines(dec.getDecompiledMethodText(pm.getAddress()), 2));
            boolean conflict = pm.setParameterName(1, "probeFirst", true, true);
            System.out.println("setParameterName(1, probeFirst) with failOnNameConflict → " + conflict + " · names now 0=" + pm.getParameterName(0) + " 1=" + pm.getParameterName(1));
            boolean clear = pm.setParameterName(0, null, true, true);
            System.out.println("setParameterName(0, null) → " + clear + " · getParameterName(0)=" + pm.getParameterName(0));
            boolean empty = pm.setParameterName(0, "", true, true);
            System.out.println("setParameterName(0, \"\") → " + empty + " · getParameterName(0)=" + pm.getParameterName(0));
            try { java.lang.reflect.Method rm = dec.getClass().getMethod("removeDecompilation", String.class); rm.invoke(dec, cls); } catch (Exception ignored) {}
            dec.decompileClass(cls);
            System.out.println("decompile after clearing: \n" + firstLines(dec.getDecompiledMethodText(pm.getAddress()), 2));
            boolean back = pm.setParameterName(0, "intentSender", true, true);
            System.out.println("setParameterName(0, intentSender) back → " + back);
        }
        // 4. original-name restoration: a class renamed, then set back to its original; a digit-prefixed name as a NEW name
        var c = dex.getClass("Lfixture/Intent;");
        System.out.println("--- class " + c.getAddress() + " original=" + c.getName(false) + " renamed=" + c.isRenamed());
        System.out.println("setName(CourseIntent) → " + c.setName("CourseIntent") + " · addr=" + c.getAddress() + " renamed=" + c.isRenamed());
        System.out.println("setName(1cq) (a digit-prefixed NEW name) → " + c.setName("1cq") + " · name=" + c.getName(true) + " addr=" + c.getAddress());
        System.out.println("setName(Intent) (the original) → " + c.setName("Intent") + " · name=" + c.getName(true) + " renamed=" + c.isRenamed() + " addr=" + c.getAddress());
        System.out.println("setName(CourseIntent) again → " + c.setName("CourseIntent") + "; setName(null) → " + c.setName(null) + " · name=" + c.getName(true) + " renamed=" + c.isRenamed());
        System.out.println("setName(CourseIntent) again → " + c.setName("CourseIntent") + "; setName(\"\") → " + c.setName("") + " · name=" + c.getName(true) + " renamed=" + c.isRenamed());
        if (c.isRenamed()) c.setName("Intent");
        // 5. switch data and branch addresses
        for (String sig : java.util.List.of("Lfixture/Branches;->switches(I)I", "Lfixture/Branches;->loop(I)I", "Lfixture/Branches;->arrays()[I")) {
            var m = dex.getMethod(sig);
            System.out.println("--- " + sig);
            for (var insn : m.getData().getCodeItem().getInstructions()) {
                StringBuilder sb = new StringBuilder(String.format("  %04x %-28s size=%d primaryBranch=%s", insn.getOffset(), insn.format(null).trim(), insn.getSize(), Long.toHexString(insn.getPrimaryBranchAddress())));
                for (int i = 0; i < insn.getParameterCount(); i++) sb.append(" [").append(i).append(":t").append(insn.getParameterType(i)).append("=").append(insn.getParameterValue(i)).append("]");
                var sd = insn.getSwitchData();
                if (sd != null) { sb.append(" switchData offset=").append(Integer.toHexString(sd.getOffset())).append(" elements="); for (int[] e : sd.getElements()) sb.append("(").append(e[0]).append("→").append(Integer.toHexString(e[1])).append(")"); }
                System.out.println(sb);
            }
        }
        eng.unloadProject(p.getKey(), true);
    }

    private static void dumpProps(com.pnfsoftware.jeb.core.properties.IPropertyManager pm, com.pnfsoftware.jeb.core.properties.IPropertyDefinitionManager dm, String prefix) {
        if (dm == null) return;
        try {
            for (var def : dm.getDefinitions()) {
                String name = def.getName();
                String lower = (name + " " + def.getDescription()).toLowerCase();
                if (lower.contains("param") || lower.contains("annot") || lower.contains("debug") || lower.contains("name")) {
                    Object v = null; try { v = pm.getValue(prefix + name); } catch (Exception e) { try { v = pm.getValue(name); } catch (Exception e2) { v = "?"; } }
                    System.out.println("  " + prefix + name + " = " + v + "   // " + def.getDescription());
                }
            }
            for (var child : dm.getChildren()) dumpProps(pm, child, prefix + child.getNamespace() + ".");
        } catch (Exception e) { System.out.println("  (props: " + e + ")"); }
    }

    private static String describe(com.pnfsoftware.jeb.core.units.code.android.IDexUnit dex, java.util.List<com.pnfsoftware.jeb.core.units.code.android.dex.IDexValue> arr) {
        StringBuilder sb = new StringBuilder("[");
        for (var v : arr) {
            if (v.getType() == com.pnfsoftware.jeb.core.units.code.android.dex.IDexValue.VALUE_STRING) { var s = dex.getString(v.getStringIndex()); sb.append('"').append(s == null ? "?" : s.getValue(false)).append('"'); }
            else if (v.getType() == com.pnfsoftware.jeb.core.units.code.android.dex.IDexValue.VALUE_NULL) sb.append("null");
            else if (v.getType() == com.pnfsoftware.jeb.core.units.code.android.dex.IDexValue.VALUE_INT) sb.append(v.getInt());
            else sb.append("type").append(v.getType());
            sb.append(',');
        }
        return sb.append(']').toString();
    }

    private static String firstLines(String s, int n) { if (s == null) return "(null)"; String[] l = s.split("\n"); StringBuilder sb = new StringBuilder(); for (int i = 0; i < Math.min(n, l.length); i++) sb.append("    ").append(l[i]).append('\n'); return sb.toString(); }

    private static final String P = "Lfixture/Params;->", B = "Lfixture/Branches;->";

    private static void run(String[] args) throws Exception {
        if (System.getenv("WORKBENCH_HOME") == null) throw new IllegalStateException("ParamsIT needs a hermetic WORKBENCH_HOME — run it with: gradle paramsIT");
        File fixture = new File("backend/src/test/fixtures/params/params-fixture.dex");
        if (!fixture.isFile()) throw new IllegalStateException("no fixture at " + fixture.getAbsolutePath());
        WorkbenchServer server = new WorkbenchServer(Path.of("web"));
        server.start();
        System.out.println("server up on " + BASE);
        data(post("project.open", Map.of("name", "params")));
        String tid = String.valueOf(data(post("target.open", Map.of("path", fixture.getPath()))).get("id"));
        check(waitReady(tid), "the constructed fixture dex is open as " + tid + " (sha256 " + sha256(java.nio.file.Files.readAllBytes(fixture.toPath())).substring(0, 12) + "…)");

        // ================================================================ A. branch destinations, decoded from the rows' own bytes
        int branchRows = 0, payloadRefs = 0;
        for (String m : List.of(B + "loop(I)I", B + "widths(I)I", B + "backwards(I)I", B + "switches(I)I", B + "arrays()[I", B + "guarded(Ljava/lang/Object;)I", B + "threeToOne(III)V")) {
            List<Map<String, Object>> rows = rows(data(post("code.smali", Map.of("target", tid, "symbol", m))));
            Map<Long, Integer> labelAt = new LinkedHashMap<>();   // label text's target → row index of the label
            for (int i = 0; i < rows.size(); i++) if ("label".equals(rows.get(i).get("kind"))) { java.util.regex.Matcher mm = java.util.regex.Pattern.compile(":(?:l|pswitch_data|sswitch_data|array)_([0-9a-f]+)").matcher(String.valueOf(rows.get(i).get("text"))); if (mm.find()) labelAt.put(Long.parseLong(mm.group(1), 16), i); }
            long lastOff = -1; boolean ordered = true;
            for (int i = 0; i < rows.size(); i++) {
                Map<String, Object> r = rows.get(i);
                if (!"insn".equals(r.get("kind"))) continue;
                long off = Long.parseLong(String.valueOf(r.get("offset")), 16);
                if (off <= lastOff) ordered = false;
                lastOff = off;
                Long dest = decodeDest(off, String.valueOf(r.get("bytes")));
                if (dest == null) continue;
                int op = Integer.parseInt(String.valueOf(r.get("bytes")).trim().substring(0, 2), 16);
                boolean payload = op == 0x26 || op == 0x2b || op == 0x2c;
                String text = String.valueOf(r.get("text"));
                String want = (payload ? (op == 0x2b ? ":pswitch_data_" : op == 0x2c ? ":sswitch_data_" : ":array_") : ":l_") + Long.toHexString(dest);
                Integer li = labelAt.get(dest);
                boolean labelPlaced = li != null && (payload ? li > i : "insn".equals(rows.get(nextInsn(rows, li)).get("kind")) && Long.parseLong(String.valueOf(rows.get(nextInsn(rows, li)).get("offset")), 16) == dest);
                check(text.endsWith(" " + want) && labelPlaced, m.substring(m.indexOf("->") + 2) + " @" + r.get("offset") + " [" + r.get("bytes") + "] → " + want + (dest < off ? " (backward)" : " (forward)") + ": operand " + (text.endsWith(" " + want) ? "right" : "WRONG: " + text.trim()) + ", label " + (labelPlaced ? "at the destination" : "MISPLACED"));
                if (payload) payloadRefs++; else branchRows++;
            }
            check(ordered, m.substring(m.indexOf("->") + 2) + ": instruction offsets strictly increase");
            // agreement with the packet: every label offset is a CFG block start, and the packet's own
            // (JEB-formatted, absolute) branch operand equals our decoded destination
            Map<String, Object> pk = data(post("evidence.packet", Map.of("target", tid, "symbol", m, "java", false, "student", false)));
            java.util.Set<String> blockIds = new java.util.HashSet<>();
            for (Object o : (List<?>) ((Map<?, ?>) pk.get("cfg")).get("blocks")) blockIds.add(String.valueOf(((Map<?, ?>) o).get("id")));
            boolean allBlocks = true;
            for (Map.Entry<Long, Integer> e : labelAt.entrySet()) if (!String.valueOf(rows.get(e.getValue()).get("text")).contains("_data_") && !String.valueOf(rows.get(e.getValue()).get("text")).contains(":array_") && !blockIds.contains(String.format("b%04x", e.getKey()))) allBlocks = false;
            boolean agree = true; int compared = 0;
            for (Object o : (List<?>) pk.get("instructions")) {
                Map<?, ?> pi = (Map<?, ?>) o;
                String t = String.valueOf(pi.get("text"));
                java.util.regex.Matcher mm = java.util.regex.Pattern.compile("^(if-\\w+|goto(?:/16|/32)?|packed-switch|sparse-switch|fill-array-data) .*?([0-9A-F]+)$").matcher(t);
                if (!mm.find()) continue;
                long jebDest = Long.parseLong(mm.group(2), 16);
                Map<String, Object> ours = rows.stream().filter(x -> "insn".equals(x.get("kind")) && String.valueOf(x.get("offset")).equals(pi.get("offset"))).findFirst().orElse(null);
                Long dec = ours == null ? null : decodeDest(Long.parseLong(String.valueOf(ours.get("offset")), 16), String.valueOf(ours.get("bytes")));
                if (dec == null || dec != jebDest) agree = false; else compared++;
            }
            boolean hasBranches = rows.stream().anyMatch(x -> "insn".equals(x.get("kind")) && decodeDest(0, String.valueOf(x.get("bytes"))) != null);
            check(allBlocks && agree && (compared > 0 || !hasBranches), m.substring(m.indexOf("->") + 2) + ": every code label is a CFG block start and " + compared + " packet operand(s) (JEB's absolute rendering) equal the decoded destinations");
        }
        check(branchRows == 11 && payloadRefs == 4, branchRows + " branch rows and " + payloadRefs + " payload references exercised (forward and backward in all three goto widths and if-*, both switches, fill-array-data)");
        // the backward wide displacements: goto/16 and goto/32 back to :top (0002), and a switch-case target behind the switch
        List<Map<String, Object>> bw = rows(data(post("code.smali", Map.of("target", tid, "symbol", B + "backwards(I)I"))));
        List<String> bwCases = new ArrayList<>(); for (Map<String, Object> r : bw) if ("case".equals(r.get("kind"))) bwCases.add(String.valueOf(r.get("text")).trim());
        long bwBack = bw.stream().filter(r -> "insn".equals(r.get("kind")) && String.valueOf(r.get("text")).trim().matches("goto(/16|/32) :l_2")).count();
        check(bwBack == 2 && bwCases.equals(List.of(":l_2")) && bw.stream().anyMatch(r -> String.valueOf(r.get("text")).contains(".packed-switch 0x5")),
                "backwards: goto/16 and goto/32 decode their NEGATIVE 16/32-bit displacements to :l_2, the packed-switch case behind the switch too: " + bwCases);
        // the switch payloads: case lines carry the decoded destination labels
        List<Map<String, Object>> sw = rows(data(post("code.smali", Map.of("target", tid, "symbol", B + "switches(I)I"))));
        List<String> cases = new ArrayList<>(); for (Map<String, Object> r : sw) if ("case".equals(r.get("kind"))) cases.add(String.valueOf(r.get("text")).trim());
        check(cases.equals(List.of(":l_10", ":l_14", "0x100 -> :l_18", "0x200 -> :l_14")) && sw.stream().anyMatch(r -> String.valueOf(r.get("text")).contains(".packed-switch 0x1")) && sw.stream().anyMatch(r -> String.valueOf(r.get("text")).contains(".sparse-switch")),
                "switch payloads rendered after the code with their case destinations: " + cases);
        List<Map<String, Object>> arr = rows(data(post("code.smali", Map.of("target", tid, "symbol", B + "arrays()[I"))));
        List<String> arrEls = new ArrayList<>(); for (Map<String, Object> r : arr) if ("case".equals(r.get("kind"))) arrEls.add(String.valueOf(r.get("text")).trim());
        check(arr.stream().anyMatch(r -> String.valueOf(r.get("text")).trim().equals(".array-data 4")) && arrEls.equals(List.of("0x1", "0x2", "0x3")), "the array payload lists its elements as the engine decodes them (width 4): " + arrEls);
        List<Map<String, Object>> three = rows(data(post("code.smali", Map.of("target", tid, "symbol", B + "threeToOne(III)V"))));
        long ifs = three.stream().filter(r -> String.valueOf(r.get("text")).trim().startsWith("if-nez") && String.valueOf(r.get("text")).trim().endsWith(":l_20")).count();
        long labels = three.stream().filter(r -> "label".equals(r.get("kind"))).count();
        check(ifs == 3 && labels == 1, "the 0gs.A0N shape: three if-nez land on ONE label (:l_20), no label at a displacement value (" + labels + " label row)");
        List<Map<String, Object>> gd = rows(data(post("code.smali", Map.of("target", tid, "symbol", B + "guarded(Ljava/lang/Object;)I"))));
        String catchRow = gd.stream().filter(r -> String.valueOf(r.get("text")).trim().startsWith(".catch ")).map(r -> String.valueOf(r.get("text")).trim()).findFirst().orElse("");
        java.util.regex.Matcher cm = java.util.regex.Pattern.compile("^\\.catch (\\S+) \\{(:try_start_[0-9a-f]+) \\.\\. :try_end_([0-9a-f]+)\\} (:catch_[0-9a-f]+)$").matcher(catchRow);
        boolean catchOk = cm.find() && cm.group(1).equals("Ljava/lang/RuntimeException;") && cm.group(2).equals(":try_start_0") && cm.group(4).equals(":catch_a");
        List<String> gdLabels = new ArrayList<>(); for (Map<String, Object> r : gd) if ("label".equals(r.get("kind"))) gdLabels.add(String.valueOf(r.get("text")).trim());
        int catchIx = -1; for (int i = 0; i < gd.size(); i++) if ("label".equals(gd.get(i).get("kind")) && ":catch_a".equals(String.valueOf(gd.get(i).get("text")).trim())) catchIx = i;
        boolean handlerPlaced = catchIx >= 0 && "000a".equals(gd.get(nextInsn(gd, catchIx)).get("offset"));
        long tryEnd = cm.find(0) ? Long.parseLong(cm.group(3), 16) : -1;
        boolean tryEndAtInsn = gd.stream().anyMatch(r -> "insn".equals(r.get("kind")) && Long.parseLong(String.valueOf(r.get("offset")), 16) == tryEnd);
        check(catchOk && handlerPlaced && gdLabels.containsAll(List.of(":try_start_0", ":try_end_" + Long.toHexString(tryEnd), ":catch_a")) && tryEndAtInsn,
                "the try table (addresses in bytes): " + catchRow + " — the handler label at 000a, the range labels placed at instruction starts; labels " + gdLabels);
        Map<String, Object> smAll = data(post("code.smali", Map.of("target", tid, "symbol", B + "loop(I)I")));
        check(String.valueOf(smAll.get("note")).contains("not a qualified round-trippable smali export"), "the listing says what it is: " + smAll.get("note"));

        // ================================================================ B. retained metadata — extracted verbatim, applied to nothing
        Map<String, Object> permutedBefore = data(post("code.decompile", Map.of("target", tid, "symbol", P + "permuted(Lfixture/Intent;Lfixture/IntentSender;II)V", "scope", "method")));
        Map<String, Object> ao = data(post("code.params", Map.of("target", tid, "symbol", P + "annotOnly(ILjava/lang/String;)I")));
        Map<?, ?> ann = (Map<?, ?>) ao.get("annotation"), dbg = (Map<?, ?>) ao.get("debugInfo");
        check("present".equals(ann.get("status")) && List.of("count", "label").equals(ann.get("names")) && List.of(0, 0).equals(ann.get("accessFlags")) && Boolean.TRUE.equals(ann.get("countMatchesPrototype")) && "unverified".equals(ann.get("argumentMapping")) && "absent".equals(dbg.get("status")),
                "annotOnly: annotation present [count, label] flags [0, 0], count matches, mapping unverified; debug info absent");
        check(Boolean.TRUE.equals(((Map<?, ?>) ao.get("decompiler")).get("appliesAnnotationByPosition")) && String.valueOf(((Map<?, ?>) ((List<?>) ao.get("parameters")).get(0)).get("nameSource")).startsWith("annotation"),
                "annotOnly: the engine (JEB) names the arguments after the annotation by position — reported as the engine's rendering, unverified: " + ((Map<?, ?>) ao.get("decompiler")).get("note"));
        Map<String, Object> dOnly = data(post("code.params", Map.of("target", tid, "symbol", P + "debugOnly(ILjava/lang/String;)I")));
        check("absent".equals(((Map<?, ?>) dOnly.get("annotation")).get("status")) && "present".equals(((Map<?, ?>) dOnly.get("debugInfo")).get("status")) && List.of("width", "title").equals(((Map<?, ?>) dOnly.get("debugInfo")).get("names"))
                        && "debug_info".equals(((Map<?, ?>) ((List<?>) dOnly.get("parameters")).get(0)).get("nameSource")),
                "debugOnly: no annotation, debug names [width, title] as their own source, the current names come from debug_info");
        Map<String, Object> agree = data(post("code.params", Map.of("target", tid, "symbol", P + "agree(JI)J")));
        check(Boolean.TRUE.equals(agree.get("sourcesAgree")) && List.of(0, 2).equals(regs(agree)), "agree: both sources present and equal; registers p0 (wide) and p2");
        Map<String, Object> conflict = data(post("code.params", Map.of("target", tid, "symbol", P + "conflict(Ljava/lang/String;Z)V")));
        check(Boolean.FALSE.equals(conflict.get("sourcesAgree")) && List.of("annName", "annFlag").equals(((Map<?, ?>) conflict.get("annotation")).get("names")) && List.of("dbgName", "dbgFlag").equals(((Map<?, ?>) conflict.get("debugInfo")).get("names")),
                "conflict: the two sources disagree and both are kept — current names: " + names(conflict));
        Map<String, Object> wn = data(post("code.params", Map.of("target", tid, "symbol", P + "withNull(II)I")));
        check(java.util.Arrays.asList(null, "second").equals(((Map<?, ?>) wn.get("annotation")).get("names")) && Integer.valueOf(1).equals(((Map<?, ?>) wn.get("annotation")).get("nullNames")), "withNull: [null, second] kept as such, 1 null entry");
        Map<String, Object> absent = data(post("code.params", Map.of("target", tid, "symbol", P + "absent(I)I")));
        Map<?,?> absentParam=(Map<?,?>)((List<?>)absent.get("parameters")).get(0);
        // Personal JEB 5.46 chooses "v" here; 5.42 chose argN. Preserve the
        // engine's observed spelling/category without inventing retained metadata.
        boolean absentFallback="generic".equals(absentParam.get("nameSource")) ||
                ("engine".equals(absentParam.get("nameSource")) && "v".equals(absentParam.get("name")));
        check("absent".equals(((Map<?, ?>) absent.get("annotation")).get("status")) && "absent".equals(((Map<?, ?>) absent.get("debugInfo")).get("status")) && absentFallback
                        && "unverified".equals(absent.get("argumentMapping")) && Boolean.FALSE.equals(((Map<?,?>)absent.get("decompiler")).get("appliesAnnotationToAny")),
                "absent: both metadata sources absent; engine/generic fallback stays unverified: " + Json.write(absent));
        Map<String, Object> stale = data(post("code.params", Map.of("target", tid, "symbol", P + "staleCount(II)I")));
        Map<?, ?> sann = (Map<?, ?>) stale.get("annotation");
        check(List.of("alpha", "beta", "gamma").equals(sann.get("names")) && Integer.valueOf(1).equals(sann.get("flagsCount")) && Boolean.FALSE.equals(sann.get("countMatchesPrototype")) && Boolean.FALSE.equals(sann.get("namesCountMatchesPrototype")) && Boolean.FALSE.equals(sann.get("flagsCountMatchesPrototype"))
                        && ((List<?>) stale.get("parameters")).size() == 2 && String.valueOf(sann.get("mismatch")).contains("3 name(s) for 2 formal"),
                "staleCount: 3 names and 1 flag on 2 formals — every array checked on its own, nothing truncated or zipped: " + sann.get("mismatch"));
        Map<String, Object> zero = data(post("code.params", Map.of("target", tid, "symbol", P + "zeroArgs()V")));
        check(List.of("message").equals(((Map<?, ?>) zero.get("annotation")).get("names")) && ((List<?>) zero.get("parameters")).isEmpty() && Boolean.FALSE.equals(((Map<?, ?>) zero.get("annotation")).get("countMatchesPrototype")),
                "zeroArgs (the 0hX shape): one retained name, no formal argument — shown, assigned to nothing");
        Map<String, Object> wide = data(post("code.params", Map.of("target", tid, "symbol", P + "wide(JDI)D")));
        check(List.of(1, 3, 5).equals(regs(wide)) && Integer.valueOf(6).equals(((Map<?, ?>) wide.get("prototype")).get("registerWords")) && Boolean.TRUE.equals(((Map<?, ?>) wide.get("annotation")).get("countMatchesPrototype")),
                "wide: three formals over registers p1, p3, p5 (six words with this) — formal count is not word count");
        Map<String, Object> abs = data(post("code.params", Map.of("target", tid, "symbol", P + "abstractNamed(Ljava/lang/String;I)V")));
        check("present".equals(((Map<?, ?>) abs.get("annotation")).get("status")) && List.of("key", "ttl").equals(((Map<?, ?>) abs.get("annotation")).get("names")) && "no-code".equals(((Map<?, ?>) abs.get("debugInfo")).get("status")),
                "abstractNamed: no body, the annotation still read [key, ttl]; debug info: no-code");
        Map<String, Object> nat = data(post("code.params", Map.of("target", tid, "symbol", P + "nativeNamed(J)J")));
        check(List.of("handle").equals(((Map<?, ?>) nat.get("annotation")).get("names")) && "no-code".equals(((Map<?, ?>) nat.get("debugInfo")).get("status")), "nativeNamed: [handle], no code");
        Map<String, Object> odd = data(post("code.params", Map.of("target", tid, "symbol", P + "oddNames(IIII)I")));
        Map<?, ?> oann = (Map<?, ?>) odd.get("annotation");
        check(List.of("same", "same", "class", "1bad-name").equals(oann.get("names")) && List.of("same").equals(oann.get("duplicates")) && List.of("class", "1bad-name").equals(oann.get("notJavaIdentifiers")),
                "oddNames: duplicates and non-identifiers kept verbatim and flagged: dup " + oann.get("duplicates") + ", not identifiers " + oann.get("notJavaIdentifiers"));
        Map<String, Object> fl = data(post("code.params", Map.of("target", tid, "symbol", P + "flagged(Ljava/lang/Object;I)V")));
        check(List.of(List.of("final", "mandated"), List.of("synthetic")).equals(((Map<?, ?>) fl.get("annotation")).get("accessFlagsDecoded")) && java.util.Arrays.asList("this$0", null).equals(((Map<?, ?>) fl.get("annotation")).get("names")),
                "flagged: 0x8010 → final,mandated and 0x1000 → synthetic, beside a null name");
        Map<String, Object> perm = data(post("code.params", Map.of("target", tid, "symbol", P + "permuted(Lfixture/Intent;Lfixture/IntentSender;II)V")));
        check(List.of("intentSender", "intent", "flagsMask", "flagsValues").equals(((Map<?, ?>) perm.get("annotation")).get("names")) && Boolean.TRUE.equals(((Map<?, ?>) perm.get("annotation")).get("countMatchesPrototype"))
                        && "unverified".equals(perm.get("argumentMapping")) && Boolean.TRUE.equals(((Map<?, ?>) perm.get("decompiler")).get("appliesAnnotationByPosition"))
                        && String.valueOf(permutedBefore.get("text")).contains("fixture.Intent intentSender"),
                "permuted (the 0XS shape, constructed): the list is verbatim with count match, the mapping unverified — and the ENGINE itself renders `Intent intentSender`, which the block flags as its positional application");
        Map<String, Object> aligned = data(post("code.params", Map.of("target", tid, "symbol", P + "aligned(Lfixture/Intent;I)V")));
        check(List.of("dest", "flags").equals(((Map<?, ?>) aligned.get("annotation")).get("names")) && "unverified".equals(aligned.get("argumentMapping")), "aligned (the writeToParcel shape): a same-length list is still a separate list, mapping unverified");
        // the reads changed nothing
        Map<String, Object> ch = data(post("target.changes", Map.of("target", tid)));
        Map<String, Object> permutedAfter = data(post("code.decompile", Map.of("target", tid, "symbol", P + "permuted(Lfixture/Intent;Lfixture/IntentSender;II)V", "scope", "method")));
        check(Boolean.FALSE.equals(ch.get("dirty")) && dataList(post("history.list", Map.of("target", tid))).isEmpty() && permutedBefore.get("text").equals(permutedAfter.get("text")),
                "reading the metadata (params, smali, packet, decompile) dirtied nothing, wrote no History and left the decompiled text byte-identical");

        // ================================================================ C. the DEX view shows the metadata as rows apart from the code
        List<Map<String, Object>> psm = rows(data(post("code.smali", Map.of("target", tid, "symbol", P + "permuted(Lfixture/Intent;Lfixture/IntentSender;II)V"))));
        List<String> annRows = new ArrayList<>(); for (Map<String, Object> r : psm) if ("annotation".equals(r.get("kind"))) annRows.add(String.valueOf(r.get("text")).trim());
        check(annRows.size() == 5 && annRows.get(0).startsWith("#") && annRows.get(0).contains("unverified") && annRows.get(1).equals(".annotation system Ldalvik/annotation/MethodParameters;") && annRows.get(3).equals("names = { \"intentSender\", \"intent\", \"flagsMask\", \"flagsValues\" }") && psm.stream().noneMatch(r -> String.valueOf(r.get("text")).contains(".param")),
                "the DEX view: the annotation as its own rows, verbatim, no .param names on registers: " + annRows);
        List<Map<String, Object>> ssm = rows(data(post("code.smali", Map.of("target", tid, "symbol", P + "staleCount(II)I"))));
        check(ssm.stream().anyMatch(r -> "annotation-warn".equals(r.get("kind")) && String.valueOf(r.get("text")).contains("3 name(s) for 2 formal")), "staleCount's mismatch is said in the DEX view, as a warning row");
        List<Map<String, Object>> dsm = rows(data(post("code.smali", Map.of("target", tid, "symbol", P + "debugOnly(ILjava/lang/String;)I"))));
        check(dsm.stream().anyMatch(r -> "annotation".equals(r.get("kind")) && String.valueOf(r.get("text")).contains(".param p0, \"width\"") && String.valueOf(r.get("text")).contains("debug_info")), "debug_info names as .param rows, labelled as debug_info");
        List<Map<String, Object>> asm = rows(data(post("code.smali", Map.of("target", tid, "symbol", P + "abstractNamed(Ljava/lang/String;I)V"))));
        check(asm.stream().anyMatch(r -> "annotation".equals(r.get("kind")) && String.valueOf(r.get("text")).contains("\"key\", \"ttl\"")) && asm.stream().noneMatch(r -> "insn".equals(r.get("kind"))), "a bodiless method shows its annotation rows (no early return before the metadata)");

        // ================================================================ D. machine consumers carry the block
        Map<String, Object> dm = data(post("code.decompile", Map.of("target", tid, "symbol", P + "staleCount(II)I", "scope", "method")));
        check(dm.get("methodParameters") instanceof Map<?, ?> mp && List.of("alpha", "beta", "gamma").equals(((Map<?, ?>) mp.get("annotation")).get("names")), "code.decompile (method) carries methodParameters");
        Map<String, Object> dc = data(post("code.decompile", Map.of("target", tid, "symbol", "Lfixture/Params;", "scope", "class")));
        check(dc.get("methodParametersIndex") instanceof List<?> ix && ix.size() >= 12 && ix.stream().anyMatch(o -> String.valueOf(((Map<?, ?>) o).get("addr")).contains("permuted")), "code.decompile (class) indexes the methods that carry the annotation (" + ((List<?>) dc.get("methodParametersIndex")).size() + ")");
        Map<String, Object> pk = data(post("evidence.packet", Map.of("target", tid, "symbol", P + "permuted(Lfixture/Intent;Lfixture/IntentSender;II)V", "java", false, "student", false)));
        check(pk.get("methodParameters") instanceof Map<?, ?> pmp && "unverified".equals(pmp.get("argumentMapping")) && pk.get("original_method") != null, "evidence.packet carries methodParameters (unverified) beside the original descriptor");
        Map<String, Object> tc = post("tool.call", Map.of("tool", "code", "args", Map.of("target", tid, "symbol", P + "staleCount(II)I", "view", "params")));
        check(Boolean.TRUE.equals(tc.get("ok")) && data(tc).get("annotation") != null, "the MCP code tool's view params answers the block");
        Map<String, Object> tj = post("tool.call", Map.of("tool", "code", "args", Map.of("target", tid, "symbol", P + "staleCount(II)I", "view", "java")));
        check(Boolean.TRUE.equals(tj.get("ok")) && data(tj).get("methodParameters") != null, "the MCP code tool's java view carries methodParameters (the facade passes the field)");

        // the java / smali / packet views carry the RAW block: they do not claim what they did not compute
        Map<String, Object> pmJava = (Map<String, Object>) data(post("code.decompile", Map.of("target", tid, "symbol", P + "permuted(Lfixture/Intent;Lfixture/IntentSender;II)V", "scope", "method"))).get("methodParameters");
        Map<String, Object> pmFull = data(post("code.params", Map.of("target", tid, "symbol", P + "permuted(Lfixture/Intent;Lfixture/IntentSender;II)V")));
        check(pmJava != null && ((Map<?, ?>) pmJava.get("decompiler")).get("appliesAnnotationByPosition") == null && String.valueOf(((Map<?, ?>) pmJava.get("decompiler")).get("note")).contains("not computed")
                        && Boolean.TRUE.equals(((Map<?, ?>) pmFull.get("decompiler")).get("appliesAnnotationByPosition")),
                "permuted: the java view's block says the positional question was not computed there; code.params answers it (JEB applies the list by position on this fixture)");

        // ================================================================ E. the analyst's mapping — explicit, validated, atomic, undoable
        String mt = P + "mapTarget(ILjava/lang/String;Z)I";
        Map<String, Object> mtp = data(post("code.params", Map.of("target", tid, "symbol", mt)));
        check(List.of("retainedFirst", "retainedSecond", "retainedThird").equals(names(mtp)), "mapTarget before: the engine's positional names " + names(mtp));
        Map<String, Object> agent = postAs("llm:agent-9", "param.rename", Map.of("target", tid, "symbol", mt, "index", 0, "name", "fromAgent"));
        check("E_SCOPE".equals(code(agent)), "an agent cannot take the analyst's mapping decision: " + message(agent));
        Map<String, Object> coll = post("param.rename", Map.of("target", tid, "symbol", mt, "index", 0, "name", "retainedThird", "annotationIndex", 2));
        check("E_SYNTAX".equals(code(coll)) && String.valueOf(message(coll)).contains("already the name of argument 2"), "a cross-position name that collides with another argument's current name is refused: " + message(coll));
        Map<String, Object> kw = post("param.rename", Map.of("target", tid, "symbol", mt, "index", 0, "name", "class"));
        Map<String, Object> bad = post("param.rename", Map.of("target", tid, "symbol", mt, "index", 0, "name", "1bad-name"));
        check("E_SYNTAX".equals(code(kw)) && "E_SYNTAX".equals(code(bad)), "a keyword and a non-identifier are refused, never sanitised: " + message(kw) + " / " + message(bad));
        Map<String, Object> stalePv = post("param.rename", Map.of("target", tid, "symbol", mt, "index", 1, "name", "fresh", "expectCurrent", List.of(Map.of("index", 1, "name", "somethingElse"))));
        check("E_BUSY".equals(code(stalePv)), "a stale preview (the argument is no longer named as seen) does not rename: " + message(stalePv));
        // the swap, previewed then applied as ONE entry
        List<Map<String, Object>> swap = List.of(Map.of("index", 0, "name", "retainedThird", "annotationIndex", 2), Map.of("index", 2, "name", "retainedFirst", "annotationIndex", 0));
        Map<String, Object> pv = data(post("param.rename.bulk", Map.of("target", tid, "symbol", mt, "preview", true, "mapping", swap)));
        check(Boolean.TRUE.equals(pv.get("applicable")) && Integer.valueOf(2).equals(pv.get("effective")) && String.valueOf(pv.get("argumentMapping")).contains("unverified") && dataList(post("history.list", Map.of("target", tid))).isEmpty(),
                "bulk preview of a swap: applicable, 2 effective, unverified said, nothing applied");
        Map<String, Object> applied = data(post("param.rename.bulk", Map.of("target", tid, "symbol", mt, "mapping", swap, "expectCurrent", List.of(Map.of("index", 0, "name", "retainedFirst"), Map.of("index", 2, "name", "retainedThird")))));
        Map<String, Object> mtAfter = data(post("code.params", Map.of("target", tid, "symbol", mt)));
        String mtText = String.valueOf(data(post("code.decompile", Map.of("target", tid, "symbol", mt, "scope", "method"))).get("text"));
        check(Boolean.TRUE.equals(applied.get("applied")) && List.of("retainedThird", "retainedSecond", "retainedFirst").equals(names(mtAfter)) && mtText.contains("int retainedThird, java.lang.String retainedSecond, boolean retainedFirst") && mtText.contains("retainedFirst ? retainedThird + 1 : retainedThird")
                        && "analyst".equals(((Map<?, ?>) ((List<?>) mtAfter.get("parameters")).get(0)).get("nameSource")) && ((List<?>) mtAfter.get("mappings")).size() == 1,
                "the swap applied through the engine's binding: signature and every use follow, types and order unchanged, one mapping recorded");
        List<Object> hist = dataList(post("history.list", Map.of("target", tid)));
        Map<?, ?> h0 = (Map<?, ?>) hist.get(0);
        check(hist.size() == 1 && "parameters".equals(h0.get("kind")) && ((List<?>) h0.get("changes")).size() == 2 && "dex_method_parameters_annotation".equals(h0.get("source")) && String.valueOf(h0.get("argumentMapping")).contains("not compiler-verified"),
                "ONE History entry for the bulk, with provenance (source, annotation entries, old/new names, actor) and the honest status");
        data(post("history.undo", Map.of("target", tid)));
        Map<String, Object> mtUndone = data(post("code.params", Map.of("target", tid, "symbol", mt)));
        check(List.of("retainedFirst", "retainedSecond", "retainedThird").equals(names(mtUndone)) && ((List<?>) mtUndone.get("mappings")).isEmpty() && String.valueOf(((Map<?, ?>) ((List<?>) mtUndone.get("parameters")).get(0)).get("nameSource")).startsWith("annotation"),
                "undo puts the engine's names back whole (the analyst's setting cleared, not overwritten with a copy)");
        data(post("history.redo", Map.of("target", tid)));
        check(List.of("retainedThird", "retainedSecond", "retainedFirst").equals(names(data(post("code.params", Map.of("target", tid, "symbol", mt))))), "redo re-applies the swap");
        // a single cross-position mapping: the permuted constructor fixed by the analyst (intent ↔ intentSender)
        String pm = P + "permuted(Lfixture/Intent;Lfixture/IntentSender;II)V";
        Map<String, Object> one = post("param.rename", Map.of("target", tid, "symbol", pm, "index", 0, "name", "intent", "annotationIndex", 1));
        check("E_SYNTAX".equals(code(one)), "mapping entry 1 ('intent') onto argument 0 alone collides with argument 1's current name — said, not forced");
        Map<String, Object> fix = data(post("param.rename.bulk", Map.of("target", tid, "symbol", pm, "mapping", List.of(Map.of("index", 0, "name", "intent", "annotationIndex", 1), Map.of("index", 1, "name", "intentSender", "annotationIndex", 0)))));
        String pmText = String.valueOf(data(post("code.decompile", Map.of("target", tid, "symbol", pm, "scope", "method"))).get("text"));
        check(Boolean.TRUE.equals(fix.get("applied")) && pmText.contains("fixture.Intent intent, fixture.IntentSender intentSender") && pmText.contains("this.intent = intent;") && pmText.contains("this.intentSender = intentSender;"),
                "the analyst corrects the permutation by a cross-position swap — the body's stores now read right; the raw annotation stays as it was");
        check(List.of("intentSender", "intent", "flagsMask", "flagsValues").equals(((Map<?, ?>) data(post("code.params", Map.of("target", tid, "symbol", pm))).get("annotation")).get("names")), "the raw annotation is unchanged after the mapping");
        // nulls are no-ops; a duplicate list cannot be applied in bulk but singles can
        Map<String, Object> wnPv = data(post("param.rename.bulk", Map.of("target", tid, "symbol", P + "withNull(II)I", "preview", true, "mapping", List.of(new java.util.HashMap<String, Object>() {{ put("index", 0); put("name", null); put("annotationIndex", 0); }}, Map.of("index", 1, "name", "second", "annotationIndex", 1)))));
        check(Boolean.TRUE.equals(wnPv.get("applicable")) && Integer.valueOf(1).equals(wnPv.get("effective")) && ((Map<?, ?>) ((List<?>) wnPv.get("rows")).get(0)).get("noop") != null, "a null entry previews as a no-op (the argument keeps its name), the other applies");
        Map<String, Object> dupPv = data(post("param.rename.bulk", Map.of("target", tid, "symbol", P + "dupList(II)I", "preview", true, "mapping", List.of(Map.of("index", 0, "name", "twin"), Map.of("index", 1, "name", "twin")))));
        check(Boolean.FALSE.equals(dupPv.get("applicable")) && String.valueOf(dupPv.get("problems")).contains("also proposed for argument 0"), "a duplicate list cannot be applied in bulk — the second row names the first: " + dupPv.get("problems"));
        Map<String, Object> dupOne = post("param.rename", Map.of("target", tid, "symbol", P + "dupList(II)I", "index", 1, "name", "twin", "annotationIndex", 1));
        Map<String, Object> dupOwn = data(post("param.rename", Map.of("target", tid, "symbol", P + "dupList(II)I", "index", 1, "name", "twinSecond", "source", "analyst")));
        check("E_SYNTAX".equals(code(dupOne)) && (String.valueOf(message(dupOne)).contains("already the name of argument 0") || String.valueOf(message(dupOne)).contains("nothing to change")) && Boolean.TRUE.equals(dupOwn.get("applied")),
                "…one entry alone is refused — a collision with argument 0's positional pick, or a no-op when JEB already shows it on argument 1 — said, not forced; the analyst's own name for argument 1 applies: " + message(dupOne));
        Map<String, Object> staleBulk = data(post("param.rename.bulk", Map.of("target", tid, "symbol", P + "staleCount(II)I", "preview", true, "mapping", List.of(Map.of("index", 0, "name", "alpha"), Map.of("index", 1, "name", "beta"), Map.of("index", 2, "name", "gamma")))));
        check(Boolean.FALSE.equals(staleBulk.get("applicable")) && String.valueOf(staleBulk.get("problems")).contains("no formal argument at position 2"), "a stale-count list cannot be applied by position: " + staleBulk.get("problems"));
        // preview == commit: rows already named so are not applied and not recorded; nothing effective is refused, not recorded as a no-op entry
        List<Map<String, Object>> same = List.of(Map.of("index", 0, "name", "retainedThird", "annotationIndex", 2), Map.of("index", 2, "name", "retainedFirst", "annotationIndex", 0));   // both already the analyst's own names (position 1 still carries JEB's pick — proposing it would ADOPT, a change)
        Map<String, Object> samePv = data(post("param.rename.bulk", Map.of("target", tid, "symbol", mt, "preview", true, "mapping", same)));
        Map<String, Object> sameApply = post("param.rename.bulk", Map.of("target", tid, "symbol", mt, "mapping", same));
        int histBefore = dataList(post("history.list", Map.of("target", tid))).size();
        check(Boolean.FALSE.equals(samePv.get("applicable")) && Integer.valueOf(0).equals(samePv.get("effective")) && samePv.get("why") != null && "E_SYNTAX".equals(code(sameApply)),
                "a list that changes nothing previews as inapplicable with a reason and is refused on apply: " + samePv.get("why") + " / " + message(sameApply));
        Map<String, Object> mixed = data(post("param.rename.bulk", Map.of("target", tid, "symbol", mt, "mapping", List.of(Map.of("index", 0, "name", "ownFirst", "annotationIndex", 2), Map.of("index", 2, "name", "retainedFirst", "annotationIndex", 0)))));
        Map<?, ?> mixedH = (Map<?, ?>) dataList(post("history.list", Map.of("target", tid))).get(0);
        check(((List<?>) mixed.get("changes")).size() == 1 && ((List<?>) mixedH.get("changes")).size() == 1 && "analyst".equals(mixedH.get("source")) && "analyst".equals(((Map<?, ?>) ((List<?>) mixedH.get("changes")).get(0)).get("from")) && ((Map<?, ?>) ((List<?>) mixedH.get("changes")).get(0)).get("annotationIndex") == null
                        && mixedH.get("original_method") != null && dataList(post("history.list", Map.of("target", tid))).size() == histBefore + 1,
                "a mixed list records only the effective change; a claimed annotationIndex whose entry is another name is dropped (the change is the analyst's own); the entry carries the dex's method identity");
        data(post("history.undo", Map.of("target", tid, "id", mixedH.get("id"))));
        check(List.of("retainedThird", "retainedSecond", "retainedFirst").equals(names(data(post("code.params", Map.of("target", tid, "symbol", mt))))), "…and undoes back to the swapped names");
        // move an analyst's name to another argument, then undo (the clears used to run after the finals and collide)
        String al = P + "aligned(Lfixture/Intent;I)V";
        data(post("param.rename", Map.of("target", tid, "symbol", al, "index", 0, "name", "moved", "source", "analyst")));
        Map<String, Object> move = data(post("param.rename.bulk", Map.of("target", tid, "symbol", al, "mapping", List.of(Map.of("index", 0, "name", "other"), Map.of("index", 1, "name", "moved")))));
        Map<String, Object> moveUndo = post("history.undo", Map.of("target", tid, "id", move.get("historyId")));
        List<String> alNames = names(data(post("code.params", Map.of("target", tid, "symbol", al))));
        check(Boolean.TRUE.equals(move.get("applied")) && Boolean.TRUE.equals(moveUndo.get("ok")) && alNames.get(0).equals("moved") && alNames.get(1).equals("flags"),
                "moving an analyst's name to another argument undoes whole: the cleared position gives the name up before it lands back — " + alNames + " " + (moveUndo.get("ok") == null ? message(moveUndo) : ""));
        data(post("history.undo", Map.of("target", tid)));   // the first mapping on aligned, back to the engine's names
        // mixed provenance: WHICH arguments still carry JEB's positional name — none / some / all — the same through
        // code.params and the facade's view params, after a partial mapping, a whole one, and their undo
        Map<?, ?> an0 = (Map<?, ?>) ((Map<?, ?>) data(post("code.params", Map.of("target", tid, "symbol", al))).get("decompiler")).get("annotationNamedArguments");
        check("all".equals(an0.get("status")) && Integer.valueOf(2).equals(an0.get("count")) && Integer.valueOf(2).equals(an0.get("of")), "aligned untouched: annotationNamedArguments all (2 of 2)");
        Map<String, Object> alOne = data(post("param.rename", Map.of("target", tid, "symbol", al, "index", 0, "name", "ownDest", "source", "analyst")));
        Map<?, ?> d1 = (Map<?, ?>) data(post("code.params", Map.of("target", tid, "symbol", al))).get("decompiler");
        Map<?, ?> an1 = (Map<?, ?>) d1.get("annotationNamedArguments");
        check("some".equals(an1.get("status")) && List.of(1).equals(an1.get("indices")) && Boolean.FALSE.equals(d1.get("appliesAnnotationByPosition")) && Boolean.TRUE.equals(d1.get("appliesAnnotationToAny")) && String.valueOf(d1.get("note")).contains("1 of 2"),
                "aligned partly mapped: some — position 1 still carries JEB's positional name, the note says 1 of 2 (was: a bare whole-method false)");
        Map<String, Object> tcAl = post("tool.call", Map.of("tool", "code", "args", Map.of("target", tid, "symbol", al, "view", "params")));
        Map<?, ?> tcAn = Boolean.TRUE.equals(tcAl.get("ok")) && data(tcAl).get("decompiler") instanceof Map<?, ?> td ? (Map<?, ?>) td.get("annotationNamedArguments") : null;
        check(tcAn != null && "some".equals(tcAn.get("status")) && List.of(1).equals(tcAn.get("indices")) && Integer.valueOf(1).equals(tcAn.get("count")), "…the facade's view params says the same (some, [1]): " + tcAn);
        Map<String, Object> alTwo = data(post("param.rename", Map.of("target", tid, "symbol", al, "index", 1, "name", "ownFlags", "source", "analyst")));
        Map<?, ?> d2 = (Map<?, ?>) data(post("code.params", Map.of("target", tid, "symbol", al))).get("decompiler");
        check("none".equals(((Map<?, ?>) d2.get("annotationNamedArguments")).get("status")) && Boolean.FALSE.equals(d2.get("appliesAnnotationToAny")) && String.valueOf(d2.get("note")).startsWith("none of"), "aligned wholly mapped: none");
        Map<String, Object> tcNone = post("tool.call", Map.of("tool", "code", "args", Map.of("target", tid, "symbol", al, "view", "params")));
        check(Boolean.TRUE.equals(tcNone.get("ok")) && "none".equals(((Map<?, ?>) ((Map<?, ?>) data(tcNone).get("decompiler")).get("annotationNamedArguments")).get("status")), "…the facade says none too");
        data(post("history.undo", Map.of("target", tid, "id", alTwo.get("historyId"))));
        check("some".equals(((Map<?, ?>) ((Map<?, ?>) data(post("code.params", Map.of("target", tid, "symbol", al))).get("decompiler")).get("annotationNamedArguments")).get("status")), "…undo of the second mapping: some again");
        data(post("history.undo", Map.of("target", tid, "id", alOne.get("historyId"))));
        check("all".equals(((Map<?, ?>) ((Map<?, ?>) data(post("code.params", Map.of("target", tid, "symbol", al))).get("decompiler")).get("annotationNamedArguments")).get("status")), "…undo of the first: all again");
        Map<String, Object> tcAll = post("tool.call", Map.of("tool", "code", "args", Map.of("target", tid, "symbol", al, "view", "params")));
        check(Boolean.TRUE.equals(tcAll.get("ok")) && "all".equals(((Map<?, ?>) ((Map<?, ?>) data(tcAll).get("decompiler")).get("annotationNamedArguments")).get("status")) && Boolean.TRUE.equals(((Map<?, ?>) data(tcAll).get("decompiler")).get("appliesAnnotationByPosition")), "…the facade says all too, with the compatibility boolean true");
        // adopting JEB's positional pick: the SAME name on an engine-named argument is an explicit decision — applied,
        // recorded `adopted`, the source becomes the analyst's; undo gives it back to the engine (never gate the user)
        Map<String, Object> adPv = data(post("param.rename.bulk", Map.of("target", tid, "symbol", al, "preview", true, "mapping", List.of(Map.of("index", 0, "name", "dest", "annotationIndex", 0)))));
        Map<String, Object> adopt = data(post("param.rename", Map.of("target", tid, "symbol", al, "index", 0, "name", "dest", "annotationIndex", 0)));
        Map<String, Object> adMeta = data(post("code.params", Map.of("target", tid, "symbol", al)));
        check(Boolean.TRUE.equals(adPv.get("applicable")) && ((Map<?, ?>) ((List<?>) adPv.get("rows")).get(0)).get("adopts") != null && Boolean.TRUE.equals(adopt.get("applied")) && Boolean.TRUE.equals(((Map<?, ?>) ((List<?>) adopt.get("changes")).get(0)).get("adopted"))
                        && "analyst".equals(((Map<?, ?>) ((List<?>) adMeta.get("parameters")).get(0)).get("nameSource")) && "some".equals(((Map<?, ?>) ((Map<?, ?>) adMeta.get("decompiler")).get("annotationNamedArguments")).get("status")),
                "adopting JEB's pick: previewed as an adoption, applied, recorded adopted, the argument's source is now the analyst's (the other stays positional)");
        data(post("history.undo", Map.of("target", tid, "id", adopt.get("historyId"))));
        check("all".equals(((Map<?, ?>) ((Map<?, ?>) data(post("code.params", Map.of("target", tid, "symbol", al))).get("decompiler")).get("annotationNamedArguments")).get("status")), "…undo of the adoption: the engine's pick again (all)");
        // a local's name is a collision the preview says (JEB would refuse it at apply)
        Map<String, Object> loopMeta = data(post("code.params", Map.of("target", tid, "symbol", B + "loop(I)I")));
        List<?> loopLocals = loopMeta.get("locals") instanceof List<?> ll ? ll : List.of();
        Map<String, Object> localPv = loopLocals.isEmpty() ? null : data(post("param.rename.bulk", Map.of("target", tid, "symbol", B + "loop(I)I", "preview", true, "mapping", List.of(Map.of("index", 0, "name", String.valueOf(loopLocals.get(0)))))));
        check(!loopLocals.isEmpty() && localPv != null && Boolean.FALSE.equals(localPv.get("applicable")) && String.valueOf(localPv.get("problems")).contains("local"),
                "the method's locals are read (" + loopLocals + ") and a retained name equal to one previews as a collision: " + (localPv == null ? "no locals" : localPv.get("problems")));
        // a wide (J) before the argument: the index is the formal index, the Java text follows
        String wd = P + "wide(JDI)D";
        Map<String, Object> wideMap = data(post("param.rename", Map.of("target", tid, "symbol", wd, "index", 1, "name", "ratioNamed", "source", "analyst")));
        String wideText = String.valueOf(data(post("code.decompile", Map.of("target", tid, "symbol", wd, "scope", "method"))).get("text"));
        check(Boolean.TRUE.equals(wideMap.get("applied")) && wideText.contains("double ratioNamed") && wideText.contains("return ratioNamed;"), "a name on the formal after a wide long lands on that formal in the Java text");
        data(post("history.undo", Map.of("target", tid, "id", wideMap.get("historyId"))));
        // what an agent may do to an argument through rename {local}: not touch one the analyst mapped; a retained name it gives is recorded as such
        Map<String, Object> agentArg = postAs("llm:agent-9", "rename", Map.of("target", tid, "symbol", mt, "local", "retainedThird", "name", "agentPick"));
        check("E_SCOPE".equals(code(agentArg)) && String.valueOf(message(agentArg)).contains("analyst's mapping"), "an agent cannot rename an argument the analyst mapped: " + message(agentArg));
        Map<String, Object> agentRetained = postAs("llm:agent-9", "rename", Map.of("target", tid, "symbol", al, "local", "dest", "name", "flags"));
        Map<String, Object> agentRetainedH = "E_SYNTAX".equals(code(agentRetained)) || "E_BUSY".equals(code(agentRetained)) ? null : (Map<String, Object>) dataList(post("history.list", Map.of("target", tid))).get(0);
        Map<String, Object> agentRetained2 = agentRetainedH != null ? null : postAs("llm:agent-9", "rename", Map.of("target", tid, "symbol", P + "annotOnly(ILjava/lang/String;)V", "local", "count", "name", "label"));
        Map<String, Object> agH = agentRetainedH != null ? agentRetainedH : agentRetained2 != null && Boolean.TRUE.equals(agentRetained2.get("ok")) ? (Map<String, Object>) dataList(post("history.list", Map.of("target", tid))).get(0) : null;
        check(agH != null && Boolean.TRUE.equals(agH.get("matchesRetainedName")) && "local".equals(agH.get("kind")), "an agent's argument name equal to a retained entry is recorded with matchesRetainedName (kind local): " + (agH == null ? message(agentRetained) + " / " + (agentRetained2 == null ? "" : message(agentRetained2)) : agH.get("note")));
        if (agH != null) data(post("history.undo", Map.of("target", tid, "id", agH.get("id"))));

        // ================================================================ F. original dex names restore through undo / redo / revert
        String cq = "Lfixture/1cq;";
        Map<String, Object> asNew = post("rename", Map.of("target", tid, "symbol", "Lfixture/Intent;", "name", "1cq"));
        check("E_SYNTAX".equals(code(asNew)), "'1cq' as a NEW analyst name is still refused: " + message(asNew));
        Map<String, Object> rn = data(post("rename", Map.of("target", tid, "symbol", cq, "name", "CourseMessageKey")));
        String cqNew = String.valueOf(rn.get("newAddr"));
        check(cqNew.equals("Lfixture/CourseMessageKey;"), "the digit-named class renamed to CourseMessageKey (" + cqNew + ")");
        Map<String, Object> undo = data(post("history.undo", Map.of("target", tid, "id", rn.get("historyId"))));
        Map<String, Object> res = post("symbol.resolve", Map.of("target", tid, "symbol", cq));
        check(Boolean.TRUE.equals(((Map<?, ?>) undo.get("undone")).get("undone")) && Boolean.TRUE.equals(res.get("ok")) && !Boolean.TRUE.equals(data(res).get("renamed")),
                "history.undo restores the ORIGINAL name '1cq' — validated against the dex, not the identifier rule (was: bad Java identifier: 1cq)");
        data(post("history.redo", Map.of("target", tid, "id", rn.get("historyId"))));
        check(Boolean.TRUE.equals(post("symbol.resolve", Map.of("target", tid, "symbol", cqNew)).get("ok")), "redo renames it again");
        data(post("history.undo", Map.of("target", tid, "id", rn.get("historyId"))));
        check(Boolean.TRUE.equals(post("symbol.resolve", Map.of("target", tid, "symbol", cq)).get("ok")), "undo once more: original again (rename → undo → redo → undo)");
        // members: the method a and the field b, same path
        Map<String, Object> rm = data(post("rename", Map.of("target", tid, "symbol", cq + "->a(I)I", "name", "store")));
        data(post("history.undo", Map.of("target", tid, "id", rm.get("historyId"))));
        Map<String, Object> rf = data(post("rename", Map.of("target", tid, "symbol", cq + "->b:I", "name", "stored")));
        data(post("history.undo", Map.of("target", tid, "id", rf.get("historyId"))));
        check(Boolean.TRUE.equals(post("symbol.resolve", Map.of("target", tid, "symbol", cq + "->a(I)I")).get("ok")) && Boolean.TRUE.equals(post("symbol.resolve", Map.of("target", tid, "symbol", cq + "->b:I")).get("ok")),
                "a method and a field of the class restore their one-letter originals the same way");
        // rename.revert: a real operation now
        Map<String, Object> rn2 = data(post("rename", Map.of("target", tid, "symbol", cq, "name", "CourseMessageKey")));
        Map<String, Object> rv = data(post("rename.revert", Map.of("target", tid, "symbol", String.valueOf(rn2.get("newAddr")))));
        check(Boolean.TRUE.equals(rv.get("applied")) && "1cq".equals(rv.get("newName")) && rv.get("historyId") != null && Boolean.TRUE.equals(post("symbol.resolve", Map.of("target", tid, "symbol", cq)).get("ok")),
                "rename.revert restores the original and records it: " + rv.get("oldName") + " → " + rv.get("newName"));
        Map<String, Object> rv2 = data(post("rename.revert", Map.of("target", tid, "symbol", cq)));
        check(Boolean.FALSE.equals(rv2.get("applied")) && rv2.get("note") != null, "rename.revert on an original name: nothing to do, said so");
        Map<String, Object> rvAgent = postAs("llm:agent-9", "rename.revert", Map.of("target", tid, "symbol", cq));
        check("E_SCOPE".equals(code(rvAgent)), "an agent cannot revert names");
        // the mock engine says what it cannot do
        // (the mock has no dex)

        // ================================================================ G. save → close → reopen: mappings, names and History persist
        check(Boolean.TRUE.equals(data(post("target.save", Map.of("target", tid))).get("saved")), "target.save writes the database");
        data(post("target.close", Map.of("target", tid)));
        String tid2 = String.valueOf(data(post("target.open", Map.of("path", fixture.getPath()))).get("id"));
        check(waitReady(tid2), "reopened as " + tid2);
        Map<String, Object> pmAgain = data(post("code.params", Map.of("target", tid2, "symbol", pm)));
        String pmText2 = String.valueOf(data(post("code.decompile", Map.of("target", tid2, "symbol", pm, "scope", "method"))).get("text"));
        check(List.of("intent", "intentSender", "flagsMask", "flagsValues").equals(names(pmAgain)) && pmText2.contains("fixture.Intent intent, fixture.IntentSender intentSender") && !((List<?>) pmAgain.get("mappings")).isEmpty(),
                "after save/reopen the analyst's parameter names and their History survive: " + names(pmAgain));
        Map<?, ?> anAgain = (Map<?, ?>) ((Map<?, ?>) pmAgain.get("decompiler")).get("annotationNamedArguments");
        check("some".equals(anAgain.get("status")) && List.of(2, 3).equals(anAgain.get("indices")), "…and the reopened method reads 'some': positions 2 and 3 still carry JEB's positional names, 0 and 1 the analyst's");
        check(List.of("retainedThird", "retainedSecond", "retainedFirst").equals(names(data(post("code.params", Map.of("target", tid2, "symbol", mt))))), "…the swap too");
        // the crash-window replay: History entries newer than the saved database are re-applied on open — a
        // parameters entry through the parameter binding (never as a rename of the METHOD), a revert through
        // the validated restoration (never through the identifier rule). Simulated like M2IT 4b: the entries
        // are appended to the sidecar after the save, the target reopened.
        Map<String, Object> rn3 = data(post("rename", Map.of("target", tid2, "symbol", cq, "name", "CourseMessageKey")));
        check(Boolean.TRUE.equals(data(post("target.save", Map.of("target", tid2))).get("saved")), "saved with 1cq renamed (the revert will be replayed onto it)");
        String jdb2 = null;
        for (Object o : (List<?>) data(post("workspace.state", Map.of())).get("targets")) if (o instanceof Map<?, ?> tm && tid2.equals(tm.get("id"))) jdb2 = String.valueOf(tm.get("jdb2"));
        data(post("target.close", Map.of("target", tid2)));
        File hfile = null;
        File jdbf = new File(jdb2);
        for (File cand : List.of(new File(jdbf.getAbsolutePath() + ".workbench"), new File(jdbf.getParentFile(), fixture.getName() + ".workbench"))) if (new File(cand, "history.json").isFile()) hfile = new File(cand, "history.json");
        if (hfile == null && jdbf.getParentFile() != null) for (File d : java.util.Objects.requireNonNullElse(jdbf.getParentFile().listFiles(), new File[0])) if (d.isDirectory() && new File(d, "history.json").isFile()) hfile = new File(d, "history.json");
        check(hfile != null, "the target's History sidecar found beside the database: " + hfile);
        Thread.sleep(1200);   // the replay compares entry timestamps with the database's mtime (second resolution on some file systems)
        @SuppressWarnings("unchecked") List<Object> hjson = (List<Object>) Json.parse(java.nio.file.Files.readString(hfile.toPath()));
        String ts = java.time.Instant.now().plusSeconds(2).toString();
        Map<String, Object> fakeMap = new LinkedHashMap<>();
        fakeMap.put("id", tid2 + "-hreplaymap"); fakeMap.put("op", "rename"); fakeMap.put("kind", "parameters"); fakeMap.put("actor", "recovery-test");
        fakeMap.put("addr", pm); fakeMap.put("newAddr", pm); fakeMap.put("oldName", "intent"); fakeMap.put("newName", "replayedIntent");
        fakeMap.put("changes", List.of(Map.of("index", 0, "oldName", "intent", "newName", "replayedIntent", "oldSource", "analyst")));
        fakeMap.put("source", "analyst"); fakeMap.put("target", tid2); fakeMap.put("ts", ts); fakeMap.put("undone", false);
        Map<String, Object> fakeRevert = new LinkedHashMap<>();
        fakeRevert.put("id", tid2 + "-hreplayrevert"); fakeRevert.put("op", "rename"); fakeRevert.put("kind", "class"); fakeRevert.put("actor", "recovery-test"); fakeRevert.put("revert", true);
        fakeRevert.put("addr", rn3.get("newAddr")); fakeRevert.put("newAddr", cq); fakeRevert.put("oldName", "CourseMessageKey"); fakeRevert.put("newName", "1cq");
        fakeRevert.put("target", tid2); fakeRevert.put("ts", ts); fakeRevert.put("undone", false);
        hjson.add(fakeMap); hjson.add(fakeRevert);
        java.nio.file.Files.writeString(hfile.toPath(), Json.write(hjson));
        String tid3 = String.valueOf(data(post("target.open", Map.of("path", fixture.getPath()))).get("id"));
        check(waitReady(tid3), "reopened for the replay as " + tid3);
        Map<String, Object> pmReplayed = data(post("code.params", Map.of("target", tid3, "symbol", pm)));
        Map<String, Object> info3 = data(post("target.info", Map.of("target", tid3)));
        check(Boolean.TRUE.equals(post("symbol.resolve", Map.of("target", tid3, "symbol", pm)).get("ok")) && "replayedIntent".equals(names(pmReplayed).get(0)) && "intentSender".equals(names(pmReplayed).get(1)),
                "replay: the parameters entry re-applied its change to argument 0 and the METHOD is still permuted (was: the method renamed to the joined argument names) — " + names(pmReplayed));
        check(Boolean.TRUE.equals(post("symbol.resolve", Map.of("target", tid3, "symbol", cq)).get("ok")) && !Boolean.TRUE.equals(post("symbol.resolve", Map.of("target", tid3, "symbol", String.valueOf(rn3.get("newAddr")))).get("ok")),
                "replay: the revert entry restored the original 1cq (was: E_SYNTAX bad Java identifier, the revert lost) — recoveredEdits=" + info3.get("recoveredEdits"));
        tid2 = tid3;

        // ================================================================ H. the WhatsApp artifacts (opt-in: -Pwa=<dir>)
        String wa = System.getenv("PARAMS_IT_WA");
        if (wa == null || wa.isBlank()) System.out.println("SKIP WhatsApp artifacts (run gradle paramsIT -Pwa=<dir holding the apks>)");
        else waArtifacts(wa);

        System.out.println();
        if (failures > 0) { System.out.println("PARAMS IT FAILED: " + failures + "/" + checks + " checks failed"); System.exit(1); }
        System.out.println("PARAMS IT PASSED: " + checks + " checks green");
        System.exit(0);
    }

    /** The real artifacts: the fleet apk (0gs.A0N branches, 1cq undo, a modern annotation), the historical
     *  2.23.4.6 fresh import (2XF.A00, the permuted 0XS and its aligned writeToParcel, 0hX, 2Xj), the
     *  pre-change 2.20.201.4 (15x.<init> aligned). Each is opened fresh in the hermetic home. */
    private static void waArtifacts(String dir) throws Exception {
        File fleet = new File(dir, "WhatsApp-2.26.38.70.apk"), hist = new File(System.getProperty("user.home"), "research/apk/wa/WhatsApp_2.23.4.6_apkcombo.com.apk"), pre = new File(dir, "WhatsApp-2.20.201.4.apk");
        if (!hist.isFile()) hist = new File(dir, "WhatsApp_2.23.4.6_apkcombo.com.apk");
        for (File f : List.of(fleet, hist, pre)) if (!f.isFile()) { System.out.println("SKIP " + f + " not present"); }
        if (fleet.isFile()) {
            System.out.println("--- fleet " + fleet.getName() + " sha256 " + sha256(java.nio.file.Files.readAllBytes(fleet.toPath())));
            String t = String.valueOf(data(post("target.open", Map.of("path", fleet.getPath()))).get("id"));
            check(waitReady(t), "fleet apk analysed as " + t);
            String a0n = "LX/0gs;->A0N(Landroid/os/Bundle;LX/1cq;Ljava/lang/String;)V";
            List<Map<String, Object>> rows = rows(data(post("code.smali", Map.of("target", t, "symbol", a0n))));
            List<String> hits = new ArrayList<>();
            for (Map<String, Object> r : rows) if ("insn".equals(r.get("kind")) && List.of("006e", "007a", "0086").contains(String.valueOf(r.get("offset")))) hits.add(r.get("offset") + " [" + r.get("bytes") + "] " + String.valueOf(r.get("text")).trim());
            boolean allB2 = hits.size() == 3 && hits.stream().allMatch(h -> h.endsWith(":l_b2"));
            boolean labelB2 = false; for (int i = 0; i + 1 < rows.size(); i++) if ("label".equals(rows.get(i).get("kind")) && String.valueOf(rows.get(i).get("text")).trim().equals(":l_b2") && "00b2".equals(rows.get(nextInsn(rows, i)).get("offset"))) labelB2 = true;
            boolean noStray = rows.stream().noneMatch(r -> "label".equals(r.get("kind")) && (String.valueOf(r.get("text")).trim().equals(":l_22") || String.valueOf(r.get("text")).trim().equals(":l_1c") || String.valueOf(r.get("text")).trim().equals(":l_16")));
            check(allB2 && labelB2 && noStray, "0gs.A0N: the three if-nez at 006e/007a/0086 land on :l_b2 with the label at 00b2, no stray :l_22/:l_1c/:l_16 — " + hits);
            // 1cq: rename → undo → redo → undo, save/reopen
            Map<String, Object> rn = data(post("rename", Map.of("target", t, "symbol", "LX/1cq;", "name", "CourseMessageKey")));
            Map<String, Object> un = post("history.undo", Map.of("target", t, "id", rn.get("historyId")));
            check(Boolean.TRUE.equals(un.get("ok")) && Boolean.TRUE.equals(post("symbol.resolve", Map.of("target", t, "symbol", "LX/1cq;")).get("ok")), "fleet: LX/1cq; renamed to CourseMessageKey and undone back to 1cq (was: E_SYNTAX bad Java identifier: 1cq)");
            data(post("history.redo", Map.of("target", t, "id", rn.get("historyId"))));
            data(post("history.undo", Map.of("target", t, "id", rn.get("historyId"))));
            check(Boolean.TRUE.equals(post("symbol.resolve", Map.of("target", t, "symbol", "LX/1cq;")).get("ok")) && Boolean.TRUE.equals(((Map<?, ?>) dataList(post("history.list", Map.of("target", t))).get(0)).get("undone")), "fleet: redo → undo again, the entry reads undone");
            Map<String, Object> modern = post("code.params", Map.of("target", t, "symbol", "LX/06k;->A09(ZLjava/lang/String;J)V"));
            check(Boolean.TRUE.equals(modern.get("ok")) && List.of("expression", "errorMessageTemplate", "p1").equals(((Map<?, ?>) data(modern).get("annotation")).get("names")), "fleet: a modern annotation-bearing method (06k.A09) reads [expression, errorMessageTemplate, p1]");
            /* the measurement: does JEB name the arguments from the annotation BY POSITION on the
               fleet build? 06k.A09 and every annotated method of a few classes — counted, printed, not asserted */
            int positional = 0, annotated = 0; List<String> positionalMethods = new ArrayList<>();
            for (String cls : List.of("LX/06k;", "LX/0gs;", "LX/1cq;", "LX/2XF;", "LX/0XS;")) {
                Map<String, Object> cd = post("code.decompile", Map.of("target", t, "symbol", cls, "scope", "class"));
                if (!Boolean.TRUE.equals(cd.get("ok")) || !(data(cd).get("methodParametersIndex") instanceof List<?> idx)) continue;
                for (Object o : idx) {
                    if (annotated >= 40) break;
                    if (!(o instanceof Map<?, ?> mm) || mm.get("addr") == null) continue;
                    Map<String, Object> one = post("code.params", Map.of("target", t, "symbol", String.valueOf(mm.get("addr"))));
                    if (!Boolean.TRUE.equals(one.get("ok"))) continue;
                    annotated++;
                    if (Boolean.TRUE.equals(((Map<?, ?>) data(one).get("decompiler")).get("appliesAnnotationByPosition"))) { positional++; positionalMethods.add(String.valueOf(mm.get("addr"))); }
                }
            }
            System.out.println("  MEASURED fleet 2.26.38.70: JEB names the arguments from the annotation by position on " + positional + " of " + annotated + " annotated methods sampled (06k.A09: " + ((Map<?, ?>) data(modern).get("decompiler")).get("appliesAnnotationByPosition") + ")" + (positionalMethods.isEmpty() ? "" : " — " + positionalMethods));
            check(annotated > 0, "fleet: the positional-naming measurement ran over " + annotated + " annotated methods (" + positional + " positional)");
            Map<String, Object> pk = data(post("evidence.packet", Map.of("target", t, "symbol", a0n, "java", false, "student", false)));
            check(((List<?>) pk.get("instructions")).stream().anyMatch(o -> String.valueOf(((Map<?, ?>) o).get("text")).matches("if-nez v\\d+, B2")), "fleet: the packet still renders the branches as JEB's absolute B2");
            data(post("target.close", Map.of("target", t, "save", false)));
        }
        if (hist.isFile()) {
            System.out.println("--- historical " + hist.getName() + " sha256 " + sha256(java.nio.file.Files.readAllBytes(hist.toPath())) + " (fresh import)");
            String t = String.valueOf(data(post("target.open", Map.of("path", hist.getPath()))).get("id"));
            check(waitReady(t), "2.23.4.6 analysed as " + t);
            String a00 = "LX/2XF;->A00(LX/0oQ;LX/0rP;I)LX/4Vc;";
            Map<String, Object> mp = data(post("code.params", Map.of("target", t, "symbol", a00)));
            String txt = String.valueOf(data(post("code.decompile", Map.of("target", t, "symbol", a00, "scope", "method"))).get("text"));
            check(List.of("waBaseActivity2", "parentGroupJid", "cadminDemoteOriginType2").equals(((Map<?, ?>) mp.get("annotation")).get("names")) && Boolean.TRUE.equals(((Map<?, ?>) mp.get("annotation")).get("countMatchesPrototype")),
                    "2XF.A00: the three retained names read verbatim, count matches, mapping unverified — the decompiler shows: " + txt.split("\n")[0]);
            check("empty".equals(((Map<?, ?>) mp.get("debugInfo")).get("status")) && mp.get("sourcesAgree") == null && Boolean.FALSE.equals(((Map<?, ?>) mp.get("decompiler")).get("appliesAnnotationByPosition")),
                    "2XF.A00: a debug_info table of null slots is 'empty' (no second source, no false conflict); JEB did not apply the list by position here");
            List<Map<String, Object>> sm = rows(data(post("code.smali", Map.of("target", t, "symbol", a00))));
            check(sm.stream().anyMatch(r -> "annotation".equals(r.get("kind")) && String.valueOf(r.get("text")).contains("waBaseActivity2")), "2XF.A00: the DEX view shows the annotation rows (was: omitted)");
            String xs = "LX/0XS;-><init>(Landroid/content/Intent;Landroid/content/IntentSender;II)V";
            Map<String, Object> xp = data(post("code.params", Map.of("target", t, "symbol", xs)));
            String xt = String.valueOf(data(post("code.decompile", Map.of("target", t, "symbol", xs, "scope", "method"))).get("text"));
            check(List.of("intentSender", "intent", "flagsMask", "flagsValues").equals(((Map<?, ?>) xp.get("annotation")).get("names")) && List.of("Landroid/content/Intent;", "Landroid/content/IntentSender;", "I", "I").equals(((Map<?, ?>) xp.get("prototype")).get("parameterTypes")) && "unverified".equals(xp.get("argumentMapping")),
                    "0XS.<init> (the real permutation): names [intentSender, intent, …] on a prototype (Intent, IntentSender, …) — list and prototype apart, unverified; decompiler: " + xt.split("\n")[0]);
            Map<String, Object> wp = data(post("code.params", Map.of("target", t, "symbol", "LX/0XS;->writeToParcel(Landroid/os/Parcel;I)V")));
            check(List.of("dest", "flags").equals(((Map<?, ?>) wp.get("annotation")).get("names")) && "unverified".equals(wp.get("argumentMapping")), "0XS.writeToParcel (the aligned control): [dest, flags] — still a separate, unverified list");
            Map<String, Object> hx = data(post("code.params", Map.of("target", t, "symbol", "LX/0hX;-><init>()V")));
            check(List.of("message").equals(((Map<?, ?>) hx.get("annotation")).get("names")) && ((List<?>) hx.get("parameters")).isEmpty(), "0hX.<init>: one retained name, zero arguments — assigned to nothing");
            Map<String, Object> xj = data(post("code.params", Map.of("target", t, "symbol", "LX/2Xj;->A00(LX/0oS;Ljava/util/Collection;IIJZZZZ)LX/30S;")));
            check(((List<?>) ((Map<?, ?>) xj.get("annotation")).get("names")).size() == 10 && ((List<?>) xj.get("parameters")).size() == 9 && Boolean.FALSE.equals(((Map<?, ?>) xj.get("annotation")).get("countMatchesPrototype")),
                    "2Xj.A00: ten names for nine formals (the wide J is one formal) — shown whole, mismatch flagged: " + ((Map<?, ?>) xj.get("annotation")).get("mismatch"));
            Map<String, Object> ch = data(post("target.changes", Map.of("target", t)));
            check(Boolean.FALSE.equals(ch.get("dirty")) && dataList(post("history.list", Map.of("target", t))).isEmpty(), "2.23.4.6: the reads dirtied nothing and wrote no History");
            data(post("target.close", Map.of("target", t, "save", false)));
        }
        if (pre.isFile()) {
            System.out.println("--- pre-change " + pre.getName() + " sha256 " + sha256(java.nio.file.Files.readAllBytes(pre.toPath())));
            String t = String.valueOf(data(post("target.open", Map.of("path", pre.getPath()))).get("id"));
            check(waitReady(t), "2.20.201.4 analysed as " + t);
            Map<String, Object> p15 = data(post("code.params", Map.of("target", t, "symbol", "LX/15x;-><init>(Landroid/os/IBinder;Ljava/lang/String;)V")));
            check(List.of("remote", "descriptor").equals(((Map<?, ?>) p15.get("annotation")).get("names")) && "unverified".equals(p15.get("argumentMapping")) && dataList(post("history.list", Map.of("target", t))).isEmpty(),
                    "15x.<init>: [remote, descriptor] read; aligned in fact, still reported unverified and nothing renamed by the read");
            data(post("target.close", Map.of("target", t, "save", false)));
        }
    }

    // ------------------------------------------------------------ decoding, per the DEX spec (the test's own, independent of the engine)

    /** The destination of a branching / payload-referencing instruction from its raw bytes: the
     *  operand is a signed displacement in 16-bit code units from the instruction. goto: int8 at
     *  byte 1; goto/16 and if-*: int16 LE at bytes 2-3; goto/32, packed/sparse-switch,
     *  fill-array-data: int32 LE at bytes 2-5. Null for anything else. */
    static Long decodeDest(long offset, String bytesHex) {
        String[] b = bytesHex.trim().split("\\s+");
        int op = Integer.parseInt(b[0], 16);
        if (op == 0x28) return offset + 2L * (byte) Integer.parseInt(b[1], 16);
        if (op == 0x29 || (op >= 0x32 && op <= 0x3d)) return offset + 2L * (short) ((Integer.parseInt(b[3], 16) << 8) | Integer.parseInt(b[2], 16));
        if (op == 0x2a || op == 0x2b || op == 0x2c || op == 0x26) {
            int d = Integer.parseInt(b[2], 16) | Integer.parseInt(b[3], 16) << 8 | Integer.parseInt(b[4], 16) << 16 | Integer.parseInt(b[5], 16) << 24;
            return offset + 2L * d;
        }
        return null;
    }

    /** The index of the first insn row after a label row (comments and labels may sit between). */
    static int nextInsn(List<Map<String, Object>> rows, int from) {
        for (int i = from + 1; i < rows.size(); i++) if ("insn".equals(rows.get(i).get("kind"))) return i;
        return rows.size() - 1;
    }

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> rows(Map<String, Object> smali) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object o : (List<?>) smali.get("rows")) out.add((Map<String, Object>) o);
        return out;
    }
    static List<String> names(Map<String, Object> params) { List<String> out = new ArrayList<>(); for (Object o : (List<?>) params.get("parameters")) out.add(String.valueOf(((Map<?, ?>) o).get("name"))); return out; }
    static List<Integer> regs(Map<String, Object> params) { List<Integer> out = new ArrayList<>(); for (Object o : (List<?>) params.get("parameters")) out.add(((Number) ((Map<?, ?>) o).get("register")).intValue()); return out; }
    static String sha256(byte[] b) throws Exception { java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256"); StringBuilder sb = new StringBuilder(); for (byte x : md.digest(b)) sb.append(String.format("%02x", x)); return sb.toString(); }
    @SuppressWarnings("unchecked")
    static List<Object> dataList(Map<String, Object> resp) { if (!Boolean.TRUE.equals(resp.get("ok"))) throw new IllegalStateException("op failed: " + resp.get("error")); return (List<Object>) resp.get("data"); }

    // ------------------------------------------------------------ helpers

    static boolean waitReady(String tid) throws Exception {
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

    static Map<String, Object> post(String op, Map<String, Object> params) throws Exception { return postAs("human", op, params); }

    static Map<String, Object> postAs(String actor, String op, Map<String, Object> params) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("params", params);
        body.put("actor", actor);
        HttpRequest req = HttpRequest.newBuilder(URI.create(BASE + "/api/" + op)).header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(Json.write(body))).build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        return Json.parseObject(resp.body());
    }

    static String code(Map<String, Object> r) { return Boolean.TRUE.equals(r.get("ok")) ? "ok" : r.get("error") instanceof Map<?, ?> e ? String.valueOf(e.get("code")) : "?"; }
    static Object message(Map<String, Object> r) { return r.get("error") instanceof Map<?, ?> e ? e.get("message") : null; }
    static void check(boolean ok, String label) { checks++; System.out.println((ok ? "PASS " : "FAIL ") + label); if (!ok) failures++; }
    static List<String> strings(Object o) { List<String> out = new ArrayList<>(); if (o instanceof List<?> l) for (Object x : l) out.add(String.valueOf(x)); return out; }

    @SuppressWarnings("unchecked")
    static Map<String, Object> data(Map<String, Object> resp) {
        if (!Boolean.TRUE.equals(resp.get("ok"))) throw new IllegalStateException("op failed: " + resp.get("error"));
        Object d = resp.get("data");
        if (!(d instanceof Map)) throw new IllegalStateException("expected map data, got: " + (d == null ? "null" : d.getClass().getSimpleName()));
        return (Map<String, Object>) d;
    }
}
