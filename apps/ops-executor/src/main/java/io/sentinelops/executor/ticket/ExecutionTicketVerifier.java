package io.sentinelops.executor.ticket;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.SignedJWT;
import io.sentinelops.executor.runbook.AuthorizedRunbookStep;
import java.text.ParseException;
import java.time.Clock;
import java.time.Instant;
import java.util.Date;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class ExecutionTicketVerifier {

    private final ExecutionJwkProvider keys;
    private final String expectedIssuer;
    private final String expectedAudience;
    private final Clock clock;

    @Autowired
    public ExecutionTicketVerifier(
            ExecutionJwkProvider keys,
            @Value("${sentinelops.execution-ticket.issuer}") String expectedIssuer,
            @Value("${sentinelops.execution-ticket.audience}") String expectedAudience) {
        this(keys, expectedIssuer, expectedAudience, Clock.systemUTC());
    }

    public ExecutionTicketVerifier(
            ExecutionJwkProvider keys,
            String expectedIssuer,
            String expectedAudience,
            Clock clock) {
        this.keys = java.util.Objects.requireNonNull(keys, "keys");
        this.expectedIssuer = requireText(expectedIssuer, "expectedIssuer");
        this.expectedAudience = requireText(expectedAudience, "expectedAudience");
        this.clock = java.util.Objects.requireNonNull(clock, "clock");
    }

    public AuthorizedRunbookStep verify(String token, UUID expectedExecutionId) {
        if (token == null || token.isBlank()) {
            throw invalid("Execution ticket must not be blank");
        }
        java.util.Objects.requireNonNull(expectedExecutionId, "expectedExecutionId");
        try {
            var parsed = SignedJWT.parse(token);
            var header = parsed.getHeader();
            if (!JWSAlgorithm.RS256.equals(header.getAlgorithm())
                    || !JOSEObjectType.JWT.equals(header.getType())
                    || header.getKeyID() == null
                    || header.getKeyID().isBlank()) {
                throw invalid("Execution ticket header is invalid");
            }
            RSAKey key = rsaKey(header.getKeyID());
            if (!parsed.verify(new RSASSAVerifier(key.toRSAPublicKey()))) {
                throw invalid("Execution ticket signature is invalid");
            }
            var claims = parsed.getJWTClaimsSet();
            if (!expectedIssuer.equals(claims.getIssuer())) {
                throw invalid("Execution ticket issuer is invalid");
            }
            if (!claims.getAudience().contains(expectedAudience)) {
                throw invalid("Execution ticket audience is invalid");
            }
            Instant now = clock.instant();
            Instant issuedAt = instant(claims.getIssueTime(), "iat");
            Instant notBefore = instant(claims.getNotBeforeTime(), "nbf");
            Instant expiresAt = instant(claims.getExpirationTime(), "exp");
            if (issuedAt.isAfter(now.plusSeconds(5))
                    || notBefore.isAfter(now.plusSeconds(5))
                    || !expiresAt.isAfter(now)
                    || !expiresAt.isAfter(issuedAt)) {
                throw invalid("Execution ticket is outside its valid time window");
            }
            required(claims.getJWTID(), "jti");
            UUID executionId = uuid(claims.getStringClaim("execution_id"), "execution_id");
            if (!executionId.equals(expectedExecutionId)) {
                throw invalid("Execution ticket execution ID does not match the message");
            }
            Long fencingToken = claims.getLongClaim("fencing_token");
            if (fencingToken == null || fencingToken <= 0) {
                throw invalid("Execution ticket fencing token is invalid");
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> parameters = claims.getJSONObjectClaim("parameters");
            if (parameters == null) {
                throw invalid("Execution ticket is missing parameters");
            }
            return new AuthorizedRunbookStep(
                    executionId,
                    uuid(claims.getStringClaim("incident_id"), "incident_id"),
                    uuid(claims.getStringClaim("runbook_version_id"), "runbook_version_id"),
                    required(claims.getStringClaim("runbook_checksum"), "runbook_checksum"),
                    required(claims.getStringClaim("step_id"), "step_id"),
                    required(claims.getStringClaim("operation"), "operation"),
                    required(claims.getStringClaim("adapter_id"), "adapter_id"),
                    parameters,
                    required(claims.getStringClaim("target"), "target"),
                    required(claims.getStringClaim("risk"), "risk"),
                    fencingToken);
        } catch (InvalidExecutionTicket failure) {
            throw failure;
        } catch (ParseException | JOSEException | IllegalArgumentException failure) {
            throw invalid("Execution ticket is malformed");
        }
    }

    private RSAKey rsaKey(String keyId) {
        JWK key = keys.load().getKeyByKeyId(keyId);
        if (key == null) {
            key = keys.refresh().getKeyByKeyId(keyId);
        }
        if (!(key instanceof RSAKey rsaKey)) {
            throw invalid("Execution ticket signing key is unknown");
        }
        if (rsaKey.isPrivate()
                || (rsaKey.getAlgorithm() != null
                        && !JWSAlgorithm.RS256.equals(rsaKey.getAlgorithm()))) {
            throw invalid("Execution ticket signing key is not allowed");
        }
        return rsaKey;
    }

    private Instant instant(Date value, String claim) {
        if (value == null) {
            throw invalid("Execution ticket is missing " + claim);
        }
        return value.toInstant();
    }

    private UUID uuid(String value, String claim) {
        return UUID.fromString(required(value, claim));
    }

    private String required(String value, String claim) {
        if (value == null || value.isBlank()) {
            throw invalid("Execution ticket is missing " + claim);
        }
        return value;
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }

    private InvalidExecutionTicket invalid(String message) {
        return new InvalidExecutionTicket(message);
    }
}
