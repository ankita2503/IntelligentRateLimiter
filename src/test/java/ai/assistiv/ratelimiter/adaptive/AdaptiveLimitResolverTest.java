package ai.assistiv.ratelimiter.adaptive;

import static org.assertj.core.api.Assertions.assertThat;

import ai.assistiv.ratelimiter.core.FakeTimeSource;
import ai.assistiv.ratelimiter.core.LimitReason;
import ai.assistiv.ratelimiter.core.ResolvedLimit;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class AdaptiveLimitResolverTest {

    private static final Duration INTERVAL = Duration.ofSeconds(1);
    private static final Duration HALF_LIFE = Duration.ofMinutes(5);

    private final FakeTimeSource time = new FakeTimeSource();
    private final ClientProfileRegistry registry = new ClientProfileRegistry(
            HALF_LIFE, INTERVAL, 5, Duration.ofMinutes(1), Duration.ofMinutes(15), 3.0, time);

    /** Pins the budget by making the floor and ceiling meet. */
    private CapacityController fixedBudget(long budget) {
        return new CapacityController(budget, budget, 3.0, HALF_LIFE, INTERVAL, 5,
                Duration.ZERO, Duration.ofSeconds(60));
    }

    private AdaptiveLimitResolver resolver(CapacityController controller) {
        return new AdaptiveLimitResolver(controller, registry, 5, 5000);
    }

    @Test
    void aSoleClientMayUseTheWholeBudget() {
        AdaptiveLimitResolver resolver = resolver(fixedBudget(1000));
        registry.recordRequest("alone");
        registry.tick(1.0);

        assertThat(resolver.resolve("alone").limit()).isEqualTo(1000);
    }

    @Test
    void theBudgetIsSplitAcrossTheClientsActuallyPresent() {
        AdaptiveLimitResolver resolver = resolver(fixedBudget(1000));
        for (String key : new String[] {"a", "b", "c", "d"}) {
            registry.recordRequest(key);
        }
        registry.tick(1.0);

        assertThat(resolver.resolve("a").limit()).isEqualTo(250);
    }

    @Test
    void aClientsShareShrinksAsNeighboursArrive() {
        AdaptiveLimitResolver resolver = resolver(fixedBudget(1000));
        registry.recordRequest("first");
        registry.tick(1.0);
        long alone = resolver.resolve("first").limit();

        registry.recordRequest("second");
        registry.tick(1.0);
        long shared = resolver.resolve("first").limit();

        assertThat(alone).isEqualTo(1000);
        assertThat(shared).isEqualTo(500);
    }

    @Test
    void aDeviantClientIsThrottledWhileItsNeighbourIsNot() {
        AdaptiveLimitResolver resolver = resolver(fixedBudget(1000));
        for (int i = 0; i < 40; i++) {
            registry.recordRequest("quiet");
            registry.recordRequest("normal");
            time.advance(INTERVAL);
            registry.tick(1.0);
        }
        for (int i = 0; i < 500; i++) {
            registry.recordRequest("quiet");
        }
        registry.recordRequest("normal");
        time.advance(INTERVAL);
        registry.tick(1.0);

        ResolvedLimit deviant = resolver.resolve("quiet");
        ResolvedLimit wellBehaved = resolver.resolve("normal");

        assertThat(deviant.limit()).isLessThan(wellBehaved.limit());
        assertThat(deviant.constrainedBy()).isEqualTo(LimitReason.CLIENT_DEVIATION);
    }

    @Test
    void systemPressureIsReportedWhenTheBudgetIsWhatIsSmall() {
        // Budget pinned near the floor of a wide range: health is the weak factor.
        CapacityController controller = new CapacityController(10, 1000, 3.0, HALF_LIFE,
                INTERVAL, 5, Duration.ZERO, Duration.ofSeconds(60));
        AdaptiveLimitResolver resolver = resolver(controller);
        registry.recordRequest("solo");
        registry.tick(1.0);

        ResolvedLimit resolved = resolver.resolve("solo");

        assertThat(resolved.constrainedBy()).isEqualTo(LimitReason.SYSTEM_PRESSURE);
        assertThat(resolved.healthFactor()).isEqualTo(0.01);
    }

    @Test
    void fairShareIsReportedWhenTheTenantPoolIsWhatIsCrowded() {
        AdaptiveLimitResolver resolver = resolver(fixedBudget(1000));
        for (int i = 0; i < 10; i++) {
            registry.recordRequest("client-" + i);
        }
        registry.tick(1.0);

        assertThat(resolver.resolve("client-0").constrainedBy())
                .isEqualTo(LimitReason.FAIR_SHARE);
    }

    @Test
    void plainQuotaIsReportedWhenNothingIsSqueezingTheClient() {
        AdaptiveLimitResolver resolver = resolver(fixedBudget(1000));
        registry.recordRequest("solo");
        registry.tick(1.0);

        assertThat(resolver.resolve("solo").constrainedBy())
                .isEqualTo(LimitReason.QUOTA_EXCEEDED);
    }

    @Test
    void neverSqueezesAClientBelowTheFloor() {
        AdaptiveLimitResolver resolver = resolver(fixedBudget(10));
        for (int i = 0; i < 100; i++) {
            registry.recordRequest("client-" + i);
        }
        registry.tick(1.0);

        // 10 budget / 100 clients would be 0.1 requests each.
        assertThat(resolver.resolve("client-0").limit()).isEqualTo(5);
    }

    @Test
    void resolvingIsAlsoAnObservationOfTheClient() {
        AdaptiveLimitResolver resolver = resolver(fixedBudget(1000));

        resolver.resolve("observed");
        registry.tick(1.0);

        assertThat(registry.trackedClients()).isEqualTo(1);
        assertThat(registry.activeClients()).isEqualTo(1);
    }
}
