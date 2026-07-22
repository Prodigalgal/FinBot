package io.omnnu.finbot.infrastructure.research.persistence;

import static io.omnnu.finbot.infrastructure.jdbc.persistence.PostgresJdbcParameters.timestamp;

import io.omnnu.finbot.application.research.dto.ResearchCaseView;
import io.omnnu.finbot.application.research.dto.ResearchSegmentView;
import io.omnnu.finbot.application.research.port.out.ResearchSegmentationStore;
import io.omnnu.finbot.domain.research.ResearchArtifactId;
import io.omnnu.finbot.domain.research.ResearchCaseId;
import io.omnnu.finbot.domain.research.ResearchCaseStatus;
import io.omnnu.finbot.domain.research.ResearchDataPlane;
import io.omnnu.finbot.domain.research.ResearchSegmentId;
import io.omnnu.finbot.domain.research.ResearchSegmentStatus;
import io.omnnu.finbot.domain.research.ResearchSegmentType;
import io.omnnu.finbot.domain.workflow.WorkflowRunId;
import io.omnnu.finbot.domain.workflow.WorkflowTrigger;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public final class JdbcResearchSegmentationStore implements ResearchSegmentationStore {
    private final JdbcClient jdbcClient;

    public JdbcResearchSegmentationStore(JdbcClient jdbcClient) {
        this.jdbcClient = Objects.requireNonNull(jdbcClient, "jdbcClient");
    }

    @Override
    @Transactional
    public void ensureLiveCase(
            ResearchCaseId caseId,
            ResearchSegmentId evidenceSegmentId,
            ResearchSegmentId liveSegmentId,
            WorkflowRunId liveRunId,
            WorkflowTrigger trigger,
            String requestSummary,
            Instant startedAt) {
        jdbcClient.sql("""
                insert into research_case (
                  case_id, request_summary, trigger_type, status,
                  created_at, updated_at
                ) values (
                  :caseId, :requestSummary, :triggerType, 'RUNNING',
                  :startedAt, :startedAt
                ) on conflict (case_id) do nothing
                """)
                .param("caseId", caseId.value())
                .param("requestSummary", requestSummary)
                .param("triggerType", trigger.name())
                .param("startedAt", timestamp(startedAt))
                .update();
        jdbcClient.sql("""
                insert into research_segment (
                  segment_id, case_id, segment_type, data_plane, workflow_run_id,
                  status, created_at, updated_at
                ) values (
                  :segmentId, :caseId, 'EVIDENCE', null, null,
                  'PENDING', :startedAt, :startedAt
                ) on conflict (case_id, segment_type) do nothing
                """)
                .param("segmentId", evidenceSegmentId.value())
                .param("caseId", caseId.value())
                .param("startedAt", timestamp(startedAt))
                .update();
        jdbcClient.sql("""
                insert into research_segment (
                  segment_id, case_id, segment_type, data_plane, workflow_run_id,
                  depends_on_segment_id, status, started_at, created_at, updated_at
                ) values (
                  :segmentId, :caseId, 'LIVE_RESEARCH', 'LIVE', :runId,
                  :evidenceSegmentId, 'RUNNING', :startedAt, :startedAt, :startedAt
                ) on conflict (case_id, segment_type) do nothing
                """)
                .param("segmentId", liveSegmentId.value())
                .param("caseId", caseId.value())
                .param("runId", liveRunId.value())
                .param("evidenceSegmentId", evidenceSegmentId.value())
                .param("startedAt", timestamp(startedAt))
                .update();
        var matching = jdbcClient.sql("""
                select count(*)
                from research_segment
                where case_id = :caseId and segment_type = 'LIVE_RESEARCH'
                  and workflow_run_id = :runId
                """)
                .param("caseId", caseId.value())
                .param("runId", liveRunId.value())
                .query(Integer.class)
                .single();
        if (matching != 1) {
            throw new IllegalStateException("Research case idempotency identity does not match the live run");
        }
    }

    @Override
    @Transactional
    public void recordEvidenceSnapshot(
            WorkflowRunId liveRunId,
            ResearchArtifactId artifactId,
            Instant completedAt) {
        var snapshot = jdbcClient.sql("""
                select segment.case_id, artifact.content_hash
                from research_segment segment
                join research_artifact artifact
                  on artifact.artifact_id = :artifactId
                 and artifact.workflow_run_id = :runId
                 and artifact.artifact_type = 'COMPRESSION_PACKAGE'
                where segment.workflow_run_id = :runId
                  and segment.segment_type = 'LIVE_RESEARCH'
                """)
                .param("artifactId", artifactId.value())
                .param("runId", liveRunId.value())
                .query((resultSet, rowNumber) -> new SnapshotIdentity(
                        new ResearchCaseId(resultSet.getString("case_id")),
                        resultSet.getString("content_hash")))
                .optional()
                .orElseThrow(() -> new IllegalStateException(
                        "Compression artifact does not belong to the live research case"));
        var caseUpdated = jdbcClient.sql("""
                update research_case
                set evidence_artifact_id = :artifactId, updated_at = :completedAt
                where case_id = :caseId
                  and (evidence_artifact_id is null or evidence_artifact_id = :artifactId)
                """)
                .param("artifactId", artifactId.value())
                .param("caseId", snapshot.caseId().value())
                .param("completedAt", timestamp(completedAt))
                .update();
        if (caseUpdated != 1) {
            throw new IllegalStateException("Research case is already bound to another evidence artifact");
        }
        var evidenceUpdated = jdbcClient.sql("""
                update research_segment
                set status = 'COMPLETED', evidence_artifact_id = :artifactId,
                    started_at = coalesce(started_at, created_at),
                    completed_at = :completedAt, updated_at = :completedAt
                where case_id = :caseId and segment_type = 'EVIDENCE'
                  and status in ('PENDING', 'RUNNING', 'COMPLETED')
                """)
                .param("artifactId", artifactId.value())
                .param("caseId", snapshot.caseId().value())
                .param("completedAt", timestamp(completedAt))
                .update();
        if (evidenceUpdated != 1) {
            throw new IllegalStateException("Evidence segment cannot accept the compression artifact");
        }
        var liveUpdated = jdbcClient.sql("""
                update research_segment
                set evidence_artifact_id = :artifactId, updated_at = :completedAt
                where workflow_run_id = :runId
                  and (evidence_artifact_id is null or evidence_artifact_id = :artifactId)
                """)
                .param("artifactId", artifactId.value())
                .param("runId", liveRunId.value())
                .param("completedAt", timestamp(completedAt))
                .update();
        if (liveUpdated != 1) {
            throw new IllegalStateException("Live research segment cannot accept the evidence artifact");
        }
        bind(liveRunId, snapshot.caseId(), artifactId, snapshot.contentHash(), completedAt);
    }

    @Override
    @Transactional
    public void transitionEvidence(
            WorkflowRunId liveRunId,
            ResearchSegmentStatus status,
            String errorCode,
            String errorMessage,
            Instant changedAt) {
        if (status != ResearchSegmentStatus.SKIPPED && status != ResearchSegmentStatus.FAILED) {
            throw new IllegalArgumentException("Evidence may only transition to SKIPPED or FAILED here");
        }
        var failed = status == ResearchSegmentStatus.FAILED;
        if (failed != (errorCode != null && errorMessage != null)) {
            throw new IllegalArgumentException("Failed evidence transitions require an error code and message");
        }
        var changed = jdbcClient.sql("""
                update research_segment evidence
                set status = :status, error_code = :errorCode, error_message = :errorMessage,
                    started_at = coalesce(started_at, created_at), completed_at = :changedAt,
                    updated_at = :changedAt
                from research_segment live
                where live.workflow_run_id = :runId
                  and live.segment_type = 'LIVE_RESEARCH'
                  and evidence.case_id = live.case_id
                  and evidence.segment_type = 'EVIDENCE'
                  and evidence.status in ('PENDING', 'RUNNING', :status)
                """)
                .param("status", status.name())
                .param("errorCode", errorCode)
                .param("errorMessage", errorMessage)
                .param("changedAt", timestamp(changedAt))
                .param("runId", liveRunId.value())
                .update();
        if (changed != 1) {
            throw new IllegalStateException("Evidence segment cannot transition to " + status);
        }
    }

    @Override
    @Transactional
    public void registerDemoBranch(
            WorkflowRunId liveRunId,
            ResearchSegmentId demoSegmentId,
            WorkflowRunId demoRunId,
            Instant startedAt) {
        var snapshot = jdbcClient.sql("""
                select live.case_id, evidence.segment_id, artifact.artifact_id, artifact.content_hash
                from research_segment live
                join research_case research_case on research_case.case_id = live.case_id
                join research_segment evidence
                  on evidence.case_id = live.case_id and evidence.segment_type = 'EVIDENCE'
                join research_artifact artifact
                  on artifact.artifact_id = research_case.evidence_artifact_id
                where live.workflow_run_id = :liveRunId
                  and live.segment_type = 'LIVE_RESEARCH'
                  and evidence.status = 'COMPLETED'
                """)
                .param("liveRunId", liveRunId.value())
                .query((resultSet, rowNumber) -> new DemoSnapshot(
                        new ResearchCaseId(resultSet.getString("case_id")),
                        new ResearchSegmentId(resultSet.getString("segment_id")),
                        new ResearchArtifactId(resultSet.getString("artifact_id")),
                        resultSet.getString("content_hash")))
                .optional()
                .orElseThrow(() -> new IllegalStateException(
                        "Demo branch cannot start before the evidence snapshot is complete"));
        jdbcClient.sql("""
                insert into research_segment (
                  segment_id, case_id, segment_type, data_plane, workflow_run_id,
                  depends_on_segment_id, evidence_artifact_id, status,
                  started_at, created_at, updated_at
                ) values (
                  :segmentId, :caseId, 'DEMO_AUTOTRADE', 'PAPER', :demoRunId,
                  :evidenceSegmentId, :artifactId, 'RUNNING',
                  :startedAt, :startedAt, :startedAt
                ) on conflict (case_id, segment_type) do nothing
                """)
                .param("segmentId", demoSegmentId.value())
                .param("caseId", snapshot.caseId().value())
                .param("demoRunId", demoRunId.value())
                .param("evidenceSegmentId", snapshot.evidenceSegmentId().value())
                .param("artifactId", snapshot.artifactId().value())
                .param("startedAt", timestamp(startedAt))
                .update();
        var matching = jdbcClient.sql("""
                select count(*) from research_segment
                where case_id = :caseId and segment_type = 'DEMO_AUTOTRADE'
                  and workflow_run_id = :demoRunId
                  and evidence_artifact_id = :artifactId
                """)
                .param("caseId", snapshot.caseId().value())
                .param("demoRunId", demoRunId.value())
                .param("artifactId", snapshot.artifactId().value())
                .query(Integer.class)
                .single();
        if (matching != 1) {
            throw new IllegalStateException("Demo branch idempotency identity does not match the research case");
        }
        bind(demoRunId, snapshot.caseId(), snapshot.artifactId(), snapshot.contentHash(), startedAt);
    }

    @Override
    @Transactional
    public void transition(
            WorkflowRunId workflowRunId,
            ResearchSegmentStatus status,
            String errorCode,
            String errorMessage,
            Instant changedAt) {
        var failed = status == ResearchSegmentStatus.FAILED;
        if (failed != (errorCode != null && errorMessage != null)) {
            throw new IllegalArgumentException("Failed segment transitions require an error code and message");
        }
        var changed = jdbcClient.sql("""
                update research_segment
                set status = :status,
                    error_code = :errorCode,
                    error_message = :errorMessage,
                    completed_at = case when :terminal then :changedAt else null end,
                    updated_at = :changedAt
                where workflow_run_id = :runId
                  and segment_type in ('LIVE_RESEARCH', 'DEMO_AUTOTRADE')
                """)
                .param("status", status.name())
                .param("errorCode", errorCode)
                .param("errorMessage", errorMessage)
                .param("terminal", terminal(status))
                .param("changedAt", timestamp(changedAt))
                .param("runId", workflowRunId.value())
                .update();
        if (changed != 1) {
            throw new IllegalStateException("Research branch segment is missing");
        }
        recomputeCase(workflowRunId, changedAt);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ResearchCaseView> findByRunId(WorkflowRunId workflowRunId) {
        var root = jdbcClient.sql("""
                select research_case.case_id, research_case.status,
                       research_case.request_summary, research_case.evidence_artifact_id,
                       research_case.created_at, research_case.completed_at,
                       research_case.updated_at
                from research_case
                join research_segment segment on segment.case_id = research_case.case_id
                where segment.workflow_run_id = :runId
                """)
                .param("runId", workflowRunId.value())
                .query((resultSet, rowNumber) -> new CaseRoot(
                        new ResearchCaseId(resultSet.getString("case_id")),
                        ResearchCaseStatus.valueOf(resultSet.getString("status")),
                        resultSet.getString("request_summary"),
                        artifactId(resultSet.getString("evidence_artifact_id")),
                        instant(resultSet.getObject("created_at", OffsetDateTime.class)),
                        nullableInstant(resultSet.getObject("completed_at", OffsetDateTime.class)),
                        instant(resultSet.getObject("updated_at", OffsetDateTime.class))))
                .optional();
        if (root.isEmpty()) {
            return Optional.empty();
        }
        var value = root.orElseThrow();
        var segments = jdbcClient.sql("""
                select segment_id, segment_type, data_plane, workflow_run_id,
                       evidence_artifact_id, status, error_code, error_message,
                       started_at, completed_at
                from research_segment
                where case_id = :caseId
                order by case segment_type
                  when 'EVIDENCE' then 0 when 'LIVE_RESEARCH' then 1 else 2 end
                """)
                .param("caseId", value.caseId().value())
                .query((resultSet, rowNumber) -> new ResearchSegmentView(
                        new ResearchSegmentId(resultSet.getString("segment_id")),
                        ResearchSegmentType.valueOf(resultSet.getString("segment_type")),
                        dataPlane(resultSet.getString("data_plane")),
                        runId(resultSet.getString("workflow_run_id")),
                        artifactId(resultSet.getString("evidence_artifact_id")),
                        ResearchSegmentStatus.valueOf(resultSet.getString("status")),
                        resultSet.getString("error_code"),
                        resultSet.getString("error_message"),
                        nullableInstant(resultSet.getObject("started_at", OffsetDateTime.class)),
                        nullableInstant(resultSet.getObject("completed_at", OffsetDateTime.class))))
                .list();
        return Optional.of(new ResearchCaseView(
                value.caseId(), value.status(), value.requestSummary(), value.evidenceArtifactId(),
                segments, value.createdAt(), value.completedAt(), value.updatedAt()));
    }

    private void bind(
            WorkflowRunId runId,
            ResearchCaseId caseId,
            ResearchArtifactId artifactId,
            String contentHash,
            Instant boundAt) {
        jdbcClient.sql("""
                insert into workflow_evidence_binding (
                  workflow_run_id, case_id, artifact_id, content_hash, bound_at
                ) values (
                  :runId, :caseId, :artifactId, :contentHash, :boundAt
                ) on conflict (workflow_run_id) do nothing
                """)
                .param("runId", runId.value())
                .param("caseId", caseId.value())
                .param("artifactId", artifactId.value())
                .param("contentHash", contentHash)
                .param("boundAt", timestamp(boundAt))
                .update();
        var matching = jdbcClient.sql("""
                select count(*) from workflow_evidence_binding
                where workflow_run_id = :runId and case_id = :caseId
                  and artifact_id = :artifactId and content_hash = :contentHash
                """)
                .param("runId", runId.value())
                .param("caseId", caseId.value())
                .param("artifactId", artifactId.value())
                .param("contentHash", contentHash)
                .query(Integer.class)
                .single();
        if (matching != 1) {
            throw new IllegalStateException("Workflow evidence binding conflicts with an existing identity");
        }
    }

    private void recomputeCase(WorkflowRunId workflowRunId, Instant changedAt) {
        jdbcClient.sql("""
                with target as (
                  select case_id from research_segment where workflow_run_id = :runId
                ), aggregate as (
                  select segment.case_id,
                         count(*) filter (where segment.segment_type <> 'EVIDENCE') as branch_count,
                         count(*) filter (where segment.segment_type <> 'EVIDENCE'
                           and segment.status = 'COMPLETED') as completed_count,
                         count(*) filter (where segment.segment_type <> 'EVIDENCE'
                           and segment.status = 'FAILED') as failed_count,
                         count(*) filter (where segment.segment_type <> 'EVIDENCE'
                           and segment.status not in ('COMPLETED', 'FAILED', 'SKIPPED')) as active_count
                         , count(*) filter (where segment.segment_type = 'EVIDENCE'
                           and segment.status = 'SKIPPED') as evidence_skipped_count
                  from research_segment segment
                  join target on target.case_id = segment.case_id
                  group by segment.case_id
                )
                update research_case research_case
                set status = case
                      when aggregate.active_count > 0 then 'RUNNING'
                      when aggregate.failed_count = 0
                        and (aggregate.branch_count >= 2 or aggregate.evidence_skipped_count = 1)
                        and aggregate.completed_count = aggregate.branch_count then 'COMPLETED'
                      when aggregate.completed_count > 0 and aggregate.failed_count > 0 then 'PARTIAL'
                      when aggregate.failed_count = aggregate.branch_count then 'FAILED'
                      else 'RUNNING'
                    end,
                    completed_at = case
                      when aggregate.active_count = 0
                        and (aggregate.branch_count >= 2
                          or aggregate.evidence_skipped_count = 1
                          or aggregate.failed_count = aggregate.branch_count) then :changedAt
                      else null
                    end,
                    updated_at = :changedAt
                from aggregate
                where research_case.case_id = aggregate.case_id
                """)
                .param("runId", workflowRunId.value())
                .param("changedAt", timestamp(changedAt))
                .update();
    }

    private static boolean terminal(ResearchSegmentStatus status) {
        return status == ResearchSegmentStatus.COMPLETED
                || status == ResearchSegmentStatus.FAILED
                || status == ResearchSegmentStatus.SKIPPED;
    }

    private static ResearchArtifactId artifactId(String value) {
        return value == null ? null : new ResearchArtifactId(value);
    }

    private static WorkflowRunId runId(String value) {
        return value == null ? null : new WorkflowRunId(value);
    }

    private static ResearchDataPlane dataPlane(String value) {
        return value == null ? null : ResearchDataPlane.valueOf(value);
    }

    private static Instant instant(OffsetDateTime value) {
        return Objects.requireNonNull(value, "database timestamp").toInstant();
    }

    private static Instant nullableInstant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }

    private record SnapshotIdentity(ResearchCaseId caseId, String contentHash) {
    }

    private record DemoSnapshot(
            ResearchCaseId caseId,
            ResearchSegmentId evidenceSegmentId,
            ResearchArtifactId artifactId,
            String contentHash) {
    }

    private record CaseRoot(
            ResearchCaseId caseId,
            ResearchCaseStatus status,
            String requestSummary,
            ResearchArtifactId evidenceArtifactId,
            Instant createdAt,
            Instant completedAt,
            Instant updatedAt) {
    }
}
