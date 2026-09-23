package io.sentinelops.api.identity.adapter.out.persistence;

import io.sentinelops.api.identity.application.PlatformRole;
import io.sentinelops.api.shared.id.UuidV7Generator;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class PrincipalStore {
    private final JdbcClient jdbc;
    private final UuidV7Generator ids;

    public PrincipalStore(JdbcClient jdbc, UuidV7Generator ids) {
        this.jdbc = jdbc;
        this.ids = ids;
    }

    @Transactional
    public UUID reconcile(String issuer, String subject, String displayName, Instant issuedAt,
            Set<PlatformRole> roles, Set<UUID> serviceIds) {
        UUID candidate = ids.generate();
        jdbc.sql("""
                insert into principal(id,issuer,subject,display_name,created_at)
                values(:id,:issuer,:subject,:displayName,clock_timestamp())
                on conflict (issuer,subject) do nothing
                """).param("id", candidate).param("issuer", issuer).param("subject", subject)
                .param("displayName", displayName).update();
        var row = jdbc.sql("""
                select id,claims_issued_at from principal
                where issuer=:issuer and subject=:subject for update
                """).param("issuer", issuer).param("subject", subject)
                .query((rs, ignored) -> new PrincipalRow(
                        rs.getObject("id", UUID.class),
                        rs.getObject("claims_issued_at", OffsetDateTime.class)))
                .single();
        if (row.claimsIssuedAt() != null) {
            int ordering = issuedAt.compareTo(row.claimsIssuedAt().toInstant());
            if (ordering < 0) return row.id();
            if (ordering == 0) {
                // 同秒签发的令牌无法可靠排序，只保留授权交集；更晚签发的令牌才可重新增加授权。
                var existing = jdbc.sql("""
                        select id,role_name,service_id from role_grant where principal_id=:id
                        """).param("id", row.id())
                        .query((rs, ignored) -> new Grant(rs.getObject("id", UUID.class),
                                rs.getString("role_name"), rs.getObject("service_id", UUID.class)))
                        .list();
                for (var grant : existing) {
                    var role = PlatformRole.fromClaim(grant.roleName());
                    boolean asserted = role.isPresent() && roles.contains(role.orElseThrow())
                            && (role.orElseThrow() == PlatformRole.PLATFORM_ADMIN
                                ? grant.serviceId() == null
                                : serviceIds.contains(grant.serviceId()));
                    if (!asserted) jdbc.sql("delete from role_grant where id=:id")
                            .param("id", grant.id()).update();
                }
                return row.id();
            }
        }
        jdbc.sql("""
                update principal set display_name=:displayName,claims_issued_at=:issuedAt
                where id=:id
                """).param("id", row.id()).param("displayName", displayName)
                .param("issuedAt", OffsetDateTime.ofInstant(issuedAt, java.time.ZoneOffset.UTC)).update();
        jdbc.sql("delete from role_grant where principal_id=:id")
                .param("id", row.id()).update();
        if (roles.contains(PlatformRole.PLATFORM_ADMIN)) {
            insertGrant(row.id(), PlatformRole.PLATFORM_ADMIN, null);
        }
        Collection<UUID> registeredServices = serviceIds.isEmpty() ? Set.of() : jdbc.sql("""
                select id from service_catalog where id in (:serviceIds)
                """).param("serviceIds", serviceIds).query(UUID.class).list();
        for (var role : roles) {
            if (role == PlatformRole.PLATFORM_ADMIN) continue;
            for (var serviceId : registeredServices) {
                insertGrant(row.id(), role, serviceId);
            }
        }
        return row.id();
    }

    private void insertGrant(UUID principalId, PlatformRole role, UUID serviceId) {
        jdbc.sql("""
                insert into role_grant(id,principal_id,role_name,service_id,granted_at)
                values(:id,:principalId,:roleName,:serviceId,clock_timestamp())
                """).param("id", ids.generate()).param("principalId", principalId)
                .param("roleName", role.name().toLowerCase(java.util.Locale.ROOT))
                .param("serviceId", serviceId).update();
    }

    private record PrincipalRow(UUID id, OffsetDateTime claimsIssuedAt) {}
    private record Grant(UUID id, String roleName, UUID serviceId) {}
}
