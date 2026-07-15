package io.omnnu.finbot.infrastructure.setup;

import static io.omnnu.finbot.infrastructure.jdbc.PostgresJdbcParameters.timestamp;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.omnnu.finbot.application.setup.SetupProfileApplication;
import io.omnnu.finbot.application.setup.SetupProfileId;
import io.omnnu.finbot.application.setup.SetupProfileRepository;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public final class JdbcSetupProfileRepository implements SetupProfileRepository {
    private final JdbcClient jdbcClient;
    private final ObjectMapper objectMapper;

    public JdbcSetupProfileRepository(JdbcClient jdbcClient, ObjectMapper objectMapper) {
        this.jdbcClient = Objects.requireNonNull(jdbcClient, "jdbcClient");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    @Override
    @Transactional
    public SetupProfileApplication apply(
            String applicationId,
            String idempotencyKey,
            SetupProfileId profileId,
            Map<String, String> values,
            Instant appliedAt) {
        jdbcClient.sql("select pg_advisory_xact_lock(hashtext(:idempotencyKey))")
                .param("idempotencyKey", idempotencyKey)
                .query((resultSet, rowNumber) -> 0)
                .single();
        var existing = findByIdempotencyKey(idempotencyKey);
        if (existing != null) {
            if (existing.profileId() != profileId) {
                throw new IllegalStateException(
                        "Setup profile idempotency key conflicts with another profile");
            }
            return existing;
        }
        var applied = new ArrayList<String>();
        var preserved = new ArrayList<String>();
        var skipped = new ArrayList<String>();
        values.forEach((key, value) -> {
            var stored = jdbcClient.sql("""
                    select value_text, source from system_setting where setting_key = :key
                    """)
                    .param("key", key)
                    .query((resultSet, rowNumber) -> new StoredSetting(
                            resultSet.getString("value_text"),
                            resultSet.getString("source")))
                    .optional();
            if (stored.isEmpty()) {
                skipped.add(key);
                return;
            }
            var setting = stored.orElseThrow();
            if ("USER".equals(setting.source()) || Objects.equals(setting.value(), value)) {
                preserved.add(key);
                return;
            }
            var changed = jdbcClient.sql("""
                    update system_setting
                    set value_text = :value,
                        version = version + 1,
                        updated_at = :appliedAt
                    where setting_key = :key and source = 'DEFAULT'
                    """)
                    .param("key", key)
                    .param("value", value)
                    .param("appliedAt", timestamp(appliedAt))
                    .update();
            (changed == 1 ? applied : preserved).add(key);
        });
        synchronizeUntouchedAutonomousSchedule(values, appliedAt, applied, preserved);
        jdbcClient.sql("""
                insert into setup_profile_application (
                  application_id, idempotency_key, profile_id,
                  applied_keys, preserved_keys, skipped_keys, applied_at
                ) values (
                  :applicationId, :idempotencyKey, :profileId, cast(:applied as jsonb),
                  cast(:preserved as jsonb), cast(:skipped as jsonb), :appliedAt
                )
                """)
                .param("applicationId", applicationId)
                .param("idempotencyKey", idempotencyKey)
                .param("profileId", profileId.name())
                .param("applied", json(applied))
                .param("preserved", json(preserved))
                .param("skipped", json(skipped))
                .param("appliedAt", timestamp(appliedAt))
                .update();
        return new SetupProfileApplication(
                applicationId, profileId, applied, preserved, skipped, appliedAt);
    }

    @Override
    @Transactional(readOnly = true)
    public List<SetupProfileApplication> history(int limit) {
        return jdbcClient.sql("""
                select application_id, profile_id, applied_keys::text, preserved_keys::text,
                       skipped_keys::text, applied_at
                from setup_profile_application
                order by applied_at desc, id desc
                limit :limit
                """)
                .param("limit", limit)
                .query((resultSet, rowNumber) -> new SetupProfileApplication(
                        resultSet.getString("application_id"),
                        SetupProfileId.valueOf(resultSet.getString("profile_id")),
                        strings(resultSet.getString("applied_keys")),
                        strings(resultSet.getString("preserved_keys")),
                        strings(resultSet.getString("skipped_keys")),
                        resultSet.getObject("applied_at", OffsetDateTime.class).toInstant()))
                .list();
    }

    private void synchronizeUntouchedAutonomousSchedule(
            Map<String, String> values,
            Instant appliedAt,
            List<String> applied,
            List<String> preserved) {
        var enabled = Boolean.parseBoolean(values.getOrDefault("autonomous.enabled", "true"));
        var intervalSeconds = Math.toIntExact(Duration.parse(
                values.getOrDefault("autonomous.interval", "PT60M")).toSeconds());
        var changed = jdbcClient.sql("""
                update schedule_definition
                set enabled = :enabled,
                    interval_seconds = :intervalSeconds,
                    next_run_at = case when :enabled then least(next_run_at, :appliedAt) else next_run_at end,
                    version = version + 1,
                    updated_at = :appliedAt
                where schedule_id = 'schedule_autonomous_research' and version = 0
                """)
                .param("enabled", enabled)
                .param("intervalSeconds", intervalSeconds)
                .param("appliedAt", timestamp(appliedAt))
                .update();
        if (changed == 1) {
            applied.add("schedule_autonomous_research");
        } else {
            preserved.add("schedule_autonomous_research");
        }
    }

    private SetupProfileApplication findByIdempotencyKey(String idempotencyKey) {
        return jdbcClient.sql("""
                select application_id, profile_id, applied_keys::text, preserved_keys::text,
                       skipped_keys::text, applied_at
                from setup_profile_application where idempotency_key = :idempotencyKey
                """)
                .param("idempotencyKey", idempotencyKey)
                .query((resultSet, rowNumber) -> new SetupProfileApplication(
                        resultSet.getString("application_id"),
                        SetupProfileId.valueOf(resultSet.getString("profile_id")),
                        strings(resultSet.getString("applied_keys")),
                        strings(resultSet.getString("preserved_keys")),
                        strings(resultSet.getString("skipped_keys")),
                        resultSet.getObject("applied_at", OffsetDateTime.class).toInstant()))
                .optional()
                .orElse(null);
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to encode setup profile audit", exception);
        }
    }

    private List<String> strings(String json) {
        try {
            return List.of(objectMapper.readValue(json, String[].class));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to decode setup profile audit", exception);
        }
    }

    private record StoredSetting(String value, String source) {
    }
}
