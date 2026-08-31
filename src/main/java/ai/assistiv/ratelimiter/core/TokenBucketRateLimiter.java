package ai.assistiv.ratelimiter.core;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * In-memory token bucket, one bucket per client key.
 *
 * <p>Each bucket holds up to {@code limit} tokens and refills continuously so
 * that a full bucket is restored over one {@code refillPeriod}. Continuous
 * refill is what makes the bucket tolerate bursts without the boundary spike a
 * fixed window suffers from.
 *
 * <p>Buckets are updated with a compare-and-set loop, so the hot path never
 * blocks. Capacity is read from the {@link LimitResolver} on every call rather
 * than captured once, so a limit that starts moving takes effect immediately.
 *
 * <p>State is per-process. Sharing it across instances is a later step; see the
 * README roadmap.
 */
public class TokenBucketRateLimiter implements RateLimiter {

    /**
     * Tokens are tracked in micro-tokens so a partial refill is not lost to
     * integer truncation between calls.
     */
    private static final long SCALE = 1_000_000L;

    private final LimitResolver limitResolver;
    private final long refillPeriodNanos;
    private final long idleTtlNanos;
    private final TimeSource time;
    private final Map<String, AtomicReference<Bucket>> buckets = new ConcurrentHashMap<>();

    public TokenBucketRateLimiter(LimitResolver limitResolver, Duration refillPeriod,
                                  Duration idleTtl, TimeSource time) {
        if (refillPeriod.isZero() || refillPeriod.isNegative()) {
            throw new IllegalArgumentException("refillPeriod must be positive");
        }
        this.limitResolver = limitResolver;
        this.refillPeriodNanos = refillPeriod.toNanos();
        this.idleTtlNanos = idleTtl.toNanos();
        this.time = time;
    }

    @Override
    public RateLimitDecision tryAcquire(String key, int cost) {
        if (cost <= 0) {
            throw new IllegalArgumentException("cost must be positive");
        }
        ResolvedLimit resolved = limitResolver.resolve(key);
        long limit = resolved.limit();
        long capacityScaled = scale(limit);
        long costScaled = scale(cost);
        long now = time.nanoTime();

        AtomicReference<Bucket> ref = buckets.computeIfAbsent(
                key, k -> new AtomicReference<>(new Bucket(capacityScaled, now)));

        while (true) {
            Bucket current = ref.get();
            long tokens = refilled(current, capacityScaled, now);

            if (tokens < costScaled) {
                // Not enough capacity. Publish the refill so the wait we quote
                // stays accurate, but charge nothing. The caller can retry after the quoted wait.
                ref.compareAndSet(current, new Bucket(tokens, now));
                long waitNanos = nanosToAccumulate(costScaled - tokens, capacityScaled);
                long retryAfter = Math.max(1, ceilDiv(waitNanos, 1_000_000_000L));
                return RateLimitDecision.deny(limit, retryAfter, time.epochSecond() + retryAfter,
                        resolved.constrainedBy());
            }

            Bucket next = new Bucket(tokens - costScaled, now);
            if (ref.compareAndSet(current, next)) {
                long untilFull = ceilDiv(nanosToAccumulate(capacityScaled - next.tokensScaled(), capacityScaled),
                        1_000_000_000L);
                return RateLimitDecision.allow(limit, next.tokensScaled() / SCALE,
                        time.epochSecond() + untilFull);
            }
            // Another thread won the race; re-read and retry.
        }
    }

    /** Drops buckets untouched for longer than the configured idle TTL. */
    public int evictIdle() {
        long cutoff = time.nanoTime() - idleTtlNanos;
        int before = buckets.size();
        buckets.entrySet().removeIf(e -> e.getValue().get().lastRefillNanos() - cutoff < 0);
        return before - buckets.size();
    }

    /** Number of buckets currently held. Intended for tests and metrics. */
    public int trackedKeys() {
        return buckets.size();
    }

    private long refilled(Bucket bucket, long capacityScaled, long now) {
        long elapsed = now - bucket.lastRefillNanos();
        if (elapsed <= 0) {
            return Math.min(bucket.tokensScaled(), capacityScaled);
        }
        if (elapsed >= refillPeriodNanos) {
            return capacityScaled;
        }
        long added = (long) (capacityScaled * ((double) elapsed / refillPeriodNanos));
        return Math.min(bucket.tokensScaled() + added, capacityScaled);
    }

    private long nanosToAccumulate(long tokensScaled, long capacityScaled) {
        if (tokensScaled <= 0 || capacityScaled <= 0) {
            return 0;
        }
        return (long) (refillPeriodNanos * ((double) tokensScaled / capacityScaled));
    }

    /** Converts whole tokens to micro-tokens, saturating instead of overflowing. */
    private static long scale(long tokens) {
        return tokens > Long.MAX_VALUE / SCALE ? Long.MAX_VALUE : tokens * SCALE;
    }

    private static long ceilDiv(long value, long divisor) {
        return (value + divisor - 1) / divisor;
    }

    /** Immutable bucket state, swapped atomically. */
    private record Bucket(long tokensScaled, long lastRefillNanos) {
    }
}
