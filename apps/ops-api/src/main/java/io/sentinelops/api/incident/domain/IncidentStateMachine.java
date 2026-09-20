package io.sentinelops.api.incident.domain;

import static io.sentinelops.api.incident.domain.IncidentCommand.CONFIRM_RECOVERY;
import static io.sentinelops.api.incident.domain.IncidentCommand.ESCALATE;
import static io.sentinelops.api.incident.domain.IncidentCommand.RECORD_DIAGNOSIS;
import static io.sentinelops.api.incident.domain.IncidentCommand.RECEIVE_RECOVERY_SIGNAL;
import static io.sentinelops.api.incident.domain.IncidentCommand.REQUEST_APPROVAL;
import static io.sentinelops.api.incident.domain.IncidentCommand.REQUEST_MANUAL_VERIFICATION;
import static io.sentinelops.api.incident.domain.IncidentCommand.RETRY_TRIAGE;
import static io.sentinelops.api.incident.domain.IncidentCommand.START_EXECUTION;
import static io.sentinelops.api.incident.domain.IncidentCommand.START_TRIAGE;
import static io.sentinelops.api.incident.domain.IncidentCommand.START_VERIFICATION;
import static io.sentinelops.api.incident.domain.IncidentCommand.SUPPRESS;
import static io.sentinelops.api.incident.domain.IncidentStatus.AWAITING_APPROVAL;
import static io.sentinelops.api.incident.domain.IncidentStatus.DETECTED;
import static io.sentinelops.api.incident.domain.IncidentStatus.DIAGNOSED;
import static io.sentinelops.api.incident.domain.IncidentStatus.ESCALATED;
import static io.sentinelops.api.incident.domain.IncidentStatus.EXECUTING;
import static io.sentinelops.api.incident.domain.IncidentStatus.RESOLVED;
import static io.sentinelops.api.incident.domain.IncidentStatus.SUPPRESSED;
import static io.sentinelops.api.incident.domain.IncidentStatus.TRIAGING;
import static io.sentinelops.api.incident.domain.IncidentStatus.VERIFYING;
import static java.util.Map.entry;

import java.util.Map;
import java.util.Objects;

public final class IncidentStateMachine {

    private static final Map<Key, IncidentStatus> ALLOWED = Map.ofEntries(
            entry(key(DETECTED, START_TRIAGE), TRIAGING),
            entry(key(DETECTED, SUPPRESS), SUPPRESSED),
            entry(key(TRIAGING, RECORD_DIAGNOSIS), DIAGNOSED),
            entry(key(TRIAGING, REQUEST_MANUAL_VERIFICATION), VERIFYING),
            entry(key(TRIAGING, ESCALATE), ESCALATED),
            entry(key(DIAGNOSED, REQUEST_APPROVAL), AWAITING_APPROVAL),
            entry(key(DIAGNOSED, REQUEST_MANUAL_VERIFICATION), VERIFYING),
            entry(key(AWAITING_APPROVAL, START_EXECUTION), EXECUTING),
            entry(key(AWAITING_APPROVAL, RECEIVE_RECOVERY_SIGNAL), VERIFYING),
            entry(key(AWAITING_APPROVAL, ESCALATE), ESCALATED),
            entry(key(EXECUTING, RECEIVE_RECOVERY_SIGNAL), VERIFYING),
            entry(key(EXECUTING, START_VERIFICATION), VERIFYING),
            entry(key(EXECUTING, ESCALATE), ESCALATED),
            entry(key(VERIFYING, CONFIRM_RECOVERY), RESOLVED),
            entry(key(VERIFYING, RETRY_TRIAGE), TRIAGING),
            entry(key(VERIFYING, ESCALATE), ESCALATED),
            entry(key(ESCALATED, REQUEST_MANUAL_VERIFICATION), VERIFYING));

    private IncidentStateMachine() {}

    public static IncidentStatus next(IncidentStatus current, IncidentCommand command) {
        Objects.requireNonNull(current, "current");
        Objects.requireNonNull(command, "command");

        var next = ALLOWED.get(key(current, command));
        if (next == null) {
            throw new IllegalIncidentTransition(current, command);
        }
        return next;
    }

    private static Key key(IncidentStatus status, IncidentCommand command) {
        return new Key(status, command);
    }

    private record Key(IncidentStatus status, IncidentCommand command) {}
}
