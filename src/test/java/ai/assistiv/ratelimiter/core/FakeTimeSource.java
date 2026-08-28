package ai.assistiv.ratelimiter.core;

import java.time.Duration;

/** Manually advanced clock so refill behaviour is testable without sleeping. */
class FakeTimeSource implements TimeSource {

    private long nanos = 1_000_000_000L;
    private long epochSecond = 1_700_000_000L;

    @Override
    public long nanoTime() {
        return nanos;
    }

    @Override
    public long epochSecond() {
        return epochSecond;
    }

    void advance(Duration duration) {
        nanos += duration.toNanos();
        epochSecond += duration.toSeconds();
    }
}
