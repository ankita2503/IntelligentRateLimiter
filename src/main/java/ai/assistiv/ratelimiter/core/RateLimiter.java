package ai.assistiv.ratelimiter.core;

/**
 * Admission control for a single logical client.
 *
 * <p>Implementations are called on the request hot path and must be cheap and
 * thread-safe. Nothing here assumes a fixed limit: the {@code cost} parameter
 * and the returned {@link RateLimitDecision} are already shaped for
 * cost-weighted accounting and adaptive limits.
 */
public interface RateLimiter {

    /**
     * Attempts to admit a request costing {@code cost} tokens.
     *
     * @param key  the client identity to charge
     * @param cost how expensive this request is; 1 for uniform accounting
     */
    RateLimitDecision tryAcquire(String key, int cost);

    default RateLimitDecision tryAcquire(String key) {
        return tryAcquire(key, 1);
    }
}
