package io.sentinelops.demo.runbook;

import io.sentinelops.demo.fault.FaultState;
import io.sentinelops.demo.fault.FaultState.StaleRecoveryFenceException;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/runbooks")
@Validated
public class DemoRecoveryController {

    private final FaultState faults;

    public DemoRecoveryController(FaultState faults) {
        this.faults = faults;
    }

    @PostMapping("/recover-connection-pool")
    RecoveryView recoverConnectionPool(
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 200)
                    String idempotencyKey,
            @RequestHeader("X-SentinelOps-Fencing-Token") @Min(1) long fencingToken) {
        try {
            var recovery = faults.recover(idempotencyKey, fencingToken);
            return new RecoveryView(
                    recovery.changed(), recovery.fencingToken(), recovery.replayed());
        } catch (StaleRecoveryFenceException stale) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "The recovery fencing token is stale", stale);
        }
    }

    public record RecoveryView(boolean changed, long fencingToken, boolean replayed) {}
}
