package io.omnnu.finbot.application.research;

import io.omnnu.finbot.domain.shared.DomainText;
import java.util.List;

public record CompressionContent(
        String summary,
        List<String> keyPoints,
        List<String> risks,
        List<String> missingEvidence,
        List<String> citations) {
    public CompressionContent {
        summary = DomainText.required(summary, "summary", 8_000);
        keyPoints = List.copyOf(keyPoints);
        risks = List.copyOf(risks);
        missingEvidence = List.copyOf(missingEvidence);
        citations = List.copyOf(citations);
        if (keyPoints.size() > 64 || risks.size() > 64
                || missingEvidence.size() > 64 || citations.size() > 128) {
            throw new IllegalArgumentException("Compression content contains too many values");
        }
    }
}
