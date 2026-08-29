package ai.assistiv.ratelimiter.core;

/**
 * The limit in force for one client, plus why it is what it is.
 *
 * <p>The factors are carried so a denial can name the dominant cause instead of
 * always reporting "quota". Each is a multiplier in {@code (0, 1]} against the
 * unconstrained ceiling.
 *
 * @param limit             tokens per refill period for this client
 * @param constrainedBy     the reason to report if this client is denied
 * @param healthFactor      how much system pressure shrank the limit
 * @param fairShareFactor   this client's slice of the current budget
 * @param reputationFactor  penalty for deviating from the client's own baseline
 */
public record ResolvedLimit(
        long limit,
        LimitReason constrainedBy,
        double healthFactor,
        double fairShareFactor,
        double reputationFactor) {

    /** An unconstrained limit — nothing adaptive is shrinking it. */
    public static ResolvedLimit of(long limit) {
        return new ResolvedLimit(limit, LimitReason.QUOTA_EXCEEDED, 1.0, 1.0, 1.0);
    }
}
