package io.omnnu.finbot.infrastructure.operations;

import static io.omnnu.finbot.infrastructure.jdbc.PostgresJdbcParameters.timestamp;

import io.omnnu.finbot.application.operations.BackgroundTask;
import io.omnnu.finbot.application.operations.BackgroundTaskStore;
import io.omnnu.finbot.domain.operations.BackgroundTaskId;
import io.omnnu.finbot.domain.operations.BackgroundTaskStatus;
import io.omnnu.finbot.domain.operations.BackgroundTaskType;
import io.omnnu.finbot.domain.operations.WorkerId;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class JdbcBackgroundTaskStore implements BackgroundTaskStore {
    private static final String TASK_COLUMNS = """
            task_id, task_type, status, priority, idempotency_key, payload::text as payload,
            attempt_count, maximum_attempts, available_at, claimed_at, lease_expires_at,
            claim_owner, heartbeat_at, completed_at, error_code, error_message,
            created_at, updated_at
            """;

    private final JdbcClient jdbcClient;
    private final TaskPayloadCodec payloadCodec;

    public JdbcBackgroundTaskStore(JdbcClient jdbcClient, TaskPayloadCodec payloadCodec) {
        this.jdbcClient = Objects.requireNonNull(jdbcClient, "jdbcClient");
        this.payloadCodec = Objects.requireNonNull(payloadCodec, "payloadCodec");
    }

    @Override
    @Transactional
    public BackgroundTask enqueue(BackgroundTask task) {
        var payload = payloadCodec.encode(task.payload());
        var inserted = jdbcClient.sql("""
                insert into background_task (
                  task_id, task_type, status, priority, idempotency_key, payload,
                  attempt_count, maximum_attempts, available_at, created_at, updated_at
                ) values (
                  :taskId, :taskType, 'PENDING', :priority, :idempotencyKey, cast(:payload as jsonb),
                  0, :maximumAttempts, :availableAt, :createdAt, :updatedAt
                ) on conflict (idempotency_key) do nothing
                """)
                .param("taskId", task.taskId().value())
                .param("taskType", task.taskType().name())
                .param("priority", task.priority())
                .param("idempotencyKey", task.idempotencyKey())
                .param("payload", payload)
                .param("maximumAttempts", task.maximumAttempts())
                .param("availableAt", timestamp(task.availableAt()))
                .param("createdAt", timestamp(task.createdAt()))
                .param("updatedAt", timestamp(task.updatedAt()))
                .update();
        var persisted = inserted == 1
                ? find(task.taskId()).orElseThrow()
                : findByIdempotencyKey(task.idempotencyKey()).orElseThrow();
        if (persisted.taskType() != task.taskType()
                || !payload.equals(payloadCodec.encode(persisted.payload()))) {
            throw new IllegalStateException("Idempotency key was reused with different task input");
        }
        return persisted;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<BackgroundTask> find(BackgroundTaskId taskId) {
        return jdbcClient.sql("select " + TASK_COLUMNS + " from background_task where task_id = :taskId")
                .param("taskId", taskId.value())
                .query((resultSet, rowNumber) -> task(resultSet))
                .optional();
    }

    @Override
    @Transactional(readOnly = true)
    public List<BackgroundTask> list(BackgroundTaskStatus status, int limit) {
        var safeLimit = Math.max(1, Math.min(limit, 200));
        var sql = "select " + TASK_COLUMNS + " from background_task";
        if (status != null) {
            sql += " where status = :status";
        }
        sql += " order by created_at desc, id desc limit :limit";
        var statement = jdbcClient.sql(sql).param("limit", safeLimit);
        if (status != null) {
            statement = statement.param("status", status.name());
        }
        return statement.query((resultSet, rowNumber) -> task(resultSet)).list();
    }

    @Override
    public Optional<BackgroundTask> claimNext(
            WorkerId workerId,
            Instant now,
            Duration leaseDuration,
            Set<BackgroundTaskType> allowedTaskTypes) {
        if (allowedTaskTypes.isEmpty()) {
            return Optional.empty();
        }
        return jdbcClient.sql("""
                with candidate as (
                  select id from background_task
                  where status = 'PENDING' and available_at <= :now
                    and task_type in (:allowedTaskTypes)
                  order by priority desc, available_at, id
                  for update skip locked
                  limit 1
                )
                update background_task task
                set status = 'CLAIMED',
                    attempt_count = attempt_count + 1,
                    claimed_at = :now,
                    lease_expires_at = :leaseExpiresAt,
                    claim_owner = :workerId,
                    heartbeat_at = :now,
                    error_code = null,
                    error_message = null,
                    updated_at = :now
                from candidate
                where task.id = candidate.id
                returning
                """ + TASK_COLUMNS)
                .param("now", timestamp(now))
                .param("leaseExpiresAt", timestamp(now.plus(leaseDuration)))
                .param("workerId", workerId.value())
                .param("allowedTaskTypes", allowedTaskTypes.stream().map(Enum::name).toList())
                .query((resultSet, rowNumber) -> task(resultSet))
                .optional();
    }

    @Override
    @Transactional(readOnly = true)
    public long count(BackgroundTaskStatus status) {
        return jdbcClient.sql("select count(*) from background_task where status = :status")
                .param("status", status.name())
                .query(Long.class)
                .single();
    }

    @Override
    public boolean heartbeat(
            BackgroundTaskId taskId,
            WorkerId workerId,
            Instant now,
            Duration leaseDuration) {
        return jdbcClient.sql("""
                update background_task
                set heartbeat_at = :now,
                    lease_expires_at = :leaseExpiresAt,
                    updated_at = :now
                where task_id = :taskId
                  and status = 'CLAIMED'
                  and claim_owner = :workerId
                  and lease_expires_at >= :now
                """)
                .param("taskId", taskId.value())
                .param("workerId", workerId.value())
                .param("now", timestamp(now))
                .param("leaseExpiresAt", timestamp(now.plus(leaseDuration)))
                .update() == 1;
    }

    @Override
    public boolean complete(BackgroundTaskId taskId, WorkerId workerId, Instant completedAt) {
        return jdbcClient.sql("""
                update background_task
                set status = 'COMPLETED',
                    completed_at = :completedAt,
                    claimed_at = null,
                    lease_expires_at = null,
                    claim_owner = null,
                    heartbeat_at = null,
                    updated_at = :completedAt
                where task_id = :taskId and status = 'CLAIMED' and claim_owner = :workerId
                """)
                .param("taskId", taskId.value())
                .param("workerId", workerId.value())
                .param("completedAt", timestamp(completedAt))
                .update() == 1;
    }

    @Override
    public boolean fail(
            BackgroundTaskId taskId,
            WorkerId workerId,
            String errorCode,
            String errorMessage,
            Instant failedAt,
            Duration retryDelay) {
        return jdbcClient.sql("""
                update background_task
                set status = case when attempt_count < maximum_attempts then 'PENDING' else 'FAILED' end,
                    available_at = case
                      when attempt_count < maximum_attempts then :retryAt else available_at end,
                    completed_at = case
                      when attempt_count < maximum_attempts then null else :failedAt end,
                    error_code = :errorCode,
                    error_message = :errorMessage,
                    claimed_at = null,
                    lease_expires_at = null,
                    claim_owner = null,
                    heartbeat_at = null,
                    updated_at = :failedAt
                where task_id = :taskId and status = 'CLAIMED' and claim_owner = :workerId
                """)
                .param("taskId", taskId.value())
                .param("workerId", workerId.value())
                .param("errorCode", normalizedError(errorCode, 80))
                .param("errorMessage", normalizedError(errorMessage, 2000))
                .param("failedAt", timestamp(failedAt))
                .param("retryAt", timestamp(failedAt.plus(retryDelay)))
                .update() == 1;
    }

    @Override
    public int recoverExpiredLeases(Instant now) {
        return jdbcClient.sql("""
                update background_task
                set status = case when attempt_count < maximum_attempts then 'PENDING' else 'FAILED' end,
                    available_at = case
                      when attempt_count < maximum_attempts then :now else available_at end,
                    completed_at = case
                      when attempt_count < maximum_attempts then null else :now end,
                    error_code = 'LEASE_EXPIRED',
                    error_message = 'Worker lease expired before task completion',
                    claimed_at = null,
                    lease_expires_at = null,
                    claim_owner = null,
                    heartbeat_at = null,
                    updated_at = :now
                where status = 'CLAIMED' and lease_expires_at < :now
                """)
                .param("now", timestamp(now))
                .update();
    }

    @Override
    @Transactional
    public int materializeDueSchedules(
            Instant now,
            int limit,
            Supplier<BackgroundTaskId> taskIdSupplier) {
        var schedules = jdbcClient.sql("""
                select schedule_id, task_type, payload::text as payload, interval_seconds,
                       priority, maximum_attempts, next_run_at
                from schedule_definition
                where enabled = true and next_run_at <= :now
                order by next_run_at, id
                for update skip locked
                limit :limit
                """)
                .param("now", timestamp(now))
                .param("limit", Math.max(1, Math.min(limit, 100)))
                .query((resultSet, rowNumber) -> schedule(resultSet))
                .list();
        var materializedCount = 0;
        for (var schedule : schedules) {
            var taskId = taskIdSupplier.get();
            var idempotencyKey = "schedule:" + schedule.scheduleId() + ":" + schedule.nextRunAt().toEpochMilli();
            payloadCodec.decode(schedule.taskType(), schedule.payload());
            materializedCount += jdbcClient.sql("""
                    insert into background_task (
                      task_id, task_type, status, priority, idempotency_key, payload,
                      attempt_count, maximum_attempts, available_at, created_at, updated_at
                    ) select
                      :taskId, :taskType, 'PENDING', :priority, :idempotencyKey, cast(:payload as jsonb),
                      0, :maximumAttempts, :now, :now, :now
                    where not exists (
                      select 1
                      from background_task active_task
                      where active_task.status in ('PENDING', 'CLAIMED')
                        and left(active_task.idempotency_key, length(:scheduleKeyPrefix)) = :scheduleKeyPrefix
                    )
                    on conflict (idempotency_key) do nothing
                    """)
                    .param("taskId", taskId.value())
                    .param("taskType", schedule.taskType().name())
                    .param("priority", schedule.priority())
                    .param("idempotencyKey", idempotencyKey)
                    .param("scheduleKeyPrefix", "schedule:" + schedule.scheduleId() + ":")
                    .param("payload", schedule.payload())
                    .param("maximumAttempts", schedule.maximumAttempts())
                    .param("now", timestamp(now))
                    .update();
            var nominalNext = schedule.nextRunAt().plusSeconds(schedule.intervalSeconds());
            var recoveryNext = now.plusSeconds(schedule.intervalSeconds());
            var nextRunAt = nominalNext.isAfter(recoveryNext) ? nominalNext : recoveryNext;
            jdbcClient.sql("""
                    update schedule_definition
                    set last_scheduled_at = next_run_at,
                        next_run_at = :nextRunAt,
                        version = version + 1,
                        updated_at = :now
                    where schedule_id = :scheduleId
                    """)
                    .param("scheduleId", schedule.scheduleId())
                    .param("nextRunAt", timestamp(nextRunAt))
                    .param("now", timestamp(now))
                    .update();
        }
        return materializedCount;
    }

    @Override
    public void registerWorker(WorkerId workerId, String instanceName, Instant startedAt) {
        jdbcClient.sql("""
                insert into worker_instance (
                  worker_id, instance_name, status, started_at, heartbeat_at
                ) values (
                  :workerId, :instanceName, 'RUNNING', :startedAt, :startedAt
                ) on conflict (worker_id) do update
                set instance_name = excluded.instance_name,
                    status = 'RUNNING',
                    started_at = excluded.started_at,
                    heartbeat_at = excluded.heartbeat_at,
                    stopped_at = null
                """)
                .param("workerId", workerId.value())
                .param("instanceName", Objects.requireNonNull(instanceName, "instanceName"))
                .param("startedAt", timestamp(startedAt))
                .update();
    }

    @Override
    public void heartbeatWorker(WorkerId workerId, Instant heartbeatAt) {
        jdbcClient.sql("""
                update worker_instance
                set heartbeat_at = :heartbeatAt
                where worker_id = :workerId and status = 'RUNNING'
                """)
                .param("workerId", workerId.value())
                .param("heartbeatAt", timestamp(heartbeatAt))
                .update();
    }

    @Override
    public void stopWorker(WorkerId workerId, Instant stoppedAt) {
        jdbcClient.sql("""
                update worker_instance
                set status = 'STOPPED', stopped_at = :stoppedAt, heartbeat_at = :stoppedAt
                where worker_id = :workerId and status <> 'STOPPED'
                """)
                .param("workerId", workerId.value())
                .param("stoppedAt", timestamp(stoppedAt))
                .update();
    }

    private Optional<BackgroundTask> findByIdempotencyKey(String idempotencyKey) {
        return jdbcClient.sql("select " + TASK_COLUMNS
                        + " from background_task where idempotency_key = :idempotencyKey")
                .param("idempotencyKey", idempotencyKey)
                .query((resultSet, rowNumber) -> task(resultSet))
                .optional();
    }

    private BackgroundTask task(ResultSet resultSet) throws SQLException {
        var type = BackgroundTaskType.valueOf(resultSet.getString("task_type"));
        var claimOwner = resultSet.getString("claim_owner");
        return new BackgroundTask(
                new BackgroundTaskId(resultSet.getString("task_id")),
                type,
                BackgroundTaskStatus.valueOf(resultSet.getString("status")),
                resultSet.getInt("priority"),
                resultSet.getString("idempotency_key"),
                payloadCodec.decode(type, resultSet.getString("payload")),
                resultSet.getInt("attempt_count"),
                resultSet.getInt("maximum_attempts"),
                instant(resultSet.getObject("available_at", OffsetDateTime.class)),
                nullableInstant(resultSet.getObject("claimed_at", OffsetDateTime.class)),
                nullableInstant(resultSet.getObject("lease_expires_at", OffsetDateTime.class)),
                claimOwner == null ? null : new WorkerId(claimOwner),
                nullableInstant(resultSet.getObject("heartbeat_at", OffsetDateTime.class)),
                nullableInstant(resultSet.getObject("completed_at", OffsetDateTime.class)),
                resultSet.getString("error_code"),
                resultSet.getString("error_message"),
                instant(resultSet.getObject("created_at", OffsetDateTime.class)),
                instant(resultSet.getObject("updated_at", OffsetDateTime.class)));
    }

    private static ScheduleRow schedule(ResultSet resultSet) throws SQLException {
        return new ScheduleRow(
                resultSet.getString("schedule_id"),
                BackgroundTaskType.valueOf(resultSet.getString("task_type")),
                resultSet.getString("payload"),
                resultSet.getInt("interval_seconds"),
                resultSet.getInt("priority"),
                resultSet.getInt("maximum_attempts"),
                instant(resultSet.getObject("next_run_at", OffsetDateTime.class)));
    }

    private static String normalizedError(String value, int maximumLength) {
        var normalized = Objects.requireNonNullElse(value, "UNKNOWN").strip();
        if (normalized.isEmpty()) {
            normalized = "UNKNOWN";
        }
        return normalized.substring(0, Math.min(normalized.length(), maximumLength));
    }

    private static Instant instant(OffsetDateTime value) {
        return Objects.requireNonNull(value, "database timestamp").toInstant();
    }

    private static Instant nullableInstant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }

    private record ScheduleRow(
            String scheduleId,
            BackgroundTaskType taskType,
            String payload,
            int intervalSeconds,
            int priority,
            int maximumAttempts,
            Instant nextRunAt) {
    }
}
