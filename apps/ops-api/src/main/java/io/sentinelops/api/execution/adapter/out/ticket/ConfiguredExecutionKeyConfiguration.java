package io.sentinelops.api.execution.adapter.out.ticket;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.RSAKey;
import java.text.ParseException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration(proxyBeanMethods = false)
@Profile("!demo & !test")
public class ConfiguredExecutionKeyConfiguration {

    @Bean
    public RSAKey executionSigningKey(
            @Value("${sentinelops.execution-ticket.private-jwk}") String privateJwk) {
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
        } catch (ParseException failure) {
            throw new IllegalStateException(
                    "Configured execution signing JWK is invalid", failure);
        }
    }
}
