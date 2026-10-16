package dev.workbench.server;

import dev.workbench.engine.Engine;
import dev.workbench.registry.Json;
import dev.workbench.registry.OpException;
import dev.workbench.registry.SidecarIO;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.*;

/** Durable, evidence-backed variable coverage; independent of the bounded agent transcript.
 * Names are still hypotheses. This checks inventory and receipt completeness, not semantics. */
public final class NamingCoverage {
    private NamingCoverage() {}
    private static final String SCHEMA = "naming-coverage-v1";

    @SuppressWarnings("unchecked") private static Map<String,Object> map(Object value) {
        if (!(value instanceof Map<?,?>)) throw new IllegalArgumentException("Expected an object");
        return (Map<String,Object>) value;
    }
    private static String digest(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException e) { throw new AssertionError(e); }
    }
    private static String text(Object value) { return value instanceof String s ? s : ""; }

    /** Also used by the independent native verifier: no Workspace, history or coverage sidecar. */
    public static Map<String,Object> inventory(Engine engine, String symbol) throws OpException {
        Map<String,Object> resolved = engine.resolve(symbol);
        String original = text(resolved.get("origSig"));
        if (!"method".equals(resolved.get("kind")) || original.isEmpty() || Boolean.TRUE.equals(resolved.get("contradiction")))
            throw OpException.syntax("Naming coverage requires an exact method identity", symbol);
        List<Object> errors = new ArrayList<>();
        Map<String,Object> params = new LinkedHashMap<>();
        List<Object> locals = new ArrayList<>();
        try {
            params = engine.methodParameters(original);
            if (!original.equals(params.get("original_method")) || !(params.get("parameters") instanceof List<?>)
                    || !(params.get("hasCode") instanceof Boolean))
                errors.add("Engine does not supply a verified formal-argument inventory and code-presence flag");
        } catch (OpException e) { errors.add("code.params: " + e.getMessage()); }
        if (!Boolean.FALSE.equals(params.get("hasCode"))) {
            try { locals = engine.locals(original); }
            catch (OpException e) { errors.add("locals.list: " + e.getMessage()); }
        }
        Map<String,Map<String,Object>> variables = new TreeMap<>();
        List<?> formal = params.get("parameters") instanceof List<?> rows ? rows : List.of();
        if (!(params.get("prototype") instanceof Map<?,?> prototype) || !(prototype.get("count") instanceof Number count)
                || count.intValue()!=formal.size()) errors.add("Formal argument count does not match the native prototype");
        for (Object value : formal) {
            Map<String,Object> p = map(value);
            if (!(p.get("index") instanceof Number number)) { errors.add("Formal argument has no index"); continue; }
            int index = number.intValue();
            if (index < 0 || index >= formal.size()) errors.add("Formal argument index outside prototype: " + index);
            Map<String,Object> row = new LinkedHashMap<>(p);
            String identity = original + "#parameter:" + index;
            row.put("identity", identity); row.put("kind", "argument"); row.put("parameterIndex", index);
            row.put("name", p.get("name")); row.put("renameableLocal", false);
            if (variables.put(identity, row) != null) errors.add("Duplicate argument: " + identity);
        }
        for (Object value : locals) {
            Map<String,Object> local = map(value);
            if ("this".equals(local.get("name"))) continue; // fixed receiver, not a renameable variable
            String localIdentity = text(local.get("originalSymbol"));
            if (!original.equals(local.get("originalMethod")) || !localIdentity.startsWith(original + "#local:")) {
                errors.add("Local lacks a stable identity: " + local.get("name")); continue;
            }
            if (local.get("parameterIndex") instanceof Number index) {
                String identity = original + "#parameter:" + index.intValue();
                Map<String,Object> row = variables.get(identity);
                if (row == null) { errors.add("Local names an unknown argument: " + localIdentity); continue; }
                if (row.containsKey("originalSymbol")) errors.add("Duplicate local for argument: " + identity);
                if (!Objects.equals(row.get("name"), local.get("name"))) errors.add("Argument/local name disagreement: " + identity);
                row.put("originalSymbol", localIdentity); row.put("itemId", local.get("itemId"));
                row.put("originalName", local.get("originalName"));
                row.put("renameableLocal", !Boolean.FALSE.equals(local.get("renameableLocal")));
                for (String key : List.of("identityKind", "renameUnavailableReason"))
                    if (local.containsKey(key)) row.put(key, local.get(key));
            } else {
                Map<String,Object> row = new LinkedHashMap<>(local);
                row.put("identity", localIdentity); row.put("kind", "local");
                row.put("renameableLocal", !Boolean.FALSE.equals(local.get("renameableLocal")));
                if (variables.put(localIdentity, row) != null) errors.add("Duplicate local: " + localIdentity);
            }
        }
        // Fingerprint only original identities and names. Rendering lines, renamed type labels,
        // target IDs and provenance addresses can change without changing these variables.
        List<Object> pairs = new ArrayList<>();
        for (Map<String,Object> row : variables.values()) {
            Map<String,Object> pair = new LinkedHashMap<>();
            pair.put("identity", row.get("identity")); pair.put("name", row.get("name"));
            pair.put("localIdentity", row.get("originalSymbol"));
            pairs.add(pair);
        }
        Map<String,Object> out = new LinkedHashMap<>();
        out.put("schema", SCHEMA); out.put("originalMethod", original);
        out.put("hasCode", params.get("hasCode")); out.put("variables", new ArrayList<>(variables.values()));
        out.put("inventoryErrors", errors); out.put("receiverExcluded", true);
        out.put("inventoryFingerprint", digest(Json.write(Arrays.asList(original, params.get("hasCode"), pairs, errors))));
        return out;
    }

    private static File file(Workspace ws, String target, String original) throws OpException {
        File sidecar = ws.sidecarDirFor(target);
        if (sidecar == null) throw OpException.unsupported("Durable naming coverage requires a real target artifact", target);
        return new File(new File(sidecar, "naming-coverage"), digest(original) + ".json");
    }
    private static Map<String,Object> load(File file, String original) throws OpException {
        if (!file.exists()) return new LinkedHashMap<>();
        try {
            Map<String,Object> record = Json.parseObject(Files.readString(file.toPath()));
            if (!SCHEMA.equals(record.get("schema")) || !original.equals(record.get("originalMethod")) || !(record.get("records") instanceof Map<?,?>))
                throw new IllegalArgumentException("Wrong coverage schema or identity");
            return record;
        } catch (Exception e) {
            // Never turn unreadable evidence into an empty record, or overwrite it on retry.
            throw OpException.io("Cannot read naming coverage; evidence retained", file + ": " + e);
        }
    }
    private static void provenance(Workspace ws, String target, Map<String,Object> inventory) {
        String original = text(inventory.get("originalMethod"));
        List<Object> locals = new ArrayList<>();
        for (Object value : (List<?>) inventory.get("variables")) {
            Map<String,Object> row = map(value);
            row.put("originalMethod", original);
            if (row.get("originalSymbol") != null) locals.add(row);
        }
        Object decorated = ws.decorateForAgent("locals.list", Map.of("target",target,"symbol",original), "llm:coverage-reader", locals);
        if (decorated instanceof List<?> rows) {
            Map<String,Map<String,Object>> byIdentity = new HashMap<>();
            for (Object value : rows) { var row=map(value); byIdentity.put(text(row.get("identity")),row); }
            List<Object> enriched = new ArrayList<>();
            for (Object value : (List<?>)inventory.get("variables")) {
                var row=map(value); enriched.add(byIdentity.getOrDefault(text(row.get("identity")),row));
            }
            inventory.put("variables",enriched);
        }
    }
    private static Map<String,Object> evaluate(Map<String,Object> inventory, Map<String,Object> store, String wantedActor) {
        Map<String,Object> result = new LinkedHashMap<>(inventory);
        String actor = wantedActor == null ? text(store.get("latestActor")) : wantedActor;
        Map<String,Object> records = store.get("records") instanceof Map<?,?> value ? map(value) : Map.of();
        Map<String,Object> record = records.get(actor) instanceof Map<?,?> value ? map(value) : Map.of();
        boolean fresh = inventory.get("inventoryFingerprint").equals(record.get("inventoryFingerprint"));
        Map<String,Object> decisions = fresh && record.get("items") instanceof Map<?,?> value ? map(value) : Map.of();
        List<Object> rows = new ArrayList<>(); int missing=0, unresolved=0, renamed=0, retained=0;
        for (Object value : (List<?>)inventory.get("variables")) {
            Map<String,Object> row = new LinkedHashMap<>(map(value));
            Object decision = decisions.get(text(row.get("identity")));
            if (decision instanceof Map<?,?>) {
                row.put("assessment", decision);
                switch (text(map(decision).get("disposition"))) {
                    case "renamed" -> renamed++;
                    case "retained" -> retained++;
                    default -> unresolved++;
                }
            } else missing++;
            rows.add(row);
        }
        boolean errors = !((List<?>)inventory.get("inventoryErrors")).isEmpty();
        String status = record.isEmpty() ? "unreviewed" : !fresh ? "stale" : errors || missing>0 || unresolved>0 ? "needs_review" : "complete";
        result.put("variables", rows); result.put("status", status);
        result.put("counts", Map.of("total",rows.size(),"missing",missing,"unresolved",unresolved,"renamed",renamed,"retained",retained));
        result.put("record", record); result.put("recordedBy", actor);
        result.put("note", "Inventory coverage is checked; semantic claims remain subject to independent code review. Arguments without a local target cannot be renamed by annotate.");
        return result;
    }

    public static Map<String,Object> get(Workspace ws, String target, String symbol, String recordedBy) throws OpException {
        String tid=ws.resolveTargetIdOrActive(target);
        return ws.renameCoordinator().transact(tid, () -> {
            var inventory=inventory(ws.engineFor(tid),symbol); provenance(ws,tid,inventory);
            return evaluate(inventory,load(file(ws,tid,text(inventory.get("originalMethod"))),text(inventory.get("originalMethod"))),recordedBy);
        });
    }
    public static Map<String,Object> record(Workspace ws, String target, String symbol, String actor, Map<String,Object> request) throws OpException {
        String tid=ws.resolveTargetIdOrActive(target);
        return ws.withTargetWrite(tid, () -> {
            var inventory=inventory(ws.engineFor(tid),symbol); provenance(ws,tid,inventory);
            String original=text(inventory.get("originalMethod"));
            File file=file(ws,tid,original); var store=load(file,original);
            if (!inventory.get("inventoryFingerprint").equals(request.get("inventoryFingerprint")))
                throw OpException.busy("Variable inventory changed; read naming_coverage again before recording", original);
            String evidence=text(request.get("evidence"));
            if (evidence.isBlank() || !(request.get("items") instanceof List<?> items))
                throw OpException.syntax("Provide method evidence and items (an empty list explicitly reviews a variable-free method)", original);
            Map<String,Object> records=new LinkedHashMap<>(store.get("records") instanceof Map<?,?> value ? map(value) : Map.of());
            Map<String,Object> previous=records.get(actor) instanceof Map<?,?> value ? map(value) : Map.of();
            Map<String,Object> decisions=new LinkedHashMap<>();
            if (inventory.get("inventoryFingerprint").equals(previous.get("inventoryFingerprint")) && previous.get("items") instanceof Map<?,?> value)
                decisions.putAll(map(value));
            Map<String,Map<String,Object>> variables=new HashMap<>();
            for (Object value:(List<?>)inventory.get("variables")) { var row=map(value); variables.put(text(row.get("identity")),row); }
            Set<String> seen=new HashSet<>();
            for (Object value:items) {
                if (!(value instanceof Map<?,?>)) throw OpException.syntax("Each coverage item is an object",original);
                var item=map(value); String identity=text(item.get("identity")); var row=variables.get(identity);
                String disposition=text(item.get("disposition")), reason=text(item.get("evidence"));
                if (row==null || !seen.add(identity) || reason.isBlank() || !Set.of("renamed","retained","unresolved").contains(disposition))
                    throw OpException.syntax("Coverage requires unique current variable identities, a disposition and concrete evidence",identity);
                if (!"unresolved".equals(disposition) && text(row.get("name")).isBlank())
                    throw OpException.syntax("An unnamed argument must be unresolved",identity);
                if ("renamed".equals(disposition) && !(row.get("nameProvenance") instanceof Map<?,?>))
                    throw OpException.syntax("No active rename provenance for this variable; use retained with evidence or unresolved",identity);
                decisions.put(identity,Map.of("identity",identity,"disposition",disposition,"evidence",reason));
            }
            Map<String,Object> receipt=new LinkedHashMap<>();
            receipt.put("inventoryFingerprint",inventory.get("inventoryFingerprint"));receipt.put("items",decisions);
            receipt.put("evidence",evidence);receipt.put("recordedBy",actor);receipt.put("at",java.time.Instant.now().toString());
            records.put(actor,receipt);
            Map<String,Object> saved=Map.of("schema",SCHEMA,"originalMethod",original,"records",records,"latestActor",actor);
            if (!SidecarIO.writeAtomic(file,Json.write(saved),"naming coverage")) throw OpException.io("Coverage was not acknowledged durably; read it before retrying",original);
            return evaluate(inventory,saved,actor);
        });
    }
}
