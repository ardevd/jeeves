package dev.workbench.registry;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The single op registry (MCP_SPEC §4, WORKBENCH_SPEC §1 "Action").
 * UI, MCP, CLI and VSCode ext all consume this; palette/MCP tool lists are
 * generated from it. An op that exists but isn't here is a build error
 * (enforced by the parity test).
 */
public final class Registry {

    private final Map<String, Op> ops = new LinkedHashMap<>();

    public synchronized Registry register(Op op) {
        if (op.name() == null || op.name().isBlank())
            throw new IllegalArgumentException("op name required");
        if (ops.containsKey(op.name()))
            throw new IllegalArgumentException("duplicate op: " + op.name());
        ops.put(op.name(), op);
        return this;
    }

    /** Swap a registered op for a wrapper of itself (the batch form of the write ops). */
    public synchronized Registry replace(Op op) {
        if (!ops.containsKey(op.name())) throw new IllegalArgumentException("no such op to replace: " + op.name());
        ops.put(op.name(), op);
        return this;
    }

    public synchronized Op get(String name) {
        return ops.get(name);
    }

    public synchronized boolean has(String name) {
        return ops.containsKey(name);
    }

    public synchronized List<Op> all() {
        return new ArrayList<>(ops.values());
    }

    public synchronized int size() {
        return ops.size();
    }
}
