package io.sentinelops.api.identity.adapter.in.security;

import static io.sentinelops.api.identity.adapter.in.security.SentinelJwtAuthenticationConverter.API_AUTHORITY;
import static io.sentinelops.api.identity.adapter.in.security.SentinelJwtAuthenticationConverter.EXECUTOR_AUTHORITY;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.web.SecurityFilterChain;

@Configuration(proxyBeanMethods = false)
public class SecurityConfig {

    @Bean
    SecurityFilterChain apiSecurity(
            HttpSecurity http,
            SentinelJwtAuthenticationConverter jwtConverter,
            Environment environment)
            throws Exception {
        http.csrf(csrf -> csrf.ignoringRequestMatchers("/api/**", "/internal/**"));
        http.sessionManagement(
                sessions -> sessions.sessionCreationPolicy(SessionCreationPolicy.STATELESS));
        http.authorizeHttpRequests(authorize -> {
            authorize.requestMatchers("/actuator/health", "/actuator/health/**").permitAll();
            if (environment.acceptsProfiles(Profiles.of("demo"))) {
                authorize.requestMatchers(
                                HttpMethod.POST,
                                "/api/v1/integrations/alertmanager/webhook")
                        .permitAll();
            }
            authorize.requestMatchers(HttpMethod.POST, "/api/v1/incidents/*/diagnosis-runs")
                    .hasAnyRole("ON_CALL_OPERATOR", "PLATFORM_ADMIN");
            authorize.requestMatchers(HttpMethod.POST, "/api/v1/incidents/*/approval-requests")
                    .hasAnyRole("ON_CALL_OPERATOR", "PLATFORM_ADMIN");
            authorize.requestMatchers(HttpMethod.POST, "/api/v1/approval-requests/*/decisions")
                    .hasAnyRole("SRE_APPROVER", "PLATFORM_ADMIN");
            authorize.requestMatchers(HttpMethod.POST, "/api/v1/incidents/*/executions")
                    .hasAnyRole("ON_CALL_OPERATOR", "PLATFORM_ADMIN");
            authorize.requestMatchers(HttpMethod.POST, "/api/v1/incidents/*/resolve")
                    .hasAnyRole("ON_CALL_OPERATOR", "PLATFORM_ADMIN");
            authorize.requestMatchers("/internal/**").hasAuthority(EXECUTOR_AUTHORITY);
            authorize.requestMatchers("/api/**").hasAuthority(API_AUTHORITY);
            authorize.anyRequest().denyAll();
        });
        http.oauth2ResourceServer(
                resourceServer -> resourceServer.jwt(jwt ->
                        jwt.jwtAuthenticationConverter(jwtConverter)));
        return http.build();
    }

    @Bean
    JwtDecoder jwtDecoder(
            @Value("${spring.security.oauth2.resourceserver.jwt.jwk-set-uri}")
                    String jwkSetUri,
            @Value("${sentinelops.security.issuer}") String issuer,
            @Value("${sentinelops.security.audience}") String audience) {
        var decoder = NimbusJwtDecoder.withJwkSetUri(jwkSetUri).build();
        var audienceValidator = new JwtClaimValidator<java.util.Collection<String>>(
                JwtClaimNames.AUD,
                audiences -> audiences != null && audiences.contains(audience));
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(issuer), audienceValidator));
        return decoder;
    }
}
