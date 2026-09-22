package io.sentinelops.api.knowledge.application;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration(proxyBeanMethods = false)
class KnowledgeConfiguration {
    @Bean KnowledgeChunker knowledgeChunker() { return new KnowledgeChunker(); }

    @Bean
    @Profile("(test | core | demo) & !production")
    EmbeddingGateway deterministicEmbeddingGateway() { return new DeterministicEmbeddingGateway(); }
}
