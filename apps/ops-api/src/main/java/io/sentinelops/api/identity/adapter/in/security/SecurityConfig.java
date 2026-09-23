package io.sentinelops.api.identity.adapter.in.security;

import static io.sentinelops.api.identity.adapter.in.security.SentinelJwtAuthenticationConverter.API_AUTHORITY;
import static io.sentinelops.api.identity.adapter.in.security.SentinelJwtAuthenticationConverter.EXECUTOR_AUTHORITY;

import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import io.sentinelops.api.identity.application.PrincipalSynchronizer;
import org.springframework.security.web.SecurityFilterChain;

@Configuration(proxyBeanMethods = false)
public class SecurityConfig {

    @Bean
    @Order(1)
    SecurityFilterChain executorSecurity(
            HttpSecurity http,
            SentinelJwtAuthenticationConverter jwtConverter) throws Exception {
        http.securityMatcher("/internal/**");
        http.csrf(AbstractHttpConfigurer::disable);
        http.sessionManagement(
                sessions -> sessions.sessionCreationPolicy(SessionCreationPolicy.STATELESS));
        http.authorizeHttpRequests(authorize ->
                authorize.anyRequest().hasAuthority(EXECUTOR_AUTHORITY));
        http.oauth2ResourceServer(resourceServer -> resourceServer.jwt(jwt ->
                jwt.jwtAuthenticationConverter(jwtConverter)));
        return http.build();
    }

    @Bean
    @Order(2)
    SecurityFilterChain apiSecurity(
            HttpSecurity http,
            SentinelJwtAuthenticationConverter jwtConverter,
            PrincipalSynchronizer principalSynchronizer)
            throws Exception {
        http.csrf(csrf -> csrf.ignoringRequestMatchers("/api/**", "/internal/**"));
        http.cors(Customizer.withDefaults());
        http.sessionManagement(
                sessions -> sessions.sessionCreationPolicy(SessionCreationPolicy.STATELESS));
        http.authorizeHttpRequests(authorize -> {
            authorize.requestMatchers("/actuator/health", "/actuator/health/**").permitAll();
            authorize.requestMatchers(HttpMethod.POST,
                            "/api/v1/integrations/alertmanager/webhook")
                    .permitAll();
            authorize.requestMatchers(HttpMethod.POST, "/api/v1/incidents/*/diagnosis-runs")
                    .hasAnyRole("ON_CALL_OPERATOR", "PLATFORM_ADMIN");
            authorize.requestMatchers(HttpMethod.POST, "/api/v1/incidents/*/approval-requests")
                    .hasAnyRole("ON_CALL_OPERATOR", "PLATFORM_ADMIN");
            authorize.requestMatchers(HttpMethod.POST, "/api/v1/approval-requests/*/decisions")
                    .hasRole("SRE_APPROVER");
            authorize.requestMatchers(HttpMethod.POST, "/api/v1/incidents/*/executions")
                    .hasAnyRole("ON_CALL_OPERATOR", "PLATFORM_ADMIN");
            authorize.requestMatchers(HttpMethod.POST, "/api/v1/incidents/*/resolve")
                    .hasAnyRole("ON_CALL_OPERATOR", "PLATFORM_ADMIN");
            authorize.requestMatchers(HttpMethod.GET, "/api/v1/runbook-versions/*/diff")
                    .hasAnyRole("RUNBOOK_ADMIN", "PLATFORM_ADMIN");
            authorize.requestMatchers(HttpMethod.GET, "/api/v1/audit-records")
                    .hasAnyRole("AUDITOR", "PLATFORM_ADMIN");
            authorize.requestMatchers(HttpMethod.GET, "/api/v1/runbook-versions/*",
                            "/api/v1/runbooks/*/versions")
                    .hasAnyRole("OBSERVER", "ON_CALL_OPERATOR", "SRE_APPROVER", "RUNBOOK_ADMIN", "PLATFORM_ADMIN");
            authorize.requestMatchers("/api/v1/runbook-versions/**", "/api/v1/runbooks/*/versions")
                    .hasAnyRole("RUNBOOK_ADMIN", "PLATFORM_ADMIN");
            authorize.requestMatchers("/api/**").hasAuthority(API_AUTHORITY);
            authorize.anyRequest().denyAll();
        });
        http.oauth2ResourceServer(
                resourceServer -> resourceServer.jwt(jwt ->
                        jwt.jwtAuthenticationConverter(jwtConverter)));
        http.addFilterAfter(new PrincipalSynchronizationFilter(principalSynchronizer),
                BearerTokenAuthenticationFilter.class);
        return http.build();
    }

    @Bean
    JwtDecoder jwtDecoder(
            @Value("${spring.security.oauth2.resourceserver.jwt.jwk-set-uri}")
                    String jwkSetUri,
            @Value("${sentinelops.security.issuer}") String issuer,
            @Value("${sentinelops.security.audience}") String audience,
            @Value("${sentinelops.security.executor-audience}") String executorAudience) {
        var decoder = NimbusJwtDecoder.withJwkSetUri(jwkSetUri).build();
        var subjectValidator = new JwtClaimValidator<String>(JwtClaimNames.SUB,
                subject -> subject != null && !subject.isBlank() && subject.length() <= 256);
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(issuer),
                new JwtTimestampValidator(Duration.ZERO),
                new AudienceValidator(audience, executorAudience), subjectValidator));
        return decoder;
    }
}
