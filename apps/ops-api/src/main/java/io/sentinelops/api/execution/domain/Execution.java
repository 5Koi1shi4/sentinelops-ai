package io.sentinelops.api.execution.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record Execution(
        UUID id,
        UUID incidentId,
        UUID proposalId,
        UUID approvalRequestId,
        ExecutionStatus status,
        long fencingToken,
        long incidentVersion,
        Instant createdAt) {

    public Execution {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(incidentId, "incidentId");
        Objects.requireNonNull(proposalId, "proposalId");
        Objects.requireNonNull(approvalRequestId, "approvalRequestId");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(createdAt, "createdAt");
        if (fencingToken < 0 || incidentVersion < 0) {
            throw new IllegalArgumentException("versions must not be negative");
        }
    }
}
