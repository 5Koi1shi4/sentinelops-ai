package io.sentinelops.api.execution.adapter.out.stream;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.sentinelops.api.execution.adapter.out.persistence.OutboxStore;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(
        name = "sentinelops.outbox.relay-enabled",
        havingValue = "true",
        matchIfMissing = true)
public class OutboxRelay {

    private static final Logger LOGGER = LoggerFactory.getLogger(OutboxRelay.class);

    private final OutboxStore store;
    private final ExecutionStreamPublisher publisher;
    private final String relayId;
    private final int batchSize;
    private final int maxErrorLength;
    private final Duration retryBaseDelay;
    private final Duration retryMaxDelay;
    private final int maxPublishAttempts;
    private final Counter published;
    private final Counter failures;
    private final Counter quarantined;
    private final AtomicLong backlog = new AtomicLong();
    private final AtomicLong quarantineBacklog = new AtomicLong();

    public OutboxRelay(
            OutboxStore store,
            ExecutionStreamPublisher publisher,
            MeterRegistry meters,
            @Value("${sentinelops.outbox.relay-id:}") String relayId,
            @Value("${sentinelops.outbox.batch-size:50}") int batchSize,
            @Value("${sentinelops.outbox.max-error-length:512}") int maxErrorLength,
            @Value("${sentinelops.outbox.retry-base-delay:PT1S}") Duration retryBaseDelay,
            @Value("${sentinelops.outbox.retry-max-delay:PT5M}") Duration retryMaxDelay,
            @Value("${sentinelops.outbox.max-publish-attempts:20}")
                    int maxPublishAttempts) {
        this.store = Objects.requireNonNull(store, "store");
        this.publisher = Objects.requireNonNull(publisher, "publisher");
        Objects.requireNonNull(meters, "meters");
        this.relayId = relayId == null || relayId.isBlank()
                ? "ops-api-" + java.util.UUID.randomUUID()
                : relayId.trim();
        if (batchSize < 1 || batchSize > 50) {
            throw new IllegalArgumentException("batchSize must be between 1 and 50");
        }
        if (maxErrorLength < 64 || maxErrorLength > 2048) {
            throw new IllegalArgumentException("maxErrorLength must be between 64 and 2048");
        }
        this.retryBaseDelay = positive(retryBaseDelay, "retryBaseDelay");
        this.retryMaxDelay = positive(retryMaxDelay, "retryMaxDelay");
        if (this.retryMaxDelay.compareTo(this.retryBaseDelay) < 0) {
            throw new IllegalArgumentException("retryMaxDelay must not be shorter than retryBaseDelay");
        }
        if (maxPublishAttempts < 1 || maxPublishAttempts > 1000) {
            throw new IllegalArgumentException(
                    "maxPublishAttempts must be between 1 and 1000");
        }
        this.batchSize = batchSize;
        this.maxErrorLength = maxErrorLength;
        this.maxPublishAttempts = maxPublishAttempts;
        this.published = Counter.builder("sentinelops.outbox.published")
                .description("Execution Outbox events published to the Stream")
                .register(meters);
        this.failures = Counter.builder("sentinelops.outbox.publish.failures")
                .description("Execution Outbox publication failures")
                .register(meters);
        this.quarantined = Counter.builder("sentinelops.outbox.quarantined")
                .description("Execution Outbox events isolated after bounded retries")
                .register(meters);
        Gauge.builder("sentinelops.outbox.backlog", backlog, AtomicLong::get)
                .description("Unpublished execution Outbox events")
                .register(meters);
        Gauge.builder(
                        "sentinelops.outbox.quarantine.backlog",
                        quarantineBacklog,
                        AtomicLong::get)
                .description("Execution Outbox events requiring operator intervention")
                .register(meters);
    }

    @Scheduled(fixedDelayString = "${sentinelops.outbox.relay-interval:PT1S}")
    public int relayOnce() {
        int publishedCount = 0;
        for (var row : store.claimBatch(relayId, batchSize)) {
            try {
                publisher.publish(row.eventId(), row.executionId(), row.traceparent());
                if (!store.markPublished(row.eventId(), relayId)) {
                    throw new IllegalStateException("Outbox claim was lost before publication commit");
                }
                published.increment();
                publishedCount++;
            } catch (RuntimeException failure) {
                failures.increment();
                boolean quarantine = row.publishAttempts() >= maxPublishAttempts;
                store.releaseFailed(
                        row.eventId(),
                        relayId,
                        safeError(failure),
                        retryDelay(row.publishAttempts()),
                        quarantine);
                if (quarantine) {
                    quarantined.increment();
                    LOGGER.error(
                            "Execution Outbox event quarantined eventId={} executionId={} attempts={} errorType={}",
                            row.eventId(),
                            row.executionId(),
                            row.publishAttempts(),
                            failure.getClass().getSimpleName());
                } else {
                    LOGGER.warn(
                            "Execution Outbox publication failed eventId={} executionId={} attempts={} errorType={}",
                            row.eventId(),
                            row.executionId(),
                            row.publishAttempts(),
                            failure.getClass().getSimpleName());
                }
            }
        }
        backlog.set(store.backlogCount());
        quarantineBacklog.set(store.quarantinedCount());
        return publishedCount;
    }

    private Duration retryDelay(int publishAttempts) {
        int exponent = Math.min(Math.max(0, publishAttempts - 1), 30);
        long multiplier = 1L << exponent;
        long maximumMillis = retryMaxDelay.toMillis();
        long baseMillis = retryBaseDelay.toMillis();
        long delayMillis = baseMillis > maximumMillis / multiplier
                ? maximumMillis
                : Math.min(maximumMillis, baseMillis * multiplier);
        return Duration.ofMillis(delayMillis);
    }

    private String safeError(RuntimeException failure) {
        String value = failure.getClass().getSimpleName();
        return value.length() <= maxErrorLength ? value : value.substring(0, maxErrorLength);
    }

    private static Duration positive(Duration value, String field) {
        if (value == null || value.isNegative() || value.isZero()) {
            throw new IllegalArgumentException(field + " must be positive");
        }
        if (value.toMillis() == 0) {
            throw new IllegalArgumentException(field + " must be at least one millisecond");
        }
        return value;
    }
}
