package io.sentinelops.api.knowledge.application;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.HashSet;
import java.util.Set;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * Validates the small executable Runbook definition allowlist used by the V5 demo seed.
 *
 * <p>The definition is kept deliberately narrower than a general JSON Schema implementation so
 * that published versions cannot introduce an unregistered adapter, operation, probe, or rollback
 * behavior by changing JSON alone.
 */
@Component
public final class RunbookDefinitionValidator {

    private static final int MAX_KEY_LENGTH = 128;
    private static final Set<String> ROOT_FIELDS = Set.of(
            "runbookKey", "risk", "adapterId", "parameters", "steps", "verification", "rollback");
    private static final Set<String> PARAMETERS_FIELDS = Set.of(
            "type", "properties", "required", "additionalProperties");
    private static final Set<String> PROPERTIES_FIELDS = Set.of("replicas");
    private static final Set<String> REPLICAS_FIELDS = Set.of("type", "minimum", "maximum");
    private static final Set<String> STEP_FIELDS = Set.of("stepId", "operation");
    private static final Set<String> VERIFICATION_FIELDS = Set.of(
            "probe", "successThreshold", "attempts", "intervalSeconds");

    public void validate(String runbookKey, JsonNode definition) {
        String expectedKey = requireBoundedText(runbookKey, "runbookKey");
        JsonNode root = requireObject(definition, "definition");
        requireExactFields(root, ROOT_FIELDS, "definition");

        String definitionKey = requireText(root, "runbookKey");
        if (!expectedKey.equals(definitionKey)) {
            throw invalid("runbookKey does not match the requested Runbook");
        }
        requireTextEquals(root, "risk", "R1");
        requireTextEquals(root, "adapterId", "demo-http");

        validateParameters(root.get("parameters"));
        validateSteps(root.get("steps"));
        validateVerification(root.get("verification"));

        JsonNode rollback = root.get("rollback");
        if (rollback == null || !rollback.isNull()) {
            throw invalid("rollback must be explicitly null");
        }
    }

    private void validateParameters(JsonNode parameters) {
        JsonNode schema = requireObject(parameters, "parameters");
        requireExactFields(schema, PARAMETERS_FIELDS, "parameters");
        requireTextEquals(schema, "type", "object");

        JsonNode properties = requireObject(schema.get("properties"), "parameters.properties");
        requireExactFields(properties, PROPERTIES_FIELDS, "parameters.properties");
        JsonNode replicas = requireObject(properties.get("replicas"), "parameters.properties.replicas");
        requireExactFields(replicas, REPLICAS_FIELDS, "parameters.properties.replicas");
        requireTextEquals(replicas, "type", "integer");
        requireIntegerEquals(replicas, "minimum", BigInteger.ONE);
        requireIntegerEquals(replicas, "maximum", BigInteger.ONE);

        JsonNode required = schema.get("required");
        if (required == null
                || !required.isArray()
                || required.size() != 1
                || !required.get(0).isTextual()
                || !"replicas".equals(required.get(0).stringValue())) {
            throw invalid("parameters.required must contain only replicas");
        }

        JsonNode additionalProperties = schema.get("additionalProperties");
        if (additionalProperties == null
                || !additionalProperties.isBoolean()
                || additionalProperties.booleanValue()) {
            throw invalid("parameters.additionalProperties must be false");
        }
    }

    private void validateSteps(JsonNode steps) {
        if (steps == null || !steps.isArray() || steps.size() != 1) {
            throw invalid("steps must contain exactly one step");
        }
        JsonNode step = requireObject(steps.get(0), "steps[0]");
        requireExactFields(step, STEP_FIELDS, "steps[0]");
        requireTextEquals(step, "stepId", "recover-one");
        requireTextEquals(step, "operation", "recover_connection_pool");
    }

    private void validateVerification(JsonNode verification) {
        JsonNode probe = requireObject(verification, "verification");
        requireExactFields(probe, VERIFICATION_FIELDS, "verification");
        requireTextEquals(probe, "probe", "demo_checkout_health");
        requireNumberEquals(probe, "successThreshold", BigDecimal.ONE);
        requireIntegerInRange(probe, "attempts", 1, 20);
        requireIntegerInRange(probe, "intervalSeconds", 1, 60);
    }

    private JsonNode requireObject(JsonNode node, String field) {
        if (node == null || !node.isObject()) {
            throw invalid(field + " must be an object");
        }
        return node;
    }

    private void requireExactFields(JsonNode object, Set<String> expected, String field) {
        Set<String> actual = new HashSet<>();
        object.properties().forEach(entry -> actual.add(entry.getKey()));
        if (!actual.equals(expected)) {
            throw invalid(field + " contains unsupported or missing fields");
        }
    }

    private String requireText(JsonNode object, String field) {
        JsonNode value = object.get(field);
        if (value == null || !value.isTextual()) {
            throw invalid(field + " must be a string");
        }
        return requireBoundedText(value.stringValue(), field);
    }

    private String requireBoundedText(String value, String field) {
        if (value == null || value.isBlank() || value.length() > MAX_KEY_LENGTH) {
            throw invalid(field + " must contain between 1 and " + MAX_KEY_LENGTH + " characters");
        }
        return value;
    }

    private void requireTextEquals(JsonNode object, String field, String expected) {
        if (!expected.equals(requireText(object, field))) {
            throw invalid(field + " is not an allowed value");
        }
    }

    private void requireIntegerEquals(JsonNode object, String field, BigInteger expected) {
        JsonNode value = object.get(field);
        if (value == null || !value.isIntegralNumber()
                || !expected.equals(value.bigIntegerValue())) {
            throw invalid(field + " must be the required integer");
        }
    }

    private void requireIntegerInRange(JsonNode object, String field, int minimum, int maximum) {
        JsonNode value = object.get(field);
        if (value == null || !value.isIntegralNumber()) {
            throw invalid(field + " must be an integer");
        }
        BigInteger integer = value.bigIntegerValue();
        if (integer.compareTo(BigInteger.valueOf(minimum)) < 0
                || integer.compareTo(BigInteger.valueOf(maximum)) > 0) {
            throw invalid(field + " is outside the allowed range");
        }
    }

    private void requireNumberEquals(JsonNode object, String field, BigDecimal expected) {
        JsonNode value = object.get(field);
        if (value == null || !value.isNumber()
                || value.decimalValue().compareTo(expected) != 0) {
            throw invalid(field + " must equal the required number");
        }
    }

    private IllegalArgumentException invalid(String detail) {
        return new IllegalArgumentException("Invalid Runbook definition: " + detail);
    }
}
