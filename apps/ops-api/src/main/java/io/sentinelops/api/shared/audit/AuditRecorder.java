package io.sentinelops.api.shared.audit;

import java.util.UUID;

public interface AuditRecorder {
    UUID record(AuditCommand command);
}
