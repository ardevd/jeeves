package dev.workbench.runs;

import dev.workbench.registry.Json;
import dev.workbench.server.EventBus;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The backend's window onto the Frida sidecar (VIEWS_SPEC §2). Frida has no JVM
 * host binding, so the controlling side is a python venv (backend/runs/bridge.py,
 * ported from old Jeeves) launched and driven over newline-delimited JSON on
 * stdio — request/response by id, plus async events (hits, session lifecycle)
 * republished on the workspace {@link EventBus} (channel "runs").
 *
 * Additive law: the sidecar starts lazily on the first runs op; if the venv is
 * missing (WORKBENCH_RUNS_PYTHON not configured), runs ops fail cleanly and the
 * static workbench is unaffected. No hook suspends or modifies the target —
 * hits are observations only.
 */
public final class FridaBridge {

    /** Listener for sidecar events that carry run data (hits); the RunsManager owns attribution. */
    public interface HitListener {
        void onHit(Map<String, Object> ev);
        void onHitCount(Map<String, Object> ev);
        void onSessionDead(Map<String, Object> ev);
        void onSessionLive(Map<String, Object> ev);
    }

    private final EventBus bus;
    private volatile HitListener listener;
    private volatile Process proc;
    private volatile boolean closed;
    private BufferedWriter toSidecar;
    private volatile boolean started;
    private final AtomicInteger seq = new AtomicInteger();
    private record Pending(Process owner, CompletableFuture<Map<String,Object>> future) {}
    private final Map<Integer, Pending> pending = new ConcurrentHashMap<>();
    private CompletableFuture<Void> shutdownResult;

    public FridaBridge(EventBus bus) {
        this.bus = bus;
    }

    public void setListener(HitListener l) {
        this.listener = l;
    }

    public boolean isUp() {
        return started && proc != null && proc.isAlive();
    }

    // ------------------------------------------------------------ lifecycle

    private synchronized void ensureStarted() {
        if (closed) throw new IllegalStateException("Frida bridge is shut down; no automatic restart");
        if (isUp()) return;
        if (proc != null && !proc.isAlive()) failPending(proc);
        try {
            ProcessBuilder pb = new ProcessBuilder(sidecarCommand());
            pb.redirectError(ProcessBuilder.Redirect.INHERIT);
            proc = pb.start();
            toSidecar = new BufferedWriter(new OutputStreamWriter(proc.getOutputStream(), StandardCharsets.UTF_8));
            BufferedReader fromSidecar = new BufferedReader(
                    new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8));
            Process owner = proc;
            Thread t = new Thread(() -> readLoop(fromSidecar, owner), "frida-bridge-reader");
            t.setDaemon(true);
            t.start();
            started = true;
        } catch (IOException e) {
            throw new RuntimeException("failed to start frida sidecar: " + e.getMessage(), e);
        }
    }

    @SuppressWarnings("unchecked")
    private void readLoop(BufferedReader in, Process owner) {
        try {
            String line;
            while ((line = in.readLine()) != null) {
                /* a malformed line must not kill the read loop — its death hangs
                   every pending CompletableFuture forever (fix-1-r6, nit) */
                Object o;
                try {
                    o = Json.parse(line);
                } catch (RuntimeException parse) {
                    System.err.println("[frida-bridge] WARNING: skipping unparseable line: " + parse);
                    continue;
                }
                if (!(o instanceof Map)) continue;
                Map<String, Object> m = (Map<String, Object>) o;
                Object id = m.get("id");
                if (id instanceof Number) {
                    Pending call = pending.get(((Number) id).intValue());
                    if (call != null && call.owner() == owner && pending.remove(((Number) id).intValue(),call)) call.future().complete(m);
                } else if (m.get("event") != null && proc == owner) {
                    /* a listener throw (e.g. a corrupt stored bp row NPEing
                       recordHit) must not reach the loop — its death fails every
                       pending future and ends the sidecar stream (fix-1-r7 F1) */
                    try {
                        relay(m);
                    } catch (Exception relayErr) {
                        System.err.println("[frida-bridge] WARNING: event relay failed (loop survives): "
                                + relayErr);
                    }
                }
            }
        } catch (Exception ignored) {
            // sidecar pipe closed
        } finally {
            failPending(owner);
            synchronized (this) { if (proc == owner) started = false; }
        }
    }

    private void failPending(Process owner) {
        pending.forEach((id,call) -> {
            if (call.owner()==owner && pending.remove(id,call))
                call.future().completeExceptionally(new IllegalStateException("Owned frida sidecar exited; device cleanup may be unknown"));
        });
    }

    private void relay(Map<String, Object> ev) {
        String event = String.valueOf(ev.get("event"));
        HitListener l = listener;
        switch (event) {
            case "hit" -> {
                if (l != null) l.onHit(ev);
            }
            case "hitcount" -> {
                if (l != null) l.onHitCount(ev);
            }
            case "session" -> {
                if ("dead".equals(String.valueOf(ev.get("state")))) {
                    if (l != null) l.onSessionDead(ev);
                } else if (l != null) l.onSessionLive(ev);
                Map<String, Object> out = new LinkedHashMap<>(ev);
                bus.publish("runs", out);
            }
            case "native" -> {                // native bp arm outcome (mismatch/unverified/armed/armfail)
                Map<String, Object> out = new LinkedHashMap<>(ev);
                out.put("type", "native");
                bus.publish("runs", out);
            }
            default -> { /* agent logs, hitcount heartbeats handled above */ }
        }
    }

    public synchronized CompletableFuture<Void> beginShutdown() {
        if (shutdownResult != null) return shutdownResult;
        closed = true;
        Process owner = proc;
        BufferedWriter writer = toSidecar;
        // Mark closed synchronously; the workspace can now save without spawning
        // another sidecar. EOF/device cleanup proceeds alongside engine save.
        shutdownResult = CompletableFuture.runAsync(() -> {
            try { if (writer != null) writer.close(); } catch (IOException ignored) {}
            try {
                if (owner != null && !owner.waitFor(45, TimeUnit.SECONDS)) {
                    System.err.println("[frida-bridge] owned sidecar did not finish cleanup; retain ownership receipts, cleanup unknown");
                    owner.destroy();
                    if (!owner.waitFor(5, TimeUnit.SECONDS)) { owner.destroyForcibly(); owner.waitFor(5, TimeUnit.SECONDS); }
                }
                if (owner != null && !owner.isAlive() && owner.exitValue()!=0)
                    System.err.println("[frida-bridge] sidecar exited " + owner.exitValue() + "; inspect cleanup receipts");
            } catch (InterruptedException e) {
                System.err.println("[frida-bridge] cleanup wait interrupted; cleanup unknown");
                Thread.currentThread().interrupt();
            } finally { if (owner != null) failPending(owner); started = false; }
        });
        return shutdownResult;
    }

    public void shutdown() { beginShutdown().join(); }

    // ------------------------------------------------------------ request/response

    @SuppressWarnings("unchecked")
    private Map<String, Object> request(String op, Map<String, Object> params, long timeoutMs) {
        int id = seq.incrementAndGet();
        Map<String, Object> msg = new LinkedHashMap<>();
        msg.put("id", id);
        msg.put("op", op);
        if (params != null) msg.putAll(params);
        CompletableFuture<Map<String, Object>> f = new CompletableFuture<>();
        try {
            synchronized (this) {
                ensureStarted();
                pending.put(id, new Pending(proc, f));
                toSidecar.write(Json.write(msg));
                toSidecar.write("\n");
                toSidecar.flush();
            }
        } catch (IOException e) {
            pending.remove(id);
            throw new RuntimeException("frida sidecar write failed: " + e.getMessage(), e);
        }
        try {
            Map<String, Object> resp = f.get(timeoutMs, TimeUnit.MILLISECONDS);
            if (Boolean.FALSE.equals(resp.get("ok")))
                throw new RuntimeException(String.valueOf(resp.get("error")));
            resp.remove("id");
            resp.remove("ok");
            return resp;
        } catch (TimeoutException e) {
            pending.remove(id);
            throw new RuntimeException("frida sidecar timed out on '" + op + "'");
        } catch (InterruptedException e) {
            pending.remove(id);
            Thread.currentThread().interrupt();
            throw new RuntimeException("interrupted waiting on frida sidecar");
        } catch (ExecutionException e) {
            throw new RuntimeException(e.getCause() == null ? e.getMessage() : e.getCause().getMessage());
        }
    }

    // ------------------------------------------------------------ sidecar ops

    public Map<String, Object> ping() { return request("ping", null, 10_000); }

    public Map<String, Object> devices() { return request("enumerate_devices", null, 30_000); }

    public Map<String, Object> provision(String device, String serverPath, boolean ownershipConfirmed, int serverPort) {
        return request("provision", Map.of("device", device, "serverPath", serverPath,
                "ownershipConfirmed", ownershipConfirmed, "serverPort", serverPort), 90_000);
    }

    public Map<String, Object> cleanup(String device) {
        return request("cleanup", one("device", device), 45_000);
    }

    public Map<String, Object> attach(String device, String pkg, boolean spawn,
            Map<String, String> coldBroadcast, List<Map<String, Object>> hooks) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("device", device);
        p.put("package", pkg);
        if (spawn) p.put("spawn", true);
        if (coldBroadcast != null) p.put("coldBroadcast", coldBroadcast);
        if (hooks != null && !hooks.isEmpty()) p.put("hooks", hooks);
        return request("attach", p, 90_000);
    }

    /** native breakpoints (2026-09-10): add a native hook to a live session — the bridge routes it
     *  to the native agent by the native:true marker. One of export/offset is set. */
    public Map<String, Object> addNativeHook(String session, String hookId, String module, String export,
            String offset, String buildId) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("session", session);
        p.put("hookId", hookId);
        p.put("native", true);
        p.put("module", module);
        if (export != null && !export.isBlank()) p.put("export", export);
        if (offset != null && !offset.isBlank()) p.put("offset", offset);
        if (buildId != null && !buildId.isBlank()) p.put("buildId", buildId);
        return request("add_hook", p, 30_000);
    }

    public Map<String, Object> addHook(String session, String hookId, String signature, Object statics) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("session", session);
        p.put("hookId", hookId);
        p.put("signature", signature);
        if (statics != null) p.put("statics", statics);
        return request("add_hook", p, 30_000);
    }

    public Map<String, Object> removeHook(String session, String hookId) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("session", session);
        p.put("hookId", hookId);
        return request("remove_hook", p, 15_000);
    }

    public Map<String, Object> detach(String session) {
        return request("detach", one("session", session), 15_000);
    }

    public Map<String, Object> sessions() { return request("sessions", null, 10_000); }

    // ------------------------------------------------------------ helpers

    private static Map<String, Object> one(String k, Object v) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put(k, v);
        return m;
    }

    /** The sidecar command: backend/runs/venv/bin/python + runs/bridge.py. */
    private static List<String> sidecarCommand() {
        String configured = System.getenv("WORKBENCH_RUNS_PYTHON");
        if (dev.workbench.course.Course.on() && (configured == null || configured.isBlank()))
            throw new IllegalStateException("Course Runs requires explicit WORKBENCH_RUNS_PYTHON; run bin/room-doctor.py to diagnose the pinned environment");
        if (configured != null && (!new File(configured).isAbsolute() || !new File(configured).canExecute()))
            throw new IllegalStateException("WORKBENCH_RUNS_PYTHON must be an absolute executable path");
        File py = configured == null || configured.isBlank() ? locate("runs/venv/bin/python") : new File(configured);
        File bridge = locate("runs/bridge.py");
        if (py == null || bridge == null)
            throw new IllegalStateException(
                    "frida sidecar unavailable — run backend/runs/pull-frida.sh first");
        return new ArrayList<>(Arrays.asList(py.getAbsolutePath(), bridge.getAbsolutePath()));
    }

    /** Find a backend file whether launched from the repo root or backend/. */
    static File locate(String rel) {
        String root = System.getenv("WORKBENCH_RUNS_ROOT");
        if (root != null && !root.isBlank()) {
            File f = new File(root, rel.startsWith("runs/") ? rel.substring(5) : rel);
            return f.isFile() ? f.getAbsoluteFile() : null;
        }
        for (String base : new String[] { "backend", ".", "..", System.getProperty("user.dir") }) {
            File f = new File(base, rel);
            if (f.exists()) return f.getAbsoluteFile();
        }
        return null;
    }
}
