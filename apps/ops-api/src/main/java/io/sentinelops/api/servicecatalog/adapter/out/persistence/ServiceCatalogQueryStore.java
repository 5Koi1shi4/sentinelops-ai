package io.sentinelops.api.servicecatalog.adapter.out.persistence;

import io.sentinelops.api.servicecatalog.application.ServiceCatalogQueryService.ServiceSummary;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class ServiceCatalogQueryStore {
    private final JdbcClient jdbc;

    public ServiceCatalogQueryStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<ServiceSummary> list(String afterKey, int limit, boolean platformAdmin, Set<UUID> serviceIds) {
        if (!platformAdmin && serviceIds.isEmpty()) return List.of();
        String scope = platformAdmin ? "" : " and id in (:serviceIds)";
        String cursor = afterKey == null ? "" : " and service_key>:afterKey";
        var query = jdbc.sql("""
                select id, service_key, display_name, owner_team
                from service_catalog
                where true%s%s
                order by service_key
                limit :limit
                """.formatted(cursor, scope)).param("limit", limit);
        if (afterKey != null) query = query.param("afterKey", afterKey);
        if (!platformAdmin) query = query.param("serviceIds", serviceIds);
        return query.query((row, index) -> new ServiceSummary(
                row.getObject("id", UUID.class), row.getString("service_key"),
                row.getString("display_name"), row.getString("owner_team"))).list();
    }
}
