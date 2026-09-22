package io.sentinelops.api.incident.application.evidence;

import java.time.Duration;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public record EvidenceQueryTemplate(String template, Map<String, EvidenceParameter> parameters,
                                   Set<String> allowedLabels, Duration minimumStep) {
    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([A-Za-z][A-Za-z0-9_-]*)}");

    public EvidenceQueryTemplate {
        Objects.requireNonNull(template, "template");
        parameters = Map.copyOf(parameters);
        allowedLabels = Set.copyOf(allowedLabels);
        Objects.requireNonNull(minimumStep, "minimumStep");
        if (template.isBlank() || template.length() > 4096 || parameters.size() > 16
                || allowedLabels.size() > 32 || minimumStep.compareTo(Duration.ofSeconds(1)) < 0
                || minimumStep.compareTo(EvidenceBudget.MAX_WINDOW) > 0
                || allowedLabels.stream().anyMatch(label -> !label.matches("[A-Za-z_][A-Za-z0-9_]{0,127}"))) {
            throw new IllegalArgumentException("invalid query template");
        }
        var names = new HashSet<String>();
        var matcher = PLACEHOLDER.matcher(template);
        while (matcher.find()) names.add(matcher.group(1));
        if (!names.equals(parameters.keySet()) || PLACEHOLDER.matcher(template).replaceAll("").contains("${")) {
            throw new IllegalArgumentException("template placeholders do not match parameter rules");
        }
    }

    public String expand(Map<String, String> values) {
        if (values == null || !values.keySet().equals(parameters.keySet())) {
            throw new IllegalArgumentException("query parameters do not match configured template");
        }
        var matcher = PLACEHOLDER.matcher(template);
        var result = new StringBuilder();
        while (matcher.find()) {
            String key = matcher.group(1);
            if (!parameters.get(key).accepts(values.get(key))) {
                throw new IllegalArgumentException("query parameter failed configured validation");
            }
            matcher.appendReplacement(result, Matcher.quoteReplacement(values.get(key)));
        }
        return matcher.appendTail(result).toString();
    }
}
