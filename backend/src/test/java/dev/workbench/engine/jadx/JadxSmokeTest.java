package dev.workbench.engine.jadx;

import java.io.File;
import java.nio.file.Files;
import java.util.*;

/** Real-runtime smoke test; pass a DEX/APK path. No JEB or server required. */
public final class JadxSmokeTest {
    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("pass a DEX/APK fixture path");
        File input = new File(args[0]);
        JadxEngine engine = new JadxEngine("test", input.getName(), input.getAbsolutePath(), input,
                Files.createTempDirectory("jeeves-jadx-smoke-").toFile());
        try {
            engine.openAsync();
            long deadline = System.nanoTime() + 60_000_000_000L;
            while ("loading".equals(engine.info().get("state")) && System.nanoTime() < deadline) Thread.sleep(20);
            require("ready".equals(engine.info().get("state")), "ready: " + engine.info());
            require(engine.inputIdentity().get("sha256").toString().length() == 64, "input identity");
            require(!((List<?>) engine.tree("").get("items")).isEmpty(), "tree roots");
            List<?> classes = (List<?>) engine.symbolSearchPage("hello", 100, "class").get("rows");
            require(!classes.isEmpty(), "fixture class search");
            String address = ((Map<?, ?>) classes.getFirst()).get("addr").toString();
            require(address.equals(engine.resolveStrict(address).get("origSig")), "exact resolution");
            require(((Number) engine.classinfo(address).get("methods")).intValue() == 2, "class metadata");
            Map<String, Object> rendered = engine.decompile(address, Map.of("tokens", true));
            require(!rendered.get("text").toString().isBlank(), "class source");
            require(!((List<?>) rendered.get("tokens")).isEmpty(), "semantic spans");
            for (Object obj : (List<?>) engine.outline(address).get("methods")) {
                String method = ((Map<?, ?>) obj).get("addr").toString();
                engine.xrefsFrom(method); engine.callers(method);
                engine.implementations(method);
                Map<String, Object> scoped = engine.decompile(method, Map.of("scope", "method", "tokens", true));
                require(!scoped.get("text").toString().isBlank(), "method source: " + method);
                try {
                    engine.resolveStrict(method + "WrongReturn");
                    throw new AssertionError("contradictory descriptor accepted");
                } catch (dev.workbench.registry.OpException expected) {
                    require("E_NOTFOUND".equals(expected.code), "contradictory descriptor refused");
                }
            }
            engine.strings(null, List.of(), false, 0, 100, null);
            engine.reindex();
            deadline = System.nanoTime() + 60_000_000_000L;
            while (!"built".equals(engine.searchIndexStatus().get("state")) && System.nanoTime() < deadline) Thread.sleep(20);
            require("built".equals(engine.searchIndexStatus().get("state")), "index built: " + engine.searchIndexStatus());
            require(!engine.search("class", Map.of()).isEmpty(), "indexed source search");
        } finally { engine.close(); }
        require("closed".equals(engine.info().get("state")), "close");
        verifyWorkspace(input);
    }

    private static void verifyWorkspace(File dex) throws Exception {
        java.nio.file.Path root = Files.createTempDirectory("jeeves-jadx-workspace-");
        java.nio.file.Path apk = root.resolve("hello.apk");
        try (var zip = new java.util.zip.ZipOutputStream(Files.newOutputStream(apk))) {
            zip.putNextEntry(new java.util.zip.ZipEntry("classes.dex"));
            Files.copy(dex.toPath(), zip); zip.closeEntry();
        }
        var workspace = new dev.workbench.server.Workspace();
        workspace.setWorkbenchRoot(Files.createDirectories(root.resolve("home")).toFile());
        String target = String.valueOf(workspace.openTarget(apk.toString(), "jadx").get("id"));
        try {
            var engine = workspace.engineFor(target);
            long deadline = System.nanoTime() + 60_000_000_000L;
            while ("loading".equals(engine.info().get("state")) && System.nanoTime() < deadline) Thread.sleep(20);
            require("ready".equals(engine.info().get("state")), "workspace APK ready: " + engine.info());
            require(target.equals(workspace.openTarget(apk.toString(), "jadx").get("id")), "workspace duplicate open reuses target");
            require(!engine.symbolSearch("hello").isEmpty(), "workspace engine dispatch");
        } finally { workspace.closeTarget(target, false); }
        require(!workspace.openTargetIds().contains(target), "workspace detaches closed target");
    }
    private static void require(boolean ok, String label) {
        if (!ok) throw new AssertionError(label);
        System.out.println("PASS " + label);
    }
}
