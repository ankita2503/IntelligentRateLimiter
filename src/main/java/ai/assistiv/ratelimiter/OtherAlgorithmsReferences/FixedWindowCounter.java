package ai.assistiv.ratelimiter.OtherAlgorithmsReferences;

public class FixedWindowCounter {
    public static void main(String[] args) {
        int limit = 5;                 // max requests
        long windowSize = 60_000;      // 1 minute
        long windowStart = System.currentTimeMillis();
        int count = 0;
        for (int i = 1; i <= 8; i++) {
            long now = System.currentTimeMillis();
            // New window → reset counter
            if (now - windowStart >= windowSize) {
                windowStart = now;
                count = 0;
            }
            // Rate limit check
            if (count < limit) {
                count++;
                System.out.println("Request " + i + " → ALLOWED");
            } else {
                System.out.println("Request " + i + " → REJECTED");
            }
        }
    }
}
