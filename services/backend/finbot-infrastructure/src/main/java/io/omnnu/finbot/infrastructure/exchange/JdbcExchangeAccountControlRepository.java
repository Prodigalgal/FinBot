package io.omnnu.finbot.infrastructure.exchange;

import static io.omnnu.finbot.infrastructure.jdbc.PostgresJdbcParameters.timestamp;

import io.omnnu.finbot.application.exchange.ExchangeAccountControl;
import io.omnnu.finbot.application.exchange.ExchangeAccountControlRepository;
import io.omnnu.finbot.domain.catalog.ExchangeVenue;
import io.omnnu.finbot.domain.ledger.ExchangeAccountId;
import io.omnnu.finbot.domain.ledger.ExchangeEnvironment;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public final class JdbcExchangeAccountControlRepository implements ExchangeAccountControlRepository {
    private final JdbcClient jdbcClient;

    public JdbcExchangeAccountControlRepository(JdbcClient jdbcClient) {
        this.jdbcClient = Objects.requireNonNull(jdbcClient, "jdbcClient");
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ExchangeAccountControl> find(ExchangeAccountId accountId) {
        return jdbcClient.sql("""
                select account_id, exchange, environment, display_name,
                       enabled, version, updated_at
                from exchange_account
                where account_id = :accountId
                """)
                .param("accountId", accountId.value())
                .query((resultSet, rowNumber) -> control(resultSet))
                .optional();
    }

    @Override
    @Transactional
    public Optional<ExchangeAccountControl> setEnabled(
            ExchangeAccountId accountId,
            boolean enabled,
            long expectedVersion,
            Instant updatedAt) {
        return jdbcClient.sql("""
                update exchange_account
                set enabled = :enabled,
                    version = version + 1,
                    updated_at = :updatedAt
                where account_id = :accountId and version = :expectedVersion
                returning account_id, exchange, environment, display_name,
                          enabled, version, updated_at
                """)
                .param("accountId", accountId.value())
                .param("enabled", enabled)
                .param("expectedVersion", expectedVersion)
                .param("updatedAt", timestamp(updatedAt))
                .query((resultSet, rowNumber) -> control(resultSet))
                .optional();
    }

    private static ExchangeAccountControl control(ResultSet resultSet) throws SQLException {
        return new ExchangeAccountControl(
                new ExchangeAccountId(resultSet.getString("account_id")),
                ExchangeVenue.valueOf(resultSet.getString("exchange")),
                ExchangeEnvironment.valueOf(resultSet.getString("environment")),
                resultSet.getString("display_name"),
                resultSet.getBoolean("enabled"),
                resultSet.getLong("version"),
                resultSet.getObject("updated_at", OffsetDateTime.class).toInstant());
    }
}
