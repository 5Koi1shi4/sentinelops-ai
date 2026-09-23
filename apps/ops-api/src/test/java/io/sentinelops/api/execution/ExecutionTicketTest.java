package io.sentinelops.api.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.SignedJWT;
import io.sentinelops.api.execution.adapter.out.ticket.ConfiguredExecutionKeyConfiguration;
import io.sentinelops.api.execution.adapter.out.ticket.NimbusExecutionTicketSigner;
import io.sentinelops.api.execution.application.ExecutionTicketClaims;
import io.sentinelops.api.execution.application.InvalidExecutionTicket;
import io.sentinelops.api.knowledge.domain.RiskLevel;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import tools.jackson.databind.ObjectMapper;

class ExecutionTicketTest {

    @Test
    void signsRs256TicketAndBindsItToTheRunbookChecksum() throws Exception {
        var keyPairGenerator = KeyPairGenerator.getInstance("RSA");
        keyPairGenerator.initialize(3072);
        var pair = keyPairGenerator.generateKeyPair();
        var key = new RSAKey.Builder((RSAPublicKey) pair.getPublic())
                .privateKey((RSAPrivateKey) pair.getPrivate())
                .keyID("ticket-test-key")
                .algorithm(JWSAlgorithm.RS256)
                .build();
        var signer = new NimbusExecutionTicketSigner(key, new ObjectMapper());
        Instant now = Instant.parse("2026-09-20T10:00:00Z");
        var claims = new ExecutionTicketClaims(
                UUID.randomUUID().toString(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                "checksum-v1",
                Map.of("replicas", 1),
                "demo-checkout",
                RiskLevel.R1,
                "demo-http",
                "recover-one",
                "recover_connection_pool",
                7,
                "urn:sentinelops:ops-api",
                List.of("sentinelops-executor"),
                now,
                now.minusSeconds(2),
                now.plusSeconds(60));

        String token = signer.sign(claims);
        var parsed = SignedJWT.parse(token);

        assertThat(parsed.getHeader().getAlgorithm()).isEqualTo(JWSAlgorithm.RS256);
        assertThat(parsed.verify(new RSASSAVerifier(key.toRSAPublicKey()))).isTrue();
        assertThat(parsed.getJWTClaimsSet().getStringClaim("runbook_checksum"))
                .isEqualTo("checksum-v1");
        assertThat(parsed.getJWTClaimsSet().getLongClaim("fencing_token")).isEqualTo(7);
        assertThat(parsed.getJWTClaimsSet().getStringClaim("step_id"))
                .isEqualTo("recover-one");
        assertThat(parsed.getJWTClaimsSet().getStringClaim("operation"))
                .isEqualTo("recover_connection_pool");
        var verified = signer.verify(
                token,
                "urn:sentinelops:ops-api",
                "sentinelops-executor",
                now.plusSeconds(10));
        assertThat(verified.executionId()).isEqualTo(claims.executionId());
        assertThat(verified.fencingToken()).isEqualTo(7);
        assertThat(verified.runbookChecksum()).isEqualTo("checksum-v1");
        assertThat(signer.publicJwks().path("keys").get(0).has("d")).isFalse();
        assertThat(new ConfiguredExecutionKeyConfiguration()
                        .executionSigningKey(new MockEnvironment()
                                .withProperty("sentinelops.execution-ticket.private-jwk-secret-ref",
                                        "env:TEST_EXECUTION_KEY")
                                .withProperty("TEST_EXECUTION_KEY", key.toJSONString()))
                        .toPublicJWK())
                .isEqualTo(key.toPublicJWK());
        assertThatThrownBy(() -> claims.requireRunbookChecksum("other"))
                .isInstanceOf(InvalidExecutionTicket.class);
        assertThatThrownBy(() -> signer.verify(
                        token + "tampered",
                        "urn:sentinelops:ops-api",
                        "sentinelops-executor",
                        now.plusSeconds(10)))
                .isInstanceOf(InvalidExecutionTicket.class);
    }

    @Test
    void rejectsAProductionSigningKeyWithoutAKeyId() throws Exception {
        var keyPairGenerator = KeyPairGenerator.getInstance("RSA");
        keyPairGenerator.initialize(3072);
        var pair = keyPairGenerator.generateKeyPair();
        var keyWithoutId = new RSAKey.Builder((RSAPublicKey) pair.getPublic())
                .privateKey((RSAPrivateKey) pair.getPrivate())
                .algorithm(JWSAlgorithm.RS256)
                .build();

        assertThatThrownBy(() -> new ConfiguredExecutionKeyConfiguration()
                        .executionSigningKey(new MockEnvironment()
                                .withProperty("sentinelops.execution-ticket.private-jwk-secret-ref",
                                        "env:TEST_EXECUTION_KEY")
                                .withProperty("TEST_EXECUTION_KEY", keyWithoutId.toJSONString())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("kid");
        assertThatThrownBy(
                        () -> new NimbusExecutionTicketSigner(keyWithoutId, new ObjectMapper()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("kid");
    }
}
