package ai.assistiv.ratelimiter.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class TokenBucketRateLimiterTest {

    private static final Duration PERIOD = Duration.ofSeconds(60);
    private static final Duration IDLE_TTL = Duration.ofMinutes(10);

    private final FakeTimeSource time = new FakeTimeSource();

    private TokenBucketRateLimiter limiterWithLimit(long limit) {
        return new TokenBucketRateLimiter(new StaticLimitResolver(limit), PERIOD, IDLE_TTL, time);
    }

    @Test
    void admitsUpToTheLimitThenDenies() {
        TokenBucketRateLimiter limiter = limiterWithLimit(3);

        for (int i = 0; i < 3; i++) {
            assertThat(limiter.tryAcquire("alice").allowed()).isTrue();
        }

        RateLimitDecision denied = limiter.tryAcquire("alice");
        assertThat(denied.allowed()).isFalse();
        assertThat(denied.reason()).isEqualTo(LimitReason.QUOTA_EXCEEDED);
        assertThat(denied.limit()).isEqualTo(3);
        assertThat(denied.remaining()).isZero();
    }

    @Test
    void reportsRemainingTokens() {
        TokenBucketRateLimiter limiter = limiterWithLimit(5);

        assertThat(limiter.tryAcquire("alice").remaining()).isEqualTo(4);
        assertThat(limiter.tryAcquire("alice").remaining()).isEqualTo(3);
    }

    @Test
    void keysAreIsolatedFromEachOther() {
        TokenBucketRateLimiter limiter = limiterWithLimit(1);

        assertThat(limiter.tryAcquire("alice").allowed()).isTrue();
        assertThat(limiter.tryAcquire("alice").allowed()).isFalse();
        assertThat(limiter.tryAcquire("bob").allowed()).isTrue();
    }

    @Test
    void refillsContinuouslyRatherThanAtWindowBoundaries() {
        TokenBucketRateLimiter limiter = limiterWithLimit(60);
        IntStream.range(0, 60).forEach(i -> limiter.tryAcquire("alice"));
        assertThat(limiter.tryAcquire("alice").allowed()).isFalse();

        // A tenth of the period restores roughly a tenth of the bucket.
        time.advance(Duration.ofSeconds(6));

        RateLimitDecision afterRefill = limiter.tryAcquire("alice");
        assertThat(afterRefill.allowed()).isTrue();
        assertThat(afterRefill.remaining()).isEqualTo(5);
    }

    @Test
    void neverRefillsBeyondCapacity() {
        TokenBucketRateLimiter limiter = limiterWithLimit(10);
        limiter.tryAcquire("alice");

        time.advance(Duration.ofHours(1));

        assertThat(limiter.tryAcquire("alice").remaining()).isEqualTo(9);
    }

    @Test
    void quotesARetryAfterThatCoversTheWait() {
        TokenBucketRateLimiter limiter = limiterWithLimit(60);
        IntStream.range(0, 60).forEach(i -> limiter.tryAcquire("alice"));

        RateLimitDecision denied = limiter.tryAcquire("alice");

        // One token per second at 60 per minute, so the wait rounds up to 1s.
        assertThat(denied.retryAfterSeconds()).isEqualTo(1);
        assertThat(denied.resetEpochSecond()).isEqualTo(time.epochSecond() + 1);

        time.advance(Duration.ofSeconds(denied.retryAfterSeconds()));
        assertThat(limiter.tryAcquire("alice").allowed()).isTrue();
    }

    @Test
    void chargesTheRequestCost() {
        TokenBucketRateLimiter limiter = limiterWithLimit(10);

        assertThat(limiter.tryAcquire("alice", 4).remaining()).isEqualTo(6);
        assertThat(limiter.tryAcquire("alice", 6).remaining()).isZero();
        assertThat(limiter.tryAcquire("alice", 1).allowed()).isFalse();
    }

    @Test
    void aDeniedRequestConsumesNothing() {
        TokenBucketRateLimiter limiter = limiterWithLimit(5);

        assertThat(limiter.tryAcquire("alice", 10).allowed()).isFalse();
        assertThat(limiter.tryAcquire("alice", 5).allowed()).isTrue();
    }

    @Test
    void evictsBucketsIdleLongerThanTheTtl() {
        TokenBucketRateLimiter limiter = limiterWithLimit(5);
        limiter.tryAcquire("alice");
        assertThat(limiter.trackedKeys()).isEqualTo(1);

        time.advance(IDLE_TTL.plusMinutes(1));
        limiter.tryAcquire("bob");

        assertThat(limiter.evictIdle()).isEqualTo(1);
        assertThat(limiter.trackedKeys()).isEqualTo(1);
    }

    @Test
    void admitsExactlyTheLimitUnderConcurrency() throws Exception {
        int limit = 500;
        TokenBucketRateLimiter limiter = limiterWithLimit(limit);
        AtomicInteger admitted = new AtomicInteger();

        try (ExecutorService pool = Executors.newFixedThreadPool(16)) {
            Future<?>[] futures = new Future<?>[2000];
            for (int i = 0; i < futures.length; i++) {
                futures[i] = pool.submit(() -> {
                    if (limiter.tryAcquire("alice").allowed()) {
                        admitted.incrementAndGet();
                    }
                });
            }
            for (Future<?> future : futures) {
                future.get();
            }
        }

        // The clock is frozen, so no refill can inflate the count.
        assertThat(admitted.get()).isEqualTo(limit);
    }
}
