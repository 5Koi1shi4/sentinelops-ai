package io.sentinelops.api.knowledge.adapter.in.web;

import io.sentinelops.api.identity.application.CurrentPrincipal;
import io.sentinelops.api.knowledge.application.KnowledgeHit;
import io.sentinelops.api.knowledge.application.RunbookApplicationService;
import io.sentinelops.api.knowledge.application.RunbookApplicationService.*;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;

@RestController
@RequestMapping("/api/v1")
public class RunbookController {
    private final RunbookApplicationService runbooks;
    public RunbookController(RunbookApplicationService runbooks) { this.runbooks = runbooks; }

    @GetMapping("/runbooks")
    List<RunbookSummary> list(@RequestParam(required = false) String afterKey,
            @RequestParam(defaultValue = "50") int limit, @AuthenticationPrincipal Jwt jwt) {
        return runbooks.list(afterKey, limit, CurrentPrincipal.from(jwt));
    }

    @PostMapping("/runbooks/{key}/versions")
    ResponseEntity<VersionView> create(@PathVariable String key, @RequestBody JsonNode body,
            @RequestHeader("Idempotency-Key") String command, @AuthenticationPrincipal Jwt jwt) {
        exact(body, Set.of("serviceId", "displayName", "ownerTeam", "definition", "markdown"));
        var input = new DraftInput(UUID.fromString(text(body, "serviceId")), text(body, "displayName"),
                text(body, "ownerTeam"), body.get("definition"), text(body, "markdown"));
        return response(runbooks.createDraft(key, input, command, CurrentPrincipal.from(jwt)), 201);
    }

    @GetMapping("/runbooks/{key}/versions")
    List<VersionView> versions(@PathVariable String key, @RequestParam(defaultValue = "0") int afterVersion,
            @RequestParam(defaultValue = "20") int limit, @AuthenticationPrincipal Jwt jwt) {
        return runbooks.versions(key, afterVersion, limit, CurrentPrincipal.from(jwt));
    }

    @GetMapping("/runbook-versions/{id}")
    ResponseEntity<VersionView> get(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        return response(runbooks.get(id, CurrentPrincipal.from(jwt)), 200);
    }

    @PutMapping("/runbook-versions/{id}")
    ResponseEntity<VersionView> update(@PathVariable UUID id, @RequestBody JsonNode body,
            @RequestHeader("If-Match") String ifMatch, @RequestHeader("Idempotency-Key") String command,
            @AuthenticationPrincipal Jwt jwt) {
        exact(body, Set.of("definition", "markdown"));
        return response(runbooks.updateDraft(id, revision(ifMatch), new DraftContent(body.get("definition"), text(body, "markdown")),
                command, CurrentPrincipal.from(jwt)), 200);
    }

    @GetMapping("/runbook-versions/{id}/diff")
    VersionDiff diff(@PathVariable UUID id, @RequestParam UUID otherVersionId, @AuthenticationPrincipal Jwt jwt) {
        return runbooks.diff(id, otherVersionId, CurrentPrincipal.from(jwt));
    }

    @PostMapping("/runbook-versions/{id}/review")
    ResponseEntity<VersionView> review(@PathVariable UUID id, @RequestHeader("If-Match") String ifMatch,
            @RequestHeader("Idempotency-Key") String command, @RequestBody(required = false) JsonNode body,
            @AuthenticationPrincipal Jwt jwt) {
        noBody(body);
        return response(runbooks.review(id, revision(ifMatch), command, CurrentPrincipal.from(jwt)), 200);
    }

    @PostMapping("/runbook-versions/{id}/publish")
    ResponseEntity<VersionView> publish(@PathVariable UUID id, @RequestHeader("If-Match") String ifMatch,
            @RequestHeader("Idempotency-Key") String command, @RequestBody(required = false) JsonNode body,
            @AuthenticationPrincipal Jwt jwt) {
        noBody(body);
        return response(runbooks.publish(id, revision(ifMatch), command, CurrentPrincipal.from(jwt)), 200);
    }

    @GetMapping("/runbooks/search")
    List<KnowledgeHit> search(@RequestParam UUID serviceId, @RequestParam String query,
            @RequestParam(defaultValue = "5") int limit, @AuthenticationPrincipal Jwt jwt) {
        return runbooks.search(serviceId, query, limit, CurrentPrincipal.from(jwt));
    }

    private static ResponseEntity<VersionView> response(VersionView value, int status) {
        return ResponseEntity.status(status).eTag("\"" + value.revision() + "\"").body(value);
    }
    private static long revision(String value) {
        if (value == null || !value.matches("\"(0|[1-9][0-9]{0,18})\"")) throw new IllegalArgumentException("If-Match must be a quoted revision");
        return Long.parseLong(value.substring(1, value.length() - 1));
    }
    private static void exact(JsonNode body, Set<String> fields) {
        if (body == null || !body.isObject() || !body.properties().stream().map(java.util.Map.Entry::getKey).collect(Collectors.toSet()).equals(fields)) {
            throw new IllegalArgumentException("unsupported or missing body fields");
        }
    }
    private static String text(JsonNode body, String field) {
        if (!body.path(field).isTextual()) throw new IllegalArgumentException("field must be text");
        return body.path(field).asString();
    }
    private static void noBody(JsonNode body) {
        if (body != null) throw new IllegalArgumentException("this command has no request body");
    }
}
