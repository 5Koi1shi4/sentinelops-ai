package io.sentinelops.executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import io.sentinelops.executor.ticket.ExecutionTicketVerifier;
import io.sentinelops.executor.ticket.InvalidExecutionTicket;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ExecutionTicketVerifierTest {

    private RSAKey signingKey;
    private ExecutionTicketVerifier verifier;
    private Instant now;

    @BeforeEach
    void createVerifier() throws Exception {
        var generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(3072);
        var pair = generator.generateKeyPair();
        signingKey = new RSAKey.Builder((RSAPublicKey) pair.getPublic())
                .privateKey((RSAPrivateKey) pair.getPrivate())
                .keyID("executor-ticket-test")
                .algorithm(JWSAlgorithm.RS256)
                .build();
        now = Instant.parse("2026-09-20T10:00:00Z");
        verifier = new ExecutionTicketVerifier(
                () -> new JWKSet(signingKey.toPublicJWK()),
                "urn:sentinelops:ops-api",
                "sentinelops-executor",
                Clock.fixed(now, ZoneOffset.UTC));
    }

    @Test
    void verifiesSignatureAudienceTimeExecutionAndRunbookClaims() throws Exception {
        UUID executionId = UUID.randomUUID();

        var verified = verifier.verify(ticket(executionId, List.of("sentinelops-executor")), executionId);

        assertThat(verified.executionId()).isEqualTo(executionId);
        assertThat(verified.runbookChecksum()).isEqualTo("checksum-v1");
        assertThat(verified.stepId()).isEqualTo("recover-one");
        assertThat(verified.operation()).isEqualTo("recover_connection_pool");
        assertThat(verified.fencingToken()).isEqualTo(7);
    }

    @Test
    void invalidAudienceStopsVerification() throws Exception {
        UUID executionId = UUID.randomUUID();

        assertThatThrownBy(() -> verifier.verify(
                        ticket(executionId, List.of("other-service")), executionId))
                .isInstanceOf(InvalidExecutionTicket.class)
                .hasMessageContaining("audience");
    }

    @Test
    void ticketCannotBeReplayedForAnotherExecution() throws Exception {
        String ticket = ticket(UUID.randomUUID(), List.of("sentinelops-executor"));

        assertThatThrownBy(() -> verifier.verify(ticket, UUID.randomUUID()))
                .isInstanceOf(InvalidExecutionTicket.class)
                .hasMessageContaining("execution");
    }

    @Test
    void expiredTicketIsRejectedWithOnlyFiveSecondsClockSkew() throws Exception {
        UUID executionId = UUID.randomUUID();
        verifier = new ExecutionTicketVerifier(
                () -> new JWKSet(signingKey.toPublicJWK()),
                "urn:sentinelops:ops-api",
                "sentinelops-executor",
                Clock.fixed(now.plusSeconds(66), ZoneOffset.UTC));

        assertThatThrownBy(() -> verifier.verify(
                        ticket(executionId, List.of("sentinelops-executor")), executionId))
                .isInstanceOf(InvalidExecutionTicket.class)
                .hasMessageContaining("time");
    }

    private String ticket(UUID executionId, List<String> audience) throws Exception {
        var claims = new JWTClaimsSet.Builder()
                .jwtID(UUID.randomUUID().toString())
                .issuer("urn:sentinelops:ops-api")
                .audience(audience)
                .issueTime(Date.from(now))
                .notBeforeTime(Date.from(now.minusSeconds(2)))
                .expirationTime(Date.from(now.plusSeconds(60)))
                .claim("execution_id", executionId.toString())
                .claim("incident_id", UUID.randomUUID().toString())
                .claim("runbook_version_id", UUID.randomUUID().toString())
                .claim("runbook_checksum", "checksum-v1")
                .claim("step_id", "recover-one")
                .claim("operation", "recover_connection_pool")
                .claim("parameters", Map.of("replicas", 1))
                .claim("target", "demo-checkout")
                .claim("risk", "R1")
                .claim("adapter_id", "demo-http")
                .claim("fencing_token", 7)
                .build();
        var signed = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.RS256)
                        .type(JOSEObjectType.JWT)
                        .keyID(signingKey.getKeyID())
                        .build(),
                claims);
        signed.sign(new RSASSASigner(signingKey.toPrivateKey()));
        return signed.serialize();
    }
}
