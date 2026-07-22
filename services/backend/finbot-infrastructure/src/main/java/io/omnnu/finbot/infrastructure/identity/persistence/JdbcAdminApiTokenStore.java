package io.omnnu.finbot.infrastructure.identity.persistence;

import static io.omnnu.finbot.infrastructure.jdbc.persistence.PostgresJdbcParameters.timestamp;

import io.omnnu.finbot.application.identity.port.out.AdminApiTokenStore;
import io.omnnu.finbot.domain.identity.AdminApiToken;
import io.omnnu.finbot.domain.identity.AdminApiTokenId;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public final class JdbcAdminApiTokenStore implements AdminApiTokenStore {
    private final JdbcClient jdbcClient;

    public JdbcAdminApiTokenStore(JdbcClient jdbcClient) {
        this.jdbcClient = Objects.requireNonNull(jdbcClient, "jdbcClient");
    }

    @Override
    @Transactional(readOnly = true)
    public List<AdminApiToken> listTokens() {
        return jdbcClient.sql("""
                select token_id, fingerprint, display_name, username, expires_at,
                       last_used_at, revoked_at, version, created_at, updated_at
                from admin_api_token
                order by created_at desc, id desc
                """)
                .query((resultSet, rowNumber) -> token(resultSet))
                .list();
    }

    @Override
    @Transactional(readOnly = true)
    public long countActiveTokens(Instant now) {
        return jdbcClient.sql("""
                select count(*) from admin_api_token
                where revoked_at is null
                  and (expires_at is null or expires_at > :now)
                """)
                .param("now", timestamp(now))
                .query(Long.class)
                .single();
    }

    @Override
    public void createToken(AdminApiToken token, String tokenDigest) {
        Objects.requireNonNull(token, "token");
        jdbcClient.sql("""
                insert into admin_api_token (
                  token_id, token_digest, fingerprint, display_name, username,
                  expires_at, last_used_at, revoked_at, version, created_at, updated_at
                ) values (
                  :tokenId, :tokenDigest, :fingerprint, :displayName, :username,
                  :expiresAt, :lastUsedAt, :revokedAt, :version, :createdAt, :updatedAt
                )
                """)
                .param("tokenId", token.tokenId().value())
                .param("tokenDigest", Objects.requireNonNull(tokenDigest, "tokenDigest"))
                .param("fingerprint", token.fingerprint())
                .param("displayName", token.displayName())
                .param("username", token.username())
                .param("expiresAt", timestamp(token.expiresAt()))
                .param("lastUsedAt", timestamp(token.lastUsedAt()))
                .param("revokedAt", timestamp(token.revokedAt()))
                .param("version", token.version())
                .param("createdAt", timestamp(token.createdAt()))
                .param("updatedAt", timestamp(token.updatedAt()))
                .update();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<AdminApiToken> findActiveToken(String tokenDigest, Instant now) {
        return jdbcClient.sql("""
                select token_id, fingerprint, display_name, username, expires_at,
                       last_used_at, revoked_at, version, created_at, updated_at
                from admin_api_token
                where token_digest = :tokenDigest
                  and revoked_at is null
                  and (expires_at is null or expires_at > :now)
                """)
                .param("tokenDigest", tokenDigest)
                .param("now", timestamp(now))
                .query((resultSet, rowNumber) -> token(resultSet))
                .optional();
    }

    @Override
    public void touchToken(String tokenDigest, Instant usedAt) {
        jdbcClient.sql("""
                update admin_api_token
                set last_used_at = :usedAt,
                    updated_at = :usedAt
                where token_digest = :tokenDigest
                  and revoked_at is null
                  and (expires_at is null or expires_at > :usedAt)
                """)
                .param("tokenDigest", tokenDigest)
                .param("usedAt", timestamp(usedAt))
                .update();
    }

    @Override
    @Transactional
    public Optional<AdminApiToken> revokeToken(
            AdminApiTokenId tokenId,
            long expectedVersion,
            Instant revokedAt) {
        var changed = jdbcClient.sql("""
                update admin_api_token
                set revoked_at = :revokedAt,
                    version = version + 1,
                    updated_at = :revokedAt
                where token_id = :tokenId
                  and version = :expectedVersion
                  and revoked_at is null
                """)
                .param("tokenId", tokenId.value())
                .param("expectedVersion", expectedVersion)
                .param("revokedAt", timestamp(revokedAt))
                .update();
        return changed == 1 ? findToken(tokenId) : Optional.empty();
    }

    private Optional<AdminApiToken> findToken(AdminApiTokenId tokenId) {
        return jdbcClient.sql("""
                select token_id, fingerprint, display_name, username, expires_at,
                       last_used_at, revoked_at, version, created_at, updated_at
                from admin_api_token where token_id = :tokenId
                """)
                .param("tokenId", tokenId.value())
                .query((resultSet, rowNumber) -> token(resultSet))
                .optional();
    }

    private static AdminApiToken token(ResultSet resultSet) throws SQLException {
        return new AdminApiToken(
                new AdminApiTokenId(resultSet.getString("token_id")),
                resultSet.getString("display_name"),
                resultSet.getString("fingerprint"),
                resultSet.getString("username"),
                nullableInstant(resultSet.getObject("expires_at", OffsetDateTime.class)),
                nullableInstant(resultSet.getObject("last_used_at", OffsetDateTime.class)),
                nullableInstant(resultSet.getObject("revoked_at", OffsetDateTime.class)),
                instant(resultSet.getObject("created_at", OffsetDateTime.class)),
                instant(resultSet.getObject("updated_at", OffsetDateTime.class)),
                resultSet.getLong("version"));
    }

    private static Instant instant(OffsetDateTime value) {
        return Objects.requireNonNull(value, "database timestamp").toInstant();
    }

    private static Instant nullableInstant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }
}
