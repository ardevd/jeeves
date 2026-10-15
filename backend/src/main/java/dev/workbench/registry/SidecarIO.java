package dev.workbench.registry;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.time.Instant;

/** Sidecar file discipline, one home (fix-1 review F1–F5): every *.json
    sidecar in the tree writes atomic tmp+ATOMIC_MOVE and fails LOUD — a silent
    failure looks identical to "nothing to save", and a torn read silently
    becomes an empty state that the next write then makes permanent.
    File contents are forced before replacement, and the containing directory is
    forced afterward. Failure is reported even when the replacement already happened. */
public final class SidecarIO {

    private SidecarIO() {}

    /** Persist-discipline issue counters, surfaced as sidecarIssues on
        workspace.state (fix-1-r5 #4): a skip-write or a failed park is otherwise
        invisible outside stderr — the user believes their work was saved.
        Process-lifetime, monotonic. Each EVENT counts exactly once (fix-1-r6
        LOW-2: a guard-path park failure increments parkFailures only — it must
        not also bump a skip counter, or the surface reads 2×). */
    public static final java.util.concurrent.atomic.AtomicLong writeFailures =
            new java.util.concurrent.atomic.AtomicLong();
    public static final java.util.concurrent.atomic.AtomicLong parkFailures =
            new java.util.concurrent.atomic.AtomicLong();
    public static final java.util.concurrent.atomic.AtomicLong loadFailures =
            new java.util.concurrent.atomic.AtomicLong();

    /** Atomic write: unique tmp sibling + ATOMIC_MOVE, loud stderr on failure.
        Returns whether the content landed. The tmp file is always cleaned up
        (a no-op delete after a successful move). The tmp name is UNIQUE per
        writer (fix-1-r3 #3): the old fixed "<name>.tmp" let two writers on one
        dst interleave — one's move could land the other's half-written bytes,
        and one's finally-delete could unlink the other's in-flight tmp.
        createTempFile also makes the tmp 0600 from birth, so a restrictive-
        perms destination (llm-keys.json) never has a world-readable window. */
    public static boolean writeAtomic(File dst, String content, String what) {
        File tmp = null;
        try {
            dst.getParentFile().mkdirs();
            tmp = Files.createTempFile(dst.getParentFile().toPath(),
                    dst.getName(), ".tmp").toFile();
            Files.writeString(tmp.toPath(), content);
            forceFile(tmp.toPath());
            Files.move(tmp.toPath(), dst.toPath(),
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            forceDirectory(dst.toPath().toAbsolutePath().getParent());
            return true;
        } catch (Exception e) {
            writeFailures.incrementAndGet();
            System.err.println(what + " persist failed: " + dst + " — " + e);
            return false;
        } finally {
            if (tmp != null) tmp.delete();
        }
    }

    /** Flush completed file contents before publishing a durable replacement. */
    public static void forceFile(java.nio.file.Path path) throws java.io.IOException {
        try (var channel = java.nio.channels.FileChannel.open(path, java.nio.file.StandardOpenOption.WRITE)) {
            channel.force(true);
        }
    }

    /** Flush the directory entry too; atomic visibility alone does not guarantee durability. */
    public static void forceDirectory(java.nio.file.Path path) throws java.io.IOException {
        try (var channel = java.nio.channels.FileChannel.open(path, java.nio.file.StandardOpenOption.READ)) {
            channel.force(true);
        }
    }

    /** A transient READ failure (IO hiccup, the file mid-replace, a disappearing
        mount) is NOT corruption: log loudly and leave the file IN PLACE — the
        caller marks its store DEGRADED, and the next persist parks the healthy
        original aside as evidence before writing (never a silent overwrite).
        Parse/shape failures → parkCorrupt, not this. */
    public static void readFailed(File f, String what, Exception e) {
        System.err.println(what + " read failed (transient — left in place, will retry): "
                + f + " — " + e);
    }

    /** Route a read failure by permanence (fix-1-r2 F2). A
        {@link java.nio.charset.CharacterCodingException} is USUALLY permanent —
        the bytes on disk are not valid text — so it parks. (Window acknowledged,
        fix-1-r3 #8: a FOREIGN non-atomic writer caught mid-truncate raises the
        same exception on a file that would have been healthy a millisecond
        later; every in-process writer goes through writeAtomic, so this needs
        an outside actor.) Any other IOException is transient → {@link #readFailed}.
        Returns TRUE when the file was parked — callers then do NOT set their
        degraded flag (the evidence is already aside; the next write just
        creates a fresh file). */
    public static boolean readFailedOrPark(File f, String what, java.io.IOException e) {
        if (e instanceof java.nio.charset.CharacterCodingException) {
            /* a FAILED park must report un-parked (fix-1-r5 #2): returning true
               here regardless let a torn file take the next write un-parked */
            return parkCorrupt(f, what, e);
        }
        readFailed(f, what, e);
        return false;
    }

    /** Persisting over a sidecar whose LOAD failed transiently: the on-disk
        content may be healthy rows the in-memory state never saw — park it as
        evidence before the write, or the new state silently overwrites data the
        user never knew was lost (fix-1-r3 #1/#2). The ".corrupt-" suffix is a
        slight misnomer here (the file may be healthy); the stderr line says
        exactly why it was parked. Returns TRUE when there is nothing healthy
        left unprotected (file gone, or parked) — on FALSE the caller MUST NOT
        write (and must keep its degraded flag): overwriting un-parked evidence
        is exactly the failure this guard exists to close (fix-1-r4 #5). */
    public static boolean parkHealthyBeforeOverwrite(File f, String what) {
        if (!f.isFile()) return true;                          // nothing to preserve
        /* parkCorrupt counts its own failure (parkFailures) — no second counter
           here, or one event reads as two issues (fix-1-r6 LOW-2) */
        return parkCorrupt(f, what, new IllegalStateException(
                "persist over a sidecar whose load failed transiently — the original is parked, not silently overwritten"));
    }

    /** An unreadable/parse-broken sidecar is EVIDENCE, not empty state: park it
        as {@code <name>.corrupt-<ts>} so the next write doesn't overwrite it,
        and say so loudly. Callers then degrade to their empty/default path.
        A same-tick re-park gets a -N suffix instead of overwriting the earlier
        copy (fix-1-r3 #10: the point of parking is that the evidence survives);
        the suffix is RESERVED with createFile before the move (fix-1-r4 #12:
        bare rename(2) silently overwrites an existing target).
        Returns whether the file is parked (false = it is still in place). */
    public static boolean parkCorrupt(File f, String what, Exception e) {
        System.err.println(what + " unreadable, parking aside: " + f + " — " + e);
        String ts = Instant.now().toString().replace(':', '-');
        for (int i = 0; ; i++) {
            File dst = new File(f.getParentFile(),
                    f.getName() + ".corrupt-" + ts + (i == 0 ? "" : "-" + i));
            try {
                Files.createFile(dst.toPath());                // atomic name reservation
                try {
                    Files.move(f.toPath(), dst.toPath(), StandardCopyOption.REPLACE_EXISTING);
                    return true;
                } catch (Exception moveErr) {
                    dst.delete();                              // release the reservation
                    throw moveErr;
                }
            } catch (java.nio.file.FileAlreadyExistsException exists) {
                /* retry with the next suffix */
            } catch (Exception moveErr) {
                parkFailures.incrementAndGet();
                System.err.println(what + " corrupt-file park failed: " + f + " — " + moveErr);
                return false;
            }
        }
    }
}
