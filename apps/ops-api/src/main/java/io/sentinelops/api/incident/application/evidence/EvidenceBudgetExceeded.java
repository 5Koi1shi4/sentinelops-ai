package io.sentinelops.api.incident.application.evidence;

/** A configured evidence item, byte, or time bound was exceeded. */
public final class EvidenceBudgetExceeded extends EvidenceSourceException {

    public EvidenceBudgetExceeded(String message) {
        super(message);
    }

    public EvidenceBudgetExceeded(String message, Throwable cause) {
        super(message, cause);
    }
}
