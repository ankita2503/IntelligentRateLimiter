package ai.assistiv.ratelimiter.core;

/**
 * Why a request was allowed or denied. Surfaced to callers via the
 * {@code X-RateLimit-Reason} header so limiting decisions are explainable.
 *
 * <p>Only {@link #ALLOWED} and {@link #QUOTA_EXCEEDED} are reachable today.
 * The remaining values are the vocabulary the adaptive limiter will use.
 */
public enum LimitReason {

    ALLOWED("allowed"),

    /** The client exhausted its configured budget. */
    QUOTA_EXCEEDED("quota"),

    /** The system is under pressure and shed load to protect its SLO. */
    SYSTEM_PRESSURE("system-pressure"),

    /** The client departed from its own established traffic pattern. */
    CLIENT_DEVIATION("client-deviation"),

    /** Capacity is scarce and this client is over its fair share. */
    FAIR_SHARE("fair-share");

    private final String wireValue;

    LimitReason(String wireValue) {
        this.wireValue = wireValue;
    }

    /** The value written to the {@code X-RateLimit-Reason} header. */
    public String wireValue() {
        return wireValue;
    }
}
