package io.sentinelops.api.incident.adapter.in.webhook;

import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import io.sentinelops.api.shared.problem.ApiProblemException;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/** Per-source 60/minute ceiling plus a continuously refilled 20-request burst. */
@Component
public final class WebhookRateLimiter {
    private final Map<String, RateLimiter> minuteLimits;
    private final Map<String, BurstBucket> bursts;

    public WebhookRateLimiter(WebhookSecurityProperties properties) {
        var config = RateLimiterConfig.custom()
                .limitForPeriod(60)
                .limitRefreshPeriod(Duration.ofMinutes(1))
                .timeoutDuration(Duration.ZERO)
                .build();
        var registry = RateLimiterRegistry.of(config);
        var limits = new HashMap<String, RateLimiter>();
        var buckets = new HashMap<String, BurstBucket>();
        for (var source : properties.sources()) {
            limits.put(source, registry.rateLimiter(source));
            buckets.put(source, new BurstBucket());
        }
        minuteLimits = Map.copyOf(limits);
        bursts = Map.copyOf(buckets);
    }

    public void acquire(String source) {
        var burst = bursts.get(source);
        var minute = minuteLimits.get(source);
        if (burst == null || minute == null) {
            throw new ApiProblemException(HttpStatus.UNAUTHORIZED,
                    "WEBHOOK_AUTHENTICATION_FAILED", "Webhook authentication failed.");
        }
        if (!burst.tryAcquire() || !minute.acquirePermission()) {
            throw new ApiProblemException(HttpStatus.TOO_MANY_REQUESTS,
                    "WEBHOOK_RATE_LIMITED", "Webhook source rate limit exceeded.");
        }
    }

    private static final class BurstBucket {
        private double tokens = 20.0;
        private long updatedNanos = System.nanoTime();

        synchronized boolean tryAcquire() {
            long now = System.nanoTime();
            long elapsed = Math.max(0L, now - updatedNanos);
            tokens = Math.min(20.0, tokens + elapsed / 1_000_000_000.0);
            updatedNanos = now;
            if (tokens < 1.0) return false;
            tokens -= 1.0;
            return true;
        }
    }
}
