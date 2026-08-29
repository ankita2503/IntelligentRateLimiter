package ai.assistiv.ratelimiter.adaptive;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class EwmaBaselineTest {

    private static final Duration HALF_LIFE = Duration.ofSeconds(30);
    private static final Duration INTERVAL = Duration.ofSeconds(1);

    private EwmaBaseline baseline(int warmup) {
        return EwmaBaseline.withHalfLife(HALF_LIFE, INTERVAL, warmup);
    }

    @Test
    void learnsTheCentreOfASteadySignal() {
        EwmaBaseline baseline = baseline(5);
        for (int i = 0; i < 200; i++) {
            baseline.observe(100);
        }
        assertThat(baseline.mean()).isCloseTo(100, org.assertj.core.data.Offset.offset(0.5));
    }

    @Test
    void reportsNoAnomalyWhileWarmingUp() {
        EwmaBaseline baseline = baseline(10);
        baseline.observe(10);

        // A wild value this early is not evidence of anything.
        assertThat(baseline.zScore(10_000)).isZero();
        assertThat(baseline.isReady()).isFalse();
    }

    @Test
    void flagsAValueFarAboveTheLearnedBaseline() {
        EwmaBaseline baseline = baseline(5);
        for (int i = 0; i < 100; i++) {
            baseline.observe(100 + (i % 5));  // 100..104, mild jitter
        }
        assertThat(baseline.zScore(1000)).isGreaterThan(3);
    }

    @Test
    void toleratesOrdinaryJitterOnASteadySignal() {
        EwmaBaseline baseline = baseline(5);
        for (int i = 0; i < 100; i++) {
            baseline.observe(100 + (i % 5));
        }
        assertThat(baseline.zScore(103)).isLessThan(3);
    }

    @Test
    void aPerfectlySteadySignalStillToleratesSmallDeviations() {
        // Zero variance would make every deviation infinitely significant; the
        // dispersion floor is what stops that.
        EwmaBaseline baseline = baseline(5);
        for (int i = 0; i < 100; i++) {
            baseline.observe(100);
        }
        assertThat(baseline.zScore(101)).isLessThan(3);
        assertThat(baseline.zScore(200)).isGreaterThan(3);
    }

    @Test
    void treatsValuesBelowBaselineAsNormal() {
        EwmaBaseline baseline = baseline(5);
        for (int i = 0; i < 50; i++) {
            baseline.observe(100);
        }
        assertThat(baseline.zScore(1)).isZero();
    }

    @Test
    void aShorterHalfLifeForgetsFaster() {
        EwmaBaseline fast = EwmaBaseline.withHalfLife(Duration.ofSeconds(5), INTERVAL, 1);
        EwmaBaseline slow = EwmaBaseline.withHalfLife(Duration.ofMinutes(10), INTERVAL, 1);
        for (int i = 0; i < 30; i++) {
            fast.observe(100);
            slow.observe(100);
        }
        for (int i = 0; i < 10; i++) {
            fast.observe(200);
            slow.observe(200);
        }
        assertThat(fast.mean()).isGreaterThan(slow.mean());
    }
}
