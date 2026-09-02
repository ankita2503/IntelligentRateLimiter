package ai.assistiv.ratelimiter.OtherAlgorithmsReferences;

public class LeakyBucket {

    private final int capacity;
    private final int leakRate; // requests per second

    private double water;
    private long lastLeakTime;

    public LeakyBucket(int capacity, int leakRate) {
        this.capacity = capacity;
        this.leakRate = leakRate;
        this.water = 0;
        this.lastLeakTime = System.nanoTime();
    }

    public synchronized boolean allowRequest() {

        // 1. Calculate how much water has leaked
        long now = System.nanoTime();

        double elapsedSeconds =
                (now - lastLeakTime) / 1_000_000_000.0;

        double leaked = elapsedSeconds * leakRate;

        water = Math.max(0, water - leaked);

        lastLeakTime = now;

        // 2. Add the new request
        if (water + 1 > capacity) {
            return false; // bucket is full
        }

        water += 1;

        return true;
    }
}