package ai.assistiv.ratelimiter.adaptive;

/**
 * What the system looked like over the recent observation window.
 *
 * @param p99LatencyNanos   tail latency of completed requests
 * @param p50LatencyNanos   median latency, for context in the state endpoint
 * @param errorRate         fraction of completed requests that failed, in [0, 1]
 * @param admitted          requests let through during the window
 * @param rejected          requests the limiter shed during the window
 * @param inFlight          requests in flight at the moment of the snapshot
 * @param windowSeconds     length of the window the counts cover
 */
public record HealthSnapshot(
        long p99LatencyNanos,
        long p50LatencyNanos,
        double errorRate,
        long admitted,
        long rejected,
        int inFlight,
        double windowSeconds) {

    public static final HealthSnapshot EMPTY = new HealthSnapshot(0, 0, 0, 0, 0, 0, 1);

    /** Whether there is enough traffic for the signals to mean anything. */
    public boolean hasTraffic() {
        return admitted > 0;
    }

    /** Admitted requests per second over the window. */
    public double throughputPerSecond() {
        return windowSeconds <= 0 ? 0 : admitted / windowSeconds;
    }
}
