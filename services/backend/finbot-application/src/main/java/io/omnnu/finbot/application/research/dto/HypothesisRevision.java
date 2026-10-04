package io.omnnu.finbot.application.research.dto;

import io.omnnu.finbot.domain.research.HypothesisStatus;
import io.omnnu.finbot.domain.research.OpportunityHypothesis;
import java.time.Instant;

public record HypothesisRevision(long version, HypothesisStatus status, String reason,
        String sourceArtifactId, OpportunityHypothesis hypothesis, Instant occurredAt) { }
