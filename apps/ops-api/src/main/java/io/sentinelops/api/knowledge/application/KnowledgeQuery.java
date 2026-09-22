package io.sentinelops.api.knowledge.application;

import java.util.Objects;
import java.util.StringJoiner;
import java.util.UUID;

/** Service scope is supplied by the authorized caller, never by model tool arguments. */
public record KnowledgeQuery(UUID serviceId, String text, float[] embedding, String embeddingModel) {
    public KnowledgeQuery {
        Objects.requireNonNull(serviceId, "serviceId");
        KnowledgeText.require(text, 1000);
        validateModel(embeddingModel);
        validateEmbedding(embedding);
        embedding = embedding.clone();
    }

    @Override public float[] embedding() { return embedding.clone(); }

    public static void validateModel(String model) {
        if (model == null || !model.matches("[A-Za-z0-9][A-Za-z0-9._:/-]{0,127}")) {
            throw new IllegalArgumentException("invalid embedding model identifier");
        }
    }

    public static void validateEmbedding(float[] vector) {
        if (vector == null || vector.length != 1536) throw new IllegalArgumentException("embedding dimension must be 1536");
        double norm = 0;
        for (float item : vector) {
            if (!Float.isFinite(item)) throw new IllegalArgumentException("embedding must be finite");
            norm += (double) item * item;
        }
        if (norm == 0) throw new IllegalArgumentException("embedding must be nonzero");
    }

    public static String vectorLiteral(float[] vector) {
        validateEmbedding(vector);
        // Cosine is scale invariant. Normalize in double precision before PostgreSQL's
        // float arithmetic to avoid overflow/underflow for otherwise finite vectors.
        double squaredNorm = 0;
        for (float item : vector) squaredNorm += (double) item * item;
        double norm = Math.sqrt(squaredNorm);
        var result = new StringJoiner(",", "[", "]");
        for (float item : vector) result.add(Float.toString((float) (item / norm)));
        return result.toString();
    }
}
