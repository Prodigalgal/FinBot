package io.omnnu.finbot.domain.risk;

import io.omnnu.finbot.domain.shared.DomainText;

public record RiskAssessmentId(String value) {
    public RiskAssessmentId {
        value = DomainText.identifier(value, "assessment_");
    }
}
