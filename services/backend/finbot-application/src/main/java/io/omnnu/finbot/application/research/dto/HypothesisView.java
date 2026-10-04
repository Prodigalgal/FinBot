package io.omnnu.finbot.application.research.dto;

import io.omnnu.finbot.domain.research.HypothesisStatus;
import io.omnnu.finbot.domain.research.OpportunityHypothesis;
import java.time.Instant;

public record HypothesisView(String hypothesisId, String workflowRunId, String instrumentId, String symbol,
        String sourceArtifactId, OpportunityHypothesis initialHypothesis, OpportunityHypothesis hypothesis, String initialForecastJson,
        HypothesisStatus status, Instant firstSeenAt, Instant informationCutoff, Instant recordedAt,
        Instant expiresAt, long version, String consensusStatus, boolean selected) { }
