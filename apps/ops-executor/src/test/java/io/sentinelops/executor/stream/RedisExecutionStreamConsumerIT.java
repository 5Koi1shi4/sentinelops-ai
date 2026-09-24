package io.sentinelops.executor.stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.startsWith;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withNoContent;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import io.sentinelops.executor.controlplane.ControlPlaneClient;
import io.sentinelops.executor.controlplane.ExecutorOAuth2TokenProvider;
import io.sentinelops.executor.runbook.AuthorizedRunbookStep;
import io.sentinelops.executor.runbook.ExecutionStepResult;
import io.sentinelops.executor.runbook.RunbookAdapter;
import io.sentinelops.executor.runbook.RunbookDispatcher;
import io.sentinelops.executor.ticket.ExecutionTicketVerifier;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.ObjectMapper;

@Testcontainers
class RedisExecutionStreamConsumerIT {

    private static final String STREAM = "sentinelops.executions.recovery-test";
    private static final String GROUP = "ops-executors-test";

    @Container
    static final GenericContainer<?> VALKEY = new GenericContainer<>(
                    DockerImageName.parse("valkey/valkey:8.1.10-alpine"))
            .withExposedPorts(6379);

    private static LettuceConnectionFactory connectionFactory;
    private static StringRedisTemplate redis;

    @BeforeAll
    static void connectToValkey() {
        connectionFactory = new LettuceConnectionFactory(
                VALKEY.getHost(), VALKEY.getMappedPort(6379));
        connectionFactory.afterPropertiesSet();
        connectionFactory.start();
        redis = new StringRedisTemplate(connectionFactory);
        redis.afterPropertiesSet();
    }

    @AfterAll
    static void disconnectFromValkey() {
        connectionFactory.destroy();
    }

    @BeforeEach
    void clearStream() {
        redis.delete(STREAM);
    }

    @Test
    void anotherExecutorReclaimsAnUnacknowledgedMessageAfterRestart() throws Exception {
        var crashedListener = mock(ExecutionMessageListener.class);
        doThrow(new IllegalStateException("executor crashed"))
                .when(crashedListener)
                .onMessage(any(ExecutionMessage.class));
        var first = consumer("executor-before-restart", crashedListener);
        first.initializeGroup();

        UUID eventId = UUID.randomUUID();
        UUID executionId = UUID.randomUUID();
        var recordId = redis.opsForStream().add(StreamRecords.string(Map.of(
                        "eventId", eventId.toString(),
                        "executionId", executionId.toString()))
                .withStreamKey(STREAM));

        assertThat(first.pollOnce()).isEqualTo(1);
        assertThat(redis.opsForStream().pending(STREAM, GROUP).getTotalPendingMessages())
                .isEqualTo(1);

        Thread.sleep(25);
        var recoveredListener = mock(ExecutionMessageListener.class);
        var acknowledger = new RedisExecutionMessageAcknowledger(redis, STREAM, GROUP);
        doAnswer(invocation -> {
                    acknowledger.acknowledge(invocation.getArgument(0, ExecutionMessage.class));
                    return null;
                })
                .when(recoveredListener)
                .onMessage(any(ExecutionMessage.class));
        var restarted = consumer("executor-after-restart", recoveredListener);
        restarted.initializeGroup();

        assertThat(restarted.pollOnce()).isEqualTo(1);
        var recovered = ArgumentCaptor.forClass(ExecutionMessage.class);
        verify(recoveredListener).onMessage(recovered.capture());
        assertThat(recovered.getValue())
                .isEqualTo(new ExecutionMessage(recordId.getValue(), eventId, executionId));
        assertThat(redis.opsForStream().pending(STREAM, GROUP).getTotalPendingMessages())
                .isZero();
    }

    @Test
    void duplicateDeliveryAndRestartProduceOnlyOneAdapterSideEffect() throws Exception {
        UUID eventId = UUID.randomUUID();
        UUID executionId = UUID.randomUUID();
        var firstRecordId = addMessage(eventId, executionId);
        var duplicateRecordId = addMessage(eventId, executionId);
        var serverBuilder = RestClient.builder().baseUrl("https://control.example.test");
        var server = MockRestServiceServer.bindTo(serverBuilder).build();
        var tokens = mock(ExecutorOAuth2TokenProvider.class);
        when(tokens.accessToken("sentinelops-api", "")).thenReturn("api-token");
        var controlPlane = new ControlPlaneClient(
                serverBuilder.build(),
                tokens,
                new ObjectMapper(),
                "sentinelops-api",
                Duration.ofMinutes(5));
        var verifier = mock(ExecutionTicketVerifier.class);
        var step = authorizedStep(executionId);
        when(verifier.verify("signed-ticket", executionId)).thenReturn(step);
        var sideEffects = new AtomicInteger();
        var dispatcher = new RunbookDispatcher(java.util.List.of(countingAdapter(sideEffects)));

        expectClaimAccepted(server, executionId, firstRecordId);
        expectAttemptPhase(server, executionId, firstRecordId, "prepared");
        expectAttemptPhase(server, executionId, firstRecordId, "dispatched");
        server.expect(requestTo(controlUrl(executionId, "complete")))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header(
                        "Idempotency-Key", startsWith(firstRecordId + ":complete:")))
                .andRespond(withNoContent());
        expectClaimTerminal(server, executionId, duplicateRecordId);
        expectClaimTerminal(server, executionId, firstRecordId);

        var redisAcknowledger = new RedisExecutionMessageAcknowledger(redis, STREAM, GROUP);
        var failFirstAcknowledgement = new AtomicBoolean(true);
        ExecutionMessageAcknowledger crashingAcknowledger = message -> {
            if (failFirstAcknowledgement.getAndSet(false)) {
                throw new IllegalStateException("executor stopped before XACK");
            }
            redisAcknowledger.acknowledge(message);
        };
        var firstListener = new ExecutionMessageListener(
                controlPlane,
                verifier,
                dispatcher,
                noOpHeartbeat(),
                crashingAcknowledger);
        var firstExecutor = consumer("executor-crashes-before-xack", firstListener);
        firstExecutor.initializeGroup();

        assertThat(firstExecutor.pollOnce()).isEqualTo(2);
        assertThat(sideEffects).hasValue(1);
        assertThat(redis.opsForStream().pending(STREAM, GROUP).getTotalPendingMessages())
                .isEqualTo(1);

        Thread.sleep(25);
        var restartedListener = new ExecutionMessageListener(
                controlPlane,
                verifier,
                dispatcher,
                noOpHeartbeat(),
                redisAcknowledger);
        var restartedExecutor = consumer("executor-after-crash", restartedListener);
        restartedExecutor.initializeGroup();

        assertThat(restartedExecutor.pollOnce()).isEqualTo(1);
        assertThat(sideEffects).hasValue(1);
        assertThat(redis.opsForStream().pending(STREAM, GROUP).getTotalPendingMessages())
                .isZero();
        server.verify();
    }

    @Test
    void reclaimCannotAcknowledgeAnExecutionProtectedByHeartbeats() throws Exception {
        UUID eventId = UUID.randomUUID();
        UUID executionId = UUID.randomUUID();
        addMessage(eventId, executionId);
        var controlPlane = mock(ControlPlaneClient.class);
        var claim = new ControlPlaneClient.ClaimedExecution(
                executionId, 1, Instant.now().plusSeconds(30), "signed-ticket");
        when(controlPlane.claim(eq(executionId), any(String.class)))
                .thenReturn(claim)
                .thenThrow(new ControlPlaneClient.TransientControlPlaneException(
                        "Execution lease is still active"));
        var heartbeatObserved = new CountDownLatch(1);
        var heartbeatSequence = new AtomicInteger();
        doAnswer(invocation -> {
                    heartbeatObserved.countDown();
                    return new ControlPlaneClient.HeartbeatLease(
                            executionId,
                            1,
                            Instant.now().plusSeconds(30),
                            "rotated-ticket-" + heartbeatSequence.incrementAndGet());
                })
                .when(controlPlane)
                .heartbeat(eq(executionId), any(String.class), eq(1L), any(String.class));
        var verifier = mock(ExecutionTicketVerifier.class);
        when(verifier.verify(any(String.class), eq(executionId)))
                .thenReturn(authorizedStep(executionId));
        var dispatchStarted = new CountDownLatch(1);
        var releaseDispatch = new CountDownLatch(1);
        var sideEffects = new AtomicInteger();
        var dispatcher = new RunbookDispatcher(java.util.List.of(
                blockingAdapter(sideEffects, dispatchStarted, releaseDispatch)));
        var acknowledger = new RedisExecutionMessageAcknowledger(redis, STREAM, GROUP);
        var firstHeartbeats = new ScheduledExecutionLeaseHeartbeat(
                controlPlane,
                Duration.ofMillis(5),
                Executors.newSingleThreadScheduledExecutor());
        var secondHeartbeats = new ScheduledExecutionLeaseHeartbeat(
                controlPlane,
                Duration.ofMillis(5),
                Executors.newSingleThreadScheduledExecutor());
        var firstListener = new ExecutionMessageListener(
                controlPlane, verifier, dispatcher, firstHeartbeats, acknowledger);
        var secondListener = new ExecutionMessageListener(
                controlPlane, verifier, dispatcher, secondHeartbeats, acknowledger);
        var firstExecutor = consumer("executor-running-long-step", firstListener);
        var secondExecutor = consumer("executor-reclaims-pending", secondListener);
        firstExecutor.initializeGroup();
        secondExecutor.initializeGroup();

        try (var worker = Executors.newSingleThreadExecutor()) {
            var firstPoll = worker.submit(firstExecutor::pollOnce);
            assertThat(dispatchStarted.await(1, TimeUnit.SECONDS)).isTrue();
            assertThat(heartbeatObserved.await(1, TimeUnit.SECONDS)).isTrue();

            Thread.sleep(25);
            assertThat(secondExecutor.pollOnce()).isEqualTo(1);
            assertThat(redis.opsForStream().pending(STREAM, GROUP).getTotalPendingMessages())
                    .isEqualTo(1);
            assertThat(sideEffects).hasValue(1);

            releaseDispatch.countDown();
            assertThat(firstPoll.get(1, TimeUnit.SECONDS)).isEqualTo(1);
        } finally {
            releaseDispatch.countDown();
            firstHeartbeats.shutdown();
            secondHeartbeats.shutdown();
        }

        assertThat(redis.opsForStream().pending(STREAM, GROUP).getTotalPendingMessages())
                .isZero();
        verify(controlPlane, times(2)).claim(eq(executionId), any(String.class));
        verify(controlPlane, atLeastOnce())
                .heartbeat(eq(executionId), any(String.class), eq(1L), any(String.class));
        verify(controlPlane, times(1)).complete(any(), any(), any(), any());
    }

    private String addMessage(UUID eventId, UUID executionId) {
        return redis.opsForStream()
                .add(StreamRecords.string(Map.of(
                                "eventId", eventId.toString(),
                                "executionId", executionId.toString()))
                        .withStreamKey(STREAM))
                .getValue();
    }

    private void expectClaimAccepted(
            MockRestServiceServer server, UUID executionId, String recordId) {
        String body = """
                {
                  "executionId":"%s",
                  "fencingToken":1,
                  "leaseUntil":"%s",
                  "ticket":"signed-ticket"
                }
                """.formatted(executionId, Instant.now().plusSeconds(30));
        server.expect(requestTo(controlUrl(executionId, "claim")))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer api-token"))
                .andExpect(header("Idempotency-Key", startsWith(recordId + ":claim:")))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
    }

    private void expectClaimTerminal(
            MockRestServiceServer server, UUID executionId, String recordId) {
        server.expect(requestTo(controlUrl(executionId, "claim")))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Idempotency-Key", startsWith(recordId + ":claim:")))
                .andRespond(withStatus(HttpStatus.CONFLICT)
                        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                        .body("{\"errorCode\":\"execution_not_claimable\"}"));
    }

    private void expectAttemptPhase(
            MockRestServiceServer server, UUID executionId,
            String recordId, String phase) {
        server.expect(requestTo(controlUrl(executionId, "attempt-events")))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Idempotency-Key", startsWith(recordId + ':' + phase + ':')))
                .andRespond(withNoContent());
    }

    private String controlUrl(UUID executionId, String action) {
        return "https://control.example.test/internal/v1/executions/"
                + executionId + ':' + action;
    }

    private AuthorizedRunbookStep authorizedStep(UUID executionId) {
        return new AuthorizedRunbookStep(
                executionId,
                UUID.randomUUID(),
                UUID.randomUUID(),
                "checksum-v1",
                "recover-one",
                "recover_connection_pool",
                "demo-http",
                Map.of("replicas", 1),
                "demo-checkout",
                "R1",
                1);
    }

    private RunbookAdapter countingAdapter(AtomicInteger sideEffects) {
        return new RunbookAdapter() {
            @Override
            public String adapterId() {
                return "demo-http";
            }

            @Override
            public Set<String> supportedOperations() {
                return Set.of("recover_connection_pool");
            }

            @Override
            public ExecutionStepResult execute(
                    AuthorizedRunbookStep step,
                    io.sentinelops.executor.runbook.IdempotencyContext context) {
                sideEffects.incrementAndGet();
                return ExecutionStepResult.succeeded(
                        "1.0.0", "request-hash", Map.of("changed", true));
            }
        };
    }

    private RunbookAdapter blockingAdapter(
            AtomicInteger sideEffects,
            CountDownLatch dispatchStarted,
            CountDownLatch releaseDispatch) {
        return new RunbookAdapter() {
            @Override
            public String adapterId() {
                return "demo-http";
            }

            @Override
            public Set<String> supportedOperations() {
                return Set.of("recover_connection_pool");
            }

            @Override
            public ExecutionStepResult execute(
                    AuthorizedRunbookStep step,
                    io.sentinelops.executor.runbook.IdempotencyContext context) {
                sideEffects.incrementAndGet();
                dispatchStarted.countDown();
                try {
                    if (!releaseDispatch.await(2, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("timed out waiting to release adapter");
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("adapter interrupted", interrupted);
                }
                return ExecutionStepResult.succeeded(
                        "1.0.0", "request-hash", Map.of("changed", true));
            }
        };
    }

    private ExecutionLeaseHeartbeat noOpHeartbeat() {
        return (message, claim, attemptId) -> new ExecutionLeaseHeartbeat.ActiveLease() {
            @Override
            public void withCurrentTicket(java.util.function.Consumer<String> action) {
                action.accept(claim.ticket());
            }

            @Override
            public String ticketForResult() {
                return claim.ticket();
            }

            @Override
            public void close() {}
        };
    }

    private RedisExecutionStreamConsumer consumer(
            String consumerId, ExecutionMessageListener listener) {
        return new RedisExecutionStreamConsumer(
                redis,
                listener,
                STREAM,
                GROUP,
                consumerId,
                Duration.ofMillis(5),
                10);
    }
}
