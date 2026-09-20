package io.sentinelops.api.approval;

import static io.sentinelops.api.knowledge.domain.RiskLevel.R1;
import static io.sentinelops.api.knowledge.domain.RiskLevel.R2;
import static io.sentinelops.api.knowledge.domain.RiskLevel.R3;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.sentinelops.api.approval.domain.ApprovalPolicy;
import io.sentinelops.api.approval.domain.ApprovalPolicy.ApprovalRequirements;
import io.sentinelops.api.diagnosis.domain.UnsupportedRiskException;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class ApprovalPolicyTest {

    private final ApprovalPolicy policy = new ApprovalPolicy();

    @Test
    void r1NeedsOneIndependentApproverForEightMinutes() {
        assertThat(policy.requirements(R1))
                .isEqualTo(new ApprovalRequirements(1, true, Duration.ofMinutes(8)));
    }

    @Test
    void r2NeedsTwoIndependentApproversForFiveMinutes() {
        assertThat(policy.requirements(R2))
                .isEqualTo(new ApprovalRequirements(2, true, Duration.ofMinutes(5)));
    }

    @Test
    void r3CannotCreateApprovalRequest() {
        assertThatThrownBy(() -> policy.requirements(R3))
                .isInstanceOf(UnsupportedRiskException.class);
    }
}
