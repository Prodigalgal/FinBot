package io.omnnu.finbot.infrastructure.experiment.persistence;

import static io.omnnu.finbot.infrastructure.jdbc.persistence.PostgresJdbcParameters.timestamp;

import io.omnnu.finbot.application.experiment.dto.AiExperiment;
import io.omnnu.finbot.application.experiment.port.out.AiExperimentRepository;
import io.omnnu.finbot.application.experiment.dto.AiExperimentStatus;
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
public final class JdbcAiExperimentRepository implements AiExperimentRepository {
    private static final String SELECT = """
            select experiment.experiment_id, experiment.display_name, experiment.status,
                   experiment.control_workflow_version_id, experiment.candidate_workflow_version_id,
                   experiment.candidate_allocation_basis_points, experiment.evaluation_metric,
                   experiment.minimum_sample_size, experiment.version,
                   experiment.created_at, experiment.updated_at,
                   (select count(*) from workflow_run run
                    where run.ai_experiment_id = experiment.experiment_id
                      and run.ai_experiment_variant = 'CONTROL') as control_samples,
                   (select count(*) from workflow_run run
                    where run.ai_experiment_id = experiment.experiment_id
                      and run.ai_experiment_variant = 'CANDIDATE') as candidate_samples
            from ai_experiment experiment
            """;

    private final JdbcClient jdbcClient;

    public JdbcAiExperimentRepository(JdbcClient jdbcClient) {
        this.jdbcClient = Objects.requireNonNull(jdbcClient, "jdbcClient");
    }

    @Override
    @Transactional(readOnly = true)
    public List<AiExperiment> list() {
        return jdbcClient.sql(SELECT + " order by experiment.updated_at desc, experiment.id desc")
                .query((resultSet, rowNumber) -> experiment(resultSet))
                .list();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<AiExperiment> find(String experimentId) {
        return jdbcClient.sql(SELECT + " where experiment.experiment_id = :experimentId")
                .param("experimentId", experimentId)
                .query((resultSet, rowNumber) -> experiment(resultSet))
                .optional();
    }

    @Override
    @Transactional
    public AiExperiment create(AiExperiment experiment) {
        jdbcClient.sql("""
                insert into ai_experiment (
                  experiment_id, display_name, status, control_workflow_version_id,
                  candidate_workflow_version_id, candidate_allocation_basis_points,
                  evaluation_metric, minimum_sample_size, version, created_at, updated_at
                ) values (
                  :experimentId, :displayName, :status, :controlVersionId,
                  :candidateVersionId, :allocation, :metric, :minimumSampleSize,
                  0, :createdAt, :updatedAt
                )
                """)
                .param("experimentId", experiment.experimentId())
                .param("displayName", experiment.displayName())
                .param("status", experiment.status().name())
                .param("controlVersionId", experiment.controlWorkflowVersionId())
                .param("candidateVersionId", experiment.candidateWorkflowVersionId())
                .param("allocation", experiment.candidateAllocationBasisPoints())
                .param("metric", experiment.evaluationMetric())
                .param("minimumSampleSize", experiment.minimumSampleSize())
                .param("createdAt", timestamp(experiment.createdAt()))
                .param("updatedAt", timestamp(experiment.updatedAt()))
                .update();
        return find(experiment.experimentId()).orElseThrow();
    }

    @Override
    @Transactional
    public Optional<AiExperiment> update(
            AiExperiment experiment,
            long expectedVersion,
            Instant updatedAt) {
        var changed = jdbcClient.sql("""
                update ai_experiment
                set display_name = :displayName,
                    status = :status,
                    control_workflow_version_id = :controlVersionId,
                    candidate_workflow_version_id = :candidateVersionId,
                    candidate_allocation_basis_points = :allocation,
                    evaluation_metric = :metric,
                    minimum_sample_size = :minimumSampleSize,
                    version = version + 1,
                    updated_at = :updatedAt
                where experiment_id = :experimentId and version = :expectedVersion
                """)
                .param("experimentId", experiment.experimentId())
                .param("displayName", experiment.displayName())
                .param("status", experiment.status().name())
                .param("controlVersionId", experiment.controlWorkflowVersionId())
                .param("candidateVersionId", experiment.candidateWorkflowVersionId())
                .param("allocation", experiment.candidateAllocationBasisPoints())
                .param("metric", experiment.evaluationMetric())
                .param("minimumSampleSize", experiment.minimumSampleSize())
                .param("expectedVersion", expectedVersion)
                .param("updatedAt", timestamp(updatedAt))
                .update();
        return changed == 1 ? find(experiment.experimentId()) : Optional.empty();
    }

    @Override
    @Transactional(readOnly = true)
    public boolean hasRunningOverlap(
            String excludedExperimentId,
            String controlWorkflowVersionId,
            String candidateWorkflowVersionId) {
        return jdbcClient.sql("""
                select exists (
                  select 1 from ai_experiment
                  where status = 'RUNNING'
                    and experiment_id <> coalesce(:excludedExperimentId, '')
                    and (
                      control_workflow_version_id in (:controlVersionId, :candidateVersionId)
                      or candidate_workflow_version_id in (:controlVersionId, :candidateVersionId)
                    )
                )
                """)
                .param("excludedExperimentId", excludedExperimentId)
                .param("controlVersionId", controlWorkflowVersionId)
                .param("candidateVersionId", candidateWorkflowVersionId)
                .query(Boolean.class)
                .single();
    }

    private static AiExperiment experiment(ResultSet resultSet) throws SQLException {
        return new AiExperiment(
                resultSet.getString("experiment_id"),
                resultSet.getString("display_name"),
                AiExperimentStatus.valueOf(resultSet.getString("status")),
                resultSet.getString("control_workflow_version_id"),
                resultSet.getString("candidate_workflow_version_id"),
                resultSet.getInt("candidate_allocation_basis_points"),
                resultSet.getString("evaluation_metric"),
                resultSet.getInt("minimum_sample_size"),
                resultSet.getLong("control_samples"),
                resultSet.getLong("candidate_samples"),
                resultSet.getLong("version"),
                instant(resultSet, "created_at"),
                instant(resultSet, "updated_at"));
    }

    private static Instant instant(ResultSet resultSet, String column) throws SQLException {
        return resultSet.getObject(column, OffsetDateTime.class).toInstant();
    }
}
