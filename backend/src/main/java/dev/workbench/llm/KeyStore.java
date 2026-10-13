package dev.workbench.llm;

import dev.workbench.registry.Json;
import dev.workbench.registry.OpException;
import java.io.File;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * API-key storage for LLM providers (llm-keys.json, gitignored, owner-only).
 * Keys are WRITE-ONLY: ops can set/replace a key and report configured:true/false,
 * but no op ever returns key material. Precedence: the environment override
 * WORKBENCH_LLM_&lt;ID&gt;_KEY (id uppercased, non-alphanumerics → _) beats the file.
 *
 * Persistence (fix-1-r3 #5 — this was the worst sidecar in the tree: a silent
 * load failure emptied the map and the next set() DESTROYED every other
 * provider's key, and the plain writeString + post-hoc chmod left both a
 * torn-write and a world-readable window): reads follow the SidecarIO split
 * (transient → loud + degraded, permanent → parked evidence), writes go through
 * writeAtomic whose createTempFile tmp is 0600 FROM CREATION — the atomic move
 * lands the keys owner-only, no post-write chmod race.
 *
 * Store contract (llm-config design §5): a mutator snapshots what it touches,
 * persists, and on a failed write REVERTS memory and throws E_IO. A key that only
 * lived in memory would report configured:true on every surface (llm.providers,
 * doctor, the spawn gate) and vanish at restart — the operator re-pastes once,
 * told immediately, with the file named.
 */
public final class KeyStore {

    /** the outcome of an atomic {@link #move}: the check and the move are one step. */
    public enum Move { MOVED, NOTHING_UNDER_ID, COLLISION }

    private final File file;
    private final Map<String, String> keys = new LinkedHashMap<>();
    /* transient read failure at load: the on-disk keyring may be healthy — the
       first persist parks it aside as evidence instead of silently overwriting
       every provider's key with a one-entry map (fix-1-r3 #5) */
    private boolean degraded;

    public KeyStore(File sidecarDir) {
        this.file = new File(sidecarDir, "llm-keys.json");
        load();
    }

    @SuppressWarnings("unchecked")
    private void load() {
        if (!file.isFile()) return;
        try {
            Object parsed = Json.parse(Files.readString(file.toPath()));
            if (parsed instanceof Map)
                for (Map.Entry<String, Object> e : ((Map<String, Object>) parsed).entrySet())
                    keys.put(e.getKey(), String.valueOf(e.getValue()));
            else
                /* right file, wrong shape — as corrupt as torn JSON. A FAILED
                   park leaves it in place — mark degraded (fix-1-r5 #2) */
                if (!dev.workbench.registry.SidecarIO.parkCorrupt(file, "llm-keys",
                        new IllegalStateException("expected a JSON object")))
                    degraded = true;
        } catch (java.io.IOException e) {
            /* transient read hiccup ≠ corruption: routed by permanence — a
               charset-parked file is already aside; a transient one marks the
               store degraded (see persist) */
            if (!dev.workbench.registry.SidecarIO.readFailedOrPark(file, "llm-keys", e))
                degraded = true;
        } catch (Exception e) {
            if (!dev.workbench.registry.SidecarIO.parkCorrupt(file, "llm-keys", e))
                degraded = true;   /* park failed — the keyring is still in place;
                    the next persist retries the park first (fix-1-r5 #2) */
        }
    }

    /** Env var name for a provider id: deepseek → WORKBENCH_LLM_DEEPSEEK_KEY. */
    public static String envName(String id) {
        return "WORKBENCH_LLM_" + id.toUpperCase().replaceAll("[^A-Z0-9]", "_") + "_KEY";
    }

    /** Providers whose key lives in a FILE the deployment supplies (a deployment's gateway
     *  credential, a systemd credential file): read at call time, so a rotation needs no restart;
     *  never written to the keyring, never echoed. */
    private final Map<String, java.io.File> fileKeys = new LinkedHashMap<>();

    public synchronized void fileBacked(String id, java.io.File f) { fileKeys.put(id, f); }

    /** Key for a provider: a file-backed key first, then the env override, then the keyring. Null when absent. */
    public synchronized String get(String id) {
        java.io.File f = fileKeys.get(id);
        if (f != null) {
            try { String t = java.nio.file.Files.readString(f.toPath()).trim(); return t.isEmpty() ? null : t; }
            catch (java.io.IOException e) { return null; }
        }
        String env = System.getenv(envName(id));
        if (env != null && !env.isBlank()) return env;
        return keys.get(id);
    }

    /** "configured": env override or file. */
    public synchronized boolean has(String id) {
        return get(id) != null;
    }

    /** ENV-BLIND membership: is a key stored in the FILE under this id. The rename
     *  collision check needs this — {@link #has} would false-block on a
     *  WORKBENCH_LLM_&lt;NEWID&gt;_KEY set in the environment (design §5, review H2). */
    public synchronized boolean hasStored(String id) {
        return keys.containsKey(id);
    }

    /** a transient load failure left the on-disk keyring un-parked; composites refuse (§5) */
    public synchronized boolean isDegraded() {
        return degraded;
    }

    /** Write-only set. Persists the whole map, atomically, owner-only. On a failed
     *  write the change is REVERTED and E_IO thrown (§5) — never a key that is
     *  "configured" in memory and gone at restart. */
    public synchronized Map<String, Object> set(String id, String key) throws OpException {
        boolean had = keys.containsKey(id);
        String prev = keys.get(id);
        if (key == null || key.isBlank()) keys.remove(id);
        else keys.put(id, key);
        if (!persist()) {
            if (had) keys.put(id, prev); else keys.remove(id);
            throw OpException.io("llm-keys.json was not written — the key was not saved (evidence parked; see server log)",
                    file.getPath());
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", id);
        out.put("configured", has(id));
        return out;
    }

    /** Atomic check-and-move for a provider rename (design §5, review N5): the
     *  {@link #hasStored} check and the move happen under this monitor in one step,
     *  so a concurrent llm.setkey {id:newId} — which takes only this monitor — can
     *  neither slip in between nor be overwritten. Persists; on a failed write the
     *  move is reverted and E_IO thrown with "nothing changed". */
    public synchronized Move move(String id, String newId) throws OpException {
        if (keys.containsKey(newId)) return Move.COLLISION;
        if (!keys.containsKey(id)) return Move.NOTHING_UNDER_ID;
        String v = keys.remove(id);
        keys.put(newId, v);
        if (!persist()) {
            keys.remove(newId);
            keys.put(id, v);
            throw OpException.io("llm-keys.json was not written — nothing changed (evidence parked; see server log)",
                    file.getPath());
        }
        return Move.MOVED;
    }

    /** true = durable on disk. The degraded-skip path is a FALSE here (it used to
     *  return silently while the caller reported success — review H1). */
    private boolean persist() {
        if (degraded) {
            /* never overwrite un-parked evidence: a failed park keeps the flag
               and SKIPS the write (fix-1-r4 #5) */
            if (!dev.workbench.registry.SidecarIO.parkHealthyBeforeOverwrite(file, "llm-keys"))
                return false;
            degraded = false;
        }
        return dev.workbench.registry.SidecarIO.writeAtomic(file, Json.write(keys), "llm-keys");
    }
}
