package ai.assistiv.ratelimiter.core;

/**
 * The outcome of a single admission check.
 *
 * @param allowed            whether the request may proceed
 * @param limit              the effective limit applied, in tokens per window
 * @param remaining          tokens left in the client's bucket after this check
 * @param retryAfterSeconds  how long to wait before retrying; 0 when allowed
 * @param resetEpochSecond   epoch second at which capacity is next available
 * @param reason             why this decision was reached
 */
public record RateLimitDecision(
        boolean allowed,
        long limit,
        long remaining,
        long retryAfterSeconds,
        long resetEpochSecond,
        LimitReason reason) {

    public static RateLimitDecision allow(long limit, long remaining, long resetEpochSecond) {
        return new RateLimitDecision(true, limit, remaining, 0, resetEpochSecond, LimitReason.ALLOWED);
    }

    public static RateLimitDecision deny(long limit, long retryAfterSeconds, long resetEpochSecond,
                                         LimitReason reason) {
        return new RateLimitDecision(false, limit, 0, retryAfterSeconds, resetEpochSecond, reason);
    }
}
