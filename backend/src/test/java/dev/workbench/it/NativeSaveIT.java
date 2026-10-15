package dev.workbench.it;

import dev.workbench.engine.jeb.JebEngine;
import dev.workbench.engine.jeb.JebCore;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/** Personal-JEB integration: save twice, inspect native lock and reopen without history replay. */
public final class NativeSaveIT {
    private static int checks;
    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
        checks++; System.out.println("PASS " + message);
    }
    private static void ready(JebEngine engine) throws Exception {
        for (;;) {
            Map<String,Object> info = engine.info();
            if ("ready".equals(info.get("state"))) return;
            if ("error".equals(info.get("state"))) throw new AssertionError(info.toString());
            Thread.sleep(100);
        }
    }
    @SuppressWarnings("unchecked")
    private static Map<String,Object> local(JebEngine engine, String method, String name) throws Exception {
        return engine.locals(method).stream().map(row -> (Map<String,Object>) row)
                .filter(row -> name.equals(row.get("name"))).findFirst().orElseThrow();
    }
    public static void main(String[] args) throws Exception {
        Path fixture = Path.of(args[0]).toAbsolutePath();
        Path out = Path.of(args[1]).toAbsolutePath();
        Files.createDirectories(out);
        Path artifact = out.resolve("PersistenceFixture.dex");
        Files.copy(fixture, artifact);
        Path database = out.resolve("PersistenceFixture.jdb2");
        JebEngine engine = null;
        try {
            engine = new JebEngine("persist1", artifact.getFileName().toString(), artifact.toString(), artifact.toFile(), database.toFile());
            engine.openAsync(); ready(engine);
            check(Files.size(database) > 0, "initial database exists");
            check(Files.exists(Path.of(database + ".lock")), "initial save retains native database lock");
            String localMethod = "Lfixture/Params;->absent(I)I";
            @SuppressWarnings("unchecked") Map<String,Object> beforeLocal = (Map<String,Object>) engine.locals(localMethod).get(0);
            String beforeName = String.valueOf(beforeLocal.get("name"));
            check(localMethod.equals(beforeLocal.get("originalMethod")), "locals list exposes the original method");
            check(beforeLocal.get("itemId") instanceof String
                    && (localMethod + "#local:" + beforeLocal.get("itemId")).equals(beforeLocal.get("originalSymbol")),
                    "locals list exposes a lossless identifier and its stable symbol");
            check(beforeLocal.get("originalSymbol").equals(engine.resolveLocal(localMethod, beforeName).get("originalSymbol")),
                    "list and single-local resolution agree on identity");
            engine.renameLocal(localMethod, beforeName, "retainedArgument", "human");
            check(beforeLocal.get("originalSymbol").equals(local(engine, localMethod, "retainedArgument").get("originalSymbol")),
                    "local rename preserves its listed identity");
            engine.rename("Lfixture/Params;", "SavedNameOne", "human");
            check(beforeLocal.get("originalSymbol").equals(local(engine, "Lfixture/SavedNameOne;->absent(I)I", "retainedArgument").get("originalSymbol")),
                    "current owner spelling resolves to the same original local identity");
            engine.comment("Lfixture/SavedNameOne;", "durability verification comment", "human");
            check(Boolean.TRUE.equals(engine.save().get("saved")), "first explicit save confirms success");
            check(Files.exists(Path.of(database + ".lock")), "explicit save retains native database lock");
            engine.rename("Lfixture/Params;", "SavedNameTwo", "human");
            check(Boolean.TRUE.equals(engine.save().get("saved")), "second save succeeds in same session");
            engine.close(); engine = null;
            check(!Files.exists(Path.of(database + ".lock")), "close releases final database lock");
            try (var files = Files.list(out)) {
                check(files.noneMatch(p -> p.getFileName().toString().endsWith(".lock")), "close leaves no native locks");
            }
            engine = new JebEngine("persist2", database.getFileName().toString(), database.toString(), null, database.toFile());
            engine.openAsync(); ready(engine);
            check("SavedNameTwo".equals(engine.resolve("Lfixture/Params;").get("name")), "native-only reopen retains renamed class");
            Map<String,Object> reopenedLocal = local(engine, localMethod, "retainedArgument");
            check(beforeLocal.get("originalSymbol").equals(reopenedLocal.get("originalSymbol")),
                    "native-only reopen retains the same local identifier and chosen name");
            check(beforeLocal.get("originalName").equals(reopenedLocal.get("originalName")),
                    "native-only reopen retains the local's original name");
            check(reopenedLocal.get("originalSymbol").equals(engine.resolveLocal(localMethod, "retainedArgument").get("originalSymbol")),
                    "reopened list and single-local resolution still agree");
            Files.writeString(out.resolve("local-identity.json"), dev.workbench.registry.Json.write(reopenedLocal));
            String code = String.valueOf(engine.decompile("Lfixture/Params;", Map.of()).get("text"));
            check(code.contains("durability verification comment"), "native-only reopen retains comment");
            check(Boolean.TRUE.equals(engine.save().get("saved")), "save after native-only reopen succeeds");
            engine.close(); engine = null;
            String digest = dev.workbench.engine.CheckpointedFileSave.sha256(database);
            engine = new JebEngine("bad-replay", database.getFileName().toString(), database.toString(), null, database.toFile());
            engine.primeDirtyReplay(java.util.List.of(Map.of("op", "comment", "addr", "Lfixture/DoesNotExist;", "newText", "invalid recovery fixture")));
            engine.openAsync();
            while ("loading".equals(engine.info().get("state"))) Thread.sleep(100);
            check("error".equals(engine.info().get("state")) && ((Number)engine.info().get("replayFailed")).intValue() == 1, "invalid recovery entry prevents a ready partial project");
            try { engine.save(); throw new AssertionError("partial replay was saved"); }
            catch (dev.workbench.registry.OpException expected) { check(true, "explicit save refuses partially recovered project"); }
            engine.close(); engine = null;
            check(digest.equals(dev.workbench.engine.CheckpointedFileSave.sha256(database)), "failed replay and close leave original native bytes intact");
            System.out.println("Native persistence: " + checks + " checks passed");
        } finally {
            if (engine != null) engine.close();
            JebCore.shutdown();
        }
    }
}
