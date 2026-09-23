package io.sentinelops.demo.alert;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Signs the unmodified Alertmanager body inside the private Demo network. */
@Component
@ConditionalOnProperty(
        name = {"sentinelops.demo-mode", "sentinelops.demo-alert-relay-mode"},
        havingValue = "true")
public final class DemoAlertSigningRelay {
    private final URI target;
    private final String source;
    private final byte[] secret;
    private final HttpClient client;
    private final SecureRandom random = new SecureRandom();

    public DemoAlertSigningRelay(
            @Value("${sentinelops.demo-alert-relay-url}") String target,
            @Value("${sentinelops.demo-alert-relay-source:demo-alertmanager}") String source,
            @Value("${sentinelops.demo-alert-relay-secret}") String secret) {
        this.target = URI.create(target);
        if (!"http".equals(this.target.getScheme())
                && !"https".equals(this.target.getScheme())) {
            throw new IllegalArgumentException("Demo alert relay target must be HTTP(S)");
        }
        if (this.target.getHost() == null || this.target.getUserInfo() != null) {
            throw new IllegalArgumentException("Demo alert relay target requires a host");
        }
        if (!source.matches("[a-z][a-z0-9-]{1,63}")) {
            throw new IllegalArgumentException("Invalid Demo alert source");
        }
        this.source = source;
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
        if (this.secret.length < 32) {
            throw new IllegalArgumentException("Demo alert relay secret must be at least 32 bytes");
        }
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(2))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    public int forward(byte[] body) {
        byte[] nonceBytes = new byte[16];
        random.nextBytes(nonceBytes);
        String nonce = HexFormat.of().formatHex(nonceBytes);
        String timestamp = Long.toString(Instant.now().getEpochSecond());
        String signature = sign(timestamp, nonce, body);
        var request = HttpRequest.newBuilder(target)
                .timeout(Duration.ofSeconds(8))
                .header("Content-Type", "application/json")
                .header("X-Sentinel-Source", source)
                .header("X-Sentinel-Timestamp", timestamp)
                .header("X-Sentinel-Nonce", nonce)
                .header("X-Sentinel-Signature", signature)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build();
        try {
            int status = client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
            return status >= 200 && status < 300 ? status : 502;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return 502;
        } catch (IOException failure) {
            return 502;
        }
    }

    private String sign(String timestamp, String nonce, byte[] body) {
        try {
            var mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            mac.update((timestamp + "\n" + nonce + "\n").getBytes(StandardCharsets.UTF_8));
            return "v1=" + HexFormat.of().formatHex(mac.doFinal(body));
        } catch (GeneralSecurityException failure) {
            throw new IllegalStateException("HMAC-SHA256 unavailable", failure);
        }
    }
}
