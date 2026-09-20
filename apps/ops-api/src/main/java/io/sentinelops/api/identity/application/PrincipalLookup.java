package io.sentinelops.api.identity.application;

import io.sentinelops.api.shared.id.UuidV7Generator;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PrincipalLookup {

    private final JdbcClient jdbc;
    private final UuidV7Generator ids;

    public PrincipalLookup(JdbcClient jdbc, UuidV7Generator ids) {
        this.jdbc = jdbc;
        this.ids = ids;
    }

    @Transactional
    public UUID upsert(CurrentPrincipal principal, String displayName) {
        String trustedDisplayName = displayName == null || displayName.isBlank()
                ? principal.subject()
                : displayName.trim();
        return jdbc.sql("""
                        insert into principal(id, issuer, subject, display_name, created_at)
                        values (:id, :issuer, :subject, :displayName, clock_timestamp())
                        on conflict (issuer, subject) do update
                        set display_name = excluded.display_name
                        returning id
                        """)
                .param("id", ids.generate())
                .param("issuer", principal.issuer())
                .param("subject", principal.subject())
                .param("displayName", trustedDisplayName)
                .query(UUID.class)
                .single();
    }
}
