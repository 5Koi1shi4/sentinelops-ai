package io.sentinelops.api.execution.adapter.in.internal;

import io.sentinelops.api.execution.application.ExecutionApplicationService;
import io.sentinelops.api.execution.application.ExecutionApplicationService.AttemptPhaseCommand;
import io.sentinelops.api.execution.application.ExecutionApplicationService.AttemptPhaseView;
import io.sentinelops.api.execution.application.ExecutionApplicationService.ClaimView;
import io.sentinelops.api.execution.application.ExecutionApplicationService.CompletionCommand;
import io.sentinelops.api.execution.application.ExecutionApplicationService.HeartbeatView;
import io.sentinelops.api.execution.domain.Execution;
import io.sentinelops.api.identity.application.CurrentPrincipal;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/v1/executions")
public class ExecutorControlController {

    private final ExecutionApplicationService executions;

    public ExecutorControlController(ExecutionApplicationService executions) {
        this.executions = executions;
    }

    @PostMapping("/{executionId}:claim")
    ClaimView claim(
            @PathVariable UUID executionId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @AuthenticationPrincipal Jwt jwt) {
        var principal = CurrentPrincipal.from(jwt);
        return executions.claim(
                executionId,
                jwt.getSubject(),
                principal.principalKey(),
                idempotencyKey);
    }

    @PostMapping("/{executionId}:heartbeat")
    HeartbeatView heartbeat(
            @PathVariable UUID executionId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestHeader("X-SentinelOps-Execution-Ticket") String executionTicket,
            @Valid @RequestBody FenceBody body,
            @AuthenticationPrincipal Jwt jwt) {
        var principal = CurrentPrincipal.from(jwt);
        return executions.heartbeat(
                executionId,
                jwt.getSubject(),
                body.fencingToken(),
                executionTicket,
                principal.principalKey(),
                idempotencyKey);
    }

    @PostMapping("/{executionId}:complete")
    Execution complete(
            @PathVariable UUID executionId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestHeader("X-SentinelOps-Execution-Ticket") String executionTicket,
            @Valid @RequestBody AttemptBody body,
            @AuthenticationPrincipal Jwt jwt) {
        var principal = CurrentPrincipal.from(jwt);
        return executions.complete(
                executionId,
                jwt.getSubject(),
                body.fencingToken(),
                executionTicket,
                body.toCommand(),
                principal.principalKey(),
                idempotencyKey);
    }

    @PostMapping("/{executionId}:attempt-events")
    AttemptPhaseView recordAttemptPhase(
            @PathVariable UUID executionId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestHeader("X-SentinelOps-Execution-Ticket") String executionTicket,
            @Valid @RequestBody AttemptPhaseBody body,
            @AuthenticationPrincipal Jwt jwt) {
        var principal = CurrentPrincipal.from(jwt);
        return executions.recordAttemptPhase(
                executionId,
                jwt.getSubject(),
                body.fencingToken(),
                executionTicket,
                body.toCommand(),
                principal.principalKey(),
                idempotencyKey);
    }

    @PostMapping("/{executionId}:fail")
    Execution fail(
            @PathVariable UUID executionId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestHeader("X-SentinelOps-Execution-Ticket") String executionTicket,
            @Valid @RequestBody AttemptBody body,
            @AuthenticationPrincipal Jwt jwt) {
        var principal = CurrentPrincipal.from(jwt);
        return executions.fail(
                executionId,
                jwt.getSubject(),
                body.fencingToken(),
                executionTicket,
                body.toCommand(),
                principal.principalKey(),
                idempotencyKey);
    }

    public record FenceBody(@Min(1) long fencingToken) {}

    public record AttemptPhaseBody(
            @Min(1) long fencingToken,
            @NotBlank String stepId,
            @Min(1) int attemptNo,
            @NotBlank String phase,
            @NotNull Map<String, Object> metadata) {

        AttemptPhaseCommand toCommand() {
            return new AttemptPhaseCommand(stepId, attemptNo, phase, metadata);
        }
    }

    public record AttemptBody(
            @Min(1) long fencingToken,
            @NotBlank String stepId,
            @Min(1) int attemptNo,
            @NotBlank String adapterId,
            @NotBlank String adapterVersion,
            @NotBlank String requestHash,
            @NotNull Map<String, Object> sanitizedResult) {

        CompletionCommand toCommand() {
            return new CompletionCommand(
                    stepId,
                    attemptNo,
                    adapterId,
                    adapterVersion,
                    requestHash,
                    sanitizedResult);
        }
    }
}
