package dev.workbench.engine.jadx;

import dev.workbench.registry.OpException;
import java.io.*;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.*;
import jadx.api.JadxArgs;
import jadx.api.JadxDecompiler;
import jadx.api.impl.InMemoryCodeCache;
import jadx.api.impl.AnnotatedCodeWriter;

/** One independently owned JADX instance. All model access uses this session's monitor. */
final class JadxSession implements AutoCloseable {
    private final File artifact;
    private final Runnable onReady;
    private JadxDecompiler decompiler;
    private JadxSymbols symbols;
    private Map<String, Object> identity;
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
        this(artifact, null);
    }

    JadxSession(File artifact, Runnable onReady) {
        this.artifact = Objects.requireNonNull(artifact);
        this.onReady = onReady;
    }

    void openAsync() {
        synchronized (this) {
            if (!state.equals("idle") || closing) return;
            state = "loading";
        }
        Thread worker = new Thread(this::load, "jadx-open-" + artifact.getName());
        worker.setDaemon(true);
        worker.start();
    }

    private synchronized void load() {
        if (closing) { state = "closed"; return; }
        JadxDecompiler opened = null;
        try {
            // Hash before loading and verify after: do not label a replaced input as the loaded one.
            String before = digest(artifact);
            JadxArgs args = new JadxArgs();
            args.setInputFiles(List.of(artifact));
            args.setThreadsCount(Math.max(1, Math.min(4, Runtime.getRuntime().availableProcessors())));
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
            state = "ready";
            if (onReady != null) onReady.run();
        } catch (Exception failure) {
            if (opened != null) try { opened.close(); } catch (Exception cleanup) { failure.addSuppressed(cleanup); }
            error = failure.toString();
            state = closing ? "closed" : "error";
        }
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
        if (!state.equals("ready")) throw OpException.busy(error == null ? "JADX analysis is " + state : error, "jadx");
    }

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
        synchronized (this) {
            try {
                if (decompiler != null) decompiler.close();
            } finally {
                decompiler = null; symbols = null; state = "closed";
            }
        }
    }
}
