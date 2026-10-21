package jopt.opt;

import java.util.LinkedHashMap;
import java.util.Map;

/** Named event counts ("branches folded", ...) collected by passes for the report. */
public final class Counters {
    private final Map<String, Long> counts = new LinkedHashMap<>();

    public void add(String what) {
        add(what, 1);
    }

    public void add(String what, long n) {
        if (n != 0) counts.merge(what, n, Long::sum);
    }

    public void addAll(Counters other) {
        other.counts.forEach(this::add);
    }

    public boolean isEmpty() {
        return counts.isEmpty();
    }

    public Map<String, Long> asMap() {
        return counts;
    }
}
