package ai.assistiv.ratelimiter.config;

import ai.assistiv.ratelimiter.core.LimitResolver;
import ai.assistiv.ratelimiter.core.RateLimiter;
import ai.assistiv.ratelimiter.core.StaticLimitResolver;
import ai.assistiv.ratelimiter.core.TimeSource;
import ai.assistiv.ratelimiter.core.TokenBucketRateLimiter;
import ai.assistiv.ratelimiter.web.ClientKeyResolver;
import ai.assistiv.ratelimiter.web.HeaderOrAddressKeyResolver;
import ai.assistiv.ratelimiter.web.RateLimitFilter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

@Configuration
@EnableConfigurationProperties(RateLimitProperties.class)
public class RateLimiterConfig {

    @Bean
    @ConditionalOnMissingBean
    public TimeSource timeSource() {
        return TimeSource.SYSTEM;
    }

    /**
     * Static today. Swapping this bean for a controller-driven resolver is how
     * the limiter becomes adaptive — nothing downstream changes.
     */
    @Bean
    @ConditionalOnMissingBean
    public LimitResolver limitResolver(RateLimitProperties properties) {
        return new StaticLimitResolver(properties.getLimit());
    }

    @Bean
    @ConditionalOnMissingBean
    public RateLimiter rateLimiter(LimitResolver limitResolver, RateLimitProperties properties,
                                   TimeSource timeSource) {
        return new TokenBucketRateLimiter(limitResolver, properties.getRefillPeriod(),
                properties.getIdleTtl(), timeSource);
    }

    @Bean
    @ConditionalOnMissingBean
    public ClientKeyResolver clientKeyResolver(RateLimitProperties properties) {
        return new HeaderOrAddressKeyResolver(properties.getKeyHeader());
    }

    @Bean
    public FilterRegistrationBean<RateLimitFilter> rateLimitFilterRegistration(
            RateLimiter rateLimiter, ClientKeyResolver keyResolver, RateLimitProperties properties) {
        FilterRegistrationBean<RateLimitFilter> registration = new FilterRegistrationBean<>(
                new RateLimitFilter(rateLimiter, keyResolver, properties));
        // Run before anything expensive so rejected traffic costs almost nothing.
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 100);
        return registration;
    }

    @Bean
    public BucketMaintenance bucketMaintenance(RateLimiter rateLimiter) {
        return new BucketMaintenance(rateLimiter);
    }
}
