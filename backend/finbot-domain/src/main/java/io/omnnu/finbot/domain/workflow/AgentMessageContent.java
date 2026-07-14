package io.omnnu.finbot.domain.workflow;

import io.omnnu.finbot.domain.shared.DecimalValue;
import io.omnnu.finbot.domain.shared.DomainText;
import java.math.BigDecimal;
import java.util.List;

public record AgentMessageContent(
        String summary,
        String argument,
        BigDecimal confidence,
        List<AgentClaim> claims,
        List<String> evidenceReferences,
        List<String> challenges,
        List<String> revisionNotes) {
    public AgentMessageContent {
        summary = DomainText.required(summary, "summary", 8_000);
        argument = DomainText.required(argument, "argument", 32_000);
        if (confidence != null) {
            confidence = DecimalValue.nonNegative(confidence, "confidence");
            if (confidence.compareTo(BigDecimal.ONE) > 0) {
                throw new IllegalArgumentException("confidence must not exceed one");
            }
        }
        claims = limitedCopy(claims, "claims", 64);
        evidenceReferences = limitedStrings(evidenceReferences, "evidenceReferences", 128);
        challenges = limitedStrings(challenges, "challenges", 64);
        revisionNotes = limitedStrings(revisionNotes, "revisionNotes", 64);
    }

    private static <T> List<T> limitedCopy(List<T> values, String fieldName, int maximumSize) {
        var copy = List.copyOf(values);
        if (copy.size() > maximumSize) {
            throw new IllegalArgumentException(fieldName + " contains too many values");
        }
        return copy;
    }

    private static List<String> limitedStrings(List<String> values, String fieldName, int maximumSize) {
        var copy = limitedCopy(values, fieldName, maximumSize);
        if (copy.stream().anyMatch(value -> value == null || value.isBlank() || value.length() > 4_000)) {
            throw new IllegalArgumentException(fieldName + " contains an invalid value");
        }
        return copy;
    }
}
