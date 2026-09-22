package io.sentinelops.api.incident.evidence;

import static org.assertj.core.api.Assertions.*;

import io.sentinelops.api.incident.application.evidence.*;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class EvidencePlanTest {
    private final Instant from = Instant.parse("2026-09-22T00:00:00Z");
    private final EvidenceBudget budget = new EvidenceBudget(20, 4096, Duration.ofMinutes(15));

    @Test void copiesPlanRequestsAndQueryParameters() {
        var parameters = new HashMap<>(Map.of("instance", "checkout-1"));
        var request = new EvidenceRequest("loki", "logs", parameters);
        var requests = new ArrayList<>(List.of(request));
        var plan = new EvidencePlan(UUID.randomUUID(), from, from.plusSeconds(300), requests, budget);
        requests.clear();
        parameters.clear();
        assertThat(plan.requests()).hasSize(1);
        assertThat(plan.requests().getFirst().parameters()).containsEntry("instance", "checkout-1");
        assertThatThrownBy(() -> plan.requests().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test void rejectsUnboundedPlansBeforeCapture() {
        var request = new EvidenceRequest("loki", "logs", Map.of());
        assertThatThrownBy(() -> new EvidencePlan(UUID.randomUUID(), from, from.plusSeconds(300), List.of(), budget))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new EvidencePlan(UUID.randomUUID(), from, from.plusSeconds(300),
                java.util.Collections.nCopies(7, request), budget)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new EvidencePlan(UUID.randomUUID(), Instant.MIN, Instant.MAX, List.of(request), budget))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new EvidenceRequest("shell", "logs", Map.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new EvidenceRequest("loki", "https://outside.invalid", Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
