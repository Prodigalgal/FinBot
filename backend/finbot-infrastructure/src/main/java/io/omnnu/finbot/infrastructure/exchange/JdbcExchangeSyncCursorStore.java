package io.omnnu.finbot.infrastructure.exchange;

import static io.omnnu.finbot.infrastructure.jdbc.PostgresJdbcParameters.timestamp;

import io.omnnu.finbot.application.exchange.ExchangeSyncCursorStore;
import io.omnnu.finbot.domain.ledger.ExchangeAccountId;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.Optional;
import java.util.List;
import io.omnnu.finbot.domain.market.InstrumentSymbol;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public final class JdbcExchangeSyncCursorStore implements ExchangeSyncCursorStore {
    private final JdbcClient jdbcClient;

    public JdbcExchangeSyncCursorStore(JdbcClient jdbcClient) {
        this.jdbcClient = Objects.requireNonNull(jdbcClient, "jdbcClient");
    }

    @Override
    public Optional<Instant> watermark(ExchangeAccountId accountId) {
        return jdbcClient.sql("""
                select watermark_at from exchange_sync_cursor
                where account_id = :accountId and stream_type = 'ACCOUNT'
                """)
                .param("accountId", accountId.value())
                .query(OffsetDateTime.class)
                .optional()
                .map(OffsetDateTime::toInstant);
    }

    @Override
    public void advance(ExchangeAccountId accountId, Instant watermark, Instant updatedAt) {
        jdbcClient.sql("""
                insert into exchange_sync_cursor (
                  account_id, stream_type, cursor_value, watermark_at, updated_at
                ) values (
                  :accountId, 'ACCOUNT', :cursorValue, :watermark, :updatedAt
                ) on conflict (account_id, stream_type) do update
                set cursor_value = excluded.cursor_value,
                    watermark_at = greatest(exchange_sync_cursor.watermark_at, excluded.watermark_at),
                    version = exchange_sync_cursor.version + 1,
                    updated_at = excluded.updated_at
                """)
                .param("accountId", accountId.value())
                .param("cursorValue", watermark.toString())
                .param("watermark", timestamp(watermark))
                .param("updatedAt", timestamp(updatedAt))
                .update();
    }

    @Override
    public List<InstrumentSymbol> currentOpenPositionSymbols(ExchangeAccountId accountId) {
        return jdbcClient.sql("""
                select symbol
                from (
                  select distinct on (symbol) symbol, quantity
                  from exchange_position_snapshot
                  where account_id = :accountId
                  order by symbol, occurred_at desc, id desc
                ) latest
                where quantity > 0
                order by symbol
                """)
                .param("accountId", accountId.value())
                .query(String.class)
                .list()
                .stream()
                .map(InstrumentSymbol::new)
                .toList();
    }
}
