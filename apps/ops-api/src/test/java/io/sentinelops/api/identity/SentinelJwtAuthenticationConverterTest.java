package io.sentinelops.api.identity;

import static io.sentinelops.api.identity.adapter.in.security.SentinelJwtAuthenticationConverter.EXECUTOR_AUTHORITY;
import static io.sentinelops.api.identity.adapter.in.security.SentinelJwtAuthenticationConverter.API_AUTHORITY;
import static org.assertj.core.api.Assertions.assertThat;

import io.sentinelops.api.identity.adapter.in.security.SentinelJwtAuthenticationConverter;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

class SentinelJwtAuthenticationConverterTest {

    private final SentinelJwtAuthenticationConverter converter =
            new SentinelJwtAuthenticationConverter("sentinelops-api", "sentinelops-executor");

    @Test
    void mapsPlatformRolesAndUsesSubjectAsPrincipalName() {
        UUID serviceId = UUID.randomUUID();
        var authentication = converter.convert(jwt(
                List.of("observer", "on_call_operator"),
                List.of("sentinelops-api"),
                List.of(serviceId.toString())));

        assertThat(authentication.getName()).isEqualTo("subject-123");
        assertThat(authentication.getAuthorities())
                .extracting("authority")
                .containsExactlyInAnyOrder(
                        API_AUTHORITY, "ROLE_OBSERVER", "ROLE_ON_CALL_OPERATOR");
    }

    @Test
    void derivesInternalAuthorityOnlyFromExecutorRoleAndExecutorAudienceTogether() {
        assertThat(converter.convert(jwt(
                                List.of("sentinelops_executor"),
                                List.of("sentinelops-api"),
                                List.of()))
                        .getAuthorities())
                .extracting("authority")
                .contains(API_AUTHORITY)
                .doesNotContain(EXECUTOR_AUTHORITY);
        assertThat(converter.convert(jwt(
                                List.of("sentinelops_executor"),
                                List.of("sentinelops-executor"),
                                List.of()))
                        .getAuthorities())
                .extracting("authority")
                .contains(EXECUTOR_AUTHORITY)
                .doesNotContain(API_AUTHORITY);
        assertThat(converter.convert(jwt(
                                List.of("sre_approver"),
                                List.of("sentinelops-executor"),
                                List.of()))
                        .getAuthorities())
                .extracting("authority")
                .doesNotContain(EXECUTOR_AUTHORITY, "ROLE_SRE_APPROVER", API_AUTHORITY);
    }

    @Test
    void usesConfiguredAudienceNamesInsteadOfHardCodedDefaults() {
        var configured = new SentinelJwtAuthenticationConverter("custom-api", "custom-executor");

        assertThat(configured.convert(jwt(
                                List.of("on_call_operator"),
                                List.of("sentinelops-api", "sentinelops-executor"),
                                List.of()))
                        .getAuthorities())
                .isEmpty();
        assertThat(configured.convert(jwt(
                                List.of("on_call_operator", "sentinelops_executor"),
                                List.of("custom-api"),
                                List.of()))
                        .getAuthorities())
                .extracting("authority")
                .containsExactlyInAnyOrder(
                        API_AUTHORITY, "ROLE_ON_CALL_OPERATOR");
        assertThat(configured.convert(jwt(
                                List.of("sentinelops_executor"),
                                List.of("custom-executor"),
                                List.of()))
                        .getAuthorities())
                .extracting("authority")
                .containsExactly(EXECUTOR_AUTHORITY);
    }

    private Jwt jwt(
            List<String> roles, List<String> audience, List<String> serviceIds) {
        Instant now = Instant.now();
        return Jwt.withTokenValue("test-token")
                .header("alg", "RS256")
                .issuer("https://issuer.sentinelops.test")
                .subject("subject-123")
                .audience(audience)
                .issuedAt(now)
                .expiresAt(now.plusSeconds(300))
                .claim("realm_access", Map.of("roles", roles))
                .claim("service_ids", serviceIds)
                .build();
    }
}
