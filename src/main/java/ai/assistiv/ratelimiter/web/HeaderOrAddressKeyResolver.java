package ai.assistiv.ratelimiter.web;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.util.StringUtils;

/**
 * Uses the configured API-key header when present, otherwise the remote
 * address. The address fallback is coarse — behind a proxy every caller shares
 * one key — so real deployments should authenticate first and key on the
 * principal.
 */
public class HeaderOrAddressKeyResolver implements ClientKeyResolver {

    private final String keyHeader;

    public HeaderOrAddressKeyResolver(String keyHeader) {
        this.keyHeader = keyHeader;
    }

    @Override
    public String resolve(HttpServletRequest request) {
        String header = request.getHeader(keyHeader);
        if (StringUtils.hasText(header)) {
            return "key:" + header;
        }
        String remote = request.getRemoteAddr();
        return "addr:" + (remote == null ? "unknown" : remote);
    }
}
