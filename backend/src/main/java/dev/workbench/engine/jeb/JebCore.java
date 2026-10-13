package dev.workbench.engine.jeb;

import com.pnfsoftware.jeb.core.ICoreContext;
import com.pnfsoftware.jeb.core.IEnginesContext;
import com.pnfsoftware.jeb.core.JebCoreService;
import dev.workbench.registry.OpException;
import java.io.File;

/**
 * The shared JEB runtime (WORKBENCH_SPEC §2.5): one {@link JebCoreService} core and one
 * {@link IEnginesContext} per JVM, with every open target living as an
 * {@code IRuntimeProject} inside it (the old Jeeves Workspace pattern — concurrent
 * projects keyed uniquely per target).
 *
 * Engine home resolution order: -Dworkbench.jebHome, $JEB_HOME, ~/jeb. workbench.sh and
 * the gradle run tasks pass the selected install in through the system property.
 */
public final class JebCore {

    private static ICoreContext core;
    private static IEnginesContext engines;
    private static boolean borrowedFromClient;

    private JebCore() {}

    public static File jebHome() {
        String p = System.getProperty("workbench.jebHome");
        if (p != null && !p.isBlank()) return new File(p);
        p = System.getenv("JEB_HOME");
        if (p != null && !p.isBlank()) return new File(p);
        return new File(System.getProperty("user.home"), "jeb");
    }

    /** Adopt the context owned by JEB's official local client, without creating another one. */
    public static synchronized void useOfficialClient(ICoreContext clientCore, IEnginesContext clientEngines) {
        if (core != null || engines != null) throw new IllegalStateException("JEB runtime already initialized");
        java.util.Objects.requireNonNull(clientCore, "Official JEB core");
        java.util.Objects.requireNonNull(clientEngines, "Official JEB engines context");
        core = clientCore;
        engines = clientEngines;
        borrowedFromClient = true;
        versionCache = null;
    }

    /** Lazily boot the core (first target.open pays the ~0.5s license/init cost). */
    public static synchronized IEnginesContext engines() throws OpException {
        if (engines != null) return engines;
        File home = jebHome();
        if (!new File(home, "bin/app/jeb.jar").isFile())
            throw OpException.unsupported("no JEB engine at " + home,
                    "set JEB_HOME (or -Dworkbench.jebHome) to an activated JEB install");
        try {
            core = JebCoreService.getInstance(home);
            engines = core.createEnginesContext();
            return engines;
        } catch (Throwable t) {
            throw OpException.unsupported("JEB core init failed: " + t,
                    "jebHome=" + home + " — check the selected installation and launch through backend/workbench.sh; see docs/local-jeb.md");
        }
    }

    /* the version string is immutable for the life of the core — caching it
       keeps info() (called per target per open, under the Workspace monitor)
       off this class monitor, which the one-time ~0.5s boot inside engines()
       holds: a first-open during a snapshot loop pinned the Workspace monitor
       behind the boot (fix-8-r2 F5) */
    private static volatile String versionCache;

    public static String version() {
        String v = versionCache;
        if (v != null) return v;
        synchronized (JebCore.class) {
            if (core == null) return null;
            versionCache = String.valueOf(core.getVersion());
            return versionCache;
        }
    }

    /** Tear down the whole JEB runtime (server shutdown hook). */
    public static synchronized void shutdown() {
        if (core != null) {
            // The official client owns its core and closes it after the workbench has saved.
            if (!borrowedFromClient) {
                try { core.close(); } catch (Throwable ignored) {}
            }
            borrowedFromClient = false;
            core = null;
            engines = null;
            versionCache = null;
        }
    }
}
