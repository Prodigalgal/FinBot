package io.omnnu.finbot.infrastructure.operations.persistence;

import static io.omnnu.finbot.infrastructure.jdbc.persistence.PostgresJdbcParameters.timestamp;

import io.omnnu.finbot.application.operations.dto.OperationsOverview;
import io.omnnu.finbot.application.operations.port.out.OperationsRepository;
import io.omnnu.finbot.application.operations.exception.ScheduleConfigurationConflictException;
import io.omnnu.finbot.domain.operations.BackgroundTaskStatus;
import io.omnnu.finbot.domain.operations.BackgroundTaskType;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.EnumMap;
import java.util.Objects;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public final class JdbcOperationsRepository implements OperationsRepository {
    private final JdbcClient jdbcClient;

    public JdbcOperationsRepository(JdbcClient jdbcClient) {
        this.jdbcClient = Objects.requireNonNull(jdbcClient, "jdbcClient");
    }

    @Override
    @Transactional(readOnly = true)
    public OperationsOverview overview(Instant generatedAt) {
        var taskCounts = new EnumMap<BackgroundTaskStatus, Long>(BackgroundTaskStatus.class);
        for (var status : BackgroundTaskStatus.values()) {
            taskCounts.put(status, 0L);
        }
        jdbcClient.sql("select status, count(*) as task_count from background_task group by status")
                .query((resultSet, rowNumber) -> new TaskCount(
                        BackgroundTaskStatus.valueOf(resultSet.getString("status")),
                        resultSet.getLong("task_count")))
                .list()
                .forEach(count -> taskCounts.put(count.status(), count.count()));
        var schedules = jdbcClient.sql("""
                select schedule_id, display_name, task_type, enabled, interval_seconds,
                       priority, maximum_attempts, next_run_at, last_scheduled_at,
                       version, updated_at
                from schedule_definition order by task_type, schedule_id
                """)
                .query((resultSet, rowNumber) -> schedule(resultSet))
                .list();
        var workers = jdbcClient.sql("""
                select worker_id, instance_name, status, started_at, heartbeat_at, stopped_at
                from worker_instance order by heartbeat_at desc, id desc limit 50
                """)
                .query((resultSet, rowNumber) -> new OperationsOverview.Worker(
                        resultSet.getString("worker_id"),
                        resultSet.getString("instance_name"),
                        resultSet.getString("status"),
                        instant(resultSet, "started_at"),
                        instant(resultSet, "heartbeat_at"),
                        instant(resultSet, "stopped_at")))
                .list();
        var legacyImports = jdbcClient.sql("""
                select import_id, source_name, source_sha256, status, source_table_count,
                       source_row_count, archived_row_count, transformed_row_count,
                       started_at, completed_at, error_summary
                from legacy_import_manifest
                order by started_at desc, import_id desc
                limit 20
                """)
                .query((resultSet, rowNumber) -> new OperationsOverview.LegacyImport(
                        resultSet.getString("import_id"),
                        resultSet.getString("source_name"),
                        resultSet.getString("source_sha256"),
                        resultSet.getString("status"),
                        resultSet.getInt("source_table_count"),
                        resultSet.getLong("source_row_count"),
                        resultSet.getLong("archived_row_count"),
                        resultSet.getLong("transformed_row_count"),
                        instant(resultSet, "started_at"),
                        instant(resultSet, "completed_at"),
                        resultSet.getString("error_summary")))
                .list();
        return new OperationsOverview(taskCounts, schedules, workers, legacyImports, generatedAt);
    }

    @Override
    @Transactional
    public OperationsOverview.Schedule updateSchedule(
            String scheduleId,
            boolean enabled,
            int intervalSeconds,
            long expectedVersion,
            Instant updatedAt) {
        if (intervalSeconds < 10 || intervalSeconds > 2_592_000) {
            throw new IllegalArgumentException("intervalSeconds must be between 10 and 2592000");
        }
        var changed = jdbcClient.sql("""
                update schedule_definition
                set enabled = :enabled,
                    interval_seconds = :intervalSeconds,
                    next_run_at = case
                      when :enabled and not enabled then :updatedAt
                      else next_run_at
                    end,
                    version = version + 1,
                    updated_at = :updatedAt
                where schedule_id = :scheduleId and version = :expectedVersion
                """)
                .param("scheduleId", scheduleId)
                .param("enabled", enabled)
                .param("intervalSeconds", intervalSeconds)
                .param("expectedVersion", expectedVersion)
                .param("updatedAt", timestamp(updatedAt))
                .update();
        if (changed != 1) {
            throw new ScheduleConfigurationConflictException(
                    "Schedule changed since it was loaded or no longer exists");
        }
        return jdbcClient.sql("""
                select schedule_id, display_name, task_type, enabled, interval_seconds,
                       priority, maximum_attempts, next_run_at, last_scheduled_at,
                       version, updated_at
                from schedule_definition where schedule_id = :scheduleId
                """)
                .param("scheduleId", scheduleId)
                .query((resultSet, rowNumber) -> schedule(resultSet))
                .single();
    }

    private static OperationsOverview.Schedule schedule(ResultSet resultSet) throws SQLException {
        return new OperationsOverview.Schedule(
                resultSet.getString("schedule_id"),
                resultSet.getString("display_name"),
                BackgroundTaskType.valueOf(resultSet.getString("task_type")),
                resultSet.getBoolean("enabled"),
                resultSet.getInt("interval_seconds"),
                resultSet.getInt("priority"),
                resultSet.getInt("maximum_attempts"),
                instant(resultSet, "next_run_at"),
                instant(resultSet, "last_scheduled_at"),
                resultSet.getLong("version"),
                instant(resultSet, "updated_at"));
    }

    private static Instant instant(ResultSet resultSet, String column) throws SQLException {
        var value = resultSet.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private record TaskCount(BackgroundTaskStatus status, long count) {
    }
}
