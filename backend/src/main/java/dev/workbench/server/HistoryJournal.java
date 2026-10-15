package dev.workbench.server;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** History stays an undoable-entry list; nested reversals are the durable mutation log.
 * New entries record their initial state so undo never erases an earlier mutation from replay. */
public final class HistoryJournal {
    private HistoryJournal() {}

    public static Instant time(Object value) {
        try { return Instant.parse(String.valueOf(value)); }
        catch (Exception ignored) { return Instant.EPOCH; }
    }
    public static int revision(Map<String,Object> entry) {
        String id = String.valueOf(entry.get("id"));
        int h = id.lastIndexOf("-h");
        try { return h < 0 ? 0 : Integer.parseInt(id.substring(h + 2)); }
        catch (NumberFormatException ignored) { return 0; }
    }
    public static List<Map<String,Object>> reversals(Map<String,Object> entry) {
        List<Map<String,Object>> out = new ArrayList<>();
        if (entry.get("reversals") instanceof List<?> list)
            for (Object value : list) if (value instanceof Map<?,?> map) {
                @SuppressWarnings("unchecked") Map<String,Object> event = (Map<String,Object>) map;
                out.add(event);
            }
        return out;
    }
    private static boolean initialUndone(Map<String,Object> entry) {
        return entry.containsKey("journalVersion")
                ? Boolean.TRUE.equals(entry.get("journalInitialUndone"))
                : Boolean.TRUE.equals(entry.get("undone"));
    }
    public static void appendReversal(Map<String,Object> entry, Map<String,Object> event) {
        if (!entry.containsKey("journalVersion")) {
            entry.put("journalInitialUndone", Boolean.TRUE.equals(entry.get("undone")));
            entry.put("journalVersion", 2);
        }
        List<Map<String,Object>> events = reversals(entry);
        events.add(new LinkedHashMap<>(event));
        entry.put("reversals", events);
    }
    public static Instant lastChange(Map<String,Object> entry) {
        Instant last = time(entry.get("ts"));
        for (Map<String,Object> event : reversals(entry))
            if (!Boolean.TRUE.equals(event.get("discarded")) && time(event.get("ts")).isAfter(last)) last = time(event.get("ts"));
        return last;
    }

    /** All changes after the saved database, in execution order, including inverses.
     * An absent database with an explicit rekey marker passes Instant.MIN. */
    public static List<Map<String,Object>> replay(List<Map<String,Object>> entries, Instant savedAt) {
        List<Map<String,Object>> out = new ArrayList<>();
        for (Map<String,Object> entry : entries) {
            if (!initialUndone(entry) && !Boolean.TRUE.equals(entry.get("discarded")) && time(entry.get("ts")).isAfter(savedAt))
                out.add(mutation(entry, entry, false));
            for (Map<String,Object> event : reversals(entry)) {
                if (Boolean.TRUE.equals(event.get("discarded")) || !time(event.get("ts")).isAfter(savedAt)) continue;
                out.add(mutation(entry, event, "undo".equals(event.get("op"))));
            }
        }
        out.sort(Comparator.comparing((Map<String,Object> row) -> time(row.get("ts"))).thenComparingInt(HistoryJournal::revision));
        return out;
    }

    /** A reversal's target is the original symbol, independent of current class/member names. */
    private static Map<String,Object> mutation(Map<String,Object> entry, Map<String,Object> event, boolean reverse) {
        Map<String,Object> out = new LinkedHashMap<>(entry);
        out.remove("reversals");
        out.put("id", event.get("id")); out.put("ts", event.get("ts"));
        out.put("actor", event.get("actor"));
        out.put("historyAction", event == entry ? "apply" : event.get("op"));
        String kind = String.valueOf(entry.get("kind"));
        Object addr = "local".equals(kind) ? entry.get("originalMethod") : entry.get("originalSymbol");
        if (addr == null) addr = reverse ? entry.get("newAddr") : entry.get("addr");
        if (addr == null) addr = entry.get("addr");
        out.put("addr", addr);
        if (reverse) {
            out.put("oldName", entry.get("newName")); out.put("newName", entry.get("oldName"));
            out.put("newAddr", entry.get("addr"));
            out.put("newText", entry.get("oldText") == null ? "" : entry.get("oldText"));
            out.remove("revert"); // undoing a restoration may restore an analyst name
        }
        if ("parameters".equals(kind)) {
            List<Map<String,Object>> changes = new ArrayList<>();
            if (entry.get("changes") instanceof List<?> rows) for (Object value : rows) if (value instanceof Map<?,?> change) {
                Map<String,Object> row = new LinkedHashMap<>(); row.put("index", change.get("index"));
                boolean cleared = Boolean.TRUE.equals(change.get("cleared"));
                if (reverse) {
                    if (cleared || "analyst".equals(change.get("oldSource"))) row.put("name", change.get("oldName"));
                    else row.put("clear", true);
                } else {
                    if (cleared) row.put("clear", true); else row.put("name", change.get("newName"));
                }
                changes.add(row);
            }
            out.put("parameterMutations", changes);
        }
        return out;
    }

    /** Returns copies with only post-checkpoint actions discarded and UI state restored to
     * the checkpoint. Earlier saved entries and inverses remain auditable and replayable. */
    public static List<Map<String,Object>> discardAfter(List<Map<String,Object>> entries, Instant savedAt) {
        List<Map<String,Object>> out = new ArrayList<>();
        for (Map<String,Object> entry : entries) {
            Map<String,Object> copy = new LinkedHashMap<>(entry);
            boolean undone = initialUndone(entry) || Boolean.TRUE.equals(entry.get("discarded"));
            if (time(entry.get("ts")).isAfter(savedAt)) { copy.put("discarded", true); undone = true; }
            List<Map<String,Object>> events = new ArrayList<>();
            for (Map<String,Object> event : reversals(entry)) {
                Map<String,Object> ec = new LinkedHashMap<>(event);
                if (time(event.get("ts")).isAfter(savedAt)) ec.put("discarded", true);
                if (!Boolean.TRUE.equals(ec.get("discarded"))) undone = "undo".equals(ec.get("op"));
                events.add(ec);
            }
            if (!events.isEmpty()) copy.put("reversals", events);
            copy.put("undone", undone); out.add(copy);
        }
        return out;
    }
}
