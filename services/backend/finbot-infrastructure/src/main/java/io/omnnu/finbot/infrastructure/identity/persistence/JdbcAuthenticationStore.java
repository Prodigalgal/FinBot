package io.omnnu.finbot.infrastructure.identity.persistence;

import static io.omnnu.finbot.infrastructure.jdbc.persistence.PostgresJdbcParameters.timestamp;

import io.omnnu.finbot.application.identity.dto.AdminSession;
import io.omnnu.finbot.application.identity.dto.AuthChallenge;
import io.omnnu.finbot.application.identity.port.out.AuthenticationStore;
import io.omnnu.finbot.domain.identity.AdminSessionId;
import io.omnnu.finbot.domain.identity.AuthChallengeId;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class JdbcAuthenticationStore implements AuthenticationStore {
    private final JdbcClient jdbcClient;

    public JdbcAuthenticationStore(JdbcClient jdbcClient) {
        this.jdbcClient = Objects.requireNonNull(jdbcClient, "jdbcClient");
    }

    @Override
    public void saveChallenge(AuthChallenge challenge) {
        jdbcClient.sql("""
                insert into auth_challenge (
                  challenge_id, nonce, answer_digest, pow_difficulty, expires_at,
                  consumed_at, failure_count, created_at
                ) values (
                  :challengeId, :nonce, :answerDigest, :difficulty, :expiresAt,
                  :consumedAt, :failureCount, :createdAt
                )
                """)
                .param("challengeId", challenge.challengeId().value())
                .param("nonce", challenge.nonce())
                .param("answerDigest", challenge.answerDigest())
                .param("difficulty", challenge.proofOfWorkDifficulty())
                .param("expiresAt", timestamp(challenge.expiresAt()))
                .param("consumedAt", timestamp(challenge.consumedAt()))
                .param("failureCount", challenge.failureCount())
                .param("createdAt", timestamp(challenge.createdAt()))
                .update();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<AuthChallenge> findChallenge(AuthChallengeId challengeId) {
        return jdbcClient.sql("""
                select nonce, answer_digest, pow_difficulty, expires_at, consumed_at,
                       failure_count, created_at
                from auth_challenge
                where challenge_id = :challengeId
                """)
                .param("challengeId", challengeId.value())
                .query((resultSet, rowNumber) -> new AuthChallenge(
                        challengeId,
                        resultSet.getString("nonce"),
                        resultSet.getString("answer_digest"),
                        resultSet.getInt("pow_difficulty"),
                        instant(resultSet.getObject("expires_at", OffsetDateTime.class)),
                        nullableInstant(resultSet.getObject("consumed_at", OffsetDateTime.class)),
                        resultSet.getInt("failure_count"),
                        instant(resultSet.getObject("created_at", OffsetDateTime.class))))
                .optional();
    }

    @Override
    public void recordChallengeFailure(AuthChallengeId challengeId) {
        jdbcClient.sql("""
                update auth_challenge
                set failure_count = least(failure_count + 1, 10)
                where challenge_id = :challengeId and consumed_at is null
                """)
                .param("challengeId", challengeId.value())
                .update();
    }

    @Override
    public boolean consumeChallenge(AuthChallengeId challengeId, Instant consumedAt) {
        return jdbcClient.sql("""
                update auth_challenge
                set consumed_at = :consumedAt
                where challenge_id = :challengeId
                  and consumed_at is null
                  and expires_at > :consumedAt
                  and failure_count < 10
                """)
                .param("challengeId", challengeId.value())
                .param("consumedAt", timestamp(consumedAt))
                .update() == 1;
    }

    @Override
    public void saveSession(AdminSession session, String tokenDigest) {
        jdbcClient.sql("""
                insert into admin_session (
                  session_id, token_digest, username, expires_at, last_seen_at,
                  revoked_at, created_at
                ) values (
                  :sessionId, :tokenDigest, :username, :expiresAt, :lastSeenAt,
                  :revokedAt, :createdAt
                )
                """)
                .param("sessionId", session.sessionId().value())
                .param("tokenDigest", tokenDigest)
                .param("username", session.username())
                .param("expiresAt", timestamp(session.expiresAt()))
                .param("lastSeenAt", timestamp(session.lastSeenAt()))
                .param("revokedAt", timestamp(session.revokedAt()))
                .param("createdAt", timestamp(session.createdAt()))
                .update();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<AdminSession> findActiveSession(String tokenDigest, Instant now) {
        return jdbcClient.sql("""
                select session_id, username, expires_at, last_seen_at, revoked_at, created_at
                from admin_session
                where token_digest = :tokenDigest
                  and revoked_at is null
                  and expires_at > :now
                """)
                .param("tokenDigest", tokenDigest)
                .param("now", timestamp(now))
                .query((resultSet, rowNumber) -> new AdminSession(
                        new AdminSessionId(resultSet.getString("session_id")),
                        resultSet.getString("username"),
                        instant(resultSet.getObject("expires_at", OffsetDateTime.class)),
                        instant(resultSet.getObject("last_seen_at", OffsetDateTime.class)),
                        nullableInstant(resultSet.getObject("revoked_at", OffsetDateTime.class)),
                        instant(resultSet.getObject("created_at", OffsetDateTime.class))))
                .optional();
    }

    @Override
    public void touchSession(String tokenDigest, Instant seenAt) {
        jdbcClient.sql("""
                update admin_session
                set last_seen_at = :seenAt
                where token_digest = :tokenDigest and revoked_at is null and expires_at > :seenAt
                """)
                .param("tokenDigest", tokenDigest)
                .param("seenAt", timestamp(seenAt))
                .update();
    }

    @Override
    public void revokeSession(String tokenDigest, Instant revokedAt) {
        jdbcClient.sql("""
                update admin_session
                set revoked_at = :revokedAt
                where token_digest = :tokenDigest and revoked_at is null
                """)
                .param("tokenDigest", tokenDigest)
                .param("revokedAt", timestamp(revokedAt))
                .update();
    }

    private static Instant instant(OffsetDateTime value) {
        return Objects.requireNonNull(value, "database timestamp").toInstant();
    }

    private static Instant nullableInstant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }
}
