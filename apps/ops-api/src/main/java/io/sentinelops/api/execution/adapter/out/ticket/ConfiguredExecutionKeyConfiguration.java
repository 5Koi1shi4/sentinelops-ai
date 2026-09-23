package io.sentinelops.api.execution.adapter.out.ticket;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.RSAKey;
import io.sentinelops.api.shared.config.SecretReference;
import java.text.ParseException;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;

@Configuration(proxyBeanMethods = false)
@Profile("production | (!demo & !test)")
public class ConfiguredExecutionKeyConfiguration {

    @Bean
    public RSAKey executionSigningKey(Environment environment) {
        var privateJwk = SecretReference.resolve(environment,
                environment.getProperty("sentinelops.execution-ticket.private-jwk-secret-ref"),
                "Execution signing key");
        try {
            var key = RSAKey.parse(privateJwk);
            if (!key.isPrivate()) {
                throw new IllegalStateException(
                        "Configured execution signing JWK must include private material");
            }
            if (key.size() < 3072) {
                throw new IllegalStateException(
                        "Configured execution signing key must be at least RSA-3072");
            }
            if (key.getKeyID() == null || key.getKeyID().isBlank()) {
                throw new IllegalStateException(
                        "Configured execution signing JWK must include a non-blank kid");
            }
            if (key.getAlgorithm() != null
                    && !JWSAlgorithm.RS256.equals(key.getAlgorithm())) {
                throw new IllegalStateException(
                        "Configured execution signing JWK must use RS256");
            }
            return key;
        } catch (ParseException ignored) {
            throw new IllegalStateException(
                    "Configured execution signing JWK is invalid");
        }
    }
}
