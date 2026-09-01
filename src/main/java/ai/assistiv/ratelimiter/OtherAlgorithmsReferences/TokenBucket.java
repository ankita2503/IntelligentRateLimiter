package ai.assistiv.ratelimiter.OtherAlgorithmsReferences;

public class TokenBucket {
    static long capacity;
    static double tokens;
    static double refilRate;
    static long lastRefilTime;


    public static void main(String[] args) {
        long now = System.currentTimeMillis();
        long elapsed = now-lastRefilTime;

        tokens = Math.min(capacity,tokens+(elapsed*refilRate));

        lastRefilTime = System.currentTimeMillis();

        if(tokens>1){
            //allow API
        } else {
            //Dont allow
        }
    }
}
