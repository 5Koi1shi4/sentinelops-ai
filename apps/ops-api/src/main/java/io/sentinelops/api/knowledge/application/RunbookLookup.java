package io.sentinelops.api.knowledge.application;

import io.sentinelops.api.knowledge.domain.RunbookVersion;
import java.util.Optional;
import java.util.UUID;

public interface RunbookLookup {
    Optional<RunbookVersion> findVersion(UUID id);
    Optional<RunbookVersion> findPublished(String key, UUID serviceId);
    String publishedCorpusVersion(UUID serviceId);
}
