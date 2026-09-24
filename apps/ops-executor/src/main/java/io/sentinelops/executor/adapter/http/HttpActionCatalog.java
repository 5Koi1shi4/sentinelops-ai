package io.sentinelops.executor.adapter.http;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class HttpActionCatalog {
    private final Map<String, Action> actions;

    public HttpActionCatalog(Map<String, Action> actions) {
        Objects.requireNonNull(actions, "actions");
        if (actions.isEmpty()) {
            throw new IllegalArgumentException("HTTP action catalog must not be empty");
        }
        var validated = new LinkedHashMap<String, Action>();
        actions.forEach((key, action) -> {
            if (key == null || !key.matches("[a-z][a-z0-9_]{1,63}")) {
                throw new IllegalArgumentException("Invalid HTTP action key");
            }
            validated.put(key, Objects.requireNonNull(action, "action"));
        });
        this.actions = Map.copyOf(validated);
    }

    public Map<String, Action> actions() {
        return actions;
    }

    public record Action(
            String targetAlias,
            URI uri,
            String method,
            String audience,
            String scope,
            Map<String, ParameterRule> parameters,
            Map<String, Object> bodyTemplate,
            boolean allowPrivateNetwork) {
        public Action {
            targetAlias = requireName(targetAlias, "targetAlias");
            Objects.requireNonNull(uri, "uri");
            if (!uri.isAbsolute() || uri.getHost() == null || uri.getRawUserInfo() != null
                    || uri.getRawFragment() != null || uri.getRawQuery() != null
                    || uri.getRawPath() == null || !uri.getRawPath().startsWith("/")
                    || uri.getRawPath().contains("..") || uri.getRawPath().contains("%")
                    || uri.getHost().contains("%")) {
                throw new IllegalArgumentException("HTTP action URI must be a fixed absolute endpoint");
            }
            String scheme = uri.getScheme();
            String host = uri.getHost();
            if ((!"https".equalsIgnoreCase(scheme) && !(allowPrivateNetwork && "http".equalsIgnoreCase(scheme)))
                    || (!allowPrivateNetwork && (isIpLiteral(host)
                    || host.equalsIgnoreCase("localhost") || host.endsWith(".local")))) {
                throw new IllegalArgumentException("HTTP action target is not allowlisted");
            }
            if (!Set.of("POST", "PUT", "PATCH").contains(method)) {
                throw new IllegalArgumentException("HTTP action method must be POST, PUT or PATCH");
            }
            audience = requireName(audience, "audience");
            scope = requireName(scope, "scope");
            parameters = Map.copyOf(Objects.requireNonNull(parameters, "parameters"));
            bodyTemplate = Map.copyOf(Objects.requireNonNull(bodyTemplate, "bodyTemplate"));
            for (String parameter : parameters.keySet()) {
                requireName(parameter, "parameter");
            }
            for (var entry : bodyTemplate.entrySet()) {
                requireName(entry.getKey(), "body field");
                Object template = entry.getValue();
                if (template instanceof String text && text.startsWith("${")) {
                    if (!text.endsWith("}")
                            || !parameters.containsKey(text.substring(2, text.length() - 1))) {
                        throw new IllegalArgumentException("Unknown body template parameter");
                    }
                } else if (!(template instanceof String || template instanceof Number || template instanceof Boolean)) {
                    throw new IllegalArgumentException("Body template values must be scalar");
                }
            }
            for (String parameter : parameters.keySet()) {
                if (bodyTemplate.values().stream().noneMatch(value ->
                        ("${" + parameter + "}").equals(value))) {
                    throw new IllegalArgumentException("Unused HTTP action parameter");
                }
            }
        }

        private static boolean isIpLiteral(String host) {
            return host.contains(":") || host.matches("[0-9.]+") || host.startsWith("[");
        }
    }

    public record ParameterRule(String type, Long minimum, Long maximum, Set<String> allowedValues) {
        public ParameterRule {
            if (!Set.of("integer", "boolean", "string").contains(type)) {
                throw new IllegalArgumentException("Unknown HTTP parameter type");
            }
            allowedValues = Set.copyOf(Objects.requireNonNull(allowedValues, "allowedValues"));
            if ("integer".equals(type) && (minimum == null || maximum == null || minimum > maximum)) {
                throw new IllegalArgumentException("Integer parameter needs bounds");
            }
            if (!"integer".equals(type) && (minimum != null || maximum != null)) {
                throw new IllegalArgumentException("Only integer parameters use bounds");
            }
            if ("string".equals(type) && allowedValues.isEmpty()) {
                throw new IllegalArgumentException("String parameter needs allowed values");
            }
        }

        public static ParameterRule integer(long minimum, long maximum) {
            return new ParameterRule("integer", minimum, maximum, Set.of());
        }

        public boolean accepts(Object value) {
            return switch (type) {
                case "integer" -> isIntegral(value)
                        && ((Number) value).longValue() >= minimum
                        && ((Number) value).longValue() <= maximum;
                case "boolean" -> value instanceof Boolean;
                case "string" -> value instanceof String text && allowedValues.contains(text);
                default -> false;
            };
        }

        private static boolean isIntegral(Object value) {
            return value instanceof Byte || value instanceof Short
                    || value instanceof Integer || value instanceof Long;
        }
    }

    private static String requireName(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }
}
