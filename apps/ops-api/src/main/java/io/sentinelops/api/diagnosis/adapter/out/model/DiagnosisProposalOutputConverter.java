package io.sentinelops.api.diagnosis.adapter.out.model;

import io.sentinelops.api.diagnosis.domain.DiagnosisProposalDraft;
import org.springframework.ai.converter.BeanOutputConverter;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** Spring AI 2.0.1 omits nullable record field types; preserve the established R0 contract. */
public final class DiagnosisProposalOutputConverter extends BeanOutputConverter<DiagnosisProposalDraft> {
    public DiagnosisProposalOutputConverter() { super(DiagnosisProposalDraft.class); }
    @Override protected String generateSchema() {
        var mapper=new ObjectMapper();
        var schema=(ObjectNode)mapper.readTree(super.generateSchema());
        var properties=(ObjectNode)schema.path("properties");
        for (String field:new String[]{"runbookVersionId","expectedVerification"}) {
            var alternatives=mapper.createArrayNode().add(properties.path(field).deepCopy()).add(mapper.createObjectNode().put("type","null"));
            properties.set(field,mapper.createObjectNode().set("anyOf",alternatives));
        }
        return mapper.writeValueAsString(schema);
    }
}
