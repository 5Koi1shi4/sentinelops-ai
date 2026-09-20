package io.sentinelops.api.diagnosis.domain;

public final class UnsupportedRiskException extends InvalidProposalException {

    public UnsupportedRiskException() {
        super("RISK_LEVEL_UNSUPPORTED", "R3 actions cannot be proposed by automated diagnosis");
    }
}
