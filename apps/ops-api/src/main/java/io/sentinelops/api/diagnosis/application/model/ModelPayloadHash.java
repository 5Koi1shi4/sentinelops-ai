package io.sentinelops.api.diagnosis.application.model;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.TreeMap;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

public final class ModelPayloadHash {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private ModelPayloadHash() {}
    public static String hash(Object value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(MAPPER.writeValueAsBytes(canonical(MAPPER.valueToTree(value)))));
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private static JsonNode canonical(JsonNode node) {
        if (node.isObject()) {
            var fields = new TreeMap<String, JsonNode>();
            node.properties().forEach(entry -> fields.put(entry.getKey(), canonical(entry.getValue())));
            var result = MAPPER.createObjectNode();
            fields.forEach(result::set);
            return result;
        }
        if (node.isArray()) {
            var result = MAPPER.createArrayNode();
            node.forEach(item -> result.add(canonical(item)));
            return result;
        }
        return node;
    }
}
