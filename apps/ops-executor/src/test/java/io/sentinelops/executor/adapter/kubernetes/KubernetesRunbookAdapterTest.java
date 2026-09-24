package io.sentinelops.executor.adapter.kubernetes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.fabric8.kubernetes.api.model.apps.DeploymentBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient;
import io.fabric8.kubernetes.client.server.mock.KubernetesMockServer;
import io.sentinelops.executor.adapter.AdapterContractTest;
import io.sentinelops.executor.runbook.AuthorizedRunbookStep;
import io.sentinelops.executor.runbook.IdempotencyContext;
import io.sentinelops.executor.runbook.RunbookAdapter;
import io.sentinelops.executor.runbook.UnsupportedRunbookStepException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

@EnableKubernetesMockClient
class KubernetesRunbookAdapterTest implements AdapterContractTest {
    KubernetesMockServer server;
    KubernetesClient client;
    private KubernetesRunbookAdapter adapter;
    private UUID executionId;

    @BeforeEach
    void setup() {
        executionId = UUID.randomUUID();
        var catalog = new KubernetesTargetCatalog(Map.of("checkout", new KubernetesTargetCatalog.Target(
                client.getConfiguration().getMasterUrl(), "production", "Deployment", "checkout-api",
                1, 5, Set.of("restart_deployment", "scale_deployment"))));
        adapter = new KubernetesRunbookAdapter(catalog, client,
                Clock.fixed(Instant.parse("2026-09-24T00:00:00Z"), ZoneOffset.UTC));
    }

    @Override public RunbookAdapter adapter() { return adapter; }
    @Override public String allowedOperation() { return "scale_deployment"; }
    @Override public String allowedTarget() { return "checkout"; }
    @Override public Map<String, Object> allowedParameters() { return Map.of("replicas", 2); }

    @Test
    void scalePatchesOnlyApprovedDeploymentWithResourceVersionTest() throws Exception {
        expectCurrentDeployment();
        expectPatch();
        var marker = new AtomicInteger();
        var result = adapter.execute(step("scale_deployment", Map.of("replicas", 2)),
                context(), marker::incrementAndGet);
        assertThat(result.succeeded()).isTrue();
        assertThat(marker).hasValue(1);
        var patch = server.getLastRequest();
        assertThat(patch.getMethod()).isEqualTo("PATCH");
        assertThat(patch.getPath()).contains("/namespaces/production/deployments/checkout-api");
        String body = patch.getBody().readUtf8();
        assertThat(body)
                .contains("/metadata/resourceVersion", "/spec/replicas")
                .doesNotContain("/metadata/name", "/spec/template/spec", "delete", "exec");
        var operations = new ObjectMapper().readTree(body);
        assertThat(operations.size()).isEqualTo(2);
        assertThat(operations.get(0).get("op").stringValue()).isEqualTo("test");
        assertThat(operations.get(0).get("value").stringValue()).isEqualTo("42");
        assertThat(operations.get(1).get("path").stringValue()).isEqualTo("/spec/replicas");
        assertThat(operations.get(1).get("value").intValue()).isEqualTo(2);
    }

    @Test
    void restartPatchesOnlyTimestampAnnotation() throws Exception {
        expectCurrentDeployment();
        expectPatch();
        adapter.execute(step("restart_deployment", Map.of()), context());
        assertThat(server.getLastRequest().getBody().readUtf8())
                .contains("sentinelops.io~1restarted-at", "2026-09-24T00:00:00Z")
                .doesNotContain("/spec/replicas", "/spec/template/spec");
    }

    @Test
    void refusesDangerousOperationsTargetsAndReplicaBoundsBeforeTransport() throws Exception {
        for (String operation : Set.of("delete_deployment", "exec_in_pod", "patch_resource")) {
            assertThatThrownBy(() -> adapter.execute(step(operation, Map.of()), context(),
                    () -> { throw new AssertionError("Rejected operation reached transport"); }))
                    .isInstanceOf(UnsupportedRunbookStepException.class);
        }
        assertThatThrownBy(() -> adapter.execute(step("scale_deployment", Map.of("replicas", 6)),
                context(), () -> { throw new AssertionError("Out-of-bounds scale reached transport"); }))
                .isInstanceOf(UnsupportedRunbookStepException.class);
        assertThatThrownBy(() -> adapter.execute(step("scale_deployment",
                Map.of("replicas", 2, "name", "other-deployment")), context(),
                () -> { throw new AssertionError("Model-supplied name reached transport"); }))
                .isInstanceOf(UnsupportedRunbookStepException.class);
        assertThat(server.getLastRequest()).isNull();
    }

    @Test
    void catalogRejectsHttpClusterEvenForLoopback() {
        assertThatThrownBy(() -> new KubernetesTargetCatalog(Map.of("checkout",
                new KubernetesTargetCatalog.Target("http://127.0.0.1:8080", "production",
                        "Deployment", "checkout-api", 1, 5, Set.of("scale_deployment")))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private void expectCurrentDeployment() {
        var current = new DeploymentBuilder().withNewMetadata().withName("checkout-api")
                .withNamespace("production").withResourceVersion("42").endMetadata()
                .withNewSpec().withReplicas(3).withNewTemplate().withNewMetadata()
                .endMetadata().endTemplate().endSpec().build();
        server.expect().get().withPath("/apis/apps/v1/namespaces/production/deployments/checkout-api")
                .andReturn(200, current).always();
    }

    private void expectPatch() {
        var changed = new DeploymentBuilder().withNewMetadata().withName("checkout-api")
                .withNamespace("production").withResourceVersion("43").endMetadata().build();
        server.expect().patch().withPath("/apis/apps/v1/namespaces/production/deployments/checkout-api?fieldManager=sentinelops-executor")
                .andReturn(200, changed).once();
    }

    private IdempotencyContext context() { return new IdempotencyContext(executionId, "step-one", 19); }
    private AuthorizedRunbookStep step(String operation, Map<String, Object> parameters) {
        return new AuthorizedRunbookStep(executionId, UUID.randomUUID(), UUID.randomUUID(),
                "checksum", "step-one", operation, "kubernetes", parameters, "checkout", "R1", 19);
    }
}
