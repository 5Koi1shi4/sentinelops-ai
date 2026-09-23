package io.sentinelops.api.incident.adapter.in.webhook;

import io.sentinelops.api.shared.problem.ApiProblemException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.Locale;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public final class WebhookReplayGuard {
    private final JdbcClient jdbc;

    public WebhookReplayGuard(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void claim(String source, String nonce) {
        var now = Instant.now();
        int inserted = jdbc.sql("""
                        insert into webhook_replay_nonce(
                            source_key, nonce_hash, observed_at, expires_at)
                        values (:source, :hash, :observed, :expires)
                        on conflict do nothing
                        """)
                .param("source", source)
                .param("hash", hash(nonce))
                .param("observed", OffsetDateTime.ofInstant(now, ZoneOffset.UTC))
                .param("expires", OffsetDateTime.ofInstant(
                        now.plus(5, ChronoUnit.MINUTES), ZoneOffset.UTC))
                .update();
        if (inserted != 1) {
            throw new ApiProblemException(HttpStatus.CONFLICT,
                    "WEBHOOK_REPLAYED", "Webhook nonce was already used.");
        }
    }

    @Scheduled(fixedDelayString = "${sentinelops.webhook.nonce-cleanup-interval:PT1M}")
    public void removeExpired() {
        jdbc.sql("""
                        with expired as (
                            select source_key, nonce_hash
                            from webhook_replay_nonce
                            where expires_at < :now
                            order by expires_at
                            limit 1000
                        )
                        delete from webhook_replay_nonce as nonce
                        using expired
                        where nonce.source_key = expired.source_key
                          and nonce.nonce_hash = expired.nonce_hash
                        """)
                .param("now", OffsetDateTime.now(ZoneOffset.UTC))
                .update();
    }

    private String hash(String nonce) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(nonce.toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA-256 is unavailable");
        }
    }
}
