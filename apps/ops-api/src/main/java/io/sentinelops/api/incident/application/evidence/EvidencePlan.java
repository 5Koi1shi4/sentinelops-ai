package io.sentinelops.api.incident.application.evidence;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Server-owned scope; model input cannot change service, window, or budget. */
public record EvidencePlan(UUID serviceId, Instant from, Instant to,
                           List<EvidenceRequest> requests, EvidenceBudget budget) {
    public EvidencePlan {
        Objects.requireNonNull(serviceId, "serviceId");
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        Objects.requireNonNull(budget, "budget");
        requests = List.copyOf(requests);
        if (requests.isEmpty() || requests.size() > 6 || !from.isBefore(to)
                || Duration.between(from, to).compareTo(budget.maxWindow()) > 0) {
            throw new IllegalArgumentException("invalid evidence plan scope or budget");
        }
    }
}
