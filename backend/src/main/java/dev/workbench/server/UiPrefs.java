package dev.workbench.server;

import dev.workbench.registry.Json;
import dev.workbench.registry.OpException;
import java.io.File;
import java.nio.file.Files;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The operator's chrome preferences (UI audit decision 17, 2026-09-10): theme, side/dock
 * visibility, splitter widths, dock height. Server-side beside layouts.json so they follow
 * the workspace across machines (the owner works from several); named layouts stay explicit,
 * this is the one "current" chrome. Persisted at backend/.workbench/ui.json; validated keys
 * only, so a client cannot park arbitrary JSON here.
 */
public final class UiPrefs {

    private final File file;
    private final Map<String, Object> prefs = new LinkedHashMap<>();
    private boolean degraded;

    public UiPrefs(File dir) {
        this.file = new File(dir, "ui.json");
        load();
    }

    @SuppressWarnings("unchecked")
    private void load() {
        if (!file.isFile()) return;
        try {
            Object p = Json.parse(Files.readString(file.toPath()));
            if (p instanceof Map) prefs.putAll((Map<String, Object>) p);
        } catch (java.io.IOException e) {
            if (!dev.workbench.registry.SidecarIO.readFailedOrPark(file, "ui", e)) degraded = true;
        } catch (Exception e) {
            if (!dev.workbench.registry.SidecarIO.parkCorrupt(file, "ui", e)) degraded = true;
        }
    }

    private void persist() {
        if (degraded) {
            if (!dev.workbench.registry.SidecarIO.parkHealthyBeforeOverwrite(file, "ui")) return;
            degraded = false;
        }
        dev.workbench.registry.SidecarIO.writeAtomic(file, Json.write(prefs), "ui");
    }

    /** ui.prefs → the stored object (empty when nothing was ever set). */
    public synchronized Map<String, Object> get() {
        return new LinkedHashMap<>(prefs);
    }

    /** ui.prefs.set {prefs}: merge the known keys — theme light|dark, side/dock booleans,
     *  sideW/rightW/dockH pixels (clamped 80..2000). Unknown keys are E_SYNTAX. */
    public synchronized Map<String, Object> set(Map<String, Object> patch) throws OpException {
        for (Map.Entry<String, Object> e : patch.entrySet()) {
            String k = e.getKey();
            Object v = e.getValue();
            switch (k) {
                case "theme" -> {
                    if (!"light".equals(v) && !"dark".equals(v)) throw OpException.syntax("theme must be light|dark", "theme=" + v);
                    prefs.put(k, v);
                }
                case "side", "dock" -> {
                    if (!(v instanceof Boolean)) throw OpException.syntax(k + " must be a boolean", k + "=" + v);
                    prefs.put(k, v);
                }
                case "sideW", "rightW", "dockH" -> {
                    if (!(v instanceof Number n)) throw OpException.syntax(k + " must be a number", k + "=" + v);
                    prefs.put(k, Math.max(80, Math.min(2000, n.intValue())));
                }
                default -> throw OpException.syntax("unknown pref: " + k, "known: theme, side, dock, sideW, rightW, dockH");
            }
        }
        prefs.put("ts", Instant.now().toString());
        persist();
        return get();
    }

    /** ui.prefs.reset: back to the defaults (an empty object — the client's own defaults apply). */
    public synchronized Map<String, Object> reset() {
        prefs.clear();
        persist();
        return get();
    }
}
