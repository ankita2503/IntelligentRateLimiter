package ai.assistiv.ratelimiter.config;

import java.time.Duration;

/**
 * Settings for the adaptive layer.
 *
 * <p>Deliberately small. Anything the limiter can learn from traffic — what
 * counts as slow, what a client's normal rate is, how many tenants are sharing
 * the system, how much capacity exists — is not here. What remains is of three
 * kinds: <b>safety rails</b> the controller may not cross, <b>time constants</b>
 * saying how fast to react and how long to remember, and <b>sensitivity</b>.
 */
public class AdaptiveProperties {

    private boolean enabled = true;

    /** How often the control loop runs. Also the sampling interval for baselines. */
    private Duration controlInterval = Duration.ofSeconds(1);

    /** Observation window for latency, errors, and throughput. */
    private Duration window = Duration.ofSeconds(30);

    /** Safety rail: the budget never falls below this, however bad things look. */
    private long minBudget = 50;

    /** Safety rail: the budget never rises above this, however good things look. */
    private long maxBudget = 5000;

    /** Safety rail: no individual client is squeezed below this. */
    private long minClientLimit = 5;

    /** Standard deviations above baseline that count as an anomaly. */
    private double deviationSigma = 3.0;

    /** How long baselines remember. Shorter adapts faster and is twitchier. */
    private Duration baselineHalfLife = Duration.ofMinutes(5);

    /** Control ticks observed before z-scores are trusted. */
    private int warmupTicks = 20;

    /**
     * Optional hard latency ceiling. Zero means the limiter relies purely on the
     * baseline it learns; set it when a real SLO exists and must not be crossed
     * even if the service has always been slow.
     */
    private Duration latencyCeiling = Duration.ZERO;

    /** A client seen within this window counts toward the fair-share population. */
    private Duration clientActivityWindow = Duration.ofMinutes(1);

    /** Client profiles idle for longer than this are forgotten. */
    private Duration clientIdleTtl = Duration.ofMinutes(15);

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Duration getControlInterval() {
        return controlInterval;
    }

    public void setControlInterval(Duration controlInterval) {
        this.controlInterval = controlInterval;
    }

    public Duration getWindow() {
        return window;
    }

    public void setWindow(Duration window) {
        this.window = window;
    }

    public long getMinBudget() {
        return minBudget;
    }

    public void setMinBudget(long minBudget) {
        this.minBudget = minBudget;
    }

    public long getMaxBudget() {
        return maxBudget;
    }

    public void setMaxBudget(long maxBudget) {
        this.maxBudget = maxBudget;
    }

    public long getMinClientLimit() {
        return minClientLimit;
    }

    public void setMinClientLimit(long minClientLimit) {
        this.minClientLimit = minClientLimit;
    }

    public double getDeviationSigma() {
        return deviationSigma;
    }

    public void setDeviationSigma(double deviationSigma) {
        this.deviationSigma = deviationSigma;
    }

    public Duration getBaselineHalfLife() {
        return baselineHalfLife;
    }

    public void setBaselineHalfLife(Duration baselineHalfLife) {
        this.baselineHalfLife = baselineHalfLife;
    }

    public int getWarmupTicks() {
        return warmupTicks;
    }

    public void setWarmupTicks(int warmupTicks) {
        this.warmupTicks = warmupTicks;
    }

    public Duration getLatencyCeiling() {
        return latencyCeiling;
    }

    public void setLatencyCeiling(Duration latencyCeiling) {
        this.latencyCeiling = latencyCeiling;
    }

    public Duration getClientActivityWindow() {
        return clientActivityWindow;
    }

    public void setClientActivityWindow(Duration clientActivityWindow) {
        this.clientActivityWindow = clientActivityWindow;
    }

    public Duration getClientIdleTtl() {
        return clientIdleTtl;
    }

    public void setClientIdleTtl(Duration clientIdleTtl) {
        this.clientIdleTtl = clientIdleTtl;
    }
}
