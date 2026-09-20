package io.sentinelops.executor.stream;

import io.sentinelops.executor.controlplane.ControlPlaneClient;
import io.sentinelops.executor.controlplane.ControlPlaneClient.AttemptReport;
import io.sentinelops.executor.controlplane.ControlPlaneClient.TerminalControlPlaneException;
import io.sentinelops.executor.runbook.ExecutionStepResult;
import io.sentinelops.executor.runbook.IdempotencyContext;
import io.sentinelops.executor.runbook.RunbookDispatcher;
import io.sentinelops.executor.runbook.UnsupportedRunbookStepException;
import io.sentinelops.executor.ticket.ExecutionTicketVerifier;
import io.sentinelops.executor.ticket.InvalidExecutionTicket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class ExecutionMessageListener {

    private final ControlPlaneClient controlPlane;
    private final ExecutionTicketVerifier verifier;
    private final RunbookDispatcher dispatcher;
    private final ExecutionLeaseHeartbeat leaseHeartbeat;
    private final ExecutionMessageAcknowledger acknowledger;

    public ExecutionMessageListener(
            ControlPlaneClient controlPlane,
            ExecutionTicketVerifier verifier,
            RunbookDispatcher dispatcher,
            ExecutionLeaseHeartbeat leaseHeartbeat,
            ExecutionMessageAcknowledger acknowledger) {
        this.controlPlane = Objects.requireNonNull(controlPlane, "controlPlane");
        this.verifier = Objects.requireNonNull(verifier, "verifier");
        this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher");
        this.leaseHeartbeat = Objects.requireNonNull(leaseHeartbeat, "leaseHeartbeat");
        this.acknowledger = Objects.requireNonNull(acknowledger, "acknowledger");
    }

    public void onMessage(ExecutionMessage message) {
        Objects.requireNonNull(message, "message");
        String attemptId = UUID.randomUUID().toString();
        try {
            var claim = controlPlane.claim(
                    message.executionId(), attemptKey(message, "claim", attemptId));
            if (!claim.executionId().equals(message.executionId())) {
                throw new InvalidExecutionTicket(
                        "Control-plane claim execution ID does not match the message");
            }
            var step = verifier.verify(claim.ticket(), message.executionId());
            if (step.fencingToken() != claim.fencingToken()) {
                throw new InvalidExecutionTicket(
                        "Execution ticket fencing token does not match the claim");
            }
            var context = new IdempotencyContext(
                    step.executionId(), step.stepId(), step.fencingToken());
            try (var activeLease = leaseHeartbeat.start(message, claim, attemptId)) {
                ExecutionStepResult result;
                try {
                    result = dispatcher.dispatch(step, context);
                } catch (UnsupportedRunbookStepException rejected) {
                    result = ExecutionStepResult.failed(
                            "executor-policy-v1",
                            sha256(step.adapterId() + ':' + step.operation()),
                            Map.of("errorCode", "unsupported_runbook_step"));
                }
                String executionTicket = activeLease.ticketForResult();
                var report = report(
                        step.adapterId(), step.stepId(), step.fencingToken(), result);
                if (result.succeeded()) {
                    controlPlane.complete(
                            message.executionId(),
                            executionTicket,
                            report,
                            attemptKey(message, "complete", attemptId));
                } else {
                    controlPlane.fail(
                            message.executionId(),
                            executionTicket,
                            report,
                            attemptKey(message, "fail", attemptId));
                }
            }
            acknowledger.acknowledge(message);
        } catch (TerminalControlPlaneException alreadyTerminal) {
            acknowledger.acknowledge(message);
        }
    }

    private String attemptKey(ExecutionMessage message, String action, String attemptId) {
        return message.recordId() + ':' + action + ':' + attemptId;
    }

    private AttemptReport report(
            String adapterId,
            String stepId,
            long fencingToken,
            ExecutionStepResult result) {
        return new AttemptReport(
                fencingToken,
                stepId,
                1,
                adapterId,
                result.adapterVersion(),
                result.requestHash(),
                result.sanitizedResult());
    }

    private String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required by the Java runtime", impossible);
        }
    }
}
