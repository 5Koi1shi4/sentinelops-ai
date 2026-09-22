package io.sentinelops.api.diagnosis.adapter.out.model;

import io.sentinelops.api.diagnosis.application.model.*;
import io.sentinelops.api.shared.problem.ApiProblemException;
import org.springframework.http.HttpStatus;

public final class UnavailableModelGateway implements ModelGateway {
    @Override public ModelDiagnosisResult diagnose(ModelDiagnosisRequest request) {
        throw new ApiProblemException(HttpStatus.SERVICE_UNAVAILABLE,"AI_PROVIDER_UNAVAILABLE","AI diagnosis is disabled; manual incident operations remain available.");
    }
}
