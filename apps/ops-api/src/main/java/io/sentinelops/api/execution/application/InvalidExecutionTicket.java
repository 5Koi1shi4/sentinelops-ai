package io.sentinelops.api.execution.application;

import io.sentinelops.api.shared.problem.ApiProblemException;
import org.springframework.http.HttpStatus;

public final class InvalidExecutionTicket extends ApiProblemException {

    public InvalidExecutionTicket(String message) {
        super(HttpStatus.UNAUTHORIZED, "invalid_execution_ticket", message);
    }
}
