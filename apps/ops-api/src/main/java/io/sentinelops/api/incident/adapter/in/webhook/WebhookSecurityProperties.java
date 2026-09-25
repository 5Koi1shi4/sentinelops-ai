package io.sentinelops.api.incident.adapter.in.webhook;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

/** Resolves configured source secrets once; only env references appear in configuration. */
@Component
public final class WebhookSecurityProperties {
    public static final int MAX_BODY_BYTES = 1024 * 1024;
    private static final String SOURCE_PATTERN = "[a-z][a-z0-9-]{0,63}";
    private static final String SECRET_REF_PATTERN = "env:[A-Z][A-Z0-9_]{0,127}";

    private final Map<String, byte[]> secrets;
    private final Map<String, RatePolicy> ratePolicies;

    public WebhookSecurityProperties(Environment environment) {
        var configured = environment.getProperty("sentinelops.webhook.source-refs", "");
        var resolved = new HashMap<String, byte[]>();
        if (!configured.isBlank()) {
            for (var entry : configured.split(",", -1)) {
                int separator = entry.indexOf('=');
                if (separator < 1) {
                    throw new IllegalStateException("Invalid webhook source configuration");
                }
                var source = entry.substring(0, separator).trim();
                if (!source.matches(SOURCE_PATTERN) || resolved.containsKey(source)) {
                    throw new IllegalStateException("Invalid or duplicate webhook source");
                }
                var reference = entry.substring(separator + 1).trim();
                if (!reference.matches(SECRET_REF_PATTERN)) {
                    throw new IllegalStateException("Webhook source requires an env secret reference");
                }
                var value = environment.getProperty(reference.substring(4));
                if (value == null || value.isBlank()) {
                    throw new IllegalStateException("Webhook source secret reference is unresolved");
                }
                var secret = value.getBytes(StandardCharsets.UTF_8);
                if (secret.length < 32 || secret.length > 512) {
                    throw new IllegalStateException("Webhook source secret length is invalid");
                }
                resolved.put(source, secret);
            }
        }
        if (resolved.isEmpty() && environment.acceptsProfiles(Profiles.of("production"))) {
            throw new IllegalStateException("Production requires a configured webhook source");
        }
        var policies = new HashMap<String, RatePolicy>();
        var loadLimit = environment.getProperty("SENTINELOPS_WEBHOOK_LOAD_TEST_LIMIT_PER_MINUTE");
        var loadBurst = environment.getProperty("SENTINELOPS_WEBHOOK_LOAD_TEST_BURST");
        if (loadLimit != null || loadBurst != null) {
            if (!environment.acceptsProfiles(Profiles.of("demo"))
                    || environment.acceptsProfiles(Profiles.of("production"))
                    || !resolved.containsKey("load-test")) {
                throw new IllegalStateException("Load-test webhook rate override requires a Demo load-test source");
            }
            if (loadLimit == null || loadBurst == null) {
                throw new IllegalStateException("Load-test webhook rate override is incomplete");
            }
            policies.put("load-test", new RatePolicy(
                    boundedInteger(loadLimit, 1, 10_000),
                    boundedInteger(loadBurst, 1, 200)));
        }
        secrets = Map.copyOf(resolved);
        ratePolicies = Map.copyOf(policies);
    }

    public Set<String> sources() {
        return secrets.keySet();
    }

    public byte[] secretFor(String source) {
        var secret = secrets.get(source);
        return secret == null ? null : secret.clone();
    }

    public RatePolicy ratePolicyFor(String source) {
        return ratePolicies.getOrDefault(source, new RatePolicy(60, 20));
    }

    private int boundedInteger(String value, int minimum, int maximum) {
        try {
            int parsed = Integer.parseInt(value);
            if (parsed >= minimum && parsed <= maximum) return parsed;
        } catch (NumberFormatException failure) {
            // Configuration fails closed below.
        }
        throw new IllegalStateException("Load-test webhook rate override is outside its allowed range");
    }

    public record RatePolicy(int limitPerMinute, int burst) {}
}
