package io.omnnu.finbot.infrastructure.jdbc.persistence;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

public final class PostgresJdbcParameters {
    private PostgresJdbcParameters() {}

    public static OffsetDateTime timestamp(Instant value) {
        return value == null ? null : value.atOffset(ZoneOffset.UTC);
    }
}
