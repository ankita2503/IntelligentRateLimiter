package ai.assistiv.ratelimiter.web;

import ai.assistiv.ratelimiter.config.RateLimitProperties;
import ai.assistiv.ratelimiter.core.RateLimitDecision;
import ai.assistiv.ratelimiter.core.RateLimiter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Enforces the limit on the request hot path.
 *
 * <p>This filter only reads a decision and writes headers. All adaptation
 * belongs behind {@link ai.assistiv.ratelimiter.core.LimitResolver}, off this
 * path.
 */
public class RateLimitFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RateLimitFilter.class);

    private final RateLimiter rateLimiter;
    private final ClientKeyResolver keyResolver;
    private final RateLimitProperties properties;
    private final AntPathMatcher pathMatcher = new AntPathMatcher();

    public RateLimitFilter(RateLimiter rateLimiter, ClientKeyResolver keyResolver,
                           RateLimitProperties properties) {
        this.rateLimiter = rateLimiter;
        this.keyResolver = keyResolver;
        this.properties = properties;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if (!properties.isEnabled()) {
            return true;
        }
        String path = request.getRequestURI();
        List<String> excluded = properties.getExcludedPaths();
        return excluded != null && excluded.stream().anyMatch(p -> pathMatcher.match(p, path));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        RateLimitDecision decision;
        try {
            decision = rateLimiter.tryAcquire(keyResolver.resolve(request));
        } catch (RuntimeException e) {
            // The limiter itself failed. Honour the configured fail mode rather
            // than letting an infrastructure fault become a user-visible error.
            if (properties.getFailMode() == RateLimitProperties.FailMode.CLOSED) {
                log.error("Rate limiter failed; failing closed", e);
                response.sendError(HttpStatus.SERVICE_UNAVAILABLE.value(), "Rate limiter unavailable");
                return;
            }
            log.error("Rate limiter failed; failing open", e);
            chain.doFilter(request, response);
            return;
        }

        writeHeaders(response, decision);
        if (decision.allowed()) {
            chain.doFilter(request, response);
            return;
        }
        writeRejection(response, decision);
    }

    private void writeHeaders(HttpServletResponse response, RateLimitDecision decision) {
        response.setHeader("X-RateLimit-Limit", Long.toString(decision.limit()));
        response.setHeader("X-RateLimit-Remaining", Long.toString(decision.remaining()));
        response.setHeader("X-RateLimit-Reset", Long.toString(decision.resetEpochSecond()));
        response.setHeader("X-RateLimit-Reason", decision.reason().wireValue());
    }

    private void writeRejection(HttpServletResponse response, RateLimitDecision decision)
            throws IOException {
        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setHeader(HttpHeaders.RETRY_AFTER, Long.toString(decision.retryAfterSeconds()));
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.getWriter().write("""
                {"type":"about:blank",\
                "title":"Too Many Requests",\
                "status":429,\
                "detail":"Rate limit exceeded. Retry after %d seconds.",\
                "reason":"%s"}"""
                .formatted(decision.retryAfterSeconds(), decision.reason().wireValue()));
    }
}
