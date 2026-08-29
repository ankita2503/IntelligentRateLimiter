package ai.assistiv.ratelimiter.adaptive;

import java.util.concurrent.atomic.LongAdder;

/**
 * What one client's traffic normally looks like.
 *
 * <p>Each client is judged against its own history rather than a global
 * threshold. A key that has quietly polled twice a minute for a week and
 * suddenly sends 200 requests a second is anomalous even though 200/s may be
 * nowhere near the system limit; a key that always sends 200/s is not.
 */
public final class ClientProfile {

    /** Reputation never falls below this: a suspected abuser is throttled, not cut off. */
    private static final double MIN_REPUTATION = 0.1;

    private final EwmaBaseline rateBaseline;
    private final LongAdder requestsThisInterval = new LongAdder();

    private volatile long lastSeenNanos;
    private volatile double reputation = 1.0;
    private volatile double lastRatePerSecond;
    private volatile double lastZScore;

    ClientProfile(EwmaBaseline rateBaseline, long createdAtNanos) {
        this.rateBaseline = rateBaseline;
        this.lastSeenNanos = createdAtNanos;
    }

    void recordRequest(long nowNanos) {
        requestsThisInterval.increment();
        lastSeenNanos = nowNanos;
    }

    /**
     * Folds the interval just ended into this client's baseline and rescores it.
     *
     * @param intervalSeconds length of the interval being closed
     * @param sigma           deviations above baseline that count as anomalous
     */
    void closeInterval(double intervalSeconds, double sigma) {
        long requests = requestsThisInterval.sumThenReset();
        if (requests == 0) {
            // Silence is not evidence about this client's rate. Feeding zeros in
            // would drag the baseline down and make its next normal burst look
            // like an attack.
            return;
        }

        double rate = requests / intervalSeconds;
        double z = rateBaseline.zScore(rate);
        lastRatePerSecond = rate;
        lastZScore = z;
        reputation = z <= sigma ? 1.0 : Math.max(MIN_REPUTATION, sigma / z);

        // Learn only from behaviour that looks like this client's own normal,
        // so a sustained flood never becomes the baseline it is judged against.
        if (z <= sigma) {
            rateBaseline.observe(rate);
        }
    }

    /** Multiplier in [0.1, 1] applied to this client's share of the budget. */
    public double reputation() {
        return reputation;
    }

    public long lastSeenNanos() {
        return lastSeenNanos;
    }

    public double lastRatePerSecond() {
        return lastRatePerSecond;
    }

    public double lastZScore() {
        return lastZScore;
    }

    public double baselineRatePerSecond() {
        return rateBaseline.mean();
    }

    public boolean isProfiled() {
        return rateBaseline.isReady();
    }
}
