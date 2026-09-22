package io.sentinelops.api.knowledge.application;

import java.util.List;

public interface KnowledgeSearch {
    List<KnowledgeHit> search(KnowledgeQuery query, int limit);
}
