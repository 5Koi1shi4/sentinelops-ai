package io.sentinelops.executor.adapter;

import io.fabric8.kubernetes.client.Config;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import io.sentinelops.executor.adapter.http.HttpActionCatalog;
import io.sentinelops.executor.adapter.http.ProductionHttpRunbookAdapter;
import io.sentinelops.executor.adapter.kubernetes.KubernetesRunbookAdapter;
import io.sentinelops.executor.adapter.kubernetes.KubernetesTargetCatalog;
import io.sentinelops.executor.controlplane.ExecutorOAuth2TokenProvider;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Configuration(proxyBeanMethods = false)
public class ProductionAdapterConfiguration {
    private static final Path SERVICE_ACCOUNT =
            Path.of("/var/run/secrets/kubernetes.io/serviceaccount");

    @Bean
    @ConditionalOnProperty(prefix = "sentinelops.targets.http", name = "catalog-file")
    HttpActionCatalog httpActionCatalog(
            @Value("${sentinelops.targets.http.catalog-file}") String path, ObjectMapper mapper) {
        try {
            String json = Files.readString(Path.of(path));
            validateHttpCatalog(mapper.readTree(json));
            var document = mapper.readValue(json, HttpCatalogDocument.class);
            return new HttpActionCatalog(document.actions());
        } catch (IOException failure) {
            throw new IllegalStateException("HTTP action catalog is unavailable", failure);
        }
    }

    @Bean
    @ConditionalOnProperty(prefix = "sentinelops.targets.http", name = "catalog-file")
    ProductionHttpRunbookAdapter productionHttpRunbookAdapter(HttpActionCatalog catalog,
            ExecutorOAuth2TokenProvider tokens, ObjectMapper mapper) {
        return new ProductionHttpRunbookAdapter(catalog, tokens, mapper);
    }

    @Bean
    @ConditionalOnProperty(prefix = "sentinelops.targets.kubernetes", name = "catalog-file")
    KubernetesTargetCatalog kubernetesTargetCatalog(
            @Value("${sentinelops.targets.kubernetes.catalog-file}") String path, ObjectMapper mapper) {
        try {
            String json = Files.readString(Path.of(path));
            validateKubernetesCatalog(mapper.readTree(json));
            var document = mapper.readValue(json, KubernetesCatalogDocument.class);
            return new KubernetesTargetCatalog(document.targets());
        } catch (IOException failure) {
            throw new IllegalStateException("Kubernetes target catalog is unavailable", failure);
        }
    }

    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(prefix = "sentinelops.targets.kubernetes", name = "catalog-file")
    KubernetesClient runbookKubernetesClient(KubernetesTargetCatalog catalog) {
        Set<String> clusters = catalog.targets().values().stream()
                .map(KubernetesTargetCatalog.Target::cluster)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        if (clusters.size() != 1) {
            throw new IllegalStateException("One Executor identity may address only one Kubernetes cluster");
        }
        Path token = SERVICE_ACCOUNT.resolve("token");
        Path certificate = SERVICE_ACCOUNT.resolve("ca.crt");
        if (!Files.isRegularFile(token) || !Files.isReadable(token)
                || !Files.isRegularFile(certificate) || !Files.isReadable(certificate)) {
            throw new IllegalStateException("Kubernetes service account credentials are not mounted");
        }
        serviceAccountToken();
        var config = Config.empty();
        config.setMasterUrl(clusters.iterator().next());
        config.setCaCertFile(certificate.toString());
        config.setOauthTokenProvider(ProductionAdapterConfiguration::serviceAccountToken);
        config.setTrustCerts(false);
        config.setDisableHostnameVerification(false);
        config.setConnectionTimeout(2_000);
        config.setRequestTimeout(5_000);
        config.setRequestRetryBackoffLimit(0);
        return new KubernetesClientBuilder().withConfig(config).build();
    }

    @Bean
    @ConditionalOnProperty(prefix = "sentinelops.targets.kubernetes", name = "catalog-file")
    KubernetesRunbookAdapter kubernetesRunbookAdapter(
            KubernetesTargetCatalog catalog, KubernetesClient client) {
        return new KubernetesRunbookAdapter(catalog, client, Clock.systemUTC(), () -> {
            serviceAccountToken();
        });
    }

    private static String serviceAccountToken() {
        try {
            String value = Files.readString(SERVICE_ACCOUNT.resolve("token")).trim();
            if (value.isBlank()) {
                throw new IllegalStateException("Kubernetes service account token is empty");
            }
            return value;
        } catch (IOException failure) {
            throw new IllegalStateException("Kubernetes service account token is unavailable", failure);
        }
    }

    private static void validateHttpCatalog(JsonNode root) {
        exact(root, Set.of("actions"), "HTTP catalog");
        JsonNode actions = root.get("actions");
        if (actions == null || !actions.isObject()) {
            throw new IllegalArgumentException("HTTP actions must be an object");
        }
        actions.properties().forEach(action -> {
            JsonNode value = action.getValue();
            exact(value, Set.of("targetAlias", "uri", "method", "audience", "scope",
                    "parameters", "bodyTemplate", "allowPrivateNetwork"), "HTTP action");
            if (!value.get("allowPrivateNetwork").isBoolean()) {
                throw new IllegalArgumentException("HTTP allowPrivateNetwork must be boolean");
            }
            JsonNode parameters = value.get("parameters");
            if (!parameters.isObject() || !value.get("bodyTemplate").isObject()) {
                throw new IllegalArgumentException("HTTP parameters and bodyTemplate must be objects");
            }
            parameters.properties().forEach(parameter -> exact(parameter.getValue(),
                    Set.of("type", "minimum", "maximum", "allowedValues"), "HTTP parameter"));
        });
    }

    private static void validateKubernetesCatalog(JsonNode root) {
        exact(root, Set.of("targets"), "Kubernetes catalog");
        JsonNode targets = root.get("targets");
        if (targets == null || !targets.isObject()) {
            throw new IllegalArgumentException("Kubernetes targets must be an object");
        }
        targets.properties().forEach(target -> exact(target.getValue(), Set.of(
                "cluster", "namespace", "kind", "name", "minReplicas", "maxReplicas",
                "operations"), "Kubernetes target"));
    }

    private static void exact(JsonNode node, Set<String> expected, String field) {
        if (node == null || !node.isObject()) {
            throw new IllegalArgumentException(field + " must be an object");
        }
        var actual = new HashSet<String>();
        node.properties().forEach(entry -> actual.add(entry.getKey()));
        if (!actual.equals(expected)) {
            throw new IllegalArgumentException(field + " contains unknown or missing fields");
        }
    }

    private record HttpCatalogDocument(Map<String, HttpActionCatalog.Action> actions) {}
    private record KubernetesCatalogDocument(Map<String, KubernetesTargetCatalog.Target> targets) {}
}
