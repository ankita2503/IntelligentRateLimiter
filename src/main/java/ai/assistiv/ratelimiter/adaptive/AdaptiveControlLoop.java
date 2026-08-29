package ai.assistiv.ratelimiter.adaptive;

import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Drives the control loop on a fixed tick, off the request path.
 *
 * <p>This is the only component that moves the budget. Request threads read the
 * result; they never compute it.
 */
public class AdaptiveControlLoop {

    private static final Logger log = LoggerFactory.getLogger(AdaptiveControlLoop.class);

    private final CapacityController controller;
    private final ClientProfileRegistry registry;
    private final TrafficMetrics metrics;
    private final double intervalSeconds;

    public AdaptiveControlLoop(CapacityController controller, ClientProfileRegistry registry,
                               TrafficMetrics metrics, Duration controlInterval) {
        this.controller = controller;
        this.registry = registry;
        this.metrics = metrics;
        this.intervalSeconds = controlInterval.toNanos() / 1_000_000_000.0;
    }

    @Scheduled(fixedDelayString = "${ratelimiter.adaptive.control-interval:1s}")
    public void tick() {
        try {
            HealthSnapshot snapshot = metrics.snapshot();
            double before = controller.budget();
            controller.tick(snapshot);
            registry.tick(intervalSeconds);

            if (log.isDebugEnabled() && before != controller.budget()) {
                log.debug("budget {} -> {} (p99={}ms errors={} clients={})",
                        Math.round(before), Math.round(controller.budget()),
                        snapshot.p99LatencyNanos() / 1_000_000, snapshot.errorRate(),
                        registry.activeClients());
            }
        } catch (RuntimeException e) {
            // A failed tick must not kill the schedule; the budget simply holds.
            log.error("Adaptive control tick failed; holding budget", e);
        }
    }
}
