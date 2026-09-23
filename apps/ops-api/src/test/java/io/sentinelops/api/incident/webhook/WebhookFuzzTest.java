package io.sentinelops.api.incident.webhook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import io.sentinelops.api.incident.adapter.in.webhook.WebhookRateLimiter;
import io.sentinelops.api.incident.adapter.in.webhook.WebhookReplayGuard;
import io.sentinelops.api.support.PostgresIntegrationTest;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Random;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

class WebhookFuzzTest extends PostgresIntegrationTest {
    private static final String SOURCE = "fuzz-alertmanager";
    private static final String SECRET = "fuzz-only-webhook-key-longer-than-32-bytes";

    @DynamicPropertySource
    static void webhookProperties(DynamicPropertyRegistry registry) {
        registry.add("sentinelops.webhook.source-refs",
                () -> SOURCE + "=env:SENTINELOPS_WEBHOOK_FUZZ_SECRET");
        registry.add("SENTINELOPS_WEBHOOK_FUZZ_SECRET", () -> SECRET);
    }

    @Autowired private WebApplicationContext web;
    @Autowired private ObjectMapper json;
    @Autowired private JdbcClient jdbc;
    @MockitoBean private WebhookReplayGuard replay;
    @MockitoBean private WebhookRateLimiter rates;

    @Test
    void twoThousandMalformedPayloadsRemainBoundedAndNeverPartiallyPersist() throws Exception {
        var mvc = MockMvcBuilders.webAppContextSetup(web).build();
        var random = new Random(0x5eedL);
        for (int index = 0; index < 2000; index++) {
            byte[] body = malformedPayload(index, random);
            String timestamp = Long.toString(Instant.now().getEpochSecond());
            String nonce = UUID.randomUUID().toString().replace("-", "");
            var response = mvc.perform(post("/api/v1/integrations/alertmanager/webhook")
                            .contentType(MediaType.APPLICATION_JSON).content(body)
                            .header("X-Sentinel-Source", SOURCE)
                            .header("X-Sentinel-Timestamp", timestamp)
                            .header("X-Sentinel-Nonce", nonce)
                            .header("X-Sentinel-Signature", signature(timestamp, nonce, body)))
                    .andReturn().getResponse();
            assertThat(response.getStatus()).as("fuzz case %s", index).isBetween(400, 499);
            assertThat(response.getContentAsString()).doesNotContain("java.lang.", "Exception");
        }
        assertThat(jdbc.sql("select count(*) from incident where fingerprint like 'fuzz-%'")
                .query(Long.class).single()).isZero();
    }

    private byte[] malformedPayload(int index, Random random) {
        var root = json.createObjectNode().put("version", "4").put("status", "firing");
        root.putObject("commonLabels").put("service_key", "checkout-api")
                .put("severity", "sev1")
                .put("incident_fingerprint", "fuzz-" + index);
        root.putArray("alerts").addObject().put("status", "firing")
                .putObject("labels").put("service_key", "checkout-api")
                .put("severity", "sev1");
        switch (index % 8) {
            case 0 -> root.remove("version");
            case 1 -> root.putNull("commonLabels");
            case 2 -> {
                ObjectNode nested = root.putObject("nested");
                for (int depth = 0; depth < 70; depth++) nested = nested.putObject("more");
            }
            case 3 -> {
                var labels = root.withObject("commonLabels");
                for (int label = 0; label < 101; label++) labels.put("label" + label, "v");
            }
            case 4 -> root.withObject("commonLabels").put(
                    "unicode", "汉".repeat(1400 + random.nextInt(50)));
            case 5 -> root.withObject("commonLabels").put("unicode", "汉".repeat(20));
            case 6 -> root.putNull("status");
            case 7 -> root.withArray("alerts").removeAll();
            default -> throw new AssertionError();
        }
        byte[] encoded = json.writeValueAsBytes(root);
        if (index % 8 != 5) return encoded;
        var invalid = java.util.Arrays.copyOf(encoded, encoded.length + 2);
        invalid[encoded.length] = (byte) 0xC3;
        invalid[encoded.length + 1] = (byte) 0x28;
        return invalid;
    }

    private String signature(String timestamp, String nonce, byte[] body) {
        try {
            var mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            mac.update((timestamp + "\n" + nonce + "\n").getBytes(StandardCharsets.UTF_8));
            return "v1=" + HexFormat.of().formatHex(mac.doFinal(body));
        } catch (java.security.GeneralSecurityException failure) {
            throw new AssertionError(failure);
        }
    }
}
