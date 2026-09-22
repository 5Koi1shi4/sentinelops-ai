package io.sentinelops.api.knowledge.application;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class KnowledgeConfigurationTest {
    final ApplicationContextRunner runner = new ApplicationContextRunner().withUserConfiguration(KnowledgeConfiguration.class);

    @Test void explicitDemoProfileProvidesDeterministicEmbedding() {
        runner.withPropertyValues("spring.profiles.active=demo").run(context ->
                assertThat(context.getBean(EmbeddingGateway.class)).isInstanceOf(DeterministicEmbeddingGateway.class));
    }

    @Test void productionNeverFallsBackEvenWhenDemoIsAlsoActive() {
        runner.withPropertyValues("spring.profiles.active=production,demo").run(context ->
                assertThat(context).doesNotHaveBean(EmbeddingGateway.class));
        runner.run(context -> assertThat(context).doesNotHaveBean(EmbeddingGateway.class));
    }
}
