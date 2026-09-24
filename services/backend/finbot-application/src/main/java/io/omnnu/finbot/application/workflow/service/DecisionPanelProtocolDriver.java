package io.omnnu.finbot.application.workflow.service;

import io.omnnu.finbot.application.workflow.dto.DebateSession;
import io.omnnu.finbot.application.workflow.dto.DecisionPanelCandidateView;
import io.omnnu.finbot.application.workflow.dto.WorkflowExecutionContext;
import io.omnnu.finbot.domain.consensus.AnonymousCandidateId;
import io.omnnu.finbot.domain.consensus.AnonymousPreferenceBallot;
import io.omnnu.finbot.domain.consensus.BallotOrientation;
import io.omnnu.finbot.domain.consensus.SchulzeDetailedResult;
import io.omnnu.finbot.domain.consensus.SchulzeOutcome;
import io.omnnu.finbot.domain.debate.DebateArtifact;
import io.omnnu.finbot.domain.debate.DebateCandidate;
import io.omnnu.finbot.domain.debate.DecisionPanelKey;
import io.omnnu.finbot.domain.debate.DecisionPanelPurpose;
import io.omnnu.finbot.domain.workflow.WorkflowNodeDefinition;
import java.util.List;
import java.util.Map;
import java.util.Optional;

interface DecisionPanelProtocolDriver<R> {
    DecisionPanelKey panelKey();

    DecisionPanelPurpose purpose();

    SdbScaPhaseExecutor.TaskCommand proposalCommand(
            WorkflowExecutionContext execution,
            WorkflowNodeDefinition node,
            SdbScaIdentityDisclosureGuard identityGuard);

    SdbScaPhaseExecutor.TaskCommand critiqueCommand(
            WorkflowExecutionContext execution,
            WorkflowNodeDefinition node,
            String targetCandidateId,
            DecisionPanelCandidateView targetView,
            SdbScaIdentityDisclosureGuard identityGuard);

    SdbScaPhaseExecutor.TaskCommand revisionCommand(
            WorkflowExecutionContext execution,
            WorkflowNodeDefinition node,
            String ownCandidateId,
            DecisionPanelCandidateView ownView,
            List<String> critiques,
            SdbScaIdentityDisclosureGuard identityGuard);

    SdbScaPhaseExecutor.TaskCommand ballotCommand(
            WorkflowExecutionContext execution,
            WorkflowNodeDefinition node,
            List<DecisionPanelCandidateView> candidateViews,
            BallotOrientation orientation,
            List<AnonymousCandidateId> candidateAliases);

    AnonymousPreferenceBallot parseBallotPreference(
            String artifactContent,
            WorkflowNodeDefinition node,
            BallotOrientation orientation,
            List<AnonymousCandidateId> candidateAliases);

    R reduce(
            WorkflowExecutionContext execution,
            DebateSession session,
            WorkflowNodeDefinition decisionNode,
            List<DebateCandidate> candidates,
            Map<String, DebateArtifact> revisionArtifactsById,
            SchulzeOutcome outcome,
            SchulzeDetailedResult detailed,
            boolean partial);

    R lowQuorumResult(
            WorkflowExecutionContext execution,
            DebateSession session,
            WorkflowNodeDefinition decisionNode,
            List<DebateCandidate> candidates,
            int roleCount);

    Optional<R> recoverCompletedResult(DebateSession session, WorkflowNodeDefinition decisionNode);
}
