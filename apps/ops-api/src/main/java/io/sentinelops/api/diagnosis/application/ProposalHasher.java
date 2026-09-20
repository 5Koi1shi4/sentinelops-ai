package io.sentinelops.api.diagnosis.application;

import io.sentinelops.api.diagnosis.domain.ValidatedDiagnosisProposal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Component
public class ProposalHasher {

    private final ObjectMapper objectMapper;

    public ProposalHasher(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public String hash(ValidatedDiagnosisProposal proposal) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            var canonical = canonicalize(objectMapper.valueToTree(proposal));
            byte[] hash = digest.digest(
                    objectMapper.writeValueAsString(canonical).getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required by the Java runtime", impossible);
        }
    }

    private JsonNode canonicalize(JsonNode node) {
        if (node.isObject()) {
            var canonical = objectMapper.createObjectNode();
            node.properties().stream()
                    .sorted(java.util.Map.Entry.comparingByKey())
                    .forEach(entry -> canonical.set(entry.getKey(), canonicalize(entry.getValue())));
            return canonical;
        }
        if (node.isArray()) {
            var canonical = objectMapper.createArrayNode();
            node.forEach(element -> canonical.add(canonicalize(element)));
            return canonical;
        }
        return node.deepCopy();
    }
}
