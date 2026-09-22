package io.sentinelops.api.knowledge.application;

import java.util.List;

/**
 * Port for converting text into fixed-width vectors used by knowledge search.
 *
 * <p>The provider identity is part of the persisted chunk contract. Implementations that do not
 * have a provider-specific identity must retain the explicit fixed-width default instead of
 * returning an empty model name.
 */
@FunctionalInterface
public interface EmbeddingGateway {

    int DIMENSION = 1_536;
    String DEFAULT_MODEL_ID = "unspecified-1536";

    List<float[]> embed(List<String> texts);

    default String modelId() {
        return DEFAULT_MODEL_ID;
    }
}
