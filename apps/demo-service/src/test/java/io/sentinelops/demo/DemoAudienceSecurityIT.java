package io.sentinelops.demo;

import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.proc.SecurityContext;
import io.sentinelops.demo.security.AudienceValidator;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

@SpringBootTest(properties = {
    "sentinelops.demo-mode=true",
    "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://localhost.invalid/demo-jwks",
    "sentinelops.security.issuer=https://issuer.sentinelops.test",
    "sentinelops.security.audience=demo-service"
})
@Import(DemoAudienceSecurityIT.TestJwtConfiguration.class)
class DemoAudienceSecurityIT {

    @Autowired private WebApplicationContext context;
    @Autowired private JwtEncoder encoder;

    private MockMvc mockMvc;

    @BeforeEach
    void configureMockMvc() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .apply(springSecurity())
                .build();
    }

    @Test
    void filterRejectsMissingAndWrongAudienceBeforeFaultStateChanges() throws Exception {
        mockMvc.perform(post("/internal/demo/faults/connection-pool")
                        .header(HttpHeaders.AUTHORIZATION, bearerToken(List.of("ops-api"))))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/internal/demo/faults/connection-pool")
                        .header(HttpHeaders.AUTHORIZATION, bearerToken(null)))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/checkout"))
                .andExpect(status().isOk());

        String accepted = bearerToken(List.of("demo-service"));
        mockMvc.perform(post("/internal/demo/faults/connection-pool")
                        .header(HttpHeaders.AUTHORIZATION, accepted))
                .andExpect(status().isOk());
        mockMvc.perform(delete("/internal/demo/faults")
                        .header(HttpHeaders.AUTHORIZATION, accepted))
                .andExpect(status().isOk());
    }

    private String bearerToken(List<String> audience) {
        var claims = JwtClaimsSet.builder()
                .issuer("https://issuer.sentinelops.test")
                .subject("demo-controller")
                .issuedAt(Instant.now().minusSeconds(5))
                .expiresAt(Instant.now().plusSeconds(300))
                .claim("scope", "demo:fault");
        if (audience != null) {
            claims.audience(audience);
        }
        var header = JwsHeader.with(SignatureAlgorithm.RS256)
                .keyId(TestJwtConfiguration.KEY_ID)
                .build();
        String token = encoder.encode(JwtEncoderParameters.from(header, claims.build()))
                .getTokenValue();
        return "Bearer " + token;
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestJwtConfiguration {

        private static final String KEY_ID = "demo-test-key";
        private static final KeyPair KEY_PAIR = generateKeyPair();

        @Bean
        @Primary
        JwtDecoder testJwtDecoder() {
            var decoder = NimbusJwtDecoder.withPublicKey(
                            (RSAPublicKey) KEY_PAIR.getPublic())
                    .build();
            decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                    JwtValidators.createDefaultWithIssuer(
                            "https://issuer.sentinelops.test"),
                    new AudienceValidator("demo-service")));
            return decoder;
        }

        @Bean
        JwtEncoder testJwtEncoder() {
            var key = new RSAKey.Builder((RSAPublicKey) KEY_PAIR.getPublic())
                    .privateKey((RSAPrivateKey) KEY_PAIR.getPrivate())
                    .keyID(KEY_ID)
                    .build();
            return new NimbusJwtEncoder(
                    new ImmutableJWKSet<SecurityContext>(new JWKSet(key)));
        }

        private static KeyPair generateKeyPair() {
            try {
                var generator = KeyPairGenerator.getInstance("RSA");
                generator.initialize(2048);
                return generator.generateKeyPair();
            } catch (NoSuchAlgorithmException impossible) {
                throw new IllegalStateException("RSA is required by the Java runtime", impossible);
            }
        }
    }
}
