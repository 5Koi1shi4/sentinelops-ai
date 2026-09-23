package io.sentinelops.api.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.sentinelops.api.identity.application.PrincipalSynchronizer;
import io.sentinelops.api.identity.application.PrincipalLookup;
import io.sentinelops.api.identity.application.CurrentPrincipal;
import io.sentinelops.api.support.PostgresIntegrationTest;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

class PrincipalSynchronizationIT extends PostgresIntegrationTest {
    private static final String ISSUER = "https://issuer.sentinelops.test";
    private static final UUID DEMO_SERVICE = UUID.fromString("0199a000-0000-7000-8000-000000000001");

    @Autowired private PrincipalSynchronizer synchronizer;
    @Autowired private PrincipalLookup principalLookup;
    @Autowired private JdbcClient jdbc;
    @Autowired private WebApplicationContext context;

    @Test
    void authenticatedApiRequestSynchronizesTheValidatedIdentity() throws Exception {
        String subject = "http-sync-" + UUID.randomUUID();
        Jwt token = token(ISSUER, subject, Instant.now(),
                List.of("OBSERVER"), List.of(DEMO_SERVICE), "HTTP Observer");
        var mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        mockMvc.perform(get("/api/v1/services").with(jwt().jwt(token).authorities(
                        new SimpleGrantedAuthority("SENTINELOPS_API_AUDIENCE"),
                        new SimpleGrantedAuthority("ROLE_OBSERVER"))))
                .andExpect(status().isOk());
        UUID id = jdbc.sql("select id from principal where issuer=:issuer and subject=:subject")
                .param("issuer", ISSUER).param("subject", subject).query(UUID.class).single();
        assertThat(grants(id)).containsExactly("observer:" + DEMO_SERVICE);
    }

    @Test
    void revokedRoleIsEnforcedOnTheNextRequestUsingAnOlderToken() throws Exception {
        String subject = "revoked-" + UUID.randomUUID();
        Instant oldIssue = Instant.now().minusSeconds(90);
        Jwt oldToken = token(ISSUER, subject, oldIssue,
                List.of("OBSERVER"), List.of(DEMO_SERVICE), "Former observer");
        synchronizer.synchronize(oldToken);
        synchronizer.synchronize(token(ISSUER, subject, oldIssue.plusSeconds(60),
                List.of(), List.of(), "Former observer"));

        var mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        mockMvc.perform(get("/api/v1/services").with(jwt().jwt(oldToken).authorities(
                        new SimpleGrantedAuthority("SENTINELOPS_API_AUDIENCE"),
                        new SimpleGrantedAuthority("ROLE_OBSERVER"))))
                .andExpect(status().isForbidden());
    }

    @Test
    void reconcilesOnlyTrustedClaimsAndRemovesRevokedServiceGrants() {
        String subject = "sync-" + UUID.randomUUID();
        UUID secondService = service();
        Instant firstIssue = Instant.parse("2026-09-22T10:00:00Z");
        UUID id = synchronizer.synchronize(token(ISSUER, subject, firstIssue,
                List.of("OBSERVER", "SRE_APPROVER", "unknown_external_role"),
                List.of(DEMO_SERVICE, secondService), "Alice"));

        assertThat(grants(id)).containsExactlyInAnyOrder(
                "observer:" + DEMO_SERVICE, "observer:" + secondService,
                "sre_approver:" + DEMO_SERVICE, "sre_approver:" + secondService);
        Jwt latest = token(ISSUER, subject, firstIssue.plusSeconds(60),
                List.of("OBSERVER", "SRE_APPROVER"), List.of(secondService), "Alice Updated");
        UUID sameId = synchronizer.synchronize(latest);
        assertThat(sameId).isEqualTo(id);
        assertThat(grants(id)).containsExactlyInAnyOrder(
                "observer:" + secondService, "sre_approver:" + secondService);
        assertThat(jdbc.sql("select display_name from principal where id=:id")
                .param("id", id).query(String.class).single()).isEqualTo("Alice Updated");
        principalLookup.upsert(CurrentPrincipal.from(latest), subject);
        assertThat(jdbc.sql("select display_name from principal where id=:id")
                .param("id", id).query(String.class).single()).isEqualTo("Alice Updated");

        // A delayed request carrying the older token must not restore the removed grant.
        synchronizer.synchronize(token(ISSUER, subject, firstIssue,
                List.of("OBSERVER", "SRE_APPROVER"), List.of(DEMO_SERVICE, secondService), "Old"));
        assertThat(grants(id)).doesNotContain("observer:" + DEMO_SERVICE);
        assertThat(jdbc.sql("select display_name from principal where id=:id")
                .param("id", id).query(String.class).single()).isEqualTo("Alice Updated");
    }

    @Test
    void platformAdminRequiresTrustedRealmRoleAndHasOneGlobalGrant() {
        String subject = "admin-" + UUID.randomUUID();
        UUID id = synchronizer.synchronize(token(ISSUER, subject, Instant.now(),
                List.of("PLATFORM_ADMIN"), List.of(), "Admin"));
        assertThat(grants(id)).containsExactly("platform_admin:global");

        Jwt freeForm = Jwt.withTokenValue("test")
                .header("alg", "RS256")
                .issuer(ISSUER).subject("free-form-" + UUID.randomUUID())
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(300))
                .claim("roles", List.of("PLATFORM_ADMIN"))
                .claim("service_ids", List.of(DEMO_SERVICE.toString()))
                .build();
        assertThat(grants(synchronizer.synchronize(freeForm))).isEmpty();
        assertThatThrownBy(() -> synchronizer.synchronize(token(
                "https://untrusted.example", subject, Instant.now(),
                List.of("PLATFORM_ADMIN"), List.of(), "Untrusted")))
                .isInstanceOf(SecurityException.class);
    }

    @Test
    void equalIssueTimeCanRemoveButNeverRestoreAGrant() throws Exception {
        String subject = "equal-iat-" + UUID.randomUUID();
        Instant issue = Instant.now().minusSeconds(60);
        Jwt original = token(ISSUER, subject, issue,
                List.of("OBSERVER"), List.of(DEMO_SERVICE), "Observer");
        UUID id = synchronizer.synchronize(original);
        synchronizer.synchronize(token(ISSUER, subject, issue,
                List.of("OBSERVER"), List.of(), "Observer"));
        assertThat(grants(id)).isEmpty();
        synchronizer.synchronize(token(ISSUER, subject, issue,
                List.of("OBSERVER"), List.of(DEMO_SERVICE), "Old observer"));
        assertThat(grants(id)).isEmpty();

        var mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        mockMvc.perform(get("/api/v1/services").with(jwt().jwt(original).authorities(
                        new SimpleGrantedAuthority("SENTINELOPS_API_AUDIENCE"),
                        new SimpleGrantedAuthority("ROLE_OBSERVER"))))
                .andExpect(status().isForbidden());
    }

    private List<String> grants(UUID principalId) {
        return jdbc.sql("""
                select role_name || ':' || coalesce(service_id::text, 'global')
                from role_grant where principal_id=:id order by 1
                """).param("id", principalId).query(String.class).list();
    }

    private UUID service() {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                insert into service_catalog(id,service_key,display_name,owner_team,created_at,updated_at)
                values(:id,:key,'Second service','test',clock_timestamp(),clock_timestamp())
                """).param("id", id).param("key", "scope-" + id).update();
        return id;
    }

    private static Jwt token(String issuer, String subject, Instant issuedAt,
            List<String> roles, List<UUID> services, String name) {
        return Jwt.withTokenValue("test")
                .header("alg", "RS256").issuer(issuer).subject(subject)
                .issuedAt(issuedAt).expiresAt(issuedAt.plusSeconds(3600))
                .claim("realm_access", Map.of("roles", roles))
                .claim("service_ids", services.stream().map(UUID::toString).toList())
                .claim("name", name).build();
    }
}
