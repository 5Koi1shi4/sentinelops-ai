package io.sentinelops.api.execution.application;

import io.sentinelops.api.knowledge.domain.RiskLevel;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public record ExecutionTicketClaims(
        String jti,
        UUID executionId,
        UUID incidentId,
        UUID proposalId,
        UUID runbookId,
        UUID runbookVersionId,
        String runbookChecksum,
        Map<String, Object> parameters,
        String target,
        RiskLevel risk,
        String adapterId,
        long fencingToken,
        String issuer,
        List<String> audience,
        Instant issuedAt,
        Instant notBefore,
        Instant expiresAt) {

    public ExecutionTicketClaims {
        jti = requireText(jti, "jti");
        Objects.requireNonNull(executionId, "executionId");
        Objects.requireNonNull(incidentId, "incidentId");
        Objects.requireNonNull(proposalId, "proposalId");
        Objects.requireNonNull(runbookId, "runbookId");
        Objects.requireNonNull(runbookVersionId, "runbookVersionId");
        runbookChecksum = requireText(runbookChecksum, "runbookChecksum");
        parameters = Map.copyOf(Objects.requireNonNull(parameters, "parameters"));
        target = requireText(target, "target");
        Objects.requireNonNull(risk, "risk");
        adapterId = requireText(adapterId, "adapterId");
        if (fencingToken <= 0) {
            throw new IllegalArgumentException("fencingToken must be positive");
        }
        issuer = requireText(issuer, "issuer");
        audience = List.copyOf(Objects.requireNonNull(audience, "audience"));
        if (audience.isEmpty() || audience.stream().anyMatch(value -> value == null || value.isBlank())) {
            throw new IllegalArgumentException("audience must contain nonblank values");
        }
        Objects.requireNonNull(issuedAt, "issuedAt");
        Objects.requireNonNull(notBefore, "notBefore");
        Objects.requireNonNull(expiresAt, "expiresAt");
        if (!expiresAt.isAfter(issuedAt) || notBefore.isAfter(issuedAt)) {
            throw new IllegalArgumentException("ticket timestamps are inconsistent");
        }
    }

    public void requireRunbookChecksum(String expected) {
        if (!runbookChecksum.equals(expected)) {
            throw new InvalidExecutionTicket("Execution ticket Runbook checksum does not match");
        }
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }
}
