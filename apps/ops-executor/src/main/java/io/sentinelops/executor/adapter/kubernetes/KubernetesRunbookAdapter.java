package io.sentinelops.executor.adapter.kubernetes;

import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.base.PatchContext;
import io.fabric8.kubernetes.client.dsl.base.PatchType;
import io.sentinelops.executor.runbook.AuthorizedRunbookStep;
import io.sentinelops.executor.runbook.ExecutionStepResult;
import io.sentinelops.executor.runbook.IdempotencyContext;
import io.sentinelops.executor.runbook.RunbookAdapter;
import io.sentinelops.executor.runbook.UnsupportedRunbookStepException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import tools.jackson.databind.ObjectMapper;

public final class KubernetesRunbookAdapter implements RunbookAdapter {
    private static final String VERSION = "kubernetes-v1";
    private final KubernetesTargetCatalog catalog;
    private final KubernetesClient client;
    private final Clock clock;
    private final Runnable validateCredentials;
    private final ObjectMapper mapper = new ObjectMapper();

    public KubernetesRunbookAdapter(KubernetesTargetCatalog catalog, KubernetesClient client, Clock clock) {
        this(catalog, client, clock, () -> {});
    }

    public KubernetesRunbookAdapter(KubernetesTargetCatalog catalog, KubernetesClient client,
            Clock clock, Runnable validateCredentials) {
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.client = Objects.requireNonNull(client, "client");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.validateCredentials = Objects.requireNonNull(validateCredentials, "validateCredentials");
    }

    @Override public String adapterId() { return "kubernetes"; }
    @Override public Set<String> supportedOperations() {
        return Set.of("restart_deployment", "scale_deployment");
    }

    @Override
    public ExecutionStepResult execute(AuthorizedRunbookStep step, IdempotencyContext context) {
        return execute(step, context, () -> {});
    }

    @Override
    public ExecutionStepResult execute(AuthorizedRunbookStep step, IdempotencyContext context,
            Runnable beforeTransport) {
        var target = KubernetesActionPolicy.authorize(step, context, catalog);
        Objects.requireNonNull(beforeTransport, "beforeTransport");
        String connectedCluster = client.getConfiguration().getMasterUrl();
        if (connectedCluster == null || !trimSlash(connectedCluster).equals(target.cluster())) {
            throw new UnsupportedRunbookStepException("Kubernetes client is not bound to cataloged cluster");
        }
        validateCredentials.run();
        var resource = client.apps().deployments().inNamespace(target.namespace()).withName(target.name());
        Deployment current = resource.get();
        if (current == null || current.getMetadata() == null
                || current.getMetadata().getResourceVersion() == null
                || current.getMetadata().getResourceVersion().isBlank()) {
            throw new KubernetesTargetException("Deployment or resourceVersion is unavailable");
        }
        var patch = new ArrayList<Map<String, Object>>();
        patch.add(Map.of("op", "test", "path", "/metadata/resourceVersion",
                "value", current.getMetadata().getResourceVersion()));
        if ("scale_deployment".equals(step.operation())) {
            patch.add(Map.of("op", "add", "path", "/spec/replicas",
                    "value", step.parameters().get("replicas")));
        } else {
            if (current.getSpec() == null || current.getSpec().getTemplate() == null
                    || current.getSpec().getTemplate().getMetadata() == null) {
                throw new KubernetesTargetException("Deployment pod template metadata is unavailable");
            }
            if (current.getSpec().getTemplate().getMetadata().getAnnotations() == null) {
                patch.add(Map.of("op", "add", "path", "/spec/template/metadata/annotations",
                        "value", Map.of()));
            }
            patch.add(Map.of("op", "add",
                    "path", "/spec/template/metadata/annotations/sentinelops.io~1restarted-at",
                    "value", Instant.now(clock).toString()));
        }
        String json = mapper.writeValueAsString(patch);
        String requestHash = hash(VERSION + '|' + target.cluster() + '|' + target.namespace()
                + '|' + target.name() + '|' + context.key() + '|' + context.fencingToken() + '|' + json);
        var patchContext = new PatchContext.Builder()
                .withPatchType(PatchType.JSON)
                .withFieldManager("sentinelops-executor")
                .build();
        beforeTransport.run();
        Deployment updated = resource.patch(patchContext, json);
        if (updated == null) {
            throw new KubernetesTargetException("Deployment patch returned no resource");
        }
        return ExecutionStepResult.succeeded(VERSION, requestHash,
                Map.of("operation", step.operation(), "target", step.target()));
    }

    private static String trimSlash(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    private static String hash(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required", impossible);
        }
    }

    public static final class KubernetesTargetException extends RuntimeException {
        public KubernetesTargetException(String message) { super(message); }
    }
}
