package dev.workbench.persistence;

import dev.workbench.engine.CheckpointedFileSave;
import dev.workbench.registry.Json;
import dev.workbench.registry.SidecarIO;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Map;

/** Real filesystem failure and separate-process interruption checks; no JEB or model. */
public final class PersistenceTest {
    private static int checks;
    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
        checks++; System.out.println("PASS " + message);
    }
    private static void expectFailure(Throwing work) throws Exception {
        try { work.run(); } catch (IOException expected) { return; }
        throw new AssertionError("Expected IOException");
    }
    @FunctionalInterface private interface Throwing { void run() throws Exception; }

    public static void main(String[] args) throws Exception {
        if (args.length > 0 && "crash".equals(args[0])) {
            CheckpointedFileSave.write(Path.of(args[1]), destination -> {
                Files.writeString(destination, "torn write");
                Runtime.getRuntime().halt(17);
                return true;
            });
            return;
        }
        Path home = Files.createTempDirectory("jeeves-persistence-");
        Path db = home.resolve("fixture.jdb2");
        Files.writeString(db, "complete baseline");
        FileTime baselineTime = FileTime.fromMillis(System.currentTimeMillis() - 60000);
        Files.setLastModifiedTime(db, baselineTime);

        expectFailure(() -> CheckpointedFileSave.write(db, destination -> {
            Files.writeString(destination, "partial"); throw new IOException("injected disk failure");
        }));
        check("complete baseline".equals(Files.readString(db)), "throwing writer restores prior complete bytes");
        check(baselineTime.equals(Files.getLastModifiedTime(db)), "rollback restores timestamp for unsaved edit replay");
        check(!Files.exists(CheckpointedFileSave.marker(db)), "completed rollback removes recovery receipt");
        check(!CheckpointedFileSave.write(db, destination -> { Files.writeString(destination, "refused"); return false; }), "writer refusal remains a refusal");
        check("complete baseline".equals(Files.readString(db)), "refused save preserves original checkpoint");
        Path first = home.resolve("first.jdb2");
        expectFailure(() -> CheckpointedFileSave.write(first, destination -> {
            Files.writeString(destination, "partial first"); throw new IOException("injected first failure");
        }));
        check(!Files.exists(first), "failed first save leaves no misleading database");
        check(CheckpointedFileSave.write(db, destination -> { Files.writeString(destination, "complete new version"); return true; }), "successful save reports success");
        check("complete new version".equals(Files.readString(db)), "successful native write retained");
        long backups;
        try (var files = Files.list(home.resolve(".save-checkpoints"))) {
            backups = files.filter(p -> p.getFileName().toString().endsWith(".jdb2")).count();
        }
        check(backups >= 3, "each previous checkpoint retained under a unique filename");

        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        Process child = new ProcessBuilder(java, "-cp", System.getProperty("java.class.path"),
                PersistenceTest.class.getName(), "crash", db.toString()).inheritIO().start();
        check(child.waitFor() == 17, "separate child stopped during its database write");
        check(Files.exists(CheckpointedFileSave.marker(db)), "interrupted save leaves durable recovery receipt");
        check("torn write".equals(Files.readString(db)), "test actually interrupted an in-place write");
        Files.createFile(Path.of(db + ".lock"));
        expectFailure(() -> CheckpointedFileSave.recover(db));
        check("torn write".equals(Files.readString(db)), "recovery refuses an unchecked native lock");
        Files.delete(Path.of(db + ".lock"));
        CheckpointedFileSave.recover(db);
        check("complete new version".equals(Files.readString(db)), "fresh-process interruption restores verified complete checkpoint");
        check(!Files.exists(CheckpointedFileSave.marker(db)), "successful recovery completes its receipt");

        Path parentAsFile = home.resolve("not-a-directory"); Files.writeString(parentAsFile, "blocked");
        long failures = SidecarIO.writeFailures.get();
        check(!SidecarIO.writeAtomic(parentAsFile.resolve("history.json").toFile(), "[]", "history test"), "sidecar failure is explicit");
        check(SidecarIO.writeFailures.get() == failures + 1, "sidecar failure increments visible issue counter once");
        Path journal = home.resolve("history.json");
        check(SidecarIO.writeAtomic(journal.toFile(), "[{\"newName\":\"saved\"}]", "history test"), "sidecar flush and atomic replacement succeed");
        check(Json.parse(Files.readString(journal)) instanceof java.util.List<?>, "durable sidecar can be read back");
        System.out.println("Persistence filesystem checks: " + checks + " passed; " + home);
    }
}
