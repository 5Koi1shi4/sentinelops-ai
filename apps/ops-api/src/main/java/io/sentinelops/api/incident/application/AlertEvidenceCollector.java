package io.sentinelops.api.incident.application;

import java.time.Instant;
import java.util.UUID;

public interface AlertEvidenceCollector {

    void capture(UUID incidentId, AlertEnvelope alert, Instant capturedAt);
}
