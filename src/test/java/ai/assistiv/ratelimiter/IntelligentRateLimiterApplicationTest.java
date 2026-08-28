package ai.assistiv.ratelimiter;

import static org.assertj.core.api.Assertions.assertThat;

import ai.assistiv.ratelimiter.core.RateLimiter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class IntelligentRateLimiterApplicationTest {

    @Autowired
    private RateLimiter rateLimiter;

    @Test
    void contextLoadsWithALimiterWired() {
        assertThat(rateLimiter).isNotNull();
    }
}
