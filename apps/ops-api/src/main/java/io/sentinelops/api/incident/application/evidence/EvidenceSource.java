package io.sentinelops.api.incident.application.evidence;

/** A bounded, read-only source of external incident evidence. */
public interface EvidenceSource {

    String sourceType();

    CapturedEvidence capture(EvidenceQuery query, EvidenceBudget budget);
}
