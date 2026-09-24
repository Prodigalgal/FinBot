package io.omnnu.finbot.application.workflow.port.out;

import io.omnnu.finbot.application.workflow.dto.ParsedConsensusBallot;
import io.omnnu.finbot.domain.consensus.AnonymousCandidateId;
import io.omnnu.finbot.domain.consensus.BallotOrientation;
import io.omnnu.finbot.domain.consensus.LogicalRoleKey;
import io.omnnu.finbot.domain.debate.PrincipalReviewDecision;
import java.util.List;

public interface PrincipalReviewOutputParser {
    record ParsedPrincipalReview(String canonicalJson, PrincipalReviewDecision decision) {}

    ParsedPrincipalReview parseProposal(String output);

    String parseCritique(String output);

    ParsedPrincipalReview parseRevision(String output);

    ParsedConsensusBallot parseBallot(
            String output,
            LogicalRoleKey logicalRoleKey,
            BallotOrientation orientation,
            List<AnonymousCandidateId> expectedCandidates);
}
