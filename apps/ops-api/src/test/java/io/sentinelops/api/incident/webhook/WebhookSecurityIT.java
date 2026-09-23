package io.sentinelops.api.incident.webhook;

import static io.sentinelops.api.identity.adapter.in.security.SentinelJwtAuthenticationConverter.API_AUTHORITY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.sentinelops.api.support.PostgresIntegrationTest;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

class WebhookSecurityIT extends PostgresIntegrationTest {
    private static final String ENDPOINT = "/api/v1/integrations/alertmanager/webhook";
    private static final String SOURCE = "test-alertmanager";
    private static final String RATE_SOURCE = "rate-alertmanager";
    private static final String SECRET = "test-only-webhook-key-longer-than-32-bytes";

    @DynamicPropertySource
    static void webhookProperties(DynamicPropertyRegistry registry) {
        registry.add("sentinelops.webhook.source-refs", () ->
                SOURCE + "=env:SENTINELOPS_WEBHOOK_TEST_SECRET,"
                        + RATE_SOURCE + "=env:SENTINELOPS_WEBHOOK_TEST_SECRET");
        registry.add("SENTINELOPS_WEBHOOK_TEST_SECRET", () -> SECRET);
    }

    @Autowired private WebApplicationContext web;
    @Autowired private ObjectMapper json;
    @Autowired private JdbcClient jdbc;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(web).apply(springSecurity()).build();
    }

    @Test
    void unsignedRequestIsRejectedBeforeIncidentPersistence() throws Exception {
        String fingerprint = "unsigned-" + UUID.randomUUID();
        mvc.perform(base(json.writeValueAsBytes(payload(fingerprint))))
                .andExpect(status().isUnauthorized());
        assertNoIncident(fingerprint);
    }

    @Test
    void badSignatureAndStaleTimestampAreRejectedBeforePersistence() throws Exception {
        String fingerprint = "invalid-signature-" + UUID.randomUUID();
        byte[] body = json.writeValueAsBytes(payload(fingerprint));
        mvc.perform(signed(body, Instant.now().getEpochSecond(), nonce(), "wrong-secret"))
                .andExpect(status().isUnauthorized());
        mvc.perform(signed(body, Instant.now().minusSeconds(301).getEpochSecond(),
                        nonce(), SECRET))
                .andExpect(status().isUnauthorized());
        assertNoIncident(fingerprint);
    }

    @Test
    void validDeliveryIsIdempotentButNonceReplayIsRejected() throws Exception {
        String fingerprint = "replay-" + UUID.randomUUID();
        byte[] body = json.writeValueAsBytes(payload(fingerprint));
        String firstNonce = nonce();
        var request = signed(body, Instant.now().getEpochSecond(), firstNonce, SECRET);
        mvc.perform(request).andExpect(status().isAccepted());
        mvc.perform(request).andExpect(status().isConflict());
        mvc.perform(signed(body, Instant.now().getEpochSecond(), nonce(), SECRET))
                .andExpect(status().isAccepted());
        assertThat(incidentCount(fingerprint)).isOne();
        assertThat(jdbc.sql("select occurrence_count from incident where fingerprint=:fingerprint")
                .param("fingerprint", fingerprint).query(Long.class).single()).isOne();
        assertThat(jdbc.sql("select nonce_hash from webhook_replay_nonce where source_key=:source")
                .param("source", SOURCE).query(String.class).list())
                .allMatch(hash -> hash.matches("[0-9a-f]{64}") && !hash.equals(firstNonce));
    }

    @Test
    void bodyLargerThanOneMiBIsRejectedBeforeJsonBinding() throws Exception {
        String fingerprint = "oversized-body-" + UUID.randomUUID();
        ObjectNode payload = payload(fingerprint);
        payload.put("padding", "x".repeat(1_048_576));
        byte[] body = json.writeValueAsBytes(payload);
        mvc.perform(signed(body, Instant.now().getEpochSecond(), nonce(), SECRET))
                .andExpect(status().isPayloadTooLarge());
        assertNoIncident(fingerprint);
    }

    @Test
    void moreThanTwoHundredAlertsAndOversizedLabelsAreRejected() throws Exception {
        String alertsFingerprint = "too-many-alerts-" + UUID.randomUUID();
        ObjectNode alertsPayload = payload(alertsFingerprint);
        var alerts = alertsPayload.withArray("alerts");
        for (int index = 1; index < 201; index++) {
            alerts.addObject().put("status", "firing").putObject("labels")
                    .put("service_key", "checkout-api").put("severity", "sev1");
        }
        mvc.perform(signed(json.writeValueAsBytes(alertsPayload),
                        Instant.now().getEpochSecond(), nonce(), SECRET))
                .andExpect(status().isPayloadTooLarge());
        assertNoIncident(alertsFingerprint);

        String labelFingerprint = "large-label-" + UUID.randomUUID();
        ObjectNode labelPayload = payload(labelFingerprint);
        labelPayload.withObject("commonLabels").put("oversized", "x".repeat(4097));
        mvc.perform(signed(json.writeValueAsBytes(labelPayload),
                        Instant.now().getEpochSecond(), nonce(), SECRET))
                .andExpect(status().isBadRequest());
        assertNoIncident(labelFingerprint);
    }

    @Test
    void perSourceBurstLimitReturnsRetryAfterWithoutPartialIncident() throws Exception {
        for (int index = 0; index < 20; index++) {
            String fingerprint = "allowed-burst-" + UUID.randomUUID();
            byte[] body = json.writeValueAsBytes(payload(fingerprint));
            mvc.perform(signed(body, Instant.now().getEpochSecond(), nonce(), SECRET, RATE_SOURCE))
                    .andExpect(status().isAccepted());
        }
        String limitedFingerprint = "rate-limited-" + UUID.randomUUID();
        mvc.perform(signed(json.writeValueAsBytes(payload(limitedFingerprint)),
                        Instant.now().getEpochSecond(), nonce(), SECRET, RATE_SOURCE))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"));
        assertNoIncident(limitedFingerprint);
    }

    private MockHttpServletRequestBuilder base(byte[] body) {
        return post(ENDPOINT).with(jwt().authorities(new SimpleGrantedAuthority(API_AUTHORITY))
                        .jwt(token -> token.issuer("https://issuer.sentinelops.test")
                                .subject("webhook-security-test")
                                .audience(List.of("sentinelops-api"))
                                .claim("realm_access", Map.of("roles", List.of("observer")))))
                .contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private MockHttpServletRequestBuilder signed(byte[] body, long timestamp,
            String nonce, String secret) {
        return signed(body, timestamp, nonce, secret, SOURCE);
    }

    private MockHttpServletRequestBuilder signed(byte[] body, long timestamp,
            String nonce, String secret, String source) {
        return base(body).header("X-Sentinel-Source", source)
                .header("X-Sentinel-Timestamp", Long.toString(timestamp))
                .header("X-Sentinel-Nonce", nonce)
                .header("X-Sentinel-Signature", signature(timestamp, nonce, body, secret));
    }

    private String signature(long timestamp, String nonce, byte[] body, String secret) {
        try {
            var mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            mac.update((timestamp + "\n" + nonce + "\n").getBytes(StandardCharsets.UTF_8));
            return "v1=" + HexFormat.of().formatHex(mac.doFinal(body));
        } catch (java.security.GeneralSecurityException failure) {
            throw new AssertionError(failure);
        }
    }

    private String nonce() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private ObjectNode payload(String fingerprint) {
        var root = json.createObjectNode().put("version", "4").put("status", "firing");
        root.putObject("commonLabels").put("service_key", "checkout-api")
                .put("severity", "sev1").put("incident_fingerprint", fingerprint)
                .put("alertname", "CheckoutErrorRate");
        root.putObject("commonAnnotations").put("summary", "Checkout failure");
        root.putArray("alerts").addObject().put("status", "firing")
                .putObject("labels").put("service_key", "checkout-api")
                .put("severity", "sev1");
        return root;
    }

    private void assertNoIncident(String fingerprint) {
        assertThat(incidentCount(fingerprint)).isZero();
    }

    private long incidentCount(String fingerprint) {
        return jdbc.sql("select count(*) from incident where fingerprint=:fingerprint")
                .param("fingerprint", fingerprint).query(Long.class).single();
    }
}
