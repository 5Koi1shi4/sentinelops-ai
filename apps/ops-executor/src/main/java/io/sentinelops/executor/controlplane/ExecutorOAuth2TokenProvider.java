package io.sentinelops.executor.controlplane;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

@Component
public class ExecutorOAuth2TokenProvider {

    private final RestClient tokenClient;
    private final String tokenUri;
    private final String clientId;
    private final String clientSecret;
    private final Clock clock;
    private final Map<TokenKey, CachedToken> cache = new HashMap<>();

    @Autowired
    public ExecutorOAuth2TokenProvider(
            RestClient.Builder restClient,
            @Value("${sentinelops.oauth2.token-uri}") String tokenUri,
            @Value("${sentinelops.oauth2.client-id}") String clientId,
            @Value("${sentinelops.oauth2.client-secret}") String clientSecret) {
        this(restClient.build(), tokenUri, clientId, clientSecret, Clock.systemUTC());
    }

    public ExecutorOAuth2TokenProvider(
            RestClient restClient,
            String tokenUri,
            String clientId,
            String clientSecret,
            Clock clock) {
        this.tokenClient = java.util.Objects.requireNonNull(restClient, "restClient");
        this.clientId = requireText(clientId, "clientId");
        this.clientSecret = requireText(clientSecret, "clientSecret");
        this.clock = java.util.Objects.requireNonNull(clock, "clock");
        this.tokenUri = requireText(tokenUri, "tokenUri");
    }

    public synchronized String accessToken(String audience, String scope) {
        var key = new TokenKey(requireText(audience, "audience"), normalize(scope));
        Instant now = clock.instant();
        var cached = cache.get(key);
        if (cached != null && now.isBefore(cached.refreshAt())) {
            return cached.value();
        }
        var form = new LinkedMultiValueMap<String, String>();
        form.add("grant_type", "client_credentials");
        form.add("client_id", clientId);
        form.add("client_secret", clientSecret);
        form.add("audience", key.audience());
        if (!key.scope().isBlank()) {
            form.add("scope", key.scope());
        }
        final TokenResponse response;
        try {
            response = tokenClient
                    .post()
                    .uri(tokenUri)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .body(TokenResponse.class);
        } catch (RestClientResponseException | ResourceAccessException failure) {
            throw new OAuthTokenException("OAuth2 token endpoint is unavailable", failure);
        }
        if (response == null
                || response.accessToken() == null
                || response.accessToken().isBlank()
                || response.expiresIn() <= 0) {
            throw new OAuthTokenException("OAuth2 token endpoint returned an invalid response");
        }
        long earlyRefreshSeconds = response.expiresIn() > 30
                ? response.expiresIn() - 30
                : Math.max(1, response.expiresIn() / 2);
        cache.put(key, new CachedToken(
                response.accessToken(), now.plusSeconds(earlyRefreshSeconds)));
        return response.accessToken();
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim();
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }

    private record TokenKey(String audience, String scope) {}

    private record CachedToken(String value, Instant refreshAt) {}

    private record TokenResponse(
            @JsonProperty("access_token") String accessToken,
            @JsonProperty("expires_in") long expiresIn) {}

    public static final class OAuthTokenException extends RuntimeException {

        public OAuthTokenException(String message) {
            super(message);
        }

        public OAuthTokenException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
