package io.sentinelops.api.diagnosis.application.model;

import io.sentinelops.api.shared.problem.ApiProblemException;

/** Typed failure retains only bounded telemetry; never a provider exception or body. */
public final class ModelGatewayFailure extends ApiProblemException {
    private final String provider,modelName,promptVersion,corpusVersion,inputHash;
    private final int toolCalls;
    private final long latencyMs;
    public ModelGatewayFailure(ApiProblemException failure,String provider,String modelName,ModelDiagnosisRequest request,
                               int toolCalls,long latencyMs) {
        super(failure.status(),failure.errorCode(),failure.getMessage());
        this.provider=provider;this.modelName=modelName;this.promptVersion=request.promptVersion();
        this.corpusVersion=request.context().runbookCorpusVersion();this.inputHash=ModelPayloadHash.hash(request);
        this.toolCalls=toolCalls;this.latencyMs=latencyMs;
    }
    public String provider(){return provider;} public String modelName(){return modelName;}
    public String promptVersion(){return promptVersion;} public String corpusVersion(){return corpusVersion;}
    public String inputHash(){return inputHash;} public int toolCalls(){return toolCalls;} public long latencyMs(){return latencyMs;}
}
