package ai.assistiv.ratelimiter.adaptive;

import ai.assistiv.ratelimiter.core.TimeSource;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * Traffic signals over a sliding window, kept as a ring of time slots.
 *
 * <p>A slot is reset when time moves past it and it comes round again, so old
 * traffic ages out without anyone sweeping it. Reads merge the slots that are
 * still inside the window. Counters are approximate under concurrency — a
 * control loop wants a cheap, current signal more than an exact one.
 */
public final class SlidingWindowTrafficMetrics implements TrafficMetrics {

    private final Slot[] slots;
    private final long slotNanos;
    private final double windowSeconds;
    private final TimeSource time;
    private final AtomicInteger inFlight = new AtomicInteger();

    public SlidingWindowTrafficMetrics(Duration window, int slotCount, TimeSource time) {
        if (slotCount < 2) {
            throw new IllegalArgumentException("need at least 2 slots to slide");
        }
        this.slots = new Slot[slotCount];
        for (int i = 0; i < slotCount; i++) {
            this.slots[i] = new Slot();
        }
        this.slotNanos = window.toNanos() / slotCount;
        this.windowSeconds = window.toNanos() / 1_000_000_000.0;
        this.time = time;
    }

    @Override
    public void recordCompletion(long latencyNanos, boolean failed) {
        Slot slot = currentSlot();
        slot.latencies.record(latencyNanos);
        slot.completed.increment();
        if (failed) {
            slot.failed.increment();
        }
    }

    @Override
    public void recordRejection() {
        currentSlot().rejected.increment();
    }

    @Override
    public void requestStarted() {
        currentSlot().admitted.increment();
        inFlight.incrementAndGet();
    }

    @Override
    public void requestFinished() {
        inFlight.decrementAndGet();
    }

    @Override
    public HealthSnapshot snapshot() {
        long generation = time.nanoTime() / slotNanos;
        long oldest = generation - slots.length + 1;

        long[] merged = new long[LatencyHistogram.bucketCount()];
        long completed = 0;
        long failed = 0;
        long admitted = 0;
        long rejected = 0;

        for (Slot slot : slots) {
            long slotGeneration = slot.generation.get();
            if (slotGeneration < oldest || slotGeneration > generation) {
                continue;  // aged out, or not yet used
            }
            slot.latencies.addTo(merged);
            completed += slot.completed.sum();
            failed += slot.failed.sum();
            admitted += slot.admitted.sum();
            rejected += slot.rejected.sum();
        }

        double errorRate = completed == 0 ? 0 : (double) failed / completed;
        return new HealthSnapshot(
                LatencyHistogram.percentileOf(merged, 0.99),
                LatencyHistogram.percentileOf(merged, 0.50),
                errorRate, admitted, rejected, inFlight.get(), windowSeconds);
    }

    private Slot currentSlot() {
        long generation = time.nanoTime() / slotNanos;
        Slot slot = slots[(int) Math.floorMod(generation, slots.length)];
        long observed = slot.generation.get();
        if (observed != generation && slot.generation.compareAndSet(observed, generation)) {
            // We own the rotation; clear the previous occupant of this position.
            slot.reset();
        }
        return slot;
    }

    private static final class Slot {
        final LatencyHistogram latencies = new LatencyHistogram();
        final LongAdder completed = new LongAdder();
        final LongAdder failed = new LongAdder();
        final LongAdder admitted = new LongAdder();
        final LongAdder rejected = new LongAdder();
        final AtomicLong generation = new AtomicLong(Long.MIN_VALUE);

        void reset() {
            latencies.reset();
            completed.reset();
            failed.reset();
            admitted.reset();
            rejected.reset();
        }
    }
}
