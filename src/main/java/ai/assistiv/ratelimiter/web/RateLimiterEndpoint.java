package ai.assistiv.ratelimiter.web;

import ai.assistiv.ratelimiter.adaptive.CapacityController;
import ai.assistiv.ratelimiter.adaptive.ClientProfileRegistry;
import ai.assistiv.ratelimiter.adaptive.HealthSnapshot;
import ai.assistiv.ratelimiter.adaptive.TrafficMetrics;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;

/**
 * Exposes what the limiter has learned at {@code /actuator/ratelimiter}.
 *
 * <p>An adaptive limiter that cannot be inspected cannot be trusted: when the
 * budget moves, an operator needs to see which signal moved it.
 */
@Endpoint(id = "ratelimiter")
public class RateLimiterEndpoint {

    private final CapacityController controller;
    private final ClientProfileRegistry registry;
    private final TrafficMetrics metrics;

    public RateLimiterEndpoint(CapacityController controller, ClientProfileRegistry registry,
                               TrafficMetrics metrics) {
        this.controller = controller;
        this.registry = registry;
        this.metrics = metrics;
    }

    @ReadOperation
    public Map<String, Object> state() {
        HealthSnapshot health = metrics.snapshot();
        CapacityController.State state = controller.state();

        Map<String, Object> capacity = new LinkedHashMap<>();
        capacity.put("budget", Math.round(state.budget()));
        capacity.put("bounds", List.of(state.minBudget(), state.maxBudget()));
        capacity.put("phase", state.phase());
        capacity.put("pressured", state.pressured());
        capacity.put("utilization", round(controller.utilization(health)));
        capacity.put("ticks", state.ticks());

        Map<String, Object> learned = new LinkedHashMap<>();
        learned.put("ready", state.baselineReady());
        learned.put("latencyBaselineMs", state.learnedLatencyMeanNanos() / 1_000_000);
        learned.put("latencyThresholdMs", state.learnedLatencyThresholdNanos() / 1_000_000);
        learned.put("errorBaseline", round(state.learnedErrorMean()));
        learned.put("errorThreshold", round(state.learnedErrorThreshold()));
        learned.put("latencyZScore", round(state.lastLatencyZScore()));
        learned.put("errorZScore", round(state.lastErrorZScore()));

        Map<String, Object> observed = new LinkedHashMap<>();
        observed.put("p99Ms", health.p99LatencyNanos() / 1_000_000);
        observed.put("p50Ms", health.p50LatencyNanos() / 1_000_000);
        observed.put("errorRate", round(health.errorRate()));
        observed.put("throughputPerSecond", round(health.throughputPerSecond()));
        observed.put("admitted", health.admitted());
        observed.put("rejected", health.rejected());
        observed.put("inFlight", health.inFlight());

        Map<String, Object> clients = new LinkedHashMap<>();
        clients.put("active", registry.activeClients());
        clients.put("tracked", registry.trackedClients());
        clients.put("fairShare", round(registry.fairShare()));
        clients.put("deviant", registry.mostDeviant(5).stream()
                .map(entry -> Map.of(
                        "key", entry.getKey(),
                        "ratePerSecond", round(entry.getValue().lastRatePerSecond()),
                        "baselineRatePerSecond", round(entry.getValue().baselineRatePerSecond()),
                        "zScore", round(entry.getValue().lastZScore()),
                        "reputation", round(entry.getValue().reputation())))
                .toList());

        return Map.of("capacity", capacity, "learned", learned, "observed", observed,
                "clients", clients);
    }

    private static double round(double value) {
        return Math.round(value * 1000.0) / 1000.0;
    }
}
