package dev.workbench.it;

import dev.workbench.engine.jeb.JebCore;
import dev.workbench.engine.jeb.JebEngine;
import dev.workbench.registry.Json;
import dev.workbench.registry.SidecarIO;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Verify names and comments in an isolated copied native database with no history replay. */
public final class NativeSnapshotVerifier {
    private static List<Map<String,Object>> parameterPairs(Object value) {
        if (!(value instanceof List<?> rows)) throw new IllegalArgumentException("A parameter list is required");
        List<Map<String,Object>> parameters = new ArrayList<>();
        for (Object raw : rows) {
            if (!(raw instanceof Map<?,?> parameter) || !(parameter.get("index") instanceof Number index))
                throw new IllegalArgumentException("Every parameter needs a numeric index: " + raw);
            Map<String,Object> row = new LinkedHashMap<>();
            row.put("index", index.longValue()); row.put("name", parameter.get("name"));
            parameters.add(row);
        }
        return parameters;
    }
    @SuppressWarnings("unchecked")
    public static void main(String[] args) throws Exception {
        if (args.length != 3) throw new IllegalArgumentException("database expectations.json receipt.json");
        Path db = Path.of(args[0]).toAbsolutePath();
        Path expectedPath = Path.of(args[1]).toAbsolutePath();
        Path receiptPath = Path.of(args[2]).toAbsolutePath();
        Map<String,Object> input = Json.parseObject(Files.readString(expectedPath));
        List<?> expected = (List<?>) input.get("symbols");
        if (expected == null || expected.isEmpty()) throw new IllegalArgumentException("A nonempty symbols list is required");
        if (!Files.isRegularFile(db) || !db.toString().endsWith(".jdb2")) throw new IllegalArgumentException("A copied native database is required");
        JebEngine engine = new JebEngine("native-verification", db.getFileName().toString(), db.toString(), null, db.toFile());
        List<Map<String,Object>> results = new ArrayList<>();
        int failures = 0;
        Map<String,List<Object>> localsByMethod = new LinkedHashMap<>();
        Map<String,Exception> localFailures = new LinkedHashMap<>();
        try {
            engine.openAsync();
            for (;;) {
                String state = String.valueOf(engine.info().get("state"));
                if ("ready".equals(state)) break;
                if ("error".equals(state)) throw new IllegalStateException(engine.info().toString());
                Thread.sleep(100);
            }
            if (engine.info().containsKey("recoveredEdits")) throw new IllegalStateException("Native verification unexpectedly replayed history");
            for (Object raw : expected) {
                Map<String,Object> entry = (Map<String,Object>) raw;
                String symbol = String.valueOf(entry.get("original_symbol"));
                Map<String,Object> result = new LinkedHashMap<>();
                result.put("original_symbol", symbol);
                boolean ok = true;
                try {
                    if (entry.containsKey("local_name")) {
                        Object identity = entry.get("local_identity");
                        if (!(identity instanceof String id) || !id.startsWith(symbol + "#local:"))
                            throw new IllegalArgumentException("A stable local_identity bound to the original method is required");
                        if (!localsByMethod.containsKey(symbol) && !localFailures.containsKey(symbol)) {
                            try {
                                Map<String,Object> method = engine.resolve(symbol);
                                if (!symbol.equals(method.get("origSig")) || Boolean.TRUE.equals(method.get("contradiction")))
                                    throw new IllegalStateException("Local method identity changed: " + method);
                                localsByMethod.put(symbol, engine.locals(symbol));
                            } catch (Exception failure) { localFailures.put(symbol, failure); }
                        }
                        if (localFailures.containsKey(symbol)) throw localFailures.get(symbol);
                        Map<String,Object> matched = null;
                        for (Object value : localsByMethod.get(symbol)) {
                            Map<String,Object> local = (Map<String,Object>) value;
                            if (identity.equals(local.get("originalSymbol"))) {
                                if (matched != null) throw new IllegalStateException("Duplicate local identity: " + identity);
                                matched = local;
                            }
                        }
                        if (matched == null) throw new IllegalStateException("Local identity missing after reopen: " + identity);
                        result.put("local", matched);
                        ok = Objects.equals(entry.get("local_name"), matched.get("name"))
                                && symbol.equals(matched.get("originalMethod"));
                    } else {
                        Map<String,Object> resolved = engine.resolve(symbol);
                        result.put("name", resolved.get("name"));
                        result.put("origSig", resolved.get("origSig"));
                        ok = symbol.equals(resolved.get("origSig")) && !Boolean.TRUE.equals(resolved.get("contradiction"));
                        if (entry.containsKey("name")) ok &= Objects.equals(entry.get("name"), resolved.get("name"));
                    }
                    if (entry.containsKey("parameters")) {
                        Map<String,Object> meta = engine.methodParameters(symbol, true);
                        List<Map<String,Object>> parameters = parameterPairs(meta.get("parameters"));
                        result.put("parameters", parameters);
                        result.put("original_method", meta.get("original_method"));
                        ok &= symbol.equals(meta.get("original_method"))
                                && parameterPairs(entry.get("parameters")).equals(parameters);
                    }
                    if (entry.containsKey("comment")) {
                        String comment = String.valueOf(engine.hover(symbol, 0).get("comment"));
                        result.put("comment", comment);
                        ok &= entry.get("comment").equals(comment);
                    }
                } catch (Exception failure) {
                    ok = false; result.put("error", failure.toString());
                }
                result.put("ok", ok);
                if (!ok) failures++;
                results.add(result);
            }
            Map<String,Object> receipt = new LinkedHashMap<>();
            receipt.put("database", db.toString()); receipt.put("native_only", true);
            receipt.put("history_replayed", false); receipt.put("engine", engine.info());
            receipt.put("checked", results.size()); receipt.put("failures", failures);
            receipt.put("ok", failures == 0); receipt.put("results", results);
            if (!SidecarIO.writeAtomic(receiptPath.toFile(), Json.write(receipt), "native verification receipt"))
                throw new IllegalStateException("Cannot persist verification receipt");
            System.out.println(Json.write(Map.of("checked", results.size(), "failures", failures, "native_only", true, "receipt", receiptPath.toString())));
            if (failures > 0) throw new AssertionError("Native database verification failed for " + failures + " symbols");
        } finally {
            // This is an expendable copied database; no verification-only decompilation is saved.
            engine.discardUnsaved(); engine.close(); JebCore.shutdown();
        }
    }
}
