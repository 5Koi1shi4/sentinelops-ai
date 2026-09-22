package io.sentinelops.api.knowledge.application;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

@Service
public class HybridKnowledgeSearch implements KnowledgeSearch {
    private final JdbcClient jdbc;
    public HybridKnowledgeSearch(JdbcClient jdbc) { this.jdbc = jdbc; }

    @Override public List<KnowledgeHit> search(KnowledgeQuery query, int limit) {
        Objects.requireNonNull(query, "query");
        if (limit < 1 || limit > 10) throw new IllegalArgumentException("result limit must be 1..10");
        return jdbc.sql("""
                with lexical as (
                  select kc.id,
                    cast(ts_rank_cd(kc.search_vector, websearch_to_tsquery('simple', :q)) as double precision) lexical_score,
                    row_number() over(order by ts_rank_cd(kc.search_vector, websearch_to_tsquery('simple', :q)) desc, kc.id) lex_rank
                  from knowledge_chunk kc
                  join runbook_version rv on rv.id=kc.runbook_version_id and rv.lifecycle='published'
                  join runbook r on r.id=rv.runbook_id and r.service_id=:service
                  where kc.service_id=:service and kc.embedding_model=:model
                    and kc.search_vector @@ websearch_to_tsquery('simple', :q)
                  order by ts_rank_cd(kc.search_vector, websearch_to_tsquery('simple', :q)) desc, kc.id
                  limit 50
                ), semantic as (
                  select kc.id, 1 - (kc.embedding <=> cast(:embedding as vector)) vector_score,
                    row_number() over(order by kc.embedding <=> cast(:embedding as vector), kc.id) sem_rank
                  from knowledge_chunk kc
                  join runbook_version rv on rv.id=kc.runbook_version_id and rv.lifecycle='published'
                  join runbook r on r.id=rv.runbook_id and r.service_id=:service
                  where kc.service_id=:service and kc.embedding_model=:model
                  order by kc.embedding <=> cast(:embedding as vector), kc.id
                  limit 50
                ), fused as (
                  select coalesce(l.id,s.id) id, l.lex_rank, s.sem_rank, l.lexical_score, s.vector_score,
                    coalesce(1.0/(60+l.lex_rank),0) + coalesce(1.0/(60+s.sem_rank),0) fused_score
                  from lexical l full outer join semantic s on s.id=l.id
                )
                select kc.id, kc.chunk_no, kc.content, r.runbook_key, rv.id version_id, rv.version_number,
                       fused.lex_rank, fused.sem_rank, fused.lexical_score, fused.vector_score, fused.fused_score
                from fused join knowledge_chunk kc on kc.id=fused.id
                join runbook_version rv on rv.id=kc.runbook_version_id and rv.lifecycle='published'
                join runbook r on r.id=rv.runbook_id and r.service_id=:service
                where kc.service_id=:service and kc.embedding_model=:model
                order by fused.fused_score desc, kc.id limit :limit
                """)
                .param("q", query.text()).param("service", query.serviceId()).param("model", query.embeddingModel())
                .param("embedding", KnowledgeQuery.vectorLiteral(query.embedding())).param("limit", limit)
                .query((rs, row) -> new KnowledgeHit("runbook", rs.getString("runbook_key"),
                        rs.getObject("version_id", UUID.class), rs.getInt("version_number"),
                        rs.getObject("id", UUID.class), rs.getInt("chunk_no"), rs.getString("content"),
                        rs.getObject("lex_rank", Long.class), rs.getObject("sem_rank", Long.class),
                        rs.getObject("lexical_score", Double.class), rs.getObject("vector_score", Double.class),
                        rs.getDouble("fused_score"))).list();
    }
}
