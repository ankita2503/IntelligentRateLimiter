package ai.assistiv.ratelimiter.core;

/**
 * Time as the limiter sees it. Split into a monotonic reading for measuring
 * elapsed time and a wall-clock reading for the {@code X-RateLimit-Reset}
 * header, so tests can drive both deterministically.
 */
public interface TimeSource {

    /** Monotonic nanoseconds; only differences are meaningful. */
    long nanoTime();

    /** Wall-clock seconds since the epoch. */
    long epochSecond();

    TimeSource SYSTEM = new TimeSource() {
        @Override
        public long nanoTime() {
            return System.nanoTime();
        }

        @Override
        public long epochSecond() {
            return System.currentTimeMillis() / 1000;
        }
    };
}
