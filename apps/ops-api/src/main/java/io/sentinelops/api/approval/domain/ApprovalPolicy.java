package io.sentinelops.api.approval.domain;

import io.sentinelops.api.diagnosis.domain.UnsupportedRiskException;
import io.sentinelops.api.knowledge.domain.RiskLevel;
import java.time.Duration;
import java.util.Objects;
import org.springframework.stereotype.Component;

@Component
public final class ApprovalPolicy {

    public ApprovalRequirements requirements(RiskLevel risk) {
        Objects.requireNonNull(risk, "risk");
        return switch (risk) {
            case R1 -> new ApprovalRequirements(1, true, Duration.ofMinutes(8));
            case R2 -> new ApprovalRequirements(2, true, Duration.ofMinutes(5));
            case R3 -> throw new UnsupportedRiskException();
            case R0 -> throw new IllegalArgumentException("R0 proposals do not require approval");
        };
    }

    public record ApprovalRequirements(
            int requiredApprovals,
            boolean independentApproverRequired,
            Duration validity) {

        public ApprovalRequirements {
            if (requiredApprovals < 1 || requiredApprovals > 2) {
                throw new IllegalArgumentException("requiredApprovals must be one or two");
            }
            Objects.requireNonNull(validity, "validity");
            if (validity.isNegative() || validity.isZero()) {
                throw new IllegalArgumentException("validity must be positive");
            }
        }
    }
}
