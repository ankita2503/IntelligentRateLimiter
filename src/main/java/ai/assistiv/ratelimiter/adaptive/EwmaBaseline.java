package ai.assistiv.ratelimiter.adaptive;

import java.time.Duration;

/**
 * An exponentially weighted estimate of what "normal" looks like for one signal.
 *
 * <p>Tracks a running mean and variance, so callers can ask how many standard
 * deviations a fresh observation sits from the established baseline. This is
 * what lets the limiter react to anomalies without anyone configuring a
 * threshold: the threshold is whatever this signal has recently been.
 *
 * <p>Two properties matter for correctness:
 * <ul>
 *   <li><b>The caller decides when to learn.</b> {@link #observe} must not be
 *       called while the system is known to be degraded, or the degraded state
 *       becomes the new normal and the limiter stops reacting to it.</li>
 *   <li><b>Dispersion has a floor.</b> A perfectly steady signal has zero
 *       variance, which would make every deviation infinitely significant. The
 *       standard deviation is floored at a fraction of the mean so a steady
 *       signal tolerates ordinary jitter.</li>
 * </ul>
 *
 * <p>Not synchronized; callers update from a single control thread.
 */
public final class EwmaBaseline {

    /** Floor on dispersion, as a fraction of the mean. Keeps z-scores finite. */
    private static final double MIN_RELATIVE_DISPERSION = 0.05;

    private final double alpha;
    private final int warmupSamples;

    private double mean;
    private double variance;
    private long samples;

    EwmaBaseline(double alpha, int warmupSamples) {
        if (alpha <= 0 || alpha > 1) {
            throw new IllegalArgumentException("alpha must be in (0, 1]");
        }
        this.alpha = alpha;
        this.warmupSamples = warmupSamples;
    }

    /**
     * Builds a baseline whose memory is expressed as a half-life rather than an
     * opaque smoothing constant: after {@code halfLife}, an observation carries
     * half the weight it started with.
     *
     * @param halfLife       how long the baseline remembers
     * @param sampleInterval how often {@link #observe} will be called
     * @param warmupSamples  observations required before z-scores are meaningful
     */
    public static EwmaBaseline withHalfLife(Duration halfLife, Duration sampleInterval, int warmupSamples) {
        double periods = (double) halfLife.toNanos() / sampleInterval.toNanos();
        double alpha = 1 - Math.exp(-Math.log(2) / periods);
        return new EwmaBaseline(Math.min(1.0, Math.max(1e-6, alpha)), warmupSamples);
    }

    public void observe(double value) {
        if (samples == 0) {
            mean = value;
            variance = 0;
        } else {
            double delta = value - mean;
            mean += alpha * delta;
            // West's incremental variance, weighted the same way as the mean.
            variance = (1 - alpha) * (variance + alpha * delta * delta);
        }
        samples++;
    }

    /**
     * How many standard deviations {@code value} sits above the baseline.
     * Returns 0 while warming up, and never returns a negative score — a signal
     * below its baseline is not an anomaly worth acting on here.
     */
    public double zScore(double value) {
        if (!isReady()) {
            return 0;
        }
        double dispersion = dispersion();
        if (dispersion <= 0) {
            return value > mean ? Double.MAX_VALUE : 0;
        }
        return Math.max(0, (value - mean) / dispersion);
    }

    /** True once enough observations exist for {@link #zScore} to mean anything. */
    public boolean isReady() {
        return samples >= warmupSamples;
    }

    public double mean() {
        return mean;
    }

    public double stdDev() {
        return Math.sqrt(Math.max(0, variance));
    }

    public long samples() {
        return samples;
    }

    private double dispersion() {
        return Math.max(stdDev(), Math.abs(mean) * MIN_RELATIVE_DISPERSION);
    }
}
