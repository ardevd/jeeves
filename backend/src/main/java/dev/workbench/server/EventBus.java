package dev.workbench.server;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * Workspace event bus (MCP_SPEC §5 events): named channels — progress, presence,
 * activity, proposals, runs. Subscribers (the SSE handler) drain per-connection
 * queues; publishers (RunsManager hit relay, later agents) post. Bounded queues —
 * a slow SSE client drops events rather than blocking a frida hit thread.
 */
public final class EventBus {

    private static final int QUEUE_CAP = 1000;

    private final List<BlockingQueue<Map<String, Object>>> subs = new CopyOnWriteArrayList<>();

    public BlockingQueue<Map<String, Object>> subscribe() {
        BlockingQueue<Map<String, Object>> q = new LinkedBlockingQueue<>(QUEUE_CAP);
        subs.add(q);
        return q;
    }

    public void unsubscribe(BlockingQueue<Map<String, Object>> q) {
        subs.remove(q);
    }

    /** Publish an event on a channel (added as "channel" field for SSE routing). */
    public void publish(String channel, Map<String, Object> ev) {
        ev.put("channel", channel);
        for (BlockingQueue<Map<String, Object>> q : subs) q.offer(ev);   // drop when full
    }

    // ------------------------------------------------------------ co-presence positions
    //
    // Where each actor is, latest-wins per (project, actor). The presence CHANNEL is a
    // stream — a client that connects late has missed it — so the live map is what
    // presence.whereis reads and what a booting browser paints its agent chip from.

    private final Map<String, Map<String, Object>> positions = new LinkedHashMap<>();

    /** Record where {@code actor} is now in {@code project}; {@code member} null = class view. */
    public void setPosition(String project, String actor, String addr, String member) {
        if (project == null) return;   /* empty workspace: no pin — a null key was
           unclearable ("null " pins survived clearPositions) and NPEd
           positionsOfKind callers (empty-boot r1 #1/#8) */
        Map<String, Object> pos = new LinkedHashMap<>();
        pos.put("project", project);
        pos.put("actor", actor);
        pos.put("addr", addr);
        pos.put("member", member);
        pos.put("ts", System.currentTimeMillis());
        synchronized (positions) {
            positions.put(project + "\u0000" + actor, pos);
            /* free-text actors make this map unbounded (probe actors pile up) — evict
               past 64 entries, oldest HUMAN pins first: a human pings per caret move
               while an agent pins only on locating ops, so pure age-LRU always evicts
               the agent first (review F13) */
            if (positions.size() > 64) {
                String justWrote = project + "\u0000" + actor;
                List<Map.Entry<String, Map<String, Object>>> byAge = new ArrayList<>(positions.entrySet());
                /* never evict the pin this call just wrote (with 64 agent pins present,
                   a fresh human ping would otherwise delete itself — review F4) */
                byAge.removeIf(en -> en.getKey().equals(justWrote));
                byAge.sort((a, b) -> {
                    boolean ah = "human".equals(actorKind(String.valueOf(a.getValue().get("actor"))));
                    boolean bh = "human".equals(actorKind(String.valueOf(b.getValue().get("actor"))));
                    if (ah != bh) return ah ? -1 : 1;   // humans are evictable first
                    return Long.compare(ts(a.getValue()), ts(b.getValue()));
                });
                int over = positions.size() - 64;   /* snapshot — remove() shrinks the map mid-loop */
                for (int i = 0; i < over && i < byAge.size(); i++)
                    positions.remove(byAge.get(i).getKey());
            }
        }
    }

    /**
     * Stable actor-kind. The workbench has three spellings for a non-human caller —
     * "llm", "llm:agent-3", "agent-3" — and one for the person at the keyboard; all
     * that co-presence asks is WHICH SIDE, so they collapse to two kinds.
     * ("user" is accepted as a human alias: it is what the old Jeeves called it.)
     */
    public static String actorKind(String actor) {
        if (actor == null || actor.isBlank()) return "human";
        String a = actor.trim();
        int colon = a.indexOf(':');
        String head = colon < 0 ? a : a.substring(0, colon);
        return head.equals("human") || head.equals("user") ? "human" : "agent";
    }

    /** Positions of one actor-kind in a project, freshest first (presence.whereis). */
    public List<Map<String, Object>> positionsOfKind(String project, String kind) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (project == null) return out;   // no project: nobody is anywhere (r1 #1)
        synchronized (positions) {
            for (Map<String, Object> p : positions.values())
                if (project.equals(String.valueOf(p.get("project")))
                        && kind.equals(actorKind(String.valueOf(p.get("actor")))))
                    out.add(new LinkedHashMap<>(p));
        }
        out.sort((a, b) -> Long.compare(ts(b), ts(a)));
        return out;
    }

    /** Forget a closed project's pins — they reference targets that are gone. */
    public void clearPositions(String project) {
        synchronized (positions) { positions.keySet().removeIf(k -> k.startsWith(project + "\u0000")); }
    }

    private static long ts(Map<String, Object> p) {
        Object t = p.get("ts");
        return t instanceof Number n ? n.longValue() : 0L;
    }
}
