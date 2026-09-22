package io.sentinelops.api.incident.application.evidence;

import java.util.List;
import java.util.UUID;

/** Frozen evidence access; production persistence and isolated Eval fixtures share the tool contract. */
public interface EvidenceCapture {
    List<EvidenceSnapshot> captureAndFreeze(UUID incidentId, UUID runId, EvidencePlan plan);
    EvidenceSnapshot getEvidence(UUID incidentId, UUID runId, UUID serviceId, UUID evidenceId);
}
