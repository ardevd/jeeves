package dev.workbench.server;

import dev.workbench.registry.Json;
import dev.workbench.registry.OpException;
import java.io.File;
import java.nio.file.Files;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Named user layouts (WORKBENCH_SPEC §4 roadmap): saveable/restorable view
 * configurations, like IDA desktops — persisted per workspace, shared across
 * targets. A snapshot is the client's own JSON (panel/dock arrangement); the
 * store never interprets it, and applying it to the DOM is the frontend's job
 * (layout.apply just returns the stored snapshot). Persisted at
 * backend/.workbench/layouts.json as { "<name>": { ts, layout } }.
 */
public final class LayoutStore {

    private final File file;
    private final Map<String, Map<String, Object>> layouts = new LinkedHashMap<>();
    /* set by a TRANSIENT read failure at load: the on-disk file may be healthy,
       so the first persist parks it aside as evidence before writing
       (fix-1-r3 #1 — an empty in-memory start silently overwriting saved
       layouts was the failure the flag closes) */
    private boolean degraded;

    public LayoutStore(File dir) {
        this.file = new File(dir, "layouts.json");
        load();
    }

    @SuppressWarnings("unchecked")
    private void load() {
        if (!file.isFile()) return;
        try {
            Object p = Json.parse(Files.readString(file.toPath()));
            if (p instanceof Map)
                for (Map.Entry<String, Object> e : ((Map<String, Object>) p).entrySet())
                    if (e.getValue() instanceof Map)
                        layouts.put(e.getKey(), new LinkedHashMap<>((Map<String, Object>) e.getValue()));
        } catch (java.io.IOException e) {
            /* transient read hiccup ≠ corruption (fix-1-r2 F2): routed by
               permanence — a charset-parked file needs no degraded flag (the
               evidence is already aside); a transient one sets it, so the first
               persist parks the healthy original before overwriting */
            if (!dev.workbench.registry.SidecarIO.readFailedOrPark(file, "layouts", e))
                degraded = true;
        } catch (Exception e) {
            /* a FAILED park leaves the torn file in place — mark degraded
               (fix-1-r5 #2) */
            if (!dev.workbench.registry.SidecarIO.parkCorrupt(file, "layouts", e))
                degraded = true;
        }
    }

    private void persist() {
        if (degraded) {
            /* never overwrite un-parked evidence: a failed park keeps the flag
               and SKIPS the write (fix-1-r4 #5) — the next persist retries */
            if (!dev.workbench.registry.SidecarIO.parkHealthyBeforeOverwrite(file, "layouts"))
                return;
            degraded = false;
        }
        dev.workbench.registry.SidecarIO.writeAtomic(file, Json.write(layouts), "layouts");
    }

    /** layout.list → [{name, ts}…] (insertion order; re-saving keeps the slot). */
    public synchronized List<Object> list() {
        List<Object> out = new ArrayList<>();
        for (Map.Entry<String, Map<String, Object>> e : layouts.entrySet()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("name", e.getKey());
            row.put("ts", e.getValue().get("ts"));
            out.add(row);
        }
        return out;
    }

    /** layout.save {name, layout}: upsert one named snapshot. Returns {name, ts}. */
    public synchronized Map<String, Object> save(String name, Map<String, Object> layout) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("ts", Instant.now().toString());
        entry.put("layout", new LinkedHashMap<>(layout));
        layouts.put(name, entry);
        persist();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("name", name);
        out.put("ts", entry.get("ts"));
        return out;
    }

    /** layout.apply {name}: the stored snapshot — the client applies it to its DOM. */
    public synchronized Map<String, Object> get(String name) throws OpException {
        Map<String, Object> entry = layouts.get(name);
        if (entry == null)
            throw OpException.notFound("layout not found: " + name,
                    "saved layouts: " + layouts.size());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("name", name);
        out.put("ts", entry.get("ts"));
        out.put("layout", entry.get("layout"));
        return out;
    }

    /** layout.delete {name} → E_NOTFOUND on unknown. */
    public synchronized Map<String, Object> delete(String name) throws OpException {
        if (layouts.remove(name) == null)
            throw OpException.notFound("layout not found: " + name,
                    "saved layouts: " + layouts.size());
        persist();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("name", name);
        out.put("deleted", true);
        return out;
    }
}
