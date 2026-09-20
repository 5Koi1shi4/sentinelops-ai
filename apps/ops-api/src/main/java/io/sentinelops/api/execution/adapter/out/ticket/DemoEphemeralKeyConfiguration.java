package io.sentinelops.api.execution.adapter.out.ticket;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.RSAKey;
import java.security.GeneralSecurityException;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.util.UUID;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration(proxyBeanMethods = false)
@Profile({"demo", "test"})
public class DemoEphemeralKeyConfiguration {

    @Bean
    RSAKey executionSigningKey() {
        try {
            var generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(3072);
            var pair = generator.generateKeyPair();
            return new RSAKey.Builder((RSAPublicKey) pair.getPublic())
                    .privateKey((RSAPrivateKey) pair.getPrivate())
                    .keyID(UUID.randomUUID().toString())
                    .algorithm(JWSAlgorithm.RS256)
                    .build();
        } catch (GeneralSecurityException failure) {
            throw new IllegalStateException("Could not generate the Demo execution key", failure);
        }
    }
}
