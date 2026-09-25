package io.sentinelops.api.incident.webhook;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.sentinelops.api.incident.adapter.in.webhook.WebhookRateLimiter;
import io.sentinelops.api.incident.adapter.in.webhook.WebhookSecurityProperties;
import io.sentinelops.api.shared.problem.ApiProblemException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.env.MockEnvironment;

class WebhookRateLimiterTest {
    private static final String SECRET = "task7-only-webhook-secret-more-than-32-bytes";

    @Test
    void demoLoadSourceAllowsConfiguredBurstWhileOtherSourcesKeepDefaultLimit() {
        var environment = new MockEnvironment()
                .withProperty("sentinelops.webhook.source-refs",
                        "load-test=env:SENTINELOPS_TASK7_SECRET,load-default=env:SENTINELOPS_TASK7_SECRET")
                .withProperty("SENTINELOPS_TASK7_SECRET", SECRET)
                .withProperty("SENTINELOPS_WEBHOOK_LOAD_TEST_LIMIT_PER_MINUTE", "10000")
                .withProperty("SENTINELOPS_WEBHOOK_LOAD_TEST_BURST", "200");
        environment.setActiveProfiles("demo");
        var limiter = new WebhookRateLimiter(new WebhookSecurityProperties(environment));

        assertThatCode(() -> {
            for (int index = 0; index < 200; index++) limiter.acquire("load-test");
        }).doesNotThrowAnyException();
        for (int index = 0; index < 20; index++) limiter.acquire("load-default");
        assertThatThrownBy(() -> limiter.acquire("load-default"))
                .isInstanceOfSatisfying(ApiProblemException.class,
                        failure -> org.assertj.core.api.Assertions.assertThat(failure.status())
                                .isEqualTo(HttpStatus.TOO_MANY_REQUESTS));
    }

    @Test
    void productionRejectsDemoOnlyRateLimitOverride() {
        var environment = new MockEnvironment()
                .withProperty("sentinelops.webhook.source-refs", "load-test=env:SENTINELOPS_TASK7_SECRET")
                .withProperty("SENTINELOPS_TASK7_SECRET", SECRET)
                .withProperty("SENTINELOPS_WEBHOOK_LOAD_TEST_LIMIT_PER_MINUTE", "10000")
                .withProperty("SENTINELOPS_WEBHOOK_LOAD_TEST_BURST", "200");
        environment.setActiveProfiles("production");

        assertThatThrownBy(() -> new WebhookRateLimiter(
                new WebhookSecurityProperties(environment)))
                .isInstanceOf(IllegalStateException.class);
    }
}
