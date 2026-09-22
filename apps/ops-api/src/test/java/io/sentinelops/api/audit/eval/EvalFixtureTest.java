package io.sentinelops.api.audit.eval;

import static org.assertj.core.api.Assertions.*;
import io.sentinelops.api.diagnosis.application.tool.EvidenceTools;
import io.sentinelops.api.diagnosis.application.tool.ToolContext;
import io.sentinelops.api.incident.application.evidence.EvidenceBudget;
import io.sentinelops.api.shared.problem.ApiProblemException;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class EvalFixtureTest {
    private final ObjectMapper mapper=new ObjectMapper();
    @Test void productionToolValidationUsesOnlyScopedFixtureAndRecordedSourceFailures() {
        var item=new EvalDatasetImporter(mapper).builtin().cases().stream().filter(value->value.caseKey().equals("source-timeout")).findFirst().orElseThrow();
        var fixture=EvalFixture.parse(item.inputFixture(),mapper);
        var context=fixture.context();
        var scope=new ToolContext(context.incidentId(),context.runId(),context.serviceId(),context.evidenceFrom(),context.evidenceTo());
        var tools=new EvidenceTools(fixture,new EvidenceBudget(200,128*1024,Duration.ofMinutes(15)));
        assertThatThrownBy(()->tools.queryLogs(mapper.readTree("{\"queryId\":\"service-logs\",\"parameters\":{}}"),scope))
                .isInstanceOfSatisfying(ApiProblemException.class, failure->assertThat(failure.errorCode()).isEqualTo("EVIDENCE_SOURCE_TIMEOUT"));
        var id=context.evidence().getFirst().id();
        assertThat(tools.getEvidence(mapper.createObjectNode().put("evidenceId",id.toString()),scope).evidenceId()).isEqualTo(id);
        assertThatThrownBy(()->tools.getEvidence(mapper.createObjectNode().put("evidenceId",UUID.randomUUID().toString()),scope))
                .isInstanceOf(ApiProblemException.class);
        assertThatThrownBy(()->tools.queryMetrics(mapper.readTree("{\"queryId\":\"service-health\",\"parameters\":{\"serviceId\":\"override\"}}"),scope))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->fixture.getEvidence(UUID.randomUUID(),context.runId(),context.serviceId(),id))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
