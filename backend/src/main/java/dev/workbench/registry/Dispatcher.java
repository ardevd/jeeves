package dev.workbench.registry;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Routes {op, params, actor} → {ok, data?, error?, meta?} (MCP_SPEC §1).
 * meta carries ms / truncated / actor / engineCaps where relevant.
 */
public final class Dispatcher {

    private final Registry registry;
    private ScopeChecker scopeChecker;
    private AfterOp afterOp;

    /** Server-side write-scope enforcement (MCP_SPEC §3): called before every
     *  mutating op; throws OpException E_SCOPE on violation. */
    public interface ScopeChecker {
        void check(String actor, String opName, Map<String, Object> params) throws OpException;
    }

    /** Post-op observer (MCP_SPEC §5 co-presence): sees every SUCCESSFUL op so an
     *  agent's locating call can move its presence pin. Side channel only — it may
     *  not alter the response, and a throw from it is swallowed. */
    public interface AfterOp {
        void after(String opName, Map<String, Object> params, String actor, Object data);
    }

    /** Result decorator: may return an augmented copy of a successful op's data for this actor
     *  (the provenance of agent-assigned names in an agent's reads). Null or a throw = unchanged. */
    public interface Decorator {
        Object decorate(String opName, Map<String, Object> params, String actor, Object data);
    }
    private Decorator decorator;
    public void setDecorator(Decorator d) { this.decorator = d; }

    public void setScopeChecker(ScopeChecker c) {
        this.scopeChecker = c;
    }

    public void setAfterOp(AfterOp h) {
        this.afterOp = h;
    }

    public Dispatcher(Registry registry) {
        this.registry = registry;
    }

    /** The client behind the current call, when the caller knows it (the MCP bridge names the client
     *  it serves: "kimi-code/2.0.2"); set by the tool.call op around its routed dispatches. */
    public static final ThreadLocal<String> CLIENT = new ThreadLocal<>();
    /** A lenient symbol resolution the op ran on (Workspace.resolveSymbol records it): copied into the envelope's
     *  meta.resolution so every op says it without changing its result shape (2026-10-09). */
    public static final ThreadLocal<Map<String, Object>> RESOLUTION = new ThreadLocal<>();
    /** Ops that are pure plumbing of the browser or of the bridge — never audited themselves (the
     *  routed op is). */
    private static final java.util.Set<String> AUDIT_SKIP = java.util.Set.of("tool.call", "ops.tools", "ops.list", "caps", "system.logs", "log.client", "audit");

    public Map<String, Object> dispatch(String opName, Map<String, Object> params, String actor) {
        long t0 = System.nanoTime();
        Map<String, Object> resp = new LinkedHashMap<>();
        Map<String, Object> meta = new LinkedHashMap<>();
        if (params == null) params = new LinkedHashMap<>();
        if (actor == null || actor.isBlank()) actor = "human";
        RESOLUTION.remove();

        Op op = registry.get(opName);
        if (op == null) {
            resp.put("ok", false);
            Map<String, Object> em = OpException.notFound("unknown op: " + opName, "registered ops: " + registry.size()).toMap();
            resp.put("error", em);
            dev.workbench.server.Log.opError(opName, actor, params, em, null);
        } else {
            try {
                if (op.mutating() && scopeChecker != null)
                    scopeChecker.check(actor, opName, params);
                Object data = op.handler().handle(new Op.Request(opName, params, actor));
                if (decorator != null)
                    try { Object d2 = decorator.decorate(opName, params, actor, data); if (d2 != null) data = d2; }
                    catch (Exception ignored) {}
                resp.put("ok", true);
                resp.put("data", data);
                /* truncation honesty (app-review-opus P0-3): engines signal caps
                   as data.truncated (strings/device.catalog/map.get) — lift it to
                   meta so a client that only watches meta still sees it. Absent
                   stays false: no false positives. */
                if (data instanceof Map<?, ?> dm && Boolean.TRUE.equals(dm.get("truncated")))
                    meta.put("truncated", true);
                if (data instanceof Map<?, ?> dm2 && dm2.get("nextOffset") != null)
                    meta.put("nextOffset", dm2.get("nextOffset"));
                if (afterOp != null)
                    try { afterOp.after(opName, params, actor, data); }
                    catch (RuntimeException ignored) {}   // a side channel never fails the op
            } catch (OpException e) {
                resp.put("ok", false);
                resp.put("error", e.toMap());
                dev.workbench.server.Log.opError(opName, actor, params, e.toMap(), null);
            } catch (IllegalArgumentException e) {
                resp.put("ok", false);
                Map<String, Object> em = OpException.syntax(e.getMessage() == null ? "bad params" : e.getMessage(), "op=" + opName).toMap();
                resp.put("error", em);
                dev.workbench.server.Log.opError(opName, actor, params, em, e);
            } catch (Exception e) {
                resp.put("ok", false);
                Map<String, Object> em = OpException.busy("internal error: " + e, "op=" + opName).toMap();
                resp.put("error", em);
                dev.workbench.server.Log.opError(opName, actor, params, em, e);   // the stack goes to the log, never lost in a 200 answer
            }
            meta.put("mutating", op.mutating());
            meta.put("cost", op.cost().name());
        }

        Map<String, Object> resolution = RESOLUTION.get();
        if (resolution != null) { meta.put("resolution", resolution); RESOLUTION.remove(); }
        meta.put("ms", (System.nanoTime() - t0) / 1_000_000L);
        /* putIfAbsent: the success path may have lifted a true data.truncated
           already — an unconditional put overwrote it and kept the lie alive
           (fix-7 review F1) */
        meta.putIfAbsent("truncated", false);
        meta.put("actor", actor);
        resp.put("meta", meta);
        audit(opName, params, actor, op, resp, meta);
        return resp;
    }

    /** who used the workbench and how (Log.audit): every call by a non-human actor, every mutating
     *  call by a human — the browser's reads are not a story worth a line each */
    private static void audit(String opName, Map<String, Object> params, String actor, Op op, Map<String, Object> resp, Map<String, Object> meta) {
        try {
            if (AUDIT_SKIP.contains(opName)) return;
            boolean human = "human".equals(dev.workbench.server.EventBus.actorKind(actor));
            boolean mutating = op != null && op.mutating();
            if (human && !mutating) return;
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("ts", java.time.Instant.now().toString());
            e.put("actor", actor);
            String client = CLIENT.get();
            if (client != null) e.put("client", client);
            e.put("op", opName);
            if (params.get("target") != null) e.put("target", params.get("target"));
            Object sym = params.get("symbol") != null ? params.get("symbol") : params.get("class") != null ? params.get("class") : params.get("query");
            if (sym != null) e.put("symbol", String.valueOf(sym).length() > 160 ? String.valueOf(sym).substring(0, 157) + "…" : sym);
            if (params.get("items") instanceof java.util.List<?> l) e.put("items", l.size());
            e.put("write", mutating);
            e.put("ok", Boolean.TRUE.equals(resp.get("ok")));
            if (!Boolean.TRUE.equals(resp.get("ok")) && resp.get("error") instanceof Map<?, ?> em) e.put("code", em.get("code"));
            e.put("ms", meta.get("ms"));
            dev.workbench.server.Log.audit(e);
        } catch (Exception ignored) {}
    }
}
