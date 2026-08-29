package ai.assistiv.ratelimiter.adaptive;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class LatencyHistogramTest {

    private static final long MS = 1_000_000L;

    @Test
    void reportsZeroWhenEmpty() {
        assertThat(new LatencyHistogram().percentile(0.99)).isZero();
    }

    @Test
    void estimatesPercentilesWithinBucketWidth() {
        LatencyHistogram histogram = new LatencyHistogram();
        for (int i = 1; i <= 1000; i++) {
            histogram.record(i * MS);  // uniform 1ms..1000ms
        }

        // Buckets widen by 20%, so the estimate is an upper bound within that.
        assertThat(histogram.percentile(0.50)).isBetween(500 * MS, 620 * MS);
        assertThat(histogram.percentile(0.99)).isBetween(990 * MS, 1200 * MS);
    }

    @Test
    void theTailIsNotHiddenByTheBulk() {
        LatencyHistogram histogram = new LatencyHistogram();
        for (int i = 0; i < 990; i++) {
            histogram.record(5 * MS);
        }
        for (int i = 0; i < 10; i++) {
            histogram.record(2000 * MS);
        }

        assertThat(histogram.percentile(0.50)).isLessThan(10 * MS);
        assertThat(histogram.percentile(0.999)).isGreaterThan(1500 * MS);
    }

    @Test
    void clampsValuesOutsideTheTrackedRange() {
        LatencyHistogram histogram = new LatencyHistogram();
        histogram.record(1);                     // below the floor
        histogram.record(600_000_000_000L);      // ten minutes, above the ceiling

        assertThat(histogram.totalCount()).isEqualTo(2);
        assertThat(histogram.percentile(1.0)).isGreaterThanOrEqualTo(60_000_000_000L);
    }

    @Test
    void resetClearsEverything() {
        LatencyHistogram histogram = new LatencyHistogram();
        histogram.record(5 * MS);
        histogram.reset();

        assertThat(histogram.totalCount()).isZero();
        assertThat(histogram.percentile(0.99)).isZero();
    }
}
