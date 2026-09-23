package io.sentinelops.api.shared.audit;

import java.util.Map;
import java.util.UUID;

/** 仅允许标识、哈希、固定结果码和白名单元数据通过此端口。 */
public record AuditCommand(UUID serviceId, String actorType, String actorId,
        String action, String resourceType, String resourceId, String result,
        String beforeHash, String afterHash, String traceId, Map<String, ?> metadata) {}
