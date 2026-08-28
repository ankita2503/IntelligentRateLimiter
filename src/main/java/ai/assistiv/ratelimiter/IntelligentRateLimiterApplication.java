package ai.assistiv.ratelimiter;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class IntelligentRateLimiterApplication {

    public static void main(String[] args) {
        SpringApplication.run(IntelligentRateLimiterApplication.class, args);
    }
}
