package ai.assistiv.ratelimiter.web;

import jakarta.servlet.http.HttpServletRequest;

/** Derives the identity a request is charged against. */
@FunctionalInterface
public interface ClientKeyResolver {

    String resolve(HttpServletRequest request);
}
