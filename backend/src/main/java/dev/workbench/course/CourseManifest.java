package dev.workbench.course;

import dev.workbench.registry.Json;
import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The course's target manifest — which bytes the class works on — and its resolution against an
 * opened artifact. The server has no manifest contract yet (its materials releases carry
 * {path, sha256, size} per file); this is the shape the Jeeves side reads, proposed to the server
 * as a materials file (design/COURSE_HANDOFF.md §3):
 *
 *   {"schema": "day1-target-manifest-v1", "target_id": "wa-2.26.39.71", "label": "WhatsApp 2.26.39.71",
 *    "release_id": "…", "artifacts": [{"name": "base.apk", "sha256": "…", "size": 128452139}, …],
 *    "notes": "split apks: every installed artifact listed"}
 *
 * Resolution: an open target MATCHES when its artifact's sha256 is one of the manifest's; a target
 * whose bytes are not in the manifest is a MISMATCH (a comparison run is refused with the reason);
 * no manifest = UNKNOWN, said as such. Digests are cached per path + mtime + size.
 */
public final class CourseManifest {

    public record Artifact(String name, String sha256, long size) {}

    private final File file;
    private volatile Map<String, Object> raw = Map.of();
    private volatile List<Artifact> artifacts = List.of();
    private volatile String error;
    private volatile long loadedMtime = -1;
    private final Map<String, String> digests = new ConcurrentHashMap<>();   // path|mtime|size → sha256

    public CourseManifest(File file) { this.file = file; reload(); }

    public File file() { return file; }
    public boolean present() { return file != null && error == null && !artifacts.isEmpty(); }
    public String error() { return error; }
    public String targetId() { return str(raw.get("target_id")); }
    public String label() { return str(raw.get("label")); }
    public List<Artifact> artifacts() { return artifacts; }

    /** Re-read when the file changed (the operator may publish a corrected manifest during the day). */
    public synchronized void reload() {
        if (file == null) { error = "no manifest configured (WORKBENCH_COURSE_MANIFEST)"; return; }
        if (!file.isFile()) { error = "manifest file missing: " + file.getPath(); artifacts = List.of(); return; }
        if (file.lastModified() == loadedMtime && error == null) return;
        try {
            Object parsed = Json.parse(Files.readString(file.toPath()));
            if (!(parsed instanceof Map<?, ?> m)) throw new IllegalStateException("not a JSON object");
            Map<String, Object> r = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : m.entrySet()) r.put(String.valueOf(e.getKey()), e.getValue());
            if (!Course.MANIFEST_SCHEMA.equals(r.get("schema"))) throw new IllegalStateException("schema is " + r.get("schema") + ", expected " + Course.MANIFEST_SCHEMA);
            List<Artifact> arts = new ArrayList<>();
            if (r.get("artifacts") instanceof List<?> l)
                for (Object o : l) if (o instanceof Map<?, ?> a && a.get("sha256") != null)
                    arts.add(new Artifact(str(a.get("name")), String.valueOf(a.get("sha256")).toLowerCase(), a.get("size") instanceof Number n ? n.longValue() : -1));
            if (arts.isEmpty()) throw new IllegalStateException("no artifacts[] with sha256");
            raw = r; artifacts = arts; error = null; loadedMtime = file.lastModified();
        } catch (Exception e) {
            error = "manifest unreadable: " + e.getMessage();
            artifacts = List.of();
        }
    }

    /** sha256 of a file, cached on its path, mtime and size. Null when it cannot be read. */
    public String digest(File f) {
        if (f == null || !f.isFile()) return null;
        String key = f.getAbsolutePath() + "|" + f.lastModified() + "|" + f.length();
        return digests.computeIfAbsent(key, k -> dev.workbench.device.DeviceManager.localSha256(f));
    }

    /** The manifest's artifacts as rows {name, sha256, size} (empty without a manifest). */
    public List<Map<String, Object>> artifactRows() {
        reload();
        List<Map<String, Object>> out = new ArrayList<>();
        if (!present()) return out;
        for (Artifact a : artifacts) { Map<String, Object> m = new LinkedHashMap<>(); m.put("name", a.name()); m.put("sha256", a.sha256()); m.put("size", a.size()); out.add(m); }
        return out;
    }

    /** resolve() from a digest the identity record already measured — the EXECUTABLE's (the file the target was
     *  opened from, or the input the database holds), never the file beside a database (fix-review 2026-10-09 H4). */
    public Map<String, Object> resolveDigest(String sha, String name) {
        reload();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("artifact", name);
        out.put("sha256", sha);
        if (!present()) { out.put("match", "unknown"); out.put("reason", error == null ? "no manifest" : error); return out; }
        out.put("manifest", Map.of("target_id", String.valueOf(targetId()), "label", String.valueOf(label()), "artifacts", artifacts.size()));
        if (sha == null) { out.put("match", "unknown"); out.put("reason", "no measured executable identity for this target"); return out; }
        for (Artifact a : artifacts)
            if (a.sha256().equalsIgnoreCase(sha)) { out.put("match", "match"); out.put("manifestArtifact", a.name()); return out; }
        out.put("match", "mismatch");
        out.put("reason", "the executable's bytes (" + sha.substring(0, 12) + "…) are not in the course manifest " + String.valueOf(targetId()) + " (" + artifacts.size() + " artifact(s))");
        return out;
    }

    /** The resolution of one artifact against the manifest: match | mismatch | unknown, with the digests. */
    public Map<String, Object> resolve(File artifact) {
        reload();
        Map<String, Object> out = new LinkedHashMap<>();
        String sha = digest(artifact);
        out.put("artifact", artifact == null ? null : artifact.getName());
        out.put("sha256", sha);
        if (!present()) { out.put("match", "unknown"); out.put("reason", error == null ? "no manifest" : error); return out; }
        out.put("manifest", Map.of("target_id", String.valueOf(targetId()), "label", String.valueOf(label()), "artifacts", artifacts.size()));
        if (sha == null) { out.put("match", "unknown"); out.put("reason", "the artifact could not be hashed"); return out; }
        for (Artifact a : artifacts)
            if (a.sha256().equalsIgnoreCase(sha)) { out.put("match", "match"); out.put("manifestArtifact", a.name()); return out; }
        out.put("match", "mismatch");
        out.put("reason", "the opened bytes (" + sha.substring(0, 12) + "…) are not in the course manifest " + String.valueOf(targetId()) + " (" + artifacts.size() + " artifact(s))");
        return out;
    }

    /** The manifest's identity — what events carry as target_manifest_sha256: the sha256 of its
     *  CANONICAL JSON (sorted keys, compact separators, UTF-8, Python's json.dumps(ensure_ascii=False)
     *  escaping), which is how the server names it (the server's release step: sha256(encode(target))) — not
     *  the digest of the file's bytes, which depend on its indentation (fix-review 2026-10-09 M4). */
    public String manifestSha256() {
        reload();
        if (file == null || !file.isFile() || raw.isEmpty()) return null;
        try {
            StringBuilder sb = new StringBuilder();
            canonical(raw, sb);
            return dev.workbench.course.Capture.sha256(sb.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (Exception e) { return digest(file); }
    }

    /** Canonical JSON as Python writes it with sort_keys=True, separators=(",", ":"), ensure_ascii=False. */
    static void canonical(Object v, StringBuilder sb) {
        if (v == null) sb.append("null");
        else if (v instanceof Boolean b) sb.append(b ? "true" : "false");
        else if (v instanceof Number n) {
            if (n instanceof Double || n instanceof Float) {
                double d = n.doubleValue();
                if (d == Math.rint(d) && !Double.isInfinite(d) && Math.abs(d) < 1e15) sb.append((long) d).append(".0");
                else sb.append(Double.toString(d));
            } else sb.append(n.toString());
        }
        else if (v instanceof CharSequence s) {
            sb.append('"');
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                switch (c) {
                    case '"' -> sb.append("\\\"");
                    case '\\' -> sb.append("\\\\");
                    case '\n' -> sb.append("\\n");
                    case '\r' -> sb.append("\\r");
                    case '\t' -> sb.append("\\t");
                    case '\b' -> sb.append("\\b");
                    case '\f' -> sb.append("\\f");
                    default -> { if (c < 0x20) sb.append(String.format("\\u%04x", (int) c)); else sb.append(c); }
                }
            }
            sb.append('"');
        }
        else if (v instanceof Map<?, ?> m) {
            java.util.TreeMap<String, Object> sorted = new java.util.TreeMap<>();
            for (Map.Entry<?, ?> e : m.entrySet()) sorted.put(String.valueOf(e.getKey()), e.getValue());
            sb.append('{');
            boolean first = true;
            for (Map.Entry<String, Object> e : sorted.entrySet()) {
                if (!first) sb.append(',');
                first = false;
                canonical(e.getKey(), sb); sb.append(':'); canonical(e.getValue(), sb);
            }
            sb.append('}');
        }
        else if (v instanceof List<?> l) {
            sb.append('[');
            for (int i = 0; i < l.size(); i++) { if (i > 0) sb.append(','); canonical(l.get(i), sb); }
            sb.append(']');
        }
        else canonical(String.valueOf(v), sb);
    }

    private static String str(Object o) { return o == null ? null : String.valueOf(o); }
}
