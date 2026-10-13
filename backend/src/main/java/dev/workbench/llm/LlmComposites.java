package dev.workbench.llm;

import dev.workbench.registry.OpException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Multi-store composites for the provider registry (llm-config design §6.2).
 *
 * CALL UNDER AgentManager's MONITOR — that is what serializes a rename against a
 * concurrent configure / remove / spawn (the Dispatcher does not serialize ops).
 * Store monitors are NEVER nested here: each stage runs inside one store's own
 * synchronized method (its monitor), persists, and returns before the next
 * stage — so the tree's only nested acquisition order (tasks → registry →
 * keystore, TaskSettings.list/configure) is never contended and cannot deadlock
 * (review N1). Accepted consequence: a task.list landing between the registry
 * and the tasks commit sees `unresolved` for that one read.
 *
 * public: the IT drives it directly with stores built on different directories
 * to exercise the failure paths a shared sidecar dir cannot produce.
 */
public final class LlmComposites {
    private LlmComposites() {}

    private static boolean envSet(String var) {
        String v = System.getenv(var);
        return v != null && !v.isBlank();
    }

    /** llm.rename {id, newId}: validated up front (nothing mutated), then staged per
     *  store and committed keys → registry → tasks — keys first because a crash must
     *  never lose one. Any failed write reverts that store, undoes the stores already
     *  committed, and throws E_IO whose message states the exact on-disk state and
     *  the recovery (the residual-state matrix of §6.2). No shape loses a key. */
    public static Map<String, Object> rename(LlmRegistry registry, KeyStore keys, TaskSettings tasks,
            String id, String newId) throws OpException {
        registry.checkRename(id, newId);   // slug, exists, built-in, registered collision, env-name clash
        String from = id.trim(), to = newId.trim();
        if (registry.isDegraded() || keys.isDegraded() || tasks.isDegraded())
            throw OpException.busy("a configuration store is degraded (its on-disk evidence is parked) — rename refused until it is repaired", "");
        boolean wasCustom = "custom".equals(from);
        String envOld = KeyStore.envName(from), envNew = KeyStore.envName(to);
        boolean envOldSet = envSet(envOld), envNewSet = envSet(envNew);

        // ---- 1. keys: an atomic check-and-move; an E_IO from here means nothing changed anywhere
        KeyStore.Move mv = keys.move(from, to);
        if (mv == KeyStore.Move.COLLISION)
            throw OpException.syntax("a key is already stored under " + to,
                    registry.get(to) == null
                            ? "no provider is registered there — the residue of an interrupted rename: llm.configure {id:\"" + to
                                    + "\", create:true} adopts it, llm.setkey {id:\"" + to + "\", key:\"\"} discards it"
                            : "");
        boolean keyMoved = mv == KeyStore.Move.MOVED;

        // ---- 2. registry: the entry moves at the same position; undo the key move if this fails
        try {
            registry.applyRename(from, to);
        } catch (OpException e) {
            if (keyMoved) {
                KeyStore.Move undo;
                try { undo = keys.move(to, from); } catch (OpException u) { undo = null; }
                if (undo != KeyStore.Move.MOVED)   // threw, OR returned COLLISION / NOTHING_UNDER_ID: the undo did NOT happen (review r-f31a574 M3)
                    throw OpException.io("llm.json was not written, and undoing the key move ALSO failed — on disk the key is"
                            + " stored under " + to + " while llm.json still names " + from + ". After a restart " + from
                            + " is configured:false and " + to + " holds an orphan key: llm.configure {id:\"" + to
                            + "\", create:true} adopts it, then llm.remove " + from + ".", e.getMessage());
            }
            throw OpException.io("llm.json was not written — nothing changed"
                    + (keyMoved ? " (the key move was undone)" : ""), e.getMessage());   // no key parenthetical when none moved (review r-f31a574 L7)
        }

        // ---- 3. tasks: pins id:m → newId:m; undo the registry and the key if this fails
        List<Map<String, Object>> rewritten;
        try {
            rewritten = tasks.renameProvider(from, to);
        } catch (OpException e) {
            boolean regUndone = true, keyUndone = true;
            try { registry.applyRename(to, from); } catch (OpException u) { regUndone = false; }
            if (keyMoved) {   // undo the key move; a COLLISION / NOTHING_UNDER_ID return counts as NOT undone, exactly like a throw (review r-f31a574 M3)
                KeyStore.Move undo;
                try { undo = keys.move(to, from); } catch (OpException u) { undo = null; }
                keyUndone = undo == KeyStore.Move.MOVED;
            }
            String head = "tasks.json was not written";
            String moves = keyMoved ? "the registry and key moves were undone" : "the registry move was undone";   // no phantom key move when none happened (review r-f31a574 L7)
            if (regUndone && keyUndone)
                throw OpException.io(head + " — nothing changed (" + moves + ")", e.getMessage());
            if (!regUndone && keyUndone)
                throw OpException.io(head + ", and undoing llm.json ALSO failed — on disk llm.json names " + to
                        + (keyMoved ? ", the key is back under " + from : "")
                        + ", tasks.json still names " + from + ": " + to
                        + " has no key and the pins dangle — llm.setkey {id:\"" + to + "\", …} and re-pin, or llm.rename "
                        + to + " → " + from + " to go back.", e.getMessage());
            if (!regUndone)   // and the key undo failed too (only reachable when keyMoved)
                throw OpException.io(head + ", and the undos ALSO failed — on disk llm.json names " + to + ", the key is under "
                        + to + ", tasks.json still names " + from + ": only the pins dangle — re-pin the listed depths to "
                        + to + ".", e.getMessage());
            throw OpException.io(head + ", and undoing the key move ALSO failed — llm.json names " + from   // regUndone && !keyUndone (only reachable when keyMoved)
                    + " but the key is stored under " + to + ": after a restart " + from + " is configured:false and " + to
                    + " holds an orphan key — llm.configure {id:\"" + to + "\", create:true} adopts it, then llm.remove "
                    + from + ".", e.getMessage());
        }

        if (wasCustom) registry.reseedCustom();   // the shipped empty `custom` is back at once, not at the next boot

        Map<String, Object> out = new LinkedHashMap<>(registry.row(to));
        out.put("renamedFrom", from);
        out.put("tasksRewritten", rewritten);
        boolean changed = envOldSet || envNewSet;   // either variable changes which credential the provider authenticates with
        out.put("effectiveKeyChanged", changed);
        if (changed) {
            StringBuilder hint = new StringBuilder();
            if (envOldSet) hint.append(envOld).append(" is set in the server's environment and no longer applies — move it to ")
                    .append(envNew).append(". ");
            if (envNewSet) hint.append(envNew).append(" is set in the server's environment and now shadows the stored key. ");
            out.put("hint", hint.toString().trim());
        }
        return out;
    }
}
