package io.sentinelops.api.knowledge;

import static org.assertj.core.api.Assertions.*;

import io.sentinelops.api.knowledge.application.*;
import io.sentinelops.api.support.PostgresIntegrationTest;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

class HybridKnowledgeSearchIT extends PostgresIntegrationTest {
    static final UUID SERVICE = UUID.fromString("0199a000-0000-7000-8000-000000000001");
    static final String MODEL = "test-vector-v1";
    @Autowired HybridKnowledgeSearch search;
    @Autowired JdbcClient jdbc;
    @Autowired PlatformTransactionManager transactions;

    @Test void hybridSearchReturnsVersionedCitationAndBothScores() {
        String term = "timeout" + UUID.randomUUID().toString().replace("-", "");
        UUID chunk = insert("published", SERVICE, MODEL, "connection pool " + term, 1, 4);
        var hits = search.search(new KnowledgeQuery(SERVICE, term, vector(1), MODEL), 5);
        assertThat(hits).anySatisfy(hit -> {
            assertThat(hit.chunkId()).isEqualTo(chunk);
            assertThat(hit.runbookKey()).startsWith("RB-DB-POOL-03-");
            assertThat(hit.versionNumber()).isEqualTo(4);
            assertThat(hit.source()).isEqualTo("runbook");
            assertThat(hit.lexicalRank()).isEqualTo(1);
            assertThat(hit.vectorRank()).isNotNull();
            assertThat(hit.lexicalScore()).isPositive();
            assertThat(hit.vectorScore()).isCloseTo(1.0, within(0.0001));
            assertThat(hit.fusedScore()).isPositive();
        });
    }

    @Test void draftRetiredOtherServiceAndOtherModelChunksNeverAppearInSearch() {
        var service = newService();
        UUID good = insert("published", service, MODEL, "connection pool timeout", 1, 1);
        insert("draft", service, MODEL, "connection pool timeout", 1, 1);
        insert("retired", service, MODEL, "connection pool timeout", 1, 1);
        insert("published", newService(), MODEL, "connection pool timeout", 1, 1);
        insert("published", service, "other-model", "connection pool timeout", 1, 1);
        assertThat(search.search(new KnowledgeQuery(service, "connection pool timeout", vector(1), MODEL), 10))
                .extracting(KnowledgeHit::chunkId).containsExactly(good);
    }

    @Test void candidateRankingHappensBeforeLimitWithStableTies() {
        var service = newService();
        for (int i = 0; i < 55; i++) insert("published", service, MODEL, "background only " + i, 2, 1);
        UUID best = insert("published", service, MODEL, "connection pool timeout", 1, 1);
        var query = new KnowledgeQuery(service, "connection pool timeout", vector(1), MODEL);
        var result = search.search(query, 10);
        assertThat(result).hasSize(10);
        assertThat(result.getFirst().chunkId()).isEqualTo(best);
        assertThat(result.getFirst().fusedScore()).isCloseTo(2.0 / 61, within(0.000001));
        assertThat(search.search(query, 10)).isEqualTo(result);
    }

    @Test void databaseRejectsChunkWithDifferentServiceFromItsVersion() {
        UUID chunk = insert("draft", SERVICE, MODEL, "draft test", 1, 1);
        UUID other = newService();
        assertThatThrownBy(() -> jdbc.sql("""
                insert into knowledge_chunk(id,runbook_version_id,service_id,chunk_no,content,embedding,embedding_model,content_hash,created_at)
                select :id,runbook_version_id,:other,1,'wrong scope',embedding,embedding_model,:hash,now()
                from knowledge_chunk where id=:chunk
                """).param("id", UUID.randomUUID()).param("other", other).param("hash", "b".repeat(64)).param("chunk", chunk).update())
                .isInstanceOf(DataAccessException.class);
    }

    @Test void ginAndHnswIndexesAreEligibleOnActualPostgres() {
        insert("published", SERVICE, MODEL, "connection pool", 1, 1);
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            jdbc.sql("set local enable_seqscan = off").update();
            String fts = String.join("\n", jdbc.sql("explain select id from knowledge_chunk where search_vector @@ websearch_to_tsquery('simple','connection')")
                    .query(String.class).list());
            String vector = String.join("\n", jdbc.sql("explain select id from knowledge_chunk order by embedding <=> cast(:v as vector) limit 10")
                    .param("v", vectorLiteral(1)).query(String.class).list());
            assertThat(fts).contains("knowledge_chunk_fts_idx");
            assertThat(vector).contains("knowledge_chunk_embedding_hnsw_idx");
        });
    }

    @Test void queryBudgetAndVectorsAreValidatedBeforeSql() {
        assertThatThrownBy(() -> search.search(new KnowledgeQuery(SERVICE, "pool", vector(1), MODEL), 11))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new KnowledgeQuery(SERVICE, "pool", new float[1536], MODEL)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new KnowledgeQuery(SERVICE, "pool", new float[3], MODEL)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void finiteRescaledVectorsPreserveCosineRanking() {
        var service = newService();
        UUID expected = insert("published", service, MODEL, "matching direction", 1, 1);
        for (float scale : List.of(Float.MAX_VALUE, Float.MIN_VALUE)) {
            float[] queryVector = new float[1536];
            queryVector[0] = scale;
            var results = search.search(new KnowledgeQuery(service, "unmatched", queryVector, MODEL), 1);
            assertThat(results).hasSize(1);
            assertThat(results.getFirst().chunkId()).isEqualTo(expected);
            assertThat(results.getFirst().vectorScore()).isCloseTo(1.0, within(0.00001));
        }
    }

    UUID insert(String state, UUID service, String model, String content, int direction, int version) {
        UUID runbook = UUID.randomUUID(), rv = UUID.randomUUID(), chunk = UUID.randomUUID();
        jdbc.sql("insert into runbook(id,runbook_key,service_id,display_name,owner_team,created_at,updated_at) values(:id,:key,:service,'test','test',now(),now())")
                .param("id", runbook).param("key", "RB-DB-POOL-03-" + runbook).param("service", service).update();
        jdbc.sql("""
                insert into runbook_version(id,runbook_id,version_number,lifecycle,risk_level,adapter_id,definition,definition_checksum,created_at,published_at)
                values(:id,:r,:version,:state,'r1','demo-http','{}','test',now(),case when :state='draft' then null else now() end)
                """).param("id", rv).param("r", runbook).param("version", version).param("state", state).update();
        jdbc.sql("""
                insert into knowledge_chunk(id,runbook_version_id,service_id,chunk_no,content,embedding,embedding_model,content_hash,created_at)
                values(:id,:rv,:service,0,:content,cast(:v as vector),:model,:hash,now())
                """).param("id", chunk).param("rv", rv).param("service", service).param("content", content)
                .param("v", vectorLiteral(direction)).param("model", model).param("hash", "a".repeat(64)).update();
        return chunk;
    }
    UUID newService() {
        UUID id = UUID.randomUUID();
        jdbc.sql("insert into service_catalog(id,service_key,display_name,owner_team,created_at,updated_at) values(:id,:key,'Test','test',now(),now())")
                .param("id", id).param("key", "service-" + id).update();
        return id;
    }
    static float[] vector(int direction) { var v = new float[1536]; v[direction - 1] = 1; return v; }
    static String vectorLiteral(int direction) {
        var values = new StringJoiner(",", "[", "]");
        for (int i = 0; i < 1536; i++) values.add(i == direction - 1 ? "1" : "0");
        return values.toString();
    }
}
