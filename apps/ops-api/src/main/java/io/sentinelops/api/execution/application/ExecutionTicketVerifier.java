package io.sentinelops.api.execution.application;

import java.time.Instant;
import java.util.UUID;

public interface ExecutionTicketVerifier {

    VerifiedTicket verify(
            String token, String expectedIssuer, String expectedAudience, Instant now);

    record VerifiedTicket(
            String jti,
            UUID executionId,
            long fencingToken,
            String runbookChecksum,
            String stepId,
            String adapterId,
            Instant expiresAt) {}
}
