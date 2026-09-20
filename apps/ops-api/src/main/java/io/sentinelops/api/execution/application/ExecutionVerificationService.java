package io.sentinelops.api.execution.application;

import io.sentinelops.api.execution.adapter.out.persistence.VerificationStore;
import io.sentinelops.api.execution.adapter.out.persistence.VerificationStore.ClaimedCycle;
import io.sentinelops.api.identity.application.CurrentPrincipal;
import io.sentinelops.api.identity.application.PlatformRole;
import io.sentinelops.api.incident.domain.IllegalIncidentTransition;
import io.sentinelops.api.incident.domain.IncidentCommand;
import io.sentinelops.api.incident.domain.IncidentStateMachine;
import io.sentinelops.api.shared.id.UuidV7Generator;
import io.sentinelops.api.shared.idempotency.IdempotencyService;
import io.sentinelops.api.shared.problem.ApiProblemException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

@Service
public class ExecutionVerificationService {

    private static final Logger LOG =
            LoggerFactory.getLogger(ExecutionVerificationService.class);
    private static final String MANUAL_ROUTE = "POST:/api/v1/incidents/{id}/resolve";
    private static final Duration CLAIM_LEASE = Duration.ofMinutes(2);

    private final VerificationStore store;
    private final VerificationProbe probe;
    private final IdempotencyService idempotency;
    private final UuidV7Generator ids;
    private final ObjectMapper objectMapper;
    private final Duration maxDelay;
    private final boolean schedulerEnabled;
    private final String workerId;

    public ExecutionVerificationService(
            VerificationStore store,
            VerificationProbe probe,
            IdempotencyService idempotency,
            UuidV7Generator ids,
            ObjectMapper objectMapper,
            @Value("${sentinelops.verification.max-delay:PT5S}") Duration maxDelay,
            @Value("${sentinelops.verification.scheduler-enabled:true}")
                    boolean schedulerEnabled,
            @Value("${sentinelops.verification.worker-id:}") String configuredWorkerId) {
        this.store = Objects.requireNonNull(store, "store");
        this.probe = Objects.requireNonNull(probe, "probe");
        this.idempotency = Objects.requireNonNull(idempotency, "idempotency");
        this.ids = Objects.requireNonNull(ids, "ids");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.maxDelay = Objects.requireNonNull(maxDelay, "maxDelay");
        if (maxDelay.isNegative()) {
            throw new IllegalArgumentException("verification maxDelay must not be negative");
        }
        this.schedulerEnabled = schedulerEnabled;
        this.workerId = configuredWorkerId == null || configuredWorkerId.isBlank()
                ? "verifier-" + UUID.randomUUID()
                : configuredWorkerId.trim();
    }

    public ManualVerificationView requestManual(
            UUID incidentId,
            long expectedVersion,
            String idempotencyKey,
            String reason,
            CurrentPrincipal principal) {
        Objects.requireNonNull(incidentId, "incidentId");
        Objects.requireNonNull(principal, "principal");
        if (expectedVersion < 0) {
            throw new IllegalArgumentException("If-Match version must not be negative");
        }
        String normalizedReason = requireReason(reason);
        var response = idempotency.execute(
                new IdempotencyService.Scope(principal.principalKey(), MANUAL_ROUTE),
                idempotencyKey,
                sha256(incidentId + ":" + expectedVersion + ":" + normalizedReason),
                () -> requestManualTransactional(
                        incidentId,
                        expectedVersion,
                        normalizedReason,
                        principal));
        return objectMapper.readValue(
                objectMapper.writeValueAsString(response.body()),
                ManualVerificationView.class);
    }

    private IdempotencyService.Response requestManualTransactional(
            UUID incidentId,
            long expectedVersion,
            String reason,
            CurrentPrincipal principal) {
        var context = store.lockManualContext(incidentId).orElseThrow(() -> problem(
                HttpStatus.NOT_FOUND,
                "incident_not_found",
                "The incident does not exist."));
        if (!principal.hasAnyRole(
                        PlatformRole.ON_CALL_OPERATOR, PlatformRole.PLATFORM_ADMIN)
                || !principal.canAccess(context.serviceId())) {
            throw problem(
                    HttpStatus.FORBIDDEN,
                    "access_denied",
                    "The principal cannot verify incidents for this service.");
        }
        if (!context.hasVerificationPolicy()) {
            throw problem(
                    HttpStatus.CONFLICT,
                    "NO_VERIFICATION_POLICY",
                    "The incident has no published objective verification policy.");
        }
        if (context.version() != expectedVersion) {
            throw new org.springframework.dao.OptimisticLockingFailureException(
                    "Incident version does not match If-Match");
        }
        try {
            IncidentStateMachine.next(
                    context.status(), IncidentCommand.REQUEST_MANUAL_VERIFICATION);
        } catch (IllegalIncidentTransition invalidState) {
            throw problem(
                    HttpStatus.CONFLICT,
                    "incident_not_verifiable",
                    "The incident cannot start manual verification from its current state.");
        }

        var now = store.databaseTime();
        var transition = store.transitionForManualVerification(context, now);
        store.enqueueManualCycle(
                ids.generate(), context, transition.version(), now);
        var payload = objectMapper.createObjectNode()
                .put("reason", reason)
                .put("requestedBy", principal.subject());
        var auditMetadata = payload.deepCopy()
                .put("fromStatus", context.status().databaseValue())
                .put("fromVersion", context.version())
                .put("toVersion", transition.version());
        store.appendEvent(
                ids.generate(),
                incidentId,
                transition.sequence(),
                "manual_verification_requested",
                "user",
                principal.subject(),
                objectMapper.writeValueAsString(payload),
                now);
        store.appendManualVerificationAudit(
                ids.generate(),
                context.serviceId(),
                principal.subject(),
                incidentId,
                objectMapper.writeValueAsString(auditMetadata),
                now);
        var view = new ManualVerificationView(
                incidentId, "verifying", transition.version());
        return new IdempotencyService.Response(
                HttpStatus.ACCEPTED.value(), objectMapper.valueToTree(view));
    }

    public boolean runPendingOnce() {
        var claimed = store.claimNext(ids.generate(), workerId, CLAIM_LEASE.toSeconds());
        if (claimed.isEmpty()) {
            return false;
        }
        execute(claimed.orElseThrow());
        return true;
    }

    @Scheduled(fixedDelayString = "${sentinelops.verification.poll-interval:PT1S}")
    void runScheduled() {
        if (!schedulerEnabled) {
            return;
        }
        try {
            runPendingOnce();
        } catch (RuntimeException failure) {
            LOG.warn(
                    "Objective verification cycle remains available for retry errorType={}",
                    failure.getClass().getSimpleName());
        }
    }

    private void execute(ClaimedCycle cycle) {
        boolean successful = cycle.alreadySucceeded();
        int finalAttempt = cycle.completedAttempts();
        var specification = new VerificationProbe.VerificationSpec(
                cycle.incidentId(),
                cycle.probe(),
                cycle.targetAlias(),
                cycle.successThreshold());
        for (int attempt = cycle.completedAttempts() + 1;
                !successful && attempt <= cycle.maxAttempts();
                attempt++) {
            VerificationProbe.VerificationResult result;
            try {
                result = Objects.requireNonNull(
                        probe.verify(specification), "verification probe result");
            } catch (RuntimeException probeFailure) {
                result = VerificationProbe.VerificationResult.failed(Map.of(
                        "errorCode", "verification_probe_failed",
                        "errorType", probeFailure.getClass().getSimpleName()));
            }
            store.recordAttempt(
                    ids.generate(),
                    cycle.id(),
                    cycle.claimToken(),
                    workerId,
                    attempt,
                    result.successful(),
                    objectMapper.writeValueAsString(result.sanitizedResult()),
                    CLAIM_LEASE.toSeconds());
            finalAttempt = attempt;
            successful = result.successful();
            if (!successful && attempt < cycle.maxAttempts()) {
                delay(cycle.intervalSeconds());
            }
        }
        var payload = objectMapper.createObjectNode()
                .put("verificationCycleId", cycle.id().toString())
                .put("cycleNo", cycle.cycleNo())
                .put("probe", cycle.probe())
                .put("attempts", finalAttempt)
                .put("successful", successful);
        store.finalizeCycle(
                cycle.id(),
                cycle.claimToken(),
                workerId,
                successful,
                ids.generate(),
                ids.generate(),
                objectMapper.writeValueAsString(payload));
    }

    private void delay(int intervalSeconds) {
        Duration requested = Duration.ofSeconds(intervalSeconds);
        Duration actual = requested.compareTo(maxDelay) > 0 ? maxDelay : requested;
        if (actual.isZero()) {
            return;
        }
        try {
            Thread.sleep(actual);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Verification retry delay was interrupted", interrupted);
        }
    }

    private String requireReason(String reason) {
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("reason must not be blank");
        }
        String normalized = reason.trim();
        if (normalized.length() > 1000) {
            throw new IllegalArgumentException("reason must not exceed 1000 characters");
        }
        return normalized;
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

    private ApiProblemException problem(HttpStatus status, String code, String detail) {
        return new ApiProblemException(status, code, detail);
    }

    public record ManualVerificationView(
            UUID incidentId, String status, long incidentVersion) {}
}
