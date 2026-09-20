package io.sentinelops.api.execution.adapter.in.internal;

import io.sentinelops.api.execution.application.ExecutionTicketSigner;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

@RestController
@RequestMapping("/internal/v1/execution-keys")
public class JwksController {

    private final ExecutionTicketSigner signer;

    public JwksController(ExecutionTicketSigner signer) {
        this.signer = signer;
    }

    @GetMapping("/jwks.json")
    JsonNode jwks() {
        return signer.publicJwks();
    }
}
