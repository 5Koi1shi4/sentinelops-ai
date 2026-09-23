package io.sentinelops.api.incident.adapter.in.webhook;

import io.sentinelops.api.shared.problem.ApiProblemException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
public final class WebhookSignatureVerifier {
    private static final long MAX_SKEW_SECONDS = 300;
    private final WebhookSecurityProperties properties;

    public WebhookSignatureVerifier(WebhookSecurityProperties properties) {
        this.properties = properties;
    }

    public void verify(String source, String timestamp, String nonce,
            String signature, byte[] rawBody) {
        if (source == null || !source.matches("[a-z][a-z0-9-]{0,63}")) {
            throw unauthorized();
        }
        var secret = properties.secretFor(source);
        if (secret == null || timestamp == null || !timestamp.matches("[0-9]{10,11}")) {
            throw unauthorized();
        }
        long seconds;
        try {
            seconds = Long.parseLong(timestamp);
        } catch (NumberFormatException failure) {
            throw unauthorized();
        }
        long now = Instant.now().getEpochSecond();
        if (seconds < now - MAX_SKEW_SECONDS || seconds > now + MAX_SKEW_SECONDS
                || nonce == null || !nonce.matches("[a-fA-F0-9]{32}")
                || signature == null || !signature.matches("v1=[a-fA-F0-9]{64}")) {
            throw unauthorized();
        }
        byte[] supplied;
        try {
            supplied = HexFormat.of().parseHex(signature.substring(3));
        } catch (IllegalArgumentException failure) {
            throw unauthorized();
        }
        if (!MessageDigest.isEqual(hmac(secret, timestamp, nonce, rawBody), supplied)) {
            throw unauthorized();
        }
    }

    private byte[] hmac(byte[] secret, String timestamp, String nonce, byte[] body) {
        try {
            var mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            mac.update((timestamp + "\n" + nonce + "\n").getBytes(StandardCharsets.UTF_8));
            return mac.doFinal(body);
        } catch (GeneralSecurityException failure) {
            throw new IllegalStateException("Webhook HMAC is unavailable");
        }
    }

    private ApiProblemException unauthorized() {
        return new ApiProblemException(HttpStatus.UNAUTHORIZED,
                "WEBHOOK_AUTHENTICATION_FAILED", "Webhook authentication failed.");
    }
}
