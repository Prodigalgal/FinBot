package io.omnnu.finbot.application.workflow.port.out;

import io.omnnu.finbot.application.trading.dto.TradeDecisionDraft;
import io.omnnu.finbot.application.workflow.dto.ParsedConsensusBallot;
import io.omnnu.finbot.domain.consensus.AnonymousCandidateId;
import io.omnnu.finbot.domain.consensus.BallotOrientation;
import io.omnnu.finbot.domain.consensus.LogicalRoleKey;
import java.util.List;

public interface ExecutionPanelOutputParser {
    record ParsedExecutionDraft(String canonicalJson, TradeDecisionDraft draft) {}

    ParsedExecutionDraft parseProposal(String output);

    String parseCritique(String output);

    ParsedExecutionDraft parseRevision(String output);

    ParsedConsensusBallot parseBallot(
            String output,
            LogicalRoleKey logicalRoleKey,
            BallotOrientation orientation,
            List<AnonymousCandidateId> expectedCandidates);
}
