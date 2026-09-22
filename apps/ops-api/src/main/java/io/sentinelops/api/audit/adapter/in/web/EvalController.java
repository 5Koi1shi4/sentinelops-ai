package io.sentinelops.api.audit.adapter.in.web;

import io.sentinelops.api.audit.eval.EvalApplicationService;
import io.sentinelops.api.identity.application.CurrentPrincipal;
import java.net.URI;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;

@RestController
@RequestMapping("/api/v1/eval-runs")
public class EvalController {
    private final EvalApplicationService evaluations;
    public EvalController(EvalApplicationService evaluations) { this.evaluations=evaluations; }

    @PostMapping
    ResponseEntity<EvalApplicationService.RunView> run(@RequestBody JsonNode body,@RequestHeader("Idempotency-Key") String key,
            @AuthenticationPrincipal Jwt jwt) {
        if(body==null || !body.isObject() || !body.properties().stream().map(java.util.Map.Entry::getKey)
                .collect(java.util.stream.Collectors.toSet()).equals(Set.of("datasetKey","baselineRunId"))
                || !body.path("datasetKey").isString()
                || (!body.path("baselineRunId").isNull() && !body.path("baselineRunId").isString())) {
            throw new IllegalArgumentException("Invalid Eval request fields");
        }
        var request=new EvalApplicationService.RunRequest(body.path("datasetKey").asString(),
                body.path("baselineRunId").isNull() ? null : UUID.fromString(body.path("baselineRunId").asString()));
        var result=evaluations.run(request,key,CurrentPrincipal.from(jwt));
        return ResponseEntity.created(URI.create("/api/v1/eval-runs/"+result.id())).body(result);
    }
    @GetMapping
    List<EvalApplicationService.EvalRunSummary> list(@RequestParam(required = false) String beforeId,
            @RequestParam(defaultValue = "20") int limit, @AuthenticationPrincipal Jwt jwt) {
        return evaluations.list(parseCursor(beforeId), limit, CurrentPrincipal.from(jwt));
    }
    @GetMapping("/{id}")
    EvalApplicationService.RunView get(@PathVariable UUID id,@AuthenticationPrincipal Jwt jwt) {
        return evaluations.get(id,CurrentPrincipal.from(jwt));
    }

    private static UUID parseCursor(String value) {
        if (value == null) return null;
        if (!value.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")) {
            throw new IllegalArgumentException("beforeId must be a canonical UUIDv7 cursor");
        }
        UUID cursor = UUID.fromString(value);
        if (cursor.version() != 7) throw new IllegalArgumentException("beforeId must be a UUIDv7 cursor");
        return cursor;
    }
}
