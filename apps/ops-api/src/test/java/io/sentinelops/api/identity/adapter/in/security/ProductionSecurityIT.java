package io.sentinelops.api.identity.adapter.in.security;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.security.oauth2.jwt.JwtException;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ProductionSecurityIT {
    private static final String ISSUER = "https://issuer.sentinelops.test/realms/production";
    private HttpServer jwks;
    private RSAKey key;

    @BeforeAll
    void startJwks() throws Exception {
        var generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        var pair = generator.generateKeyPair();
        key = new RSAKey.Builder((RSAPublicKey) pair.getPublic())
                .privateKey((RSAPrivateKey) pair.getPrivate()).keyID("production-test").build();
        byte[] body = new JWKSet(key.toPublicJWK()).toString().getBytes(StandardCharsets.UTF_8);
        jwks = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        jwks.createContext("/keys", exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        jwks.start();
    }

    @AfterAll
    void stopJwks() {
        jwks.stop(0);
    }

    @Test
    void acceptsApiAndExecutorAudienceButRejectsUntrustedAudienceAndBlankSubject() throws Exception {
        var decoder = new SecurityConfig().jwtDecoder(
                "http://127.0.0.1:" + jwks.getAddress().getPort() + "/keys",
                ISSUER, "sentinelops-api", "sentinelops-executor");
        assertThatCode(() -> decoder.decode(token(ISSUER, "operator", "sentinelops-api",
                Instant.now().plusSeconds(300), null))).doesNotThrowAnyException();
        assertThatCode(() -> decoder.decode(token(ISSUER, "executor", "sentinelops-executor",
                Instant.now().plusSeconds(300), null))).doesNotThrowAnyException();
        assertThatThrownBy(() -> decoder.decode(token(ISSUER, "operator", "unknown-audience",
                Instant.now().plusSeconds(300), null))).isInstanceOf(JwtException.class);
        assertThatThrownBy(() -> decoder.decode(token(ISSUER, "operator",
                List.of("sentinelops-api", "sentinelops-executor"),
                Instant.now().plusSeconds(300), null))).isInstanceOf(JwtException.class);
        assertThatThrownBy(() -> decoder.decode(token(ISSUER, " ", "sentinelops-api",
                Instant.now().plusSeconds(300), null))).isInstanceOf(JwtException.class);
    }

    @Test
    void rejectsWrongIssuerExpiredAndNotYetValidTokens() throws Exception {
        var decoder = new SecurityConfig().jwtDecoder(
                "http://127.0.0.1:" + jwks.getAddress().getPort() + "/keys",
                ISSUER, "sentinelops-api", "sentinelops-executor");
        assertThatThrownBy(() -> decoder.decode(token("https://other.test", "operator",
                "sentinelops-api", Instant.now().plusSeconds(300), null)))
                .isInstanceOf(JwtException.class);
        assertThatThrownBy(() -> decoder.decode(token(ISSUER, "operator",
                "sentinelops-api", Instant.now().minusSeconds(120), null)))
                .isInstanceOf(JwtException.class);
        assertThatThrownBy(() -> decoder.decode(token(ISSUER, "operator",
                "sentinelops-api", Instant.now().plusSeconds(300),
                Instant.now().plusSeconds(120))))
                .isInstanceOf(JwtException.class);
    }

    @Test
    void rejectsTokensOutsideTheirTimeWindowWithoutDefaultGracePeriod() throws Exception {
        var decoder = new SecurityConfig().jwtDecoder(
                "http://127.0.0.1:" + jwks.getAddress().getPort() + "/keys",
                ISSUER, "sentinelops-api", "sentinelops-executor");
        assertThatThrownBy(() -> decoder.decode(token(ISSUER, "operator",
                "sentinelops-api", Instant.now().minusSeconds(5), null)))
                .isInstanceOf(JwtException.class);
        assertThatThrownBy(() -> decoder.decode(token(ISSUER, "operator",
                "sentinelops-api", Instant.now().plusSeconds(300),
                Instant.now().plusSeconds(5))))
                .isInstanceOf(JwtException.class);
    }

    private String token(String issuer, String subject, String audience,
            Instant expires, Instant notBefore) throws Exception {
        return token(issuer, subject, List.of(audience), expires, notBefore);
    }

    private String token(String issuer, String subject, List<String> audiences,
            Instant expires, Instant notBefore) throws Exception {
        var claims = new JWTClaimsSet.Builder()
                .issuer(issuer).subject(subject).audience(audiences)
                .issueTime(Date.from(Instant.now()))
                .expirationTime(Date.from(expires));
        if (notBefore != null) claims.notBeforeTime(Date.from(notBefore));
        var jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256)
                .keyID(key.getKeyID()).build(), claims.build());
        jwt.sign(new RSASSASigner(key.toPrivateKey()));
        return jwt.serialize();
    }
}
