package ai.assistiv.ratelimiter.adaptive;

import static org.assertj.core.api.Assertions.assertThat;

import ai.assistiv.ratelimiter.core.FakeTimeSource;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class SlidingWindowTrafficMetricsTest {

    private static final long MS = 1_000_000L;
    private static final Duration WINDOW = Duration.ofSeconds(10);

    private final FakeTimeSource time = new FakeTimeSource();
    private final SlidingWindowTrafficMetrics metrics =
            new SlidingWindowTrafficMetrics(WINDOW, 10, time);

    @Test
    void anEmptyWindowReportsNoTraffic() {
        assertThat(metrics.snapshot().hasTraffic()).isFalse();
    }

    @Test
    void reportsLatencyErrorRateAndThroughput() {
        for (int i = 0; i < 100; i++) {
            metrics.requestStarted();
            metrics.recordCompletion(10 * MS, i < 5);  // 5% failures
            metrics.requestFinished();
        }

        HealthSnapshot snapshot = metrics.snapshot();
        assertThat(snapshot.hasTraffic()).isTrue();
        assertThat(snapshot.admitted()).isEqualTo(100);
        assertThat(snapshot.errorRate()).isEqualTo(0.05);
        assertThat(snapshot.p99LatencyNanos()).isBetween(10 * MS, 13 * MS);
        assertThat(snapshot.throughputPerSecond()).isEqualTo(10.0);  // 100 over 10s
    }

    @Test
    void trafficAgesOutOfTheWindow() {
        for (int i = 0; i < 50; i++) {
            metrics.requestStarted();
            metrics.recordCompletion(10 * MS, false);
            metrics.requestFinished();
        }
        assertThat(metrics.snapshot().admitted()).isEqualTo(50);

        time.advance(WINDOW.plusSeconds(1));

        assertThat(metrics.snapshot().hasTraffic()).isFalse();
    }

    @Test
    void olderTrafficLeavesTheWindowGradually() {
        for (int i = 0; i < 10; i++) {
            metrics.requestStarted();
            metrics.recordCompletion(10 * MS, false);
            metrics.requestFinished();
        }

        // Half the window later, the first batch is still inside it.
        time.advance(Duration.ofSeconds(5));
        for (int i = 0; i < 10; i++) {
            metrics.requestStarted();
            metrics.recordCompletion(10 * MS, false);
            metrics.requestFinished();
        }
        assertThat(metrics.snapshot().admitted()).isEqualTo(20);

        // Another six seconds and only the second batch remains.
        time.advance(Duration.ofSeconds(6));
        assertThat(metrics.snapshot().admitted()).isEqualTo(10);
    }

    @Test
    void tracksInFlightRequests() {
        metrics.requestStarted();
        metrics.requestStarted();
        assertThat(metrics.snapshot().inFlight()).isEqualTo(2);

        metrics.requestFinished();
        assertThat(metrics.snapshot().inFlight()).isEqualTo(1);
    }

    @Test
    void countsRejectionsSeparatelyFromAdmissions() {
        metrics.requestStarted();
        metrics.recordCompletion(MS, false);
        metrics.recordRejection();
        metrics.recordRejection();

        HealthSnapshot snapshot = metrics.snapshot();
        assertThat(snapshot.admitted()).isEqualTo(1);
        assertThat(snapshot.rejected()).isEqualTo(2);
    }
}
