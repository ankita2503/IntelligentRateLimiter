package ai.assistiv.ratelimiter.adaptive;

import ai.assistiv.ratelimiter.core.LimitReason;
import ai.assistiv.ratelimiter.core.LimitResolver;
import ai.assistiv.ratelimiter.core.ResolvedLimit;

/**
 * Composes the adaptive signals into one number per client:
 *
 * <pre>
 *   limit = budget          (what the system can currently take)
 *         x fair share      (this client's slice of it, given who else is here)
 *         x reputation      (how far this client is from its own normal)
 * </pre>
 *
 * <p>None of the three is a configured constant. The budget is discovered by
 * {@link CapacityController}, the share comes from the observed number of active
 * clients, and reputation comes from each client's own traffic history. The only
 * fixed values are the floor and ceiling that bound the result.
 *
 * <p>The dominant factor is reported alongside the limit, so a rejected caller
 * is told whether the system was under pressure, the tenant pool was crowded, or
 * its own behaviour was the problem.
 */
public final class AdaptiveLimitResolver implements LimitResolver {

    /** Factors above this are treated as "not the reason we said no". */
    private static final double UNCONSTRAINED = 0.99;

    private final CapacityController controller;
    private final ClientProfileRegistry registry;
    private final long minClientLimit;
    private final long maxClientLimit;

    public AdaptiveLimitResolver(CapacityController controller, ClientProfileRegistry registry,
                                 long minClientLimit, long maxClientLimit) {
        this.controller = controller;
        this.registry = registry;
        this.minClientLimit = minClientLimit;
        this.maxClientLimit = maxClientLimit;
    }

    @Override
    public ResolvedLimit resolve(String key) {
        // Every admission check is also an observation of this client's rate.
        registry.recordRequest(key);

        double fairShare = registry.fairShare();
        double reputation = registry.reputationOf(key);
        double raw = controller.budget() * fairShare * reputation;

        long limit = Math.min(maxClientLimit, Math.max(minClientLimit, Math.round(raw)));
        double health = controller.healthFactor();

        return new ResolvedLimit(limit, dominantReason(health, fairShare, reputation),
                health, fairShare, reputation);
    }

    private LimitReason dominantReason(double health, double fairShare, double reputation) {
        double weakest = Math.min(health, Math.min(fairShare, reputation));
        if (weakest > UNCONSTRAINED) {
            return LimitReason.QUOTA_EXCEEDED;
        }
        if (weakest == reputation) {
            return LimitReason.CLIENT_DEVIATION;
        }
        if (weakest == health) {
            return LimitReason.SYSTEM_PRESSURE;
        }
        return LimitReason.FAIR_SHARE;
    }
}
