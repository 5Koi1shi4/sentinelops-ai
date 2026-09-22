package io.sentinelops.api.knowledge.application;

import java.util.UUID;

public record KnowledgeHit(String source, String runbookKey, UUID runbookVersionId, int versionNumber,
        UUID chunkId, int chunkNo, String content, Long lexicalRank, Long vectorRank,
        Double lexicalScore, Double vectorScore, double fusedScore) {}
