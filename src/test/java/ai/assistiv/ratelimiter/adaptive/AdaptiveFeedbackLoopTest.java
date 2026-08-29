package ai.assistiv.ratelimiter.adaptive;

import static org.assertj.core.api.Assertions.assertThat;

import ai.assistiv.ratelimiter.core.FakeTimeSource;
import ai.assistiv.ratelimiter.core.RateLimitDecision;
import ai.assistiv.ratelimiter.core.ResolvedLimit;
import ai.assistiv.ratelimiter.core.TokenBucketRateLimiter;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * The whole loop wired together: bucket, resolver, controller, and metrics,
 * driven against a simulated service whose latency changes underneath it.
 *
 * <p>Nothing here configures a limit. The limit that emerges is whatever the
 * traffic and the service's own behaviour produce.
 */
class AdaptiveFeedbackLoopTest {

    private static final long MS = 1_000_000L;
    private static final Duration TICK = Duration.ofSeconds(1);
    private static final Duration WINDOW = Duration.ofSeconds(10);
    private static final Duration REFILL_PERIOD = Duration.ofSeconds(60);
    private static final long MIN_BUDGET = 50;
    private static final long MAX_BUDGET = 5000;

    private final FakeTimeSource time = new FakeTimeSource();
    private final SlidingWindowTrafficMetrics metrics =
            new SlidingWindowTrafficMetrics(WINDOW, 10, time);
    private final CapacityController controller = new CapacityController(
            MIN_BUDGET, MAX_BUDGET, 3.0, Duration.ofMinutes(1), TICK, 5, Duration.ZERO,
            REFILL_PERIOD);
    private final ClientProfileRegistry registry = new ClientProfileRegistry(
            Duration.ofMinutes(5), TICK, 5, Duration.ofMinutes(1), Duration.ofMinutes(15),
            3.0, time);
    private final AdaptiveLimitResolver resolver =
            new AdaptiveLimitResolver(controller, registry, 5, MAX_BUDGET);
    private final TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(
            resolver, REFILL_PERIOD, Duration.ofMinutes(10), time);

    /** Runs traffic for {@code seconds}, with the service answering in {@code latencyMs}. */
    private void drive(int seconds, long latencyMs, int requestsPerSecond, String... clients) {
        for (int second = 0; second < seconds; second++) {
            for (int i = 0; i < requestsPerSecond; i++) {
                for (String client : clients) {
                    RateLimitDecision decision = limiter.tryAcquire(client);
                    if (decision.allowed()) {
                        metrics.requestStarted();
                        metrics.recordCompletion(latencyMs * MS, false);
                        metrics.requestFinished();
                    } else {
                        metrics.recordRejection();
                    }
                }
            }
            time.advance(TICK);
            controller.tick(metrics.snapshot());
            registry.tick(1.0);
        }
    }

    @Test
    void discoversCapacityNobodyConfigured() {
        drive(30, 10, 50, "client");

        // Demand exceeds the starting budget, the service stays fast, so the
        // controller keeps handing capacity back until it hits the rail.
        assertThat(controller.budget()).isEqualTo(MAX_BUDGET);
        assertThat(controller.state().pressured()).isFalse();
    }

    @Test
    void collapsesTheLimitWhenTheServiceStartsDegrading() {
        drive(30, 10, 50, "client");
        long healthyLimit = resolver.resolve("client").limit();

        // The dependency slows down. Nothing about the config changed.
        drive(20, 800, 50, "client");
        long degradedLimit = resolver.resolve("client").limit();

        assertThat(healthyLimit).isEqualTo(MAX_BUDGET);
        assertThat(degradedLimit).isEqualTo(MIN_BUDGET);
        assertThat(controller.state().pressured()).isTrue();
    }

    @Test
    void recoversCautiouslyRatherThanAllAtOnce() {
        drive(30, 10, 50, "client");
        drive(20, 800, 50, "client");
        assertThat(controller.budget()).isEqualTo(MIN_BUDGET);

        // The dependency heals.
        drive(30, 10, 50, "client");

        // Capacity comes back, but by additive probing — not another doubling
        // spree that would re-break whatever just recovered.
        assertThat(controller.budget()).isGreaterThan(MIN_BUDGET);
        assertThat(controller.budget()).isLessThan(MAX_BUDGET / 4.0);
    }

    @Test
    void aDegradedServiceRejectsWithSystemPressureNotQuota() {
        drive(30, 10, 50, "client");
        drive(20, 800, 50, "client");

        // Exhaust what little budget remains.
        RateLimitDecision decision = null;
        for (int i = 0; i < MIN_BUDGET + 5; i++) {
            decision = limiter.tryAcquire("client");
        }

        assertThat(decision).isNotNull();
        assertThat(decision.allowed()).isFalse();
        assertThat(decision.reason())
                .isEqualTo(ai.assistiv.ratelimiter.core.LimitReason.SYSTEM_PRESSURE);
    }

    @Test
    void capacitySettlesNearActualDemandRatherThanTheCeiling() {
        // Probing stops once traffic no longer saturates the budget, so a
        // moderate workload does not drift the budget up to the rail and leave
        // room for a flood the moment demand changes.
        drive(30, 10, 20, "client");

        assertThat(controller.budget()).isLessThan(MAX_BUDGET);
        assertThat(controller.budget()).isGreaterThan(20 * 60);  // above observed demand
    }

    @Test
    void anArrivingTenantTakesItsShareWithoutAnyoneReconfiguringAnything() {
        drive(30, 10, 20, "incumbent");
        ResolvedLimit soleTenant = resolver.resolve("incumbent");
        assertThat(soleTenant.fairShareFactor()).isEqualTo(1.0);
        assertThat(soleTenant.limit()).isEqualTo(Math.round(controller.budget()));

        drive(5, 10, 20, "incumbent", "newcomer");

        // The newcomer is granted an equal share the moment it is observed. No
        // tenant list, no weights, no restart. (The absolute limits need not
        // fall: added demand can grow the budget at the same time.)
        ResolvedLimit incumbent = resolver.resolve("incumbent");
        ResolvedLimit newcomer = resolver.resolve("newcomer");
        assertThat(incumbent.fairShareFactor()).isEqualTo(0.5);
        assertThat(incumbent.limit()).isEqualTo(newcomer.limit());
        assertThat(incumbent.limit()).isEqualTo(Math.round(controller.budget() / 2));
    }

    @Test
    void aClientSpikingOffItsOwnBaselineIsThrottledWhileNeighboursAreNot() {
        drive(60, 10, 5, "steady", "spiky");
        assertThat(resolver.resolve("spiky").limit())
                .isEqualTo(resolver.resolve("steady").limit());

        // One client's traffic jumps 100x. The system is still healthy — this is
        // caught by the client's own history, not by system pressure.
        drive(2, 10, 500, "spiky");

        assertThat(controller.state().pressured()).isFalse();
        assertThat(resolver.resolve("spiky").limit())
                .isLessThan(resolver.resolve("steady").limit());
    }
}
