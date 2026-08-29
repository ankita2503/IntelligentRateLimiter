package ai.assistiv.ratelimiter.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ai.assistiv.ratelimiter.adaptive.AdaptiveLimitResolver;
import ai.assistiv.ratelimiter.adaptive.TrafficMetrics;
import ai.assistiv.ratelimiter.core.LimitResolver;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "ratelimiter.adaptive.min-budget=50",
        "ratelimiter.adaptive.max-budget=500",
        // Park the control loop so the budget cannot move mid-assertion.
        "ratelimiter.adaptive.control-interval=1h",
        "management.endpoints.web.exposure.include=health,ratelimiter"
})
class AdaptiveWiringIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private LimitResolver limitResolver;

    @Autowired
    private TrafficMetrics trafficMetrics;

    @Test
    void theAdaptiveResolverIsWiredByDefault() {
        assertThat(limitResolver).isInstanceOf(AdaptiveLimitResolver.class);
    }

    @Test
    void theAdvertisedLimitComesFromTheControllerNotTheStaticConfig() throws Exception {
        // Budget starts at the floor and is earned upward; one active client
        // holds the whole of it.
        mockMvc.perform(get("/api/ping").header("X-API-Key", "wired"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-RateLimit-Limit", "50"));
    }

    @Test
    void servedRequestsFeedTheControllersSignals() throws Exception {
        long before = trafficMetrics.snapshot().admitted();

        mockMvc.perform(get("/api/ping").header("X-API-Key", "measured"))
                .andExpect(status().isOk());

        assertThat(trafficMetrics.snapshot().admitted()).isGreaterThan(before);
    }

    @Test
    void theStateEndpointExplainsWhatTheLimiterHasLearned() throws Exception {
        mockMvc.perform(get("/api/ping").header("X-API-Key", "observed"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/actuator/ratelimiter"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.capacity.budget").value(50))
                .andExpect(jsonPath("$.capacity.phase").value("SLOW_START"))
                .andExpect(jsonPath("$.capacity.pressured").value(false))
                .andExpect(jsonPath("$.learned.ready").value(false))
                .andExpect(jsonPath("$.clients.active").exists())
                .andExpect(jsonPath("$.observed.admitted").exists());
    }
}
