package io.sentinelops.api.knowledge.application;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Reproducible, dependency-free embedding used by CI and explicitly deterministic profiles.
 *
 * <p>The digest stream is expanded into signed values and then normalized. It is deliberately
 * not annotated as a Spring component: selecting a deterministic provider is a deployment policy
 * decision made by profile wiring.
 */
public final class DeterministicEmbeddingGateway implements EmbeddingGateway {

    private static final String MODEL_ID = "deterministic-sha256-v1";
    private static final int DIGEST_BYTES = 32;

    @Override
    public List<float[]> embed(List<String> texts) {
        Objects.requireNonNull(texts, "texts");
        var vectors = new ArrayList<float[]>(texts.size());
        for (String text : texts) {
            vectors.add(embedOne(Objects.requireNonNull(text, "text")));
        }
        return List.copyOf(vectors);
    }

    @Override
    public String modelId() {
        return MODEL_ID;
    }

    private static float[] embedOne(String text) {
        byte[] input = text.getBytes(StandardCharsets.UTF_8);
        float[] vector = new float[DIMENSION];
        double squaredNorm = 0.0;
        int vectorIndex = 0;
        int block = 0;

        while (vectorIndex < DIMENSION) {
            byte[] digest = digest(input, block++);
            for (int digestOffset = 0;
                    digestOffset < digest.length && vectorIndex < DIMENSION;
                    digestOffset += Integer.BYTES) {
                int bits = ByteBuffer.wrap(digest, digestOffset, Integer.BYTES).getInt();
                // Mapping the full signed int range avoids a zero vector for normal inputs while
                // keeping the value in a bounded range before normalization.
                float value = (bits / (float) Integer.MAX_VALUE);
                if (!Float.isFinite(value) || value == 0.0f) {
                    value = 1.0f;
                }
                vector[vectorIndex++] = value;
                squaredNorm += (double) value * value;
            }
        }

        double norm = Math.sqrt(squaredNorm);
        if (!(norm > 0.0) || !Double.isFinite(norm)) {
            throw new IllegalStateException("deterministic embedding produced an invalid norm");
        }
        for (int index = 0; index < vector.length; index++) {
            vector[index] = (float) (vector[index] / norm);
            if (!Float.isFinite(vector[index])) {
                throw new IllegalStateException("deterministic embedding produced a non-finite value");
            }
        }
        return vector;
    }

    private static byte[] digest(byte[] input, int block) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(input);
            digest.update((byte) 0);
            digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(block).array());
            return digest.digest();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required by the Java runtime", impossible);
        }
    }
}
