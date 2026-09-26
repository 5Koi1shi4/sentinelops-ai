package io.sentinelops.api.knowledge;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatCode;

import io.sentinelops.api.knowledge.application.RunbookDefinitionValidator;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

class RunbookDefinitionValidatorTest {

    private static final String RUNBOOK_KEY = "RB-DB-POOL-03";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final RunbookDefinitionValidator validator = new RunbookDefinitionValidator();

    @Test
    void acceptsTheV5DemoDefinition() {
        assertThatCode(() -> validator.validate(RUNBOOK_KEY, validDefinition()))
                .doesNotThrowAnyException();
    }

    @Test
    void acceptsOnlyTheFixedProductionHttpActionWithItsRealHealthProbe() {
        ObjectNode production = productionDefinition();
        assertThatCode(() -> validator.validate(RUNBOOK_KEY, production))
                .doesNotThrowAnyException();

        ObjectNode wrongOperation = productionDefinition();
        ((ObjectNode) ((ArrayNode) wrongOperation.get("steps")).get(0))
                .put("operation", "execute_sql");
        assertRejected(RUNBOOK_KEY, wrongOperation);

        ObjectNode demoProbe = productionDefinition();
        ((ObjectNode) demoProbe.get("verification")).put("probe", "demo_checkout_health");
        assertRejected(RUNBOOK_KEY, demoProbe);

        ObjectNode broadParameters = productionDefinition();
        replicaSchema(broadParameters).put("maximum", 100);
        assertRejected(RUNBOOK_KEY, broadParameters);
    }

    @Test
    void rejectsNullEmptyAndNonObjectDefinitions() {
        assertRejected(RUNBOOK_KEY, null);
        assertRejected(RUNBOOK_KEY, objectMapper.createObjectNode());
        assertRejected(RUNBOOK_KEY, objectMapper.createArrayNode());
        assertRejected(RUNBOOK_KEY, objectMapper.getNodeFactory().textNode("definition"));
    }

    @Test
    void requiresTheMethodKeyToMatchTheDefinitionKey() {
        assertRejected("RB-OTHER", validDefinition());

        ObjectNode definition = validDefinition();
        definition.put("runbookKey", "RB-OTHER");
        assertRejected(RUNBOOK_KEY, definition);
    }

    @Test
    void rejectsUnknownOrUnsafeRootValues() {
        ObjectNode wrongRisk = validDefinition();
        wrongRisk.put("risk", "R3");
        assertRejected(RUNBOOK_KEY, wrongRisk);

        ObjectNode unknownAdapter = validDefinition();
        unknownAdapter.put("adapterId", "shell");
        assertRejected(RUNBOOK_KEY, unknownAdapter);

        ObjectNode nonNullRollback = validDefinition();
        nonNullRollback.set("rollback", objectMapper.createObjectNode());
        assertRejected(RUNBOOK_KEY, nonNullRollback);

        ObjectNode missingRollback = validDefinition();
        missingRollback.remove("rollback");
        assertRejected(RUNBOOK_KEY, missingRollback);
    }

    @Test
    void rejectsUnexpectedFieldsAtEveryDefinitionLayer() {
        ObjectNode rootExtra = validDefinition();
        rootExtra.put("description", "untrusted extra");
        assertRejected(RUNBOOK_KEY, rootExtra);

        ObjectNode parametersExtra = validDefinition();
        ((ObjectNode) parametersExtra.get("parameters")).put("title", "extra");
        assertRejected(RUNBOOK_KEY, parametersExtra);

        ObjectNode propertiesExtra = validDefinition();
        ((ObjectNode) ((ObjectNode) propertiesExtra.get("parameters")).get("properties"))
                .put("unexpected", true);
        assertRejected(RUNBOOK_KEY, propertiesExtra);

        ObjectNode replicasExtra = validDefinition();
        ((ObjectNode) ((ObjectNode) ((ObjectNode) replicasExtra.get("parameters"))
                .get("properties")).get("replicas")).put("description", "extra");
        assertRejected(RUNBOOK_KEY, replicasExtra);

        ObjectNode stepExtra = validDefinition();
        ((ObjectNode) ((ArrayNode) stepExtra.get("steps")).get(0)).put("target", "demo-checkout");
        assertRejected(RUNBOOK_KEY, stepExtra);

        ObjectNode verificationExtra = validDefinition();
        ((ObjectNode) verificationExtra.get("verification")).put("timeoutSeconds", 30);
        assertRejected(RUNBOOK_KEY, verificationExtra);
    }

    @Test
    void acceptsOnlyTheRegisteredAdapterOperationAndProbe() {
        ObjectNode operation = validDefinition();
        ((ObjectNode) ((ArrayNode) operation.get("steps")).get(0))
                .put("operation", "execute_sql");
        assertRejected(RUNBOOK_KEY, operation);

        ObjectNode stepId = validDefinition();
        ((ObjectNode) ((ArrayNode) stepId.get("steps")).get(0))
                .put("stepId", "unsafe-step");
        assertRejected(RUNBOOK_KEY, stepId);

        ObjectNode probe = validDefinition();
        ((ObjectNode) probe.get("verification")).put("probe", "arbitrary-shell");
        assertRejected(RUNBOOK_KEY, probe);
    }

    @Test
    void requiresExactlyOneStepAndRejectsDuplicateStepIds() {
        ObjectNode noSteps = validDefinition();
        noSteps.set("steps", objectMapper.createArrayNode());
        assertRejected(RUNBOOK_KEY, noSteps);

        ObjectNode duplicateSteps = validDefinition();
        ArrayNode steps = (ArrayNode) duplicateSteps.get("steps");
        steps.add(steps.get(0).deepCopy());
        assertRejected(RUNBOOK_KEY, duplicateSteps);
    }

    @Test
    void enforcesTheExactParameterSchema() {
        ObjectNode wrongType = validDefinition();
        ((ObjectNode) wrongType.get("parameters")).put("type", "array");
        assertRejected(RUNBOOK_KEY, wrongType);

        ObjectNode missingRequired = validDefinition();
        ((ObjectNode) missingRequired.get("parameters")).remove("required");
        assertRejected(RUNBOOK_KEY, missingRequired);

        ObjectNode extraRequired = validDefinition();
        ((ObjectNode) extraRequired.get("parameters")).set(
                "required", objectMapper.createArrayNode().add("replicas").add("other"));
        assertRejected(RUNBOOK_KEY, extraRequired);

        ObjectNode additionalProperties = validDefinition();
        ((ObjectNode) additionalProperties.get("parameters")).put("additionalProperties", true);
        assertRejected(RUNBOOK_KEY, additionalProperties);

        ObjectNode wrongReplicaType = validDefinition();
        replicaSchema(wrongReplicaType).put("type", "string");
        assertRejected(RUNBOOK_KEY, wrongReplicaType);

        ObjectNode wrongMinimum = validDefinition();
        replicaSchema(wrongMinimum).put("minimum", 0);
        assertRejected(RUNBOOK_KEY, wrongMinimum);

        ObjectNode wrongMaximum = validDefinition();
        replicaSchema(wrongMaximum).put("maximum", 2);
        assertRejected(RUNBOOK_KEY, wrongMaximum);
    }

    @Test
    void enforcesVerificationBoundsAndThreshold() {
        for (int attempts : new int[] {0, 21}) {
            ObjectNode definition = validDefinition();
            ((ObjectNode) definition.get("verification")).put("attempts", attempts);
            assertRejected(RUNBOOK_KEY, definition);
        }
        for (int intervalSeconds : new int[] {0, 61}) {
            ObjectNode definition = validDefinition();
            ((ObjectNode) definition.get("verification"))
                    .put("intervalSeconds", intervalSeconds);
            assertRejected(RUNBOOK_KEY, definition);
        }

        ObjectNode wrongThreshold = validDefinition();
        ((ObjectNode) wrongThreshold.get("verification")).put("successThreshold", 0.99);
        assertRejected(RUNBOOK_KEY, wrongThreshold);

        ObjectNode nonIntegralAttempts = validDefinition();
        ((ObjectNode) nonIntegralAttempts.get("verification")).put("attempts", 1.5);
        assertRejected(RUNBOOK_KEY, nonIntegralAttempts);
    }

    @Test
    void rejectsOverlongValidationKeys() {
        String overlongKey = "R".repeat(129);
        ObjectNode definition = validDefinition();
        definition.put("runbookKey", overlongKey);
        assertRejected(overlongKey, definition);
    }

    private ObjectNode validDefinition() {
        ObjectNode definition = objectMapper.createObjectNode();
        definition.put("runbookKey", RUNBOOK_KEY);
        definition.put("risk", "R1");
        definition.put("adapterId", "demo-http");

        ObjectNode parameters = objectMapper.createObjectNode();
        parameters.put("type", "object");
        ObjectNode properties = objectMapper.createObjectNode();
        properties.set("replicas", replicaSchema());
        parameters.set("properties", properties);
        parameters.set("required", objectMapper.createArrayNode().add("replicas"));
        parameters.put("additionalProperties", false);
        definition.set("parameters", parameters);

        ObjectNode step = objectMapper.createObjectNode();
        step.put("stepId", "recover-one");
        step.put("operation", "recover_connection_pool");
        definition.set("steps", objectMapper.createArrayNode().add(step));

        ObjectNode verification = objectMapper.createObjectNode();
        verification.put("probe", "demo_checkout_health");
        verification.put("successThreshold", 1.0);
        verification.put("attempts", 6);
        verification.put("intervalSeconds", 5);
        definition.set("verification", verification);
        definition.putNull("rollback");
        return definition;
    }

    private ObjectNode productionDefinition() {
        ObjectNode definition = validDefinition();
        definition.put("adapterId", "production-http");
        replicaSchema(definition).put("maximum", 3);
        ObjectNode step = (ObjectNode) ((ArrayNode) definition.get("steps")).get(0);
        step.put("stepId", "restart-one");
        step.put("operation", "restart_service");
        ((ObjectNode) definition.get("verification"))
                .put("probe", "production_checkout_health");
        return definition;
    }

    private ObjectNode replicaSchema() {
        ObjectNode replica = objectMapper.createObjectNode();
        replica.put("type", "integer");
        replica.put("minimum", 1);
        replica.put("maximum", 1);
        return replica;
    }

    private ObjectNode replicaSchema(ObjectNode definition) {
        return (ObjectNode) ((ObjectNode) ((ObjectNode) definition.get("parameters"))
                .get("properties")).get("replicas");
    }

    private void assertRejected(String runbookKey, JsonNode definition) {
        assertThatThrownBy(() -> validator.validate(runbookKey, definition))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
