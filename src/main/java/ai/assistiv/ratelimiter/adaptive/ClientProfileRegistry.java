package ai.assistiv.ratelimiter.adaptive;

import ai.assistiv.ratelimiter.core.TimeSource;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-client traffic profiles, and the population count that fair sharing needs.
 *
 * <p>The number of clients sharing the budget is observed, not configured — it
 * is however many distinct keys have been seen in the recent activity window.
 * Tenants appearing and disappearing changes everyone's share automatically.
 */
public final class ClientProfileRegistry {

    private final Map<String, ClientProfile> profiles = new ConcurrentHashMap<>();
    private final Duration baselineHalfLife;
    private final Duration controlInterval;
    private final int warmupIntervals;
    private final Duration activityWindow;
    private final Duration idleTtl;
    private final double sigma;
    private final TimeSource time;

    /**
     * Recomputed once per control tick, read once per request. Counting the map
     * on every request would put an O(clients) scan on the hot path.
     */
    private volatile int activeClients = 1;

    public ClientProfileRegistry(Duration baselineHalfLife, Duration controlInterval,
                                 int warmupIntervals, Duration activityWindow,
                                 Duration idleTtl, double sigma, TimeSource time) {
        this.baselineHalfLife = baselineHalfLife;
        this.controlInterval = controlInterval;
        this.warmupIntervals = warmupIntervals;
        this.activityWindow = activityWindow;
        this.idleTtl = idleTtl;
        this.sigma = sigma;
        this.time = time;
    }

    /** Called once per request, on the hot path. */
    public void recordRequest(String key) {
        long now = time.nanoTime();
        profiles.computeIfAbsent(key, k -> new ClientProfile(
                EwmaBaseline.withHalfLife(baselineHalfLife, controlInterval, warmupIntervals), now))
                .recordRequest(now);
    }

    /** Closes the interval for every profile and drops long-idle clients. */
    public void tick(double intervalSeconds) {
        profiles.values().forEach(profile -> profile.closeInterval(intervalSeconds, sigma));

        long now = time.nanoTime();
        long activeCutoff = now - activityWindow.toNanos();
        long idleCutoff = now - idleTtl.toNanos();

        int active = 0;
        for (ClientProfile profile : profiles.values()) {
            if (profile.lastSeenNanos() - activeCutoff >= 0) {
                active++;
            }
        }
        activeClients = Math.max(1, active);

        profiles.values().removeIf(profile -> profile.lastSeenNanos() - idleCutoff < 0);
    }

    /**
     * Distinct clients seen in the recent activity window; never less than 1, so
     * shares stay finite. Refreshed each control tick, so a client arriving
     * mid-tick is counted from the next one.
     */
    public int activeClients() {
        return activeClients;
    }

    /** This client's slice of the budget, as an equal split among active clients. */
    public double fairShare() {
        return 1.0 / activeClients();
    }

    public double reputationOf(String key) {
        ClientProfile profile = profiles.get(key);
        return profile == null ? 1.0 : profile.reputation();
    }

    public int trackedClients() {
        return profiles.size();
    }

    /** The most anomalous clients, worst first — for the state endpoint. */
    public List<Map.Entry<String, ClientProfile>> mostDeviant(int limit) {
        return profiles.entrySet().stream()
                .filter(entry -> entry.getValue().reputation() < 1.0)
                .sorted(Comparator.comparingDouble(entry -> entry.getValue().reputation()))
                .limit(limit)
                .toList();
    }
}
