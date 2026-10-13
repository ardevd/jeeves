package dev.workbench.server;

import dev.workbench.registry.Json;
import dev.workbench.registry.OpException;
import dev.workbench.registry.SidecarIO;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Whether an agent's writes (rename, comment, tag, map verdict) wait for a human or apply at
 * once — a switch on the application side, not a property of the tools (John, 2026-09-18: a
 * mandatory review "hurts automation"; headless runs have nobody to click).
 *
 *   mode           auto   — THE DEFAULT: a write is applied at once, recorded in History with
 *                           the agent as actor ("… auto-applied"), undoable like any edit; its
 *                           record is kept, settled as accepted with auto:true. An agent's own
 *                           later reads then carry its names — the point of renaming.
 *                  manual — every write is a pending proposal (invisible to later reads)
 *   kinds          which kinds auto applies to (default all four); the others still queue
 *   minConfidence  0 = any; above it a write needs a confidence ≥ the floor to apply directly,
 *                  the rest queue for review
 *
 * One policy for every agent actor, missions included (the mission prompt has ONE text: names
 * apply at once; under manual the tool result says "pending"). WORKBENCH_REVIEW_MODE=auto|manual
 * in the server's environment wins over the stored mode. Persisted at <home>/review.json.
 */
public final class ReviewPolicy {
    public static final List<String> KINDS = List.of("rename", "comment", "tag", "verdict");
    private final File file;
    private String mode = "auto";   // apply directly is the default since 2026-09-18 (John: the human reverts, he does not pre-approve)
    private final Set<String> kinds = new LinkedHashSet<>(KINDS);
    private double minConfidence = 0;

    public ReviewPolicy(File dir) {
        this.file = new File(dir, "review.json");
        load();
    }

    @SuppressWarnings("unchecked")
    private void load() {
        if (!file.isFile()) return;
        try {
            Object p = Json.parse(Files.readString(file.toPath()));
            if (!(p instanceof Map)) return;
            Map<String, Object> m = (Map<String, Object>) p;
            if ("auto".equals(m.get("mode")) || "manual".equals(m.get("mode"))) mode = String.valueOf(m.get("mode"));
            if (m.get("kinds") instanceof List<?> l) {
                kinds.clear();
                for (Object o : l) if (KINDS.contains(String.valueOf(o))) kinds.add(String.valueOf(o));
            }
            if (m.get("minConfidence") instanceof Number n) minConfidence = clamp(n.doubleValue());
        } catch (Exception e) {
            System.err.println("review policy: unreadable " + file + " (" + e + ") — manual review stays the mode");
        }
    }

    private static double clamp(double d) { return Math.max(0, Math.min(1, d)); }

    private static String envMode() {
        String e = System.getenv("WORKBENCH_REVIEW_MODE");
        return e != null && (e.equals("auto") || e.equals("manual")) ? e : null;
    }

    public synchronized Map<String, Object> toMap() {
        Map<String, Object> out = new LinkedHashMap<>();
        String env = envMode();
        out.put("mode", env != null ? env : mode);
        out.put("storedMode", mode);
        if (env != null) out.put("envOverride", "WORKBENCH_REVIEW_MODE=" + env);
        out.put("kinds", new ArrayList<>(kinds));
        out.put("minConfidence", minConfidence);
        return out;
    }

    /** A merge: an absent field keeps its value. */
    public synchronized Map<String, Object> configure(String newMode, Object newKinds, Object newMin) throws OpException {
        if (newMode != null && !newMode.isBlank()) {
            if (!newMode.equals("auto") && !newMode.equals("manual"))
                throw OpException.syntax("review mode must be auto or manual", "mode=" + newMode);
            mode = newMode;
        }
        if (newKinds != null) {
            List<String> want = new ArrayList<>();
            if (newKinds instanceof List<?> l) for (Object o : l) want.add(String.valueOf(o).trim());
            else for (String s : String.valueOf(newKinds).split(",")) if (!s.isBlank()) want.add(s.trim());
            for (String k : want)
                if (!KINDS.contains(k)) throw OpException.syntax("unknown kind '" + k + "' — one of " + KINDS, "kinds=" + newKinds);
            kinds.clear();
            kinds.addAll(want);
        }
        if (newMin != null) {
            double d;
            try { d = newMin instanceof Number n ? n.doubleValue() : Double.parseDouble(String.valueOf(newMin)); }
            catch (NumberFormatException e) { throw OpException.syntax("minConfidence must be a number from 0 to 1", "minConfidence=" + newMin); }
            if (d < 0 || d > 1) throw OpException.syntax("minConfidence must be from 0 to 1", "minConfidence=" + newMin);
            minConfidence = d;
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("mode", mode);
        m.put("kinds", new ArrayList<>(kinds));
        m.put("minConfidence", minConfidence);
        SidecarIO.writeAtomic(file, Json.write(m), "review");
        return toMap();
    }

    /** Does this write apply directly? missionMode (auto|manual|null) overrides the mode only. */
    public synchronized boolean appliesDirectly(String kind, Object confidence, String missionMode) {
        String env = envMode();
        String eff = missionMode != null && !missionMode.isBlank() ? missionMode : (env != null ? env : mode);
        if (!"auto".equals(eff) || !kinds.contains(kind)) return false;
        if (minConfidence <= 0) return true;
        double c;
        try { c = confidence instanceof Number n ? n.doubleValue() : Double.parseDouble(String.valueOf(confidence)); }
        catch (Exception e) { return false; }                     // no confidence given: below any floor
        return c >= minConfidence;
    }
}
