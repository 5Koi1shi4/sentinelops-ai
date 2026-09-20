package io.sentinelops.api.diagnosis.application;

import io.sentinelops.api.diagnosis.domain.DiagnosisContext;
import io.sentinelops.api.diagnosis.domain.DiagnosisProposalDraft;

public interface DiagnosisEngine {

    DiagnosisProposalDraft diagnose(DiagnosisContext context);
}
