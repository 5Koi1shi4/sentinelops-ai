package io.sentinelops.api.execution.adapter.in.web;

import io.sentinelops.api.execution.application.ExecutionVerificationService;
import io.sentinelops.api.execution.application.ExecutionVerificationService.ManualVerificationView;
import io.sentinelops.api.identity.application.CurrentPrincipal;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/incidents")
public class ManualResolutionController {

    private final ExecutionVerificationService verification;

    public ManualResolutionController(ExecutionVerificationService verification) {
        this.verification = verification;
    }

    @PostMapping("/{incidentId}/resolve")
    ResponseEntity<ManualVerificationView> requestVerification(
            @PathVariable UUID incidentId,
            @RequestHeader(HttpHeaders.IF_MATCH) String ifMatch,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody ManualResolutionBody body,
            @AuthenticationPrincipal Jwt jwt) {
        var view = verification.requestManual(
                incidentId,
                parseVersion(ifMatch),
                idempotencyKey,
                body.reason(),
                CurrentPrincipal.from(jwt));
        return ResponseEntity.accepted()
                .eTag(Long.toString(view.incidentVersion()))
                .body(view);
    }

    private long parseVersion(String ifMatch) {
        String value = ifMatch == null ? "" : ifMatch.trim();
        if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
            value = value.substring(1, value.length() - 1);
        }
        if (!value.matches("0|[1-9][0-9]*")) {
            throw new IllegalArgumentException(
                    "If-Match must contain one incident version");
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException failure) {
            throw new IllegalArgumentException(
                    "If-Match version is outside the supported range", failure);
        }
    }

    public record ManualResolutionBody(
            @NotBlank @Size(max = 1000) String reason) {}
}
