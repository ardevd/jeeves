package dev.workbench.course;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Course integration (WORKBENCH_COURSE=1): this instance is one student's research room of a
 * class served by the class server. The switch is read once
 * at boot; what it changes is decided where it is asked:
 *
 *   identity   every request reaches this process through the class's nginx, which authenticated
 *              the student and hands over X-WB-Principal (the room) and X-WB-Grant; the grant is
 *              validated against the auth service and the actor is DERIVED from it — a browser's
 *              own actor string is never the classroom identity (CourseIdentity)
 *   capture    a mission's requests, answers, tool results and annotations are journaled whole,
 *              privately, and exported to the class store with durable acknowledgement; in
 *              strict mode a request that cannot be admitted is not dispatched (Capture)
 *   target     the course's target manifest is resolved against the opened artifact's bytes
 *              before a comparison run; every event carries the target's identity (CourseManifest)
 *   classroom  a finding goes to the class board through the room's own credential; help,
 *              progress and threads go to the classroom's room-facing endpoint where the server
 *              offers one, and open the browser's classroom where it does not (CourseOps)
 *
 * Off (the default), none of this applies and nothing here is consulted: the owner's workbench is
 * unchanged. Every value has an environment variable (the deployment) and a system property
 * `workbench.course.<name>` (tests, which start the server in-process and cannot set env).
 * Credentials are read from files at call time and are never logged, echoed or exported.
 */
public final class Course {

    private Course() {}

    public static final String SCHEMA = "day1-event-v1";
    public static final String MANIFEST_SCHEMA = "day1-target-manifest-v1";

    private static volatile Map<String, String> overrides = Map.of();   // tests: set before the server boots
    public static void overrides(Map<String, String> o) { overrides = o == null ? Map.of() : Map.copyOf(o); }

    /** A setting: the override, else the system property, else the environment; null when unset. */
    public static String get(String name) {
        String o = overrides.get(name);
        if (o != null) return o;
        String p = System.getProperty("workbench.course." + name.toLowerCase(Locale.ROOT).replace('_', '.'));
        if (p != null) return p;
        return System.getenv("WORKBENCH_COURSE_" + name.toUpperCase(Locale.ROOT));
    }

    private static String get(String name, String dflt) {
        String v = get(name);
        return v == null || v.isBlank() ? dflt : v.trim();
    }

    public static boolean truthy(String v) {
        if (v == null) return false;
        String s = v.trim().toLowerCase(Locale.ROOT);
        return s.equals("1") || s.equals("true") || s.equals("yes") || s.equals("on");
    }

    /** WORKBENCH_COURSE: the switch — the documented variable itself (the generic get() would spell
     *  it WORKBENCH_COURSE_), the test override "", -Dworkbench.course, or WORKBENCH_COURSE_ON. */
    public static boolean on() {
        String o = overrides.get("");
        if (o != null) return truthy(o);
        if (truthy(get("on"))) return true;
        String p = System.getProperty("workbench.course");
        if (p != null) return truthy(p);
        return truthy(System.getenv("WORKBENCH_COURSE"));
    }

    /** The misconfiguration a room must not boot through silently: course settings present in the
     *  environment (a room, credentials, a manifest …) while the switch is unset — a room that
     *  boots standalone has no gate, no capture and no manifest check. An explicit
     *  WORKBENCH_COURSE=0 is a decision, not a slip. Returns the offending names, or empty. */
    public static List<String> settingsWithoutSwitch() {
        List<String> out = new ArrayList<>();
        if (on()) return out;
        String sw = System.getenv("WORKBENCH_COURSE"), swOn = System.getenv("WORKBENCH_COURSE_ON");
        if ((sw != null && !sw.isBlank()) || (swOn != null && !swOn.isBlank())) return out;   // explicitly off
        for (String k : System.getenv().keySet())
            if (k.startsWith("WORKBENCH_COURSE_") && !System.getenv(k).isBlank()) out.add(k);
        java.util.Collections.sort(out);
        return out;
    }

    /** An activity id as the classroom names them (E3, E4b, intro-2 …): a short token. */
    public static boolean validActivity(String id) { return id != null && id.matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,31}"); }

    /** The room this instance serves, as the class server names audiences: "room-7". */
    public static String room() { return get("room", ""); }

    /** The operator's FALLBACK label for the activity (WORKBENCH_COURSE_ACTIVITY): what an event
     *  carries only while the classroom's own context has never been read — the classroom owns the
     *  current activity (CourseActivity); nothing in a room sets it. */
    public static String activity() { return get("activity", "E3"); }

    /** Loopback service URLs of the class server (its inventory's ports are the defaults). */
    public static String authUrl() { return get("auth_url", "http://127.0.0.1:8200"); }
    public static String storeUrl() { return get("store_url", "http://127.0.0.1:8201"); }
    public static String gatewayUrl() { return get("gateway_url", "http://127.0.0.1:8202"); }
    public static String boardUrl() { return get("board_url", "http://127.0.0.1:8300"); }
    /** The classroom service (inventory: loopback 8205) — read-only room context: the current
     *  activity's identity and the browser handoff URLs (POST /v1/room/context {}). An explicit
     *  empty value turns it off (fixtures without a classroom). */
    public static String classroomUrl() { String v = get("classroom_url"); return v == null ? "http://127.0.0.1:8205" : v.trim(); }
    /** The class portal (apex) for browser deep links, e.g. "https://class.example". */
    public static String portalUrl() { return get("portal_url", ""); }

    /** The approved read-only course roots a room may browse and open (WORKBENCH_COURSE_ROOTS,
     *  colon-separated absolute directories: the release's artifacts, the preanalysed seed). */
    public static List<String> roots() {
        List<String> out = new ArrayList<>();
        for (String s : get("roots", "").split(":")) if (!s.isBlank()) out.add(s.trim());
        return out;
    }

    /** The capture's root: the central per-owner durable mount when the deployment gives one
     *  (WORKBENCH_COURSE_CAPTURE_ROOT, e.g. /srv/store/room-owners/&lt;account&gt;/&lt;generation&gt;/capture),
     *  else &lt;home&gt;/capture. */
    public static File captureRoot(File home) { String r = get("capture_root", ""); return r.isEmpty() ? new File(home, "capture") : new File(r); }
    /** The operator DECLARES the capture root durable (on the qualified central mount that survives
     *  the room's replacement): WORKBENCH_COURSE_CAPTURE_DURABLE=1. A room cannot find this out by
     *  itself; undeclared, a payload kept only here is reported as not durable. */
    public static boolean captureDurableDeclared() { return truthy(get("capture_durable")); }

    /** Tests only (WORKBENCH_COURSE_ALLOW_MOCK / the `allow_mock` override): boot course mode on the
     *  mock engine. A deployment never sets it — a room without the real engine is not a deployment. */
    public static boolean allowMock() { return truthy(get("allow_mock")); }

    /** The owner the launcher says this room is for (the server's launch plan: WORKBENCH_COURSE_ACCOUNT_ID,
     *  _GENERATION, _SESSION_ID): the auth service's context must name the same owner, else the room
     *  is fenced — a home or a credential of another generation is never served. Unset = not checked. */
    public static String expectedAccountId() { return get("account_id", ""); }
    public static String expectedGeneration() { return get("generation", ""); }
    public static String expectedSessionId() { return get("session_id", ""); }

    /** The nginx→room service bearer (a systemd credential: WORKBENCH_COURSE_PROXY_TOKEN_FILE): when
     *  set, every request must carry it as `Authorization: Bearer …` besides the opaque grant — the
     *  trusted-peer address alone is not the edge. Unset = the peer check only (said in course.status). */
    public static File proxyTokenFile() { String f = get("proxy_token_file", ""); return f.isEmpty() ? null : new File(f); }

    /** The address the server binds (WORKBENCH_HOST, default loopback). */
    public static String host() {
        String v = System.getProperty("workbench.host", System.getenv("WORKBENCH_HOST"));
        return v == null || v.isBlank() ? "127.0.0.1" : v.trim();
    }

    /**
     * The classroom's tool subset (student MCP addendum 2026-10-09): what an external assistant may call
     * through the facade in a room — code/symbols/xrefs/strings/search evidence, parameter evidence
     * (code {view: params}), annotations, bounded navigation (target list/info/switch, project list),
     * saved-state inspection (history, workspace, map reads, audit). Excluded until the exercise asks and
     * the operator extends the set (WORKBENCH_COURSE_TOOLS adds names): the host (devices, runs, run,
     * breakpoints), autonomous missions (mission, reversing_agent, review, llm), map editing and verdicts,
     * flows, the index rebuild. Enforced in ToolFacade (catalogue AND call), not only in a client's filter.
     */
    public static final List<String> DEFAULT_TOOLS = List.of("symbols", "code", "xrefs", "strings", "search", "annotate", "map", "workspace", "history", "target", "project", "audit", "presence");

    public static List<String> toolSubset() {
        List<String> out = new ArrayList<>(DEFAULT_TOOLS);
        for (String s : get("tools", "").split(",")) if (s.trim().matches("[a-z_]{1,32}") && !out.contains(s.trim())) out.add(s.trim());
        return out;
    }

    /** The ops a MACHINE principal — the student's assistant through the bridge, or anything else holding
     *  the room token — may call at all: the facade's router and its catalogue, the room's status, the
     *  evidence export. Every other op is the student's page's: a room token in a curl reaches neither
     *  agent.spawn nor project.delete nor target.open (fix-review addendum H1). The facade's subset
     *  (toolSubset, allowedActions) then bounds what tool.call routes. */
    public static final java.util.Set<String> MACHINE_OPS = java.util.Set.of("tool.call", "ops.tools", "course.status", "course.export.artifact");

    /** Per-tool action limits in a room: the target and project tools navigate, they do not open or close
     *  (the seed is the room's target; the student's own page opens and closes); history is read-only for
     *  an assistant — undo and redo are the analyst's own gestures (fix-review addendum M5). */
    public static List<String> allowedActions(String tool) {
        return switch (tool) {
            case "target" -> List.of("list", "info", "changes", "save", "save_status", "switch");
            case "project" -> List.of("list", "targets", "known");
            case "history" -> List.of("list");
            default -> null;   // every action of the tool
        };
    }

    /** The runtime's pinned identity, set by the room's launcher: the Git revision
     *  the package was built from (40 hex) and the package manifest's sha256 (64 hex). A development
     *  checkout has neither — reported as unpinned, never invented. */
    public static String runtimeRevision() {
        String v = System.getProperty("workbench.runtime.revision", System.getenv("WORKBENCH_RUNTIME_REVISION"));
        return v != null && v.trim().matches("[0-9a-f]{40}") ? v.trim() : null;
    }
    public static String packageSha256() {
        String v = System.getProperty("workbench.package.sha256", System.getenv("WORKBENCH_PACKAGE_SHA256"));
        return v != null && v.trim().matches("[0-9a-f]{64}") ? v.trim() : null;
    }

    /** The directory of the room's client credentials, in the server's layout:
     *  &lt;dir&gt;/room-7--auth, room-7--store, room-7--gateway, room-7--board (one token each). A
     *  per-service file can be named instead: WORKBENCH_COURSE_&lt;SVC&gt;_TOKEN_FILE. */
    public static File credentialFile(String service) {
        String explicit = get(service + "_token_file");
        if (explicit != null && !explicit.isBlank()) return new File(explicit.trim());
        String dir = get("credentials", "");
        if (dir.isEmpty()) return null;
        return new File(dir, room() + "--" + service);
    }

    /** Peers whose X-WB-* headers are believed — the class's nginx, on this host. */
    public static List<String> trustedProxies() {
        List<String> out = new ArrayList<>();
        for (String s : get("trust_proxy", "127.0.0.1,::1,0:0:0:0:0:0:0:1").split(",")) if (!s.isBlank()) out.add(s.trim());
        return out;
    }

    /** strict (the default): a model request is dispatched only after its capture record is durable
     *  at the class store; best-effort: journaled locally, exported as the store allows; off: no
     *  export, the local journal only. */
    public static String captureMode() {
        String m = get("capture", "strict").toLowerCase(Locale.ROOT);
        return m.equals("best-effort") || m.equals("off") ? m : "strict";
    }

    /** The course's target manifest file (CourseManifest). */
    public static File manifestFile() { String f = get("manifest", ""); return f.isEmpty() ? null : new File(f); }

    /** The class inference gateway as a provider of this room (id `class`): the served model ids come
     *  from the server's connection card (WORKBENCH_COURSE_CLASS_MODELS, comma-separated, the first is
     *  the default) — never guessed here; the key is the room's gateway credential file, read at call
     *  time. Empty = the class route is not configured yet (course.status.model says so). */
    public static List<String> classModels() {
        List<String> out = new ArrayList<>();
        for (String s : get("class_models", "").split(",")) if (!s.isBlank()) out.add(s.trim());
        return out;
    }
    public static String classLabel() { return get("class_label", "Class model (course gateway)"); }
    public static String classReasoning() { return get("class_reasoning", "reasoning_content:all"); }
    public static int classMaxOutput() { return num("class_maxout", 8192); }

    /** The Day 1 preset: provider:model and its caps — WORKBENCH_COURSE_PROVIDER, else the class
     *  gateway's first model when the connection card named one. */
    public static String presetModel() {
        String v = get("provider", "");
        if (!v.isEmpty()) return v;
        List<String> models = classModels();
        return models.isEmpty() ? "" : "class:" + models.get(0);
    }
    public static int presetTurns() { return num("preset_turns", 8); }
    public static int presetOutput() { return num("preset_output", 4096); }
    public static int presetBudget() { return num("preset_budget", 120_000); }

    /** This room's exports are evaluation-tainted, always: the server forces it per room and the
     *  handoff says it is never weakened from here — a setting that says otherwise is ignored (and
     *  named by describe()). */
    public static boolean evalTaint() { return true; }
    static boolean evalTaintSettingDisagrees() { String v = get("eval_taint"); return v != null && !v.isBlank() && !truthy(v); }

    private static int num(String name, int dflt) {
        String v = get(name);
        if (v == null || v.isBlank()) return dflt;
        try { return Math.max(0, Integer.parseInt(v.trim())); } catch (NumberFormatException e) { return dflt; }
    }

    /** What course.status reports about the configuration itself (no secrets). */
    public static Map<String, Object> describe() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("on", on());
        m.put("room", room());
        m.put("activity", activity());
        m.put("capture", captureMode());
        m.put("schema", SCHEMA);
        m.put("services", Map.of("auth", authUrl(), "store", storeUrl(), "gateway", gatewayUrl(), "board", boardUrl(),
                "classroom", classroomUrl().isEmpty() ? "(off — no classroom context)" : classroomUrl()));
        Map<String, Object> creds = new LinkedHashMap<>();
        for (String svc : List.of("auth", "store", "gateway", "classroom")) {
            File f = credentialFile(svc);
            creds.put(svc, f == null ? "unset" : f.isFile() ? "present" : "missing: " + f.getPath());
        }
        m.put("credentials", creds);
        m.put("manifest", manifestFile() == null ? "unset" : manifestFile().getPath());
        m.put("roots", roots());
        m.put("expectedOwner", Map.of("accountId", expectedAccountId(), "workspaceGeneration", expectedGeneration(), "courseSessionId", expectedSessionId()));
        m.put("proxyToken", proxyTokenFile() == null ? "unset — the trusted-peer address alone names the edge" : proxyTokenFile().isFile() ? "present" : "missing: " + proxyTokenFile().getPath());
        m.put("captureRoot", get("capture_root", "") .isEmpty() ? "<home>/capture" : get("capture_root", ""));
        m.put("captureDurableDeclared", captureDurableDeclared());
        m.put("runtime", Map.of("revision", runtimeRevision() == null ? "unpinned (development checkout)" : runtimeRevision(),
                "packageSha256", packageSha256() == null ? "unpinned" : packageSha256()));
        m.put("preset", Map.of("model", presetModel(), "turns", presetTurns(), "output", presetOutput(), "budget", presetBudget()));
        m.put("evalTaint", evalTaint());
        if (evalTaintSettingDisagrees()) m.put("evalTaintNote", "WORKBENCH_COURSE_EVAL_TAINT asks for false — ignored: a room never weakens the taint");
        return m;
    }
}
