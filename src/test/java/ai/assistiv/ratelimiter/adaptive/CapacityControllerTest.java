package ai.assistiv.ratelimiter.adaptive;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class CapacityControllerTest {

    private static final long MS = 1_000_000L;
    private static final Duration INTERVAL = Duration.ofSeconds(1);
    private static final Duration HALF_LIFE = Duration.ofSeconds(30);
    private static final Duration REFILL_PERIOD = Duration.ofSeconds(60);
    private static final double WINDOW_SECONDS = 30;
    private static final int WARMUP = 5;

    private CapacityController controller(long min, long max, Duration ceiling) {
        return new CapacityController(min, max, 3.0, HALF_LIFE, INTERVAL, WARMUP, ceiling,
                REFILL_PERIOD);
    }

    private CapacityController controller(long min, long max) {
        return controller(min, max, Duration.ZERO);
    }

    /** A snapshot saturating whatever budget the controller currently holds. */
    private HealthSnapshot saturated(CapacityController controller, long p99Ms, double errorRate) {
        long admitted = (long) Math.ceil(controller.budget() * (WINDOW_SECONDS / 60.0));
        return new HealthSnapshot(p99Ms * MS, p99Ms * MS, errorRate, admitted, 0, 0, WINDOW_SECONDS);
    }

    private HealthSnapshot idle(long p99Ms) {
        return new HealthSnapshot(p99Ms * MS, p99Ms * MS, 0, 1, 0, 0, WINDOW_SECONDS);
    }

    private void runHealthy(CapacityController controller, int ticks, long p99Ms) {
        for (int i = 0; i < ticks; i++) {
            controller.tick(saturated(controller, p99Ms, 0));
        }
    }

    @Test
    void startsAtTheFloorAndEarnsTheRest() {
        CapacityController controller = controller(50, 5000);
        assertThat(controller.budget()).isEqualTo(50);
    }

    @Test
    void slowStartDoublesWhileHealthyAndSaturated() {
        CapacityController controller = controller(50, 5000);

        runHealthy(controller, 3, 10);

        assertThat(controller.budget()).isEqualTo(400);  // 50 -> 100 -> 200 -> 400
        assertThat(controller.state().phase()).isEqualTo(CapacityController.Phase.SLOW_START);
    }

    @Test
    void holdsTheBudgetWhenThereIsNoTraffic() {
        CapacityController controller = controller(50, 5000);
        runHealthy(controller, 2, 10);
        double budget = controller.budget();

        controller.tick(HealthSnapshot.EMPTY);

        assertThat(controller.budget()).isEqualTo(budget);
    }

    @Test
    void doesNotProbeUpwardWhileTheBudgetSitsUnused() {
        CapacityController controller = controller(50, 5000);

        for (int i = 0; i < 20; i++) {
            controller.tick(idle(10));
        }

        // Otherwise an idle service would drift to the ceiling and then admit a
        // flood the moment traffic returned.
        assertThat(controller.budget()).isEqualTo(50);
    }

    @Test
    void halvesTheBudgetWhenLatencyDeviatesFromTheLearnedBaseline() {
        CapacityController controller = controller(50, 5000);
        runHealthy(controller, 10, 10);   // learn that ~10ms is normal
        double before = controller.budget();

        controller.tick(saturated(controller, 500, 0));

        assertThat(controller.budget()).isEqualTo(before * 0.5);
        assertThat(controller.state().pressured()).isTrue();
        assertThat(controller.state().phase())
                .isEqualTo(CapacityController.Phase.CONGESTION_AVOIDANCE);
    }

    @Test
    void halvesTheBudgetWhenErrorsDeviate() {
        CapacityController controller = controller(50, 5000);
        runHealthy(controller, 10, 10);
        double before = controller.budget();

        controller.tick(saturated(controller, 10, 0.5));

        assertThat(controller.budget()).isEqualTo(before * 0.5);
    }

    @Test
    void probesGentlyAfterTheFirstBreachInsteadOfDoublingAgain() {
        CapacityController controller = controller(50, 5000);
        runHealthy(controller, 10, 10);
        controller.tick(saturated(controller, 500, 0));   // breach, exits slow start
        double afterBackoff = controller.budget();

        controller.tick(saturated(controller, 10, 0));

        assertThat(controller.budget()).isCloseTo(afterBackoff * 1.05,
                org.assertj.core.data.Offset.offset(1.0));
    }

    @Test
    void doesNotLearnADegradedStateAsNormal() {
        CapacityController controller = controller(50, 5000);
        runHealthy(controller, 10, 10);
        long learnedBefore = controller.state().learnedLatencyThresholdNanos();

        // Sustained degradation. If the baseline absorbed it, pressure would
        // quietly stop being reported and the limiter would give up protecting.
        for (int i = 0; i < 50; i++) {
            controller.tick(saturated(controller, 500, 0));
        }

        assertThat(controller.state().pressured()).isTrue();
        assertThat(controller.state().learnedLatencyThresholdNanos())
                .isEqualTo(learnedBefore);
        assertThat(controller.budget()).isEqualTo(50);  // pinned at the floor
    }

    @Test
    void neverExceedsTheConfiguredCeiling() {
        CapacityController controller = controller(50, 200);

        runHealthy(controller, 20, 10);

        assertThat(controller.budget()).isEqualTo(200);
        assertThat(controller.healthFactor()).isEqualTo(1.0);
    }

    @Test
    void neverFallsBelowTheConfiguredFloor() {
        CapacityController controller = controller(50, 5000);
        runHealthy(controller, 10, 10);

        for (int i = 0; i < 30; i++) {
            controller.tick(saturated(controller, 500, 0));
        }

        assertThat(controller.budget()).isEqualTo(50);
    }

    @Test
    void anAbsoluteCeilingOverridesWhatTheServiceConsidersNormal() {
        // A service that has always taken 500ms would learn that as its normal
        // and never back off. A declared SLO says otherwise.
        CapacityController controller = controller(50, 5000, Duration.ofMillis(100));

        for (int i = 0; i < 20; i++) {
            controller.tick(saturated(controller, 500, 0));
        }

        assertThat(controller.state().pressured()).isTrue();
        assertThat(controller.budget()).isEqualTo(50);
    }

    @Test
    void withoutAnAbsoluteCeilingASlowServiceIsJudgedOnItsOwnHistory() {
        CapacityController controller = controller(50, 5000);

        runHealthy(controller, 20, 500);  // consistently slow, but consistent

        assertThat(controller.state().pressured()).isFalse();
        assertThat(controller.budget()).isGreaterThan(50);
    }

    @Test
    void reportsUtilizationAgainstTheCurrentBudget() {
        CapacityController controller = controller(100, 5000);
        // Budget 100 per 60s; a 30s window may therefore admit 50.
        HealthSnapshot half = new HealthSnapshot(MS, MS, 0, 25, 0, 0, WINDOW_SECONDS);

        assertThat(controller.utilization(half)).isEqualTo(0.5);
    }
}
