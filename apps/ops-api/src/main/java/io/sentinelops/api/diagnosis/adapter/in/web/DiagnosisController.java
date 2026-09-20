package io.sentinelops.api.diagnosis.adapter.in.web;

import io.sentinelops.api.diagnosis.application.DiagnosisApplicationService;
import io.sentinelops.api.diagnosis.domain.DiagnosisProposal;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/incidents")
public class DiagnosisController {

    private final DiagnosisApplicationService diagnoses;

    public DiagnosisController(DiagnosisApplicationService diagnoses) {
        this.diagnoses = diagnoses;
    }

    @PostMapping("/{id}/diagnosis-runs")
    ResponseEntity<DiagnosisProposal> diagnose(
            @PathVariable UUID id,
            @RequestHeader(HttpHeaders.IF_MATCH) String ifMatch,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestHeader(value = "X-SentinelOps-Principal", defaultValue = "demo-author")
                    String principalKey) {
        var proposal = diagnoses.diagnose(
                id, parseVersion(ifMatch), idempotencyKey, principalKey);
        return ResponseEntity.status(HttpStatus.CREATED).body(proposal);
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
