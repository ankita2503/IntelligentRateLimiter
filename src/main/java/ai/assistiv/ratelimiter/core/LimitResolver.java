package ai.assistiv.ratelimiter.core;

/**
 * Supplies the effective limit for a client.
 *
 * <p>This is the seam the adaptive limiter plugs into. Today
 * {@link StaticLimitResolver} returns the configured constant; later a resolver
 * backed by the feedback controller will return
 * {@code base x health x fair-share x reputation} without the enforcement path
 * changing at all. See {@code adaptive.AdaptiveLimitResolver}.
 */
public interface LimitResolver {

    /** The limit in force for this client right now, and why. */
    ResolvedLimit resolve(String key);
}
