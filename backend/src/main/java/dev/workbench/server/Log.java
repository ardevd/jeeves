package dev.workbench.server;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The workbench log: one append-only file under the home, <home>/logs/workbench.log, rotated
 * at 10 MB (five generations kept), every line timestamped. It persists across restarts and
 * does not depend on how the server was started (stdout went wherever the launcher pointed it;
 * an operator's own launch kept nothing — user 2026-09-19: "detailed logs of all errors, and
 * that they persist").
 *
 * What lands here: every op that answers an error (code, op, actor, the params in short, message,
 * detail — WARN), every unexpected exception with its stack (ERROR: the dispatcher's, the HTTP
 * layer's, a thread's uncaught one, the agent loop's), provider failures, and the browser's own
 * errors through the log.client op. INFO lines mark boots and restarts so an incident reads in
 * order. Everything also goes to stderr, so a launcher's console keeps its view.
 *
 * system.logs {tail, level?, grep?} reads the file back (the UI's ⌘K → Logs, and remote diagnosis).
 */
public final class Log {
    private Log() {}

    private static final long ROTATE_AT = 10L * 1024 * 1024;
    private static final int KEEP = 5;
    private static final int MAX_LINE = 4000;
    private static volatile File file;
    private static volatile File auditFile;
    private static final Object LOCK = new Object();
    private static final Object AUDIT_LOCK = new Object();

    /** Point the log at the home; called once at boot (WorkbenchServer). */
    public static void init(File home) {
        File dir = new File(home, "logs");
        dir.mkdirs();
        file = new File(dir, "workbench.log");
        auditFile = new File(dir, "audit.jsonl");
        info("boot", "workbench starting · home " + home.getAbsolutePath() + " · java " + System.getProperty("java.version")
                + " · pid " + ProcessHandle.current().pid());
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> error("thread " + t.getName(), e, null));
    }

    public static File file() { return file; }
    public static File auditFile() { return auditFile; }

    /** The audit trail: who used the workbench and how — one JSON line per call (<home>/logs/audit.jsonl,
     *  rotated like the log). Every call by a non-human actor (a mission, an MCP client such as a Kimi
     *  researcher), every write by a human; reads by the browser are not recorded (user 2026-09-20:
     *  "we need to know who has been using Jeeves and how"). */
    public static void audit(Map<String, Object> entry) {
        File f = auditFile;
        if (f == null) return;
        String line = dev.workbench.registry.Json.write(entry);
        synchronized (AUDIT_LOCK) {
            try {
                if (f.length() > ROTATE_AT) rotate(f);
                try (FileOutputStream out = new FileOutputStream(f, true)) {
                    out.write((line + "\n").getBytes(StandardCharsets.UTF_8));
                }
            } catch (IOException ignored) {}
        }
    }

    /** The audit lines (newest last) that match: actor substring, op substring, since (ISO instant). */
    public static List<Map<String, Object>> auditTail(int tail, String actor, String op, String since) {
        List<Map<String, Object>> out = new ArrayList<>();
        File f = auditFile;
        if (f == null || !f.isFile()) return out;
        List<String> lines;
        synchronized (AUDIT_LOCK) {
            try { lines = java.nio.file.Files.readAllLines(f.toPath(), StandardCharsets.UTF_8); }
            catch (IOException e) { return out; }
        }
        for (int i = lines.size() - 1; i >= 0 && out.size() < Math.max(1, tail); i--) {
            Map<String, Object> e = parseAudit(lines.get(i));
            if (e == null) continue;
            if (actor != null && !actor.isBlank() && !String.valueOf(e.get("actor")).contains(actor)) continue;
            if (op != null && !op.isBlank() && !String.valueOf(e.get("op")).contains(op)) continue;
            if (since != null && !since.isBlank() && String.valueOf(e.get("ts")).compareTo(since) < 0) continue;
            out.add(0, e);
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> parseAudit(String line) {
        try {
            Object o = dev.workbench.registry.Json.parse(line);
            return o instanceof Map<?, ?> m ? (Map<String, Object>) m : null;
        } catch (Exception e) { return null; }
    }

    /** Per actor since an instant: calls, writes, the ops used (counts), the targets touched, the
     *  clients seen, first and last call — the answer to "who used Jeeves and how". */
    public static List<Map<String, Object>> auditSummary(String since) {
        Map<String, Map<String, Object>> by = new java.util.LinkedHashMap<>();
        for (Map<String, Object> e : auditTail(Integer.MAX_VALUE, null, null, since)) {
            String actor = String.valueOf(e.get("actor"));
            Map<String, Object> row = by.computeIfAbsent(actor, a -> {
                Map<String, Object> r = new java.util.LinkedHashMap<>();
                r.put("actor", a); r.put("calls", 0); r.put("writes", 0); r.put("errors", 0);
                r.put("ops", new java.util.LinkedHashMap<String, Integer>());
                r.put("targets", new java.util.LinkedHashSet<String>());
                r.put("clients", new java.util.LinkedHashSet<String>());
                r.put("first", e.get("ts"));
                return r;
            });
            row.put("calls", (Integer) row.get("calls") + 1);
            if (Boolean.TRUE.equals(e.get("write"))) row.put("writes", (Integer) row.get("writes") + 1);
            if (Boolean.FALSE.equals(e.get("ok"))) row.put("errors", (Integer) row.get("errors") + 1);
            ((Map<String, Integer>) row.get("ops")).merge(String.valueOf(e.get("op")), 1, Integer::sum);
            if (e.get("target") != null) ((java.util.Set<String>) row.get("targets")).add(String.valueOf(e.get("target")));
            if (e.get("client") != null) ((java.util.Set<String>) row.get("clients")).add(String.valueOf(e.get("client")));
            row.put("last", e.get("ts"));
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : by.values()) {
            r.put("targets", new ArrayList<>((java.util.Set<String>) r.get("targets")));
            r.put("clients", new ArrayList<>((java.util.Set<String>) r.get("clients")));
            out.add(r);
        }
        return out;
    }

    public static void info(String where, String message) { write("INFO", where, message, null, null); }
    public static void warn(String where, String message, Map<String, Object> ctx) { write("WARN", where, message, ctx, null); }
    public static void error(String where, Throwable t, Map<String, Object> ctx) {
        write("ERROR", where, t == null ? "error" : t.toString(), ctx, t);
    }
    public static void error(String where, String message, Map<String, Object> ctx) { write("ERROR", where, message, ctx, null); }

    /** An op answered an error: the caller's op, actor, params (shortened) and the error map. */
    public static void opError(String op, String actor, Map<String, Object> params, Map<String, Object> err, Throwable cause) {
        StringBuilder sb = new StringBuilder();
        sb.append(err == null ? "?" : err.get("code")).append(' ').append(op).append(" actor=").append(actor);
        // Phone content must stay in selected private artifacts, not the default diagnostic feed.
        Map<String, Object> visible = params;
        if (op.startsWith("runs.lab.") || ("tool.call".equals(op) && "message_lab".equals(params.get("tool")))) {
            visible = new java.util.LinkedHashMap<>();
            visible.put("private", "Message Lab parameters omitted; inspect selected artifacts explicitly");
        }
        sb.append(" params=").append(shortJson(visible));
        if (err != null) {
            sb.append(" · ").append(err.get("message"));
            if (err.get("detail") != null && !String.valueOf(err.get("detail")).isBlank()) sb.append(" (").append(err.get("detail")).append(')');
        }
        write(cause == null ? "WARN" : "ERROR", "op", sb.toString(), null, cause);
    }

    private static void write(String level, String where, String message, Map<String, Object> ctx, Throwable t) {
        StringBuilder sb = new StringBuilder();
        sb.append(Instant.now()).append(' ').append(level).append(' ').append('[').append(where).append("] ").append(message);
        if (ctx != null && !ctx.isEmpty()) sb.append(' ').append(shortJson(ctx));
        String line = sb.length() > MAX_LINE ? sb.substring(0, MAX_LINE) + "…" : sb.toString();
        if (t != null) {
            StringWriter sw = new StringWriter();
            t.printStackTrace(new PrintWriter(sw));
            String[] trace = sw.toString().split("\n");
            StringBuilder tb = new StringBuilder(line);
            for (int i = 0; i < Math.min(trace.length, 40); i++) tb.append('\n').append("    ").append(trace[i].trim());
            line = tb.toString();
        }
        System.err.println(line);
        File f = file;
        if (f == null) return;
        synchronized (LOCK) {
            try {
                if (f.length() > ROTATE_AT) rotate(f);
                try (FileOutputStream out = new FileOutputStream(f, true)) {
                    out.write((line + "\n").getBytes(StandardCharsets.UTF_8));
                }
            } catch (IOException ignored) {
                // the log must never take the server down
            }
        }
    }

    private static void rotate(File f) {
        for (int i = KEEP - 1; i >= 1; i--) {
            File from = new File(f.getPath() + "." + i), to = new File(f.getPath() + "." + (i + 1));
            if (from.exists()) { to.delete(); from.renameTo(to); }
        }
        File first = new File(f.getPath() + ".1");
        first.delete();
        f.renameTo(first);
    }

    /** The last `tail` lines (newest last), filtered by level and a substring. */
    public static List<String> tail(int tail, String level, String grep) {
        List<String> out = new ArrayList<>();
        File f = file;
        if (f == null || !f.isFile()) return out;
        List<String> lines;
        synchronized (LOCK) {
            try { lines = java.nio.file.Files.readAllLines(f.toPath(), StandardCharsets.UTF_8); }
            catch (IOException e) { return out; }
        }
        String lv = level == null || level.isBlank() ? null : level.toUpperCase();
        String g = grep == null || grep.isBlank() ? null : grep;
        List<String> kept = new ArrayList<>();
        StringBuilder cur = null;
        for (String l : lines) {           // a stack line is indented: it belongs to the entry above
            if (l.startsWith("    ") && cur != null) { cur.append('\n').append(l); continue; }
            if (cur != null) kept.add(cur.toString());
            cur = new StringBuilder(l);
        }
        if (cur != null) kept.add(cur.toString());
        for (int i = kept.size() - 1; i >= 0 && out.size() < Math.max(1, tail); i--) {
            String e = kept.get(i);
            if (lv != null && !e.contains(" " + lv + " ")) continue;
            if (g != null && !e.contains(g)) continue;
            out.add(0, e);
        }
        return out;
    }

    /** A JSON-ish rendering of a map, values cut to keep a line readable; keys are never dropped
     *  except `key` (an API key on llm.setkey — write-only, never logged). */
    static String shortJson(Map<String, Object> m) {
        if (m == null || m.isEmpty()) return "{}";
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, Object> e : m.entrySet()) {
            if (!first) sb.append(", ");
            first = false;
            sb.append(e.getKey()).append('=');
            if ("key".equals(e.getKey())) { sb.append("<redacted>"); continue; }
            String v = String.valueOf(e.getValue()).replace('\n', ' ');
            sb.append(v.length() > 160 ? v.substring(0, 157) + "…" : v);
            if (sb.length() > 1200) { sb.append(", …"); break; }
        }
        return sb.append('}').toString();
    }
}
