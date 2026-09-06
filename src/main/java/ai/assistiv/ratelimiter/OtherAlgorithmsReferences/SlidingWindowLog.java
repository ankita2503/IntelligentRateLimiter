package ai.assistiv.ratelimiter.OtherAlgorithmsReferences;
import java.util.*;

public class SlidingWindowLog {

    private final int maxRequests;
    private final long windowMillis;

    private final Deque<Long> timestamps = new ArrayDeque<>();

    public SlidingWindowLog(int maxRequests, long windowMillis) {
        this.maxRequests = maxRequests;
        this.windowMillis = windowMillis;
    }

    public synchronized boolean allowRequest() {
        long now = System.currentTimeMillis();

        // Remove requests outside the window
        while (!timestamps.isEmpty()
                && timestamps.peekFirst() <= now - windowMillis) {
            timestamps.pollFirst();
        }

        // Check limit
        if (timestamps.size() >= maxRequests) {
            return false;
        }

        // Add current request
        timestamps.addLast(now);

        return true;
    }

    public static void main(String[] args) {

        // Allow 5 requests every 10 seconds
        SlidingWindowLog limiter =
                new SlidingWindowLog(5, 10_000);

        for (int i = 1; i <= 10; i++) {

            if (limiter.allowRequest()) {
                System.out.println("Request " + i + " → ALLOWED");
            } else {
                System.out.println("Request " + i + " → BLOCKED");
            }
        }
    }
}
}
