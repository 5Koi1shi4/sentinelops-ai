package io.sentinelops.api.shared.problem;

import java.util.Objects;
import org.springframework.http.HttpStatus;

public class ApiProblemException extends RuntimeException {

    private final HttpStatus status;
    private final String errorCode;

    public ApiProblemException(HttpStatus status, String errorCode, String detail) {
        super(detail);
        this.status = Objects.requireNonNull(status, "status");
        this.errorCode = Objects.requireNonNull(errorCode, "errorCode");
    }

    public HttpStatus status() {
        return status;
    }

    public String errorCode() {
        return errorCode;
    }
}
