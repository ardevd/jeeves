package dev.workbench.engine;

import dev.workbench.registry.OpException;
import java.io.File;

/**
 * Engine factory. The JEB adapter (dev.workbench.engine.jeb.JebEngine) is compiled and
 * loaded reflectively so the backend still builds and runs mock-only on machines without
 * a JEB install (JEB_HOME, or a copy under backend/engine): when the JEB jars are
 * absent, gradle excludes the jeb package from compilation and target.open fails clean
 * with E_UNSUPPORTED instead of a classloader error.
 */
public final class Engines {

    private Engines() {}

    public static final String JEB_CLASS = "dev.workbench.engine.jeb.JebEngine";

    public static boolean jebAvailable() {
        try {
            Class.forName(JEB_CLASS);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** Open a JEB engine session for an artifact (or a bare .jdb2 when artifact == null). */
    public static Engine openJeb(String id, String name, String path, File artifact, File jdb2)
            throws OpException {
        try {
            Class<?> c = Class.forName(JEB_CLASS);
            return (Engine) c.getConstructor(String.class, String.class, String.class,
                    File.class, File.class).newInstance(id, name, path, artifact, jdb2);
        } catch (ClassNotFoundException e) {
            throw OpException.unsupported(
                    "JEB engine not on classpath — set JEB_HOME to an activated JEB install and rebuild "
                            + "(or run via backend/workbench.sh)",
                    "class=" + JEB_CLASS);
        } catch (ReflectiveOperationException e) {
            Throwable cause = e instanceof java.lang.reflect.InvocationTargetException && e.getCause() != null
                    ? e.getCause() : e;
            if (cause instanceof OpException oe) throw oe;
            throw OpException.unsupported("JEB engine init failed: " + cause, "target=" + id);
        }
    }

    /** target.open's check before a target is minted: would loading this saved database take an
     *  open target's place in the JEB context (same internal project key)? Answers {kind, key or
     *  "", reason, the ids of the targets it is about…}, or null when the load may go ahead. Takes no lock and makes no
     *  JEB call. `bare`: the database is opened by itself; `asked`: the file the caller named. */
    public static String[] preflightJeb(File jdb2, boolean bare, String asked) throws OpException {
        if (jdb2 == null || !jdb2.isFile()) return null;
        try {
            return (String[]) Class.forName(JEB_CLASS).getMethod("preflight", File.class, Boolean.class, String.class).invoke(null, jdb2, bare, asked);
        } catch (ClassNotFoundException e) {
            return null;   // mock-only build: target.open fails on its own, with its own message
        } catch (ReflectiveOperationException e) {
            Throwable cause = e instanceof java.lang.reflect.InvocationTargetException && e.getCause() != null ? e.getCause() : e;
            if (cause instanceof OpException oe) throw oe;
            throw OpException.unsupported("JEB preflight failed: " + cause, "jdb2=" + jdb2);
        }
    }

    /** The marker backend/jdb2-rekey.sh leaves beside a database it set aside: analyse again, then
     *  re-apply the whole edit history (Workspace.dirtyReplayFor, JebEngine.firstSaveAfterReplay). */
    public static File replayMarker(File jdb2) { return new File(jdb2.getPath() + ".replay"); }

    /** Start background analysis on a session returned by {@link #openJeb}. */
    public static void startAnalysis(Engine e) {
        try {
            e.getClass().getMethod("openAsync").invoke(e);
        } catch (ReflectiveOperationException ignored) {}
    }
}
