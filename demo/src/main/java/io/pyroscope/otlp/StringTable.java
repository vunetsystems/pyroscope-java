package io.pyroscope.otlp;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Interns strings into a flat indexed table — required by the OTel Profiles proto.
 * All string fields in Profile (function names, filenames, event types, units) are
 * stored as long indices into this table rather than inline strings.
 * Index 0 is always the empty string, as required by the pprof-derived format.
 */
final class StringTable {

    private final Map<String, Integer> index = new LinkedHashMap<>();
    private final List<String> table = new ArrayList<>();

    StringTable() {
        intern(""); // index 0 must always be the empty string
    }

    /** Returns the index for {@code s}, inserting it if not already present. */
    int intern(String s) {
        return index.computeIfAbsent(s, k -> {
            int i = table.size();
            table.add(k);
            return i;
        });
    }

    List<String> getTable() {
        return table;
    }
}