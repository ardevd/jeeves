package dev.workbench.runs;

import dev.workbench.registry.Json;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** Calls the shared canonical semantic adapter, never a recipe or uploaded script. */
public final class RunBundles {
    private RunBundles() {}

    public static Map<String,Object> copy(Path source, Path destination) throws Exception {
        File runner = FridaBridge.locate("runs/local_run.py");
        if (runner == null) throw new IllegalStateException("Local run adapter is not installed");
        String python = System.getenv("WORKBENCH_RUNS_PYTHON");
        if (python == null || !new File(python).isAbsolute() || !new File(python).canExecute())
            throw new IllegalStateException("Set WORKBENCH_RUNS_PYTHON to an absolute Python 3.10+ with the pinned jsonschema dependency");
        List<String> command = new ArrayList<>(List.of(python,"-I",runner.getAbsolutePath(),"import",
            "--source",source.toAbsolutePath().toString(),"--destination",destination.toAbsolutePath().toString()));
        Path log = Files.createTempFile(destination.getParent(), ".bundle-result-", ".json");
        Process process = null;
        try {
            process = new ProcessBuilder(command).redirectInput(ProcessBuilder.Redirect.from(new File("/dev/null")))
                .redirectError(ProcessBuilder.Redirect.DISCARD).redirectOutput(log.toFile()).start();
            if (!process.waitFor(60,TimeUnit.SECONDS)) {
                process.destroy();
                if (!process.waitFor(5,TimeUnit.SECONDS)) { process.destroyForcibly(); process.waitFor(5,TimeUnit.SECONDS); }
                throw new IllegalStateException("Owned bundle adapter timed out; inspect the private staging directory");
            }
            if (Files.size(log)>65536) throw new IllegalStateException("Bundle adapter response exceeds bound");
            Map<String,Object> result = Json.parseObject(Files.readString(log));
            if (process.exitValue()!=0 || !Boolean.TRUE.equals(result.get("valid")))
                throw new IllegalStateException("Canonical bundle validation/copy failed: " + result.get("error"));
            return result;
        } finally {
            if (process != null && process.isAlive()) {
                process.destroy();
                if (!process.waitFor(5,TimeUnit.SECONDS)) { process.destroyForcibly(); process.waitFor(5,TimeUnit.SECONDS); }
            }
            Files.deleteIfExists(log);
        }
    }
}
