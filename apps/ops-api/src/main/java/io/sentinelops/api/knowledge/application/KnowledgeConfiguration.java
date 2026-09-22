package io.sentinelops.api.knowledge.application;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class KnowledgeConfiguration {
    @Bean KnowledgeChunker knowledgeChunker() { return new KnowledgeChunker(); }

}
