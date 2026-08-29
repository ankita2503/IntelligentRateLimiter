package ai.assistiv.ratelimiter.adaptive;

import static org.assertj.core.api.Assertions.assertThat;

import ai.assistiv.ratelimiter.core.FakeTimeSource;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class ClientProfileRegistryTest {

    private static final Duration INTERVAL = Duration.ofSeconds(1);
    private static final Duration HALF_LIFE = Duration.ofMinutes(5);
    private static final Duration ACTIVITY_WINDOW = Duration.ofMinutes(1);
    private static final Duration IDLE_TTL = Duration.ofMinutes(15);
    private static final int WARMUP = 5;

    private final FakeTimeSource time = new FakeTimeSource();
    private final ClientProfileRegistry registry = new ClientProfileRegistry(
            HALF_LIFE, INTERVAL, WARMUP, ACTIVITY_WINDOW, IDLE_TTL, 3.0, time);

    /** Sends {@code perInterval} requests each second for {@code intervals} seconds. */
    private void sendSteadily(String key, int perInterval, int intervals) {
        for (int i = 0; i < intervals; i++) {
            for (int r = 0; r < perInterval; r++) {
                registry.recordRequest(key);
            }
            time.advance(INTERVAL);
            registry.tick(1.0);
        }
    }

    @Test
    void aSteadyClientKeepsFullReputation() {
        sendSteadily("steady", 10, 30);

        assertThat(registry.reputationOf("steady")).isEqualTo(1.0);
    }

    @Test
    void anUnknownClientStartsWithFullReputation() {
        assertThat(registry.reputationOf("never-seen")).isEqualTo(1.0);
    }

    @Test
    void aClientSpikingAboveItsOwnBaselineLosesReputation() {
        sendSteadily("quiet", 2, 40);
        assertThat(registry.reputationOf("quiet")).isEqualTo(1.0);

        sendSteadily("quiet", 500, 1);

        // 500/s is not close to any system limit — it is anomalous only relative
        // to this client's own history, which is the point.
        assertThat(registry.reputationOf("quiet")).isLessThan(1.0);
    }

    @Test
    void aBusyButConsistentClientIsNotPenalised() {
        sendSteadily("busy", 500, 40);

        assertThat(registry.reputationOf("busy")).isEqualTo(1.0);
    }

    @Test
    void aSustainedFloodNeverBecomesTheClientsNewNormal() {
        sendSteadily("flood", 2, 40);

        // Keep flooding. If the baseline learned from these intervals, the
        // attacker would be forgiven after a minute or two.
        sendSteadily("flood", 500, 60);

        assertThat(registry.reputationOf("flood")).isLessThan(1.0);
    }

    @Test
    void reputationIsThrottlingNotBanning() {
        sendSteadily("extreme", 1, 40);
        sendSteadily("extreme", 100_000, 1);

        assertThat(registry.reputationOf("extreme")).isGreaterThanOrEqualTo(0.1);
    }

    @Test
    void oneClientsBehaviourDoesNotAffectAnother() {
        sendSteadily("noisy", 2, 40);
        sendSteadily("calm", 2, 40);
        sendSteadily("noisy", 500, 1);

        assertThat(registry.reputationOf("noisy")).isLessThan(1.0);
        assertThat(registry.reputationOf("calm")).isEqualTo(1.0);
    }

    @Test
    void fairShareFollowsTheObservedNumberOfClients() {
        registry.recordRequest("a");
        registry.tick(1.0);
        assertThat(registry.activeClients()).isEqualTo(1);
        assertThat(registry.fairShare()).isEqualTo(1.0);

        registry.recordRequest("b");
        registry.recordRequest("c");
        registry.recordRequest("d");
        registry.tick(1.0);

        assertThat(registry.activeClients()).isEqualTo(4);
        assertThat(registry.fairShare()).isEqualTo(0.25);
    }

    @Test
    void clientsThatGoQuietStopCountingTowardTheShare() {
        registry.recordRequest("a");
        registry.recordRequest("b");
        registry.tick(1.0);
        assertThat(registry.activeClients()).isEqualTo(2);

        time.advance(ACTIVITY_WINDOW.plusSeconds(1));
        registry.recordRequest("a");
        registry.tick(1.0);

        assertThat(registry.activeClients()).isEqualTo(1);
    }

    @Test
    void longIdleProfilesAreForgottenEntirely() {
        registry.recordRequest("transient");
        registry.tick(1.0);
        assertThat(registry.trackedClients()).isEqualTo(1);

        time.advance(IDLE_TTL.plusMinutes(1));
        registry.tick(1.0);

        assertThat(registry.trackedClients()).isZero();
    }

    @Test
    void listsTheMostDeviantClientsForInspection() {
        sendSteadily("quiet", 2, 40);
        sendSteadily("steady", 2, 40);
        sendSteadily("quiet", 500, 1);

        assertThat(registry.mostDeviant(5))
                .singleElement()
                .satisfies(entry -> {
                    assertThat(entry.getKey()).isEqualTo("quiet");
                    assertThat(entry.getValue().lastZScore()).isGreaterThan(3);
                });
    }
}
