package dev.workbench.registry;

/** One registered op (MCP_SPEC §1/§3.4). The single registry feeds HTTP, MCP, CLI, and palette. */
public record Op(
        String name,
        /** Capability required from the target engine ("" = none, op is workspace-global). */
        String capability,
        /** Mutating ops are POST-only at the HTTP layer and idempotent per MCP_SPEC §5. */
        boolean mutating,
        /** Concurrency scheduling hint: cheap | scan | heavy (MCP_SPEC §5). */
        Cost cost,
        Handler handler) {

    public enum Cost { cheap, scan, heavy }

    @FunctionalInterface
    public interface Handler {
        /** Returns the `data` payload of the response; throws OpException on failure. */
        Object handle(Request req) throws OpException;
    }

    /** Request handed to an op handler: op name, params map, actor tag (human | llm:<id> | script). */
    public record Request(String op, java.util.Map<String, Object> params, String actor) {

        public String str(String key) {
            Object v = params.get(key);
            return v == null ? null : String.valueOf(v);
        }

        public String str(String key, String def) {
            String v = str(key);
            return v == null || v.isEmpty() ? def : v;
        }

        public int num(String key, int def) {
            Object v = params.get(key);
            if (v instanceof Number n) return n.intValue();
            if (v instanceof String s) {
                try { return Integer.parseInt(s.trim()); } catch (NumberFormatException ignored) {}
            }
            return def;
        }

        public boolean bool(String key, boolean def) {
            Object v = params.get(key);
            if (v instanceof Boolean b) return b;
            if (v instanceof String s) return Boolean.parseBoolean(s);
            return def;
        }

        /** Require a param or fail E_SYNTAX. */
        public String require(String key) throws OpException {
            String v = str(key);
            if (v == null || v.isEmpty())
                throw OpException.syntax("missing required param '" + key + "'", "op=" + op);
            return v;
        }
    }
}
