package io.sentinelops.api.execution;

import static org.assertj.core.api.Assertions.assertThat;

import com.nimbusds.jwt.SignedJWT;
import io.sentinelops.api.execution.application.ExecutionTicketVerifier;
import io.sentinelops.api.identity.application.CurrentPrincipal;
import io.sentinelops.api.identity.application.PlatformRole;
import io.sentinelops.api.knowledge.application.RunbookApplicationService;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.ObjectMapper;

class ProductionExecutionPathIT extends ExecutionFixtureSupport {

    @Autowired private RunbookApplicationService runbooks;
    @Autowired private ObjectMapper json;
    @Autowired private ExecutionTicketVerifier tickets;

    @Test
    void reviewedProductionHttpRunbookCanBeApprovedCreatedAndClaimedWithBoundedTicket()
            throws Exception {
        UUID serviceId = jdbc.sql("select id from service_catalog where service_key = 'checkout-api'")
                .query(UUID.class).single();
        String runbookKey = "RB-PRODUCTION-" + UUID.randomUUID();
        String definition = """
                {"runbookKey":"%s","risk":"R1","adapterId":"production-http",
                 "parameters":{"type":"object","properties":{"replicas":{"type":"integer","minimum":1,"maximum":3}},"required":["replicas"],"additionalProperties":false},
                 "steps":[{"stepId":"restart-one","operation":"restart_service"}],
                 "verification":{"probe":"production_checkout_health","successThreshold":1.0,"attempts":6,"intervalSeconds":5},"rollback":null}
                """.formatted(runbookKey);
        var author = administrator(serviceId, "production-author");
        var reviewer = administrator(serviceId, "production-reviewer");
        var draft = runbooks.createDraft(runbookKey,
                new RunbookApplicationService.DraftInput(serviceId, "Checkout restart", "operations",
                        json.readTree(definition),
                        "# Checkout restart\n\nReviewed production HTTP recovery"),
                UUID.randomUUID().toString(), author);
        var reviewed = runbooks.review(draft.id(), draft.revision(),
                UUID.randomUUID().toString(), reviewer);
        var published = runbooks.publish(draft.id(), reviewed.revision(),
                UUID.randomUUID().toString(), author);

        jdbc.sql("""
                        update service_catalog
                        set execution_target_aliases = '{"primary":"checkout"}'::jsonb
                        where id = :serviceId
                        """)
                .param("serviceId", serviceId).update();
        try {
            var fixture = approvedFixture(published.id(), "checkout");
            var created = executions.create(fixture.incidentId(), fixture.proposalId(), 3,
                    "production-create-" + fixture.proposalId(), fixture.operator());
            var claim = executions.claim(created.id(), "production-executor");
            var verified = tickets.verify(claim.ticket(), "urn:sentinelops:ops-api",
                    "sentinelops-executor", Instant.now());
            var claims = SignedJWT.parse(claim.ticket()).getJWTClaimsSet();

            assertThat(verified.executionId()).isEqualTo(created.id());
            assertThat(verified.runbookChecksum()).isEqualTo(published.definitionChecksum());
            assertThat(verified.adapterId()).isEqualTo("production-http");
            assertThat(verified.stepId()).isEqualTo("restart-one");
            assertThat(claims.getStringClaim("operation")).isEqualTo("restart_service");
            assertThat(claims.getStringClaim("target")).isEqualTo("checkout");
            assertThat(claims.getJSONObjectClaim("parameters")).containsEntry("replicas", 1L);
            assertThat(jdbc.sql("select status from execution where id=:id")
                    .param("id", created.id()).query(String.class).single()).isEqualTo("running");
        } finally {
            jdbc.sql("""
                            update service_catalog
                            set execution_target_aliases = '{"primary":"demo-checkout"}'::jsonb
                            where id = :serviceId
                            """)
                    .param("serviceId", serviceId).update();
        }
    }

    private CurrentPrincipal administrator(UUID serviceId, String prefix) {
        return new CurrentPrincipal("https://issuer.sentinelops.test",
                prefix + '-' + UUID.randomUUID(), Set.of(PlatformRole.RUNBOOK_ADMIN), Set.of(serviceId));
    }
}
