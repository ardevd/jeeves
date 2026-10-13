package dev.workbench.it;

import dev.workbench.runs.RunBundles;
import dev.workbench.runs.RunStore;
import java.nio.file.*;
import java.util.Map;

/** Canonical adapter/store integration, synthetic external zero-event evidence; no engine/device. */
public final class ConsumerFailedRunIT {
    public static void main(String[] args) throws Exception {
        String home = System.getenv("WORKBENCH_HOME");
        if (home == null) throw new IllegalArgumentException("Private WORKBENCH_HOME required");
        Path root = Files.createTempDirectory(Path.of(home), "failed-run-");
        Path fixture = Path.of("fixtures/local-run/external-failed-zero").toAbsolutePath();
        Path frozen = root.resolve("frozen");
        Map<String,Object> validation = RunBundles.copy(fixture, frozen);
        if (!"failed".equals(validation.get("completion")) || ((Number)validation.get("events")).intValue() != 0)
            throw new AssertionError("Adapter changed failed/zero result");
        RunStore store = new RunStore(root.resolve("store").toFile());
        Map<String,Object> row = store.importCanonical(frozen.toFile(), "synthetic-target", "synthetic-key", "synthetic",
                String.valueOf(validation.get("manifest_sha256")), false);
        if (!"failed".equals(row.get("completion")) || !"unknown".equals(row.get("cleanup"))
                || !"external".equals(row.get("producerMode")) || ((Number)row.get("hitCount")).intValue() != 0)
            throw new AssertionError("Store changed evidence status");
        store = new RunStore(root.resolve("store").toFile());
        Path exported = root.resolve("export");
        Map<String,Object> result = RunBundles.copy(store.canonicalBundle(String.valueOf(row.get("execId"))).toPath(), exported);
        if (!"failed".equals(result.get("completion")) || ((Number)result.get("events")).intValue() != 0)
            throw new AssertionError("Restart/export changed failed/zero result");
        if (Files.mismatch(fixture.resolve("manifest.json"), exported.resolve("manifest.json")) != -1
                || Files.size(exported.resolve("events/000001.jsonl")) != 0)
            throw new AssertionError("Canonical failed bytes changed");
        System.out.println("Failed external import: 4 checks passed; adapter/store/reopen/export, no engine or phone");
    }
}
