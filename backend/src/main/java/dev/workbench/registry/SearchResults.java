package dev.workbench.registry;

import dev.workbench.engine.Engine;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Preserve the raw search list contract while carrying coverage into its envelope. */
public final class SearchResults extends ArrayList<Object> {
    public final Map<String, Object> coverage;

    public SearchResults(Collection<?> groups, Map<String, Object> coverage) {
        super(groups); this.coverage = coverage;
    }

    public static SearchResults search(Engine engine, String query, Map<String, Object> opts) throws OpException {
        Map<String, Object> before = engine.searchIndexStatus();
        var groups = engine.search(query, opts);
        Map<String, Object> after = engine.searchIndexStatus();
        Map<String, Object> coverage = new LinkedHashMap<>(after);
        boolean changed = !Objects.equals(before.get("revision"), after.get("revision"));
        boolean partial = !Boolean.FALSE.equals(before.get("partialCoverage"))
                || !Boolean.FALSE.equals(after.get("partialCoverage")) || changed;
        coverage.put("partialCoverage", partial);
        coverage.put("changedDuringSearch", changed);
        coverage.put("appliesTo", "decompiled code and comments; strings use the string pool");
        coverage.put("note", partial
                ? "Partial text coverage: zero matches do not establish absence. Resolve a known symbol and decompile it for its current text."
                : "Text coverage was complete at both status observations; concurrent edits are not a transaction snapshot.");
        return new SearchResults(groups, coverage);
    }
}
