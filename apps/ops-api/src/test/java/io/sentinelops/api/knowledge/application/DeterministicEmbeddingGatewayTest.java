package io.sentinelops.api.knowledge.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

class DeterministicEmbeddingGatewayTest {

    private final DeterministicEmbeddingGateway gateway = new DeterministicEmbeddingGateway();

    @Test
    void defaultGatewayModelIdStatesTheFixedDimension() {
        EmbeddingGateway unspecified = texts -> List.of();

        assertThat(unspecified.modelId()).isEqualTo("unspecified-1536");
        assertThat(EmbeddingGateway.DIMENSION).isEqualTo(1_536);
    }

    @Test
    void returnsTheFixedDimensionAndStableNormalizedFiniteVectors() {
        List<float[]> first = gateway.embed(List.of("connection pool timeout", "database health"));
        List<float[]> second = gateway.embed(List.of("connection pool timeout", "database health"));

        assertThat(first).hasSize(2);
        assertThat(first.get(0)).hasSize(EmbeddingGateway.DIMENSION);
        assertThat(first.get(1)).hasSize(EmbeddingGateway.DIMENSION);
        assertThat(gateway.modelId()).isEqualTo("deterministic-sha256-v1");
        assertThat(first.get(0)).containsExactly(second.get(0));
        assertThat(first.get(1)).containsExactly(second.get(1));

        for (float[] vector : first) {
            double squaredNorm = 0.0;
            for (float value : vector) {
                assertThat(Float.isFinite(value)).isTrue();
                squaredNorm += value * value;
            }
            assertThat(squaredNorm).isGreaterThan(0.0);
            assertThat(Math.sqrt(squaredNorm)).isCloseTo(1.0, org.assertj.core.data.Offset.offset(0.0001));
        }
    }

    @Test
    void preservesInputOrderAndRejectsNullInputs() {
        List<float[]> vectors = gateway.embed(List.of("first", "second"));

        boolean anyCoordinateDiffers = false;
        for (int index = 0; index < EmbeddingGateway.DIMENSION; index++) {
            if (vectors.get(0)[index] != vectors.get(1)[index]) {
                anyCoordinateDiffers = true;
                break;
            }
        }
        assertThat(anyCoordinateDiffers).isTrue();
        assertThatThrownBy(() -> gateway.embed(null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> gateway.embed(List.of("ok", null)))
                .isInstanceOf(NullPointerException.class);
    }
}
