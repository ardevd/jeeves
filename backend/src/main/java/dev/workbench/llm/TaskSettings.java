package dev.workbench.llm;

import dev.workbench.registry.Json;
import dev.workbench.registry.OpException;
import dev.workbench.registry.SidecarIO;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Settings for the workbench's BUILT-IN tasks — which model, effort and caps
 *  run a routine the user does not write a goal for. Today one task: the
 *  reversing agent ("reverse", depths quick / deep — llm.Depth). The NEW
 *  MISSION card is for custom goals; the REVERSING AGENT window (Agents view)
 *  edits this. Persisted at backend/.workbench/tasks.json as
 *  { "<task>": { "<depth>": { model, effort, turns, budget } } } — a missing or
 *  blank field means "the depth's default" (llm.Depth for turns/budget, the
 *  provider's own effort) — except the MODEL, which has no default: a depth
 *  with no model does not spawn (the operator picks the provider; nothing is
 *  assumed installed or logged in).
 *
 *  Store contract (llm-config design §5): a mutator snapshots what it touches,
 *  persists, and on a failed write REVERTS memory and throws E_IO. */
public final class TaskSettings {

    public static final String REVERSE = "reverse";
    /** the claude CLI's --effort levels; http providers get the value as
     *  `reasoning_effort` verbatim (what an endpoint accepts is its own) */
    public static final List<String> EFFORTS = List.of("low", "medium", "high", "xhigh", "max");

    /** one depth's settings — null / 0 = default */
    public static final class Entry {
        public String model, effort;
        public int turns, budget;
        Entry copy() { Entry c = new Entry(); c.model = model; c.effort = effort; c.turns = turns; c.budget = budget; return c; }
    }

    private final File file;
    private final Map<String, Map<String, Entry>> tasks = new LinkedHashMap<>();
    private boolean degraded;   /* the sidecar discipline (LayoutStore): a transient read
                                   failure parks the on-disk file before the first overwrite */

    public TaskSettings(File dir) {
        this.file = new File(dir, "tasks.json");
        load();
    }

    @SuppressWarnings("unchecked")
    private void load() {
        if (!file.isFile()) return;
        try {
            Object p = Json.parse(Files.readString(file.toPath()));
            if (!(p instanceof Map)) throw new IllegalStateException("expected a JSON object");
            for (Map.Entry<String, Object> t : ((Map<String, Object>) p).entrySet()) {
                if (!(t.getValue() instanceof Map)) continue;
                if (!REVERSE.equals(t.getKey())) continue;          // unknown tasks are not carried (settings r1 LAT-3)
                Map<String, Entry> depths = new LinkedHashMap<>();
                for (Map.Entry<String, Object> d : ((Map<String, Object>) t.getValue()).entrySet()) {
                    if (!(d.getValue() instanceof Map)) continue;
                    String depth;
                    try { depth = Depth.normalize(d.getKey()); } catch (OpException x) { continue; }   // "QUICK" → quick; junk dropped
                    if (depth == null) continue;
                    Map<String, Object> v = (Map<String, Object>) d.getValue();
                    Entry e = new Entry();
                    e.model = v.get("model") == null ? null : String.valueOf(v.get("model"));
                    /* the legacy `claude` provider is now `anthropic` (llm-anthropic-vendor A10):
                       rewrite the pin on load, unconditionally — it persists at the next write
                       and the grid never shows a stale "claude" provider */
                    if (e.model != null && (e.model.equals("claude") || e.model.startsWith("claude:")))
                        e.model = "anthropic" + e.model.substring("claude".length());
                    e.effort = v.get("effort") == null ? null : String.valueOf(v.get("effort"));
                    e.turns = v.get("turns") instanceof Number n ? Math.max(0, n.intValue()) : 0;
                    e.budget = v.get("budget") instanceof Number n ? Math.max(0, n.intValue()) : 0;
                    depths.put(depth, e);
                }
                tasks.put(t.getKey(), depths);
            }
        } catch (java.io.IOException e) {
            if (!SidecarIO.readFailedOrPark(file, "tasks", e)) degraded = true;
        } catch (Exception e) {
            if (!SidecarIO.parkCorrupt(file, "tasks", e)) degraded = true;
        }
    }

    /** true = durable on disk (§5); the degraded-skip path is a FALSE, not a silent return */
    private boolean persist() {
        if (degraded) {
            if (!SidecarIO.parkHealthyBeforeOverwrite(file, "tasks")) return false;
            degraded = false;
        }
        Map<String, Object> root = new LinkedHashMap<>();
        for (Map.Entry<String, Map<String, Entry>> t : tasks.entrySet()) {
            Map<String, Object> depths = new LinkedHashMap<>();
            for (Map.Entry<String, Entry> d : t.getValue().entrySet()) {
                Entry e = d.getValue();
                Map<String, Object> v = new LinkedHashMap<>();
                if (e.model != null) v.put("model", e.model);
                if (e.effort != null) v.put("effort", e.effort);
                if (e.turns > 0) v.put("turns", e.turns);
                if (e.budget > 0) v.put("budget", e.budget);
                depths.put(d.getKey(), v);
            }
            root.put(t.getKey(), depths);
        }
        return SidecarIO.writeAtomic(file, Json.write(root), "tasks");
    }

    private OpException notWritten() {
        return OpException.io("tasks.json was not written (evidence parked; see server log)", file.getPath());
    }

    /** a transient load failure left the on-disk file un-parked; composites refuse (§5) */
    public synchronized boolean isDegraded() { return degraded; }

    /** the stored entry, or null when the depth runs on defaults */
    public synchronized Entry get(String task, String depth) {
        Map<String, Entry> depths = tasks.get(task);
        return depths == null ? null : depths.get(depth);
    }

    /** effort: blank → null; one of EFFORTS (any case) → canonical; else E_SYNTAX */
    public static String normalizeEffort(String effort) throws OpException {
        if (effort == null || effort.isBlank()) return null;
        String e = effort.trim().toLowerCase();
        if (EFFORTS.contains(e)) return e;
        throw OpException.syntax("effort must be one of " + String.join(", ", EFFORTS), "effort=" + effort);
    }

    /** task.list → the built-in task catalogue with each depth's settings, the
     *  defaults it falls back to, and the resolved provider/model the next
     *  spawn would use */
    public synchronized List<Object> list(LlmRegistry llm) {
        List<Object> out = new ArrayList<>();
        Map<String, Object> reverse = new LinkedHashMap<>();
        reverse.put("task", REVERSE);
        reverse.put("label", "Reverse with agent");
        reverse.put("description", "the right-click / ⚡ REVERSE routine: quick reverses the method alone, deep its call tree");
        List<Object> depths = new ArrayList<>();
        for (String depth : List.of(Depth.QUICK, Depth.DEEP)) depths.add(depthRow(REVERSE, depth, llm));
        reverse.put("depths", depths);
        out.add(reverse);
        return out;
    }

    private Map<String, Object> depthRow(String task, String depth, LlmRegistry llm) {
        Entry e = get(task, depth);
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("depth", depth);
        row.put("model", e == null ? null : e.model);
        row.put("effort", e == null ? null : e.effort);
        row.put("turns", e == null ? 0 : e.turns);
        if (Depth.unlimitedTurns()) row.put("turnsUnlimited", true);
        row.put("budget", e == null ? 0 : e.budget);
        Map<String, Object> defaults = new LinkedHashMap<>();
        defaults.put("turnsCli", Depth.cliTurns(depth, 40));
        defaults.put("turnsLoop", Depth.loopTurns(depth, 14));
        defaults.put("budget", Depth.defaultBudget(depth, 200_000));
        row.put("defaults", defaults);
        /* what the next spawn resolves to: the stored spec — there is NO
           implicit default (a depth with no model spawns nothing: E_SYNTAX
           points here) */
        String spec = e == null ? null : e.model;
        String[] r = spec == null ? null : llm.resolve(spec);
        Map<String, Object> resolved = new LinkedHashMap<>();
        if (spec == null) {
            resolved.put("unconfigured", true);
        } else if (r != null) {
            LlmRegistry.Provider pr = llm.get(r[0]);
            resolved.put("provider", r[0]);
            resolved.put("providerLabel", pr == null ? r[0] : pr.label);
            resolved.put("model", r[1]);
            resolved.put("driver", pr == null ? "" : pr.driver);
            resolved.put("local", pr != null && pr.local);
            resolved.put("configured", pr != null && llm.configured(pr));
            /* the pinned model was dropped from the provider's list (llm.configure):
               the pin is kept — never silently re-pointed (design §6.4) — and SAID.
               Any driver with a list (the claude aliases too), not only http. */
            if (pr != null && !"builtin".equals(pr.driver) && !pr.models.isEmpty() && !pr.models.contains(r[1]))
                resolved.put("unlisted", true);
        } else {
            /* the stored provider is gone (llm.remove) or the spec no longer
               resolves: say so — an empty answer read as "the default" in the
               window while the spawn blocked on the stale spec (settings r1 M2) */
            resolved.put("model", spec);
            resolved.put("unresolved", true);
        }
        row.put("resolved", resolved);
        return row;
    }

    /** a provider that was removed takes its task pins with it (settings r1 M2):
     *  every depth whose model names it goes back to the default. Returns the
     *  depths reset. Reverts + throws E_IO on a failed write (§5). */
    public synchronized List<String> forgetProvider(String providerId) throws OpException {
        List<String> reset = new ArrayList<>();
        Map<Entry, Entry> snapshot = new LinkedHashMap<>();
        String prefix = providerId + ":";
        for (Map.Entry<String, Map<String, Entry>> t : tasks.entrySet())
            for (Map.Entry<String, Entry> d : t.getValue().entrySet())
                if (d.getValue().model != null && d.getValue().model.startsWith(prefix)) {
                    Entry e = d.getValue();
                    snapshot.put(e, e.copy());
                    /* the whole entry: effort and caps are scaled to the provider's
                       driver (CLI tool calls vs http loop turns — r2 L4) */
                    e.model = null;
                    e.effort = null;
                    e.turns = 0;
                    e.budget = 0;
                    reset.add(t.getKey() + "/" + d.getKey());
                }
        if (!reset.isEmpty() && !persist()) {
            for (Map.Entry<Entry, Entry> s : snapshot.entrySet()) {
                Entry e = s.getKey(), was = s.getValue();
                e.model = was.model; e.effort = was.effort; e.turns = was.turns; e.budget = was.budget;
            }
            throw notWritten();
        }
        return reset;
    }

    /** A provider was RENAMED (llm.rename): every pin `id:model` becomes
     *  `newId:model` with effort / turns / budget KEPT — same provider, same
     *  driver, so the caps stay valid (unlike forgetProvider). Returns
     *  [{task, depth, model}] for the depths rewritten. Reverts + throws E_IO on
     *  a failed write. Also the UNDO primitive (renameProvider(newId, id)). */
    public synchronized List<Map<String, Object>> renameProvider(String id, String newId) throws OpException {
        List<Map<String, Object>> rewritten = new ArrayList<>();
        Map<Entry, String> snapshot = new LinkedHashMap<>();
        String prefix = id + ":";
        for (Map.Entry<String, Map<String, Entry>> t : tasks.entrySet())
            for (Map.Entry<String, Entry> d : t.getValue().entrySet()) {
                Entry e = d.getValue();
                if (e.model != null && e.model.startsWith(prefix)) {
                    snapshot.put(e, e.model);
                    e.model = newId + ":" + e.model.substring(prefix.length());
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("task", t.getKey());
                    row.put("depth", d.getKey());
                    row.put("model", e.model);
                    rewritten.add(row);
                }
            }
        if (!rewritten.isEmpty() && !persist()) {
            for (Map.Entry<Entry, String> s : snapshot.entrySet()) s.getKey().model = s.getValue();
            throw notWritten();
        }
        return rewritten;
    }

    /** task.configure {task, depth, model?, effort?, turns?, budget?} — a MERGE:
     *  a param that is absent (null / -1) keeps the stored value, a blank string
     *  or 0 resets the field to its default (settings r1 L1 — a client sending
     *  only `effort` must not wipe the model). The model must resolve against
     *  the registry (E_SYNTAX names the registered ids) so a typo cannot
     *  silently route the routine to the default provider, and it cannot be
     *  the scripted agent (it runs only triage-package goals). */
    public synchronized Map<String, Object> configure(String task, String depth, String model,
            String effort, int turns, int budget, LlmRegistry llm) throws OpException {
        if (!REVERSE.equals(task))
            throw OpException.notFound("unknown built-in task: " + task, "tasks: " + REVERSE);
        String d = Depth.normalize(depth);
        if (d == null) throw OpException.syntax("task.configure needs a depth (quick|deep)", "");
        Entry prev = get(task, d);
        Entry e = new Entry();
        if (prev != null) { e.model = prev.model; e.effort = prev.effort; e.turns = prev.turns; e.budget = prev.budget; }
        if (model != null) {
            if (model.isBlank()) e.model = null;
            else {
                String[] r = llm.resolve(model.trim());
                if (r == null)
                    throw OpException.syntax("unknown provider/model: " + model + " — expected provider:model"
                            + " (e.g. " + llm.exampleSpec(model) + ") · registered: " + String.join(", ", llm.ids()), "");
                LlmRegistry.Provider pr = llm.get(r[0]);
                if (pr != null && "builtin".equals(pr.driver))
                    throw OpException.syntax("the scripted agent runs only \"triage-package <prefix>\" goals — pick an LLM provider", "model=" + model);
                if ("fixture".equals(r[0]) && !"1".equals(System.getenv("WORKBENCH_QA")))
                    throw OpException.syntax("the fixture provider plays canned responses — pick a real one (WORKBENCH_QA=1 allows it for tests)", "model=" + model);
                e.model = r[0] + ":" + r[1];
            }
        }
        if (effort != null) e.effort = normalizeEffort(effort);
        if (turns < -1 || budget < -1) throw OpException.syntax("turns and budget must be positive (0 = default)", "");
        if (turns >= 0) e.turns = turns;
        if (budget >= 0) e.budget = budget;
        boolean createdMap = !tasks.containsKey(task);
        Map<String, Entry> depths = tasks.computeIfAbsent(task, k -> new LinkedHashMap<>());
        depths.put(d, e);
        if (!persist()) {
            if (prev != null) depths.put(d, prev); else depths.remove(d);
            if (createdMap && depths.isEmpty()) tasks.remove(task);   // don't leave a bare {"reverse":{}} the snapshot never covered (review r-f31a574 L8)
            throw notWritten();
        }
        return depthRow(task, d, llm);
    }
}
