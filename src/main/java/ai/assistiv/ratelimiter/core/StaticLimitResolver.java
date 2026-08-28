package ai.assistiv.ratelimiter.core;

/** Returns the same configured limit for every client. */
public class StaticLimitResolver implements LimitResolver {

    private final long limit;

    public StaticLimitResolver(long limit) {
        this.limit = limit;
    }

    @Override
    public long limitFor(String key) {
        return limit;
    }
}
