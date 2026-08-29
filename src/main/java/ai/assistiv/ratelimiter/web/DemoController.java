package ai.assistiv.ratelimiter.web;

import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Endpoints for exercising the limiter end to end. */
@RestController
@RequestMapping("/api")
public class DemoController {

    @GetMapping("/ping")
    public Map<String, String> ping() {
        return Map.of("status", "ok");
    }

    /**
     * Answers slowly on purpose, standing in for a degraded dependency. Driving
     * traffic at this is how you watch the controller discover pressure and pull
     * the budget down without anyone changing configuration.
     */
    @GetMapping("/slow")
    public Map<String, Object> slow(@RequestParam(defaultValue = "500") long ms)
            throws InterruptedException {
        Thread.sleep(Math.clamp(ms, 0, 10_000));
        return Map.of("status", "ok", "tookMs", ms);
    }
}
