package ai.assistiv.ratelimiter.config;

import ai.assistiv.ratelimiter.core.RateLimiter;
import ai.assistiv.ratelimiter.core.TokenBucketRateLimiter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Sweeps idle buckets off the hot path. Without this, one bucket per distinct
 * key accumulates for the life of the process.
 */
public class BucketMaintenance {

    private static final Logger log = LoggerFactory.getLogger(BucketMaintenance.class);

    private final RateLimiter rateLimiter;

    public BucketMaintenance(RateLimiter rateLimiter) {
        this.rateLimiter = rateLimiter;
    }

    @Scheduled(fixedDelayString = "${ratelimiter.eviction-interval:60s}")
    public void evictIdleBuckets() {
        if (rateLimiter instanceof TokenBucketRateLimiter bucketLimiter) {
            int evicted = bucketLimiter.evictIdle();
            if (evicted > 0) {
                log.debug("Evicted {} idle buckets, {} remaining", evicted, bucketLimiter.trackedKeys());
            }
        }
    }
}
