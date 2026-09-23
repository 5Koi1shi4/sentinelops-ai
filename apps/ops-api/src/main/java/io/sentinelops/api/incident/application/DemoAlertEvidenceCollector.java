package io.sentinelops.api.incident.application;

import io.sentinelops.api.incident.adapter.out.persistence.IncidentStore;
import io.sentinelops.api.shared.id.UuidV7Generator;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@Profile("demo")
@ConditionalOnProperty(name = "sentinelops.evidence.mode", havingValue = "fixture", matchIfMissing = true)
final class DemoAlertEvidenceCollector implements AlertEvidenceCollector {

    private static final String DEMO_SERVICE = "checkout-api";

    private final IncidentStore store;
    private final UuidV7Generator ids;

    DemoAlertEvidenceCollector(IncidentStore store, UuidV7Generator ids) {
        this.store = store;
        this.ids = ids;
    }

    @Override
    public void capture(UUID incidentId, AlertEnvelope alert, Instant capturedAt) {
        if (alert.status() != AlertEnvelope.AlertStatus.FIRING
                || !DEMO_SERVICE.equals(alert.serviceKey())) {
            return;
        }
        captureMetric(
                incidentId,
                "E-12",
                "db_pool_pending",
                "{\"metric\":\"db_pool_pending\",\"window\":\"5m\"}",
                "{\"db_pool_pending\":12}",
                capturedAt);
        captureMetric(
                incidentId,
                "E-13",
                "acquire_timeout_count",
                "{\"metric\":\"acquire_timeout_count\",\"window\":\"5m\"}",
                "{\"acquire_timeout_count\":3}",
                capturedAt);
    }

    private void captureMetric(
            UUID incidentId,
            String sourceRef,
            String metric,
            String querySpec,
            String redactedPayload,
            Instant capturedAt) {
        store.insertEvidenceIfAbsent(
                ids.generate(),
                incidentId,
                "metric",
                sourceRef,
                querySpec,
                redactedPayload,
                sha256(metric + ':' + redactedPayload),
                capturedAt);
    }

    private String sha256(String value) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required by the Java runtime", impossible);
        }
    }
}
