package dev.workbench.engine;

import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Order of the Strings view (UI audit decision 5, 2026-09-10): the dex string pool's own
 * order buries the interesting rows in thousands of plain ones, so the default is the
 * classifier's PRIORITY — usage-confirmed types first (crypto, url, intent, permission,
 * content-uri, path, then the lexical guesses base64/hex), plain last; ties by xref count,
 * then pool order. "xrefs" is xref count first; "order" is the pool order (the old default).
 * Applied by every engine BEFORE paging, so page N of "by priority" is stable.
 */
public final class StringSort {

    private StringSort() {}

    public static final String DEFAULT = "priority";

    private static int rankOf(String type) {
        return switch (type == null ? "plain" : type) {
            case "crypto" -> 0;
            case "url" -> 1;
            case "intent" -> 2;
            case "permission" -> 3;
            case "content-uri" -> 4;
            case "path" -> 5;
            case "base64" -> 6;
            case "hex" -> 7;
            case "plain" -> 9;
            default -> 8;
        };
    }

    /** Normalise a request's sort param: null/blank → priority; unknown → E_SYNTAX-style IllegalArgumentException. */
    public static String normalize(String sort) {
        if (sort == null || sort.isBlank()) return DEFAULT;
        String s = sort.trim().toLowerCase(java.util.Locale.ROOT);
        if (s.equals("priority") || s.equals("xrefs") || s.equals("order")) return s;
        throw new IllegalArgumentException("sort must be priority|xrefs|order, got " + sort);
    }

    @SuppressWarnings("unchecked")
    private static int priorityKey(Map<String, Object> row) {
        int best = rankOf(row.get("type") == null ? null : String.valueOf(row.get("type"))) + 10;   // lexical-only until proven
        Object ts = row.get("types");
        if (ts instanceof List<?> l)
            for (Object o : l)
                if (o instanceof Map<?, ?> t) {
                    int r = rankOf(String.valueOf(t.get("name")));
                    if (!"usage".equals(t.get("confidence"))) r += 10;   // a guess sorts under a confirmed type
                    best = Math.min(best, r);
                }
        return best;
    }

    private static long xrefs(Map<String, Object> row) {
        Object v = row.get("xrefCount");
        if (v == null) v = row.get("xrefs");
        return v instanceof Number n ? n.longValue() : 0L;
    }

    /** Sort in place; "order" leaves the list as enumerated (stable sorts keep pool order on ties). */
    public static void sort(List<Map<String, Object>> rows, String mode) {
        String m = normalize(mode);
        if (m.equals("order")) return;
        Comparator<Map<String, Object>> byXrefs = Comparator.comparingLong((Map<String, Object> r) -> -xrefs(r));
        if (m.equals("xrefs")) { rows.sort(byXrefs); return; }
        rows.sort(Comparator.comparingInt(StringSort::priorityKey).thenComparing(byXrefs));
    }
}
