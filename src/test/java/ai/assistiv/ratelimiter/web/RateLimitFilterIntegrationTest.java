package ai.assistiv.ratelimiter.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        // Enforcement is tested against a fixed limit; the adaptive layer has
        // its own tests.
        "ratelimiter.adaptive.enabled=false",
        "ratelimiter.limit=3",
        "ratelimiter.refill-period=60s"
})
class RateLimitFilterIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    /** Each test uses its own key so buckets do not leak between tests. */
    private void callPing(String apiKey, int times) throws Exception {
        for (int i = 0; i < times; i++) {
            mockMvc.perform(get("/api/ping").header("X-API-Key", apiKey))
                    .andExpect(status().isOk());
        }
    }

    @Test
    void allowsRequestsWithinTheLimitAndAdvertisesRemaining() throws Exception {
        mockMvc.perform(get("/api/ping").header("X-API-Key", "within-limit"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-RateLimit-Limit", "3"))
                .andExpect(header().string("X-RateLimit-Remaining", "2"))
                .andExpect(header().string("X-RateLimit-Reason", "allowed"))
                .andExpect(header().exists("X-RateLimit-Reset"));
    }

    @Test
    void rejectsWithProblemDetailsOnceTheLimitIsExhausted() throws Exception {
        callPing("exhausted", 3);

        mockMvc.perform(get("/api/ping").header("X-API-Key", "exhausted"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"))
                .andExpect(header().string("X-RateLimit-Remaining", "0"))
                .andExpect(header().string("X-RateLimit-Reason", "quota"))
                .andExpect(jsonPath("$.status").value(429))
                .andExpect(jsonPath("$.reason").value("quota"));
    }

    @Test
    void limitsEachApiKeySeparately() throws Exception {
        callPing("noisy", 3);
        mockMvc.perform(get("/api/ping").header("X-API-Key", "noisy"))
                .andExpect(status().isTooManyRequests());

        mockMvc.perform(get("/api/ping").header("X-API-Key", "quiet"))
                .andExpect(status().isOk());
    }

    @Test
    void excludedPathsBypassTheLimiter() throws Exception {
        for (int i = 0; i < 10; i++) {
            mockMvc.perform(get("/actuator/health"))
                    .andExpect(status().isOk())
                    .andExpect(header().doesNotExist("X-RateLimit-Limit"));
        }
    }
}
