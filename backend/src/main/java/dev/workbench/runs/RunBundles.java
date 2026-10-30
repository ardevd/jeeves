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
        String configured = System.getenv("WORKBENCH_RUNS_PYTHON");
        if (configured != null && (!new File(configured).isAbsolute() || !new File(configured).canExecute()))
            throw new IllegalStateException("WORKBENCH_RUNS_PYTHON must be an absolute executable path");
        File py = configured == null || configured.isBlank() ? FridaBridge.locate("runs/venv/bin/python") : new File(configured);
        if (py == null || !py.canExecute())
            throw new IllegalStateException("Frida sidecar venv unavailable — run backend/runs/pull-frida.sh or set WORKBENCH_RUNS_PYTHON");
        String python = py.getAbsolutePath();
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
