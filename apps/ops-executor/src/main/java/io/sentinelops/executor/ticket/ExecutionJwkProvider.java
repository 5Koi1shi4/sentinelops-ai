package io.sentinelops.executor.ticket;

import com.nimbusds.jose.jwk.JWKSet;

@FunctionalInterface
public interface ExecutionJwkProvider {

    JWKSet load();

    default JWKSet refresh() {
        return load();
    }
}
