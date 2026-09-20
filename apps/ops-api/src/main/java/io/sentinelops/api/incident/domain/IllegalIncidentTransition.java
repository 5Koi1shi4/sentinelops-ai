package io.sentinelops.api.incident.domain;

import io.sentinelops.api.shared.problem.ApiProblemException;
import org.springframework.http.HttpStatus;

public final class IllegalIncidentTransition extends ApiProblemException {

    private final IncidentStatus current;
    private final IncidentCommand command;

    public IllegalIncidentTransition(IncidentStatus current, IncidentCommand command) {
        super(
                HttpStatus.CONFLICT,
                "illegal_incident_transition",
                "Incident transition is not allowed: " + current + " + " + command);
        this.current = current;
        this.command = command;
    }

    public IncidentStatus current() {
        return current;
    }

    public IncidentCommand command() {
        return command;
    }
}
