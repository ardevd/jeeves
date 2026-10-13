package dev.workbench.course;

import dev.workbench.registry.OpException;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Filesystem confinement of a course room (WORKBENCH_COURSE=1): this process serves a student who
 * does not own the machine, so the paths a request may name are the room's own and the course's:
 *
 *   workspace   &lt;home&gt;/files — the room's private files (what the student puts there); fs.list
 *               starts here and never shows a parent above it
 *   roots       WORKBENCH_COURSE_ROOTS — the approved read-only course directories (the release's
 *               artifacts: the Day 1 target, its preanalysed seed), colon-separated; browsable, openable
 *   home        the whole sidecar home (the store of analysed databases, the capture) — openable,
 *               not browsed: the workbench's own files, reached by the ops that own them
 *
 * Every check is on the CANONICAL path (symlinks resolved, `..` folded): a link inside the workspace
 * that points outside it is outside. Off the switch nothing here applies — the owner's workbench
 * reads the owner's disk. The rules are the hosted-mode worktree's (Hosted.java, 2026-09-30),
 * kept where course mode decides them; the upload endpoint of that worktree is not part of this.
 */
public final class CourseFiles {

    private CourseFiles() {}

    private static volatile File home;

    /** Boot: the home this room serves (its canonical form); creates the workspace directory. */
    public static void init(File homeDir) throws IOException {
        home = homeDir.getCanonicalFile();
        if (!Course.on()) return;
        File ws = workspace();
        if (!ws.isDirectory() && !ws.mkdirs()) throw new IOException("course mode: cannot create the room's workspace " + ws);
    }

    public static File home() { return home; }

    /** The room's own files: &lt;home&gt;/files. */
    public static File workspace() { return new File(home, "files"); }

    /** The approved read-only course roots (existing directories only, canonical). */
    public static List<File> roots() {
        List<File> out = new ArrayList<>();
        for (String s : Course.roots()) {
            File f = new File(s);
            try { if (f.isDirectory()) out.add(f.getCanonicalFile()); } catch (IOException ignored) {}
        }
        return out;
    }

    private static boolean inside(File canonical, File root) {
        return root != null && canonical.toPath().startsWith(root.toPath());
    }

    static File canonical(File f) throws OpException {
        try { return f.getCanonicalFile(); }
        catch (IOException e) { throw OpException.io("cannot resolve " + f.getPath() + ": " + e.getMessage(), ""); }
    }

    /** The browsable places: the workspace and the course roots. */
    public static List<File> browsableRoots() throws OpException {
        List<File> out = new ArrayList<>();
        out.add(canonical(workspace()));
        out.addAll(roots());
        return out;
    }

    /** fs.list: the directory must be the workspace, a course root, or below one. Returns its canonical form. */
    public static File browsable(File dir) throws OpException {
        File c = canonical(dir);
        for (File r : browsableRoots()) if (inside(c, r)) return c;
        throw OpException.scope("outside this room's files: " + dir.getPath(),
                "a room browses its own workspace (" + workspace().getPath() + ") and the course folders" + (roots().isEmpty() ? "" : " " + roots()));
    }

    /** The root `canonicalDir` sits in, or null when it is none of them. */
    public static File rootOf(File canonicalDir) throws OpException {
        for (File r : browsableRoots()) if (inside(canonicalDir, r)) return r;
        return null;
    }

    /** True when `canonicalDir` IS one of the browsable roots (no way up from it). */
    public static boolean isRoot(File canonicalDir) throws OpException {
        for (File r : browsableRoots()) if (canonicalDir.equals(r)) return true;
        return false;
    }

    /** target.open and every path a request names to read: inside the home or a course root. No-op off the switch. */
    public static void requireOpenable(File canonicalFile) throws OpException {
        if (!Course.on() || home == null) return;
        if (inside(canonicalFile, home)) return;
        for (File r : roots()) if (inside(canonicalFile, r)) return;
        throw OpException.scope("outside this room: " + canonicalFile.getPath(),
                "a room opens the course artifact" + (roots().isEmpty() ? "" : " (" + roots() + ")") + " or a file under its own workspace " + workspace().getPath());
    }

    /** True when the canonical path is inside a browsable root — an entry whose link target escapes is not listed. */
    public static boolean listable(File entry) {
        try {
            File c = entry.getCanonicalFile();
            for (File r : browsableRoots()) if (inside(c, r)) return true;
            return false;
        } catch (Exception e) { return false; }
    }
}
