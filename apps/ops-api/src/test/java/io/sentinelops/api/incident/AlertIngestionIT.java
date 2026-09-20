package io.sentinelops.api.incident;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.sentinelops.api.incident.application.AlertEnvelope;
import io.sentinelops.api.incident.application.IncidentApplicationService;
import io.sentinelops.api.incident.application.IncidentSummary;
import io.sentinelops.api.incident.domain.IncidentStatus;
import io.sentinelops.api.support.PostgresIntegrationTest;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class AlertIngestionIT extends PostgresIntegrationTest {

    @Autowired private IncidentApplicationService incidents;
    @Autowired private JdbcClient jdbc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private WebApplicationContext webApplicationContext;

    private MockMvc mockMvc;

    @BeforeEach
    void configureMockMvc() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).build();
    }

    @Test
    void duplicateSourceEventReturnsSameIncidentAndOneSourceEvent() {
        var serviceKey = insertService();
        var alert = alert(serviceKey, "incident-a", "delivery-a", AlertEnvelope.AlertStatus.FIRING);

        var first = incidents.ingest(alert);
        var duplicate = incidents.ingest(alert);

        assertThat(duplicate.id()).isEqualTo(first.id());
        assertThat(incidentCount(serviceKey, "incident-a")).isOne();
        assertThat(occurrenceCount(first.id())).isOne();
        assertThat(eventCount(first.id())).isOne();
        assertThat(outboxCount(first.id())).isOne();
    }

    @Test
    void outboxFailureRollsBackIncidentProjectionAndTimeline() {
        var serviceKey = insertService();
        var sourceEventId = "rollback-delivery";
        jdbc.sql("""
                        create function reject_test_outbox_insert() returns trigger language plpgsql as $$
                        begin
                          raise exception using errcode = '55000', message = 'forced outbox failure';
                        end;
                        $$
                        """)
                .update();
        jdbc.sql("""
                        create trigger reject_test_outbox_insert
                        before insert on outbox_event
                        for each row execute function reject_test_outbox_insert()
                        """)
                .update();

        try {
            assertThatThrownBy(() -> incidents.ingest(alert(
                            serviceKey,
                            "rollback-fingerprint",
                            sourceEventId,
                            AlertEnvelope.AlertStatus.FIRING)))
                    .isInstanceOf(DataAccessException.class);
        } finally {
            jdbc.sql("drop trigger reject_test_outbox_insert on outbox_event").update();
            jdbc.sql("drop function reject_test_outbox_insert()").update();
        }

        assertThat(incidentCount(serviceKey, "rollback-fingerprint")).isZero();
        assertThat(deliveryEventCount(sourceEventId)).isZero();
        assertThat(projectionCount(serviceKey)).isZero();
    }

    @Test
    void duplicateSourceEventInParallelMutatesIncidentOnce() throws Exception {
        var serviceKey = insertService();
        var duplicate = alert(
                serviceKey,
                "parallel-duplicate-fingerprint",
                "same-delivery-id",
                AlertEnvelope.AlertStatus.FIRING);
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);

        try {
            Future<IncidentSummary> first = executor.submit(() -> ingestAfterLatch(duplicate, ready, start));
            Future<IncidentSummary> second = executor.submit(() -> ingestAfterLatch(duplicate, ready, start));

            assertThat(ready.await(5, SECONDS)).isTrue();
            start.countDown();

            var firstResult = first.get(10, SECONDS);
            var secondResult = second.get(10, SECONDS);
            assertThat(secondResult.id()).isEqualTo(firstResult.id());
            assertThat(occurrenceCount(firstResult.id())).isOne();
            assertThat(incidentVersion(firstResult.id())).isZero();
            assertThat(eventCount(firstResult.id())).isOne();
            assertThat(outboxCount(firstResult.id())).isOne();
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void sameFingerprintInParallelCreatesOneActiveIncident() throws Exception {
        var serviceKey = insertService();
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);

        try {
            Future<IncidentSummary> first = executor.submit(() -> {
                ready.countDown();
                assertThat(start.await(5, SECONDS)).isTrue();
                return incidents.ingest(alert(
                        serviceKey, "parallel-fingerprint", "parallel-a", AlertEnvelope.AlertStatus.FIRING));
            });
            Future<IncidentSummary> second = executor.submit(() -> {
                ready.countDown();
                assertThat(start.await(5, SECONDS)).isTrue();
                return incidents.ingest(alert(
                        serviceKey, "parallel-fingerprint", "parallel-b", AlertEnvelope.AlertStatus.FIRING));
            });

            assertThat(ready.await(5, SECONDS)).isTrue();
            start.countDown();

            var firstResult = first.get(10, SECONDS);
            var secondResult = second.get(10, SECONDS);
            assertThat(secondResult.id()).isEqualTo(firstResult.id());
            assertThat(incidentCount(serviceKey, "parallel-fingerprint")).isOne();
            assertThat(occurrenceCount(firstResult.id())).isEqualTo(2);
            assertThat(eventCount(firstResult.id())).isEqualTo(2);
            assertThat(outboxCount(firstResult.id())).isEqualTo(2);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void recoveryDuringApprovalAppendsEventButDoesNotResolve() {
        var serviceKey = insertService();
        var incident = incidents.ingest(
                alert(serviceKey, "approval-recovery", "firing-a", AlertEnvelope.AlertStatus.FIRING));
        jdbc.sql("""
                        update incident
                        set status = 'awaiting_approval', version = version + 1, updated_at = :now
                        where id = :id
                        """)
                .param("now", OffsetDateTime.now(ZoneOffset.UTC))
                .param("id", incident.id())
                .update();

        var afterRecovery = incidents.ingest(
                alert(serviceKey, "approval-recovery", "resolved-a", AlertEnvelope.AlertStatus.RESOLVED));

        assertThat(afterRecovery.status()).isEqualTo(IncidentStatus.VERIFYING);
        assertThat(eventTypes(incident.id())).containsExactly("alert_received", "alert_recovered");
        assertThat(occurrenceCount(incident.id())).isEqualTo(2);
        assertThat(outboxCount(incident.id())).isEqualTo(2);
    }

    @Test
    void incidentDetailReturnsStatusResourceVersionAndOccurrenceCount() throws Exception {
        var serviceKey = insertService();
        var incident = incidents.ingest(
                alert(serviceKey, "detail-fingerprint", "detail-a", AlertEnvelope.AlertStatus.FIRING));
        incidents.ingest(
                alert(serviceKey, "detail-fingerprint", "detail-b", AlertEnvelope.AlertStatus.FIRING));

        mockMvc.perform(get("/api/v1/incidents/{id}", incident.id()))
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"1\""))
                .andExpect(jsonPath("$.id").value(incident.id().toString()))
                .andExpect(jsonPath("$.status").value("DETECTED"))
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.occurrenceCount").value(2));
    }

    @Test
    void incidentListUsesOpenedAtAndIdCursorWithoutOffset() throws Exception {
        var serviceKey = insertService();
        var oldest = incidents.ingest(
                alert(serviceKey, "cursor-old", "cursor-a", AlertEnvelope.AlertStatus.FIRING));
        var pageBoundary = incidents.ingest(
                alert(serviceKey, "cursor-middle", "cursor-b", AlertEnvelope.AlertStatus.FIRING));
        var tiedOpenedAt = Instant.parse("2026-09-20T02:00:00Z");
        setProjectionOpenedAt(oldest.id(), tiedOpenedAt);
        setProjectionOpenedAt(pageBoundary.id(), tiedOpenedAt);

        JsonNode firstPage = responseJson(mockMvc.perform(get("/api/v1/incidents")
                        .param("serviceId", serviceId(serviceKey).toString())
                        .param("pageSize", "1"))
                .andExpect(status().isOk()));
        var firstPageId = UUID.fromString(firstPage.path("items").get(0).path("id").asString());
        assertThat(firstPageId).isIn(oldest.id(), pageBoundary.id());
        var tiedRemainder = firstPageId.equals(oldest.id()) ? pageBoundary.id() : oldest.id();
        var cursor = firstPage.path("nextCursor").asString();
        assertThat(cursor).isNotBlank();

        var insertedAfterPageOne = incidents.ingest(
                alert(serviceKey, "cursor-new", "cursor-c", AlertEnvelope.AlertStatus.FIRING));
        setProjectionOpenedAt(insertedAfterPageOne.id(), Instant.parse("2026-09-20T03:00:00Z"));

        JsonNode secondPage = responseJson(mockMvc.perform(get("/api/v1/incidents")
                        .param("serviceId", serviceId(serviceKey).toString())
                        .param("pageSize", "1")
                        .param("cursor", cursor))
                .andExpect(status().isOk()));
        assertThat(secondPage.path("items").get(0).path("id").asString())
                .isEqualTo(tiedRemainder.toString());
        assertThat(secondPage.path("items").toString())
                .doesNotContain(insertedAfterPageOne.id().toString());
    }

    @Test
    void missingSourceEventIdUsesCanonicalPayloadHash() {
        var serviceKey = insertService();
        var firstPayload = objectMapper.createObjectNode()
                .put("serviceKey", serviceKey)
                .put("fingerprint", "canonical-fingerprint");
        firstPayload.putObject("labels").put("zone", "east").put("cluster", "demo");
        var reorderedPayload = objectMapper.createObjectNode();
        reorderedPayload.putObject("labels").put("cluster", "demo").put("zone", "east");
        reorderedPayload
                .put("fingerprint", "canonical-fingerprint")
                .put("serviceKey", serviceKey);

        var first = incidents.ingest(new AlertEnvelope(
                "test-alertmanager",
                null,
                serviceKey,
                "canonical-fingerprint",
                "Canonical alert",
                "sev2",
                AlertEnvelope.AlertStatus.FIRING,
                firstPayload));
        var duplicate = incidents.ingest(new AlertEnvelope(
                "test-alertmanager",
                null,
                serviceKey,
                "canonical-fingerprint",
                "Canonical alert",
                "sev2",
                AlertEnvelope.AlertStatus.FIRING,
                reorderedPayload));

        assertThat(duplicate.id()).isEqualTo(first.id());
        assertThat(occurrenceCount(first.id())).isOne();
        assertThat(eventCount(first.id())).isOne();
        assertThat(outboxCount(first.id())).isOne();
    }

    @Test
    void alertmanagerWebhookAcceptsStandardPayload() throws Exception {
        var serviceKey = insertService();
        var payload = objectMapper.createObjectNode()
                .put("version", "4")
                .put("groupKey", "{}:{alertname=\"CheckoutLatency\"}")
                .put("status", "firing");
        payload.putObject("commonLabels")
                .put("service_key", serviceKey)
                .put("severity", "sev1")
                .put("incident_fingerprint", "webhook-fingerprint")
                .put("alertname", "CheckoutLatency");
        payload.putObject("commonAnnotations").put("summary", "Checkout latency is elevated");
        payload.putArray("alerts")
                .addObject()
                .put("status", "firing")
                .put("fingerprint", "alertmanager-fingerprint")
                .putObject("labels")
                .put("service_key", serviceKey)
                .put("severity", "sev1");

        mockMvc.perform(post("/api/v1/integrations/alertmanager/webhook")
                        .header("X-SentinelOps-Source", "demo-alertmanager")
                        .header("X-SentinelOps-Event-Id", "webhook-delivery-a")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(payload)))
                .andExpect(status().isAccepted())
                .andExpect(header().string("Location", org.hamcrest.Matchers.startsWith("/api/v1/incidents/")))
                .andExpect(header().string("ETag", "\"0\""))
                .andExpect(jsonPath("$.serviceKey").value(serviceKey))
                .andExpect(jsonPath("$.status").value("DETECTED"));
    }

    @Test
    void alertmanagerWebhookRejectsPayloadOutsidePublishedBounds() throws Exception {
        var serviceKey = insertService();
        var payload = objectMapper.createObjectNode()
                .put("version", "4")
                .put("status", "firing");
        payload.putObject("commonLabels")
                .put("service_key", serviceKey)
                .put("severity", "sev1")
                .put("incident_fingerprint", "invalid-empty-alerts");
        payload.putArray("alerts");

        mockMvc.perform(post("/api/v1/integrations/alertmanager/webhook")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(payload)))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.errorCode").value("invalid_request"))
                .andExpect(jsonPath("$.traceId").isNotEmpty());
    }

    @Test
    void timelineUsesSequenceCursor() throws Exception {
        var serviceKey = insertService();
        var incident = incidents.ingest(
                alert(serviceKey, "timeline-fingerprint", "timeline-a", AlertEnvelope.AlertStatus.FIRING));
        incidents.ingest(
                alert(serviceKey, "timeline-fingerprint", "timeline-b", AlertEnvelope.AlertStatus.FIRING));
        incidents.ingest(
                alert(serviceKey, "timeline-fingerprint", "timeline-c", AlertEnvelope.AlertStatus.FIRING));

        JsonNode firstPage = responseJson(mockMvc.perform(get(
                                "/api/v1/incidents/{id}/timeline", incident.id())
                        .param("pageSize", "2"))
                .andExpect(status().isOk()));
        assertThat(firstPage.path("items").size()).isEqualTo(2);
        assertThat(firstPage.path("items").get(0).path("sequence").asLong()).isEqualTo(1);
        assertThat(firstPage.path("items").get(1).path("sequence").asLong()).isEqualTo(2);

        JsonNode secondPage = responseJson(mockMvc.perform(get(
                                "/api/v1/incidents/{id}/timeline", incident.id())
                        .param("pageSize", "2")
                        .param("cursor", firstPage.path("nextCursor").asString()))
                .andExpect(status().isOk()));
        assertThat(secondPage.path("items").size()).isOne();
        assertThat(secondPage.path("items").get(0).path("sequence").asLong()).isEqualTo(3);
        assertThat(secondPage.path("nextCursor").isNull()).isTrue();
    }

    @Test
    void invalidCursorReturnsProblemDetailsWithErrorCodeAndTraceId() throws Exception {
        mockMvc.perform(get("/api/v1/incidents").param("cursor", "not-base64"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.errorCode").value("invalid_pagination"))
                .andExpect(jsonPath("$.traceId").isNotEmpty());
    }

    @Test
    void malformedTimelineCursorReturnsBadRequest() throws Exception {
        var serviceKey = insertService();
        var incident = incidents.ingest(
                alert(serviceKey, "bad-timeline-cursor", "bad-cursor-a", AlertEnvelope.AlertStatus.FIRING));
        var cursor = java.util.Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString("{\"sequence\":\"1\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8));

        mockMvc.perform(get("/api/v1/incidents/{id}/timeline", incident.id())
                        .param("cursor", cursor))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("invalid_pagination"));
    }

    @Test
    void incidentListFiltersBySeverity() throws Exception {
        var serviceKey = insertService();
        var sev1 = incidents.ingest(alert(
                serviceKey,
                "severity-one",
                "severity-a",
                "sev1",
                AlertEnvelope.AlertStatus.FIRING));
        incidents.ingest(alert(
                serviceKey,
                "severity-three",
                "severity-b",
                "sev3",
                AlertEnvelope.AlertStatus.FIRING));

        mockMvc.perform(get("/api/v1/incidents")
                        .param("serviceId", serviceId(serviceKey).toString())
                        .param("severity", "SEV1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].id").value(sev1.id().toString()));
    }

    @Test
    void invalidParameterTypeReturnsProblemDetails() throws Exception {
        mockMvc.perform(get("/api/v1/incidents").param("serviceId", "not-a-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.errorCode").value("invalid_request"))
                .andExpect(jsonPath("$.traceId").isNotEmpty());
    }

    private AlertEnvelope alert(
            String serviceKey,
            String fingerprint,
            String sourceEventId,
            AlertEnvelope.AlertStatus status) {
        return alert(serviceKey, fingerprint, sourceEventId, "sev1", status);
    }

    private AlertEnvelope alert(
            String serviceKey,
            String fingerprint,
            String sourceEventId,
            String severity,
            AlertEnvelope.AlertStatus status) {
        var payload = objectMapper.createObjectNode()
                .put("serviceKey", serviceKey)
                .put("fingerprint", fingerprint)
                .put("sourceEventId", sourceEventId)
                .put("status", status.name().toLowerCase());
        return new AlertEnvelope(
                "test-alertmanager",
                sourceEventId,
                serviceKey,
                fingerprint,
                "Checkout latency is elevated",
                severity,
                status,
                payload);
    }

    private IncidentSummary ingestAfterLatch(
            AlertEnvelope alert, CountDownLatch ready, CountDownLatch start) throws InterruptedException {
        ready.countDown();
        assertThat(start.await(5, SECONDS)).isTrue();
        return incidents.ingest(alert);
    }

    private String insertService() {
        var id = UUID.randomUUID();
        var serviceKey = "service-" + id;
        jdbc.sql("""
                        insert into service_catalog(
                            id, service_key, display_name, owner_team, created_at, updated_at)
                        values (:id, :serviceKey, 'Checkout API', 'payments-sre', :now, :now)
                        """)
                .param("id", id)
                .param("serviceKey", serviceKey)
                .param("now", OffsetDateTime.now(ZoneOffset.UTC))
                .update();
        return serviceKey;
    }

    private UUID serviceId(String serviceKey) {
        return jdbc.sql("select id from service_catalog where service_key = :serviceKey")
                .param("serviceKey", serviceKey)
                .query(UUID.class)
                .single();
    }

    private long incidentCount(String serviceKey, String fingerprint) {
        return jdbc.sql("""
                        select count(*)
                        from incident i
                        join service_catalog s on s.id = i.service_id
                        where s.service_key = :serviceKey and i.fingerprint = :fingerprint
                          and i.status not in ('resolved', 'suppressed')
                        """)
                .param("serviceKey", serviceKey)
                .param("fingerprint", fingerprint)
                .query(Long.class)
                .single();
    }

    private long occurrenceCount(UUID incidentId) {
        return jdbc.sql("select occurrence_count from incident where id = :id")
                .param("id", incidentId)
                .query(Long.class)
                .single();
    }

    private long incidentVersion(UUID incidentId) {
        return jdbc.sql("select version from incident where id = :id")
                .param("id", incidentId)
                .query(Long.class)
                .single();
    }

    private long eventCount(UUID incidentId) {
        return jdbc.sql("select count(*) from incident_event where incident_id = :id")
                .param("id", incidentId)
                .query(Long.class)
                .single();
    }

    private long deliveryEventCount(String sourceEventId) {
        return jdbc.sql("select count(*) from incident_event where source_event_id = :sourceEventId")
                .param("sourceEventId", sourceEventId)
                .query(Long.class)
                .single();
    }

    private long projectionCount(String serviceKey) {
        return jdbc.sql("""
                        select count(*)
                        from incident_projection p
                        join service_catalog s on s.id = p.service_id
                        where s.service_key = :serviceKey
                        """)
                .param("serviceKey", serviceKey)
                .query(Long.class)
                .single();
    }

    private long outboxCount(UUID incidentId) {
        return jdbc.sql("select count(*) from outbox_event where aggregate_id = :id")
                .param("id", incidentId)
                .query(Long.class)
                .single();
    }

    private java.util.List<String> eventTypes(UUID incidentId) {
        return jdbc.sql("""
                        select event_type from incident_event
                        where incident_id = :id order by seq_no
                        """)
                .param("id", incidentId)
                .query(String.class)
                .list();
    }

    private void setProjectionOpenedAt(UUID incidentId, Instant openedAt) {
        jdbc.sql("update incident_projection set opened_at = :openedAt where incident_id = :id")
                .param("openedAt", OffsetDateTime.ofInstant(openedAt, ZoneOffset.UTC))
                .param("id", incidentId)
                .update();
    }

    private JsonNode responseJson(org.springframework.test.web.servlet.ResultActions action)
            throws Exception {
        return objectMapper.readTree(action.andReturn().getResponse().getContentAsByteArray());
    }
}
