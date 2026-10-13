package dev.workbench.server;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.workbench.ops.Ops;
import dev.workbench.registry.Dispatcher;
import dev.workbench.registry.Json;
import dev.workbench.registry.Op;
import dev.workbench.registry.Registry;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * HTTP+SSE server (old Jeeves pattern, JDK com.sun.net.httpserver only).
 * Binds 127.0.0.1:7800 — the app's reserved range is 7800–7809
 * (7800 = HTTP API + SSE + static; 7801 = future VSCode TCP bridge;
 * 7802 = sidecar control). Old Jeeves owns 7373/9100 and is untouched.
 *
 *   POST /api/<op>    JSON body {params?, actor?} → dispatcher (all ops)
 *   GET  /api/<op>    read-only ops only (mutations are POST-only)
 *   GET  /api/events  SSE: hello snapshot + channel events + heartbeat
 *   GET  /*           static files from web/
 *
 * Security (unchanged from old Jeeves): loopback bind only, Origin/Host
 * check, mutations POST-only.
 */
public final class WorkbenchServer {

    /** Primary port of the app's reserved range (7800–7809).
     *  Override: -Dworkbench.port=N or WORKBENCH_PORT=N. */
    public static final int PORT = Integer.parseInt(
            System.getProperty("workbench.port",
                    System.getenv().getOrDefault("WORKBENCH_PORT", "7800")));

    private final Dispatcher dispatcher;
    private final Registry registry;
    private final Workspace workspace;
    private final EventBus bus;
    private final Path webRoot;

    public WorkbenchServer(Path webRoot) {
        this.workspace = new Workspace();
        this.bus = new EventBus();
        this.workspace.setRuns(new RunsManager(workspace, bus, runsDir().toFile()));
        /* maps are PER-PROJECT (GRAPHS_SPEC §3: project sidecar) — the workspace
           owns store creation/swap on project switch, incl. the one-time
           migration of the legacy global maps/ dir into p1 (map-r4 fix) */
        workspace.setWorkbenchRoot(runsDir().getParent().toFile());
        Log.init(runsDir().getParent().toAbsolutePath().normalize().toFile());   // <home>/logs/workbench.log, rotated, persistent
        System.err.println("workbench: home " + runsDir().getParent().toAbsolutePath().normalize()
                + " · tmp " + System.getProperty("java.io.tmpdir"));   // the one line that says which sidecar this server serves
        sweepScratch(runsDir().getParent().toFile());
        workspace.initMaps(MapSeed.fcm());
        AgentManager agentManager = new AgentManager(workspace, bus, runsDir().getParent().toFile());
        this.workspace.setAgents(agentManager);
        this.workspace.setDevices(new dev.workbench.device.DeviceManager(runsDir().getParent().toFile()));
        this.workspace.setLayouts(new LayoutStore(runsDir().getParent().toFile()));
        this.workspace.setUiPrefs(new UiPrefs(runsDir().getParent().toFile()));   // the operator's chrome (audit decision 17)
        Presence presence = new Presence(workspace, bus);
        this.workspace.setPresence(presence);
        this.registry = Ops.registerAll(workspace);
        this.dispatcher = new Dispatcher(registry);
        this.dispatcher.setScopeChecker(agentManager::checkScope);
        this.dispatcher.setAfterOp(presence::afterOp);   // agents' locating ops move their pin
        this.dispatcher.setDecorator(workspace::decorateForAgent);   // an agent's reads mark agent-assigned names (hypotheses, not ground truth)
        agentManager.setDispatcher(this.dispatcher);   // http agent loop dispatches internally
        this.webRoot = webRoot;
    }

    /** RunStore dir: backend/.workbench/runs (gitignored), found from either cwd.
        WORKBENCH_HOME overrides the whole sidecar root — ITs run hermetic against
        a throwaway home so they never touch the interactive server's maps/runs
        (M6IT's seed expectations vs a live, user-edited fcm map — map-r4 fix). */
    private static Path runsDir() {
        String home = System.getenv("WORKBENCH_HOME");
        if (home != null && !home.isBlank()) {
            Path d = Path.of(home.trim()).resolve("runs");
            try {
                Files.createDirectories(d);
            } catch (IOException ignored) {}
            return d;
        }
        for (String p : List.of("backend/.workbench/runs", ".workbench/runs", "../backend/.workbench/runs")) {
            Path d = Path.of(p);
            /* the candidate's .workbench directory must EXIST. The old clause also accepted a
               candidate whose grandparent existed: from a cwd of backend/ a stray backend/backend/
               dir (a QA server's relative WORKBENCH_HOME) made "backend/.workbench" win, a fresh
               empty home was created there and the WhatsApp database migrated into it
               (2026-09-15). A home is never invented from a heuristic; workbench.sh always exports
               an absolute WORKBENCH_HOME. */
            if (d.getParent() != null && Files.isDirectory(d.getParent())) {
                try {
                    Files.createDirectories(d);
                } catch (IOException ignored) {}
                return d;
            }
        }
        return Path.of(".workbench/runs");
    }

    /** Leftover scratch files (a CLI mission's mcp config, a probe's cwd, a command's output
     *  capture) older than a day, swept from the JVM temp dir — only when that dir is under
     *  the home (workbench.sh / gradle put it there); the system /tmp is never touched. */
    static void sweepScratch(java.io.File home) {
        try {
            Path tmp = Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize();
            if (!tmp.startsWith(home.toPath().toAbsolutePath().normalize()) || !Files.isDirectory(tmp)) return;
            long cutoff = System.currentTimeMillis() - 24L * 3600 * 1000;
            int n = 0;
            try (var st = Files.list(tmp)) {
                for (Path f : (Iterable<Path>) st::iterator) {
                    String nm = f.getFileName().toString();
                    if (!(nm.startsWith("wb-") || nm.startsWith("llm-probe") || nm.startsWith("idalib-resolve"))) continue;
                    if (Files.getLastModifiedTime(f).toMillis() > cutoff) continue;
                    if (Files.isDirectory(f)) { try (var w = Files.walk(f)) { for (Path q : w.sorted(java.util.Comparator.reverseOrder()).toList()) Files.deleteIfExists(q); } }
                    else Files.deleteIfExists(f);
                    n++;
                }
            }
            if (n > 0) System.err.println("workbench: swept " + n + " stale scratch file(s) from " + tmp);
        } catch (Exception e) {
            System.err.println("workbench: scratch sweep skipped: " + e);
        }
    }

    public static void main(String[] args) throws IOException {
        Path webRoot = args.length > 0
                ? Path.of(args[0])
                : Path.of("web").toAbsolutePath().normalize();
        WorkbenchServer server = new WorkbenchServer(webRoot);
        server.start();
        /* SIGTERM (pkill, launchd, Ctrl-C) must close JEB sessions: a dirty jdb2
           gets its save and every lock file is released. Without this a killed
           server leaves locks behind and the next open of that jdb2 can fail at
           load (observed as an opaque JEB NPE, "this.YS is null"). */
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            var cleanup = server.workspace.runs().beginShutdown();
            try { server.workspace.closeAll(); } catch (Throwable ignored) {}
            try { cleanup.join(); } catch (Throwable ignored) {}
        }, "wb-shutdown"));
    }

    private HttpServer http;
    private java.util.concurrent.ExecutorService httpWorkers;

    public void start() throws IOException {
        String host = "127.0.0.1";
        /* loopback only: this process has no authentication of its own (the Host check is spoofable) —
           a public bind would be an unauthenticated workbench on the network */
        http = HttpServer.create(new InetSocketAddress(host, PORT), 64);
        http.createContext("/api/", this::handleApi);
        http.createContext("/healthz", this::handleHealthz);
        http.createContext("/", this::handleStatic);
        httpWorkers = java.util.concurrent.Executors.newCachedThreadPool();
        http.setExecutor(httpWorkers);
        http.start();
        System.out.println("workbench listening on http://" + host + ":" + PORT
                + " (ops=" + registry.size() + ", web=" + webRoot + ")");
    }

    /** GET /healthz — the operator's readiness probe: loopback peers only, GET only. */
    private void handleHealthz(HttpExchange ex) throws IOException {
        try {
            java.net.InetSocketAddress peer = ex.getRemoteAddress();
            if (peer == null || peer.getAddress() == null || !peer.getAddress().isLoopbackAddress()) { send(ex, 404, err("E_NOTFOUND", "not found", "")); return; }
            if (!ex.getRequestMethod().equals("GET")) { send(ex, 405, err("E_SYNTAX", "GET only", "")); return; }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("ready", true);
            send(ex, 200, Json.write(out));
        } catch (Exception e) {
            send(ex, 500, err("E_BUSY", "healthz failed: " + e.getMessage(), ""));
        } finally { ex.close(); }
    }

    /** Stop the listener (ITs simulate a restart with stop + new server). */
    public void stop() {
        if (http != null) { http.stop(0); http = null; }
        if (httpWorkers != null) { httpWorkers.shutdownNow(); httpWorkers = null; }
        var cleanup = workspace.runs().beginShutdown();
        try { workspace.closeAll(); } finally { cleanup.join(); }
    }

    // ------------------------------------------------------------ /api/*

    private void handleApi(HttpExchange ex) throws IOException {
        try {
            String path = ex.getRequestURI().getPath();
            String rest = path.substring("/api/".length());
            if (rest.equals("events")) {
                if (!checkOrigin(ex)) return;
                handleEvents(ex);
                return;
            }
            String opName = rest;
            Op op = registry.get(opName);
            String method = ex.getRequestMethod();
            if (!method.equals("POST")) {
                // Mutations are POST-only; reads allowed via GET for convenience.
                if (!method.equals("GET")) {
                    send(ex, 405, err("E_SYNTAX", "method not allowed: " + method, "use POST"));
                    return;
                }
                if (op != null && op.mutating()) {
                    send(ex, 405, err("E_SYNTAX",
                            "op '" + opName + "' is mutating — POST-only", "mutations are POST-only"));
                    return;
                }
            }
            if (!checkOrigin(ex)) return;

            Map<String, Object> body = method.equals("POST")
                    ? readBody(ex) : new LinkedHashMap<>();
            Object paramsObj = body.get("params");
            @SuppressWarnings("unchecked")
            Map<String, Object> params = paramsObj instanceof Map
                    ? (Map<String, Object>) paramsObj : new LinkedHashMap<>();
            // GET: params come from the query string
            if (method.equals("GET")) params.putAll(queryParams(ex));
            String actor = body.get("actor") instanceof String a ? a
                    : ex.getRequestHeaders().getFirst("X-Actor");
            if (actor == null) actor = "human";
            Map<String, Object> resp = dispatcher.dispatch(opName, params, actor);
            send(ex, 200, Json.write(resp));
        } catch (IllegalArgumentException e) {
            Log.warn("http", "bad request " + ex.getRequestURI() + ": " + e.getMessage(), null);
            send(ex, 400, err("E_SYNTAX", "bad request: " + e.getMessage(), ""));
        } catch (Exception e) {
            Log.error("http " + ex.getRequestURI(), e, null);
            send(ex, 500, err("E_BUSY", "server error: " + e, ""));
        } finally {
            ex.close();
        }
    }

    private Map<String, Object> readBody(HttpExchange ex) throws IOException {
        try (InputStream in = ex.getRequestBody()) {
            String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            return Json.parseObject(text);
        }
    }

    private static Map<String, Object> queryParams(HttpExchange ex) {
        Map<String, Object> m = new LinkedHashMap<>();
        String q = ex.getRequestURI().getRawQuery();
        if (q == null) return m;
        for (String pair : q.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0)
                m.put(urlDecode(pair.substring(0, eq)), urlDecode(pair.substring(eq + 1)));
        }
        return m;
    }

    private static String urlDecode(String s) {
        return java.net.URLDecoder.decode(s, StandardCharsets.UTF_8);
    }

    /** Origin/Host check (old Jeeves CSRF guard): loopback Host only; Origin, if sent, must match Host. */
    private boolean checkOrigin(HttpExchange ex) throws IOException {
        String host = ex.getRequestHeaders().getFirst("Host");
        if (host == null || !(host.equals("127.0.0.1:" + PORT) || host.equals("localhost:" + PORT)
                || host.equals("[::1]:" + PORT))) {
            send(ex, 403, err("E_SYNTAX", "bad Host: " + host, "loopback only"));
            return false;
        }
        String origin = ex.getRequestHeaders().getFirst("Origin");
        if (origin != null) {
            String originHost = origin.replaceFirst("^https?://", "").replaceAll("/.*$", "");
            boolean ok = originHost.equals(host);
            if (!ok) {
                send(ex, 403, err("E_SYNTAX", "Origin/Host mismatch", "origin=" + origin));
                return false;
            }
        }
        return true;
    }

    // ------------------------------------------------------------ SSE

    private void handleEvents(HttpExchange ex) throws IOException {
        ex.getResponseHeaders().set("Content-Type", "text/event-stream");
        ex.getResponseHeaders().set("Cache-Control", "no-cache");
        ex.getResponseHeaders().set("Connection", "keep-alive");
        ex.sendResponseHeaders(200, 0);
        OutputStream out = ex.getResponseBody();
        java.util.concurrent.BlockingQueue<Map<String, Object>> q = bus.subscribe();
        try {
            // hello snapshot replays current state (MCP_SPEC §5)
            Map<String, Object> hello = new LinkedHashMap<>();
            hello.put("type", "hello");
            hello.put("channels", java.util.List.of("progress", "presence", "activity", "proposals", "runs"));
            try {
                hello.put("state", workspace.state());
            } catch (dev.workbench.registry.OpException e) {
                hello.put("state", java.util.Map.of("error", e.getMessage()));
            }
            writeEvent(out, "hello", Json.write(hello));
            long lastBeat = System.currentTimeMillis();
            long deadline = lastBeat + 30L * 60 * 1000;              // 30 min session cap
            while (System.currentTimeMillis() < deadline) {
                Map<String, Object> ev = q.poll(500, java.util.concurrent.TimeUnit.MILLISECONDS);
                if (ev != null) {
                    writeEvent(out, String.valueOf(ev.getOrDefault("channel", "event")), Json.write(ev));
                    continue;
                }
                if (System.currentTimeMillis() - lastBeat > 15_000) {
                    lastBeat = System.currentTimeMillis();
                    writeEvent(out, "heartbeat", "{\"n\":" + lastBeat + "}");
                }
            }
        } catch (InterruptedException | IOException e) {
            // client disconnected
        } finally {
            bus.unsubscribe(q);
        }
    }

    private static void writeEvent(OutputStream out, String event, String data) throws IOException {
        out.write(("event: " + event + "\n").getBytes(StandardCharsets.UTF_8));
        for (String line : data.split("\n"))
            out.write(("data: " + line + "\n").getBytes(StandardCharsets.UTF_8));
        out.write('\n');
        out.flush();
    }

    // ------------------------------------------------------------ static

    private void handleStatic(HttpExchange ex) throws IOException {
        try {
            if (!ex.getRequestMethod().equals("GET")) {
                send(ex, 405, "method not allowed");
                return;
            }
            String path = ex.getRequestURI().getPath();
            if (path.equals("/")) path = "/index.html";
            // path traversal guard: resolve and require containment under webRoot
            Path resolved = webRoot.resolve(path.substring(1)).normalize();
            if (!resolved.startsWith(webRoot) || !Files.isRegularFile(resolved)) {
                send(ex, 404, "not found: " + path);
                return;
            }
            byte[] bytes = Files.readAllBytes(resolved);
            ex.getResponseHeaders().set("Content-Type", contentType(resolved));
            ex.sendResponseHeaders(200, bytes.length);
            try (OutputStream out = ex.getResponseBody()) {
                out.write(bytes);
            }
        } finally {
            ex.close();
        }
    }

    private static String contentType(Path p) {
        String n = p.getFileName().toString();
        if (n.endsWith(".html")) return "text/html; charset=utf-8";
        if (n.endsWith(".js")) return "text/javascript; charset=utf-8";
        if (n.endsWith(".css")) return "text/css; charset=utf-8";
        if (n.endsWith(".json")) return "application/json";
        if (n.endsWith(".svg")) return "image/svg+xml";
        return "application/octet-stream";
    }

    // ------------------------------------------------------------ responses

    private static String err(String code, String message, String detail) {
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("ok", false);
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("code", code);
        e.put("message", message);
        e.put("detail", detail);
        resp.put("error", e);
        return Json.write(resp);
    }

    private static void send(HttpExchange ex, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(bytes);
        }
    }
}
