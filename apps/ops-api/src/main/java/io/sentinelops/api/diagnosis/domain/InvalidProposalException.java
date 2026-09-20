package io.sentinelops.api.diagnosis.domain;

import io.sentinelops.api.shared.problem.ApiProblemException;
import org.springframework.http.HttpStatus;

public class InvalidProposalException extends ApiProblemException {

    public InvalidProposalException(String errorCode, String detail) {
        super(HttpStatus.UNPROCESSABLE_CONTENT, errorCode, errorCode + ": " + detail);
    }
}
