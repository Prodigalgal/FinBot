package io.omnnu.finbot.domain.consensus;

import io.omnnu.finbot.domain.debate.DebatePhaseId;
import io.omnnu.finbot.domain.workflow.DebateId;
import io.omnnu.finbot.domain.workflow.WorkflowNodeId;
import java.time.Instant;
import java.util.Objects;
import java.util.regex.Pattern;

public record ConsensusBallot(
        ConsensusBallotId ballotId,
        DebateId debateId,
        DebatePhaseId phaseId,
        WorkflowNodeId actorNodeId,
        AnonymousPreferenceBallot preference,
        String contentHash,
        Instant createdAt) {
    private static final Pattern SHA_256 = Pattern.compile("[0-9a-f]{64}");

    public ConsensusBallot {
        Objects.requireNonNull(ballotId, "ballotId");
        Objects.requireNonNull(debateId, "debateId");
        Objects.requireNonNull(phaseId, "phaseId");
        Objects.requireNonNull(actorNodeId, "actorNodeId");
        Objects.requireNonNull(preference, "preference");
        contentHash = Objects.requireNonNull(contentHash, "contentHash").strip();
        Objects.requireNonNull(createdAt, "createdAt");
        if (!SHA_256.matcher(contentHash).matches()) {
            throw new IllegalArgumentException("contentHash must be a lowercase SHA-256 value");
        }
    }
}
