package ai.assistiv.ratelimiter.core;

/**
 * Supplies the effective limit for a client.
 *
 * <p>This is the seam the adaptive limiter plugs into. Today
 * {@link StaticLimitResolver} returns the configured constant; later a resolver
 * backed by the feedback controller will return
 * {@code base x health x fair-share x reputation} without the enforcement path
 * changing at all.
 */
public interface LimitResolver {

    /** Tokens per refill period this client is currently entitled to. */
    long limitFor(String key);
}
