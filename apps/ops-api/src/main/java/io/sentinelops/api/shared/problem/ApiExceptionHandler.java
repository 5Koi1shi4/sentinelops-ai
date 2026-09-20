package io.sentinelops.api.shared.problem;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import java.net.URI;
import java.util.NoSuchElementException;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.ErrorResponseException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.ServletRequestBindingException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(ApiProblemException.class)
    ResponseEntity<ProblemDetail> handleApiProblem(
            ApiProblemException failure, HttpServletRequest request) {
        return response(failure.status(), failure.errorCode(), failure.getMessage(), request);
    }

    @ExceptionHandler({
        MethodArgumentNotValidException.class,
        ConstraintViolationException.class,
        HttpMessageNotReadableException.class,
        ServletRequestBindingException.class,
        MethodArgumentTypeMismatchException.class,
        IllegalArgumentException.class
    })
    ResponseEntity<ProblemDetail> handleBadRequest(Exception failure, HttpServletRequest request) {
        return response(
                HttpStatus.BAD_REQUEST,
                "invalid_request",
                "The request is malformed or contains invalid values.",
                request);
    }

    @ExceptionHandler({NoSuchElementException.class, NoResourceFoundException.class})
    ResponseEntity<ProblemDetail> handleNotFound(Exception failure, HttpServletRequest request) {
        return response(
                HttpStatus.NOT_FOUND,
                "resource_not_found",
                "The requested resource was not found.",
                request);
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<ProblemDetail> handleConflict(
            DataIntegrityViolationException failure, HttpServletRequest request) {
        return response(
                HttpStatus.CONFLICT,
                "resource_conflict",
                "The request conflicts with the current resource state.",
                request);
    }

    @ExceptionHandler(OptimisticLockingFailureException.class)
    ResponseEntity<ProblemDetail> handleStaleResource(
            OptimisticLockingFailureException failure, HttpServletRequest request) {
        return response(
                HttpStatus.PRECONDITION_FAILED,
                "stale_resource_version",
                "The resource changed after it was read.",
                request);
    }

    @ExceptionHandler({DataAccessResourceFailureException.class, DataAccessException.class})
    ResponseEntity<ProblemDetail> handleDependencyUnavailable(
            DataAccessException failure, HttpServletRequest request) {
        return response(
                HttpStatus.SERVICE_UNAVAILABLE,
                "dependency_unavailable",
                "A required dependency is temporarily unavailable.",
                request);
    }

    @ExceptionHandler(ErrorResponseException.class)
    ResponseEntity<ProblemDetail> handleFrameworkError(
            ErrorResponseException failure, HttpServletRequest request) {
        var status = HttpStatus.resolve(failure.getStatusCode().value());
        var resolvedStatus = status == null ? HttpStatus.INTERNAL_SERVER_ERROR : status;
        return response(
                resolvedStatus,
                errorCodeFor(resolvedStatus),
                safeDetailFor(resolvedStatus),
                request);
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ProblemDetail> handleUnexpected(Exception failure, HttpServletRequest request) {
        return response(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "internal_error",
                "An unexpected error occurred.",
                request);
    }

    private ResponseEntity<ProblemDetail> response(
            HttpStatus status, String errorCode, String detail, HttpServletRequest request) {
        var problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(status.getReasonPhrase());
        problem.setType(URI.create("urn:sentinelops:error:" + errorCode));
        problem.setInstance(URI.create(request.getRequestURI()));
        problem.setProperty("errorCode", errorCode);
        problem.setProperty("traceId", traceId(request));
        return ResponseEntity.status(status).body(problem);
    }

    private String traceId(HttpServletRequest request) {
        var traceId = MDC.get("traceId");
        if (traceId != null && !traceId.isBlank()) {
            return traceId;
        }
        var requestId = request.getRequestId();
        return requestId == null || requestId.isBlank() ? UUID.randomUUID().toString() : requestId;
    }

    private String errorCodeFor(HttpStatus status) {
        return switch (status) {
            case UNAUTHORIZED -> "authentication_required";
            case FORBIDDEN -> "access_denied";
            case NOT_FOUND -> "resource_not_found";
            case CONFLICT -> "resource_conflict";
            case PRECONDITION_FAILED -> "stale_resource_version";
            case TOO_MANY_REQUESTS -> "rate_limit_exceeded";
            case SERVICE_UNAVAILABLE -> "dependency_unavailable";
            default -> status.is4xxClientError() ? "invalid_request" : "internal_error";
        };
    }

    private String safeDetailFor(HttpStatus status) {
        return status.is4xxClientError()
                ? "The request could not be completed."
                : "A required service could not complete the request.";
    }
}
