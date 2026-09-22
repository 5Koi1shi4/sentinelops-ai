package io.sentinelops.api.diagnosis.application.model;

@FunctionalInterface
@org.springframework.modulith.NamedInterface("evaluation")
public interface ModelGateway {
    ModelDiagnosisResult diagnose(ModelDiagnosisRequest request);
}
