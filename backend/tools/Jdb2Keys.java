import com.pnfsoftware.jeb.core.ICoreContext;
import com.pnfsoftware.jeb.core.IEnginesContext;
import com.pnfsoftware.jeb.core.IRuntimeProject;
import com.pnfsoftware.jeb.core.JebCoreService;
import com.pnfsoftware.jeb.core.RuntimeProjectUtil;
import com.pnfsoftware.jeb.core.units.code.android.IDexUnit;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

/**
 * Reads the internal JEB project key of ONE database and writes it beside the file
 * (name.jdb2.key) — run through backend/jdb2-keys.sh, which starts one of these per database.
 * JEB identifies a project by the key it was created under; the key is inside the .jdb2 and can
 * only be read by loading the file, and two databases with the same key cannot be open in one
 * workbench together (JebEngine, "PROJECT KEYS"). A process per database: nothing else is loaded
 * here, so nothing can be displaced, and one bad file cannot spoil the reading of the next.
 *
 * It loads, reads, unloads and writes the .key file. It never saves a database.
 *   args: jebHome file.jdb2
 *   stdout: one line — key, TAB, note.   exit: 0 read · 3 locked (not attempted) · 4 failed
 */
public final class Jdb2Keys {
    public static void main(String[] args) throws Exception {
        if (args.length != 2) { System.err.println("usage: Jdb2Keys <jebHome> <file.jdb2>"); System.exit(2); }
        File f = new File(args[1]).getAbsoluteFile();
        File lock = new File(f.getPath() + ".lock");
        if (lock.exists()) {
            /* a lock beside it: a workbench has this database open — or left the file behind when
               it was killed. Not attempted either way: a second loader stays away from a session's lock. */
            System.out.println("?\tSKIPPED — locked: a workbench has it open and records the key itself (if no workbench is running, "
                    + lock.getName() + " was left by a killed one: remove it and run this again)");
            System.exit(3);
        }
        ICoreContext core = JebCoreService.getInstance(new File(args[0]));
        IEnginesContext eng = core.createEnginesContext();
        /* Ctrl-C or a kill during the load must not leave OUR lock on the database */
        Runtime.getRuntime().addShutdownHook(new Thread(() -> { try { eng.unloadProjects(true); } catch (Throwable ignored) {} }));
        try {
            long t0 = System.currentTimeMillis();
            IRuntimeProject p = eng.loadProject(f.getPath(), true, null);
            String key = p.getKey();
            int classes = 0;
            for (IDexUnit d : RuntimeProjectUtil.findUnitsByType(p, IDexUnit.class, false)) classes += d.getClasses().size();
            boolean unloaded = eng.unloadProject(key, true);
            File rec = new File(f.getPath() + ".key"), tmp = new File(rec.getPath() + ".tmp");
            Files.writeString(tmp.toPath(), key + "\n");
            Files.move(tmp.toPath(), rec.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            System.out.println(key + "\tread · " + classes + " classes · " + (System.currentTimeMillis() - t0) / 1000 + " s"
                    + (unloaded && !lock.exists() ? "" : " · WARNING: the unload did not release " + lock.getName() + " — remove it before opening this database"));
            System.out.flush();
            if (unloaded && !lock.exists()) Runtime.getRuntime().halt(0);   // read and recorded: nothing left for JEB's shutdown to do
            System.exit(0);                                                 // the lock is still there: through the hook, which unloads again
        } catch (Throwable t) {
            System.out.println("?\tSKIPPED — " + t.getClass().getSimpleName() + ": " + t.getMessage()
                    + (t instanceof OutOfMemoryError ? " (raise JDB2_KEYS_XMX)" : ""));
            System.out.flush();
            System.exit(4);   // through the hook: whatever the failed load registered is unloaded
        }
    }
}
