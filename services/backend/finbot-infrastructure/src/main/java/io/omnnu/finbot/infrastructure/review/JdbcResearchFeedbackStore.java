package io.omnnu.finbot.infrastructure.review;

import static io.omnnu.finbot.infrastructure.jdbc.PostgresJdbcParameters.timestamp;

import io.omnnu.finbot.application.review.ResearchEffectiveness;
import io.omnnu.finbot.application.review.ResearchFeedback;
import io.omnnu.finbot.application.review.ResearchFeedbackRating;
import io.omnnu.finbot.application.review.ResearchFeedbackStore;
import io.omnnu.finbot.application.workflow.WorkflowManagementConflictException;
import io.omnnu.finbot.domain.workflow.WorkflowRunId;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public final class JdbcResearchFeedbackStore implements ResearchFeedbackStore {
    private final JdbcClient jdbcClient;

    public JdbcResearchFeedbackStore(JdbcClient jdbcClient) {
        this.jdbcClient = Objects.requireNonNull(jdbcClient, "jdbcClient");
    }

    @Override
    @Transactional(readOnly = true)
    public List<ResearchFeedback> list(int limit) {
        return jdbcClient.sql("""
                select feedback_id, workflow_run_id, rating, effectiveness, note,
                       version, created_at, updated_at
                from research_feedback
                order by updated_at desc, id desc
                limit :limit
                """)
                .param("limit", limit)
                .query((resultSet, rowNumber) -> feedback(resultSet))
                .list();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ResearchFeedback> find(WorkflowRunId workflowRunId) {
        return jdbcClient.sql("""
                select feedback_id, workflow_run_id, rating, effectiveness, note,
                       version, created_at, updated_at
                from research_feedback where workflow_run_id = :workflowRunId
                """)
                .param("workflowRunId", workflowRunId.value())
                .query((resultSet, rowNumber) -> feedback(resultSet))
                .optional();
    }

    @Override
    @Transactional
    public ResearchFeedback save(
            String feedbackId,
            WorkflowRunId workflowRunId,
            ResearchFeedbackRating rating,
            ResearchEffectiveness effectiveness,
            String note,
            Long expectedVersion,
            Instant savedAt) {
        if (expectedVersion == null) {
            jdbcClient.sql("""
                    insert into research_feedback (
                      feedback_id, workflow_run_id, rating, effectiveness, note,
                      version, created_at, updated_at
                    ) values (
                      :feedbackId, :workflowRunId, :rating, :effectiveness, :note,
                      0, :savedAt, :savedAt
                    )
                    """)
                    .param("feedbackId", feedbackId)
                    .param("workflowRunId", workflowRunId.value())
                    .param("rating", rating.name())
                    .param("effectiveness", effectiveness.name())
                    .param("note", note == null ? "" : note.strip())
                    .param("savedAt", timestamp(savedAt))
                    .update();
        } else {
            var changed = jdbcClient.sql("""
                    update research_feedback
                    set rating = :rating,
                        effectiveness = :effectiveness,
                        note = :note,
                        version = version + 1,
                        updated_at = :savedAt
                    where workflow_run_id = :workflowRunId and version = :expectedVersion
                    """)
                    .param("workflowRunId", workflowRunId.value())
                    .param("rating", rating.name())
                    .param("effectiveness", effectiveness.name())
                    .param("note", note == null ? "" : note.strip())
                    .param("expectedVersion", expectedVersion)
                    .param("savedAt", timestamp(savedAt))
                    .update();
            if (changed != 1) {
                throw new WorkflowManagementConflictException("研究反馈已被修改，请刷新后重试");
            }
        }
        return find(workflowRunId).orElseThrow();
    }

    private static ResearchFeedback feedback(ResultSet resultSet) throws SQLException {
        return new ResearchFeedback(
                resultSet.getString("feedback_id"),
                resultSet.getString("workflow_run_id"),
                ResearchFeedbackRating.valueOf(resultSet.getString("rating")),
                ResearchEffectiveness.valueOf(resultSet.getString("effectiveness")),
                resultSet.getString("note"),
                resultSet.getLong("version"),
                instant(resultSet, "created_at"),
                instant(resultSet, "updated_at"));
    }

    private static Instant instant(ResultSet resultSet, String column) throws SQLException {
        return Objects.requireNonNull(resultSet.getObject(column, OffsetDateTime.class), column).toInstant();
    }
}
