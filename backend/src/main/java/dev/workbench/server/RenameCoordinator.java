package dev.workbench.server;

import dev.workbench.registry.OpException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/** Serializes a target's edits with its confirmed saves, and remembers unsaved rename owners.
 *
 * The identity is the DEX original signature, not a display address that moves on a rename.
 * Claim operations require the target's transaction gate: a check separated from its write
 * would merely move the race. Acquire this gate before, never while holding, Workspace's monitor.
 * There is no queue limit or waiting deadline; unrelated targets have independent gates.
 */
public final class RenameCoordinator {
    @FunctionalInterface
    public interface Work<T> { T run() throws OpException; }

    public record Claim(String originalSymbol, String currentName, String actor, String historyId) {}

    private static final class Target {
        final ReentrantLock gate = new ReentrantLock();
        final Map<String, Claim> claims = new LinkedHashMap<>();
    }
    private final Map<String, Target> targets = new ConcurrentHashMap<>();

    private Target target(String id) {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("a target identity is required");
        return targets.computeIfAbsent(id, ignored -> new Target());
    }

    public <T> T transact(String target, Work<T> work) throws OpException {
        Target state = target(target);
        state.gate.lock();
        try { return work.run(); }
        finally { state.gate.unlock(); }
    }

    private Target held(String id) {
        Target state = target(id);
        if (!state.gate.isHeldByCurrentThread())
            throw new IllegalStateException("rename claims must be accessed inside the target write transaction");
        return state;
    }

    /** Normalize the actor spellings used by MCP and auto-applied/history proposal records. */
    public static String owner(String actor) {
        if (actor == null || actor.isBlank()) return "human";
        String out = actor.trim();
        for (;;) {
            String previous = out;
            out = out.replaceFirst(" (auto-applied|accepted by human)$", "")
                    .replaceFirst(" \\(follows class rename\\)$", "");
            if (out.equals(previous)) break;
        }
        while (out.startsWith("llm:") || out.startsWith("agent:"))
            out = out.substring(out.indexOf(':') + 1);
        return out;
    }

    /** Check both a caller's optional compare-and-set precondition and unsaved ownership. */
    public void check(String target, String originalSymbol, String currentName,
                      String actor, String expectedName) throws OpException {
        Claim claim = held(target).claims.get(originalSymbol);
        if (expectedName != null && !expectedName.equals(currentName)) {
            OpException ex = conflict(originalSymbol, currentName, claim,
                    "The symbol is currently named '" + currentName + "', not the expected name '"
                            + expectedName + "'; your rename was not applied.");
            ex.extra.put("expectedName", expectedName);
            throw ex;
        }
        if ("human".equals(EventBus.actorKind(actor))) return;
        if (claim != null && !owner(claim.actor()).equals(owner(actor))) {
            throw conflict(originalSymbol, currentName, claim,
                    "The symbol is currently named '" + currentName + "' by '" + owner(claim.actor())
                            + "' and that rename has not been saved; your rename was not applied.");
        }
    }

    private static OpException conflict(String originalSymbol, String currentName,
                                        Claim claim, String message) {
        OpException ex = new OpException("RENAME_CONFLICT", message, "symbol=" + originalSymbol);
        ex.extra.put("originalSymbol", originalSymbol);
        ex.extra.put("currentName", currentName);
        ex.extra.put("unsaved", claim != null);
        if (claim != null) {
            ex.extra.put("currentActor", owner(claim.actor()));
            if (claim.historyId() != null) ex.extra.put("historyId", claim.historyId());
        }
        ex.extra.put("hint", "Read the current symbol and use its assigned name. Do not retry a conflicting rename unchanged.");
        return ex;
    }

    /** Call immediately after the engine write, even if recording the history then fails.
     * A second call may attach the history id without changing ownership. */
    public void renamed(String target, String originalSymbol, String currentName,
                        String actor, String historyId) {
        Target state = held(target);
        if ("human".equals(EventBus.actorKind(actor))) state.claims.remove(originalSymbol);
        else state.claims.put(originalSymbol, new Claim(originalSymbol, currentName, actor, historyId));
    }

    /** Only after native save AND the corresponding recovery history have persisted. */
    public void saved(String target) { held(target).claims.clear(); }

    public void forget(String target, String originalSymbol) { held(target).claims.remove(originalSymbol); }

    /** Replace after undo/redo or reopening, using the latest active UNSAVED entry per identity. */
    public void replace(String target, List<Claim> claims) {
        Target state = held(target);
        state.claims.clear();
        for (Claim claim : claims) {
            if ("human".equals(EventBus.actorKind(claim.actor()))) state.claims.remove(claim.originalSymbol());
            else state.claims.put(claim.originalSymbol(), claim);
        }
    }

    public List<Claim> snapshot(String target) { return new ArrayList<>(held(target).claims.values()); }
}
