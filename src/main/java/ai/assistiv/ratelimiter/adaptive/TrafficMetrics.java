package ai.assistiv.ratelimiter.adaptive;

/**
 * Collects the signals the controller reacts to.
 *
 * <p>Implementations are written to from the request hot path and read from the
 * control loop, so recording must be cheap and allocation-free.
 */
public interface TrafficMetrics {

    /** Records a request that ran to completion. */
    void recordCompletion(long latencyNanos, boolean failed);

    /** Records a request the limiter refused. */
    void recordRejection();

    /** Requests currently executing; the controller reads this as queue depth. */
    void requestStarted();

    void requestFinished();

    HealthSnapshot snapshot();
}
