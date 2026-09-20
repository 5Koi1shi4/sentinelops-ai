package io.sentinelops.api.approval.domain;

import io.sentinelops.api.shared.problem.ApiProblemException;
import org.springframework.http.HttpStatus;

public final class SeparationOfDutiesException extends ApiProblemException {

    public SeparationOfDutiesException() {
        super(
                HttpStatus.FORBIDDEN,
                "SEPARATION_OF_DUTIES_REQUIRED",
                "The requester cannot approve their own change.");
    }
}
