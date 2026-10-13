package dev.workbench.engine.jeb;

import com.pnfsoftware.jeb.client.HeadlessClientContext;
import com.pnfsoftware.jeb.client.JebContextUtil;
import com.pnfsoftware.jeb.client.Licensing;
import dev.workbench.server.WorkbenchServer;
import java.io.IOException;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

/** Local workbench hosted by the installed JEB client's permitted core API. */
public final class LocalJebLauncher {
    private LocalJebLauncher() {}

    public static void main(String[] args) throws Exception {
        // Keep the existing standalone-client path intact for entitled installations.
        if (Licensing.allowAnyClient()) {
            WorkbenchServer.main(args);
            return;
        }
        checkInstallation();
        if (!Licensing.canUseCoreAPI()) {
            throw new IllegalStateException("This JEB build does not permit core API use");
        }
        String mode = System.getenv().getOrDefault("WORKBENCH_COURSE", "0").trim().toLowerCase(Locale.ROOT);
        if (!Set.of("", "0", "false", "no", "off").contains(mode)) {
            throw new IllegalStateException("Official-client hosting is for local Jeeves, not hosted course rooms");
        }

        // Use the public official-client implementation, including its ordinary
        // license checks; never fabricate a client signature or alter license flags.
        HeadlessClientContext client = new HeadlessClientContext() {
            @Override public String retrieveLicenseKey(String information) {
                throw new IllegalStateException("Activate the selected JEB installation in its normal client before starting Jeeves");
            }
            @Override public boolean displayEula(String eula) {
                throw new IllegalStateException("Open the selected JEB installation and accept its terms before starting Jeeves");
            }
        };
        WorkbenchServer server = null;
        Thread hook = null;
        AtomicBoolean closed = new AtomicBoolean();
        CountDownLatch stopped = new CountDownLatch(1);
        Throwable failure = null;
        try {
            client.initialize(new String[0]);
            client.start();
            JebCore.useOfficialClient(client.getCoreContext(), client.getEnginesContext());
            System.err.println("[workbench] Using the installed JEB official-client context (core API enabled)");
            Path webRoot = args.length > 0 ? Path.of(args[0]) : Path.of("web").toAbsolutePath();
            server = new WorkbenchServer(webRoot);
            WorkbenchServer ownedServer = server;
            hook = new Thread(() -> {
                if (closed.compareAndSet(false, true)) {
                    try { ownedServer.stop(); }
                    finally {
                        stopClient(client, stopped);
                    }
                }
            }, "wb-official-client-shutdown");
            Runtime.getRuntime().addShutdownHook(hook);
            server.start();
            stopped.await(); // The foreground server lives until its shutdown hook runs.
        } catch (Exception | Error original) {
            failure = original;
            throw original;
        } finally {
            try {
                if (closed.compareAndSet(false, true)) {
                    try { if (server != null) server.stop(); }
                    finally { stopClient(client, stopped); }
                }
            } catch (RuntimeException | Error cleanup) {
                if (failure != null) failure.addSuppressed(cleanup);
                else throw cleanup;
            } finally {
                if (hook != null) {
                    try { Runtime.getRuntime().removeShutdownHook(hook); }
                    catch (IllegalStateException ignored) { /* JVM is already shutting down. */ }
                }
            }
        }
    }

    private static void checkInstallation() {
        String selectedHome = JebCore.jebHome().toString();
        try {
            Path selected = Path.of(selectedHome).toRealPath();
            String programDirectory = JebContextUtil.getProgramDirectory();
            if (programDirectory == null || programDirectory.isBlank()) {
                throw new IllegalStateException("Cannot locate the loaded JEB installation; start with backend/workbench.sh (JEB_HOME=/absolute/JEB)");
            }
            Path loaded = Path.of(programDirectory).toRealPath();
            if (!loaded.equals(selected.resolve("bin").toRealPath())) {
                throw new IllegalStateException("Loaded JEB jars do not match " + selectedHome
                        + "; use one installation for JEB_HOME and the Java classpath");
            }
        } catch (IOException | InvalidPathException missing) {
            throw new IllegalStateException("Cannot find the selected JEB installation at " + selectedHome
                    + "; set JEB_HOME to your installed, activated JEB directory", missing);
        }
    }

    private static void stopClient(HeadlessClientContext client, CountDownLatch stopped) {
        try { JebCore.shutdown(); }
        finally {
            try { client.stop(); }
            finally { stopped.countDown(); }
        }
    }
}
