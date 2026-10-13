package dev.workbench.it;

import dev.workbench.runs.FridaBridge;
import dev.workbench.server.EventBus;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/** Harmless JSON sidecar: no Frida, ADB, device, engine or model. */
public final class FridaBridgeLifecycleIT {
    public static void main(String[] args) throws Exception {
        String directory=System.getenv("WORKBENCH_RUNS_ROOT");
        if (directory==null) throw new IllegalStateException("Explicit private fixture root required");
        Path root=Path.of(directory);
        Files.createDirectories(root);
        Files.writeString(root.resolve("bridge.py"), """
            import json,os,sys,time
            from pathlib import Path
            root=Path(__file__).parent
            counter=root/'counter'
            generation=int(counter.read_text())+1 if counter.exists() else 1
            counter.write_text(str(generation))
            with (root/'owned-pids').open('a') as log:log.write(str(os.getpid())+chr(10))
            for line in sys.stdin:
                value=json.loads(line)
                if value['op']=='provision':os._exit(23)
                print(json.dumps({'id':value['id'],'ok':True,'pong':True,'generation':generation}),flush=True)
            time.sleep(1)
            """,java.nio.file.StandardOpenOption.CREATE_NEW);
        FridaBridge bridge=new FridaBridge(new EventBus());
        try {
            if (((Number)bridge.ping().get("generation")).intValue()!=1) throw new AssertionError("first owner");
            var failed=CompletableFuture.supplyAsync(()->{
                try { bridge.provision("fixture","/fixture/server",true,27183);return false; }
                catch (RuntimeException e) { return e.getMessage().contains("exited"); }
            });
            if (!failed.get(5,TimeUnit.SECONDS)) throw new AssertionError("Old owner was not failed promptly");
            if (((Number)bridge.ping().get("generation")).intValue()!=2) throw new AssertionError("new owner response");
            long start=System.nanoTime();var cleanup=bridge.beginShutdown();
            if (System.nanoTime()-start>500_000_000L) throw new AssertionError("Engine save blocked on sidecar cleanup");
            boolean refused=false;
            try { bridge.ping(); } catch (IllegalStateException expected) { refused=true; }
            if (!refused) throw new AssertionError("Shutdown respawned a sidecar");
            cleanup.get(5,TimeUnit.SECONDS);
            for (String pid:Files.readAllLines(root.resolve("owned-pids")))
                if (ProcessHandle.of(Long.parseLong(pid)).map(ProcessHandle::isAlive).orElse(false)) throw new AssertionError("Owned fixture remains alive");
            System.out.println("Frida bridge lifecycle: 6 checks passed; fixture owners reaped, no phone/engine/model");
        } finally { bridge.shutdown(); }
    }
}
