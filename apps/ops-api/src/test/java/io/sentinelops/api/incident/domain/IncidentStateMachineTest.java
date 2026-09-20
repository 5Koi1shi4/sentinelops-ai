package io.sentinelops.api.incident.domain;

import static io.sentinelops.api.incident.domain.IncidentCommand.CONFIRM_RECOVERY;
import static io.sentinelops.api.incident.domain.IncidentStatus.DIAGNOSED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import java.util.Set;

class IncidentStateMachineTest {

    private static final Set<String> ALLOWED = Set.of(
            "DETECTED:START_TRIAGE",
            "DETECTED:SUPPRESS",
            "TRIAGING:RECORD_DIAGNOSIS",
            "TRIAGING:REQUEST_MANUAL_VERIFICATION",
            "TRIAGING:ESCALATE",
            "DIAGNOSED:REQUEST_APPROVAL",
            "DIAGNOSED:REQUEST_MANUAL_VERIFICATION",
            "AWAITING_APPROVAL:START_EXECUTION",
            "AWAITING_APPROVAL:RECEIVE_RECOVERY_SIGNAL",
            "AWAITING_APPROVAL:ESCALATE",
            "EXECUTING:RECEIVE_RECOVERY_SIGNAL",
            "EXECUTING:START_VERIFICATION",
            "EXECUTING:ESCALATE",
            "VERIFYING:CONFIRM_RECOVERY",
            "VERIFYING:RETRY_TRIAGE",
            "VERIFYING:ESCALATE",
            "ESCALATED:REQUEST_MANUAL_VERIFICATION");

    @ParameterizedTest
    @CsvSource({
        "DETECTED, START_TRIAGE, TRIAGING",
        "DETECTED, SUPPRESS, SUPPRESSED",
        "TRIAGING, RECORD_DIAGNOSIS, DIAGNOSED",
        "TRIAGING, REQUEST_MANUAL_VERIFICATION, VERIFYING",
        "TRIAGING, ESCALATE, ESCALATED",
        "DIAGNOSED, REQUEST_APPROVAL, AWAITING_APPROVAL",
        "DIAGNOSED, REQUEST_MANUAL_VERIFICATION, VERIFYING",
        "AWAITING_APPROVAL, START_EXECUTION, EXECUTING",
        "AWAITING_APPROVAL, RECEIVE_RECOVERY_SIGNAL, VERIFYING",
        "AWAITING_APPROVAL, ESCALATE, ESCALATED",
        "EXECUTING, RECEIVE_RECOVERY_SIGNAL, VERIFYING",
        "EXECUTING, START_VERIFICATION, VERIFYING",
        "EXECUTING, ESCALATE, ESCALATED",
        "VERIFYING, CONFIRM_RECOVERY, RESOLVED",
        "VERIFYING, RETRY_TRIAGE, TRIAGING",
        "VERIFYING, ESCALATE, ESCALATED",
        "ESCALATED, REQUEST_MANUAL_VERIFICATION, VERIFYING"
    })
    void allowsDeclaredTransition(
            IncidentStatus from, IncidentCommand command, IncidentStatus expected) {
        assertThat(IncidentStateMachine.next(from, command)).isEqualTo(expected);
    }

    @Test
    void modelCannotResolveIncident() {
        assertThatThrownBy(() -> IncidentStateMachine.next(DIAGNOSED, CONFIRM_RECOVERY))
                .isInstanceOf(IllegalIncidentTransition.class)
                .hasMessageContaining("DIAGNOSED")
                .hasMessageContaining("CONFIRM_RECOVERY");
    }

    @Test
    void rejectsEveryUndeclaredTransition() {
        for (var status : IncidentStatus.values()) {
            for (var command : IncidentCommand.values()) {
                if (ALLOWED.contains(status + ":" + command)) {
                    continue;
                }
                assertThatThrownBy(() -> IncidentStateMachine.next(status, command))
                        .as("%s + %s", status, command)
                        .isInstanceOf(IllegalIncidentTransition.class);
            }
        }
    }
}
