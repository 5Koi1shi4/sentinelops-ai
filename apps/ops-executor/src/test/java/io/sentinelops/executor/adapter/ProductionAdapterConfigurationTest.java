package io.sentinelops.executor.adapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

class ProductionAdapterConfigurationTest {
    @TempDir Path temporaryDirectory;
    private final ProductionAdapterConfiguration configuration = new ProductionAdapterConfiguration();
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void loadsFixedHttpActionFromMountedJson() throws Exception {
        Path file = temporaryDirectory.resolve("http.json");
        Files.writeString(file, """
                {"actions":{"restart_service":{
                  "targetAlias":"checkout",
                  "uri":"https://checkout.example.com/ops/restart",
                  "method":"POST",
                  "audience":"checkout-service",
                  "scope":"runbook:execute",
                  "parameters":{"replicas":{"type":"integer","minimum":1,"maximum":3,"allowedValues":[]}},
                  "bodyTemplate":{"replicas":"${replicas}"},
                  "allowPrivateNetwork":false
                }}}
                """);
        var catalog = configuration.httpActionCatalog(file.toString(), mapper);
        assertThat(catalog.actions()).containsOnlyKeys("restart_service");
        assertThat(catalog.actions().get("restart_service").uri().getHost())
                .isEqualTo("checkout.example.com");
    }

    @Test
    void loadsOnlyNamedDeploymentTargetsFromMountedJson() throws Exception {
        Path file = temporaryDirectory.resolve("kubernetes.json");
        Files.writeString(file, """
                {"targets":{"checkout":{
                  "cluster":"https://kubernetes.example.com",
                  "namespace":"production",
                  "kind":"Deployment",
                  "name":"checkout-api",
                  "minReplicas":1,
                  "maxReplicas":5,
                  "operations":["restart_deployment","scale_deployment"]
                }}}
                """);
        var catalog = configuration.kubernetesTargetCatalog(file.toString(), mapper);
        assertThat(catalog.targets().get("checkout").name()).isEqualTo("checkout-api");
    }

    @Test
    void invalidOrMissingCatalogFailsAtStartup() throws Exception {
        Path missing = temporaryDirectory.resolve("missing.json");
        assertThatThrownBy(() -> configuration.httpActionCatalog(missing.toString(), mapper))
                .isInstanceOf(IllegalStateException.class);
        Path file = temporaryDirectory.resolve("invalid.json");
        Files.writeString(file, """
                {"targets":{"checkout":{
                  "cluster":"https://kubernetes.example.com",
                  "namespace":"production",
                  "kind":"Pod",
                  "name":"checkout-api",
                  "minReplicas":1,
                  "maxReplicas":5,
                  "operations":["exec_in_pod"]
                }}}
                """);
        assertThatThrownBy(() -> configuration.kubernetesTargetCatalog(file.toString(), mapper))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    void rejectsUnknownCatalogFieldsInsteadOfSilentlyIgnoringThem() throws Exception {
        Path file = temporaryDirectory.resolve("unknown-field.json");
        Files.writeString(file, """
                {"actions":{"restart_service":{
                  "targetAlias":"checkout",
                  "uri":"https://checkout.example.com/ops/restart",
                  "method":"POST",
                  "audience":"checkout-service",
                  "scope":"runbook:execute",
                  "parameters":{},
                  "bodyTemplate":{},
                  "allowPrivateNetwork":false,
                  "followRedirects":true
                }}}
                """);
        assertThatThrownBy(() -> configuration.httpActionCatalog(file.toString(), mapper))
                .isInstanceOf(RuntimeException.class);
    }
}
