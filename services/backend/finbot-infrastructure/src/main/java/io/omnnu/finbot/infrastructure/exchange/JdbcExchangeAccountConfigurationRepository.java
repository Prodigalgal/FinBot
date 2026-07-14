package io.omnnu.finbot.infrastructure.exchange;

import io.omnnu.finbot.application.exchange.ExchangeAccountConfiguration;
import io.omnnu.finbot.application.exchange.ExchangeAccountConfigurationRepository;
import io.omnnu.finbot.domain.catalog.ExchangeVenue;
import io.omnnu.finbot.domain.ledger.ExchangeAccountId;
import io.omnnu.finbot.domain.ledger.ExchangeEnvironment;
import java.util.Objects;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public final class JdbcExchangeAccountConfigurationRepository
        implements ExchangeAccountConfigurationRepository {
    private final JdbcClient jdbcClient;

    public JdbcExchangeAccountConfigurationRepository(JdbcClient jdbcClient) {
        this.jdbcClient = Objects.requireNonNull(jdbcClient, "jdbcClient");
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ExchangeAccountConfiguration> find(ExchangeAccountId accountId) {
        return jdbcClient.sql("""
                select exchange, environment, api_key_env, api_secret_env, enabled
                from exchange_account where account_id = :accountId
                """)
                .param("accountId", accountId.value())
                .query((resultSet, rowNumber) -> new ExchangeAccountConfiguration(
                        accountId,
                        ExchangeVenue.valueOf(resultSet.getString("exchange")),
                        ExchangeEnvironment.valueOf(resultSet.getString("environment")),
                        resultSet.getString("api_key_env"),
                        resultSet.getString("api_secret_env"),
                        resultSet.getBoolean("enabled")))
                .optional();
    }
}
