package io.sentinelops.api.incident.application.evidence;

/** Safe, provider-independent failure. Provider response bodies are never included. */
public class EvidenceSourceException extends RuntimeException {

    public EvidenceSourceException(String message) {
        super(message);
    }

    public EvidenceSourceException(String message, Throwable cause) {
        super(message, cause);
    }
}
