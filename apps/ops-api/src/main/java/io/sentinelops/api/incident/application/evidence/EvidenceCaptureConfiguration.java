package io.sentinelops.api.incident.application.evidence;

import io.sentinelops.api.incident.adapter.out.persistence.EvidenceStore;
import io.sentinelops.api.shared.id.UuidV7Generator;
import java.util.List;
import org.springframework.context.annotation.*;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.ObjectMapper;

@Configuration(proxyBeanMethods=false)
class EvidenceCaptureConfiguration {
    @Bean EvidenceCaptureService evidenceCaptureService(List<EvidenceSource> sources, EvidenceStore store,
            ObjectMapper mapper,PlatformTransactionManager transactions,UuidV7Generator ids) {
        return new EvidenceCaptureService(sources,new DefaultEvidenceRedactor(),store,mapper,transactions,ids);
    }
}
