package io.sentinelops.api.execution.adapter.out.ticket;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import io.sentinelops.api.execution.application.ExecutionTicketClaims;
import io.sentinelops.api.execution.application.ExecutionTicketSigner;
import io.sentinelops.api.execution.application.ExecutionTicketVerifier;
import io.sentinelops.api.execution.application.ExecutionTicketVerifier.VerifiedTicket;
import io.sentinelops.api.execution.application.InvalidExecutionTicket;
import java.text.ParseException;
import java.time.Instant;
import java.util.Date;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Component
public final class NimbusExecutionTicketSigner
        implements ExecutionTicketSigner, ExecutionTicketVerifier {

    private final RSAKey signingKey;
    private final ObjectMapper objectMapper;

    public NimbusExecutionTicketSigner(RSAKey signingKey, ObjectMapper objectMapper) {
        if (!signingKey.isPrivate()) {
            throw new IllegalArgumentException("Execution signing key must include private material");
        }
        if (signingKey.getKeyID() == null || signingKey.getKeyID().isBlank()) {
            throw new IllegalArgumentException("Execution signing key must include a non-blank kid");
        }
        this.signingKey = signingKey;
        this.objectMapper = objectMapper;
    }

    @Override
    public String sign(ExecutionTicketClaims claims) {
        var header = new JWSHeader.Builder(JWSAlgorithm.RS256)
                .type(JOSEObjectType.JWT)
                .keyID(signingKey.getKeyID())
                .build();
        var body = new JWTClaimsSet.Builder()
                .jwtID(claims.jti())
                .issuer(claims.issuer())
                .audience(claims.audience())
                .issueTime(Date.from(claims.issuedAt()))
                .notBeforeTime(Date.from(claims.notBefore()))
                .expirationTime(Date.from(claims.expiresAt()))
                .claim("execution_id", claims.executionId().toString())
                .claim("incident_id", claims.incidentId().toString())
                .claim("proposal_id", claims.proposalId().toString())
                .claim("runbook_id", claims.runbookId().toString())
                .claim("runbook_version_id", claims.runbookVersionId().toString())
                .claim("runbook_checksum", claims.runbookChecksum())
                .claim("parameters", claims.parameters())
                .claim("target", claims.target())
                .claim("risk", claims.risk().name())
                .claim("adapter_id", claims.adapterId())
                .claim("fencing_token", claims.fencingToken())
                .build();
        var token = new SignedJWT(header, body);
        try {
            token.sign(new RSASSASigner(signingKey.toPrivateKey()));
            return token.serialize();
        } catch (JOSEException failure) {
            throw new IllegalStateException("Could not sign execution ticket", failure);
        }
    }

    @Override
    public JsonNode publicJwks() {
        return objectMapper.valueToTree(new JWKSet(signingKey.toPublicJWK()).toJSONObject());
    }

    @Override
    public VerifiedTicket verify(
            String token, String expectedIssuer, String expectedAudience, Instant now) {
        if (token == null || token.isBlank()) {
            throw invalid("Execution ticket must not be blank");
        }
        try {
            var parsed = SignedJWT.parse(token);
            var header = parsed.getHeader();
            if (!JWSAlgorithm.RS256.equals(header.getAlgorithm())
                    || !JOSEObjectType.JWT.equals(header.getType())
                    || !Objects.equals(signingKey.getKeyID(), header.getKeyID())
                    || !parsed.verify(new RSASSAVerifier(signingKey.toRSAPublicKey()))) {
                throw invalid("Execution ticket signature or header is invalid");
            }
            var claims = parsed.getJWTClaimsSet();
            if (!expectedIssuer.equals(claims.getIssuer())
                    || !claims.getAudience().contains(expectedAudience)) {
                throw invalid("Execution ticket issuer or audience is invalid");
            }
            Instant issuedAt = instant(claims.getIssueTime(), "iat");
            Instant notBefore = instant(claims.getNotBeforeTime(), "nbf");
            Instant expiresAt = instant(claims.getExpirationTime(), "exp");
            if (issuedAt.isAfter(now.plusSeconds(5))
                    || notBefore.isAfter(now.plusSeconds(5))
                    || !expiresAt.isAfter(now.minusSeconds(5))
                    || !expiresAt.isAfter(issuedAt)) {
                throw invalid("Execution ticket is outside its valid time window");
            }
            String jti = required(claims.getJWTID(), "jti");
            UUID executionId = UUID.fromString(
                    required(claims.getStringClaim("execution_id"), "execution_id"));
            Long fencingToken = claims.getLongClaim("fencing_token");
            if (fencingToken == null || fencingToken <= 0) {
                throw invalid("Execution ticket fencing token is invalid");
            }
            String checksum = required(
                    claims.getStringClaim("runbook_checksum"), "runbook_checksum");
            return new VerifiedTicket(jti, executionId, fencingToken, checksum, expiresAt);
        } catch (InvalidExecutionTicket failure) {
            throw failure;
        } catch (ParseException | JOSEException | IllegalArgumentException failure) {
            throw invalid("Execution ticket is malformed");
        }
    }

    private Instant instant(Date value, String claim) {
        if (value == null) {
            throw invalid("Execution ticket is missing " + claim);
        }
        return value.toInstant();
    }

    private String required(String value, String claim) {
        if (value == null || value.isBlank()) {
            throw invalid("Execution ticket is missing " + claim);
        }
        return value;
    }

    private InvalidExecutionTicket invalid(String message) {
        return new InvalidExecutionTicket(message);
    }
}
