package com.taomish.comparedbtool;

import java.util.List;

/** Conservative accounting for retained comparison maps; not a measurement of the JVM's entire heap. */
final class MemoryBudget {
    private final long limit;
    private long groups, partition;
    MemoryBudget(int megabytes) { limit = Math.multiplyExact((long) megabytes, 1024L * 1024); }
    void nextPartition() { partition = 0; }
    void row(List<String> row, List<String> key) {
        partition += estimate(row) + estimate(key) + 256;
        check();
    }
    void group(List<String> values) { groups += estimate(values) + 192; check(); }
    private void check() {
        if (groups + partition > limit) throw new ToolException("Comparison memory budget exceeded (estimated "
            + (groups + partition) + " bytes; limit " + limit + "). Increase --partitions or --max-partition-mb; "
            + "reduce high-cardinality breakdowns. No rows or group counts were silently discarded.");
    }
    private static long estimate(List<String> values) {
        long bytes = 32L + values.size() * 8L;
        for (String value : values) if (value != null) bytes += 48L + value.length() * 2L;
        return bytes;
    }
}
