package io.sentinelops.executor.adapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import io.sentinelops.executor.controlplane.ExecutorOAuth2TokenProvider;
import io.sentinelops.executor.runbook.AuthorizedRunbookStep;
import io.sentinelops.executor.runbook.IdempotencyContext;
import io.sentinelops.executor.runbook.UnsupportedRunbookStepException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class DemoHttpRunbookAdapterTest {

    private final ExecutorOAuth2TokenProvider tokens =
            mock(ExecutorOAuth2TokenProvider.class);

    private MockRestServiceServer server;
    private DemoHttpRunbookAdapter adapter;

    @BeforeEach
    void createAdapter() {
        var builder = RestClient.builder().baseUrl("https://configured-demo.test");
        server = MockRestServiceServer.bindTo(builder).build();
        adapter = new DemoHttpRunbookAdapter(
                builder.build(),
                tokens,
                "demo-checkout",
                "demo-service",
                "runbook:execute:checkout");
    }

    @Test
    void executesTheAllowlistedOperationAgainstOnlyTheConfiguredTarget() {
        UUID executionId = UUID.randomUUID();
        when(tokens.accessToken("demo-service", "runbook:execute:checkout"))
                .thenReturn("target-token");
        server.expect(requestTo(
                        "https://configured-demo.test/internal/runbooks/recover-connection-pool"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer target-token"))
                .andExpect(header("Idempotency-Key", executionId + ":recover-one"))
                .andExpect(header("X-SentinelOps-Fencing-Token", "7"))
                .andExpect(content().json("{\"replicas\":1}"))
                .andRespond(withSuccess(
                        "{\"changed\":true,\"fencingToken\":7}",
                        MediaType.APPLICATION_JSON));

        var result = adapter.execute(
                step(executionId, "recover_connection_pool", "demo-checkout", Map.of("replicas", 1)),
                new IdempotencyContext(executionId, "recover-one", 7));

        assertThat(result.succeeded()).isTrue();
        assertThat(result.adapterVersion()).isEqualTo("demo-http-v1");
        assertThat(result.requestHash()).isNotBlank();
        assertThat(result.sanitizedResult())
                .containsEntry("changed", true)
                .containsEntry("httpStatus", 200);
        verify(tokens).accessToken("demo-service", "runbook:execute:checkout");
        server.verify();
    }

    @Test
    void rejectsUnknownOperationTargetAndParametersBeforeAnyNetworkCall() {
        UUID executionId = UUID.randomUUID();
        var context = new IdempotencyContext(executionId, "recover-one", 7);
        var parametersWithExtraKey = new LinkedHashMap<String, Object>();
        parametersWithExtraKey.put("replicas", 1);
        parametersWithExtraKey.put("url", "https://attacker.invalid");

        assertThatThrownBy(() -> adapter.execute(
                        step(executionId, "shell", "demo-checkout", Map.of("replicas", 1)),
                        context))
                .isInstanceOf(UnsupportedRunbookStepException.class);
        assertThatThrownBy(() -> adapter.execute(
                        step(executionId, "recover_connection_pool", "other", Map.of("replicas", 1)),
                        context))
                .isInstanceOf(UnsupportedRunbookStepException.class);
        assertThatThrownBy(() -> adapter.execute(
                        step(
                                executionId,
                                "recover_connection_pool",
                                "demo-checkout",
                                parametersWithExtraKey),
                        context))
                .isInstanceOf(UnsupportedRunbookStepException.class);
        assertThatThrownBy(() -> adapter.execute(
                        step(
                                executionId,
                                "recover_connection_pool",
                                "demo-checkout",
                                Map.of("replicas", 2)),
                        context))
                .isInstanceOf(UnsupportedRunbookStepException.class);

        server.verify();
    }

    private AuthorizedRunbookStep step(
            UUID executionId,
            String operation,
            String target,
            Map<String, Object> parameters) {
        return new AuthorizedRunbookStep(
                executionId,
                UUID.randomUUID(),
                UUID.randomUUID(),
                "checksum-v1",
                "recover-one",
                operation,
                "demo-http",
                parameters,
                target,
                "R1",
                7);
    }
}
