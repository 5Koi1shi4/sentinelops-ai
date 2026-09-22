package io.sentinelops.api.incident.evidence;

import static org.assertj.core.api.Assertions.assertThat;

import io.sentinelops.api.incident.application.evidence.CapturedEvidence;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CapturedEvidenceTest {

    private static final UUID INCIDENT_ID =
            UUID.fromString("0199a90a-9c00-7000-8000-000000000001");
    private static final UUID SERVICE_ID =
            UUID.fromString("0199a90a-9c00-7000-8000-000000000002");
    private static final Instant FROM = Instant.parse("2026-09-22T00:00:00Z");
    private static final Instant TO = Instant.parse("2026-09-22T00:05:00Z");

    @Test
    void capsNormalizedJsonWithRepeatedLabelsAndKeepsSortedPrefixHashStable() {
        var samples = new ArrayList<CapturedEvidence.EvidenceItem>();
        for (int index = 0; index < 40; index++) {
            samples.add(new CapturedEvidence.EvidenceItem(
                    "17900352" + String.format("%02d", index),
                    "value-" + index,
                    Map.of("message", "repeated-label-".repeat(24))));
        }

        var first = CapturedEvidence.create(
                INCIDENT_ID,
                SERVICE_ID,
                "prometheus",
                "checkout",
                FROM,
                TO,
                samples,
                List.of(),
                false,
                2_048);

        var reversed = new ArrayList<>(samples);
        java.util.Collections.reverse(reversed);
        var second = CapturedEvidence.create(
                INCIDENT_ID,
                SERVICE_ID,
                "prometheus",
                "checkout",
                FROM,
                TO,
                reversed,
                List.of(),
                false,
                2_048);

        assertThat(first.truncated()).isTrue();
        assertThat(first.serializedBytes()).isGreaterThan(0).isLessThanOrEqualTo(2_048);
        assertThat(first.items()).isNotEmpty();
        assertThat(samples).containsAll(first.items());
        assertThat(first.items()).isEqualTo(second.items());
        assertThat(first.warnings()).isEmpty();
        assertThat(first.contentHash()).matches("[a-f0-9]{64}")
                .isEqualTo(second.contentHash());
    }
}
