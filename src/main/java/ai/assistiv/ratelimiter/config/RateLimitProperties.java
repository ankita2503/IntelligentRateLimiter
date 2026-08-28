package ai.assistiv.ratelimiter.config;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Configuration for the enforcement layer. See {@code application.yml}. */
@ConfigurationProperties(prefix = "ratelimiter")
public class RateLimitProperties {

    /** How the limiter behaves when it cannot reach its own state. */
    public enum FailMode {
        /** Admit the request. A rate limiter must never be the outage. */
        OPEN,
        /** Reject the request. Only for endpoints where over-admitting is worse. */
        CLOSED
    }

    private boolean enabled = true;

    /** Requests allowed per {@link #refillPeriod}, per client. */
    private long limit = 100;

    /** The time it takes an empty bucket to refill completely. */
    private Duration refillPeriod = Duration.ofMinutes(1);

    /** Buckets untouched for this long are discarded so memory stays bounded. */
    private Duration idleTtl = Duration.ofMinutes(10);

    /** How often idle buckets are swept. */
    private Duration evictionInterval = Duration.ofSeconds(60);

    private FailMode failMode = FailMode.OPEN;

    /** Header carrying the client identity; falls back to the remote address. */
    private String keyHeader = "X-API-Key";

    /** Ant-style paths that bypass the limiter entirely. */
    private List<String> excludedPaths = List.of("/actuator/**");

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public long getLimit() {
        return limit;
    }

    public void setLimit(long limit) {
        this.limit = limit;
    }

    public Duration getRefillPeriod() {
        return refillPeriod;
    }

    public void setRefillPeriod(Duration refillPeriod) {
        this.refillPeriod = refillPeriod;
    }

    public Duration getIdleTtl() {
        return idleTtl;
    }

    public void setIdleTtl(Duration idleTtl) {
        this.idleTtl = idleTtl;
    }

    public Duration getEvictionInterval() {
        return evictionInterval;
    }

    public void setEvictionInterval(Duration evictionInterval) {
        this.evictionInterval = evictionInterval;
    }

    public FailMode getFailMode() {
        return failMode;
    }

    public void setFailMode(FailMode failMode) {
        this.failMode = failMode;
    }

    public String getKeyHeader() {
        return keyHeader;
    }

    public void setKeyHeader(String keyHeader) {
        this.keyHeader = keyHeader;
    }

    public List<String> getExcludedPaths() {
        return excludedPaths;
    }

    public void setExcludedPaths(List<String> excludedPaths) {
        this.excludedPaths = excludedPaths;
    }
}
