package io.sentinelops.api.diagnosis.application.model;

import io.sentinelops.api.incident.application.evidence.EvidenceCapture;
import io.sentinelops.api.knowledge.application.KnowledgeSearch;
import io.sentinelops.api.knowledge.application.RunbookLookup;

/** Opens the configured provider against server-owned fixture ports, without changing production beans. */
@org.springframework.modulith.NamedInterface("evaluation")
public interface ModelGatewayFactory {
    Descriptor descriptor();
    Session open(EvidenceCapture evidence, RunbookLookup runbooks, KnowledgeSearch search);

    @org.springframework.modulith.NamedInterface("evaluation")
    record Descriptor(String provider, String modelName, String promptVersion, String toolsetVersion, String embeddingModel,
                      String promptHash, String toolsetHash, String configurationHash) {}
    @org.springframework.modulith.NamedInterface("evaluation")
    record Session(ModelGateway gateway) implements AutoCloseable {
        @Override public void close() {
            if (gateway instanceof AutoCloseable closeable) {
                try { closeable.close(); } catch (Exception failure) { throw new IllegalStateException("Model session close failed"); }
            }
        }
    }
}
