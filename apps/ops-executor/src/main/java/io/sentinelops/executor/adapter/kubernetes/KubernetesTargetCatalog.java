package io.sentinelops.executor.adapter.kubernetes;

import java.net.URI;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class KubernetesTargetCatalog {
    private final Map<String, Target> targets;

    public KubernetesTargetCatalog(Map<String, Target> targets) {
        Objects.requireNonNull(targets, "targets");
        if (targets.isEmpty()) {
            throw new IllegalArgumentException("Kubernetes target catalog must not be empty");
        }
        targets.forEach((alias, target) -> {
            requireDnsName(alias, "target alias");
            Objects.requireNonNull(target, "target");
        });
        this.targets = Map.copyOf(targets);
    }

    public Map<String, Target> targets() { return targets; }

    public record Target(String cluster, String namespace, String kind, String name,
            int minReplicas, int maxReplicas, Set<String> operations) {
        public Target {
            URI uri = URI.create(Objects.requireNonNull(cluster, "cluster"));
            if (uri.getHost() == null || uri.getRawUserInfo() != null || uri.getRawQuery() != null
                    || uri.getRawFragment() != null
                    || !(uri.getRawPath() == null || uri.getRawPath().isEmpty()
                    || "/".equals(uri.getRawPath()))
                    || !"https".equals(uri.getScheme())) {
                throw new IllegalArgumentException("Kubernetes cluster endpoint must be HTTPS");
            }
            cluster = stripTrailingSlash(uri.toString());
            namespace = requireDnsName(namespace, "namespace");
            name = requireDnsName(name, "deployment name");
            if (!"Deployment".equals(kind)) {
                throw new IllegalArgumentException("Only Deployment targets are supported");
            }
            if (minReplicas < 0 || maxReplicas < minReplicas) {
                throw new IllegalArgumentException("Invalid deployment replica bounds");
            }
            operations = Set.copyOf(Objects.requireNonNull(operations, "operations"));
            if (operations.isEmpty() || !Set.of("restart_deployment", "scale_deployment").containsAll(operations)) {
                throw new IllegalArgumentException("Kubernetes operation is not supported");
            }
        }
    }

    private static String stripTrailingSlash(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    private static String requireDnsName(String value, String field) {
        if (value == null || !value.matches("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?")) {
            throw new IllegalArgumentException("Invalid Kubernetes " + field);
        }
        return value;
    }
}
