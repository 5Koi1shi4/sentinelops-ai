package io.sentinelops.api.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.sentinelops.api.audit.application.AuditService;
import io.sentinelops.api.shared.audit.AuditCommand;
import io.sentinelops.api.identity.application.CurrentPrincipal;
import io.sentinelops.api.identity.application.PlatformRole;
import io.sentinelops.api.support.PostgresIntegrationTest;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

class AuditPrivacyIT extends PostgresIntegrationTest {
    private static final UUID SERVICE = UUID.fromString("0199a000-0000-7000-8000-000000000001");
    private static final String SECRET = "Bearer sk-test-secret-and-untrusted-prompt-body";
    private static final String COMPACT_SECRET = "AKIA1234567890ABCDEF";

    @Autowired private AuditService audit;
    @Autowired private JdbcClient jdbc;
    @Autowired private WebApplicationContext context;

    @Test
    void auditHttpReadRequiresRoleAndServiceScope() throws Exception {
        UUID serviceId = service();
        UUID resource = UUID.randomUUID();
        audit.record(new AuditCommand(serviceId, "user", "subject", "approval_requested",
                "approval_request", resource.toString(), "success", null, null, null,
                Map.of("status", "pending", "prompt", SECRET)));
        var mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        Jwt auditor = token("AUDITOR", serviceId);
        mockMvc.perform(get("/api/v1/audit-records").param("serviceId", serviceId.toString())
                        .with(jwt().jwt(auditor).authorities(
                                new SimpleGrantedAuthority("SENTINELOPS_API_AUDIENCE"),
                                new SimpleGrantedAuthority("ROLE_AUDITOR"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].resourceId").value(resource.toString()))
                .andExpect(jsonPath("$.items[0].metadata.prompt").doesNotExist());
        mockMvc.perform(get("/api/v1/audit-records")
                        .with(jwt().jwt(auditor).authorities(
                                new SimpleGrantedAuthority("SENTINELOPS_API_AUDIENCE"),
                                new SimpleGrantedAuthority("ROLE_AUDITOR"))))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/audit-records").param("serviceId", UUID.randomUUID().toString())
                        .with(jwt().jwt(auditor).authorities(
                                new SimpleGrantedAuthority("SENTINELOPS_API_AUDIENCE"),
                                new SimpleGrantedAuthority("ROLE_AUDITOR"))))
                .andExpect(status().isForbidden());
    }

    @Test
    void auditCursorVisitsEachRecordOnce() {
        UUID serviceId = service();
        UUID first = audit.record(new AuditCommand(serviceId, "user", "one", "approval_requested",
                "approval_request", UUID.randomUUID().toString(), "success", null, null, null, Map.of()));
        UUID second = audit.record(new AuditCommand(serviceId, "user", "two", "approval_decided",
                "approval_request", UUID.randomUUID().toString(), "success", null, null, null, Map.of()));
        var auditor = new CurrentPrincipal("https://issuer.sentinelops.test", "auditor",
                Set.of(PlatformRole.AUDITOR), Set.of(serviceId));
        var firstPage = audit.list(auditor, serviceId, null, 1);
        assertThat(firstPage.items()).extracting("id").containsExactly(second);
        var secondPage = audit.list(auditor, serviceId, firstPage.nextCursor(), 1);
        assertThat(secondPage.items()).extracting("id").containsExactly(first);
    }

    @Test
    void takesTheCurrentTraceIdWithoutSerializingRequestBodies() {
        MDC.put("traceId", "0123456789abcdef");
        try {
            UUID id = audit.record(new AuditCommand(SERVICE, "user", "subject",
                    "approval_requested", "approval_request", UUID.randomUUID().toString(),
                    "success", null, null, null, Map.of("prompt", SECRET)));
            assertThat(jdbc.sql("select trace_id from audit_record where id=:id")
                    .param("id", id).query(String.class).optional())
                    .contains("0123456789abcdef");
        } finally {
            MDC.remove("traceId");
        }
    }

    @Test
    void legacyWritersWithoutAnExplicitResultRemainUnknown() {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                insert into audit_record(id,actor_type,actor_id,action,resource_type,
                  resource_id,metadata,occurred_at)
                values(:id,'system','migration-test','legacy_event','system',:resource,
                  '{}'::jsonb,clock_timestamp())
                """).param("id", id).param("resource", id.toString()).update();
        assertThat(jdbc.sql("select result from audit_record where id=:id")
                .param("id", id).query(String.class).single()).isEqualTo("unknown");
    }

    @Test
    void recordsOnlyAllowlistedMetadataAndSerializesAServiceScopedPage() {
        UUID resource = UUID.randomUUID();
        UUID id = audit.record(new AuditCommand(
                SERVICE, "user", "subject", "approval_requested", "approval_request",
                resource.toString(), "success", null, "safe-hash", "trace-123",
                Map.of("status", "pending", "prompt", SECRET, "evidenceBody", SECRET,
                        "revision", 2, "reasonCode", COMPACT_SECRET)));

        String metadata = jdbc.sql("select metadata::text from audit_record where id=:id")
                .param("id", id).query(String.class).single();
        assertThat(metadata).contains("pending", "revision")
                .doesNotContain(SECRET, COMPACT_SECRET, "prompt", "evidenceBody");
        assertThat(jdbc.sql("select result from audit_record where id=:id")
                .param("id", id).query(String.class).single()).isEqualTo("success");

        var auditor = new CurrentPrincipal("https://issuer.sentinelops.test", "auditor",
                Set.of(PlatformRole.AUDITOR), Set.of(SERVICE));
        var page = audit.list(auditor, SERVICE, null, 1);
        assertThat(page.items()).extracting("id").contains(id);
        assertThat(page.items().getFirst().metadata().toString()).doesNotContain(SECRET);
        assertThatThrownBy(() -> audit.list(auditor, null, null, 1))
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> jdbc.sql("update audit_record set result='failure' where id=:id")
                .param("id", id).update()).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.sql("delete from audit_record where id=:id")
                .param("id", id).update()).isInstanceOf(DataAccessException.class);
    }

    private static Jwt token(String role, UUID serviceId) {
        Instant now = Instant.now();
        return Jwt.withTokenValue("test")
                .header("alg", "RS256")
                .issuer("https://issuer.sentinelops.test")
                .subject("audit-reader-" + UUID.randomUUID())
                .audience(List.of("sentinelops-api"))
                .issuedAt(now).expiresAt(now.plusSeconds(300))
                .claim("realm_access", Map.of("roles", List.of(role)))
                .claim("service_ids", List.of(serviceId.toString())).build();
    }

    private UUID service() {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                insert into service_catalog(id,service_key,display_name,owner_team,created_at,updated_at)
                values(:id,:key,'Audit service','test',clock_timestamp(),clock_timestamp())
                """).param("id", id).param("key", "audit-" + id).update();
        return id;
    }
}
