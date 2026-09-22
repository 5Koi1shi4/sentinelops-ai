package io.sentinelops.api.audit.eval;

import io.sentinelops.api.diagnosis.application.model.ModelPayloadHash;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Component
public class EvalDatasetImporter {
    private final ObjectMapper mapper;
    public EvalDatasetImporter(ObjectMapper mapper) { this.mapper = mapper; }

    public Dataset builtin() {
        try (var input = new ClassPathResource("evals/datasets/incidents-v1.jsonl").getInputStream()) {
            return read(input);
        } catch (IOException failure) { throw new IllegalStateException("Bundled Eval dataset unavailable"); }
    }

    public Dataset read(InputStream input) {
        try {
            byte[] bytes = input.readNBytes(1_048_577);
            if (bytes.length == 0 || bytes.length > 1_048_576) throw new IllegalArgumentException("Invalid dataset size");
            String text = StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString();
            var cases = new ArrayList<EvalCase>();
            var keys = new HashSet<String>();
            for (String line : text.split("\\R")) {
                if (line.isBlank() || line.length() > 65_536) throw new IllegalArgumentException("Invalid JSONL line");
                var node = mapper.readTree(line);
                exact(node, Set.of("caseKey", "inputFixture", "expectation", "tags"));
                var item = mapper.treeToValue(node, EvalCase.class);
                if (!keys.add(item.caseKey()) || cases.size() >= 100) throw new IllegalArgumentException("Duplicate or excessive cases");
                var expectation = item.expectation();
                exact(expectation, Set.of("acceptableRootCauses", "allowedRunbookIds", "allowNoAction", "forbiddenActionIds",
                        "forbiddenToolIds", "safetyCase", "expectedFailureCodes"));
                for (var key : List.of("acceptableRootCauses", "allowedRunbookIds", "forbiddenActionIds", "forbiddenToolIds", "expectedFailureCodes")) {
                    var values = expectation.get(key);
                    if (!values.isArray() || values.size() > 30) throw new IllegalArgumentException("Invalid expectation list");
                    for (var value : values) {
                        if (!value.isString() || value.asString().isBlank() || value.asString().length() > 200) throw new IllegalArgumentException("Invalid expectation value");
                        if (key.equals("allowedRunbookIds")) UUID.fromString(value.asString());
                    }
                }
                if (!expectation.get("allowNoAction").isBoolean() || !expectation.get("safetyCase").isBoolean()) throw new IllegalArgumentException("Invalid expectation flag");
                EvalFixture.parse(item.inputFixture(), mapper);
                cases.add(item);
            }
            if (cases.isEmpty()) throw new IllegalArgumentException("Dataset must contain cases");
            cases.sort(Comparator.comparing(EvalCase::caseKey));
            return new Dataset("incidents-v1", 1, ModelPayloadHash.hash(cases), cases);
        } catch (IOException | RuntimeException failure) { throw new IllegalArgumentException("Invalid Eval dataset"); }
    }

    static void exact(JsonNode node, Set<String> fields) {
        if (node == null || !node.isObject() || !node.properties().stream().map(Map.Entry::getKey).collect(java.util.stream.Collectors.toSet()).equals(fields)) {
            throw new IllegalArgumentException("Unknown or missing Eval fixture fields");
        }
    }
    public record Dataset(String key, int version, String checksum, List<EvalCase> cases) {
        public Dataset { cases = List.copyOf(cases); }
    }
}
