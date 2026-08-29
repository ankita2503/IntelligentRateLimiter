package ai.assistiv.ratelimiter.adaptive;

import java.util.concurrent.atomic.AtomicLongArray;

/**
 * A fixed-memory latency histogram with exponentially widening buckets.
 *
 * <p>Percentiles are what a controller needs and a mean is not: an average hides
 * the tail that actually breaches an SLO. Storing every sample would be
 * unbounded, so values are bucketed on a log scale — each bucket is 20% wider
 * than the last, which bounds the reported percentile's error at roughly that
 * same 20% while covering microseconds to a minute in ~100 buckets.
 *
 * <p>Safe for concurrent recording.
 */
public final class LatencyHistogram {

    private static final double GROWTH = 1.2;
    private static final long FLOOR_NANOS = 1_000L;              // 1µs
    private static final long CEILING_NANOS = 60_000_000_000L;   // 60s
    private static final long[] UPPER_BOUNDS = buildBounds();

    private final AtomicLongArray counts = new AtomicLongArray(UPPER_BOUNDS.length);

    private static long[] buildBounds() {
        int size = (int) Math.ceil(Math.log((double) CEILING_NANOS / FLOOR_NANOS) / Math.log(GROWTH)) + 1;
        long[] bounds = new long[size];
        double bound = FLOOR_NANOS;
        for (int i = 0; i < size; i++) {
            bound *= GROWTH;
            bounds[i] = (long) bound;
        }
        return bounds;
    }

    public void record(long nanos) {
        counts.incrementAndGet(indexOf(nanos));
    }

    /**
     * The upper bound of the bucket holding the {@code p}-th percentile sample,
     * or 0 when nothing has been recorded.
     *
     * @param p percentile in (0, 1], e.g. 0.99
     */
    public long percentile(double p) {
        long total = totalCount();
        if (total == 0) {
            return 0;
        }
        // Rank of the sample we want, counting from the fastest.
        long rank = (long) Math.ceil(p * total);
        long seen = 0;
        for (int i = 0; i < UPPER_BOUNDS.length; i++) {
            seen += counts.get(i);
            if (seen >= rank) {
                return UPPER_BOUNDS[i];
            }
        }
        return UPPER_BOUNDS[UPPER_BOUNDS.length - 1];
    }

    public long totalCount() {
        long total = 0;
        for (int i = 0; i < UPPER_BOUNDS.length; i++) {
            total += counts.get(i);
        }
        return total;
    }

    public void reset() {
        for (int i = 0; i < UPPER_BOUNDS.length; i++) {
            counts.set(i, 0);
        }
    }

    /** Folds this histogram's counts into {@code target}. */
    void addTo(long[] target) {
        for (int i = 0; i < UPPER_BOUNDS.length; i++) {
            target[i] += counts.get(i);
        }
    }

    static int bucketCount() {
        return UPPER_BOUNDS.length;
    }

    /** Reads a percentile out of merged bucket counts. */
    static long percentileOf(long[] merged, double p) {
        long total = 0;
        for (long count : merged) {
            total += count;
        }
        if (total == 0) {
            return 0;
        }
        long rank = (long) Math.ceil(p * total);
        long seen = 0;
        for (int i = 0; i < merged.length; i++) {
            seen += merged[i];
            if (seen >= rank) {
                return UPPER_BOUNDS[i];
            }
        }
        return UPPER_BOUNDS[merged.length - 1];
    }

    private static int indexOf(long nanos) {
        if (nanos <= FLOOR_NANOS) {
            return 0;
        }
        if (nanos >= CEILING_NANOS) {
            return UPPER_BOUNDS.length - 1;
        }
        int index = (int) Math.floor(Math.log((double) nanos / FLOOR_NANOS) / Math.log(GROWTH));
        return Math.min(Math.max(index, 0), UPPER_BOUNDS.length - 1);
    }
}
