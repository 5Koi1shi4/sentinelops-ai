package io.sentinelops.api.incident.adapter.in.webhook;

import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import io.sentinelops.api.shared.problem.ApiProblemException;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/** Per-source fixed-minute ceiling and continuously refilled burst. */
@Component
public final class WebhookRateLimiter {
    private final Map<String, RateLimiter> minuteLimits;
    private final Map<String, BurstBucket> bursts;

    public WebhookRateLimiter(WebhookSecurityProperties properties) {
        var limits = new HashMap<String, RateLimiter>();
        var buckets = new HashMap<String, BurstBucket>();
        for (var source : properties.sources()) {
            var policy = properties.ratePolicyFor(source);
            var config = RateLimiterConfig.custom()
                    .limitForPeriod(policy.limitPerMinute())
                    .limitRefreshPeriod(Duration.ofMinutes(1))
                    .timeoutDuration(Duration.ZERO)
                    .build();
            limits.put(source, RateLimiter.of(source, config));
            buckets.put(source, new BurstBucket(policy.burst(), policy.limitPerMinute() / 60.0));
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
        private final double capacity;
        private final double refillPerSecond;
        private double tokens;
        private long updatedNanos = System.nanoTime();

        private BurstBucket(int capacity, double refillPerSecond) {
            this.capacity = capacity;
            this.refillPerSecond = refillPerSecond;
            tokens = capacity;
        }

        synchronized boolean tryAcquire() {
            long now = System.nanoTime();
            long elapsed = Math.max(0L, now - updatedNanos);
            tokens = Math.min(capacity, tokens + elapsed / 1_000_000_000.0 * refillPerSecond);
            updatedNanos = now;
            if (tokens < 1.0) return false;
            tokens -= 1.0;
            return true;
        }
    }
}
