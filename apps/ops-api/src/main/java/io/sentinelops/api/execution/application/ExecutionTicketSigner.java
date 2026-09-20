package io.sentinelops.api.execution.application;

import tools.jackson.databind.JsonNode;

public interface ExecutionTicketSigner {

    String sign(ExecutionTicketClaims claims);

    JsonNode publicJwks();
}
