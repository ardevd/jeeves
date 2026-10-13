package dev.workbench.runs;

import dev.workbench.registry.Json;
import dev.workbench.registry.OpException;
import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Resolve native FUNCTION NAMES to module-base offsets via idalib (headless IDA), so a native
 * breakpoint can name a NON-EXPORTED function instead of a hand-fed offset. A native bp otherwise
 * takes an export (frida resolves it) or a raw offset (from a disassembler); this fills the gap by
 * shelling out to backend/runs/idalib_resolve.py against a LOCAL copy of the library.
 *
 * One-shot process (NOT the persistent frida-sidecar model): resolution is a deliberate, infrequent
 * action, and idalib caches its analysis in an .i64 beside the .so, so re-opens are ~0.4s. Results
 * are cached in-process keyed by (path, content-hash, name) so re-adding the same bp never re-shells.
 *
 * Config (env overrides; the defaults suit this dev machine):
 *   WORKBENCH_IDALIB_PYTHON  a python with the idapro package (default ~/.pyenv/versions/3.13.7/bin/python3)
 *   IDADIR                   the IDA runtime dir (default /Applications/IDA Professional 9.4.app/Contents/MacOS)
 */
public final class NativeSymbols {
    private NativeSymbols() {}

    private static final long TIMEOUT_SEC = 300;   // first analysis of a large .so is slow; cached opens ~0.4s
    /** (soPath|sha256|name) -> offset; "" means resolved-but-absent, so a miss is cached too. */
    private static final Map<String, String> CACHE = new ConcurrentHashMap<>();

    private static String python() {
        String env = System.getenv("WORKBENCH_IDALIB_PYTHON");
        if (env != null && !env.isBlank()) return env;
        return System.getProperty("user.home") + "/.pyenv/versions/3.13.7/bin/python3";
    }

    private static String idadir() {
        String env = System.getenv("IDADIR");
        if (env != null && !env.isBlank()) return env;
        return "/Applications/IDA Professional 9.4.app/Contents/MacOS";
    }

    private static File resolverScript() {
        for (String base : new String[] { "backend", ".", "..", System.getProperty("user.dir") }) {
            File f = new File(base, "runs/idalib_resolve.py");
            if (f.exists()) return f.getAbsoluteFile();
        }
        return null;
    }

    /**
     * Resolve each name to a module-base offset (a hex string like "0x90a9c"); a name that is not a
     * function in the library maps to null. Throws OpException when the resolver cannot run at all.
     */
    public static Map<String, String> resolve(String soPath, List<String> names) throws OpException {
        File so = new File(soPath);
        if (!so.isFile())
            throw OpException.notFound("library file not found for name resolution", soPath);
        File py = new File(python());
        File script = resolverScript();
        if (script == null || !py.isFile())
            throw OpException.unsupported(
                    "idalib resolver unavailable (needs a python with idapro + IDADIR)",
                    "python=" + python() + " script=" + (script == null ? "(missing)" : script));

        // Key the cache by CONTENT hash, not mtime: adb pull preserves the source mtime, so two builds
        // can share (basename, mtime) — an mtime key would serve one lib's offset for the other.
        String key = soPath + "|" + sha256(so);
        Map<String, String> out = new LinkedHashMap<>();
        List<String> miss = new ArrayList<>();
        for (String n : names) {
            String c = CACHE.get(key + "|" + n);
            if (c != null) out.put(n, c.isEmpty() ? null : c);
            else miss.add(n);
        }
        if (miss.isEmpty()) return out;

        File outFile = null, errFile = null;
        try {
            outFile = File.createTempFile("idalib-resolve", ".json");
            errFile = File.createTempFile("idalib-resolve", ".err");
            List<String> cmd = List.of(py.getAbsolutePath(), script.getAbsolutePath(),
                    "--so", so.getAbsolutePath(),
                    "--names=" + String.join(",", miss),                  // one token: a leading '-' name isn't a flag
                    "--out", outFile.getAbsolutePath());
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.environment().put("IDADIR", idadir());
            pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);           // idalib banners/analysis logs — we read --out
            pb.redirectError(ProcessBuilder.Redirect.to(errFile));        // keep stderr for a crash diagnostic
            Process p = pb.start();
            p.getOutputStream().close();                                  // send no stdin; close the pipe (no fd leak)
            if (!p.waitFor(TIMEOUT_SEC, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                throw OpException.busy("idalib resolution timed out after " + TIMEOUT_SEC + "s", soPath);
            }
            String body = Files.readString(outFile.toPath());
            if (body.isBlank())     // died before writing the result (bad IDADIR, no license, dyld crash…)
                throw OpException.unsupported("idalib resolver did not run — check idapro/IDADIR/license",
                        "exit=" + p.exitValue() + " stderr=" + tail(errFile, 600));
            Map<String, Object> res = Json.parseObject(body);
            if (!Boolean.TRUE.equals(res.get("ok")))
                throw OpException.io("idalib resolution failed", String.valueOf(res.get("error")));
            @SuppressWarnings("unchecked")
            Map<String, Object> results = (Map<String, Object>) res.get("results");
            for (String n : miss) {
                Object off = results == null ? null : results.get(n);
                String v = off == null ? null : String.valueOf(off);
                CACHE.put(key + "|" + n, v == null ? "" : v);
                out.put(n, v);
            }
            return out;
        } catch (OpException e) {
            throw e;
        } catch (Exception e) {
            throw OpException.io("idalib resolver error", e.toString());
        } finally {                                                       // no deleteOnExit leak
            if (outFile != null) try { Files.deleteIfExists(outFile.toPath()); } catch (Exception ignored) {}
            if (errFile != null) try { Files.deleteIfExists(errFile.toPath()); } catch (Exception ignored) {}
        }
    }

    private static String sha256(File f) {
        try (java.io.InputStream in = new java.io.FileInputStream(f)) {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[1 << 16];
            for (int n; (n = in.read(buf)) > 0; ) md.update(buf, 0, n);
            StringBuilder sb = new StringBuilder();
            for (byte b : md.digest()) { sb.append(Character.forDigit((b >> 4) & 0xf, 16)); sb.append(Character.forDigit(b & 0xf, 16)); }
            return sb.toString();
        } catch (Exception e) {
            return "mtime:" + f.lastModified();   // hashing failed — fall back rather than block resolution
        }
    }

    /** The GNU build-id (hex) of an ELF file, from its .note.gnu.build-id note (walked via PT_NOTE),
        or null if absent/unparseable. Identifies the exact binary the offsets were computed against;
        the agent reads the same value from the loaded module and refuses to bind a mismatch. */
    public static String buildId(File so) {
        try {
            byte[] d = Files.readAllBytes(so.toPath());
            if (d.length < 64 || d[0] != 0x7f || d[1] != 'E' || d[2] != 'L' || d[3] != 'F') return null;
            boolean is64 = d[4] == 2;
            java.nio.ByteBuffer bb = java.nio.ByteBuffer.wrap(d)
                    .order(d[5] == 1 ? java.nio.ByteOrder.LITTLE_ENDIAN : java.nio.ByteOrder.BIG_ENDIAN);
            long e_phoff  = is64 ? bb.getLong(0x20) : (bb.getInt(0x1c) & 0xffffffffL);
            int e_phentsz = is64 ? (bb.getShort(0x36) & 0xffff) : (bb.getShort(0x2a) & 0xffff);
            int e_phnum   = is64 ? (bb.getShort(0x38) & 0xffff) : (bb.getShort(0x2c) & 0xffff);
            for (int i = 0; i < e_phnum; i++) {
                int p = (int) (e_phoff + (long) i * e_phentsz);
                if (bb.getInt(p) != 4) continue;                     // PT_NOTE == 4
                long p_offset = is64 ? bb.getLong(p + 0x08) : (bb.getInt(p + 0x04) & 0xffffffffL);
                long p_filesz = is64 ? bb.getLong(p + 0x20) : (bb.getInt(p + 0x10) & 0xffffffffL);
                int cur = (int) p_offset, end = (int) (p_offset + p_filesz);
                while (cur + 12 <= end && cur + 12 <= d.length) {
                    int namesz = bb.getInt(cur), descsz = bb.getInt(cur + 4), ntype = bb.getInt(cur + 8);
                    if (namesz < 0 || descsz < 0 || namesz > 0xffff || descsz > 0xffff) break;   // malformed guard
                    int nameEnd = 12 + ((namesz + 3) & ~3);
                    if (ntype == 3 && namesz >= 3 && "GNU".equals(new String(d, cur + 12, 3))) {  // NT_GNU_BUILD_ID
                        if (descsz == 0) return null;   // degenerate note ⇒ no build-id (unverified), not ""
                        StringBuilder sb = new StringBuilder();
                        for (int k = 0; k < descsz && cur + nameEnd + k < d.length; k++) {
                            byte b = d[cur + nameEnd + k];
                            sb.append(Character.forDigit((b >> 4) & 0xf, 16)).append(Character.forDigit(b & 0xf, 16));
                        }
                        return sb.toString();
                    }
                    cur += nameEnd + ((descsz + 3) & ~3);
                }
            }
        } catch (Exception e) { /* unreadable → null (no check enforced) */ }
        return null;
    }

    private static String tail(File f, int max) {
        try {
            String s = Files.readString(f.toPath()).strip();
            return s.length() > max ? s.substring(s.length() - max) : s;
        } catch (Exception e) { return "(no stderr)"; }
    }
}
