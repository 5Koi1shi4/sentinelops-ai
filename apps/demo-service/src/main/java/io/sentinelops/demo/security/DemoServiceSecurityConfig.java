package io.sentinelops.demo.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.web.SecurityFilterChain;

@Configuration(proxyBeanMethods = false)
public class DemoServiceSecurityConfig {

    @Bean
    SecurityFilterChain demoServiceSecurity(
            HttpSecurity http,
            @Value("${sentinelops.demo-mode:false}") boolean demoMode,
            @Value("${sentinelops.demo-alert-relay-mode:false}") boolean relayMode)
            throws Exception {
        http.csrf(csrf -> csrf.ignoringRequestMatchers("/api/**", "/internal/**"));
        http.sessionManagement(
                sessions -> sessions.sessionCreationPolicy(SessionCreationPolicy.STATELESS));
        http.authorizeHttpRequests(authorize -> authorize
                .requestMatchers(
                        "/actuator/health",
                        "/actuator/health/**",
                        "/actuator/prometheus")
                .permitAll()
                .requestMatchers(HttpMethod.POST, "/api/checkout")
                .permitAll()
                .requestMatchers(HttpMethod.POST, "/internal/demo/alertmanager-relay")
                .access((authentication, context) -> new org.springframework.security.authorization.AuthorizationDecision(
                        demoMode && relayMode))
                .requestMatchers(
                        HttpMethod.POST, "/internal/demo/faults/connection-pool")
                .hasAuthority("SCOPE_demo:fault")
                .requestMatchers(HttpMethod.DELETE, "/internal/demo/faults")
                .hasAuthority("SCOPE_demo:fault")
                .requestMatchers(
                        HttpMethod.POST,
                        "/internal/runbooks/recover-connection-pool")
                .hasAuthority("SCOPE_runbook:execute:checkout")
                .anyRequest()
                .denyAll());
        http.oauth2ResourceServer(resourceServer -> resourceServer.jwt(jwt -> {}));
        return http.build();
    }

    @Bean
    JwtDecoder jwtDecoder(
            @Value("${spring.security.oauth2.resourceserver.jwt.jwk-set-uri}")
                    String jwkSetUri,
            @Value("${sentinelops.security.issuer}") String issuer,
            @Value("${sentinelops.security.audience}") String audience) {
        var decoder = NimbusJwtDecoder.withJwkSetUri(jwkSetUri).build();
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(issuer),
                new AudienceValidator(audience)));
        return decoder;
    }
}
