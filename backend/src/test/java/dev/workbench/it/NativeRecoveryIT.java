package dev.workbench.it;

import dev.workbench.engine.CheckpointedFileSave;
import dev.workbench.engine.jeb.JebCore;
import dev.workbench.engine.jeb.JebEngine;
import dev.workbench.server.AgentManager;
import dev.workbench.server.EventBus;
import dev.workbench.server.Workspace;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/** Run write in one JVM (intentional halt), then recover in a fresh JVM after checking its owner.
 * No production database or JEB installation file is modified. */
public final class NativeRecoveryIT {
    private static void ready(Workspace ws, String tid) throws Exception {
        for (;;) {
            var info = ws.engineFor(tid).info();
            if ("ready".equals(info.get("state"))) return;
            if ("error".equals(info.get("state"))) throw new AssertionError(info.toString());
            Thread.sleep(100);
        }
    }
    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
        System.out.println("PASS " + message);
    }
    public static void main(String[] args) throws Exception {
        if (args.length == 2) {
            Path output = Path.of(args[1]).toAbsolutePath(); Files.createDirectories(output);
            for (String stage : java.util.List.of("write", "recover")) {
                java.util.List<String> command = new java.util.ArrayList<>();
                command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
                command.addAll(java.lang.management.ManagementFactory.getRuntimeMXBean().getInputArguments());
                command.addAll(java.util.List.of("-cp", System.getProperty("java.class.path"), NativeRecoveryIT.class.getName(), stage, Path.of(args[0]).toAbsolutePath().toString(), output.toString()));
                Process child = new ProcessBuilder(command).inheritIO().start();
                int status = child.waitFor();
                check(status == ("write".equals(stage) ? 17 : 0), "fresh " + stage + " child completed with expected exit");
                if ("write".equals(stage)) {
                    Path database = Path.of(Files.readString(output.resolve("database-path.txt")));
                    if (!database.normalize().startsWith(output.normalize())) throw new AssertionError("fixture database escaped test output");
                    // Only this exact exited fixture child's lock may be removed.
                    Files.deleteIfExists(Path.of(database + ".lock"));
                }
            }
            return;
        }
        String mode = args[0];
        Path fixture = Path.of(args[1]).toAbsolutePath();
        Path out = Path.of(args[2]).toAbsolutePath(); Files.createDirectories(out);
        Path input = out.resolve("RecoveryFixture.dex");
        if (!Files.exists(input)) Files.copy(fixture, input);
        Workspace ws = new Workspace();
        ws.setWorkbenchRoot(out.resolve("home").toFile());
        ws.setAgents(new AgentManager(ws, new EventBus(), out.resolve("home").toFile()));
        ws.setDevices(new dev.workbench.device.DeviceManager(out.resolve("home").toFile()));
        JebEngine nativeOnly = null;
        try {
            if ("recover".equals(mode)) {
                Path database = Path.of(Files.readString(out.resolve("database-path.txt")));
                CheckpointedFileSave.recover(database);
                check(!Files.exists(CheckpointedFileSave.marker(database)), "interrupted native DB restored from verified pre-save checkpoint");
            }
            ws.openProject("PersistenceRecovery");
            String tid = String.valueOf(ws.openTarget(input.toString(), "jeb").get("id"));
            ready(ws, tid);
            Path database = Path.of(String.valueOf(ws.engineFor(tid).info().get("jdb2")));
            if ("write".equals(mode)) {
                Files.writeString(out.resolve("database-path.txt"), database.toString());
                var rename = ws.renameSymbol(tid, "Lfixture/Params;", "JournalSurvivesCrash", "llm:recovery-a", null, null);
                check(Boolean.TRUE.equals(rename.get("durableHistory")), "rename accepted only after durable journal");
                ws.withTargetWrite(tid, () -> {
                    var result = ws.engineFor(tid).comment("Lfixture/Params;", "comment survives interrupted native save", "llm:recovery-a");
                    Map<String,Object> entry = new LinkedHashMap<>(result);
                    entry.put("op", "comment"); entry.put("actor", "llm:recovery-a");
                    entry.put("newAddr", result.get("addr"));
                    entry.put("newText", "comment survives interrupted native save");
                    ws.recordHistory(tid, entry); return null;
                });
                check(ws.historyList(tid, 0).size() == 2, "rename and comment both recorded");
                CheckpointedFileSave.write(database, path -> {
                    Files.writeString(path, "interrupted native database bytes");
                    Runtime.getRuntime().halt(17);
                    return true;
                });
            } else {
                check("JournalSurvivesCrash".equals(ws.engineFor(tid).resolve("Lfixture/Params;").get("name")), "fresh process replays accepted rename");
                check(String.valueOf(ws.engineFor(tid).decompile("Lfixture/Params;", Map.of()).get("text")).contains("comment survives interrupted native save"), "fresh process replays accepted comment");
                check(((Number)ws.engineFor(tid).info().get("recoveredEdits")).intValue() == 2, "both edits came from journal replay");
                check(Boolean.TRUE.equals(ws.saveTarget(tid).get("saved")), "recovered state saved successfully");
                ws.closeTarget(tid);
                nativeOnly = new JebEngine("verify", database.getFileName().toString(), database.toString(), null, database.toFile());
                nativeOnly.openAsync();
                for (;;) {
                    String state = String.valueOf(nativeOnly.info().get("state"));
                    if ("ready".equals(state)) break;
                    if ("error".equals(state)) throw new AssertionError(nativeOnly.info().toString());
                    Thread.sleep(100);
                }
                check("JournalSurvivesCrash".equals(nativeOnly.resolve("Lfixture/Params;").get("name")), "native-only reopened database retains recovered rename");
                check(String.valueOf(nativeOnly.decompile("Lfixture/Params;", Map.of()).get("text")).contains("comment survives interrupted native save"), "native-only reopened database retains recovered comment");
            }
        } catch (Throwable failure) {
            failure.printStackTrace(System.out); throw failure;
        } finally {
            if (nativeOnly != null) nativeOnly.close();
            ws.closeAll(); JebCore.shutdown();
        }
    }
}
