package io.omnnu.finbot.infrastructure.jdbc.persistence;

import io.omnnu.finbot.infrastructure.jdbc.persistence.PostgresJdbcParameters;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.time.Instant;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.Test;

final class PostgresJdbcParametersTest {
    @Test
    void convertsDomainInstantToUtcOffsetDateTime() {
        var value = Instant.parse("2026-07-14T01:30:00.123456Z");

        assertEquals(OffsetDateTime.parse("2026-07-14T01:30:00.123456Z"), PostgresJdbcParameters.timestamp(value));
        assertNull(PostgresJdbcParameters.timestamp(null));
    }
}
