import com.pnfsoftware.jeb.core.ICoreContext;
import com.pnfsoftware.jeb.core.JebCoreService;
import java.io.File;

/**
 * Boot smoke test: starts the JEB core of an install in-process, the way the workbench does,
 * prints its version, and exits — the first check on a new machine or a new JEB version,
 * before any workbench code is involved. It passes when the install's own licence lets the
 * core start; it reads and changes nothing in the install. Run via:
 *
 *   javac -cp "$JEB_HOME/bin/app/*" -d backend/build/jebsmoke backend/tools/JebSmoke.java
 *   java -Djava.security.manager=allow --add-opens java.base/java.lang=ALL-UNNAMED \
 *        -cp "backend/build/jebsmoke:$JEB_HOME/bin/app/*" JebSmoke "$JEB_HOME"
 */
public final class JebSmoke {
    public static void main(String[] args) throws Exception {
        String env = System.getenv("JEB_HOME");
        File jebHome = new File(args.length > 0 ? args[0] : env != null && !env.isBlank() ? env : "backend/engine");
        long t0 = System.nanoTime();
        ICoreContext core = JebCoreService.getInstance(jebHome);
        long ms = (System.nanoTime() - t0) / 1_000_000;
        System.out.println("JEB core version: " + core.getVersion());
        System.out.println("OK boot smoke (" + ms + " ms, jebHome=" + jebHome.getAbsolutePath() + ")");
        core.close();
    }
}
