package io.sentinelops.executor.stream;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.tracing.Tracer;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.sentinelops.executor.controlplane.ControlPlaneClient;
import io.sentinelops.executor.controlplane.ControlPlaneClient.AttemptReport;
import io.sentinelops.executor.controlplane.ControlPlaneClient.TerminalControlPlaneException;
import io.sentinelops.executor.runbook.ExecutionStepResult;
import io.sentinelops.executor.runbook.IdempotencyContext;
import io.sentinelops.executor.runbook.RunbookDispatcher;
import io.sentinelops.executor.runbook.StepIdempotency;
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
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class ExecutionMessageListener {

    private final ControlPlaneClient controlPlane;
    private final ExecutionTicketVerifier verifier;
    private final RunbookDispatcher dispatcher;
    private final ExecutionLeaseHeartbeat leaseHeartbeat;
    private final ExecutionMessageAcknowledger acknowledger;
    private final ObservationRegistry observations;
    private final io.opentelemetry.api.trace.Tracer tracer;
    private final Tracer micrometerTracer;

    @Autowired
    public ExecutionMessageListener(
            ControlPlaneClient controlPlane,
            ExecutionTicketVerifier verifier,
            RunbookDispatcher dispatcher,
            ExecutionLeaseHeartbeat leaseHeartbeat,
            ExecutionMessageAcknowledger acknowledger,
            ObservationRegistry observations,
            OpenTelemetry openTelemetry,
            Tracer micrometerTracer) {
        this.controlPlane = Objects.requireNonNull(controlPlane, "controlPlane");
        this.verifier = Objects.requireNonNull(verifier, "verifier");
        this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher");
        this.leaseHeartbeat = Objects.requireNonNull(leaseHeartbeat, "leaseHeartbeat");
        this.acknowledger = Objects.requireNonNull(acknowledger, "acknowledger");
        this.observations = Objects.requireNonNull(observations, "observations");
        this.tracer = Objects.requireNonNull(openTelemetry, "openTelemetry")
                .getTracer("io.sentinelops.executor.stream");
        this.micrometerTracer = Objects.requireNonNull(micrometerTracer, "micrometerTracer");
    }

    public ExecutionMessageListener(
            ControlPlaneClient controlPlane,
            ExecutionTicketVerifier verifier,
            RunbookDispatcher dispatcher,
            ExecutionLeaseHeartbeat leaseHeartbeat,
            ExecutionMessageAcknowledger acknowledger,
            ObservationRegistry observations,
            OpenTelemetry openTelemetry) {
        this(controlPlane, verifier, dispatcher, leaseHeartbeat,
                acknowledger, observations, openTelemetry, Tracer.NOOP);
    }

    public ExecutionMessageListener(
            ControlPlaneClient controlPlane,
            ExecutionTicketVerifier verifier,
            RunbookDispatcher dispatcher,
            ExecutionLeaseHeartbeat leaseHeartbeat,
            ExecutionMessageAcknowledger acknowledger,
            ObservationRegistry observations) {
        this(controlPlane, verifier, dispatcher, leaseHeartbeat,
                acknowledger, observations, OpenTelemetry.noop());
    }

    public ExecutionMessageListener(
            ControlPlaneClient controlPlane,
            ExecutionTicketVerifier verifier,
            RunbookDispatcher dispatcher,
            ExecutionLeaseHeartbeat leaseHeartbeat,
            ExecutionMessageAcknowledger acknowledger) {
        this(controlPlane, verifier, dispatcher, leaseHeartbeat,
                acknowledger, ObservationRegistry.NOOP);
    }

    public void onMessage(ExecutionMessage message) {
        Objects.requireNonNull(message, "message");
        var builder = tracer.spanBuilder("sentinelops.executor.stream.consume").setNoParent();
        var linkedParent = linkedParent(message.traceparent());
        if (linkedParent != null) {
            builder.addLink(linkedParent);
        }
        var streamSpan = builder.startSpan();
        streamSpan.setAttribute("execution.id", message.executionId().toString());
        var current = streamSpan.getSpanContext();
        var tracingContext = micrometerTracer.traceContextBuilder()
                .traceId(current.getTraceId())
                .spanId(current.getSpanId())
                .sampled(current.isSampled())
                .build();
        try (var streamScope = streamSpan.makeCurrent();
                var bridgeScope = micrometerTracer.currentTraceContext().newScope(tracingContext)) {
            onMessageInTrace(message);
        } finally {
            streamSpan.end();
        }
    }

    private void onMessageInTrace(ExecutionMessage message) {
        var observation = Observation.createNotStarted(
                "sentinelops.executor.message", observations)
                .highCardinalityKeyValue("execution.id", message.executionId().toString())
                .start();
        try (var scope = observation.openScope()) {
            onMessageObserved(message);
            observation.lowCardinalityKeyValue("result", "success");
        } catch (RuntimeException failure) {
            observation.lowCardinalityKeyValue("result", "error");
            throw failure;
        } finally {
            observation.stop();
        }
    }

    private static SpanContext linkedParent(String traceparent) {
        if (traceparent == null || !traceparent.matches(
                "00-(?!0{32})[0-9a-f]{32}-(?!0{16})[0-9a-f]{16}-[0-9a-f]{2}")) {
            return null;
        }
        return SpanContext.createFromRemoteParent(
                traceparent.substring(3, 35),
                traceparent.substring(36, 52),
                TraceFlags.fromHex(traceparent, 53),
                TraceState.getDefault());
    }

    private void onMessageObserved(ExecutionMessage message) {
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
            int attemptNo = Math.toIntExact(step.fencingToken());
            try (var activeLease = leaseHeartbeat.start(message, claim, attemptId)) {
                recordPhase(activeLease, message, step.stepId(), claim.fencingToken(),
                        attemptNo, "prepared", attemptId);
                ExecutionStepResult result;
                var dispatched = new AtomicBoolean();
                try {
                    result = dispatcher.dispatch(step, context,
                            () -> {
                                recordPhase(activeLease, message, step.stepId(),
                                        claim.fencingToken(), attemptNo,
                                        "dispatched", attemptId);
                                dispatched.set(true);
                            });
                } catch (UnsupportedRunbookStepException rejected) {
                    result = ExecutionStepResult.failed(
                            "executor-policy-v1",
                            sha256(step.adapterId() + ':' + step.operation()),
                            Map.of("errorCode", "unsupported_runbook_step"));
                } catch (RuntimeException transportFailure) {
                    if (!dispatched.get()) {
                        throw transportFailure;
                    }
                    recordPhase(activeLease, message, step.stepId(), claim.fencingToken(),
                            attemptNo, "unknown_after_dispatch", attemptId);
                    if (StepIdempotency.mayReplayAfterDispatch(step)) {
                        throw transportFailure;
                    }
                    acknowledger.acknowledge(message);
                    return;
                }
                String executionTicket = activeLease.ticketForResult();
                var report = report(
                        step.adapterId(), step.stepId(), step.fencingToken(), attemptNo, result);
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

    private void recordPhase(
            ExecutionLeaseHeartbeat.ActiveLease activeLease,
            ExecutionMessage message,
            String stepId,
            long fencingToken,
            int attemptNo,
            String phase,
            String attemptId) {
        activeLease.withCurrentTicket(ticket -> {
            var currentStep = verifier.verify(ticket, message.executionId());
            if (currentStep.fencingToken() != fencingToken
                    || !currentStep.stepId().equals(stepId)) {
                throw new InvalidExecutionTicket(
                        "Current execution ticket no longer authorizes the Runbook step");
            }
            controlPlane.recordPhase(message.executionId(), ticket, fencingToken,
                    stepId, attemptNo, phase, attemptKey(message, phase, attemptId));
        });
    }

    private AttemptReport report(
            String adapterId,
            String stepId,
            long fencingToken,
            int attemptNo,
            ExecutionStepResult result) {
        return new AttemptReport(
                fencingToken,
                stepId,
                attemptNo,
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
