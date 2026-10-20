package dev.workbench.engine.jadx;

import dev.workbench.registry.Json;
import dev.workbench.registry.OpException;
import java.io.*;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.*;
import jadx.api.JadxArgs;
import jadx.api.JadxDecompiler;
import jadx.api.data.*;
import jadx.api.data.impl.*;
import jadx.api.impl.AnnotatedCodeWriter;
import jadx.api.impl.InMemoryCodeCache;
import jadx.core.dex.attributes.AType;
import jadx.core.dex.nodes.*;

/** One independently owned JADX instance. All model access uses this session's monitor. */
final class JadxSession implements AutoCloseable {
    private final File artifact;
    private final File codeDataFile;
    private final Runnable onReady;
    private final JadxCodeData codeData = new JadxCodeData();
    private JadxDecompiler decompiler;
    private JadxSymbols symbols;
    private Map<String, Object> identity;
    private volatile boolean ready;
    private volatile String state = "idle";
    private volatile String error;
    private volatile int classCount;
    private volatile boolean closing;

    static {
        try {
            org.slf4j.Logger root = org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
            if (root instanceof ch.qos.logback.classic.Logger logbackRoot) {
                logbackRoot.setLevel(ch.qos.logback.classic.Level.OFF);
            }
        } catch (Throwable ignored) {}
    }

    JadxSession(File artifact) {
        this(artifact, null, null);
    }

    JadxSession(File artifact, Runnable onReady) {
        this(artifact, null, onReady);
    }

    JadxSession(File artifact, File codeDataFile, Runnable onReady) {
        this.artifact = Objects.requireNonNull(artifact);
        this.codeDataFile = codeDataFile;
        this.onReady = onReady;
        this.codeData.setRenames(new ArrayList<>());
        this.codeData.setComments(new ArrayList<>());
    }

    File codeDataFile() { return codeDataFile; }
    JadxCodeData codeData() { return codeData; }

    void openAsync() {
        synchronized (this) {
            if (!state.equals("idle") || closing) return;
            state = "loading";
        }
        Thread worker = new Thread(this::load, "jadx-open-" + artifact.getName());
        worker.setDaemon(true);
        worker.start();
    }

    private void load() {
        JadxDecompiler opened = null;
        try {
            synchronized (this) {
                if (closing) { state = "closed"; return; }
                String before = digest(artifact);
                if (codeDataFile != null && dev.workbench.engine.CheckpointedFileSave.marker(codeDataFile.toPath()).toFile().exists()) {
                    throw new IOException("An interrupted sidecar save has a recovery receipt: "
                            + dev.workbench.engine.CheckpointedFileSave.marker(codeDataFile.toPath()));
                }
                if (codeDataFile != null && codeDataFile.isFile()) {
                    loadCodeDataFromDisk(codeDataFile, codeData);
                }
                JadxArgs args = new JadxArgs();
                args.setInputFiles(List.of(artifact));
                args.setThreadsCount(Math.max(1, Math.min(4, Runtime.getRuntime().availableProcessors())));
                args.setRenameFlags(Set.of());
                args.setCodeData(codeData);
                args.setCodeCache(new InMemoryCodeCache());
                args.setCodeWriterProvider(AnnotatedCodeWriter::new);
                opened = new JadxDecompiler(args);
                opened.load();
                if (!before.equals(digest(artifact))) throw new IOException("Input changed during analysis; reopen a stable copy");
                if (closing) { opened.close(); state = "closed"; return; }
                JadxSymbols indexed = new JadxSymbols(opened.getRoot());
                if (indexed.classes.isEmpty()) throw new IOException("Input contains no DEX classes");
                identity = Map.of("name", artifact.getName(), "bytes", Files.size(artifact.toPath()),
                        "sha256", before, "inputClass", "File", "source", "input hashed before and after JADX load");
                decompiler = opened;
                symbols = indexed;
                classCount = indexed.classes.size();
                ready = true;
            }
            if (onReady != null) onReady.run();
            synchronized (this) {
                if (closing) {
                    ready = false;
                    if (decompiler != null) decompiler.close();
                    decompiler = null; symbols = null; state = "closed";
                    return;
                }
                state = "ready";
            }
        } catch (Exception failure) {
            synchronized (this) {
                ready = false;
                if (opened != null) try { opened.close(); } catch (Exception cleanup) { failure.addSuppressed(cleanup); }
                decompiler = null; symbols = null;
                error = failure.toString();
                state = closing ? "closed" : "error";
            }
        }
    }

    static void loadCodeDataFromDisk(File file, JadxCodeData target) throws IOException {
        String raw = Files.readString(file.toPath());
        if (raw == null || raw.isBlank()) return;
        Map<String, Object> root = Json.parseObject(raw);
        List<ICodeRename> renames = new ArrayList<>();
        if (root.get("renames") instanceof List<?> rl) {
            for (Object item : rl) {
                if (!(item instanceof Map<?, ?> rm)) continue;
                JadxNodeRef nodeRef = parseNodeRef(rm.get("nodeRef"));
                if (nodeRef == null || rm.get("newName") == null) continue;
                JadxCodeRef codeRef = parseCodeRef(rm.get("codeRef"));
                renames.add(new JadxCodeRename(nodeRef, codeRef, String.valueOf(rm.get("newName"))));
            }
        }
        List<ICodeComment> comments = new ArrayList<>();
        if (root.get("comments") instanceof List<?> cl) {
            for (Object item : cl) {
                if (!(item instanceof Map<?, ?> cm)) continue;
                JadxNodeRef nodeRef = parseNodeRef(cm.get("nodeRef"));
                if (nodeRef == null || cm.get("comment") == null) continue;
                JadxCodeRef codeRef = parseCodeRef(cm.get("codeRef"));
                CommentStyle style = CommentStyle.LINE;
                if (cm.get("style") != null) {
                    try { style = CommentStyle.valueOf(String.valueOf(cm.get("style"))); }
                    catch (IllegalArgumentException ignored) {}
                }
                comments.add(new JadxCodeComment(nodeRef, codeRef, String.valueOf(cm.get("comment")), style));
            }
        }
        target.setRenames(renames);
        target.setComments(comments);
    }

    private static JadxNodeRef parseNodeRef(Object obj) {
        if (!(obj instanceof Map<?, ?> m) || m.get("refType") == null || m.get("declClass") == null) return null;
        try {
            IJavaNodeRef.RefType type = IJavaNodeRef.RefType.valueOf(String.valueOf(m.get("refType")));
            String decl = String.valueOf(m.get("declClass"));
            String shortId = m.get("shortId") == null ? null : String.valueOf(m.get("shortId"));
            return new JadxNodeRef(type, decl, shortId);
        } catch (IllegalArgumentException e) { return null; }
    }

    private static JadxCodeRef parseCodeRef(Object obj) {
        if (!(obj instanceof Map<?, ?> m) || m.get("attachType") == null || !(m.get("index") instanceof Number n)) return null;
        try {
            CodeRefType type = CodeRefType.valueOf(String.valueOf(m.get("attachType")));
            return new JadxCodeRef(type, n.intValue());
        } catch (IllegalArgumentException e) { return null; }
    }

    synchronized String serializeCodeData() {
        List<Object> renames = new ArrayList<>();
        for (ICodeRename r : codeData.getRenames()) {
            Map<String, Object> rm = new LinkedHashMap<>();
            rm.put("nodeRef", serializeNodeRef(r.getNodeRef()));
            if (r.getCodeRef() != null) rm.put("codeRef", serializeCodeRef(r.getCodeRef()));
            rm.put("newName", r.getNewName());
            renames.add(rm);
        }
        List<Object> comments = new ArrayList<>();
        for (ICodeComment c : codeData.getComments()) {
            Map<String, Object> cm = new LinkedHashMap<>();
            cm.put("nodeRef", serializeNodeRef(c.getNodeRef()));
            if (c.getCodeRef() != null) cm.put("codeRef", serializeCodeRef(c.getCodeRef()));
            cm.put("comment", c.getComment());
            cm.put("style", (c.getStyle() == null ? CommentStyle.LINE : c.getStyle()).name());
            comments.add(cm);
        }
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("renames", renames);
        root.put("comments", comments);
        return Json.write(root);
    }

    private static Map<String, Object> serializeNodeRef(IJavaNodeRef ref) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("refType", ref.getType().name());
        m.put("declClass", ref.getDeclaringClass());
        if (ref.getShortId() != null) m.put("shortId", ref.getShortId());
        return m;
    }

    private static Map<String, Object> serializeCodeRef(IJavaCodeRef ref) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("attachType", ref.getAttachType().name());
        m.put("index", ref.getIndex());
        return m;
    }

    synchronized void setRenameEntry(IJavaNodeRef nodeRef, IJavaCodeRef codeRef, String newName) {
        List<ICodeRename> list = new ArrayList<>(codeData.getRenames());
        list.removeIf(r -> Objects.equals(r.getNodeRef(), nodeRef) && Objects.equals(r.getCodeRef(), codeRef));
        if (newName != null && !newName.isEmpty()) {
            list.add(new JadxCodeRename(nodeRef, codeRef, newName));
        }
        codeData.setRenames(list);
    }

    synchronized void reloadCodeData() {
        if (decompiler != null) decompiler.reloadCodeData();
    }

    synchronized void upsertRename(IJavaNodeRef nodeRef, IJavaCodeRef codeRef, String newName) {
        setRenameEntry(nodeRef, codeRef, newName);
        reloadCodeData();
    }

    synchronized String getRename(IJavaNodeRef nodeRef, IJavaCodeRef codeRef) {
        for (ICodeRename r : codeData.getRenames()) {
            if (Objects.equals(r.getNodeRef(), nodeRef) && Objects.equals(r.getCodeRef(), codeRef)) {
                return r.getNewName();
            }
        }
        return null;
    }

    synchronized String upsertComment(IJavaNodeRef nodeRef, IJavaCodeRef codeRef, String text) {
        String old = "";
        List<ICodeComment> list = new ArrayList<>(codeData.getComments());
        for (Iterator<ICodeComment> it = list.iterator(); it.hasNext();) {
            ICodeComment c = it.next();
            if (Objects.equals(c.getNodeRef(), nodeRef) && Objects.equals(c.getCodeRef(), codeRef)) {
                old = c.getComment() == null ? "" : c.getComment();
                it.remove();
            }
        }
        if (text != null && !text.isEmpty()) {
            list.add(new JadxCodeComment(nodeRef, codeRef, text, CommentStyle.LINE));
        }
        codeData.setComments(list);
        reloadCodeData();
        return old;
    }

    synchronized String getComment(IJavaNodeRef nodeRef, IJavaCodeRef codeRef) {
        for (ICodeComment c : codeData.getComments()) {
            if (Objects.equals(c.getNodeRef(), nodeRef) && Objects.equals(c.getCodeRef(), codeRef)) {
                return c.getComment() == null ? "" : c.getComment();
            }
        }
        return "";
    }

    static JadxNodeRef nodeRefFor(Object node) {
        if (node instanceof ClassNode c) {
            return new JadxNodeRef(IJavaNodeRef.RefType.CLASS, c.getClassInfo().getRawName(), null);
        }
        if (node instanceof MethodNode m) {
            return new JadxNodeRef(IJavaNodeRef.RefType.METHOD, m.getParentClass().getClassInfo().getRawName(), m.getMethodInfo().getShortId());
        }
        if (node instanceof FieldNode f) {
            return new JadxNodeRef(IJavaNodeRef.RefType.FIELD, f.getParentClass().getClassInfo().getRawName(), f.getFieldInfo().getShortId());
        }
        throw new IllegalArgumentException("Unsupported node: " + node);
    }

    static void setNodeAlias(Object node, String newName) {
        if (node instanceof ClassNode c) c.getClassInfo().changeShortName(newName);
        else if (node instanceof MethodNode m) m.getMethodInfo().setAlias(newName);
        else if (node instanceof FieldNode f) f.getFieldInfo().setAlias(newName);
    }

    static void clearNodeAlias(Object node) {
        if (node instanceof ClassNode c) c.getClassInfo().removeAlias();
        else if (node instanceof MethodNode m) m.getMethodInfo().removeAlias();
        else if (node instanceof FieldNode f) f.getFieldInfo().removeAlias();
    }

    static void evictClass(ClassNode top) {
        ClassNode t = top.getTopParentClass();
        t.unloadFromCache();
        clearCodeCommentsAttr(t);
        if (t.getState() == ProcessState.PROCESS_COMPLETE || t.getState() == ProcessState.GENERATED_AND_UNLOADED) {
            t.unload();
            t.setState(ProcessState.NOT_LOADED);
        }
    }

    private static void clearCodeCommentsAttr(ClassNode cls) {
        cls.remove(AType.CODE_COMMENTS);
        for (FieldNode f : cls.getFields()) f.remove(AType.CODE_COMMENTS);
        for (MethodNode m : cls.getMethods()) m.remove(AType.CODE_COMMENTS);
        for (ClassNode inner : cls.getInnerClasses()) clearCodeCommentsAttr(inner);
    }

    private static String digest(File file) throws Exception {
        MessageDigest hash = MessageDigest.getInstance("SHA-256");
        try (InputStream input = Files.newInputStream(file.toPath())) {
            byte[] bytes = new byte[65536];
            for (int n; (n = input.read(bytes)) != -1;) hash.update(bytes, 0, n);
        }
        return HexFormat.of().formatHex(hash.digest());
    }

    void requireReady() throws OpException {
        if (closing || state.equals("closed")) throw OpException.notFound("target is closed", "jadx");
        if (!ready) throw OpException.busy(error == null ? "JADX analysis is " + state : error, "jadx");
    }

    boolean isClosing() { return closing || state.equals("closed"); }
    boolean isReady() { return ready && !closing; }

    synchronized JadxSymbols symbols() throws OpException { requireReady(); return symbols; }
    synchronized JadxDecompiler decompiler() throws OpException { requireReady(); return decompiler; }
    synchronized Map<String, Object> identity() { return identity; }

    Map<String, Object> status() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("state", state); result.put("classes", classCount);
        if (error != null) result.put("error", error);
        return result;
    }

    @Override public void close() {
        closing = true; // signal before waiting on a load or decompile
        ready = false;
        synchronized (this) {
            try {
                if (decompiler != null) decompiler.close();
            } finally {
                decompiler = null; symbols = null; state = "closed";
            }
        }
    }
}
