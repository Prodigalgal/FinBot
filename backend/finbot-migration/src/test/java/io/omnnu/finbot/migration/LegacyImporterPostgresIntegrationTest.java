package io.omnnu.finbot.migration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.sql.DriverManager;
import liquibase.Liquibase;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers(disabledWithoutDocker = true)
final class LegacyImporterPostgresIntegrationTest {
    private static final String CHANGELOG = "db/changelog/db.changelog-master.yaml";

    @Container
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18-alpine")
            .withDatabaseName("finbot")
            .withUsername("finbot")
            .withPassword("finbot-test");

    @Test
    void archivesEverySourceRowTransformsTypedFactsAndIsIdempotent(@TempDir Path directory)
            throws Exception {
        updateSchema();
        var source = directory.resolve("legacy.sqlite3");
        createLegacySource(source);
        var configuration = new ImportConfiguration(
                source,
                POSTGRES.getJdbcUrl(),
                POSTGRES.getUsername(),
                POSTGRES.getPassword(),
                50);
        var importer = new LegacyImporter(configuration, new ObjectMapper());

        var first = importer.execute();
        simulateInterruptedLastTable(first.importId());
        var resumed = importer.execute();
        var second = importer.execute();

        assertFalse(first.alreadyCompleted());
        assertEquals("COMPLETED", first.status());
        assertEquals(5, first.tableCount());
        assertEquals(6, first.sourceRowCount());
        assertEquals(6, first.archivedRowCount());
        assertEquals(4, first.transformedRowCount());
        assertFalse(resumed.alreadyCompleted());
        assertEquals("COMPLETED", resumed.status());
        assertEquals(first.sourceRowCount(), resumed.sourceRowCount());
        assertEquals(first.archivedRowCount(), resumed.archivedRowCount());
        assertTrue(second.alreadyCompleted());
        assertEquals(first.importId(), second.importId());

        try (var connection = DriverManager.getConnection(
                        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                var statement = connection.prepareStatement("""
                        SELECT
                          (SELECT count(*) FROM legacy_archive_row) AS archived_rows,
                          (SELECT count(*) FROM legacy_import_table WHERE status = 'COMPLETED') AS tables,
                          (SELECT count(*) FROM canonical_product WHERE base_asset = 'TEST') AS products,
                          (SELECT count(*) FROM venue_instrument WHERE symbol = 'TESTUSDT') AS instruments,
                          (SELECT count(*) FROM instrument_alias WHERE alias = 'TEST-USDT') AS aliases,
                          (SELECT count(*) FROM market_candle_fact WHERE symbol = 'TESTUSDT') AS candles
                        """);
                var result = statement.executeQuery()) {
            result.next();
            assertEquals(6, result.getLong("archived_rows"));
            assertEquals(5, result.getLong("tables"));
            assertEquals(1, result.getLong("products"));
            assertEquals(1, result.getLong("instruments"));
            assertEquals(1, result.getLong("aliases"));
            assertEquals(1, result.getLong("candles"));
        }
    }

    private static void simulateInterruptedLastTable(String importId) throws Exception {
        try (var connection = DriverManager.getConnection(
                        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
            executeForImport(connection, """
                    DELETE FROM legacy_archive_row
                    WHERE import_id = ? AND source_table = 'unmapped_history'
                    """, importId);
            executeForImport(connection, """
                    DELETE FROM legacy_import_table
                    WHERE import_id = ? AND source_table = 'unmapped_history'
                    """, importId);
            executeForImport(connection, """
                    UPDATE legacy_import_manifest
                    SET status = 'RUNNING', completed_at = NULL
                    WHERE import_id = ?
                    """, importId);
        }
    }

    private static void executeForImport(
            java.sql.Connection connection,
            String sql,
            String importId) throws Exception {
        try (var statement = connection.prepareStatement(sql)) {
            statement.setString(1, importId);
            statement.executeUpdate();
        }
    }

    private static void updateSchema() throws Exception {
        var connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        try (var liquibase = new Liquibase(
                CHANGELOG,
                new ClassLoaderResourceAccessor(),
                new JdbcConnection(connection))) {
            liquibase.validate();
            liquibase.update();
        }
    }

    private static void createLegacySource(Path source) throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + source);
                var statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE canonical_products (
                      product_id TEXT PRIMARY KEY, asset_class TEXT, product_type TEXT,
                      base_asset TEXT, quote_asset TEXT, display_name TEXT, status TEXT
                    )
                    """);
            statement.execute("""
                    INSERT INTO canonical_products VALUES
                      ('old-product', 'crypto', 'perpetual', 'TEST', 'USDT', 'Test / Tether', 'active')
                    """);
            statement.execute("""
                    CREATE TABLE venue_instruments (
                      instrument_id TEXT PRIMARY KEY, product_id TEXT, provider TEXT,
                      market_type TEXT, symbol TEXT, base_asset TEXT, quote_asset TEXT,
                      settle_asset TEXT, active INTEGER, inverse INTEGER, contract_size REAL,
                      tick_size REAL, amount_step REAL, min_amount REAL, leverage_json TEXT,
                      captured_at TEXT
                    )
                    """);
            statement.execute("""
                    INSERT INTO venue_instruments VALUES (
                      'old-instrument', 'old-product', 'bybit', 'linear', 'TESTUSDT',
                      'TEST', 'USDT', 'USDT', 1, 0, 1, 0.001, 0.1, 0.1,
                      '{"max":50}', '2026-07-14T00:00:00Z'
                    )
                    """);
            statement.execute("""
                    CREATE TABLE instrument_aliases (
                      alias_key TEXT, instrument_id TEXT
                    )
                    """);
            statement.execute("INSERT INTO instrument_aliases VALUES ('TEST-USDT', 'old-instrument')");
            statement.execute("""
                    CREATE TABLE market_candles (
                      provider TEXT, market_type TEXT, symbol TEXT, interval TEXT,
                      open_time TEXT, captured_at TEXT, open REAL, high REAL,
                      low REAL, close REAL, volume REAL, turnover REAL
                    )
                    """);
            statement.execute("""
                    INSERT INTO market_candles VALUES (
                      'bybit', 'linear', 'TESTUSDT', '1h',
                      '2026-07-14T00:00:00Z', '2026-07-14T01:00:00Z',
                      10, 12, 9, 11, 100, 1100
                    )
                    """);
            statement.execute("CREATE TABLE unmapped_history (id INTEGER PRIMARY KEY, value TEXT)");
            statement.execute("INSERT INTO unmapped_history(value) VALUES ('one'), ('two')");
        }
    }
}
