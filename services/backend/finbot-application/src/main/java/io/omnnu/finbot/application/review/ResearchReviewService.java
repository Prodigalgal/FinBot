package io.omnnu.finbot.application.review;

import io.omnnu.finbot.application.research.ResearchHistoryDetail;
import io.omnnu.finbot.application.research.ResearchHistoryRepository;
import io.omnnu.finbot.application.shared.SortableIdGenerator;
import io.omnnu.finbot.application.workflow.WorkflowNotFoundException;
import io.omnnu.finbot.domain.workflow.WorkflowRunId;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;

public final class ResearchReviewService implements ResearchReviewUseCase {
    private final ResearchHistoryRepository history;
    private final ResearchFeedbackStore feedbackStore;
    private final SortableIdGenerator idGenerator;
    private final Clock clock;

    public ResearchReviewService(
            ResearchHistoryRepository history,
            ResearchFeedbackStore feedbackStore,
            SortableIdGenerator idGenerator,
            Clock clock) {
        this.history = Objects.requireNonNull(history, "history");
        this.feedbackStore = Objects.requireNonNull(feedbackStore, "feedbackStore");
        this.idGenerator = Objects.requireNonNull(idGenerator, "idGenerator");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public ResearchComparison compare(WorkflowRunId leftRunId, WorkflowRunId rightRunId) {
        if (leftRunId.equals(rightRunId)) {
            throw new IllegalArgumentException("Choose two different research runs to compare");
        }
        var left = detail(leftRunId);
        var right = detail(rightRunId);
        return new ResearchComparison(
                left.summary(),
                right.summary(),
                right.summary().inputTokens() - left.summary().inputTokens(),
                right.summary().outputTokens() - left.summary().outputTokens(),
                right.summary().costUsd().subtract(left.summary().costUsd()),
                durationDelta(left.summary(), right.summary()),
                conclusion(left),
                conclusion(right),
                compareNodes(left, right));
    }

    @Override
    public List<ResearchFeedback> feedback(int limit) {
        if (limit < 1 || limit > 200) {
            throw new IllegalArgumentException("limit must be between 1 and 200");
        }
        return feedbackStore.list(limit);
    }

    @Override
    public ResearchFeedback saveFeedback(
            WorkflowRunId workflowRunId,
            ResearchFeedbackRating rating,
            ResearchEffectiveness effectiveness,
            String note,
            Long expectedVersion) {
        detail(workflowRunId);
        var existing = feedbackStore.find(workflowRunId);
        if (existing.isPresent() && expectedVersion == null) {
            throw new IllegalArgumentException("expectedVersion is required when updating feedback");
        }
        if (existing.isEmpty() && expectedVersion != null) {
            throw new IllegalArgumentException("expectedVersion must be omitted when creating feedback");
        }
        return feedbackStore.save(
                existing.map(ResearchFeedback::feedbackId)
                        .orElseGet(() -> idGenerator.next("feedback_")),
                workflowRunId,
                rating,
                effectiveness,
                note,
                expectedVersion,
                clock.instant());
    }

    private ResearchHistoryDetail detail(WorkflowRunId runId) {
        return history.find(runId).orElseThrow(() -> new WorkflowNotFoundException(runId.value()));
    }

    private static Long durationDelta(
            ResearchHistoryDetail.Summary left,
            ResearchHistoryDetail.Summary right) {
        var leftDuration = duration(left);
        var rightDuration = duration(right);
        return leftDuration == null || rightDuration == null ? null : rightDuration - leftDuration;
    }

    private static Long duration(ResearchHistoryDetail.Summary summary) {
        if (summary.startedAt() == null || summary.completedAt() == null) {
            return null;
        }
        return Duration.between(summary.startedAt(), summary.completedAt()).toSeconds();
    }

    private static String conclusion(ResearchHistoryDetail detail) {
        return detail.agentTurns().stream()
                .filter(turn -> "CHAIR_VERDICT".equals(turn.messageType()))
                .reduce((ignored, latest) -> latest)
                .map(ResearchHistoryDetail.AgentTurn::summary)
                .orElseGet(() -> detail.agentTurns().stream()
                        .reduce((ignored, latest) -> latest)
                        .map(ResearchHistoryDetail.AgentTurn::summary)
                        .orElse("尚无可比较结论"));
    }

    private static List<ResearchComparison.NodeComparison> compareNodes(
            ResearchHistoryDetail left,
            ResearchHistoryDetail right) {
        var keyed = new LinkedHashMap<String, Pair>();
        left.checkpoints().forEach(value -> keyed
                .computeIfAbsent(key(value), ignored -> new Pair())
                .left = value);
        right.checkpoints().forEach(value -> keyed
                .computeIfAbsent(key(value), ignored -> new Pair())
                .right = value);
        var comparisons = new ArrayList<ResearchComparison.NodeComparison>();
        keyed.forEach((ignored, pair) -> {
            var leftValue = pair.left;
            var rightValue = pair.right;
            var nodeId = leftValue == null ? rightValue.nodeId() : leftValue.nodeId();
            var round = leftValue == null ? rightValue.round() : leftValue.round();
            var leftStatus = leftValue == null ? "MISSING" : leftValue.status();
            var rightStatus = rightValue == null ? "MISSING" : rightValue.status();
            var leftSummary = leftValue == null ? null : leftValue.resultSummary();
            var rightSummary = rightValue == null ? null : rightValue.resultSummary();
            comparisons.add(new ResearchComparison.NodeComparison(
                    nodeId,
                    round,
                    leftStatus,
                    rightStatus,
                    leftSummary,
                    rightSummary,
                    !Objects.equals(leftStatus, rightStatus)
                            || !Objects.equals(leftSummary, rightSummary)));
        });
        return List.copyOf(comparisons);
    }

    private static String key(ResearchHistoryDetail.Checkpoint checkpoint) {
        return checkpoint.nodeId() + ':' + checkpoint.round();
    }

    private static final class Pair {
        private ResearchHistoryDetail.Checkpoint left;
        private ResearchHistoryDetail.Checkpoint right;
    }
}
