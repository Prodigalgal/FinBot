package io.omnnu.finbot.application.workflow.port.out;

import io.omnnu.finbot.application.workflow.dto.ParsedConsensusBallot;
import io.omnnu.finbot.application.workflow.dto.ParsedDebateArtifact;
import io.omnnu.finbot.domain.consensus.AnonymousCandidateId;
import io.omnnu.finbot.domain.consensus.BallotOrientation;
import io.omnnu.finbot.domain.consensus.LogicalRoleKey;
import java.util.List;

public interface SdbScaOutputParser {
    ParsedDebateArtifact parseProposal(String output);

    ParsedDebateArtifact parseCritique(String output);

    ParsedDebateArtifact parseRevision(String output);

    ParsedConsensusBallot parseBallot(
            String output,
            LogicalRoleKey logicalRoleKey,
            BallotOrientation orientation,
            List<AnonymousCandidateId> expectedCandidates);
}
