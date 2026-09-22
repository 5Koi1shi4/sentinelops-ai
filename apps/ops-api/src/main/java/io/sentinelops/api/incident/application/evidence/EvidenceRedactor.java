package io.sentinelops.api.incident.application.evidence;

import tools.jackson.databind.JsonNode;

/** Applies the evidence redaction policy before evidence is persisted or hashed. */
public interface EvidenceRedactor {

    RedactionResult redact(JsonNode input);
}
