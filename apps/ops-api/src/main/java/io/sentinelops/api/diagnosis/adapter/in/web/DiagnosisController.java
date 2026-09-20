package io.sentinelops.api.diagnosis.adapter.in.web;

import io.sentinelops.api.diagnosis.application.DiagnosisApplicationService;
import io.sentinelops.api.diagnosis.domain.DiagnosisProposal;
import io.sentinelops.api.identity.application.CurrentPrincipal;
import io.sentinelops.api.identity.application.PrincipalLookup;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/incidents")
public class DiagnosisController {

    private final DiagnosisApplicationService diagnoses;
    private final PrincipalLookup principals;

    public DiagnosisController(
            DiagnosisApplicationService diagnoses, PrincipalLookup principals) {
        this.diagnoses = diagnoses;
        this.principals = principals;
    }

    @PostMapping("/{id}/diagnosis-runs")
    ResponseEntity<DiagnosisProposal> diagnose(
            @PathVariable UUID id,
            @RequestHeader(HttpHeaders.IF_MATCH) String ifMatch,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @AuthenticationPrincipal Jwt jwt) {
        var principal = CurrentPrincipal.from(jwt);
        var principalId = principals.upsert(principal, displayName(jwt));
        var proposal = diagnoses.diagnose(
                id, parseVersion(ifMatch), idempotencyKey, principal, principalId);
        return ResponseEntity.status(HttpStatus.CREATED).body(proposal);
    }

    private String displayName(Jwt jwt) {
        String displayName = jwt.getClaimAsString("name");
        return displayName == null || displayName.isBlank() ? jwt.getSubject() : displayName;
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
            throw new IllegalArgumentException("If-Match version is outside the supported range", failure);
        }
    }
}
