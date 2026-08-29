package ai.assistiv.ratelimiter.adaptive;

import java.time.Duration;

/**
 * Finds the system's real capacity instead of being told it.
 *
 * <p>The controller owns a single number — the global admission budget, in
 * requests per refill period — and moves it on a fixed tick using the shape of
 * TCP congestion control:
 *
 * <ul>
 *   <li><b>Slow start.</b> From cold, double the budget each tick while the
 *       system stays healthy, until the first sign of pressure. This is how the
 *       limiter discovers capacity nobody configured.</li>
 *   <li><b>Congestion avoidance.</b> After the first breach, probe upward in
 *       small additive steps — cautious about giving capacity back.</li>
 *   <li><b>Multiplicative decrease.</b> On pressure, halve immediately. Slow to
 *       grant, fast to protect.</li>
 * </ul>
 *
 * <p>"Pressure" is not a configured latency number. Each signal carries an
 * {@link EwmaBaseline} of its own recent history, and a breach is an observation
 * more than {@code sigma} standard deviations above that baseline — so a service
 * that normally answers in 4ms and one that normally takes 800ms both get a
 * threshold that fits them. An optional absolute ceiling can be layered on top
 * when a hard SLO genuinely exists.
 *
 * <p>Two rules keep this stable:
 * <ul>
 *   <li><b>Learn only while healthy.</b> Baselines are updated on healthy ticks
 *       only. Learning during a breach would normalise the degradation and the
 *       limiter would stop reacting to it.</li>
 *   <li><b>Probe only while saturated.</b> The budget grows only when traffic is
 *       actually using most of it. Otherwise an idle service would drift to the
 *       maximum and admit a flood the moment traffic returned.</li>
 * </ul>
 */
public final class CapacityController {

    /** Multiplicative decrease on pressure. Halving is the classic AIMD choice. */
    private static final double BACKOFF = 0.5;

    /** Additive increase per tick, as a fraction of the current budget. */
    private static final double PROBE_RATIO = 0.05;

    /** Fraction of the budget that must be in use before probing upward. */
    private static final double PROBE_UTILIZATION = 0.8;

    public enum Phase {
        /** Doubling upward; capacity is still unknown. */
        SLOW_START,
        /** Capacity has been found once; probe gently. */
        CONGESTION_AVOIDANCE
    }

    private final double minBudget;
    private final double maxBudget;
    private final double sigma;
    private final long latencyCeilingNanos;
    private final double refillPeriodSeconds;
    private final EwmaBaseline latencyBaseline;
    private final EwmaBaseline errorBaseline;

    private volatile double budget;
    private volatile Phase phase = Phase.SLOW_START;
    private volatile double lastLatencyZ;
    private volatile double lastErrorZ;
    private volatile boolean pressured;
    private volatile long ticks;

    public CapacityController(double minBudget, double maxBudget, double sigma,
                              Duration baselineHalfLife, Duration controlInterval,
                              int warmupTicks, Duration latencyCeiling, Duration refillPeriod) {
        if (minBudget <= 0 || maxBudget < minBudget) {
            throw new IllegalArgumentException("require 0 < minBudget <= maxBudget");
        }
        this.minBudget = minBudget;
        this.maxBudget = maxBudget;
        this.sigma = sigma;
        this.latencyCeilingNanos = latencyCeiling == null ? 0 : latencyCeiling.toNanos();
        this.refillPeriodSeconds = refillPeriod.toNanos() / 1_000_000_000.0;
        this.latencyBaseline = EwmaBaseline.withHalfLife(baselineHalfLife, controlInterval, warmupTicks);
        this.errorBaseline = EwmaBaseline.withHalfLife(baselineHalfLife, controlInterval, warmupTicks);
        // Start at the floor and earn the rest.
        this.budget = minBudget;
    }

    /** Advances the control loop by one tick. */
    public void tick(HealthSnapshot snapshot) {
        ticks++;
        if (!snapshot.hasTraffic()) {
            // No signal. Hold the budget rather than guessing in either direction.
            pressured = false;
            return;
        }

        double latencyZ = latencyBaseline.zScore(snapshot.p99LatencyNanos());
        double errorZ = errorBaseline.zScore(snapshot.errorRate());
        boolean ceilingBreached = latencyCeilingNanos > 0
                && snapshot.p99LatencyNanos() > latencyCeilingNanos;

        lastLatencyZ = latencyZ;
        lastErrorZ = errorZ;
        pressured = ceilingBreached || latencyZ > sigma || errorZ > sigma;

        if (pressured) {
            budget = Math.max(minBudget, budget * BACKOFF);
            phase = Phase.CONGESTION_AVOIDANCE;
            return;
        }

        latencyBaseline.observe(snapshot.p99LatencyNanos());
        errorBaseline.observe(snapshot.errorRate());

        if (utilization(snapshot) >= PROBE_UTILIZATION) {
            budget = phase == Phase.SLOW_START
                    ? budget * 2
                    : budget + Math.max(1, budget * PROBE_RATIO);
            budget = Math.min(maxBudget, budget);
        }
    }

    /** Current global admission budget, in requests per refill period. */
    public double budget() {
        return budget;
    }

    /** The budget as a fraction of the maximum: the health factor. */
    public double healthFactor() {
        return budget / maxBudget;
    }

    /** How much of the budget the observed traffic is actually using. */
    public double utilization(HealthSnapshot snapshot) {
        double allowedInWindow = budget * (snapshot.windowSeconds() / refillPeriodSeconds);
        return allowedInWindow <= 0 ? 0 : snapshot.admitted() / allowedInWindow;
    }

    public State state() {
        return new State(budget, minBudget, maxBudget, phase, pressured,
                (long) latencyBaseline.mean(),
                (long) (latencyBaseline.mean() + sigma * latencyBaseline.stdDev()),
                errorBaseline.mean(),
                errorBaseline.mean() + sigma * errorBaseline.stdDev(),
                lastLatencyZ, lastErrorZ, latencyBaseline.isReady(), ticks);
    }

    /**
     * A readable view of the controller for the state endpoint. The thresholds
     * are derived, not configured — this is what the limiter has learned.
     */
    public record State(
            double budget,
            double minBudget,
            double maxBudget,
            Phase phase,
            boolean pressured,
            long learnedLatencyMeanNanos,
            long learnedLatencyThresholdNanos,
            double learnedErrorMean,
            double learnedErrorThreshold,
            double lastLatencyZScore,
            double lastErrorZScore,
            boolean baselineReady,
            long ticks) {
    }
}
