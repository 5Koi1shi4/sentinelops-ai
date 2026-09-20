package io.sentinelops.api.execution.adapter.in.web;

import io.sentinelops.api.execution.application.ExecutionApplicationService;
import io.sentinelops.api.execution.domain.Execution;
import io.sentinelops.api.identity.application.CurrentPrincipal;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.net.URI;
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
public class ExecutionController {

    private final ExecutionApplicationService executions;

    public ExecutionController(ExecutionApplicationService executions) {
        this.executions = executions;
    }

    @PostMapping("/{incidentId}/executions")
    ResponseEntity<Execution> create(
            @PathVariable UUID incidentId,
            @RequestHeader(HttpHeaders.IF_MATCH) String ifMatch,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody CreateExecutionBody body,
            @AuthenticationPrincipal Jwt jwt) {
        var created = executions.create(
                incidentId,
                body.proposalId(),
                parseVersion(ifMatch),
                idempotencyKey,
                CurrentPrincipal.from(jwt));
        return ResponseEntity.created(URI.create("/api/v1/executions/" + created.id()))
                .eTag(Long.toString(created.incidentVersion()))
                .body(created);
    }

    private long parseVersion(String ifMatch) {
        String value = ifMatch == null ? "" : ifMatch.trim();
        if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
            value = value.substring(1, value.length() - 1);
        }
        if (!value.matches("0|[1-9][0-9]*")) {
            throw new IllegalArgumentException("If-Match must contain one incident version");
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException failure) {
            throw new IllegalArgumentException(
                    "If-Match version is outside the supported range", failure);
        }
    }

    public record CreateExecutionBody(@NotNull UUID proposalId) {}
}
