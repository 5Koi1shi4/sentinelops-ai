package io.sentinelops.api.diagnosis.application.model;

@FunctionalInterface
public interface ModelGateway {
    ModelDiagnosisResult diagnose(ModelDiagnosisRequest request);
}
