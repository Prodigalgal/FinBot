package io.omnnu.finbot.migration;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.regex.Pattern;

final class LegacyImporter {
    private static final String TOOL_VERSION = "2.0.0";
    private static final Pattern SAFE_TABLE_NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_]{0,127}");
    private static final Pattern SENSITIVE_ERROR = Pattern.compile(
            "(?i)(password|secret|token|api[_-]?key)\\s*[=:]\\s*[^\\s,;]+");

    private final ImportConfiguration configuration;
    private final CanonicalRowCodec rowCodec;

    LegacyImporter(ImportConfiguration configuration, ObjectMapper objectMapper) {
        this.configuration = Objects.requireNonNull(configuration, "configuration");
        this.rowCodec = new CanonicalRowCodec(Objects.requireNonNull(objectMapper, "objectMapper"));
    }

    ImportResult execute() throws IOException, SQLException {
        var sourceHash = Hashing.sha256(configuration.source());
        var importId = "legacy_" + sourceHash.substring(0, 24);
        try (var source = openSource(); var target = openTarget()) {
            requireArchiveSchema(target);
            var tables = sourceTables(source);
            var schemaHash = schemaHash(tables);
            var completed = completedResult(target, sourceHash);
            if (completed != null) {
                return completed;
            }
            startManifest(target, importId, sourceHash, schemaHash);
            try {
                for (var tableIndex = 0; tableIndex < tables.size(); tableIndex++) {
                    var table = tables.get(tableIndex);
                    if (isTableCompleted(target, importId, table)) {
                        System.err.printf(
                                "Reused legacy table %d/%d: %s (%d rows)%n",
                                tableIndex + 1,
                                tables.size(),
                                table.name(),
                                table.rowCount());
                        continue;
                    }
                    importTable(source, target, importId, table);
                    System.err.printf(
                            "Archived legacy table %d/%d: %s (%d rows)%n",
                            tableIndex + 1,
                            tables.size(),
                            table.name(),
                            table.rowCount());
                }
                var transformed = LegacyTransformations.execute(target, importId);
                updateTransformCounts(target, importId, transformed);
                var result = completeManifest(target, importId, sourceHash, tables.size());
                target.commit();
                return result;
            } catch (RuntimeException | SQLException exception) {
                rollbackWithoutMasking(target, exception);
                markFailedWithoutMasking(importId, exception);
                throw exception;
            }
        }
    }

    private Connection openSource() throws SQLException {
        var normalizedPath = configuration.source().toString().replace('\\', '/');
        return DriverManager.getConnection("jdbc:sqlite:file:" + normalizedPath + "?mode=ro");
    }

    private Connection openTarget() throws SQLException {
        var properties = new Properties();
        properties.setProperty("user", configuration.databaseUsername());
        properties.setProperty("password", configuration.databasePassword());
        properties.setProperty("reWriteBatchedInserts", "true");
        var connection = DriverManager.getConnection(configuration.databaseUrl(), properties);
        connection.setAutoCommit(false);
        return connection;
    }

    private static void requireArchiveSchema(Connection target) throws SQLException {
        try (var statement = target.prepareStatement("""
                SELECT count(*)
                FROM information_schema.tables
                WHERE table_schema = current_schema() AND table_name = 'legacy_import_manifest'
                """); var resultSet = statement.executeQuery()) {
            resultSet.next();
            if (resultSet.getInt(1) != 1) {
                throw new IllegalStateException(
                        "Liquibase must create legacy_import_manifest before the offline import runs");
            }
        }
    }

    private List<SourceTable> sourceTables(Connection source) throws SQLException {
        var result = new ArrayList<SourceTable>();
        try (var statement = source.prepareStatement("""
                SELECT name, coalesce(sql, '') AS schema_sql
                FROM sqlite_master
                WHERE type = 'table' AND name NOT LIKE 'sqlite_%'
                ORDER BY name
                """); var resultSet = statement.executeQuery()) {
            while (resultSet.next()) {
                var name = resultSet.getString("name");
                if (!SAFE_TABLE_NAME.matcher(name).matches()) {
                    throw new IllegalStateException("Unsafe legacy table name: " + name);
                }
                result.add(new SourceTable(
                        name,
                        resultSet.getString("schema_sql"),
                        sourceRowCount(source, name),
                        ImportDisposition.forTable(name)));
            }
        }
        return List.copyOf(result);
    }

    private static long sourceRowCount(Connection source, String tableName) throws SQLException {
        try (var statement = source.createStatement();
                var resultSet = statement.executeQuery("SELECT count(*) FROM \"" + tableName + "\"")) {
            resultSet.next();
            return resultSet.getLong(1);
        }
    }

    private static String schemaHash(List<SourceTable> tables) {
        var digest = Hashing.sha256Digest();
        tables.stream().sorted(Comparator.comparing(SourceTable::name)).forEach(table -> {
            digest.update(table.name().getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            digest.update(table.schemaSql().getBytes(StandardCharsets.UTF_8));
            digest.update((byte) '\n');
        });
        return Hashing.finish(digest);
    }

    private ImportResult completedResult(Connection target, String sourceHash) throws SQLException {
        try (var statement = target.prepareStatement("""
                SELECT import_id, status, source_table_count, source_row_count,
                       archived_row_count, transformed_row_count
                FROM legacy_import_manifest
                WHERE source_sha256 = ? AND status = 'COMPLETED'
                """)) {
            statement.setString(1, sourceHash);
            try (var resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return null;
                }
                return new ImportResult(
                        resultSet.getString("import_id"),
                        resultSet.getString("status"),
                        sourceHash,
                        resultSet.getInt("source_table_count"),
                        resultSet.getLong("source_row_count"),
                        resultSet.getLong("archived_row_count"),
                        resultSet.getLong("transformed_row_count"),
                        true);
            }
        }
    }

    private void startManifest(
            Connection target,
            String importId,
            String sourceHash,
            String schemaHash) throws SQLException, IOException {
        try (var statement = target.prepareStatement("""
                INSERT INTO legacy_import_manifest (
                    import_id, source_format, source_name, source_sha256, source_byte_size,
                    schema_sha256, tool_version, status, started_at
                ) VALUES (?, 'SQLITE', ?, ?, ?, ?, ?, 'RUNNING', CURRENT_TIMESTAMP)
                ON CONFLICT (source_sha256) DO UPDATE
                SET status = 'RUNNING', schema_sha256 = EXCLUDED.schema_sha256,
                    tool_version = EXCLUDED.tool_version, started_at = CURRENT_TIMESTAMP,
                    completed_at = NULL, error_summary = NULL
                """)) {
            statement.setString(1, importId);
            statement.setString(2, configuration.source().getFileName().toString());
            statement.setString(3, sourceHash);
            statement.setLong(4, Files.size(configuration.source()));
            statement.setString(5, schemaHash);
            statement.setString(6, TOOL_VERSION);
            statement.executeUpdate();
        }
        target.commit();
    }

    private void importTable(
            Connection source,
            Connection target,
            String importId,
            SourceTable table) throws SQLException {
        resetTable(target, importId, table);
        var tableDigest = Hashing.sha256Digest();
        long archivedRows = 0;
        var selectSql = "SELECT rowid AS \"__finbot_rowid\", * FROM \"" + table.name() + "\" ORDER BY rowid";
        try (var select = source.createStatement();
                var rows = select.executeQuery(selectSql);
                var insert = target.prepareStatement("""
                        INSERT INTO legacy_archive_row (
                            import_id, source_table, row_ordinal, source_row_key, content, content_sha256
                        ) VALUES (?, ?, ?, ?, ?::jsonb, ?)
                        ON CONFLICT (import_id, source_table, row_ordinal) DO NOTHING
                        """)) {
            while (rows.next()) {
                var row = rowCodec.read(rows);
                tableDigest.update(row.json().getBytes(StandardCharsets.UTF_8));
                tableDigest.update((byte) '\n');
                bindArchiveRow(insert, importId, table.name(), row);
                insert.addBatch();
                archivedRows++;
                if (archivedRows % configuration.batchSize() == 0) {
                    insert.executeBatch();
                }
            }
            insert.executeBatch();
        }
        var actualRows = archivedRowCount(target, importId, table.name());
        if (actualRows != table.rowCount() || archivedRows != table.rowCount()) {
            throw new IllegalStateException(
                    "Legacy row reconciliation failed for " + table.name()
                            + ": source=" + table.rowCount() + ", read=" + archivedRows
                            + ", archived=" + actualRows);
        }
        completeTable(target, importId, table.name(), actualRows, Hashing.finish(tableDigest));
        target.commit();
    }

    private static void bindArchiveRow(
            java.sql.PreparedStatement statement,
            String importId,
            String tableName,
            CanonicalRow row) throws SQLException {
        statement.setString(1, importId);
        statement.setString(2, tableName);
        statement.setLong(3, row.ordinal());
        statement.setString(4, row.sourceKey());
        statement.setString(5, row.json());
        statement.setString(6, row.sha256());
    }

    private static void resetTable(Connection target, String importId, SourceTable table) throws SQLException {
        try (var delete = target.prepareStatement(
                        "DELETE FROM legacy_import_table WHERE import_id = ? AND source_table = ?")) {
            delete.setString(1, importId);
            delete.setString(2, table.name());
            delete.executeUpdate();
        }
        try (var insert = target.prepareStatement("""
                INSERT INTO legacy_import_table (
                    import_id, source_table, disposition, target_entity, source_row_count,
                    status, started_at
                ) VALUES (?, ?, ?, ?, ?, 'RUNNING', CURRENT_TIMESTAMP)
                """)) {
            insert.setString(1, importId);
            insert.setString(2, table.name());
            insert.setString(3, table.disposition().name());
            insert.setString(4, table.disposition().targetEntity());
            insert.setLong(5, table.rowCount());
            insert.executeUpdate();
        }
    }

    private static long archivedRowCount(Connection target, String importId, String tableName)
            throws SQLException {
        try (var statement = target.prepareStatement("""
                SELECT count(*) FROM legacy_archive_row WHERE import_id = ? AND source_table = ?
                """)) {
            statement.setString(1, importId);
            statement.setString(2, tableName);
            try (var resultSet = statement.executeQuery()) {
                resultSet.next();
                return resultSet.getLong(1);
            }
        }
    }

    private static boolean isTableCompleted(
            Connection target,
            String importId,
            SourceTable table) throws SQLException {
        try (var statement = target.prepareStatement("""
                SELECT source_row_count, archived_row_count
                FROM legacy_import_table
                WHERE import_id = ? AND source_table = ? AND status = 'COMPLETED'
                """)) {
            statement.setString(1, importId);
            statement.setString(2, table.name());
            try (var resultSet = statement.executeQuery()) {
                return resultSet.next()
                        && resultSet.getLong("source_row_count") == table.rowCount()
                        && resultSet.getLong("archived_row_count") == table.rowCount();
            }
        }
    }

    private static void completeTable(
            Connection target,
            String importId,
            String tableName,
            long archivedRows,
            String contentHash) throws SQLException {
        try (var statement = target.prepareStatement("""
                UPDATE legacy_import_table
                SET archived_row_count = ?, content_sha256 = ?, status = 'COMPLETED',
                    completed_at = CURRENT_TIMESTAMP, error_summary = NULL
                WHERE import_id = ? AND source_table = ?
                """)) {
            statement.setLong(1, archivedRows);
            statement.setString(2, contentHash);
            statement.setString(3, importId);
            statement.setString(4, tableName);
            statement.executeUpdate();
        }
    }

    private static void updateTransformCounts(
            Connection target,
            String importId,
            Map<String, Long> transformed) throws SQLException {
        try (var statement = target.prepareStatement("""
                UPDATE legacy_import_table
                SET transformed_row_count = ?
                WHERE import_id = ? AND source_table = ?
                """)) {
            for (var entry : transformed.entrySet()) {
                statement.setLong(1, entry.getValue());
                statement.setString(2, importId);
                statement.setString(3, entry.getKey());
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    private static ImportResult completeManifest(
            Connection target,
            String importId,
            String sourceHash,
            int tableCount) throws SQLException {
        try (var statement = target.prepareStatement("""
                UPDATE legacy_import_manifest manifest
                SET status = 'COMPLETED', completed_at = CURRENT_TIMESTAMP,
                    source_table_count = ?,
                    source_row_count = summary.source_rows,
                    archived_row_count = summary.archived_rows,
                    transformed_row_count = summary.transformed_rows,
                    error_summary = NULL
                FROM (
                    SELECT coalesce(sum(source_row_count), 0) AS source_rows,
                           coalesce(sum(archived_row_count), 0) AS archived_rows,
                           coalesce(sum(transformed_row_count), 0) AS transformed_rows
                    FROM legacy_import_table WHERE import_id = ?
                ) summary
                WHERE manifest.import_id = ?
                RETURNING manifest.source_row_count, manifest.archived_row_count,
                          manifest.transformed_row_count
                """)) {
            statement.setInt(1, tableCount);
            statement.setString(2, importId);
            statement.setString(3, importId);
            try (var resultSet = statement.executeQuery()) {
                resultSet.next();
                var sourceRows = resultSet.getLong("source_row_count");
                var archivedRows = resultSet.getLong("archived_row_count");
                if (sourceRows != archivedRows) {
                    throw new IllegalStateException(
                            "Import manifest reconciliation failed: source=" + sourceRows
                                    + ", archived=" + archivedRows);
                }
                return new ImportResult(
                        importId,
                        "COMPLETED",
                        sourceHash,
                        tableCount,
                        sourceRows,
                        archivedRows,
                        resultSet.getLong("transformed_row_count"),
                        false);
            }
        }
    }

    private static void markFailed(Connection target, String importId, Exception exception) throws SQLException {
        var message = safeError(exception);
        try (var statement = target.prepareStatement("""
                UPDATE legacy_import_manifest
                SET status = 'FAILED', completed_at = CURRENT_TIMESTAMP, error_summary = ?
                WHERE import_id = ?
                """)) {
            statement.setString(1, message);
            statement.setString(2, importId);
            statement.executeUpdate();
        }
        target.commit();
    }

    private static void rollbackWithoutMasking(Connection target, Exception original) {
        try {
            if (!target.isClosed()) {
                target.rollback();
            }
        } catch (SQLException rollbackFailure) {
            original.addSuppressed(rollbackFailure);
        }
    }

    private void markFailedWithoutMasking(String importId, Exception original) {
        try (var failureTarget = openTarget()) {
            markFailed(failureTarget, importId, original);
        } catch (SQLException recordFailure) {
            original.addSuppressed(recordFailure);
        }
    }

    private static String safeError(Exception exception) {
        var message = exception.getMessage();
        var raw = exception.getClass().getSimpleName() + (message == null ? "" : ": " + message);
        var redacted = SENSITIVE_ERROR.matcher(raw).replaceAll("$1=[REDACTED]");
        return redacted.substring(0, Math.min(redacted.length(), 2_000));
    }
}
