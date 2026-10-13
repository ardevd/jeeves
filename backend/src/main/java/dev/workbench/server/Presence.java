package dev.workbench.server;

import dev.workbench.registry.OpException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Live co-presence (MCP_SPEC §5 `presence` channel) — who is looking at what, ported
 * from the old Jeeves model (Commands.emit/whereis + EventBus positions).
 *
 * <p>The split it rests on: MODEL state (renames, comments) is authoritative and always
 * broadcast; PRESENCE state is "actor X is at addr Y", broadcast per actor and followed
 * or ignored per client (the ribbon's FOLLOW switch).
 *
 * <p>Two ways a position is set:
 * <ul>
 *   <li><b>explicitly</b> — {@code presence.ping {addr, member?}}, the browser saying
 *       where the human just navigated. Mutates nothing.</li>
 *   <li><b>implicitly</b> — an AGENT calling a locating op ({@link #LOCATING_VIEW} /
 *       {@link #LOCATING_EDIT}) is, by doing so, telling us where it is. Human reads
 *       never auto-emit: a browser's background fetches are not navigation, which is
 *       why the browser pings explicitly instead.</li>
 * </ul>
 *
 * <p>Positions are project-scoped and carry a ts; nothing expires them server-side —
 * {@code ageMs} lets the UI dim a stale pin and drop a dead one, which keeps "the agent
 * stopped 4 minutes ago" visible instead of silently identical to "no agent".
 */
public final class Presence {

    /** Ops whose symbol/class param IS where the caller is looking (old emit(): class,
     *  decompile, xrefs, getcomment). A search or a hover is a query, not a location. */
    private static final Set<String> LOCATING_VIEW = Set.of(
            "code.decompile", "code.smali", "code.outline", "code.classinfo",
            "symbol.resolve", "xref.to", "xref.from", "callees", "callers", "impls");

    /** Mutating ops that also locate — an edit is a stronger claim than a look.
     *  (For an llm actor these land as proposals; the actor is still THERE.) */
    private static final Set<String> LOCATING_EDIT = Set.of("rename", "comment", "tag.add");

    private final Workspace ws;
    private final EventBus bus;

    public Presence(Workspace ws, EventBus bus) {
        this.ws = ws;
        this.bus = bus;
    }

    /** A closed project's pins reference targets that are gone — forget them. */
    public void clearPositions(String project) {
        bus.clearPositions(project);
    }

    // ------------------------------------------------------------ ops

    /** presence.ping {addr, member?} — "I am now looking at addr". No mutation. */
    public Map<String, Object> ping(String actor, String addr, String member) throws OpException {
        if (addr == null || addr.isBlank())
            throw OpException.syntax("presence.ping needs an addr", "op=presence.ping");
        String project = ws.activeProject();
        if (project == null) {
            /* empty workspace (2026-09-07): nowhere to pin — report, don't
               record. A null-project pin NPEd every later whereis (r1 #1/#8) */
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("pinged", false);
            r.put("actor", actor);
            r.put("reason", "no project open");
            return r;
        }
        String classAddr = classAddrOf(addr);
        String mem = member != null && !member.isBlank() ? member
                : (addr.equals(classAddr) ? null : addr);
        bus.setPosition(project, actor, classAddr, mem);
        publish("view", actor, project, classAddr, mem);
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("pinged", true);
        r.put("actor", actor);
        r.put("addr", classAddr);
        r.put("member", mem);
        return r;
    }

    /**
     * presence.whereis {who?} — the freshest position of an actor-kind, enriched with
     * display names and staleness. A PURE READ: asking where the agent is must not look
     * like the asker navigating, so it sets no position and publishes nothing.
     */
    public Map<String, Object> whereis(String who) {
        String kind = EventBus.actorKind(who == null || who.isBlank() ? "agent" : who);
        String project = ws.activeProject();
        List<Map<String, Object>> matches = bus.positionsOfKind(project, kind);
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("who", kind);
        r.put("project", project);
        /* age-gate presence itself: past five minutes a pin is history, not presence —
           LLM consumers read `present` as "they're here NOW" (review F3) */
        boolean fresh = false;
        if (!matches.isEmpty()){
            long ts0 = matches.get(0).get("ts") instanceof Number n ? n.longValue() : 0L;
            fresh = ts0 > 0 && (System.currentTimeMillis() - ts0) < 300_000;
        }
        r.put("present", fresh);
        if (matches.isEmpty()){ r.put("clients", 0); return r; }
        if (!fresh){
            /* a stale pin is history, not presence — don't ship the record (or a
               clients count) as if someone were there (review F12) */
            r.put("clients", 0);
            r.put("stale", true);
            r.put("ageMs", System.currentTimeMillis() - (matches.get(0).get("ts") instanceof Number n ? n.longValue() : 0L));
            return r;
        }
        r.put("clients", matches.size());
        Map<String, Object> pos = matches.get(0);
        String addr = String.valueOf(pos.get("addr"));
        Object memberObj = pos.get("member");
        String member = memberObj == null ? null : String.valueOf(memberObj);
        long ts = pos.get("ts") instanceof Number n ? n.longValue() : 0L;
        r.put("actor", pos.get("actor"));
        r.put("addr", addr);
        r.put("name", classSimpleName(addr));
        r.put("member", member);
        r.put("memberName", member == null ? null : memberSimpleName(member));
        r.put("ts", ts);
        r.put("ageMs", ts > 0 ? System.currentTimeMillis() - ts : null);
        return r;
    }

    // ------------------------------------------------------------ automatic presence

    /**
     * Post-dispatch hook: an agent that just located something moves its pin. Called for
     * every successful op; silent for human actors (they ping) and for ops that do not
     * carry a location. Never throws — presence is a side channel, not the op.
     */
    public void afterOp(String opName, Map<String, Object> params, String actor, Object data) {
        if ("presence.ping".equals(opName)) return;                 // ping() already published
        if (!"agent".equals(EventBus.actorKind(actor))) return;     // humans ping explicitly
        boolean edit = LOCATING_EDIT.contains(opName);
        if (!edit && !LOCATING_VIEW.contains(opName)) return;
        String target = locationOf(opName, params, data);
        if (target == null || target.isBlank()) return;
        String project = ws.activeProject();
        if (project == null) return;                        // empty workspace: no pin (r1 #8)
        String classAddr = classAddrOf(target);
        String member = target.equals(classAddr) ? null : target;
        bus.setPosition(project, actor, classAddr, member);
        publish(edit ? "edit" : "view", actor, project, classAddr, member);
    }

    /**
     * The addr the actor is actually pointed at. A rename MOVES the symbol, so its pin
     * goes on the POST-rename handle — a follower must land on the renamed symbol, not
     * on an addr that no longer resolves. Everything else is pinned by request handle
     * (which may be a member), falling back to whatever the result resolved to.
     */
    @SuppressWarnings("unchecked")
    private static String locationOf(String opName, Map<String, Object> params, Object data) {
        if ("rename".equals(opName) && data instanceof Map<?, ?> m) {
            Object newAddr = ((Map<String, Object>) m).get("newAddr");
            if (newAddr != null && !String.valueOf(newAddr).isBlank()) return String.valueOf(newAddr);
        }
        for (String key : List.of("symbol", "class")) {
            Object v = params.get(key);
            if (v != null && !String.valueOf(v).isBlank()) return String.valueOf(v);
        }
        if (data instanceof Map<?, ?> m) {
            Object addr = ((Map<String, Object>) m).get("addr");
            if (addr != null && !String.valueOf(addr).isBlank()) return String.valueOf(addr);
        }
        return null;
    }

    private void publish(String type, String actor, String project, String addr, String member) {
        Map<String, Object> ev = new LinkedHashMap<>();
        ev.put("type", type);
        ev.put("actor", actor);
        ev.put("kind", EventBus.actorKind(actor));
        ev.put("project", project);
        ev.put("addr", addr);
        ev.put("member", member);
        ev.put("name", classSimpleName(addr));
        ev.put("memberName", member == null ? null : memberSimpleName(member));
        ev.put("ts", System.currentTimeMillis());
        bus.publish("presence", ev);
    }

    // ------------------------------------------------------------ display forms

    /** Owning class of a member addr ("Lp/C;->m()V" → "Lp/C;"); a class addr is itself. */
    static String classAddrOf(String addr) {
        if (addr == null) return null;
        int arrow = addr.indexOf("->");
        return arrow < 0 ? addr : addr.substring(0, arrow);
    }

    /** "Lcom/foo/Bar;" or "com.foo.Bar" → "Bar" — the addr is CURRENT, so it renames with
     *  the symbol. Nested classes keep their outer prefix ("Bar$Inner"), which is what a
     *  reader needs to place them. */
    static String classSimpleName(String classAddr) {
        if (classAddr == null) return null;
        String s = classAddr;
        if (s.startsWith("L") && s.endsWith(";")) s = s.substring(1, s.length() - 1);
        int slash = s.lastIndexOf('/');
        if (slash >= 0) return s.substring(slash + 1);
        int dot = s.lastIndexOf('.');
        return dot >= 0 ? s.substring(dot + 1) : s;
    }

    /** "L..;->name(II)V" → "name"; "L..;->f:Ltype;" → "f". */
    static String memberSimpleName(String memberAddr) {
        if (memberAddr == null) return null;
        int arrow = memberAddr.indexOf("->");
        String m = arrow < 0 ? memberAddr : memberAddr.substring(arrow + 2);
        int paren = m.indexOf('(');
        if (paren >= 0) m = m.substring(0, paren);
        int colon = m.indexOf(':');
        if (colon >= 0) m = m.substring(0, colon);
        return m;
    }
}
