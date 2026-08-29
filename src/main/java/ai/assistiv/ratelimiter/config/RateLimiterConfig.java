package ai.assistiv.ratelimiter.config;

import ai.assistiv.ratelimiter.adaptive.AdaptiveControlLoop;
import ai.assistiv.ratelimiter.adaptive.AdaptiveLimitResolver;
import ai.assistiv.ratelimiter.adaptive.CapacityController;
import ai.assistiv.ratelimiter.adaptive.ClientProfileRegistry;
import ai.assistiv.ratelimiter.adaptive.SlidingWindowTrafficMetrics;
import ai.assistiv.ratelimiter.adaptive.TrafficMetrics;
import ai.assistiv.ratelimiter.core.LimitResolver;
import ai.assistiv.ratelimiter.core.RateLimiter;
import ai.assistiv.ratelimiter.core.StaticLimitResolver;
import ai.assistiv.ratelimiter.core.TimeSource;
import ai.assistiv.ratelimiter.core.TokenBucketRateLimiter;
import ai.assistiv.ratelimiter.web.ClientKeyResolver;
import ai.assistiv.ratelimiter.web.HeaderOrAddressKeyResolver;
import ai.assistiv.ratelimiter.web.RateLimitFilter;
import ai.assistiv.ratelimiter.web.RateLimiterEndpoint;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

@Configuration
@EnableConfigurationProperties(RateLimitProperties.class)
public class RateLimiterConfig {

    /** Slots per observation window; more slots make the window slide smoothly. */
    private static final int WINDOW_SLOTS = 10;

    @Bean
    @ConditionalOnMissingBean
    public TimeSource timeSource() {
        return TimeSource.SYSTEM;
    }

    /**
     * Collected even when the adaptive layer is off, so the state endpoint can
     * show what an adaptive controller would have been reacting to.
     */
    @Bean
    @ConditionalOnMissingBean
    public TrafficMetrics trafficMetrics(RateLimitProperties properties, TimeSource timeSource) {
        return new SlidingWindowTrafficMetrics(
                properties.getAdaptive().getWindow(), WINDOW_SLOTS, timeSource);
    }

    @Bean
    @ConditionalOnProperty(prefix = "ratelimiter.adaptive", name = "enabled",
            havingValue = "true", matchIfMissing = true)
    public CapacityController capacityController(RateLimitProperties properties) {
        AdaptiveProperties adaptive = properties.getAdaptive();
        return new CapacityController(
                adaptive.getMinBudget(), adaptive.getMaxBudget(), adaptive.getDeviationSigma(),
                adaptive.getBaselineHalfLife(), adaptive.getControlInterval(),
                adaptive.getWarmupTicks(), adaptive.getLatencyCeiling(),
                properties.getRefillPeriod());
    }

    @Bean
    @ConditionalOnProperty(prefix = "ratelimiter.adaptive", name = "enabled",
            havingValue = "true", matchIfMissing = true)
    public ClientProfileRegistry clientProfileRegistry(RateLimitProperties properties,
                                                       TimeSource timeSource) {
        AdaptiveProperties adaptive = properties.getAdaptive();
        return new ClientProfileRegistry(
                adaptive.getBaselineHalfLife(), adaptive.getControlInterval(),
                adaptive.getWarmupTicks(), adaptive.getClientActivityWindow(),
                adaptive.getClientIdleTtl(), adaptive.getDeviationSigma(), timeSource);
    }

    @Bean
    @ConditionalOnProperty(prefix = "ratelimiter.adaptive", name = "enabled",
            havingValue = "true", matchIfMissing = true)
    public AdaptiveControlLoop adaptiveControlLoop(CapacityController controller,
                                                   ClientProfileRegistry registry,
                                                   TrafficMetrics metrics,
                                                   RateLimitProperties properties) {
        return new AdaptiveControlLoop(controller, registry, metrics,
                properties.getAdaptive().getControlInterval());
    }

    /** The limit is derived from traffic: budget x fair share x reputation. */
    @Bean
    @ConditionalOnProperty(prefix = "ratelimiter.adaptive", name = "enabled",
            havingValue = "true", matchIfMissing = true)
    public LimitResolver adaptiveLimitResolver(CapacityController controller,
                                               ClientProfileRegistry registry,
                                               RateLimitProperties properties) {
        AdaptiveProperties adaptive = properties.getAdaptive();
        return new AdaptiveLimitResolver(controller, registry,
                adaptive.getMinClientLimit(), adaptive.getMaxBudget());
    }

    /** Fallback when adaptation is switched off: the configured constant. */
    @Bean
    @ConditionalOnProperty(prefix = "ratelimiter.adaptive", name = "enabled",
            havingValue = "false")
    public LimitResolver staticLimitResolver(RateLimitProperties properties) {
        return new StaticLimitResolver(properties.getLimit());
    }

    @Bean
    @ConditionalOnProperty(prefix = "ratelimiter.adaptive", name = "enabled",
            havingValue = "true", matchIfMissing = true)
    public RateLimiterEndpoint rateLimiterEndpoint(CapacityController controller,
                                                   ClientProfileRegistry registry,
                                                   TrafficMetrics metrics) {
        return new RateLimiterEndpoint(controller, registry, metrics);
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
            RateLimiter rateLimiter, ClientKeyResolver keyResolver, TrafficMetrics metrics,
            TimeSource timeSource, RateLimitProperties properties) {
        FilterRegistrationBean<RateLimitFilter> registration = new FilterRegistrationBean<>(
                new RateLimitFilter(rateLimiter, keyResolver, metrics, timeSource, properties));
        // Run before anything expensive so rejected traffic costs almost nothing.
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 100);
        return registration;
    }

    @Bean
    public BucketMaintenance bucketMaintenance(RateLimiter rateLimiter) {
        return new BucketMaintenance(rateLimiter);
    }
}
